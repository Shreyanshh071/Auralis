// Behaviour tests for the queue and playlist storage rules.
//
// Run with:  node scripts/test-queue-storage.mjs
//
// This imports src/lib/queueStorage.ts directly. Node strips the type
// annotations itself (built in from Node 22.18 / 23.6 onward), which is safe
// here because tsconfig.app.json sets "erasableSyntaxOnly": true, so no file in
// src can contain TypeScript that needs real transformation. Nothing is copied
// or re-implemented: the assertions below run against the same module the app
// imports.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'lib', 'queueStorage.ts');

let mod;
try {
  mod = await import(pathToFileURL(modulePath).href);
} catch (err) {
  const [major, minor] = process.versions.node.split('.').map(Number);
  const canStripTypes = major > 22 || (major === 22 && minor >= 18);
  if (!canStripTypes) {
    console.error(
      `This test loads a .ts module directly, which needs Node 22.18+ (running ${process.versions.node}).`,
    );
  }
  throw err;
}

const {
  QUEUE_STORAGE_KEY,
  PLAYLISTS_STORAGE_KEY,
  emptyStoredQueue,
  isTrackLike,
  parseStoredQueue,
  loadStoredQueue,
  saveStoredQueue,
  asUserPlaylist,
  parseStoredPlaylists,
  loadStoredPlaylists,
} = mod;

/** Minimal stand-in for window.localStorage. */
function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => {
      data.set(k, String(v));
    },
    raw: data,
  };
}

const track = (id, title = `Track ${id}`) => ({
  id,
  title,
  artist: 'Someone',
  thumbnail: `https://i.ytimg.com/vi/${id}/hqdefault.jpg`,
  duration: 180,
});

test('a saved queue comes back with the same tracks, index and current track', () => {
  const storage = fakeStorage();
  const tracks = [track('aaa'), track('bbb'), track('ccc')];

  saveStoredQueue({ tracks, index: 2, currentTrack: tracks[2] }, storage);
  // The key matters: it is what a returning session reads.
  assert.equal(QUEUE_STORAGE_KEY, 'auralis_queue');
  assert.ok(storage.raw.has('auralis_queue'));

  const restored = loadStoredQueue(storage);
  assert.deepEqual(
    restored.tracks.map((t) => t.id),
    ['aaa', 'bbb', 'ccc'],
  );
  assert.equal(restored.index, 2);
  assert.equal(restored.currentTrack.id, 'ccc');
});

test('nothing stored yields an empty queue and no current track', () => {
  const restored = loadStoredQueue(fakeStorage());
  assert.deepEqual(restored.tracks, []);
  assert.equal(restored.index, 0);
  assert.equal(restored.currentTrack, null);
});

test('storage that is unavailable is treated as empty, not as an error', () => {
  assert.deepEqual(loadStoredQueue(null), emptyStoredQueue());
  assert.deepEqual(loadStoredPlaylists(null), []);
  // Saving with no storage must not throw; playback continues either way.
  saveStoredQueue({ tracks: [track('aaa')], index: 0, currentTrack: null }, null);
});

test('a storage that throws on read yields an empty queue', () => {
  const hostile = {
    getItem() {
      throw new Error('SecurityError: storage is blocked');
    },
    setItem() {},
  };
  assert.deepEqual(loadStoredQueue(hostile), emptyStoredQueue());
  assert.deepEqual(loadStoredPlaylists(hostile), []);
});

test('truncated or non-JSON values are discarded', () => {
  assert.deepEqual(parseStoredQueue('{"tracks":[{"id":"aaa"'), emptyStoredQueue());
  assert.deepEqual(parseStoredQueue('not json at all'), emptyStoredQueue());
  assert.deepEqual(parseStoredQueue('null'), emptyStoredQueue());
  assert.deepEqual(parseStoredQueue('[]'), emptyStoredQueue());
  assert.deepEqual(parseStoredQueue(''), emptyStoredQueue());
  // A payload whose tracks are not an array cannot be rendered as a queue.
  assert.deepEqual(parseStoredQueue('{"tracks":"aaa","index":0}'), emptyStoredQueue());
});

test('entries that could never be played are dropped', () => {
  assert.equal(isTrackLike(track('aaa')), true);
  assert.equal(isTrackLike({ title: 'No id' }), false);
  assert.equal(isTrackLike({ id: '', title: 'Empty id' }), false);
  assert.equal(isTrackLike({ id: 'aaa' }), false);
  assert.equal(isTrackLike(null), false);
  assert.equal(isTrackLike('aaa'), false);

  const restored = parseStoredQueue(
    JSON.stringify({
      tracks: [track('aaa'), { id: '', title: 'broken' }, null, track('bbb'), { title: 'no id' }],
      index: 3,
      currentTrack: track('bbb'),
    }),
  );
  assert.deepEqual(
    restored.tracks.map((t) => t.id),
    ['aaa', 'bbb'],
  );
  // The stored index (3) pointed past the surviving tracks; the current track is
  // the anchor, so the index follows it to its real position.
  assert.equal(restored.index, 1);
});

test('an out-of-range or non-numeric index is brought back in range', () => {
  const tracks = [track('aaa'), track('bbb')];
  const at = (index) => parseStoredQueue(JSON.stringify({ tracks, index, currentTrack: null })).index;

  assert.equal(at(99), 0);
  assert.equal(at(-4), 0);
  assert.equal(at('1'), 0);
  assert.equal(at(null), 0);
  assert.equal(at(Number.NaN), 0);
  assert.equal(at(Number.POSITIVE_INFINITY), 0);
  assert.equal(at(1.7), 1);
  assert.equal(at(1), 1);
});

test('a current track missing from the queue is still restored, with a usable index', () => {
  const tracks = [track('aaa'), track('bbb')];
  const restored = parseStoredQueue(
    JSON.stringify({ tracks, index: 1, currentTrack: track('zzz') }),
  );
  assert.equal(restored.currentTrack.id, 'zzz');
  assert.equal(restored.index, 1);
  assert.ok(restored.index < restored.tracks.length);
});

test('a malformed current track is dropped but the queue survives', () => {
  const restored = parseStoredQueue(
    JSON.stringify({ tracks: [track('aaa')], index: 0, currentTrack: { title: 'no id' } }),
  );
  assert.equal(restored.currentTrack, null);
  assert.equal(restored.tracks.length, 1);
});

test('each empty queue is a fresh object, so restored state cannot be aliased', () => {
  const a = emptyStoredQueue();
  a.tracks.push(track('aaa'));
  assert.deepEqual(emptyStoredQueue().tracks, []);
});

test('every stored playlist reads back as one the user can edit and delete', () => {
  assert.equal(PLAYLISTS_STORAGE_KEY, 'auralis_playlists');

  // A playlist written by a build that never set isCustom. LibraryView gates the
  // delete and remove-track controls on that flag, so without normalising here
  // it would stay permanently undeletable.
  const legacy = {
    id: 'pl-1',
    title: 'Old mix',
    tracks: [track('aaa')],
    createdAt: 1,
  };
  const [restored] = parseStoredPlaylists(JSON.stringify([legacy]));
  assert.equal(restored.isCustom, true);
  assert.equal(restored.id, 'pl-1');
  assert.equal(restored.tracks.length, 1);

  // Explicit false is normalised too: it reached storage, so it is the user's.
  const [flagged] = parseStoredPlaylists(JSON.stringify([{ ...legacy, isCustom: false }]));
  assert.equal(flagged.isCustom, true);

  assert.equal(asUserPlaylist({ ...legacy }).isCustom, true);
});

test('unusable stored playlists are dropped rather than rendered', () => {
  assert.deepEqual(parseStoredPlaylists(null), []);
  assert.deepEqual(parseStoredPlaylists('{"not":"an array"}'), []);
  assert.deepEqual(parseStoredPlaylists('broken'), []);

  const kept = parseStoredPlaylists(
    JSON.stringify([
      { id: 'pl-1', title: 'Good', tracks: [], createdAt: 1 },
      { id: 'pl-2', title: 'No tracks array', createdAt: 1 },
      { title: 'No id', tracks: [], createdAt: 1 },
      null,
      'nonsense',
    ]),
  );
  assert.deepEqual(
    kept.map((p) => p.id),
    ['pl-1'],
  );
});

test('junk tracks inside a stored playlist are dropped', () => {
  const [restored] = parseStoredPlaylists(
    JSON.stringify([
      {
        id: 'pl-1',
        title: 'Mixed',
        createdAt: 1,
        tracks: [track('aaa'), { id: '' }, null, track('bbb')],
      },
    ]),
  );
  assert.deepEqual(
    restored.tracks.map((t) => t.id),
    ['aaa', 'bbb'],
  );
});

test('a playlist survives a full save and reload cycle', () => {
  const storage = fakeStorage();
  const created = {
    id: 'pl-9',
    title: 'Late night',
    tracks: [track('aaa'), track('bbb')],
    createdAt: Date.now(),
    isCustom: true,
  };
  // This is what PlayerContext writes on every playlists change.
  storage.setItem(PLAYLISTS_STORAGE_KEY, JSON.stringify([created]));

  const [reloaded] = loadStoredPlaylists(storage);
  assert.equal(reloaded.title, 'Late night');
  assert.deepEqual(
    reloaded.tracks.map((t) => t.id),
    ['aaa', 'bbb'],
  );
  assert.equal(reloaded.isCustom, true);
});
