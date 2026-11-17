// The real Worker and LibraryAccount object in workerd on this machine. Nothing leaves it.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { connect as open, startServer, stopServer } from './harness.mjs';
import { checksum, counts, fromUserDocument } from '../src/library-core.js';

const PORT = 8793;
const BASE = `http://127.0.0.1:${PORT}`;
let server;
let accounts = 0;
const account = () => `lib${Date.now()}x${accounts++}`;
let opIds = 0;
const op = (fields) => ({ id: `op-${Date.now()}-${opIds++}`, ...fields });

// Every socket is closed at the end, so a failed test can't keep the run alive.
const sockets = [];
const connect = async (...args) => { const c = await open(...args); sockets.push(c); return c; };
const GONE_AT = Date.now() - 60_000;

before(async () => { server = await startServer(PORT); }, { timeout: 120_000 });
after(async () => { sockets.forEach((c) => c.close()); await stopServer(server); });

const song = (id, extra = {}) => ({ id, title: `Song ${id}`, artist: 'A', album: null, thumbnail: 't', duration: 180, ...extra });
const playlist = (id, tracks, extra = {}) => ({ id, title: `List ${id}`, description: null, coverUrl: null, createdAt: 10, isCustom: true, tracks, ...extra });

async function admin(uid, action, body = {}, secret = 'local-admin') {
  const response = await fetch(`${BASE}/v1/library/admin/${uid}/${action}`, { method: 'POST', headers: { 'x-library-admin': secret }, body: JSON.stringify(body) });
  return { status: response.status, body: response.status === 404 ? null : await response.json() };
}

async function rpc(uid, device, frame, token = `dev:${uid}`) {
  const response = await fetch(`${BASE}/v1/library/rpc?device=${device}`, { method: 'POST', headers: { authorization: `Bearer ${token}` }, body: JSON.stringify(frame) });
  return response.status === 200 ? response.json() : { status: response.status };
}

const authority = async (uid, token = `dev:${uid}`) => {
  const response = await fetch(`${BASE}/v1/library/authority`, { headers: token ? { authorization: `Bearer ${token}` } : {} });
  return response.status === 200 ? response.json() : { status: response.status };
};

/** Runs the whole cutover for [doc], as the migration tool does. */
async function migrate(uid, doc) {
  const { library } = fromUserDocument(doc);
  const sum = await checksum(library);
  assert.equal((await admin(uid, 'begin')).body.state, 'migrating');
  const imported = await admin(uid, 'import', { library, checksum: sum, source: { exists: doc !== null, updateTime: 't1' } });
  assert.equal(imported.status, 200, JSON.stringify(imported.body));
  assert.equal(imported.body.checksum, sum);
  const committed = await admin(uid, 'commit', { checksum: sum });
  assert.equal(committed.body.state, 'cloudflare');
  return { library, sum, epoch: committed.body.epoch };
}

const socket = async (uid, device) => {
  const c = await connect(PORT, uid, device, { path: '/v1/library' });
  const ready = await c.call({ t: 'hello', v: 1 });
  return Object.assign(c, { ready });
};

const fullPull = async (uid, device) => {
  const out = { playlists: [], items: [], pages: 0 };
  let page;
  for (;;) {
    const delta = await rpc(uid, device, { t: 'pull', full: true, page });
    assert.equal(delta.t, 'delta');
    out.playlists.push(...delta.playlists); out.items.push(...delta.items); out.pages += 1;
    out.rev = delta.rev; if (delta.pins) out.pins = delta.pins;
    if (!delta.more) return out;
    page = delta.more;
  }
};

test('an untouched account is legacy, and nothing can be read or written here', async () => {
  const uid = account();
  assert.deepEqual(await authority(uid), { state: 'legacy', epoch: 0, v: 1 });
  const refused = await rpc(uid, 'pc', { t: 'push', v: 1, epoch: 0, ops: [op({ t: 'fav.put', v: song('a') })] });
  assert.deepEqual([refused.t, refused.code, refused.state], ['err', 'authority', 'legacy']);
  assert.equal((await rpc(uid, 'pc', { t: 'pull', cursor: 0 })).code, 'authority');
});

test('no token, a bad token and a missing device are refused', async () => {
  const uid = account();
  assert.equal((await authority(uid, null)).status, 401);
  assert.equal((await authority(uid, 'nonsense')).status, 401);
  assert.equal((await rpc(uid, 'pc', { t: 'hello', v: 1 }, `dev:${uid}:-5`)).status, 401, 'an expired token');
  const response = await fetch(`${BASE}/v1/library/rpc`, { method: 'POST', headers: { authorization: `Bearer dev:${uid}` }, body: '{}' });
  assert.equal(response.status, 400);
  await assert.rejects(connect(PORT, uid, 'pc', { path: '/v1/library', token: null }), (e) => e.status === 401);
});

test('the admin routes do not exist without the right secret', async () => {
  const uid = account();
  assert.equal((await admin(uid, 'status', {}, 'wrong')).status, 404);
  assert.equal((await admin(uid, 'begin', {}, '')).status, 404);
  assert.deepEqual(await authority(uid), { state: 'legacy', epoch: 0, v: 1 }, 'a refused admin call changed nothing');
});

test('cutover: fenced while migrating, verified import, then authoritative', async () => {
  const uid = account();
  const doc = { playlists: [playlist('p1', [song('a'), song('b'), song('a')]), playlist('imported:PLx', [song('c')], { isCustom: false, createdAt: 3 })],
    favorites: [song('z'), song('y')], savedAlbums: [{ id: 'al', title: 'Album', savedAt: 5 }], savedArtists: [{ id: 'ar', name: 'Artist' }],
    librarySync: { pins: ['p1'], pinsAt: 9, deleted: [{ id: 'gone', at: GONE_AT }] } };
  const { library } = fromUserDocument(doc);
  const sum = await checksum(library);

  const begun = await admin(uid, 'begin');
  assert.deepEqual([begun.body.state, begun.body.epoch, begun.body.job.step], ['migrating', 1, 'fence']);
  assert.deepEqual((await admin(uid, 'begin')).body.epoch, 1, 'beginning twice is the same migration');
  assert.equal((await authority(uid)).state, 'migrating');
  assert.equal((await rpc(uid, 'pc', { t: 'push', v: 1, epoch: 1, ops: [op({ t: 'fav.put', v: song('a') })] })).code, 'authority');

  assert.equal((await admin(uid, 'commit', { checksum: sum })).status, 409, 'nothing imported yet');
  assert.equal((await admin(uid, 'import', { library, checksum: 'f'.repeat(64), source: { exists: true } })).status, 422);
  const imported = await admin(uid, 'import', { library, checksum: sum, source: { exists: true, updateTime: 't1' } });
  assert.equal(imported.body.checksum, sum);
  assert.deepEqual(imported.body.counts, counts(library));
  const revAfterImport = imported.body.rev;
  const repeated = await admin(uid, 'import', { library, checksum: sum, source: { exists: true, updateTime: 't1' } });
  assert.equal(repeated.body.rev, revAfterImport, 'the same import again writes nothing');

  assert.equal((await admin(uid, 'commit', { checksum: 'e'.repeat(64) })).status, 409);
  assert.equal((await authority(uid)).state, 'migrating', 'a refused commit leaves the account fenced');
  const committed = await admin(uid, 'commit', { checksum: sum });
  assert.deepEqual([committed.body.state, committed.body.epoch], ['cloudflare', 2]);
  assert.equal((await admin(uid, 'commit', { checksum: sum })).body.epoch, 2, 'committing twice changes nothing');
  assert.equal((await admin(uid, 'abort')).status, 409, 'there is no abort after the flip');

  const fresh = await fullPull(uid, 'new-phone');
  const road = fresh.playlists.find((p) => p.id === 'p1');
  assert.deepEqual(road.tracks.map((t) => t.id), ['a', 'b', 'a'], 'order and the repeated song survive');
  assert.deepEqual([road.title, road.createdAt, road.isCustom], ['List p1', 10, true]);
  assert.equal(fresh.playlists.find((p) => p.id === 'imported:PLx').isCustom, false);
  assert.deepEqual(fresh.playlists.find((p) => p.id === 'gone'), { id: 'gone', rev: road.rev, deletedAt: GONE_AT });
  assert.deepEqual(fresh.items.filter((i) => i.k === 'f').sort((a, b) => a.pos - b.pos).map((i) => i.id), ['z', 'y']);
  assert.deepEqual(fresh.pins.pins, ['p1']);
});

test('a migration can be abandoned before the flip, and leaves nothing behind', async () => {
  const uid = account();
  const { library } = fromUserDocument({ favorites: [song('a')] });
  await admin(uid, 'begin');
  await admin(uid, 'import', { library, checksum: await checksum(library), source: { exists: true } });
  const aborted = await admin(uid, 'abort');
  assert.equal(aborted.body.state, 'legacy');
  assert.deepEqual(aborted.body.live, { p: 0, f: 0, a: 0, r: 0 });
  assert.equal((await admin(uid, 'abort')).body.state, 'legacy', 'aborting twice is fine');
  assert.equal((await admin(uid, 'verify')).body.counts.favorites, 0);
  // And it can start again later.
  const again = await migrate(uid, { favorites: [song('b')] });
  assert.deepEqual((await fullPull(uid, 'pc')).items.map((i) => i.id), ['b']);
  assert.ok(again.epoch > aborted.body.epoch);
});

test('an account with no document migrates as an empty library', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, null);
  const empty = await fullPull(uid, 'pc');
  assert.deepEqual([empty.playlists, empty.items], [[], []]);
  const ack = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('first') })] });
  assert.equal(ack.results[0].s, 'ok');
});

test('edits reach every other device with what changed, and not the writer', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { playlists: [playlist('p1', [song('a')])] });
  const [phone, pc] = [await socket(uid, 'phone'), await socket(uid, 'pc')];
  assert.deepEqual([phone.ready.state, phone.ready.epoch], ['cloudflare', epoch]);
  const base = phone.ready.rev;
  const p1 = (await fullPull(uid, 'phone')).playlists[0];

  const ack = await phone.call({ t: 'push', v: 1, epoch, ops: [
    op({ t: 'fav.put', v: song('liked') }),
    op({ t: 'pl.put', ifRev: p1.rev, v: playlist('p1', [], { title: 'Renamed' }), tracks: [song('b'), song('a'), song('b')] }),
    op({ t: 'pl.put', ifRev: 0, v: playlist('p2', []), tracks: [] }),
    op({ t: 'alb.put', v: { id: 'al', title: 'Album', savedAt: 1 } }),
    op({ t: 'art.put', v: { id: 'ar', name: 'Artist' } }),
    op({ t: 'pins.set', pins: ['p2'], at: 500 }),
  ] });
  assert.equal(ack.rev, base + 1, 'one batch is one revision');
  assert.deepEqual(ack.results.map((r) => r.s), ['ok', 'ok', 'ok', 'ok', 'ok', 'ok']);

  const pushed = await pc.next((f) => f.t === 'changed');
  assert.deepEqual([pushed.from, pushed.rev, pushed.by], [base, base + 1, 'phone']);
  assert.deepEqual(pushed.delta.playlists.find((p) => p.id === 'p1').tracks.map((t) => t.id), ['b', 'a', 'b']);
  assert.equal(pushed.delta.playlists.find((p) => p.id === 'p1').title, 'Renamed');
  assert.deepEqual(pushed.delta.items.map((i) => i.k).sort(), ['a', 'f', 'r']);
  assert.deepEqual(pushed.delta.pins.pins, ['p2']);
  assert.equal(phone.frames.filter((f) => f.t === 'changed').length, 0);

  // Unlike, delete, and a rename that leaves the songs alone.
  const second = await pc.call({ t: 'push', v: 1, epoch, ops: [
    op({ t: 'fav.del', key: 'liked' }),
    op({ t: 'pl.del', key: 'p2', ifRev: base + 1, at: GONE_AT }),
    op({ t: 'pl.put', ifRev: base + 1, v: playlist('p1', [], { title: 'Again' }) }),
  ] });
  assert.deepEqual(second.results.map((r) => r.s), ['ok', 'ok', 'ok']);
  const told = await phone.next((f) => f.t === 'changed');
  assert.equal(told.delta.playlists.find((p) => p.id === 'p1').tracks, undefined, 'a rename does not resend the songs');
  assert.deepEqual(told.delta.playlists.find((p) => p.id === 'p2'), { id: 'p2', rev: base + 2, deletedAt: GONE_AT });
  assert.equal(told.delta.items[0].del > 0, true);

  const delta = await rpc(uid, 'tablet', { t: 'pull', cursor: base });
  assert.deepEqual(delta.playlists.find((p) => p.id === 'p1').tracks.map((t) => t.id), ['b', 'a', 'b'], 'the songs were kept through the rename');
  assert.equal(delta.items.find((i) => i.id === 'liked').del > 0, true, 'a device that was behind learns of the unlike');
  assert.equal((await rpc(uid, 'tablet', { t: 'pull', cursor: delta.rev })).playlists.length, 0);
  phone.close(); pc.close();
});

test('a lost acknowledgment: the same batch again applies nothing twice', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, null);
  const frame = { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('a') }), op({ t: 'pl.put', ifRev: 0, v: playlist('p', []), tracks: [song('x'), song('x')] })] };
  const first = await rpc(uid, 'pc', frame);
  const again = await rpc(uid, 'pc', frame);
  assert.deepEqual(again.results.map((r) => [r.s, r.rev]), [['dup', first.rev], ['dup', first.rev]]);
  assert.equal(again.rev, first.rev);
  // Reordered: the second half of the batch first, with one new operation.
  const mixed = await rpc(uid, 'phone', { t: 'push', v: 1, epoch, ops: [frame.ops[1], op({ t: 'fav.put', v: song('b') }), frame.ops[0]] });
  assert.deepEqual(mixed.results.map((r) => r.s), ['dup', 'ok', 'dup']);
  const state = await fullPull(uid, 'pc');
  assert.deepEqual(state.playlists[0].tracks.map((t) => t.id), ['x', 'x']);
  assert.deepEqual(state.items.map((i) => i.id).sort(), ['a', 'b']);
});

test('two devices edit one playlist: the second is told to merge, then succeeds', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { playlists: [playlist('p', [song('a')])] });
  const seen = (await fullPull(uid, 'phone')).playlists[0].rev;
  const phone = await rpc(uid, 'phone', { t: 'push', v: 1, epoch, ops: [op({ t: 'pl.put', ifRev: seen, v: playlist('p', []), tracks: [song('a'), song('phone')] })] });
  assert.equal(phone.results[0].s, 'ok');
  const pc = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'pl.put', ifRev: seen, v: playlist('p', []), tracks: [song('a'), song('pc')] }), op({ t: 'fav.put', v: song('f') })] });
  assert.deepEqual(pc.results.map((r) => r.s), ['stale', 'ok'], 'only the conflicting edit waits');
  assert.equal(pc.results[0].rev, phone.rev);
  const current = (await rpc(uid, 'pc', { t: 'pull', cursor: seen })).playlists[0];
  assert.deepEqual(current.tracks.map((t) => t.id), ['a', 'phone']);
  const merged = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'pl.put', ifRev: current.rev, v: playlist('p', []), tracks: [song('a'), song('pc'), song('phone')] })] });
  assert.equal(merged.results[0].s, 'ok');
  // A delete based on the old copy is stale too.
  assert.equal((await rpc(uid, 'phone', { t: 'push', v: 1, epoch, ops: [op({ t: 'pl.del', key: 'p', ifRev: seen })] })).results[0].s, 'stale');
});

test('like and unlike from two devices: the last one accepted wins', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { favorites: [song('a')] });
  await rpc(uid, 'phone', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.del', key: 'a' })] });
  await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('a') })] });
  assert.deepEqual((await fullPull(uid, 'x')).items.map((i) => i.id), ['a']);
  await rpc(uid, 'phone', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.del', key: 'a' })] });
  assert.deepEqual((await fullPull(uid, 'x')).items, []);
  const pins = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'pins.set', pins: ['new'], at: 200 }), op({ t: 'pins.set', pins: ['old'], at: 100 })] });
  assert.deepEqual(pins.results.map((r) => r.s), ['ok', 'ok']);
  assert.deepEqual((await fullPull(uid, 'x')).pins.pins, ['new'], 'the older pin order does not replace the newer');
});

test('a stale epoch, an old protocol and bad batches are refused', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, null);
  assert.equal((await rpc(uid, 'pc', { t: 'push', v: 1, epoch: epoch - 1, ops: [op({ t: 'fav.put', v: song('a') })] })).code, 'epoch');
  assert.equal((await rpc(uid, 'pc', { t: 'push', v: 2, epoch, ops: [op({ t: 'fav.put', v: song('a') })] })).code, 'version');
  assert.equal((await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [] })).code, 'invalid');
  assert.equal((await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: Array.from({ length: 501 }, () => op({ t: 'fav.del', key: 'a' })) })).code, 'invalid');
  const mixed = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: { title: 'no id' } }), op({ t: 'fav.put', v: song('ok') })] });
  assert.deepEqual(mixed.results.map((r) => r.s), ['bad', 'ok']);
  assert.equal((await rpc(uid, 'pc', { t: 'nonsense' })).code, 'unknown');
  const big = await fetch(`${BASE}/v1/library/rpc?device=pc`, { method: 'POST', headers: { authorization: `Bearer dev:${uid}` }, body: 'x'.repeat(4 * 1024 * 1024 + 10) });
  assert.equal(big.status, 413);
});

test("one account can't reach another's library", async () => {
  const [alice, bob] = [account(), account()];
  const a = await migrate(alice, { favorites: [song('secret')] });
  await migrate(bob, null);
  assert.deepEqual((await fullPull(bob, 'pc')).items, []);
  const watching = await socket(bob, 'pc');
  await rpc(alice, 'phone', { t: 'push', v: 1, epoch: a.epoch, ops: [op({ t: 'fav.put', v: song('more') })] });
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(watching.frames.length, 0);
  // A uid in the body or query is never believed: the token decides.
  const sneaky = await fetch(`${BASE}/v1/library/rpc?device=pc&uid=${alice}`, { method: 'POST', headers: { authorization: `Bearer dev:${bob}`, 'x-uid': alice },
    body: JSON.stringify({ t: 'pull', full: true, uid: alice }) });
  assert.deepEqual((await sneaky.json()).items, []);
  watching.close();
});

test('a large library arrives in pages, complete and in order', async () => {
  const uid = account();
  const long = (n) => 'x'.repeat(n);
  const doc = { playlists: Array.from({ length: 6 }, (_, p) => playlist(`p${p}`, Array.from({ length: 700 }, (_, i) => song(`t${p}-${i}`, { title: long(150) })))),
    favorites: Array.from({ length: 2500 }, (_, i) => song(`f${String(i).padStart(4, '0')}`, { title: long(250) })) };
  const { library } = await migrate(uid, doc);
  const all = await fullPull(uid, 'pc');
  assert.ok(all.pages > 1, `expected several pages, got ${all.pages}`);
  assert.equal(all.playlists.length, 6);
  assert.deepEqual(all.playlists.find((p) => p.id === 'p3').tracks.map((t) => t.id), library.playlists[3].tracks.map((t) => t.id));
  assert.equal(all.items.length, 2500);
  assert.equal(new Set(all.items.map((i) => i.id)).size, 2500);
  assert.deepEqual(all.items.sort((a, b) => a.pos - b.pos).map((i) => i.id), library.favorites.map((t) => t.id), 'liked order is kept');
  const verified = await admin(uid, 'verify');
  assert.deepEqual(verified.body.counts, counts(library));
});

test('a device whose copy predates a re-import is told to start over', async () => {
  const uid = account();
  const { library } = fromUserDocument({ favorites: [song('a')] });
  await admin(uid, 'begin');
  const first = await admin(uid, 'import', { library, checksum: await checksum(library), source: { exists: true } });
  const other = fromUserDocument({ favorites: [song('b')] }).library;
  await admin(uid, 'import', { library: other, checksum: await checksum(other), source: { exists: true } });
  await admin(uid, 'commit', { checksum: await checksum(other) });
  const stale = await rpc(uid, 'pc', { t: 'pull', cursor: first.body.rev });
  assert.equal(stale.t, 'reset');
  assert.equal((await rpc(uid, 'pc', { t: 'pull', cursor: 999_999 })).t, 'reset', 'a cursor from another history');
  assert.deepEqual((await fullPull(uid, 'pc')).items.map((i) => i.id), ['b']);
});

test('rollback: writes are frozen, the export is the latest state, then legacy again', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { favorites: [song('a')] });
  await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('after-cutover') })] });
  const watching = await socket(uid, 'phone');
  const frozen = await admin(uid, 'rollback-begin');
  assert.deepEqual([frozen.body.state, frozen.body.job.dir], ['migrating', 'out']);
  assert.equal((await watching.next((f) => f.t === 'authority')).state, 'migrating');
  assert.equal((await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('late') })] })).code, 'authority');
  assert.equal((await admin(uid, 'begin')).status, 409);
  const exported = await admin(uid, 'export');
  assert.deepEqual(exported.body.library.favorites.map((t) => t.id), ['a', 'after-cutover']);
  assert.equal(exported.body.checksum, await checksum(exported.body.library));
  const back = await admin(uid, 'rollback-commit', { checksum: exported.body.checksum });
  assert.equal(back.body.state, 'legacy');
  assert.equal((await authority(uid)).state, 'legacy');
  watching.close();
});

test('a rollback can be cancelled and the account keeps working here', async () => {
  const uid = account();
  await migrate(uid, null);
  await admin(uid, 'rollback-begin');
  const resumed = await admin(uid, 'rollback-abort');
  assert.equal(resumed.body.state, 'cloudflare');
  const ack = await rpc(uid, 'pc', { t: 'push', v: 1, epoch: resumed.body.epoch, ops: [op({ t: 'fav.put', v: song('a') })] });
  assert.equal(ack.results[0].s, 'ok');
});

test('an idle connection does no database work, and a like writes three rows', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { favorites: Array.from({ length: 300 }, (_, i) => song(`f${i}`)) });
  const [phone, pc] = [await socket(uid, 'phone'), await socket(uid, 'pc')];
  // With removal records present too: they must not cause a scan until one is old enough to expire.
  await phone.call({ t: 'push', v: 1, epoch, ops: [op({ t: 'fav.del', key: 'f0' }), op({ t: 'pl.put', ifRev: 0, v: playlist('tmp', []), tracks: [] })] });
  await phone.call({ t: 'push', v: 1, epoch, ops: [op({ t: 'pl.del', key: 'tmp', ifRev: phone.ready.rev + 1, at: Date.now() })] });
  phone.ready = await phone.call({ t: 'hello', v: 1 });
  await pc.next((f) => f.t === 'changed' && f.rev === phone.ready.rev);
  const before = (await phone.call({ t: 'stats' })).rows;
  for (let i = 0; i < 10; i += 1) { phone.raw('ping'); pc.raw('ping'); await new Promise((r) => setTimeout(r, 100)); }
  // A device that is already current asks again: still nothing to read.
  await pc.call({ t: 'hello', v: 1 });
  assert.equal((await pc.call({ t: 'pull', cursor: phone.ready.rev })).items.length, 0);
  const idle = (await phone.call({ t: 'stats' })).rows;
  assert.deepEqual(idle, before, 'pings, hello and an up-to-date pull read and wrote nothing');
  await phone.call({ t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('one-like') })] });
  const after = (await phone.call({ t: 'stats' })).rows;
  assert.equal(after.written - idle.written, 3, 'the song, the operation id and the counters');
  assert.ok(after.read - idle.read <= 3, `a like read ${after.read - idle.read} rows`);
  phone.close(); pc.close();
});

test('a removal record past the offline window is dropped, and devices older than it start over', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { playlists: [playlist('old', [song('a')]), playlist('keep', [song('b')])], favorites: [song('f')] });
  const start = (await rpc(uid, 'pc', { t: 'hello', v: 1 })).rev;
  const WINDOW = 180 * 86_400_000;
  // Deleted "180 days ago, less two seconds": the record is due to expire in two seconds.
  const ack = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'pl.del', key: 'old', ifRev: start, at: Date.now() - WINDOW + 2000 })] });
  assert.equal(ack.results[0].s, 'ok');
  assert.ok((await rpc(uid, 'phone', { t: 'pull', cursor: start })).playlists.find((p) => p.id === 'old').deletedAt > 0, 'still told while the record lives');
  await new Promise((r) => setTimeout(r, 2300));
  assert.equal((await rpc(uid, 'phone', { t: 'pull', cursor: start })).t, 'reset', 'a device from before the removal can no longer be told about it');
  const all = await fullPull(uid, 'phone');
  assert.deepEqual(all.playlists.map((p) => p.id), ['keep']);
  assert.equal((await rpc(uid, 'pc', { t: 'pull', cursor: ack.rev })).t, 'delta', 'a device that had already seen it carries on');
});

test('account deletion erases the library here and it cannot be uploaded again', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { playlists: [playlist('p', [song('a')])], favorites: [song('f')] });
  const other = await socket(uid, 'tablet');
  assert.equal((await rpc(uid, 'phone', { t: 'erase', v: 2 })).code, 'version');
  assert.equal((await rpc(uid, 'phone', { t: 'erase', v: 1 })).t, 'erased');
  assert.equal((await other.next((f) => f.t === 'authority')).state, 'migrating', 'other devices are told at once');
  const left = await admin(uid, 'verify');
  assert.deepEqual(left.body.counts, { playlists: 0, tracks: 0, favorites: 0, albums: 0, artists: 0, pins: 0, deleted: 0 });
  assert.equal(left.body.job.dir, 'erased');
  // A device whose sign-in has not expired yet still holds the library locally.
  assert.equal((await rpc(uid, 'tablet', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('f') })] })).code, 'authority');
  assert.equal((await rpc(uid, 'tablet', { t: 'pull', full: true })).code, 'authority');
  assert.equal((await admin(uid, 'begin')).status, 409, 'and it is not migrated again by accident');
  // Erasing again succeeds, so a deletion that failed afterwards can be retried.
  assert.equal((await rpc(uid, 'phone', { t: 'erase', v: 1 })).t, 'erased');
});

test('erase also removes a library archived by a rollback, or one in the middle of moving', async () => {
  const archived = account();
  await migrate(archived, { favorites: [song('f')] });
  await admin(archived, 'rollback-begin'); await admin(archived, 'rollback-commit');
  assert.equal((await admin(archived, 'verify')).body.counts.favorites, 1, 'the rollback kept its rows as an archive');
  assert.equal((await rpc(archived, 'phone', { t: 'erase', v: 1 })).t, 'erased');
  assert.equal((await admin(archived, 'verify')).body.counts.favorites, 0);
  const moving = account();
  await admin(moving, 'begin');
  assert.equal((await rpc(moving, 'phone', { t: 'erase', v: 1 })).t, 'erased');
  const { library } = fromUserDocument({ favorites: [song('late')] });
  assert.equal((await admin(moving, 'import', { library, checksum: await checksum(library), source: { exists: true } })).status, 409, 'the migration cannot continue into an erased account');
  const never = account();
  assert.equal((await rpc(never, 'phone', { t: 'erase', v: 1 })).t, 'erased', 'an account never moved is fine to erase too');
});

test('an acknowledgment says whether this batch alone moved the revision', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { favorites: [song('x')] });
  const start = (await rpc(uid, 'pc', { t: 'hello', v: 1 })).rev;
  const changed = await rpc(uid, 'pc', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('y') })] });
  assert.deepEqual([changed.from, changed.rev, changed.changed], [start, start + 1, true]);
  const nothing = await rpc(uid, 'phone', { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('x') })] });
  assert.deepEqual([nothing.from, nothing.rev, nothing.changed], [start + 1, start + 1, false], 'a no-op names the current revision but changed nothing');
});

test('a socket whose sign-in runs out is closed, not served', async () => {
  const uid = account();
  await migrate(uid, null);
  const c = await connect(PORT, uid, 'pc', { path: '/v1/library', token: `dev:${uid}:700` });
  assert.equal((await c.call({ t: 'hello', v: 1 })).t, 'ready');
  await new Promise((r) => setTimeout(r, 900));
  c.raw(JSON.stringify({ t: 'hello', v: 1, r: 99 }));
  assert.equal(await c.closedWith(), 4401);
});

test('everything survives a restart: state, edits and the memory of applied operations', async () => {
  const uid = account();
  const { epoch } = await migrate(uid, { playlists: [playlist('p', [song('a')])] });
  const frame = { t: 'push', v: 1, epoch, ops: [op({ t: 'fav.put', v: song('kept') })] };
  const first = await rpc(uid, 'pc', frame);
  // The server stops right after storing the edit: no other device was told.
  await stopServer(server);
  server = await startServer(PORT, server.persistDir);
  assert.deepEqual(await authority(uid), { state: 'cloudflare', epoch, v: 1 });
  const again = await rpc(uid, 'pc', frame);
  assert.deepEqual(again.results.map((r) => [r.s, r.rev]), [['dup', first.rev]], 'the retry after the restart is recognised');
  const behind = await rpc(uid, 'phone', { t: 'pull', cursor: first.rev - 1 });
  assert.deepEqual(behind.items.map((i) => i.id), ['kept'], 'a device that missed the notification catches up from its cursor');
  assert.equal((await fullPull(uid, 'x')).playlists[0].tracks[0].id, 'a');
}, { timeout: 120_000 });
