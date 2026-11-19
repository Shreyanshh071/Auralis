import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AutomaticLibrary } from '../src/library-auto.js';
import { checksum, fromUserDocument, toUserDocument } from '../src/library-core.js';
import { reconcileLibrary, sameLibrary } from '../src/library-bridge-core.js';
import { LegacyUnavailable } from '../src/library-firestore.js';
const clone = (x) => structuredClone(x);
const song = (id) => ({ id, title: id, artist: 'A', album: null, thumbnail: '', duration: 100 });
const playlist = (id, n) => ({ id, title: id, createdAt: 1, tracks: Array.from({ length: n }, (_, i) => song(`${id}-${i}`)) });
const empty = () => fromUserDocument(null).library;
function fixture(doc, exists = true) {
 let state = null; let cloud = empty(); let version = 1; let legacy = clone(doc); let marker = null;
 const f = { reads: 0, writes: 0, commits: 0, fail: false, loseAck: false, conflict: null, fenced: false, now: 100000 };
 const firestore = {
  async removeFence() { if (f.failFence) throw new LegacyUnavailable('legacy-fence-412'); f.fenced = false; },
  async get(path) {
   f.reads++; if (f.fail) throw new LegacyUnavailable('legacy-429', 900000);
   if (path.startsWith('libraryAuthority/')) return { exists: f.fenced, data: {}, updateTime: 'f1' };
   if (path.startsWith('libraryBridgeJournal/')) return { exists: !!marker, data: clone(marker), updateTime: 'j1' };
   return { exists, data: clone(legacy), updateTime: exists ? String(version) : null };
  },
  async compareAndSet(uid, source, data, pending) {
   f.writes++;
   if (f.conflict) { const cb = f.conflict; f.conflict = null; cb(); return null; }
   if (source.updateTime !== (exists ? String(version) : null)) return null;
   legacy = { ...legacy, ...clone(data) }; exists = true; version++; marker = { id: pending.id, checksum: pending.checksum };
   if (f.loseAck) { f.loseAck = false; throw new LegacyUnavailable('legacy-network'); }
   return String(version);
  },
 };
 f.deleteRoot = () => { exists = false; legacy = null; version++; };
 f.client = () => new AutomaticLibrary({ firestore, load: () => clone(state), save: (x) => { state = clone(x); }, snapshot: () => clone(cloud),
  commit: async (lib, s) => { cloud = clone(lib); state = clone(s); f.commits++; }, now: () => f.now });
 Object.defineProperties(f, { state: { get: () => clone(state) }, cloud: { get: () => clone(cloud), set: (x) => { cloud = clone(x); } },
  legacy: { get: () => clone(legacy), set: (x) => { legacy = clone(x); version++; exists = true; } } });
 return f;
}

test('Pinkman: add track 112 and delete Hi concurrently, preserving Gilli and duplicate/order data', async () => {
 const f = fixture({ playlists: [playlist('Gilli', 111), playlist('Hi', 10)], favorites: [song('like')],
  librarySync: { pins: ['Gilli'], pinsAt: 1 } });
 await f.client().initialize('test');
 const cloud = f.cloud; cloud.playlists = cloud.playlists.filter((p) => p.id !== 'Hi'); f.cloud = cloud;
 const legacy = f.legacy; legacy.playlists[0].tracks.push(song('new')); f.legacy = legacy;
 await f.client().synchronize({ force: true });
 assert.deepEqual(f.cloud.playlists.map((p) => p.id), ['Gilli']);
 assert.equal(f.cloud.playlists[0].tracks.length, 112);
 assert.equal(f.cloud.playlists[0].tracks.at(-1).id, 'new');
 assert.deepEqual(f.legacy.playlists.map((p) => p.id), ['Gilli']);
 assert.equal(f.legacy.playlists[0].tracks.length, 112);
 assert.deepEqual(f.cloud.pins, ['Gilli']); assert.equal(f.cloud.favorites[0].id, 'like');
 const writes = f.writes; await f.client().synchronize({ force: true });
 assert.equal(f.writes, writes, 'bridge echoes make no writes');
});

test('new account missing source initializes once; re-login never overwrites accepted cloud edits', async () => {
 const f = fixture(null, false); await f.client().initialize('test');
 const cloud = f.cloud; cloud.favorites.push(song('local')); f.cloud = cloud;
 await f.client().initialize('test'); assert.equal(f.cloud.favorites[0].id, 'local'); assert.equal(f.commits, 1);
 await f.client().synchronize({ force: true }); assert.equal(f.legacy.favorites[0].id, 'local');
});

test('import source errors, malformed entries and old fences never become empty success', async () => {
 const f = fixture({ playlists: [] }); f.fail = true;
 await assert.rejects(f.client().initialize('test'), /legacy-429/); assert.equal(f.state, null);
 for (const source of [{ playlists: 'bad' }, { playlists: [null] }, { playlists: [{ id: 'x', tracks: [null] }] },
  { librarySync: null }, { librarySync: { pins: [''] } }, { librarySync: { deleted: [null] } }]) {
  const bad = fixture(source); await assert.rejects(bad.client().initialize('test'), /legacy-shape/); assert.equal(bad.commits, 0);
 }
 const fenced = fixture({}); fenced.fenced = true;
 await assert.rejects(fenced.client().initialize('test'), /legacy-fenced/); assert.equal(fenced.commits, 0);
});

test('CAS retries preserve legacy writes racing a cloud mirror without duplicate additions', async () => {
 const f = fixture({ playlists: [playlist('p', 2)] }); await f.client().initialize('test');
 const cloud = f.cloud; cloud.playlists[0].tracks.push(song('cloud')); f.cloud = cloud;
 const legacy = f.legacy; legacy.playlists[0].tracks.push(song('legacy')); f.legacy = legacy;
 f.conflict = () => { const next = f.legacy; next.playlists[0].tracks.push(song('racing')); f.legacy = next; };
 await f.client().synchronize({ force: true });
 const ids = f.cloud.playlists[0].tracks.map((t) => t.id);
 assert.equal(ids.length, 5); for (const id of ['cloud', 'legacy', 'racing']) assert.equal(ids.filter((x) => x === id).length, 1);
 assert.deepEqual(f.legacy.playlists[0].tracks.map((t) => t.id), ids);
});

test('lost mirror acknowledgement survives a new coordinator plus another legacy edit', async () => {
 const f = fixture({ playlists: [playlist('p', 1)] }); await f.client().initialize('test');
 const cloud = f.cloud; cloud.playlists[0].tracks.push(song('cloud')); f.cloud = cloud; f.loseAck = true;
 await assert.rejects(f.client().synchronize({ force: true }), /legacy-network/); assert.ok(f.state.pending);
 const legacy = f.legacy; legacy.playlists[0].tracks.push(song('later-legacy')); f.legacy = legacy;
 await f.client().synchronize({ force: true });
 assert.deepEqual(f.cloud.playlists[0].tracks.map((t) => t.id), ['p-0', 'cloud', 'later-legacy']);
 assert.equal(f.state.pending, null); assert.equal(f.state.failures, 0);
});

test('quota after cutover leaves cloud data usable and backs legacy work off', async () => {
 const f = fixture({ favorites: [song('old')] }); await f.client().initialize('test');
 const cloud = f.cloud; cloud.favorites.push(song('accepted')); f.cloud = cloud; f.fail = true;
 await assert.rejects(f.client().synchronize({ force: true }));
 assert.equal(f.cloud.favorites.length, 2); assert.ok(f.state.nextAt >= f.now + 900000);
 const reads = f.reads; await f.client().synchronize(); assert.equal(f.reads, reads);
});

test('a legacy full replacement propagates an explicit unlike', async () => {
 const f = fixture({ favorites: [song('old')] }); await f.client().initialize('test');
 // A complete legacy replacement is valid but explicit deletes within it must propagate.
 f.legacy = { favorites: [] }; await f.client().synchronize({ force: true }); assert.equal(f.cloud.favorites.length, 0);
});

test('a deleted legacy root is held for investigation, never silently recreated', async () => {
 const f = fixture({ favorites: [song('old')] }); await f.client().initialize('test');
 f.deleteRoot(); await assert.rejects(f.client().synchronize({ force: true }), /legacy-document-deleted/);
 assert.equal(f.writes, 0); assert.equal(f.cloud.favorites.length, 1);
});

test('fenced adoption verifies the original import, mirrors newer edits, then lifts the fence', async () => {
 const f = fixture({ favorites: [song('old')] });
 const baseline = fromUserDocument(f.legacy).library;
 f.cloud = { ...clone(baseline), favorites: [song('new')] }; f.fenced = true;
 await assert.rejects(f.client().adoptFenced('test', 'wrong'), /legacy-adoption-source/);
 assert.equal(f.fenced, true); assert.equal(f.state, null);
 await f.client().adoptFenced('test', await checksum(baseline));
 assert.deepEqual(f.legacy.favorites.map((x) => x.id), ['new']); assert.equal(f.fenced, false);
 assert.equal(f.state.adoptionFence, null); assert.equal(f.state.phase, 'ready');
});

test('failed conditional fence release is resumable and keeps the verified mirror', async () => {
 const f = fixture({ favorites: [song('old')] }); const baseline = fromUserDocument(f.legacy).library;
 f.cloud = { ...clone(baseline), favorites: [song('new')] }; f.fenced = true; f.failFence = true;
 await assert.rejects(f.client().adoptFenced('test', await checksum(baseline)), /legacy-fence-412/);
 assert.equal(f.fenced, true); assert.ok(f.state.adoptionFence); assert.ok(f.state.pending);
 f.failFence = false; await f.client().synchronize({ force: true });
 assert.equal(f.fenced, false); assert.equal(f.state.pending, null);
 assert.deepEqual(f.cloud.favorites.map((x) => x.id), ['new']);
});

test('three-way merge keeps duplicate occurrences, removals and independent metadata/saves', () => {
 const base = fromUserDocument({ playlists: [{ ...playlist('p', 0), tracks: [song('a'), song('a'), song('b')] }], favorites: [song('f')] }).library;
 const cloud = clone(base); cloud.playlists[0].tracks.splice(1, 1); cloud.favorites = []; cloud.albums.push({ id: 'al', title: 'Album' });
 const legacy = clone(base); legacy.playlists[0].tracks.push(song('c')); legacy.artists.push({ id: 'ar', name: 'Artist' });
 const result = reconcileLibrary(base, cloud, legacy, 10000);
 assert.deepEqual(result.playlists[0].tracks.map((t) => t.id), ['a', 'b', 'c']); assert.equal(result.favorites.length, 0);
 assert.equal(result.albums[0].id, 'al'); assert.equal(result.artists[0].id, 'ar');
 assert.ok(sameLibrary(result, fromUserDocument(toUserDocument(result, 10000), 10000).library));
});
