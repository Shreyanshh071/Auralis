// Tests for the listening-history store and the MediaSession / background-audio
// wiring added for OS lock-screen controls and Android background playback.
//
// Run with:  node scripts/test-media-session-history.mjs
//
// The history store is exercised behaviourally (it is fed whatever is in
// localStorage, including values from older builds). The MediaSession and
// Android integration are asserted at the source level — the same approach
// test-playback-position.mjs uses for PlayerContext — because they depend on
// browser / native APIs that a Node unit test cannot drive.

import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

import {
  HISTORY_STORAGE_KEY,
  HISTORY_LIMIT,
  parseStoredHistory,
  loadHistory,
  saveHistory,
  addToHistory,
  removeFromHistory,
  historyTracks,
} from '../src/lib/historyStorage.ts';

/** Minimal in-memory stand-in for localStorage. */
function fakeStorage(initial = {}) {
  const map = new Map(Object.entries(initial));
  return {
    map,
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, v),
    removeItem: (k) => map.delete(k),
  };
}

/** A storage that throws on everything, like a blocked/private-mode one. */
const hostileStorage = {
  getItem() {
    throw new Error('blocked');
  },
  setItem() {
    throw new Error('QuotaExceededError');
  },
  removeItem() {
    throw new Error('blocked');
  },
};

const track = (id, over = {}) => ({
  id,
  title: `Title ${id}`,
  artist: `Artist ${id}`,
  duration: 200,
  thumbnail: `https://img.example/${id}.jpg`,
  ...over,
});

const entry = (id, playedAt = 1_700_000_000_000) => ({ track: track(id), playedAt });

// ---------------------------------------------------------------------------
// Storage contract
// ---------------------------------------------------------------------------

test('the history key is namespaced to Auralis and the cap is 100', () => {
  assert.equal(HISTORY_STORAGE_KEY, 'auralis_history');
  assert.equal(HISTORY_LIMIT, 100);
});

test('a full round trip preserves tracks, order and timestamps', () => {
  const storage = fakeStorage();
  const entries = [entry('a', 3), entry('b', 2), entry('c', 1)];
  saveHistory(entries, storage);

  const loaded = loadHistory(storage);
  assert.deepEqual(
    loaded.map((e) => e.track.id),
    ['a', 'b', 'c'],
  );
  assert.deepEqual(
    loaded.map((e) => e.playedAt),
    [3, 2, 1],
  );
});

// ---------------------------------------------------------------------------
// addToHistory
// ---------------------------------------------------------------------------

test('addToHistory prepends the most recently played track', () => {
  let entries = [];
  entries = addToHistory(entries, track('a'), 1);
  entries = addToHistory(entries, track('b'), 2);
  assert.deepEqual(
    entries.map((e) => e.track.id),
    ['b', 'a'],
  );
  assert.equal(entries[0].playedAt, 2);
});

test('replaying a track floats it to the top exactly once (no duplicates)', () => {
  let entries = [entry('a', 1), entry('b', 2), entry('c', 3)];
  entries = addToHistory(entries, track('b'), 99);
  assert.deepEqual(
    entries.map((e) => e.track.id),
    ['b', 'a', 'c'],
  );
  assert.equal(entries.filter((e) => e.track.id === 'b').length, 1);
  assert.equal(entries[0].playedAt, 99, 'the timestamp is refreshed to the new play');
});

test('history is capped at HISTORY_LIMIT, dropping the oldest', () => {
  let entries = [];
  for (let i = 0; i < HISTORY_LIMIT + 25; i++) {
    entries = addToHistory(entries, track(`t${i}`), i);
  }
  assert.equal(entries.length, HISTORY_LIMIT);
  // The most recent (highest i) is first; the oldest survivors sit at the tail.
  assert.equal(entries[0].track.id, `t${HISTORY_LIMIT + 24}`);
  assert.equal(entries.at(-1).track.id, `t25`);
});

test('addToHistory ignores a track with no usable id', () => {
  const before = [entry('a', 1)];
  const after = addToHistory(before, { title: 'no id' }, 5);
  assert.deepEqual(after, before);
});

test('a nonsensical timestamp is normalised to 0 rather than stored raw', () => {
  assert.equal(addToHistory([], track('a'), Number.NaN)[0].playedAt, 0);
  assert.equal(addToHistory([], track('a'), -5)[0].playedAt, 0);
  assert.equal(addToHistory([], track('a'), 42)[0].playedAt, 42);
});

// ---------------------------------------------------------------------------
// removeFromHistory & projection
// ---------------------------------------------------------------------------

test('removeFromHistory drops only the matching id', () => {
  const entries = [entry('a'), entry('b'), entry('c')];
  const after = removeFromHistory(entries, 'b');
  assert.deepEqual(
    after.map((e) => e.track.id),
    ['a', 'c'],
  );
});

test('removeFromHistory is a no-op for an unknown id', () => {
  const entries = [entry('a'), entry('b')];
  assert.deepEqual(removeFromHistory(entries, 'zzz'), entries);
});

test('historyTracks projects entries down to the plain Track[] the UI consumes', () => {
  const tracks = historyTracks([entry('a'), entry('b')]);
  assert.deepEqual(
    tracks.map((t) => t.id),
    ['a', 'b'],
  );
  assert.equal(tracks[0].title, 'Title a');
});

// ---------------------------------------------------------------------------
// parseStoredHistory — defensive against whatever is on disk
// ---------------------------------------------------------------------------

test('the current on-disk shape ({track, playedAt}) round-trips', () => {
  const raw = JSON.stringify([entry('a', 10), entry('b', 20)]);
  const parsed = parseStoredHistory(raw);
  assert.equal(parsed.length, 2);
  assert.equal(parsed[0].track.id, 'a');
  assert.equal(parsed[0].playedAt, 10);
});

test('a legacy bare-Track[] history is migrated, not discarded', () => {
  // Builds before timestamps stored a plain array of tracks. Upgrading must not
  // wipe the user's Recently Played list.
  const raw = JSON.stringify([track('old1'), track('old2')]);
  const parsed = parseStoredHistory(raw);
  assert.deepEqual(
    parsed.map((e) => e.track.id),
    ['old1', 'old2'],
  );
  assert.equal(parsed[0].playedAt, 0, 'unknown legacy timestamps read as 0');
});

test('a mixed legacy/new array parses both shapes', () => {
  const raw = JSON.stringify([entry('new', 5), track('legacy')]);
  const parsed = parseStoredHistory(raw);
  assert.deepEqual(
    parsed.map((e) => e.track.id),
    ['new', 'legacy'],
  );
});

test('duplicate ids on disk collapse to the first seen', () => {
  const raw = JSON.stringify([entry('a', 1), entry('a', 2), entry('b', 3)]);
  const parsed = parseStoredHistory(raw);
  assert.deepEqual(
    parsed.map((e) => e.track.id),
    ['a', 'b'],
  );
});

test('an over-long stored history is clamped to HISTORY_LIMIT on read', () => {
  const big = Array.from({ length: HISTORY_LIMIT + 40 }, (_, i) => entry(`t${i}`, i));
  assert.equal(parseStoredHistory(JSON.stringify(big)).length, HISTORY_LIMIT);
});

test('corrupted or non-array storage is ignored rather than crashing startup', () => {
  for (const raw of [null, '', 'not json', '{', '{}', 'null', '"a string"', '42']) {
    assert.deepEqual(parseStoredHistory(raw), [], `must reject ${String(raw).slice(0, 12)}`);
  }
});

test('unplayable entries are dropped individually, keeping the good ones', () => {
  const raw = JSON.stringify([
    entry('good1', 1),
    { track: { title: 'no id' }, playedAt: 2 },
    { playedAt: 3 },
    track('good2'),
    { title: 'bare junk' },
  ]);
  const parsed = parseStoredHistory(raw);
  assert.deepEqual(
    parsed.map((e) => e.track.id),
    ['good1', 'good2'],
  );
});

test('saveHistory enforces the cap even if handed an over-long array', () => {
  const storage = fakeStorage();
  const big = Array.from({ length: HISTORY_LIMIT + 50 }, (_, i) => entry(`t${i}`, i));
  saveHistory(big, storage);
  const written = JSON.parse(storage.getItem(HISTORY_STORAGE_KEY));
  assert.equal(written.length, HISTORY_LIMIT);
});

test('a blocked, full or missing storage never throws — losing history is not fatal', () => {
  assert.doesNotThrow(() => saveHistory([entry('a')], hostileStorage));
  assert.deepEqual(loadHistory(hostileStorage), []);
  assert.doesNotThrow(() => saveHistory([entry('a')], null));
  assert.deepEqual(loadHistory(null), []);
});

// ---------------------------------------------------------------------------
// PlayerContext MediaSession wiring (source-level, per repo convention)
// ---------------------------------------------------------------------------

test('PlayerContext wires MediaSession metadata, transport handlers and state', () => {
  const source = fs.readFileSync('src/context/PlayerContext.tsx', 'utf8');

  assert.ok(source.includes('navigator.mediaSession'), 'must use navigator.mediaSession');
  assert.ok(source.includes('new MediaMetadata'), 'must set track metadata for the OS UI');
  assert.ok(source.includes('setActionHandler'), 'must register transport action handlers');

  for (const action of [
    "'play'",
    "'pause'",
    "'previoustrack'",
    "'nexttrack'",
    "'seekto'",
    "'seekbackward'",
    "'seekforward'",
    "'stop'",
  ]) {
    assert.ok(source.includes(action), `must wire the ${action} MediaSession action`);
  }

  assert.ok(source.includes('playbackState'), 'must reflect playing/paused to the OS');
  assert.ok(source.includes('setPositionState'), 'must publish position to the OS scrubber');
});

test('PlayerContext maintains a background audio anchor', () => {
  const source = fs.readFileSync('src/context/PlayerContext.tsx', 'utf8');
  assert.ok(source.includes('createSilentLoopUrl'), 'must build a silent keepalive source');
  assert.ok(source.includes('audioAnchorRef'), 'must hold the keepalive element');
});

test('PlayerContext routes listening history through the tested store', () => {
  const source = fs.readFileSync('src/context/PlayerContext.tsx', 'utf8');
  for (const fn of ['loadHistory', 'saveHistory', 'addToHistory']) {
    assert.ok(source.includes(fn), `history must go through ${fn}`);
  }
  assert.ok(source.includes('clearHistory'), 'must expose clearHistory');
  assert.ok(source.includes('removeFromHistory'), 'must expose removeFromHistory');
  // The old inline 50-entry cap must be gone now that the store owns the limit.
  assert.ok(
    !/auralis_history[\s\S]{0,80}slice\(0,\s*50\)/.test(source),
    'the ad-hoc 50-entry history write must be replaced by the store',
  );
});

// ---------------------------------------------------------------------------
// Android background-audio integration (source-level)
// ---------------------------------------------------------------------------

test('the Android manifest declares the background-playback permissions', () => {
  const manifest = fs.readFileSync('android/app/src/main/AndroidManifest.xml', 'utf8');
  for (const perm of [
    'android.permission.WAKE_LOCK',
    'android.permission.FOREGROUND_SERVICE',
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',
  ]) {
    assert.ok(manifest.includes(perm), `manifest must declare ${perm}`);
  }
});

test('MainActivity configures the WebView for uninterrupted background audio', () => {
  const java = fs.readFileSync('android/app/src/main/java/com/auralis/music/MainActivity.java', 'utf8');
  assert.ok(
    java.includes('setMediaPlaybackRequiresUserGesture(false)'),
    'must allow gesture-free programmatic playback',
  );
  assert.ok(java.includes('resumeTimers'), 'must keep WebView timers alive when backgrounded');
  assert.ok(java.includes('onPause'), 'must hook the activity pause lifecycle');
});
