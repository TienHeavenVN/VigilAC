#!/usr/bin/env node
/**
 * In-house protocol bot for VigilAC live testing.
 *
 * NOT a real modified client (Meteor/Wurst/LiquidBounce are unavailable in the
 * CI environment — see docs/LIVE_TEST_RESULTS.md). Connects OFFLINE to an
 * isolated Paper server and executes one named scenario per process, then
 * writes a JSON evidence file.
 *
 * Usage: node bot.js <username> <port> <scenario> <out.json>
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const mineflayer = require('mineflayer');
const { scenarios } = require('./scenarios');

const [username, portArg, scenarioId, outPath] = process.argv.slice(2);
const port = parseInt(portArg || '25565', 10);
const baseScenario = scenarioId.replace(/-lat$/, '');
const base = scenarios[baseScenario];
const scenario = base ? { ...base, description: base.description + (baseScenario !== scenarioId ? ' (120ms±30ms latency)' : '') } : undefined;

/** Staff observer: op'd idle bot that captures every [VigilAC debug] chat line. */
if (scenarioId === '__staff__') {
  const bot = mineflayer.createBot({
    host: '127.0.0.1', port, username, version: '1.21.8', auth: 'offline', viewDistance: 'small',
  });
  const stream = fs.createWriteStream(outPath, { flags: 'a' });
  bot.once('spawn', () => {
    rcon(`op ${username}`); // staff observer is op'd: exempt; must not interfere with scenarios
    stream.write(JSON.stringify({ time: Date.now(), msg: 'OBSERVER-READY' }) + '\n');
  });
  bot.on('message', (msg) => {
    stream.write(JSON.stringify({ time: Date.now(), msg: msg.toString() }) + '\n');
  });
  setInterval(() => {}, 60000); // keep alive
  process.on('SIGTERM', () => { stream.end(); process.exit(0); });
  return;
}
if (!scenario && scenarioId !== '__staff__') {
  console.error('unknown scenario:', scenarioId, 'available:', Object.keys(scenarios).join(', '));
  process.exit(2);
}

const RCON_PY = process.env.RCON_PY || path.join(__dirname, '..', '..', 'tools', 'rcon.py');
const RCON_PORT = process.env.RCON_PORT || '25575';
const RCON_PASSWORD = process.env.RCON_PASSWORD || 'vigilac-ci';
const rcon = (command) => {
  const r = spawnSync('python3', [RCON_PY, '127.0.0.1', RCON_PORT, RCON_PASSWORD, command], {
    encoding: 'utf8', timeout: 15000,
  });
  return ((r.stdout || '') + (r.stderr || '')).trim();
};

const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

const result = {
  scenario: scenarioId,
  kind: scenario.kind,
  expect: scenario.expect,
  description: scenario.description,
  username,
  ok: true,
  harnessError: null,
  evidence: [],
  debugLines: [],
  started: new Date().toISOString(),
};

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username,
  version: '1.21.8',
  auth: 'offline',
  viewDistance: 'small',
});

bot.once('spawn', async () => {
  try {
    // NOTE: the scenario bot is deliberately NOT op'd. Our permission defaults
    // grant vigilac.exempt to ops, which would silently exempt the bot from all
    // checks. The RCON console (full permissions) runs /vigilac debug and
    // /vigilac violations with this bot as the *target*.
    rcon(`vigilac debug ${username} on`);
    await sleep(300);
    await scenario.fn(bot, { rcon, sleep, result });
    result.ended = new Date().toISOString();
    // capture VL evidence while the bot is still online (command requires an online player)
    result.vlText = rcon(`vigilac violations ${username}`);
    result.hint = scenario.expect;
  } catch (err) {
    result.ok = false;
    result.harnessError = String((err && err.stack) || err);
  } finally {
    setTimeout(() => {
      try { fs.writeFileSync(outPath, JSON.stringify(result, null, 2)); }
      catch (e) { console.error('failed to write result:', e); process.exit(2); }
      bot.end();
      // give the client a moment to flush the disconnect, then exit
      setTimeout(() => process.exit(result.ok ? 0 : 1), 500);
    }, 1500);
  }
});

bot.on('message', (msg) => {
  const line = msg.toString();
  if (line.includes('VigilAC')) {
    result.debugLines.push(line);
  }
});

bot.on('error', (err) => {
  result.ok = false;
  result.harnessError = String((err && err.stack) || err);
});
bot.on('end', () => {
  try { fs.writeFileSync(outPath, JSON.stringify(result, null, 2)); } catch (_) { /* already written */ }
  process.exit(result.ok ? 0 : 1);
});

setTimeout(() => {
  result.ok = false;
  result.harnessError = 'global scenario timeout (90s)';
  try { fs.writeFileSync(outPath, JSON.stringify(result, null, 2)); } catch (_) { }
  process.exit(1);
}, 90000).unref();
