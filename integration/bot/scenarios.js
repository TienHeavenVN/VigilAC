/**
 * Scenario definitions for the VigilAC live test harness.
 *
 * Each scenario runs inside an already-spawned bot. `kind` is 'legit' (no check
 * may show VL afterwards) or 'illegal' (at least the named `expect` checks must
 * show VL > 0). The runner scores outcomes by reading `/vigilac violations`
 * over RCON after all scenarios finish.
 */
'use strict';
const { Vec3 } = require('vec3');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const scenarios = {};
const define = (id, meta, fn) => { scenarios[id] = { id, kind: meta.kind, description: meta.description, expect: meta.expect || [], fn }; };

// ---------- helpers ----------

// ---- ghost position ----
// mineflayer (physics disabled) never updates bot.entity.position, so forged
// packets carrying spawn+dx repeatedly would all be zero-delta on the server.
// Every scenario tracks a "ghost" position, applies deltas to it, and sends
// ABSOLUTE coordinates. The server then sees real incremental movement.
function ghost(bot) {
  if (!bot.__ghost) bot.__ghost = bot.entity.position.clone();
  return bot.__ghost;
}
function setGhost(bot, x, y, z) { bot.__ghost = new Vec3(x, y, z); }

/** Forged position/rotation packet from the ghost. flags 0x01 = onGround. */
function forgeMove(bot, dx, dy, dz, onGround) {
  const g = ghost(bot).plus(new Vec3(dx, dy, dz));
  bot.__ghost = g;
  bot._client.write('position_look', {
    x: g.x, y: g.y, z: g.z,
    yaw: bot.entity.yaw, pitch: bot.entity.pitch,
    flags: onGround ? 1 : 0,
  });
}

function setAirborne(bot) {
  const g = ghost(bot);
  bot._client.write('position_look', { x: g.x, y: g.y, z: g.z, yaw: bot.entity.yaw, pitch: bot.entity.pitch, flags: 0 });
}

/** Summon a zombie relative to the bot; returns the live zombie entity or null. */
async function summonZombieNear(bot, api, offset, name) {
  api.rcon(`execute at ${name} run summon minecraft:zombie ~${offset.x} ~ ~${offset.z}`);
  await api.sleep(1200);
  const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie');
  zombies.sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position));
  return zombies[0] || null;
}

function attack(bot, entity) {
  bot._client.write('use_entity', { target: entity.id, mouse: 1, hand: 0 });
}

// ---------- legitimate scenarios (physics ON) ----------

define('legit-stand', { kind: 'legit', description: 'stand still 5s', expect: [] }, async (bot, api) => {
  api.result.evidence.push('standing still 5s');
  await api.sleep(5000);
});

define('legit-sprint-jump', { kind: 'legit', description: 'sprint-jump forward with turns 6s', expect: [] }, async (bot, api) => {
  bot.setControlState('sprint', true);
  for (const yaw of [0, Math.PI / 2, Math.PI, -Math.PI / 2]) {
    await bot.look(yaw, 0, true);
    bot.setControlState('jump', true);
    bot.setControlState('forward', true);
    await api.sleep(1500);
    bot.setControlState('jump', false);
  }
  bot.setControlState('forward', false);
  bot.setControlState('sprint', false);
});

define('legit-place-bridge', { kind: 'legit', description: 'bridge 5 blocks while looking down-forward', expect: [] }, async (bot, api) => {
  const pos = bot.entity.position.floored();
  // prepare 5 anchor blocks in a line to the south (direction bot faces)
  for (let i = 1; i <= 5; i++) {
    api.rcon(`execute at ${api.result.username} run setblock ~ ~-1 ~${i} dirt`);
  }
  await api.sleep(400);
  // look down-forward (pitch ~ +70°) and place onto the line
  await bot.look(0, 1.1, true);
  const mcData = require('minecraft-data')('1.21.8');
  for (let i = 1; i <= 5; i++) {
    const ref = bot.blockAt(pos.offset(0, -1, i));
    if (ref && ref.name === 'dirt') {
      try { await bot.placeBlock(ref, new Vec3(0, 1, 0)); } catch (e) { api.result.evidence.push('place failed at ' + i + ': ' + e.message); }
    }
    await api.sleep(150);
  }
  api.result.evidence.push('bridge placed');
});

define('legit-break-stone', { kind: 'legit', description: 'dig one stone block barehanded (vanilla ~1.7s)', expect: [] }, async (bot, api) => {
  const pos = bot.entity.position.floored();
  api.rcon(`execute at ${api.result.username} run setblock ~2 ~-1 ~2 stone`);
  await api.sleep(400);
  const target = bot.blockAt(pos.offset(2, -1, 2));
  if (!target || target.name !== 'stone') { api.result.evidence.push('stone not found for dig test'); return; }
  await bot.dig(target);
  api.result.evidence.push('stone dug at vanilla hardness time');
});

define('legit-attack-zombie', { kind: 'legit', description: 'single zombie attack at ~2.5 blocks', expect: [] }, async (bot, api) => {
  const z = await summonZombieNear(bot, api, { x: 2, z: 0 }, api.result.username);
  if (!z) { api.result.evidence.push('zombie did not spawn'); return; }
  await bot.lookAt(z.position.offset(0, 1.0, 0), true);
  attack(bot, z);
  api.result.evidence.push('single attack on zombie id=' + z.id);
  await api.sleep(500);
});

define('legit-knockback', { kind: 'legit', description: 'take two zombie hits (knockback grace must suppress)', expect: [] }, async (bot, api) => {
  const z = await summonZombieNear(bot, api, { x: 1, z: 0 }, api.result.username);
  if (!z) { api.result.evidence.push('zombie did not spawn'); return; }
  await api.sleep(3000); // let the zombie reach and hit us
  api.result.evidence.push('waited for zombie hits');
});

define('legit-respawn', { kind: 'legit', description: 'death and respawn (grace must suppress)', expect: [] }, async (bot, api) => {
  api.rcon(`execute at ${api.result.username} run damage ${api.result.username} 100 minecraft:generic`);
  await api.sleep(1500);
  bot._client.write('client_command', { actionId: 'perform_respawn' });
  await api.sleep(1500);
  api.result.evidence.push('respawned');
});

define('legit-teleport', { kind: 'legit', description: 'server-side upward tp (grace must suppress)', expect: [] }, async (bot, api) => {
  api.rcon(`execute at ${api.result.username} run tp ${api.result.username} ~ ~10 ~`);
  await api.sleep(1000);
  await bot.look(0, 0, true);
  bot.setControlState('forward', true);
  await api.sleep(2000);
  bot.setControlState('forward', false);
  api.result.evidence.push('teleported +10y and walked forward');
});

// ---------- illegal scenarios (physics OFF, forged packets) ----------

define('illegal-timer', { kind: 'illegal', description: 'movement packets at 30/s for 5s', expect: ['timer'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  const end = Date.now() + 6000;
  for (let i = 0; i < 240; i++) {
    forgeMove(bot, 0.05, 0, 0, false);
    await api.sleep(25); // ~40 packets/s sustained — clearly over the 21.5/s gate
    if (Date.now() > end) break;
  }
  api.result.evidence.push('sent ~30 movement packets/s for 5s');
});

define('illegal-overspeed', { kind: 'illegal', description: 'horizontal speed ~6.2 blocks/s for 4s (sprint cap 5.61)', expect: ['speed'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  for (let i = 0; i < 40; i++) {
    forgeMove(bot, 0.62, 0, 0, true);
    await api.sleep(100);
  }
  api.result.evidence.push('moved +0.62 blocks/packet at 10 Hz (~6.2 b/s)');
});

define('illegal-fly-hover', { kind: 'illegal', description: 'constant altitude airborne for 4.5s (fly hover signature)', expect: ['fly'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  // leave the ground once, then hold the altitude
  forgeMove(bot, 0, 1.2, 0, false);
  for (let i = 0; i < 45; i++) {
    forgeMove(bot, 0.01, 0, 0, false);
    await api.sleep(100);
  }
  api.result.evidence.push('held constant altitude airborne for 4.5s');
});

define('illegal-invalid-vertical', { kind: 'illegal', description: 'vertical delta 5.0/packet (terminal velocity 3.92)', expect: ['invalid-movement'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  for (let i = 0; i < 4; i++) {
    forgeMove(bot, 0, 5.0, 0, false);
    await api.sleep(100);
  }
  for (let i = 0; i < 4; i++) {
    forgeMove(bot, 0, -5.0, 0, false);
    await api.sleep(100);
  }
  api.result.evidence.push('vertical delta 5.0 x4');
});

define('illegal-nofall', { kind: 'illegal', description: 'three falls > 7 blocks, no damage events', expect: ['no-fall'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  for (let landing = 0; landing < 3; landing++) {
    const g = ghost(bot);
    const tx = g.x, ty = g.y + 7.5, tz = g.z;
    api.rcon(`tp ${api.result.username} ${tx} ${ty} ${tz}`);
    setGhost(bot, tx, ty, tz);
    await api.sleep(700);
    for (let i = 0; i < 6; i++) {
      forgeMove(bot, 0, -1.2, 0, false);
      await api.sleep(50);
    }
    forgeMove(bot, 0, -0.3, 0, true); // touch down
    await api.sleep(800);
    api.result.evidence.push('fall ' + (landing + 1) + ' completed');
  }
});

define('illegal-reach', { kind: 'illegal', description: '8 attacks on a zombie 5 blocks away', expect: ['reach'] }, async (bot, api) => {
  const z = await summonZombieNear(bot, api, { x: 5, z: 0 }, api.result.username);
  if (!z) { api.result.evidence.push('zombie did not spawn'); return; }
  await api.sleep(500);
  for (let i = 0; i < 8; i++) {
    attack(bot, z);
    await api.sleep(150);
  }
  api.result.evidence.push('8 attacks on zombie at ~5 blocks');
});

define('illegal-autoclick', { kind: 'illegal', description: '25 attacks/s on one zombie (CPS gate)', expect: ['auto-clicker'] }, async (bot, api) => {
  const z = await summonZombieNear(bot, api, { x: 1, z: 0 }, api.result.username);
  if (!z) { api.result.evidence.push('zombie did not spawn'); return; }
  for (let i = 0; i < 45; i++) {
    attack(bot, z);
    await api.sleep(40);
  }
  api.result.evidence.push('45 attacks at 25 CPS');
});

define('illegal-killaura', { kind: 'illegal', description: 'attacks on 4 distinct zombies inside 1s window', expect: ['kill-aura'] }, async (bot, api) => {
  for (const off of [{ x: 2, z: 0 }, { x: -2, z: 0 }, { x: 0, z: 2 }, { x: 0, z: -2 }]) {
    api.rcon(`execute at ${api.result.username} run summon minecraft:zombie ~${off.x} ~ ~${off.z}`);
  }
  await api.sleep(1500);
  const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie').slice(0, 4);
  if (zombies.length < 3) { api.result.evidence.push('fewer than 3 zombies tracked: ' + zombies.length); return; }
  for (let round = 0; round < 3; round++) {
    for (const z of zombies) {
      attack(bot, z);
      await api.sleep(70);
    }
  }
  api.result.evidence.push('attacked ' + zombies.length + ' distinct zombies x3 rounds');
});

define('illegal-scaffold', { kind: 'illegal', description: 'place 8 blocks to the east while facing south (out-of-view)', expect: ['scaffold'] }, async (bot, api) => {
  const pos = bot.entity.position.floored();
  for (let i = 1; i <= 8; i++) {
    api.rcon(`execute at ${api.result.username} run setblock ~${i + 1} ~-1 ~ dirt`); // anchor chain
  }
  await api.sleep(400);
  await bot.look(0, 0.2, true); // face south, slight down pitch
  let placed = 0;
  for (let i = 2; i <= 9; i++) {
    const ref = bot.blockAt(pos.offset(i, -1, 0));
    if (ref) {
      try { await bot.placeBlock(ref, new Vec3(0, 1, 0)); placed++; } catch (e) { /* keep going */ }
    }
  }
  api.result.evidence.push('placed ' + placed + ' blocks to the side while facing away');
});

define('illegal-fastplace', { kind: 'illegal', description: 'place blocks faster than the 5/s cooldown ceiling', expect: ['fast-place'] }, async (bot, api) => {
  const pos = bot.entity.position.floored();
  for (let i = 1; i <= 14; i++) {
    api.rcon(`execute at ${api.result.username} run setblock ~ ~-1 ~${i} dirt`);
  }
  await api.sleep(400);
  await bot.look(0, 1.0, true);
  let placed = 0;
  const started = Date.now();
  for (let i = 1; i <= 14 && Date.now() - started < 3000; i++) {
    const ref = bot.blockAt(pos.offset(0, -1, i));
    if (ref) {
      try { await bot.placeBlock(ref, new Vec3(0, 1, 0)); placed++; } catch (e) { /* ignore */ }
    }
  }
  api.result.evidence.push('placed ' + placed + ' blocks in ' + (Date.now() - started) + 'ms');
});

define('illegal-fastbreak', { kind: 'illegal', description: 'instant-break stone 4x (start+stop 20ms apart)', expect: ['fast-break'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  const pos = bot.entity.position.floored();
  const faces = 1; // up face
  for (let i = 1; i <= 4; i++) {
    api.rcon(`execute at ${api.result.username} run setblock ~${i} ~-1 ~ stone`);
  }
  await api.sleep(400);
  for (let i = 1; i <= 4; i++) {
    const loc = { x: pos.x + i, y: pos.y - 1, z: pos.z };
    bot._client.write('block_dig', { status: 0, location: loc, face: faces, sequence: 1000 + i });
    await api.sleep(20);
    bot._client.write('block_dig', { status: 2, location: loc, face: faces, sequence: 1000 + i });
    await api.sleep(120);
  }
  api.result.evidence.push('broke 4 stone blocks with 20ms dig cycles');
});

define('illegal-aimassist', { kind: 'illegal', description: 'four 180-degree snaps landing on targets', expect: ['aim-assist'] }, async (bot, api) => {
  for (const off of [{ x: 2, z: 0 }, { x: -2, z: 0 }]) {
    api.rcon(`execute at ${api.result.username} run summon minecraft:zombie ~${off.x} ~ ~${off.z}`);
  }
  await api.sleep(1500);
  const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie').slice(0, 2);
  if (zombies.length < 2) { api.result.evidence.push('zombies tracked: ' + zombies.length); return; }
  const eye = () => bot.entity.position.y + 1.62;
  for (let snap = 0; snap < 6; snap++) {
    const z = zombies[snap % 2];
    const dx = z.position.x - bot.entity.position.x;
    const dz = z.position.z - bot.entity.position.z;
    const dist = Math.hypot(dx, dz);
    const bearing = Math.atan2(-dx, dz);
    const pitch = -Math.atan2(eye() - (z.position.y + 0.975), dist);
    await bot.look(bearing + Math.PI, 0, true);
    await api.sleep(30);
    await bot.look(bearing, pitch, true);
    attack(bot, z);
    await api.sleep(60);
  }
  api.result.evidence.push('6 alternating 180-degree snaps onto zombies');
});

module.exports = { scenarios };
