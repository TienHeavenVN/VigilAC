#!/usr/bin/env node
/**
 * Load-test bot for the VigilAC performance harness (integration/load.sh).
 * Each process is one player on the shared test pad sprint-jumping in a fixed
 * pattern for the given duration, then disconnects and exits.
 *
 * Usage: node loadbot.js <username> <port> <durationMs> <out.json>
 */
'use strict';
const fs = require('fs');
const mineflayer = require('mineflayer');

const [username, portArg, durationArg, outPath] = process.argv.slice(2);
const port = parseInt(portArg || '25565', 10);
const duration = parseInt(durationArg || '30000', 10);

const result = { username, ok: true, kicked: null, error: null, joinedAt: new Date().toISOString() };

const bot = mineflayer.createBot({
  host: '127.0.0.1',
  port,
  username,
  version: '1.21.8',
  auth: 'offline',
  viewDistance: 'tiny',
});

bot.on('kicked', (reason) => {
  result.ok = false;
  try { result.kicked = typeof reason === 'string' ? reason : JSON.stringify(reason); } catch { result.kicked = String(reason); }
});
bot.on('error', (err) => { result.ok = false; result.error = String(err).slice(0, 300); });

bot.once('spawn', async () => {
  const started = Date.now();
  bot.setControlState('sprint', true);
  bot.setControlState('forward', true);
  let jump = false;
  const jumpTimer = setInterval(() => {
    jump = !jump;
    bot.setControlState('jump', jump);
  }, 260);
  setTimeout(() => {
    clearInterval(jumpTimer);
    bot.setControlState('forward', false);
    bot.setControlState('sprint', false);
    bot.setControlState('jump', false);
    result.endedAt = new Date().toISOString();
    result.survivedMs = Date.now() - started;
    try { fs.writeFileSync(outPath, JSON.stringify(result, null, 2)); } catch (e) { console.error('write failed:', e); }
    bot.end();
    setTimeout(() => process.exit(result.ok ? 0 : 1), 500);
  }, duration);
});

setTimeout(() => {
  result.ok = false;
  result.error = 'load bot global timeout';
  try { fs.writeFileSync(outPath, JSON.stringify(result, null, 2)); } catch (e) { }
  process.exit(1);
}, duration + 30000).unref();
