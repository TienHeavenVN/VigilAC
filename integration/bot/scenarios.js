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
  api.rcon(`execute at ${name} run summon minecraft:zombie ~${offset.x} ~ ~${offset.z} {NoAI:1b}`);
  await api.sleep(1200);
  const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie');
  zombies.sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position));
  return zombies[0] || null;
}

function attack(bot, entity) {
  bot._client.write('use_entity', { target: entity.id, mouse: 1, hand: 0 });
}

// 1.21.2+ validates attack cooldown AND look direction server-side: attacks
// thrown while the crosshair is nowhere near the target deal no damage and
// emit no EntityDamageByEntityEvent. Aim first, then hit.
// MC pitch convention: positive = looking DOWN.
async function lookAndAttack(bot, entity) {
  const eye = bot.entity.position.y + 1.62;
  const dx = entity.position.x - bot.entity.position.x;
  const dz = entity.position.z - bot.entity.position.z;
  const dist = Math.hypot(dx, dz);
  const targetY = entity.position.y + (entity.height || 1.95) * 0.5;
  const bearing = Math.atan2(-dx, dz);
  const pitch = Math.atan2(eye - targetY, dist);
  try { await bot.look(bearing, pitch, true); } catch (_) { /* physics disabled: best effort */ }
  attack(bot, entity);
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

define('illegal-overspeed', { kind: 'illegal', description: '6.4 b/s horizontal walk (above 4.4 walk cap, under Paper revert threshold)', expect: ['speed'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  const end = Date.now() + 10000;
  while (Date.now() < end) {
    forgeMove(bot, 0.32, 0, 0, true);
    await api.sleep(50);
  }
  api.result.evidence.push('moved +0.32 blocks/packet at 20 Hz (~6.4 b/s) for 10s');
});


define('illegal-timer', { kind: 'illegal', description: 'alternating 0.62-block deltas at ~25/s for 6s (proven to produce timer evidence)', expect: ['timer'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  const end = Date.now() + 6000;
  let count = 0, fwd = true;
  const t0 = Date.now();
  while (Date.now() < end) {
    forgeMove(bot, fwd ? 0.62 : -0.62, 0, 0, false);
    fwd = !fwd;
    count++;
    await api.sleep(40);
  }
  api.result.evidence.push('sent ' + count + ' big-delta packets over ' + (Date.now() - t0) + 'ms (~' + Math.round(count / ((Date.now() - t0) / 1000)) + '/s)');
});

define('illegal-fly-hover', { kind: 'illegal', description: 'constant altitude airborne for 4.5s (fly hover signature)', expect: ['fly'] }, async (bot, api) => {
  bot.physicsEnabled = false;
  const g0 = ghost(bot);
  api.rcon(`tp ${api.result.username} ${g0.x} ${g0.y + 8} ${g0.z}`);
  setGhost(bot, g0.x, g0.y + 8, g0.z);
  await api.sleep(700);
  for (let i = 0; i < 50; i++) {
    forgeMove(bot, 0.01, 0, 0, false);
    await api.sleep(100);
  }
  api.result.evidence.push('hovered airborne ~5s at constant altitude (net vertical ~0)');
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

define('illegal-nofall', { kind: 'probe', description: 'fall-damage cancellation cannot be emulated with vanilla tooling: resistance-V zero-damage landings still fire EntityDamageEvent (treated as legitimate evidence the damage applied), and any vanilla-silent suppression path (slime, water, levitation, feather falling) is exempted by design. UNABLE-TO-TEST; the check logic itself is covered by unit tests.', expect: [] }, async (bot, api) => {
  const name = api.result.username;
  const fx = api.rcon(`effect give ${name} minecraft:resistance 9999 4 true`);
  api.result.evidence.push('resistance give -> ' + String(fx).replace(/\n/g, ' ').slice(0, 200));
  await api.sleep(300);
  for (let landing = 0; landing < 3; landing++) {
    const pos = bot.entity.position;
    api.rcon(`tp ${name} ${pos.x} ${pos.y + 5} ${pos.z}`);
    await api.sleep(2300); // teleport grace (2s) expires before the fall
    bot.physicsEnabled = true;
    await api.sleep(1500); // ~0.6s 5-block free fall, land, settle
    bot.physicsEnabled = false;
    api.result.evidence.push('fall ' + (landing + 1) + ' completed (probe of stock-server landing behavior)');
  }
  api.result.evidence.push('probe complete: see docs/LIVE_TEST_RESULTS.md (no-fall UNABLE-TO-TEST)');
});

define('illegal-reach', { kind: 'illegal', description: 'attacks beyond 3.0+tolerance: zombie HP verified before/after', expect: ['reach'] }, async (bot, api) => {
  const name = api.result.username;
  for (const dist of [3.6, 4.5, 5.0]) {
    api.rcon(`execute at ${name} run summon minecraft:zombie ~${dist} ~ ~ {NoAI:1b}`);
      }
  await api.sleep(1000);
  for (let i = 0; i < 3; i++) {
    const before = api.rcon(`execute at ${name} run data get entity @e[type=zombie,sort=nearest,limit=1] Health`);
    const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie');
    zombies.sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position));
    const z = zombies[i];
    if (!z) { api.result.evidence.push('zombie#' + i + ' not tracked client-side'); continue; }
    for (let j = 0; j < 10; j++) { await lookAndAttack(bot, z); await api.sleep(120); }
    const after = api.rcon(`execute at ${name} run data get entity @e[type=zombie,sort=nearest,limit=1] Health`);
    api.result.evidence.push('zombie#' + i + ' (~' + [3.6, 4.5, 5.0][i] + 'b): hpBefore=[' + before.slice(-40) + '] hpAfter=[' + after.slice(-40) + ']');
  }
});

define('illegal-autoclick', { kind: 'illegal', description: 'metronomic ~25 CPS on a single victim, re-summoning only after it dies (kill-aura isolated)', expect: ['auto-clicker'] }, async (bot, api) => {
  const name = api.result.username;
  api.rcon(`execute at ${name} run summon minecraft:zombie ~2 ~ ~ {NoAI:1b}`);
  await api.sleep(400);
    await api.sleep(400);
  const end = Date.now() + 12000;
  let attacks = 0, resummons = 0;
  while (Date.now() < end) {
    const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie');
    if (zombies.length === 0) {
      api.rcon(`execute at ${name} run summon minecraft:zombie ~2 ~ ~ {NoAI:1b}`);
      resummons++;
      await api.sleep(600);
      continue;
    }
    zombies.sort((a, b) => a.position.distanceTo(bot.entity.position) - b.position.distanceTo(bot.entity.position));
    await lookAndAttack(bot, zombies[0]);
    attacks++;
    await api.sleep(40);
  }
  api.result.evidence.push(attacks + ' attacks in 12s (~' + Math.round(attacks / 12) + ' CPS), resummons=' + resummons);
});

define('illegal-killaura', { kind: 'illegal', description: 'attacks on 4 distinct zombies inside 1s window (HP verified)', expect: ['kill-aura'] }, async (bot, api) => {
  const name = api.result.username;
  for (const off of [{ x: 2, z: 0 }, { x: -2, z: 0 }, { x: 0, z: 2 }, { x: 0, z: -2 }]) {
    api.rcon(`execute at ${name} run summon minecraft:zombie ~${off.x} ~ ~${off.z} {NoAI:1b}`);
  }
    await api.sleep(1500);
  const before = api.rcon(`execute at ${name} run data get entity @e[type=zombie,sort=nearest,limit=1] Health`);
  const zombies = Object.values(bot.entities).filter((e) => e.name === 'zombie').slice(0, 4);
  if (zombies.length < 3) { api.result.evidence.push('fewer than 3 zombies tracked: ' + zombies.length); return; }
  for (let round = 0; round < 3; round++) {
    for (const z of zombies) {
      await lookAndAttack(bot, z);
      await api.sleep(70);
    }
  }
  const after = api.rcon(`execute at ${name} run data get entity @e[type=zombie,sort=nearest,limit=1] Health`);
  api.result.evidence.push('zombie HP before=[' + before.slice(-120) + '] after=[' + after.slice(-120) + ']');
});

define('illegal-scaffold', { kind: 'illegal', description: 'stack 4 blocks on each of 2 pillars behind the player while facing exactly away (angle ~180deg)', expect: ['scaffold'] }, async (bot, api) => {
  const name = api.result.username;
  api.rcon(`give ${name} minecraft:dirt 64`);
  await api.sleep(200);
  const dirt = bot.inventory.items().find((item) => item.name === 'dirt');
  if (dirt) { try { await bot.equip(dirt, 'hand'); } catch (e) { api.result.evidence.push('equip failed: ' + e.message); } } else { api.result.evidence.push('dirt not in inventory after give'); }
  api.rcon(`execute at ${name} run setblock ~2 ~-1 ~1 dirt`);
  api.rcon(`execute at ${name} run setblock ~2 ~-1 ~2 dirt`);
  await api.sleep(400);
  const pos = bot.entity.position.floored();
  await bot.look(Math.PI / 2, 0, true); // face west (-x): pillars are directly behind; angle ~180deg
  let placed = 0;
  const errors = [];
  for (const dz of [1, 2]) {
    for (let i = 0; i < 4 && placed < 8; i++) {
      const ref = bot.blockAt(pos.offset(2, -1 + placed % 4, dz));
      if (!ref) { errors.push('null@' + dz + ',' + i); break; }
      try { await bot.placeBlock(ref, new Vec3(0, 1, 0)); placed++; } catch (e) { errors.push(String((e && e.message) || e).slice(0, 160)); break; }
    }
  }
  api.result.evidence.push('placed ' + placed + ' blocks behind the player (angle ~180deg)' + (errors.length ? ' errors=[' + errors.join(' || ') + ']' : ''));
});

define('illegal-fastplace', { kind: 'illegal', description: 'stack 5 blocks on each of 4 in-reach pillars (20 placements, >7/s)', expect: ['fast-place'] }, async (bot, api) => {
  const name = api.result.username;
  api.rcon(`give ${name} minecraft:dirt 64`);
  await api.sleep(200);
  const dirt = bot.inventory.items().find((item) => item.name === 'dirt');
  if (dirt) { try { await bot.equip(dirt, 'hand'); } catch (e) { api.result.evidence.push('equip failed: ' + e.message); } } else { api.result.evidence.push('dirt not in inventory after give'); }
  api.rcon(`execute at ${name} run setblock ~2 ~-1 ~1 dirt`);
  api.rcon(`execute at ${name} run setblock ~2 ~-1 ~2 dirt`);
  api.rcon(`execute at ${name} run setblock ~3 ~-1 ~1 dirt`);
  api.rcon(`execute at ${name} run setblock ~3 ~-1 ~2 dirt`);
  await api.sleep(400);
  const pos = bot.entity.position.floored();
  await bot.look(-Math.PI / 2, 0, true); // face the pillars (+x): bearing fine, rate is the signal
  const pillars = [[2, 1], [2, 2], [3, 1], [3, 2]];
  let placed = 0;
  const errors = [];
  const started = Date.now();
  for (const [dx, dz] of pillars) {
    for (let i = 0; i < 5 && Date.now() - started < 6000; i++) {
      const ref = bot.blockAt(pos.offset(dx, -1 + (i === 0 ? 0 : i), dz));
      if (!ref) { errors.push('null@' + dx + ',' + dz + ',' + i); break; }
      try { await bot.placeBlock(ref, new Vec3(0, 1, 0)); placed++; } catch (e) {
        errors.push(String((e && e.message) || e).slice(0, 160));
        break;
      }
    }
  }
  if (placed > 0) {
    const top = pos.offset(2, 4, 1); // pillar 1 apex after 5 stacks
    api.rcon(`execute if block ${top.x} ${top.y} ${top.z} minecraft:dirt run say DIRT_STACK_VERIFIED`);
  }
  api.result.evidence.push('placed ' + placed + ' blocks in ' + (Date.now() - started) + 'ms' + (errors.length ? ' errors=[' + errors.slice(0, 2).join(' || ') + ']' : '') + ' (stack verified via chat DIRT_STACK_VERIFIED if present)');
});

define('fast-break-probe', { kind: 'probe', description: 'start+stop_destroy 20ms apart on floating stone; verify block survives (server dig-progress rule)', expect: [] }, async (bot, api) => {
  bot.physicsEnabled = false;
  const name = api.result.username;
  const g = ghost(bot);
  const bx = Math.floor(g.x), by = Math.floor(g.y) + 1, bz = Math.floor(g.z) + 2;
  for (let i = 0; i < 6; i++) {
    api.rcon(`setblock ${bx} ${by} ${bz} minecraft:stone`);
    await api.sleep(250);
    bot._client.write('block_dig', { status: 0, location: { x: bx, y: by, z: bz }, face: 1, sequence: 100 + i });
    await api.sleep(20);
    bot._client.write('block_dig', { status: 2, location: { x: bx, y: by, z: bz }, face: 1, sequence: 200 + i });
    await api.sleep(300);
    api.rcon(`execute if block ${bx} ${by} ${bz} minecraft:stone run say STONE_SURVIVED`);
    await api.sleep(120);
  }
  api.result.evidence.push('6x 20ms dig bursts on floating stone sent; survival verifiable via [Rcon] STONE_SURVIVED chat lines above');
});

define('illegal-aimassist', { kind: 'illegal', description: 'four 180-degree snaps landing on targets', expect: ['aim-assist'] }, async (bot, api) => {
  for (const off of [{ x: 2, z: 0 }, { x: -2, z: 0 }]) {
    api.rcon(`execute at ${api.result.username} run summon minecraft:zombie ~${off.x} ~ ~${off.z} {NoAI:1b}`);
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
    const pitch = Math.atan2(eye() - (z.position.y + 0.975), dist);
    await bot.look(bearing + Math.PI, 0, true);
    // snap-max-interval is 120ms server-side: run look-snap and attack back to
    // back so consecutive attacks land inside one snap window (zombies frozen).
    await bot.look(bearing, pitch, true);
    attack(bot, z);
    await api.sleep(20);
  }
  const hp = api.rcon(`execute at ${api.result.username} run data get entity @e[type=zombie,sort=nearest,limit=1] Health`);
  api.result.evidence.push('6 alternating 180-degree snaps onto zombies; zombie HP=[' + hp.slice(-80) + ']');
});

module.exports = { scenarios };
