// The per-account cutover, end to end on this machine: the Firestore emulator with the real
// security rules, and the real Worker and LibraryAccount object in workerd. Nothing leaves it.
//   cd firestore-tests && npm install && (cd ../connect-server && npm ci) && npm test
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { Retryable, abort, firestoreClient, libraryClient, migrate, readExport, report, restore, rollback, status } from '../connect-server/tools/library-migrate.mjs';
import { startServer, stopServer } from '../connect-server/test/harness.mjs';

const PROJECT = 'demo-auralis';
const HOST = '127.0.0.1:8085';
const PORT = 8794;
const BASE = `http://127.0.0.1:${PORT}`;
const ROOT = `projects/${PROJECT}/databases/(default)/documents`;
let env;
let server;
let users = 0;
const user = () => `mig${Date.now()}x${users++}`;

const firestore = firestoreClient({ projectId: PROJECT, emulatorHost: HOST });
const library = libraryClient({ url: BASE, secret: 'local-admin' });
// Every test account is on the allowlist unless a test says otherwise.
const everyone = { has: () => true };
const deps = (extra = {}) => ({ firestore, library, settleMs: 0, snapshotDir: mkdtempSync(join(tmpdir(), 'auralis-snapshots-')), allowlist: everyone, ...extra });

before(async () => {
  env = await initializeTestEnvironment({ projectId: PROJECT, firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') } });
  const here = process.cwd();
  process.chdir(new URL('../connect-server', import.meta.url).pathname);
  try { server = await startServer(PORT, undefined, { LIBRARY_AUTO_IMPORT: 'true', LIBRARY_AUTO_UIDS: '*',
    LIBRARY_FIRESTORE_EMULATOR: `http://${HOST}`, FIREBASE_PROJECT_ID: PROJECT }); } finally { process.chdir(here); }
}, { timeout: 180_000 });
after(async () => { await stopServer(server); await env.cleanup(); });

const song = (id) => ({ id, title: `Song ${id}`, artist: 'A', album: null, thumbnail: 't', duration: 180 });
/** A document as real clients leave it: library fields, settings from both platforms, starter picks. */
const userDoc = (extra = {}) => ({
  playlists: [
    { id: 'p1', title: 'Road', description: null, coverUrl: null, createdAt: 5, isCustom: true, tracks: [song('a'), song('b'), song('a')] },
    { id: 'imported:youtube_music:PL9', title: 'From YouTube', description: 'd', coverUrl: 'c', createdAt: 9, isCustom: false, tracks: [song('y1'), song('y2')] },
  ],
  favorites: [song('f1'), song('f2'), song('f3')],
  savedArtists: [{ id: 'ar', name: 'Artist', thumbnail: null, subscribers: '1M' }],
  savedAlbums: [{ id: 'al', title: 'Album', artist: 'Artist', thumbnail: null, trackCount: 12, savedAt: 3 }],
  librarySync: { pins: ['p1'], pinsAt: 50, deleted: [{ id: 'gone', at: Date.now() - 1000 }] },
  preferences: { android: { theme: { value: 'dark', revision: 2, device: 'phone' } }, windows: { accent: { value: 'red', revision: 1, device: 'pc' } } },
  starterTaste: { artists: '[]', tracks: '[]' },
  updatedAt: 100, playlistsUpdatedAt: 100, favoritesUpdatedAt: 100, schemaVersion: 1,
  ...extra,
});
const COUNTS = { playlists: 2, tracks: 5, favorites: 3, albums: 1, artists: 1, pins: 1, deleted: 1 };

// ---- An old desktop client, exactly as FirestoreRest sends ------------------------------------------

function clientToken(uid) {
  const part = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  return `${part({ alg: 'none', typ: 'JWT' })}.${part({ sub: uid, user_id: uid, aud: PROJECT, iss: `https://securetoken.google.com/${PROJECT}`,
    iat: 1, exp: 4_000_000_000, auth_time: 1, firebase: { sign_in_provider: 'password', identities: {} } })}.`;
}
function value(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') return { integerValue: String(v) };
  if (typeof v === 'string') return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(value) } };
  return { mapValue: { fields: Object.fromEntries(Object.entries(v).map(([k, x]) => [k, value(x)])) } };
}
/** POST :commit with an updateMask of the top-level fields, as an old desktop pushes its library. */
async function oldClientWrites(uid, data) {
  const write = { update: { name: `${ROOT}/users/${uid}`, fields: value(data).mapValue.fields }, updateMask: { fieldPaths: Object.keys(data) } };
  return (await fetch(`http://${HOST}/v1/${ROOT}:commit`, { method: 'POST', headers: { authorization: `Bearer ${clientToken(uid)}`, 'content-type': 'application/json' },
    body: JSON.stringify({ writes: [write] }) })).status;
}

// ---- An updated client -----------------------------------------------------------------------------

async function rpc(uid, frame) {
  const response = await fetch(`${BASE}/v1/library/rpc?device=phone`, { method: 'POST', headers: { authorization: `Bearer dev:${uid}` }, body: JSON.stringify(frame) });
  return response.json();
}
const authority = async (uid) => (await (await fetch(`${BASE}/v1/library/authority`, { headers: { authorization: `Bearer dev:${uid}` } })).json()).state;
async function restoreSnapshot(uid) {
  const out = { playlists: [], items: [] };
  let page;
  for (;;) {
    const delta = await rpc(uid, { t: 'pull', full: true, page });
    out.playlists.push(...delta.playlists); out.items.push(...delta.items); if (delta.pins) out.pins = delta.pins.pins;
    if (!delta.more) return out;
    page = delta.more;
  }
}
const likesOf = (snapshot) => snapshot.items.filter((i) => i.k === 'f').sort((a, b) => a.pos - b.pos).map((i) => i.id);

test('automatic import and backend bridge accept old desktop writes under the actual security rules', async () => {
  const uid = user(); const original = userDoc();
  await firestore.set(`users/${uid}`, original);
  const response = await fetch(`${BASE}/v1/library/bootstrap`, { headers: { authorization: `Bearer dev:${uid}` } });
  const ready = await response.json();
  assert.equal(response.status, 200, JSON.stringify(ready)); assert.equal(ready.state, 'cloudflare');
  original.playlists[0].tracks.push(song('old-extra'));
  assert.equal(await oldClientWrites(uid, { playlists: original.playlists }), 200);
  const pushed = await rpc(uid, { t: 'push', v: 1, epoch: ready.epoch,
    ops: [{ id: 'auto-unlike', t: 'fav.del', key: 'f1' }] });
  assert.equal(pushed.results[0].s, 'ok');
  assert.equal((await library(uid, 'sync-legacy')).status, 200);
  const cloud = (await library(uid, 'export')).body.library;
  assert.equal(cloud.playlists.find((p) => p.id === 'p1').tracks.at(-1).id, 'old-extra');
  const root = await firestore.get(`users/${uid}`);
  assert.deepEqual(root.data.favorites.map((x) => x.id), ['f2', 'f3']);
  assert.deepEqual(root.data.preferences, original.preferences, 'masked backend commit preserves unrelated fields');
  assert.equal((await status(uid, { firestore, library })).problem, null);
});

// ---- Cutover -----------------------------------------------------------------------------------------

test('a full cutover: fenced, imported exactly, verified, then authoritative', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  assert.equal(await oldClientWrites(uid, { favorites: [song('f1'), song('f2'), song('f3')] }), 200, 'before: old clients write freely');
  const options = deps();
  const done = await migrate(uid, options);
  assert.equal(done.result, 'migrated');
  assert.deepEqual(done.counts, COUNTS);
  assert.deepEqual(done.dropped, { tracks: 0, items: 0 });
  assert.equal(await authority(uid), 'cloudflare');

  const fresh = await restoreSnapshot(uid);
  const road = fresh.playlists.find((p) => p.id === 'p1');
  assert.deepEqual(road.tracks.map((t) => t.id), ['a', 'b', 'a'], 'order and the repeated song');
  const imported = fresh.playlists.find((p) => p.id === 'imported:youtube_music:PL9');
  assert.deepEqual([imported.title, imported.description, imported.coverUrl, imported.createdAt, imported.isCustom], ['From YouTube', 'd', 'c', 9, false]);
  assert.ok(fresh.playlists.find((p) => p.id === 'gone').deletedAt > 0, 'the deletion record came along');
  assert.deepEqual(likesOf(fresh), ['f1', 'f2', 'f3']);
  assert.deepEqual(fresh.pins, ['p1']);

  // Old clients after the cutover: the library is refused, everything else still works.
  assert.equal(await oldClientWrites(uid, { favorites: [] }), 403);
  assert.equal(await oldClientWrites(uid, { preferences: { windows: { accent: { value: 'blue', revision: 2, device: 'pc' } } } }), 200);
  const frozen = await firestore.get(`users/${uid}`);
  assert.equal(frozen.data.favorites.length, 3, 'the frozen source is intact');

  const where = await status(uid, options);
  assert.deepEqual([where.state, where.fenced, where.problem, where.step], ['cloudflare', true, null, 'done']);
  const saved = readdirSync(options.snapshotDir);
  const source = saved.find((f) => f.includes('.source.'));
  assert.equal(JSON.parse(readFileSync(join(options.snapshotDir, source), 'utf8')).document.favorites.length, 3, 'the frozen source was saved for recovery');
  const firstExport = await readExport(join(options.snapshotDir, saved.find((f) => f.includes('.export.'))));
  assert.equal(firstExport.checksum, done.checksum, 'a verified export exists from the moment of the flip');
  assert.equal((await migrate(uid, options)).result, 'already-migrated');
});

for (const step of ['begin', 'fence', 'read', 'import']) {
  test(`a crash right after "${step}" resumes to the same result`, async () => {
    const uid = user();
    await firestore.set(`users/${uid}`, userDoc());
    assert.deepEqual(await migrate(uid, deps({ stopAfter: step })), { uid, result: 'stopped', at: step });
    assert.equal(await authority(uid), 'migrating', 'updated clients queue from the first step on');
    assert.equal((await rpc(uid, { t: 'push', v: 1, epoch: 1, ops: [{ id: 'x', t: 'fav.del', key: 'f1' }] })).code, 'authority');
    if (step === 'begin') {
      // No fence yet: an old client's write still lands, and must be part of what is imported.
      assert.equal(await oldClientWrites(uid, { favorites: [song('f1'), song('f2'), song('f3'), song('just-in-time')] }), 200);
    } else {
      assert.equal(await oldClientWrites(uid, { favorites: [] }), 403, 'fenced: nothing can change the source any more');
    }
    const done = await migrate(uid, deps());
    assert.equal(done.result, 'migrated');
    assert.deepEqual(done.counts, { ...COUNTS, favorites: step === 'begin' ? 4 : 3 });
    if (step === 'begin') assert.ok(likesOf(await restoreSnapshot(uid)).includes('just-in-time'));
    assert.equal((await status(uid, deps())).problem, null);
  });
}

test('a quota failure on the final read leaves the account fenced, and is never imported as empty', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  let failing = true;
  const exhausted = { ...firestore, get: (path) => {
    if (failing && path.startsWith('users/')) throw new Retryable('Firestore read of users/… failed: HTTP 429');
    return firestore.get(path);
  } };
  await assert.rejects(migrate(uid, deps({ firestore: exhausted })), (e) => e instanceof Retryable && /429/.test(e.message));
  const stuck = await status(uid, deps());
  assert.deepEqual([stuck.state, stuck.step, stuck.fenced], ['migrating', 'fence', true]);
  assert.deepEqual(stuck.live, { p: 0, f: 0, a: 0, r: 0 }, 'nothing was imported');
  assert.equal(await oldClientWrites(uid, { favorites: [] }), 403);
  await assert.rejects(migrate(uid, deps({ firestore: exhausted })), Retryable, 'still pending the next day');
  failing = false;
  const done = await migrate(uid, deps());
  assert.deepEqual(done.counts, COUNTS, 'the real library, once the read worked');
});

test('a failure writing the fence stops before anything is read', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  const broken = { ...firestore, set: () => { throw new Retryable('Firestore write failed: HTTP 503'); } };
  await assert.rejects(migrate(uid, deps({ firestore: broken })), Retryable);
  const where = await status(uid, deps());
  assert.deepEqual([where.state, where.fenced, where.step], ['migrating', false, 'fence']);
  assert.equal((await migrate(uid, deps())).result, 'migrated');
});

test('a stored library that does not match the source is not flipped', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  // The service reports different counts than the source has (as a storage fault would).
  const lying = async (id, action, body) => {
    const answer = await library(id, action, body);
    if (action === 'import') answer.body.counts = { ...answer.body.counts, favorites: 2 };
    return answer;
  };
  await assert.rejects(migrate(uid, deps({ library: lying })), /does not match the source/);
  assert.equal(await authority(uid), 'migrating');
  assert.equal(await oldClientWrites(uid, { favorites: [] }), 403, 'it fails closed: still fenced');
});

test('an account with no Firestore document needs a person to confirm, then becomes an empty library', async () => {
  const uid = user();
  await assert.rejects(migrate(uid, deps()), /no Firestore library document/);
  assert.deepEqual([(await status(uid, deps())).state, (await status(uid, deps())).fenced], ['migrating', true], 'held, fenced, nothing imported');
  const done = await migrate(uid, deps({ allowEmpty: true }));
  assert.equal(done.result, 'migrated');
  assert.deepEqual(done.counts, { playlists: 0, tracks: 0, favorites: 0, albums: 0, artists: 0, pins: 0, deleted: 0 });
  const ack = await rpc(uid, { t: 'push', v: 1, epoch: done.epoch, ops: [{ id: 'first', t: 'fav.put', v: song('first') }] });
  assert.equal(ack.results[0].s, 'ok');
  assert.equal(await oldClientWrites(uid, { favorites: [song('x')] }), 403, 'an old client cannot start a second library in Firestore');
});

test('unusable entries stop the migration for review; accepting them imports the rest', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc({ favorites: [song('f1'), { title: 'no id' }, song('f1')],
    playlists: [{ title: 'Before ids existed', tracks: [song('a'), { id: '' }] }] }));
  await assert.rejects(migrate(uid, deps()), /unusable entries/);
  const held = await status(uid, deps());
  assert.deepEqual([held.state, held.fenced, held.live.f], ['migrating', true, 0], 'fenced, nothing imported');
  const done = await migrate(uid, deps({ acceptDropped: true }));
  assert.deepEqual(done.dropped, { tracks: 1, items: 1 });
  const fresh = await restoreSnapshot(uid);
  assert.deepEqual(likesOf(fresh), ['f1']);
  assert.match(fresh.playlists.find((p) => !p.deletedAt).id, /^legacy:/);
});

// ---- Going back ----------------------------------------------------------------------------------------

test('abandoning before the flip: Firestore is the library again, exactly as it was', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  await migrate(uid, deps({ stopAfter: 'import' }));
  const before = await firestore.get(`users/${uid}`);
  assert.equal((await abort(uid, deps())).result, 'aborted');
  assert.equal(await authority(uid), 'legacy');
  assert.equal(await oldClientWrites(uid, { favorites: [song('f1')] }), 200);
  assert.deepEqual((await status(uid, deps())).live, { p: 0, f: 0, a: 0, r: 0 }, 'the import was removed');
  assert.equal(before.data.favorites.length, 3);
  await assert.rejects(abort(await (async () => { await migrate(uid, deps()); return uid; })(), deps()), /after the flip use rollback/);
});

test('rollback after edits on the library service keeps those edits', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  const { epoch } = await migrate(uid, deps());
  const p1 = (await restoreSnapshot(uid)).playlists.find((p) => p.id === 'p1');
  const ack = await rpc(uid, { t: 'push', v: 1, epoch, ops: [
    { id: 'o1', t: 'fav.put', v: song('liked-after-cutover') }, { id: 'o2', t: 'fav.del', key: 'f2' },
    { id: 'o3', t: 'pl.put', ifRev: p1.rev, v: { id: 'p1', title: 'Road trip', createdAt: 5, isCustom: true }, tracks: [song('a'), song('b'), song('a'), song('new')] },
  ] });
  assert.deepEqual(ack.results.map((r) => r.s), ['ok', 'ok', 'ok']);

  // A crash after the document was written but before it was verified.
  assert.deepEqual(await rollback(uid, deps({ stopAfter: 'write' })), { uid, result: 'stopped', at: 'write' });
  assert.equal(await authority(uid), 'migrating');
  assert.equal(await oldClientWrites(uid, { favorites: [] }), 403, 'still fenced until verified');
  const options = deps();
  const done = await rollback(uid, options);
  assert.equal(done.result, 'rolled-back');

  const doc = (await firestore.get(`users/${uid}`)).data;
  assert.deepEqual(doc.favorites.map((t) => t.id), ['f1', 'f3', 'liked-after-cutover'], 'the edits made on the library service are there');
  assert.deepEqual(doc.playlists.find((p) => p.id === 'p1').tracks.map((t) => t.id), ['a', 'b', 'a', 'new']);
  assert.equal(doc.playlists.find((p) => p.id === 'p1').title, 'Road trip');
  assert.equal(doc.preferences.android.theme.value, 'dark', 'settings were not touched');
  assert.equal(doc.schemaVersion, 1);
  assert.equal(await authority(uid), 'legacy');
  assert.equal(await oldClientWrites(uid, { favorites: [song('f1')] }), 200, 'old clients sync again');
  assert.deepEqual([(await status(uid, options)).fenced, (await status(uid, options)).problem], [false, null]);
  assert.equal(readdirSync(options.snapshotDir).filter((f) => f.includes('.export.')).length, 1);
  assert.equal((await rollback(uid, deps())).result, 'already-legacy');

  // And the account can be migrated again later, from what Firestore holds now.
  const again = await migrate(uid, deps());
  assert.ok(again.epoch > epoch);
  assert.deepEqual(likesOf(await restoreSnapshot(uid)), ['f1']);
});

test('status names a fence left behind, and abort lifts it', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  await migrate(uid, deps({ stopAfter: 'fence' }));
  await library(uid, 'abort'); // The object went back, but the tool died before removing the fence.
  const where = await status(uid, deps());
  assert.deepEqual([where.state, where.fenced], ['legacy', true]);
  assert.match(where.problem, /still fenced/);
  await abort(uid, deps());
  assert.equal((await status(uid, deps())).problem, null);
  assert.equal(await oldClientWrites(uid, { favorites: [] }), 200);
});

// ---- Pilot safeguards ------------------------------------------------------------------------------

test('the tool moves only accounts in its allowlist file', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  await assert.rejects(migrate(uid, deps({ allowlist: new Set(['someone-else']) })), /not in the allowlist/);
  await assert.rejects(migrate(uid, deps({ allowlist: null })), /not in the allowlist/);
  const untouched = await status(uid, deps());
  assert.deepEqual([untouched.state, untouched.fenced], ['legacy', false], 'nothing was changed, not even the fence');
  assert.equal(await oldClientWrites(uid, { favorites: [] }), 200);
  assert.equal((await migrate(uid, deps({ allowlist: new Set([uid]) }))).result, 'migrated');
});

test('restore puts an export back exactly, keeping a backup of what it replaced', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  const options = deps();
  const { epoch, export: file, checksum } = await migrate(uid, options);
  await rpc(uid, { t: 'push', v: 1, epoch, ops: [{ id: 'r1', t: 'fav.del', key: 'f1' }, { id: 'r2', t: 'fav.del', key: 'f2' }, { id: 'r3', t: 'fav.put', v: song('new') }] });
  const done = await restore(uid, file, options);
  assert.equal(done.result, 'restored');
  assert.equal(done.checksum, checksum);
  assert.deepEqual(likesOf(await restoreSnapshot(uid)), ['f1', 'f2', 'f3']);
  assert.equal(await authority(uid), 'cloudflare');
  const backup = await readExport(done.backup);
  assert.deepEqual(backup.library.favorites.map((t) => t.id), ['f3', 'new'], 'the replaced state can itself be restored');
  await assert.rejects(restore(user(), file, options), /another account/);
});

test('the pilot report flags stop conditions', async () => {
  const healthy = user(); const shrinking = user(); const refusing = user(); const stuck = user();
  for (const uid of [healthy, shrinking, refusing, stuck]) {
    await firestore.set(`users/${uid}`, userDoc({ favorites: Array.from({ length: 10 }, (_, i) => song(`f${i}`)) }));
  }
  await migrate(healthy, deps());
  const s = await migrate(shrinking, deps());
  await rpc(shrinking, { t: 'push', v: 1, epoch: s.epoch, ops: Array.from({ length: 8 }, (_, i) => ({ id: `d${i}`, t: 'fav.del', key: `f${i}` })) });
  const r = await migrate(refusing, deps());
  await rpc(refusing, { t: 'push', v: 1, epoch: r.epoch, ops: [{ id: 'bad1', t: 'fav.put', v: { title: 'no id' } }] });
  await migrate(stuck, deps({ stopAfter: 'fence' }));
  const result = await report([healthy, shrinking, refusing, stuck], deps({ now: () => Date.now() + 2 * 3_600_000 }));
  assert.equal(result.ok, false);
  const why = Object.fromEntries(result.stops.map((x) => [x.uid, x.reasons.join('; ')]));
  assert.equal(why[healthy], undefined);
  assert.match(why[shrinking], /favorites fell from 10 at import to 2/);
  assert.match(why[refusing], /refused client changes/);
  assert.match(why[stuck], /stuck migrating/);
  assert.equal((await report([healthy], deps())).ok, true);
});

test('rollback succeeds when a deletion record crosses its 180-day limit during the rollback', async () => {
  const uid = user();
  const almostExpired = Date.now() - 180 * 86_400_000 + 1500;
  await firestore.set(`users/${uid}`, userDoc({ librarySync: { pins: [], pinsAt: 0, deleted: [{ id: 'old-playlist', at: almostExpired }] } }));
  await migrate(uid, deps());
  await new Promise((r) => setTimeout(r, 2000));
  const done = await rollback(uid, deps());
  assert.equal(done.result, 'rolled-back', 'the account is not left frozen');
  assert.equal(await authority(uid), 'legacy');
});

test('restore refuses an export file that names no account', async () => {
  const uid = user();
  await firestore.set(`users/${uid}`, userDoc());
  const options = deps();
  const { export: file } = await migrate(uid, options);
  const saved = JSON.parse(readFileSync(file, 'utf8'));
  delete saved.uid;
  const anonymousFile = join(options.snapshotDir, 'no-uid.json');
  (await import('node:fs')).writeFileSync(anonymousFile, JSON.stringify(saved));
  await assert.rejects(restore(uid, anonymousFile, options), /names none/);
});
