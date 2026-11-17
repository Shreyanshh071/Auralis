#!/usr/bin/env node
// End-to-end smoke test of a deployed staging Worker: existing Connect behaviour, then the account
// library with synthetic test identities (staging-<id>, which can never be real users) and a
// synthetic library. No Firebase account, cookie or personal data is involved.
//
//   node tools/staging-smoke.mjs
//
// Reads from the environment or .measure.env (both ignored by Git):
//   AURALIS_STAGING_URL       https://auralis-connect-staging.<subdomain>.workers.dev  (or http://127.0.0.1:<port> locally)
//   AURALIS_STAGING_SECRET    the staging Worker's STAGING_SECRET
//   LIBRARY_ADMIN_SECRET      the staging Worker's LIBRARY_ADMIN_SECRET
// The staging Worker needs LIBRARY_PILOT_UIDS=staging-* (see docs/library-sync/pilot.md).
// Prints step results only: never secrets, tokens or library content.
import { existsSync, readFileSync } from 'node:fs';
import WebSocket from 'ws';
import { checksum, fromUserDocument } from '../src/library-core.js';

if (existsSync('.measure.env')) {
  for (const line of readFileSync('.measure.env', 'utf8').split('\n')) {
    const at = line.indexOf('=');
    if (at > 0 && !process.env[line.slice(0, at)]) process.env[line.slice(0, at)] = line.slice(at + 1).trim();
  }
}
// Falls back to what `measure.mjs secret` saved: AURALIS_MEASURE_URL (wss://) and AURALIS_MEASURE_TOKEN.
const base = (process.env.AURALIS_STAGING_URL ?? (process.env.AURALIS_MEASURE_URL ?? '').replace(/^ws/, 'http')).replace(/\/$/, '');
const secret = process.env.AURALIS_STAGING_SECRET ?? (/^staging:([^:]+):/.exec(process.env.AURALIS_MEASURE_TOKEN ?? '')?.[1] ?? '');
const adminSecret = process.env.LIBRARY_ADMIN_SECRET ?? '';
if (!base || !secret || !adminSecret) { console.error('Set AURALIS_STAGING_URL, AURALIS_STAGING_SECRET and LIBRARY_ADMIN_SECRET'); process.exit(2); }
const local = /^http:\/\/(127\.0\.0\.1|localhost)[:/]/.test(base);
if (!base.startsWith('https://') && !local) { console.error('Refusing to send secrets over plain http to a remote host'); process.exit(2); }
if (/auralis-connect\.[^.]+\.workers\.dev/.test(base) && !base.includes('staging')) { console.error('This looks like production: refusing'); process.exit(2); }
const wsBase = base.replace(/^http/, 'ws');
const run = Date.now().toString(36);
const id = (name) => `smoke-${run}-${name}`.slice(0, 40);
const token = (name) => `staging:${secret}:${id(name)}`;
const uidOf = (name) => `staging-${id(name)}`;

let failed = 0;
async function step(name, work) {
  const started = performance.now();
  try { await work(); console.log(`PASS ${name} (${Math.round(performance.now() - started)} ms)`); }
  catch (e) { failed += 1; console.log(`FAIL ${name}: ${e.message}`); }
}
function check(condition, message) { if (!condition) throw new Error(message); }

function socket(path, who, device) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${wsBase}${path}?device=${device}`, { headers: { authorization: `Bearer ${token(who)}` } });
    const s = { ws, frames: [], waiters: [], next: 1 };
    ws.on('message', (data) => {
      if (data.toString() === 'pong') { s.frames.push({ t: 'pong' }); }
      else s.frames.push(JSON.parse(data.toString()));
      s.waiters = s.waiters.filter((w) => { const f = s.frames.find(w.match); if (!f) return true; s.frames.splice(s.frames.indexOf(f), 1); w.resolve(f); return false; });
    });
    ws.once('open', () => resolve(s));
    ws.once('unexpected-response', (_, res) => reject(new Error(`socket refused: HTTP ${res.statusCode}`)));
    ws.once('error', reject);
  });
}
function wait(s, match, ms = 8000) {
  const found = s.frames.find(match);
  if (found) { s.frames.splice(s.frames.indexOf(found), 1); return Promise.resolve(found); }
  return new Promise((resolve, reject) => {
    const entry = { match, resolve: (f) => { clearTimeout(timer); resolve(f); } };
    const timer = setTimeout(() => { s.waiters = s.waiters.filter((w) => w !== entry); reject(new Error('timed out waiting for a frame')); }, ms);
    s.waiters.push(entry);
  });
}
function call(s, frame) { const r = s.next++; s.ws.send(JSON.stringify({ ...frame, r })); return wait(s, (f) => f.r === r); }

const admin = async (uid, action, body = {}, key = adminSecret) => {
  const response = await fetch(`${base}/v1/library/admin/${uid}/${action}`, { method: 'POST', headers: { 'x-library-admin': key }, body: JSON.stringify(body) });
  return { status: response.status, body: response.status === 404 ? null : await response.json() };
};
const rpc = async (who, frame, device = 'smoke-pc') => {
  const response = await fetch(`${base}/v1/library/rpc?device=${device}`, { method: 'POST', headers: { authorization: `Bearer ${token(who)}` }, body: JSON.stringify(frame) });
  if (response.status !== 200) throw new Error(`rpc HTTP ${response.status}`);
  return response.json();
};
async function fullPull(who) {
  const out = { playlists: [], items: [] };
  let page;
  for (;;) {
    const d = await rpc(who, { t: 'pull', full: true, page });
    check(d.t === 'delta', `pull answered ${d.t}/${d.code}`);
    out.playlists.push(...d.playlists); out.items.push(...d.items);
    if (!d.more) return out;
    page = d.more;
  }
}
const song = (sid) => ({ id: `synthetic-${sid}`, title: `Synthetic song ${sid}`, artist: 'Test Artist', album: null, thumbnail: '', duration: 200 });
const likes = (snapshot) => snapshot.items.filter((i) => i.k === 'f').sort((a, b) => a.pos - b.pos).map((i) => i.id.replace('synthetic-', ''));

async function migrateSynthetic(who) {
  const { library } = fromUserDocument({
    playlists: [
      { id: 'pl-road', title: 'Synthetic road trip', createdAt: 1_700_000_000_000, isCustom: true, tracks: [song('a'), song('b'), song('a')] },
      { id: 'imported:youtube_music:PLsynthetic', title: 'Synthetic import', createdAt: 1_700_000_100_000, isCustom: false, tracks: [song('y')] },
    ],
    favorites: Array.from({ length: 12 }, (_, i) => song(`f${i}`)),
    savedAlbums: [{ id: 'synthetic-album', title: 'Synthetic album', savedAt: 1 }],
    savedArtists: [{ id: 'synthetic-artist', name: 'Synthetic artist' }],
    librarySync: { pins: ['pl-road'], pinsAt: 5, deleted: [] },
  });
  const sum = await checksum(library);
  const begun = await admin(uidOf(who), 'begin');
  check(begun.status === 200, `begin answered ${begun.status} ${begun.body?.error ?? ''}`);
  const imported = await admin(uidOf(who), 'import', { library, checksum: sum, source: { exists: true, synthetic: true } });
  check(imported.status === 200 && imported.body.checksum === sum, `import answered ${imported.status} ${imported.body?.error ?? ''}`);
  const committed = await admin(uidOf(who), 'commit', { checksum: sum });
  check(committed.body?.state === 'cloudflare', 'commit did not flip the account');
  return { epoch: committed.body.epoch, sum };
}

console.log(`Target: ${local ? 'local server' : 'staging Worker'}; run id ${run}`);

// ---- Existing Connect --------------------------------------------------------------------------------
await step('Connect: two devices see state, presence and a write', async () => {
  const phone = await socket('/v1/connect', 'connect', 'smoke-phone');
  await wait(phone, (f) => f.t === 'state');
  const device = (d) => ({ id: d, name: d, nickname: null, platform: 'ANDROID', type: 'PHONE', appVersion: 'smoke', canPlay: true, online: true, createdAt: 1 });
  check((await call(phone, { t: 'hello', d: device('smoke-phone') })).t === 'ok', 'hello refused');
  const pc = await socket('/v1/connect', 'connect', 'smoke-pc');
  const state = await wait(pc, (f) => f.t === 'state');
  check(state.dv.some((d) => d.id === 'smoke-phone'), 'the second device does not see the first');
  const now = Date.now();
  const session = { version: 1, ownerId: 'smoke-phone', ownerName: 'Smoke', ownerType: 'PHONE', epoch: 1, leaseUntil: now + 60_000, track: { id: 's' },
    playing: true, buffering: false, positionMs: 0, positionAt: now, durationMs: 180_000, queueRev: 1, queueIndex: 0, shuffle: false, repeat: 'OFF',
    context: null, transfer: null, commands: {}, updatedBy: 'smoke-phone' };
  const ack = await call(phone, { t: 'update', base: 0, s: session });
  check(ack.t === 'ok', `update answered ${ack.t}/${ack.code}`);
  const pushed = await wait(pc, (f) => f.t === 'session');
  check(pushed.s.version === 1, 'the write did not reach the other device');
  phone.ws.send('ping');
  await wait(phone, (f) => f.t === 'pong');
  phone.ws.close(); pc.ws.close();
});

// ---- Library: access control ------------------------------------------------------------------------
await step('Library: an untouched account is legacy and refuses library writes', async () => {
  const answer = await (await fetch(`${base}/v1/library/authority`, { headers: { authorization: `Bearer ${token('fresh')}` } })).json();
  check(answer.state === 'legacy' && answer.epoch === 0, `authority answered ${JSON.stringify(answer)}`);
  const refused = await rpc('fresh', { t: 'push', v: 1, epoch: 0, ops: [{ id: 'x', t: 'fav.put', v: song('x') }] });
  check(refused.code === 'authority', 'a legacy account accepted a library write');
});
await step('Library: no token, a bad token, and admin without the secret are refused', async () => {
  check((await fetch(`${base}/v1/library/authority`)).status === 401, 'no token was accepted');
  check((await fetch(`${base}/v1/library/authority`, { headers: { authorization: 'Bearer nonsense' } })).status === 401, 'a bad token was accepted');
  check((await admin(uidOf('fresh'), 'status', {}, 'wrong-secret')).status === 404, 'admin answered without the secret');
});
await step('Library: an account outside the pilot allowlist cannot be moved', async () => {
  const refused = await admin('notAStagingUid123', 'begin');
  check(refused.status === 403, `begin for a non-staging uid answered ${refused.status}`);
});

// ---- Library: two devices of one account ----------------------------------------------------------
let migrated;
await step('Library: synthetic account migrates with exact checksum', async () => { migrated = await migrateSynthetic('main'); });
await step('Library: fresh device restores everything in order', async () => {
  const all = await fullPull('main');
  const road = all.playlists.find((p) => p.id === 'pl-road');
  check(road && road.tracks.map((t) => t.id.replace('synthetic-', '')).join() === 'a,b,a', 'playlist order or repeated song lost');
  check(all.playlists.find((p) => p.id === 'imported:youtube_music:PLsynthetic')?.isCustom === false, 'imported playlist metadata lost');
  check(likes(all).length === 12, `expected 12 likes, got ${likes(all).length}`);
});
await step('Library: an edit on one device is pushed to the other with its contents', async () => {
  const other = await socket('/v1/library', 'main', 'smoke-desktop-2');
  check((await call(other, { t: 'hello', v: 1 })).state === 'cloudflare', 'second device not on the library service');
  const road = (await fullPull('main')).playlists.find((p) => p.id === 'pl-road');
  const ack = await rpc('main', { t: 'push', v: 1, epoch: migrated.epoch, ops: [
    { id: `${run}-like`, t: 'fav.put', v: song('new') },
    { id: `${run}-unlike`, t: 'fav.del', key: 'synthetic-f0' },
    { id: `${run}-reorder`, t: 'pl.put', ifRev: road.rev, v: { id: 'pl-road', title: 'Synthetic road trip', createdAt: 1_700_000_000_000, isCustom: true },
      tracks: [song('b'), song('a'), song('a'), song('c')] },
  ] }, 'smoke-desktop-1');
  check(ack.results.every((r) => r.s === 'ok'), `push results ${ack.results.map((r) => r.s)}`);
  const changed = await wait(other, (f) => f.t === 'changed');
  check(changed.by === 'smoke-desktop-1' && changed.delta?.playlists?.[0]?.tracks?.length === 4, 'the other device was not told what changed');
  const dup = await rpc('main', { t: 'push', v: 1, epoch: migrated.epoch, ops: [{ id: `${run}-like`, t: 'fav.put', v: song('new') }] }, 'smoke-desktop-1');
  check(dup.results[0].s === 'dup', 'a resent operation was applied twice');
  other.ws.close();
});
await step('Library: concurrent edit to one playlist is refused as stale, then merges', async () => {
  const road = (await fullPull('main')).playlists.find((p) => p.id === 'pl-road');
  const meta = { id: 'pl-road', title: 'Synthetic road trip', createdAt: 1_700_000_000_000, isCustom: true };
  const first = await rpc('main', { t: 'push', v: 1, epoch: migrated.epoch, ops: [{ id: `${run}-c1`, t: 'pl.put', ifRev: road.rev, v: meta, tracks: [...road.tracks, song('d1')] }] }, 'smoke-desktop-1');
  const second = await rpc('main', { t: 'push', v: 1, epoch: migrated.epoch, ops: [{ id: `${run}-c2`, t: 'pl.put', ifRev: road.rev, v: meta, tracks: [...road.tracks, song('d2')] }] }, 'smoke-desktop-2');
  check(first.results[0].s === 'ok' && second.results[0].s === 'stale', `expected ok then stale, got ${first.results[0].s}, ${second.results[0].s}`);
});
await step('Library: an idle connection does no database work', async () => {
  const s = await socket('/v1/library', 'main', 'smoke-idle');
  const before = (await call(s, { t: 'stats' })).rows;
  for (let i = 0; i < 5; i += 1) { s.ws.send('ping'); await wait(s, (f) => f.t === 'pong'); await new Promise((r) => setTimeout(r, 400)); }
  const after = (await call(s, { t: 'stats' })).rows;
  check(after.read === before.read && after.written === before.written, `idle read ${after.read - before.read}, wrote ${after.written - before.written}`);
  s.ws.close();
});

// ---- Library: export, restore, rollback, deletion --------------------------------------------------
await step('Library: export verifies, and restore puts it back exactly', async () => {
  const exported = (await admin(uidOf('main'), 'export')).body;
  check(await checksum(exported.library) === exported.checksum, 'export does not match its checksum');
  await rpc('main', { t: 'push', v: 1, epoch: (await admin(uidOf('main'), 'status')).body.epoch, ops: [{ id: `${run}-later`, t: 'fav.put', v: song('later') }] });
  check((await admin(uidOf('main'), 'rollback-begin')).status === 200, 'freeze failed');
  const restored = await admin(uidOf('main'), 'restore', { library: exported.library, checksum: exported.checksum });
  check(restored.status === 200 && restored.body.checksum === exported.checksum, 'restore did not reproduce the export');
  check((await admin(uidOf('main'), 'rollback-abort')).body.state === 'cloudflare', 'could not unfreeze');
  check(!likes(await fullPull('main')).includes('later'), 'the edit after the export survived the restore');
});
await step('Library: rollback freezes writes, exports the latest library and returns to legacy', async () => {
  const s = await socket('/v1/library', 'main', 'smoke-watch');
  await call(s, { t: 'hello', v: 1 });
  check((await admin(uidOf('main'), 'rollback-begin')).status === 200, 'freeze failed');
  check((await wait(s, (f) => f.t === 'authority')).state === 'migrating', 'the watching device was not told');
  check((await rpc('main', { t: 'push', v: 1, epoch: 99, ops: [{ id: `${run}-frozen`, t: 'fav.put', v: song('z') }] })).code !== undefined, 'a frozen account accepted a write');
  const exported = (await admin(uidOf('main'), 'export')).body;
  check(await checksum(exported.library) === exported.checksum, 'rollback export does not verify');
  check((await admin(uidOf('main'), 'rollback-commit', { checksum: exported.checksum })).body.state === 'legacy', 'rollback did not finish');
  s.ws.close();
});
await step('Library: account deletion erases the library and blocks re-upload', async () => {
  const { epoch } = await migrateSynthetic('deleted');
  check((await rpc('deleted', { t: 'erase', v: 1, epoch })).t === 'erased', 'erase refused');
  const left = (await admin(uidOf('deleted'), 'verify')).body;
  check(left.counts.favorites === 0 && left.counts.playlists === 0, 'library left behind after erase');
  check((await rpc('deleted', { t: 'push', v: 1, epoch, ops: [{ id: `${run}-again`, t: 'fav.put', v: song('x') }] })).code === 'authority', 'an erased account accepted a write');
});

console.log(failed ? `${failed} step(s) FAILED` : 'All steps passed');
process.exit(failed ? 1 : 0);
