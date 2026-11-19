import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, stopServer, connect } from './harness.mjs';
import { fakeFirestore } from './fake-library-firestore.mjs';
import { checksum, fromUserDocument, toUserDocument } from '../src/library-core.js';
import { rollback, status } from '../tools/library-migrate.mjs';
const PORT = 8804; const BASE = `http://127.0.0.1:${PORT}`;
let server, fs; const sockets = [];
const vars = () => ({ LIBRARY_AUTO_IMPORT: 'true', LIBRARY_AUTO_UIDS: '*', FIREBASE_PROJECT_ID: 'demo-auralis',
 LIBRARY_FIRESTORE_EMULATOR: fs.origin, LIBRARY_LEGACY_READ_BUDGET: '300', LIBRARY_LEGACY_WRITE_BUDGET: '300' });
before(async () => { fs = await fakeFirestore(); server = await startServer(PORT, undefined, vars()); }, { timeout: 120000 });
after(async () => { for (const s of sockets) s.close(); if (server) await stopServer(server); await fs?.close(); });
const song = (id) => ({ id, title: id, artist: 'Artist', duration: 100 });
const list = (id, n) => ({ id, title: id, createdAt: 1, tracks: Array.from({ length: n }, (_, i) => song(`${id}-${i}`)) });
async function admin(uid, action, body = {}) {
 const r = await fetch(`${BASE}/v1/library/admin/${encodeURIComponent(uid)}/${action}`, { method: 'POST', headers: { 'x-library-admin': 'local-admin' }, body: JSON.stringify(body) });
 return { status: r.status, body: await r.json() };
}
async function bootstrap(uid, token = `dev:${uid}`) {
 const r = await fetch(`${BASE}/v1/library/bootstrap`, { headers: { authorization: `Bearer ${token}` } });
 return { status: r.status, body: r.status === 401 ? null : await r.json() };
}
async function rpc(uid, frame) {
 return (await fetch(`${BASE}/v1/library/rpc?device=phone`, { method: 'POST', headers: { authorization: `Bearer dev:${uid}` }, body: JSON.stringify(frame) })).json();
}
const exported = async (uid) => (await admin(uid, 'export')).body;
async function until(fn, ms = 15000) {
 const end = Date.now() + ms;
 do { if (await fn()) return; await new Promise((r) => setTimeout(r, 100)); } while (Date.now() < end);
 assert.fail('condition not met');
}
test('concurrent first sync imports once, preserving order, duplicates and profile picks', async () => {
 const doc = { playlists: [list('Gilli', 111), list('Hi', 2)], favorites: [song('liked')], starterTaste: { artists: 'A', tracks: '[]' } };
 doc.playlists[0].tracks.push(song('Gilli-0')); fs.put('users/pinkman', doc);
 const initial = await Promise.all(Array.from({ length: 5 }, () => bootstrap('pinkman')));
 assert.ok(initial.every((r) => r.status === 200 && r.body.state === 'cloudflare' && r.body.epoch === 1), JSON.stringify(initial));
 assert.equal((await exported('pinkman')).checksum, await checksum(fromUserDocument(doc).library));
 assert.equal((await admin('pinkman', 'status')).body.rev, 1);
 assert.equal((await rpc('pinkman', { t: 'profile.picks' })).picks.artists, 'A');
 assert.equal((await bootstrap('other')).body.state, 'cloudflare'); assert.equal((await exported('other')).library.playlists.length, 0);
 assert.equal((await bootstrap('pinkman', 'bad')).status, 401);
});
test('old snapshot and new operation reconcile, notify another device and mirror without replacing profile', async () => {
 const socket = await connect(PORT, 'pinkman', 'pc', { path: '/v1/library' }); sockets.push(socket);
 const hello = await socket.call({ t: 'hello', v: 1 }); assert.equal(hello.state, 'cloudflare');
 const old = fs.data('users/pinkman'); old.playlists[0].tracks.push(song('112')); fs.put('users/pinkman', old);
 const frame = { t: 'push', v: 1, epoch: hello.epoch, ops: [{ id: 'delete-hi', t: 'pl.del', key: 'Hi', ifRev: 1, at: Date.now() }] };
 assert.equal((await rpc('pinkman', frame)).results[0].s, 'ok');
 await until(() => fs.data('users/pinkman').playlists.length === 1);
 const cloud = await exported('pinkman'); assert.equal(cloud.library.playlists[0].tracks.length, 113);
 assert.equal(cloud.library.playlists[0].tracks.at(-1).id, '112');
 assert.equal(fs.data('users/pinkman').starterTaste.artists, 'A');
 await socket.next((f) => f.t === 'changed' && f.by === 'legacy-bridge');
 assert.equal((await rpc('pinkman', frame)).results[0].s, 'dup', 'bridge resets retain operation deduplication');
 const writes = fs.commits; await admin('pinkman', 'sync-legacy'); assert.equal(fs.commits, writes, 'no mirror echo');
 socket.close();
});
test('quota before import stays pending; quota after cutover retains accepted edits', async () => {
 fs.status = 429; const blocked = await bootstrap('quota-new'); assert.equal(blocked.status, 202);
 assert.equal(blocked.body.state, 'migrating'); assert.match(blocked.body.reason, /429/);
 assert.equal((await admin('quota-new', 'status')).body.state, 'legacy');
 const before = fs.calls.length; await bootstrap('quota-new'); assert.equal(fs.calls.length, before, 'durable backoff');
 const pushed = await rpc('pinkman', { t: 'push', v: 1, epoch: 1, ops: [{ id: 'quota-like', t: 'fav.put', v: song('during-quota') }] });
 assert.equal(pushed.results[0].s, 'ok'); assert.equal((await admin('pinkman', 'sync-legacy')).status, 503);
 assert.ok((await exported('pinkman')).library.favorites.some((x) => x.id === 'during-quota'));
 fs.status = 0; assert.equal((await admin('pinkman', 'sync-legacy')).status, 200);
 assert.ok(fs.data('users/pinkman').favorites.some((x) => x.id === 'during-quota'));
});
test('real restart retains original source, accepted edits and mirror baseline', async () => {
 const before = await exported('pinkman'); const dir = server.persistDir;
 await stopServer(server); server = await startServer(PORT, dir, vars());
 assert.equal((await bootstrap('pinkman')).body.epoch, 1); assert.equal((await exported('pinkman')).checksum, before.checksum);
 const source = (await admin('pinkman', 'export-source')).body.source;
 assert.equal(await checksum(source.library), source.checksum);
 assert.equal(source.library.playlists.length, 2); assert.equal(source.library.playlists[0].tracks.length, 112);
 const old = fs.data('users/pinkman'); old.favorites.push(song('after-restart')); fs.put('users/pinkman', old);
 assert.equal((await admin('pinkman', 'sync-legacy')).status, 200);
 assert.ok((await exported('pinkman')).library.favorites.some((x) => x.id === 'after-restart'));
});
test('fenced accounts need explicit adoption and exact source; URL owns identity', async () => {
 const doc = { favorites: [song('frozen')] }; fs.put('users/fenced', doc); fs.put('libraryAuthority/fenced', { state: 'cloudflare' });
 const lib = fromUserDocument(doc).library; const sum = await checksum(lib);
 await admin('fenced', 'begin'); await admin('fenced', 'import', { library: lib, checksum: sum, source: { exists: true } }); await admin('fenced', 'commit', { checksum: sum });
 assert.equal((await admin('fenced', 'adopt-bridge')).status, 400);
 const ack = await rpc('fenced', { t: 'push', v: 1, epoch: 2, ops: [{ id: 'new', t: 'fav.put', v: song('cloud-new') }] });
 assert.equal(ack.results[0].s, 'ok');
 const adopted = await admin('fenced', 'adopt-bridge', { uid: 'pinkman', acknowledge: 'allow old apps to write through the library bridge' });
 assert.equal(adopted.status, 200, JSON.stringify(adopted)); assert.equal(fs.data('libraryAuthority/fenced'), undefined);
 assert.ok(fs.data('users/fenced').favorites.some((x) => x.id === 'cloud-new'));
 assert.ok(!fs.data('users/pinkman').favorites.some((x) => x.id === 'cloud-new'));
});
test('lost Firestore commit acknowledgement survives a real restart and later old-app edit', async () => {
 fs.put('users/lostack', { playlists: [list('p', 1)] }); await bootstrap('lostack');
 await rpc('lostack', { t: 'push', v: 1, epoch: 1, ops: [{ id: 'like-lost', t: 'fav.put', v: song('new') }] });
 fs.loseAck = true; assert.equal((await admin('lostack', 'sync-legacy')).status, 503);
 assert.ok(fs.data('libraryBridgeJournal/lostack')?.id);
 const legacy = fs.data('users/lostack'); legacy.playlists[0].tracks.push(song('later')); fs.put('users/lostack', legacy);
 const dir = server.persistDir; await stopServer(server); server = await startServer(PORT, dir, vars());
 assert.equal((await admin('lostack', 'sync-legacy')).status, 200);
 const result = (await exported('lostack')).library;
 assert.deepEqual(result.playlists[0].tracks.map((x) => x.id), ['p-0', 'later']);
 assert.deepEqual(result.favorites.map((x) => x.id), ['new']);
});
test('rollback prevents automatic reimport; explicit resume works; erase removes recovery data', async () => {
 const firestore = {
  get: async (p) => { const d = fs.docs.get(p); return d ? { exists: true, ...structuredClone(d) } : { exists: false, data: null }; },
  set: async () => { assert.fail('automatic rollback must not assume a security-rule fence'); },
  merge: async () => { assert.fail('the backend owns the conditional mirror'); },
  delete: async () => { assert.fail('automatic rollback does not manipulate a legacy fence'); },
 };
 const deps = { firestore, library: admin };
 assert.equal((await status('pinkman', deps)).problem, null, 'an automatic bridge needs no fence');
 await rpc('pinkman', { t: 'push', v: 1, epoch: 1, ops: [{ id: 'rollback-cloud-pending', t: 'fav.put', v: song('cloud-before-rollback') }] });
 const old = fs.data('users/pinkman'); old.favorites.push(song('last-old-edit')); fs.put('users/pinkman', old);
 fs.conflict = () => { const raced = fs.data('users/pinkman'); raced.favorites.push(song('racing-rollback-edit')); fs.put('users/pinkman', raced); };
 const result = await rollback('pinkman', deps); assert.equal(result.result, 'rolled-back');
 assert.equal(fs.conflict, null, 'the test actually raced the conditional commit');
 for (const id of ['last-old-edit', 'racing-rollback-edit']) assert.ok(fs.data('users/pinkman').favorites.some((x) => x.id === id), 'rollback catches up old writers and retries races');
 assert.equal(fs.data('libraryAuthority/pinkman'), undefined);
 assert.equal((await bootstrap('pinkman')).status, 202);
 assert.equal((await admin('pinkman', 'resume-automatic', { acknowledge: 'resume automatic import from the verified rollback source' })).status, 200);
 assert.equal((await bootstrap('pinkman')).body.state, 'cloudflare');
 assert.equal((await rpc('pinkman', { t: 'erase', v: 1 })).t, 'erased'); assert.equal((await admin('pinkman', 'export-source')).status, 404);
 assert.equal((await bootstrap('pinkman')).status, 202, 'erased account cannot reimport');
 await until(() => !fs.data('libraryBridgeJournal/pinkman'));
});
test('failed automatic rollback stays frozen and retries the bridge without losing accepted edits', async () => {
 const uid = 'rollback-quota'; fs.put(`users/${uid}`, { favorites: [song('base')] }); await bootstrap(uid);
 await rpc(uid, { t: 'push', v: 1, epoch: 1, ops: [{ id: 'accepted-before-rollback', t: 'fav.put', v: song('accepted') }] });
 const deps = { library: admin, firestore: new Proxy({}, { get: () => () => assert.fail('automatic rollback needs no operator Firestore mutation') }) };
 fs.status = 429; await assert.rejects(rollback(uid, deps), /reconciliation failed/);
 const failed = (await admin(uid, 'status')).body; assert.equal(failed.state, 'migrating'); assert.equal(failed.automatic.phase, 'paused');
 assert.equal((await rpc(uid, { t: 'push', v: 1, epoch: failed.epoch, ops: [{ id: 'frozen', t: 'fav.put', v: song('blocked') }] })).code, 'authority');
 fs.status = 0; const legacy = fs.data(`users/${uid}`); legacy.favorites.push(song('old-after-failure')); fs.put(`users/${uid}`, legacy);
 assert.equal((await rollback(uid, deps)).result, 'rolled-back');
 assert.deepEqual(new Set(fs.data(`users/${uid}`).favorites.map((x) => x.id)), new Set(['base', 'accepted', 'old-after-failure']));
 assert.equal((await rollback(uid, deps)).result, 'already-legacy');
 await admin(uid, 'resume-automatic', { acknowledge: 'resume automatic import from the verified rollback source' });
 fs.status = 429; assert.equal((await bootstrap(uid)).status, 202);
 assert.equal((await admin(uid, 'export-source')).status, 200, 'an interrupted reimport keeps the prior source backup');
 assert.equal((await exported(uid)).library.favorites.length, 3, 'the last archive is not replaced with empty data');
 fs.status = 0;
});
test('quota cannot block erase; backend marker cleanup remains pending across a restart', async () => {
 fs.put('users/cleanup-quota', { favorites: [song('old')] }); await bootstrap('cleanup-quota');
 await rpc('cleanup-quota', { t: 'push', v: 1, epoch: 1, ops: [{ id: 'cleanup-like', t: 'fav.put', v: song('new') }] });
 await admin('cleanup-quota', 'sync-legacy'); assert.ok(fs.data('libraryBridgeJournal/cleanup-quota'));
 fs.status = 429;
 assert.equal((await rpc('cleanup-quota', { t: 'erase', v: 1 })).t, 'erased');
 await new Promise((r) => setTimeout(r, 1500));
 assert.equal((await admin('cleanup-quota', 'status')).body.legacyCleanupPending, true);
 assert.equal((await admin('cleanup-quota', 'export-source')).status, 404);
 const dir = server.persistDir; await stopServer(server); server = await startServer(PORT, dir, vars()); fs.status = 0;
 assert.equal((await admin('cleanup-quota', 'status')).body.legacyCleanupPending, true);
 assert.equal((await bootstrap('cleanup-quota')).status, 202);
 assert.equal((await rpc('cleanup-quota', { t: 'erase', v: 1 })).t, 'erased', 'deletion retry is not blocked');
});
test('pilot admission and global read budget stop imports before Firestore I/O', async () => {
 const dir = server.persistDir; await stopServer(server);
 server = await startServer(PORT, dir, { ...vars(), LIBRARY_AUTO_UIDS: 'budget-allowed', LIBRARY_LEGACY_READ_BUDGET: '0' });
 const before = fs.calls.length;
 assert.equal((await bootstrap('unlisted')).body.reason, 'rollout-off');
 assert.equal((await bootstrap('budget-allowed')).body.reason, 'legacy-budget');
 assert.equal(fs.calls.length, before);
 assert.equal((await bootstrap('lostack')).body.state, 'cloudflare', 'existing migrations remain usable');
 assert.equal((await admin('bad/uid', 'status')).status, 400);
});
