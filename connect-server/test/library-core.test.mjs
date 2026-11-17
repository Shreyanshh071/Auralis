import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonical, checksum, chunks, counts, fromUserDocument, library, operation, pilotAllowed, sameSecret, toUserDocument, CHUNK_TRACKS } from '../src/library-core.js';

const NOW = 1_800_000_000_000;
const song = (id, extra = {}) => ({ id, title: `Song ${id}`, artist: 'A', album: null, thumbnail: 'https://i/x.jpg', duration: 180, ...extra });

test('the user document becomes a library with ids, order and duplicates kept', () => {
  const doc = {
    playlists: [
      { id: 'p1', title: 'Road', description: null, coverUrl: null, createdAt: 5, isCustom: true, tracks: [song('a'), song('b'), song('a')] },
      { id: 'imported:PL9', title: 'Mix', createdAt: 9, isCustom: false, tracks: [song('c')] },
    ],
    favorites: [song('z'), song('a'), song('z')],
    savedAlbums: [{ id: 'al1', title: 'Album', artist: 'A', thumbnail: null, trackCount: 10, savedAt: 77 }],
    savedArtists: [{ id: 'ar1', name: 'Artist', thumbnail: null, subscribers: '1M' }],
    librarySync: { pins: ['p1'], pinsAt: 42, deleted: [{ id: 'gone', at: NOW - 5000 }, { id: 'p1', at: NOW - 9000 }, { id: 'ancient', at: 11 }] },
    preferences: { android: {} }, updatedAt: 1,
  };
  const { library: lib, dropped } = fromUserDocument(doc, NOW);
  assert.deepEqual(lib.playlists.map((p) => [p.id, p.tracks.map((t) => t.id)]), [['p1', ['a', 'b', 'a']], ['imported:PL9', ['c']]]);
  assert.equal(lib.playlists[1].isCustom, false);
  assert.deepEqual(lib.favorites.map((t) => t.id), ['z', 'a']);
  assert.deepEqual(lib.deleted, [{ id: 'gone', at: NOW - 5000 }], 'records for a playlist that exists again, or past their lifetime, are dropped');
  assert.deepEqual([lib.pins, lib.pinsAt], [['p1'], 42]);
  assert.deepEqual(dropped, { tracks: 0, items: 0 });
  assert.equal(library(lib).error, undefined);
});

test('unusable entries are counted, and a playlist without an id gets a stable one', () => {
  const doc = { playlists: [{ title: 'Old', tracks: [song('a'), { title: 'no id' }] }, { title: 'Old', tracks: [song('a')] }], favorites: [{ id: '' }, song('k')] };
  const first = fromUserDocument(doc);
  const again = fromUserDocument(doc);
  assert.deepEqual(first.dropped, { tracks: 1, items: 1 });
  assert.equal(first.library.playlists.length, 2);
  assert.match(first.library.playlists[0].id, /^legacy:[0-9a-f]{8}$/);
  assert.notEqual(first.library.playlists[0].id, first.library.playlists[1].id);
  assert.deepEqual(first.library.playlists.map((p) => p.id), again.library.playlists.map((p) => p.id));
});

test('a missing document is an empty library, not an error', () => {
  const { library: lib } = fromUserDocument(null);
  assert.deepEqual(counts(lib), { playlists: 0, tracks: 0, favorites: 0, albums: 0, artists: 0, pins: 0, deleted: 0 });
});

test('the checksum ignores playlist order and notices any change to a track or its position', async () => {
  const a = fromUserDocument({ playlists: [{ id: 'p1', title: 'A', tracks: [song('a'), song('b')] }, { id: 'p2', title: 'B', tracks: [] }] }).library;
  const swapped = { ...a, playlists: [...a.playlists].reverse() };
  assert.equal(await checksum(a), await checksum(swapped));
  const reordered = { ...a, playlists: [{ ...a.playlists[0], tracks: [...a.playlists[0].tracks].reverse() }, a.playlists[1]] };
  assert.notEqual(await checksum(a), await checksum(reordered));
  const retitled = { ...a, playlists: [{ ...a.playlists[0], title: 'A!' }, a.playlists[1]] };
  assert.notEqual(canonical(a), canonical(retitled));
});

test('a library survives the trip back to the document old clients read', async () => {
  const lib = fromUserDocument({ playlists: [{ id: 'p1', title: 'A', createdAt: 4, tracks: [song('a'), song('a')] }], favorites: [song('f')],
    savedAlbums: [{ id: 'al', title: 'T', savedAt: 3 }], savedArtists: [{ id: 'ar', name: 'N' }], librarySync: { pins: ['p1'], pinsAt: 8, deleted: [{ id: 'x', at: NOW - 2 }] } }, NOW).library;
  assert.equal(lib.deleted.length, 1);
  const back = fromUserDocument(toUserDocument(lib, 99), NOW).library;
  assert.equal(await checksum(back), await checksum(lib));
});

test('operations are checked and cleaned', () => {
  assert.equal(operation({ id: 'o1', t: 'fav.put', v: song('a') }).key, 'a');
  assert.equal(operation({ id: 'o1', t: 'fav.put', v: { title: 'x' } }), null);
  assert.equal(operation({ id: 'o1', t: 'fav.del', key: '' }), null);
  assert.equal(operation({ id: '', t: 'fav.del', key: 'a' }), null);
  assert.equal(operation({ id: 'o1', t: 'pl.put', ifRev: -1, v: { id: 'p' } }), null);
  assert.equal(operation({ id: 'o1', t: 'pl.put', ifRev: 0, v: { id: 'p' }, tracks: [{ nope: 1 }] }), null);
  assert.equal(operation({ id: 'o1', t: 'pl.put', ifRev: 0, v: { id: 'p', title: 'T' } }).tracks, null);
  assert.equal(operation({ id: 'o1', t: 'fav.put', v: song('a', { title: 'x'.repeat(5000) }) }), null, 'an oversized record is refused');
  assert.equal(operation({ id: 'o1', t: 'pins.set', pins: ['p'], at: 0 }), null);
  assert.equal(operation({ id: 'o1', t: 'drop.table' }), null);
});

test('tracks are stored in chunks so an append rewrites one', () => {
  const list = Array.from({ length: CHUNK_TRACKS * 2 + 1 }, (_, i) => song(`t${i}`));
  const before = chunks(list);
  const after = chunks([...list, song('new')]);
  assert.equal(before.length, 3);
  assert.deepEqual(after.slice(0, 2), before.slice(0, 2));
  assert.notEqual(after[2], before[2]);
});

test('secrets compare fully', () => {
  assert.equal(sameSecret('abc', 'abc'), true);
  assert.equal(sameSecret('abd', 'abc'), false);
  assert.equal(sameSecret('abcd', 'abc'), false);
  assert.equal(sameSecret('', ''), false);
  assert.equal(sameSecret(null, 'abc'), false);
});

test('only accounts on the pilot allowlist can be moved', () => {
  assert.equal(pilotAllowed('a', {}), false, 'unset allows none');
  assert.equal(pilotAllowed('a', { LIBRARY_PILOT_UIDS: '' }), false);
  assert.equal(pilotAllowed('a', { LIBRARY_PILOT_UIDS: 'a, b\nc' }), true);
  assert.equal(pilotAllowed('c', { LIBRARY_PILOT_UIDS: 'a, b\nc' }), true);
  assert.equal(pilotAllowed('ab', { LIBRARY_PILOT_UIDS: 'a,b' }), false, 'no partial matches');
  assert.equal(pilotAllowed('x', { LIBRARY_PILOT_UIDS: '*' }), false, 'a wildcard is refused outside local tests');
  assert.equal(pilotAllowed('x', { LIBRARY_PILOT_UIDS: '*', DEV_AUTH: 'local-tests-only' }), true);
  assert.equal(pilotAllowed('', { LIBRARY_PILOT_UIDS: ',' }), false);
  assert.equal(pilotAllowed('staging-e2e-1', { LIBRARY_PILOT_UIDS: 'staging-*', STAGING_SECRET: 's' }), true);
  assert.equal(pilotAllowed('staging-e2e-1', { LIBRARY_PILOT_UIDS: 'staging-*' }), false, 'never without the staging secret (production)');
  assert.equal(pilotAllowed('realUid123', { LIBRARY_PILOT_UIDS: 'staging-*', STAGING_SECRET: 's' }), false, 'real uids are not covered');
  assert.equal(pilotAllowed('staging-', { LIBRARY_PILOT_UIDS: 'staging-*', STAGING_SECRET: 's' }), false);
});
