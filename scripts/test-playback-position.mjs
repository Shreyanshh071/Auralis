// Behaviour tests for playback position persistence (resume where you left off).
//
// Run with:  node scripts/test-playback-position.mjs
//
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';

import {
  END_OF_TRACK_GUARD_SECONDS,
  MIN_RESUME_SECONDS,
  PLAYBACK_POSITION_SCHEMA_VERSION,
  PLAYBACK_POSITION_STORAGE_KEY,
  clearStoredPlaybackPosition,
  loadStoredPlaybackPosition,
  parseStoredPlaybackPosition,
  resumePositionFor,
  saveStoredPlaybackPosition,
} from '../src/lib/queueStorage.ts';

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

const record = (over = {}) => ({
  v: PLAYBACK_POSITION_SCHEMA_VERSION,
  trackId: 'abc123',
  position: 73,
  duration: 216,
  wasPlaying: false,
  savedAt: 1_700_000_000_000,
  ...over,
});

test('the storage key is versioned and namespaced to Auralis', () => {
  assert.equal(PLAYBACK_POSITION_STORAGE_KEY, 'auralis_playback_position');
  assert.equal(typeof PLAYBACK_POSITION_SCHEMA_VERSION, 'number');
});

test('a full round trip preserves the track, position and paused state', () => {
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: 'abc123', position: 73, duration: 216, wasPlaying: false, savedAt: 1234 },
    storage,
  );

  const loaded = loadStoredPlaybackPosition(storage);
  assert.equal(loaded.trackId, 'abc123');
  assert.equal(loaded.position, 73);
  assert.equal(loaded.duration, 216);
  assert.equal(loaded.wasPlaying, false);
  assert.equal(loaded.v, PLAYBACK_POSITION_SCHEMA_VERSION);
});

test('the writer stamps the schema version itself', () => {
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: 'x', position: 10, duration: 100, wasPlaying: true, savedAt: 1 },
    storage,
  );
  const raw = JSON.parse(storage.getItem(PLAYBACK_POSITION_STORAGE_KEY));
  assert.equal(raw.v, PLAYBACK_POSITION_SCHEMA_VERSION);
});

test('a record from a different schema version is discarded', () => {
  assert.equal(parseStoredPlaybackPosition(JSON.stringify(record({ v: 999 }))), null);
  assert.equal(parseStoredPlaybackPosition(JSON.stringify(record({ v: undefined }))), null);
});

test('corrupted storage is ignored rather than crashing startup', () => {
  for (const raw of [
    null,
    '',
    'not json',
    '{',
    '[]',
    'null',
    '"a string"',
    '42',
    JSON.stringify({ v: PLAYBACK_POSITION_SCHEMA_VERSION }),
    JSON.stringify(record({ trackId: '' })),
    JSON.stringify(record({ trackId: 42 })),
    JSON.stringify(record({ position: 'abc' })),
    JSON.stringify(record({ position: Number.NaN })),
    JSON.stringify(record({ position: -5 })),
  ]) {
    assert.equal(parseStoredPlaybackPosition(raw), null, `must reject ${String(raw).slice(0, 24)}`);
  }
});

test('a nonsensical duration is normalised to 0 rather than rejecting the record', () => {
  // Duration is only used as a fallback end-guard; a bad one must not cost the
  // user their position.
  for (const duration of [0, -1, Number.NaN, 'long', undefined]) {
    const parsed = parseStoredPlaybackPosition(JSON.stringify(record({ duration })));
    assert.ok(parsed, `duration ${String(duration)} should still parse`);
    assert.equal(parsed.duration, 0);
    assert.equal(parsed.position, 73);
  }
});

test('wasPlaying is coerced strictly, so a junk value reads as paused', () => {
  assert.equal(parseStoredPlaybackPosition(JSON.stringify(record({ wasPlaying: 'yes' }))).wasPlaying, false);
  assert.equal(parseStoredPlaybackPosition(JSON.stringify(record({ wasPlaying: 1 }))).wasPlaying, false);
  assert.equal(parseStoredPlaybackPosition(JSON.stringify(record({ wasPlaying: true }))).wasPlaying, true);
});

test('the worked example resumes: paused at 1:13 comes back at 1:13', () => {
  // "The Less I Know The Better", paused at 1:13 of 3:36.
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: 'sBzrzS1Ag_g', position: 73, duration: 216, wasPlaying: false, savedAt: 1 },
    storage,
  );

  const saved = loadStoredPlaybackPosition(storage);
  assert.equal(resumePositionFor(saved, 'sBzrzS1Ag_g', 216), 73);
  assert.equal(saved.wasPlaying, false, 'must come back paused, not playing');
});

test('a position for a different track does not leak onto the current one', () => {
  const saved = parseStoredPlaybackPosition(JSON.stringify(record({ trackId: 'other' })));
  assert.equal(resumePositionFor(saved, 'abc123', 216), 0);
});

test('no saved record, or no current track, resumes from the start', () => {
  assert.equal(resumePositionFor(null, 'abc123', 216), 0);
  assert.equal(resumePositionFor(record(), null, 216), 0);
  assert.equal(resumePositionFor(record(), undefined, 216), 0);
  assert.equal(resumePositionFor(record(), '', 216), 0);
});

test('a position in the opening seconds is not worth restoring', () => {
  for (const position of [0, 0.5, 1, MIN_RESUME_SECONDS - 0.01]) {
    assert.equal(resumePositionFor(record({ position }), 'abc123', 216), 0, `${position}s`);
  }
  assert.equal(resumePositionFor(record({ position: MIN_RESUME_SECONDS }), 'abc123', 216), MIN_RESUME_SECONDS);
});

test('a position in the final seconds is treated as finished', () => {
  const duration = 216;
  const lastSafe = duration - END_OF_TRACK_GUARD_SECONDS;
  assert.equal(resumePositionFor(record({ position: lastSafe }), 'abc123', duration), lastSafe);
  assert.equal(resumePositionFor(record({ position: lastSafe + 0.5 }), 'abc123', duration), 0);
  assert.equal(resumePositionFor(record({ position: duration }), 'abc123', duration), 0);
});

test('a position past the end of a shorter-than-expected track is rejected', () => {
  // The stored duration said 216s but the track actually resolves to 90s.
  assert.equal(resumePositionFor(record({ position: 200, duration: 216 }), 'abc123', 90), 0);
});

test('the stored duration is used when the app does not know one yet', () => {
  // On a cold start the restored track may have no duration; the end-guard must
  // still apply using the duration from the record.
  assert.equal(resumePositionFor(record({ position: 214, duration: 216 }), 'abc123', 0), 0);
  assert.equal(resumePositionFor(record({ position: 73, duration: 216 }), 'abc123', 0), 73);
});

test('an unknown duration on both sides still allows a resume', () => {
  assert.equal(resumePositionFor(record({ position: 73, duration: 0 }), 'abc123', 0), 73);
});

test('clearing removes the record so the next launch starts fresh', () => {
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: 'abc123', position: 73, duration: 216, wasPlaying: false, savedAt: 1 },
    storage,
  );
  assert.ok(loadStoredPlaybackPosition(storage));

  clearStoredPlaybackPosition(storage);
  assert.equal(loadStoredPlaybackPosition(storage), null);
  assert.equal(resumePositionFor(loadStoredPlaybackPosition(storage), 'abc123', 216), 0);
});

test('clearing works on a storage without removeItem', () => {
  const storage = fakeStorage();
  delete storage.removeItem;
  storage.setItem(PLAYBACK_POSITION_STORAGE_KEY, JSON.stringify(record()));

  clearStoredPlaybackPosition(storage);
  assert.equal(loadStoredPlaybackPosition(storage), null);
});

test('a first launch with no stored state is silent, not an error', () => {
  const storage = fakeStorage();
  assert.equal(loadStoredPlaybackPosition(storage), null);
  assert.equal(resumePositionFor(loadStoredPlaybackPosition(storage), 'abc123', 216), 0);
});

test('a blocked or full storage never throws — losing a resume point is not fatal', () => {
  assert.doesNotThrow(() =>
    saveStoredPlaybackPosition(
      { trackId: 'a', position: 5, duration: 10, wasPlaying: true, savedAt: 1 },
      hostileStorage,
    ),
  );
  assert.doesNotThrow(() => clearStoredPlaybackPosition(hostileStorage));
  assert.equal(loadStoredPlaybackPosition(hostileStorage), null);
});

test('a missing storage (SSR / no localStorage) is handled', () => {
  assert.doesNotThrow(() =>
    saveStoredPlaybackPosition(
      { trackId: 'a', position: 5, duration: 10, wasPlaying: true, savedAt: 1 },
      null,
    ),
  );
  assert.doesNotThrow(() => clearStoredPlaybackPosition(null));
  assert.equal(loadStoredPlaybackPosition(null), null);
});

test('a write without a track id is refused, so no orphan record is stored', () => {
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: '', position: 40, duration: 100, wasPlaying: true, savedAt: 1 },
    storage,
  );
  saveStoredPlaybackPosition(
    { trackId: 'ok', position: Number.NaN, duration: 100, wasPlaying: true, savedAt: 1 },
    storage,
  );
  assert.equal(storage.map.size, 0);
});

test('a negative position is clamped on write rather than corrupting the record', () => {
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: 'a', position: -12, duration: 100, wasPlaying: true, savedAt: 1 },
    storage,
  );
  assert.equal(loadStoredPlaybackPosition(storage).position, 0);
});

test('switching tracks overwrites the record instead of accumulating keys', () => {
  const storage = fakeStorage();
  saveStoredPlaybackPosition(
    { trackId: 'first', position: 50, duration: 200, wasPlaying: false, savedAt: 1 },
    storage,
  );
  saveStoredPlaybackPosition(
    { trackId: 'second', position: 9, duration: 180, wasPlaying: true, savedAt: 2 },
    storage,
  );

  assert.equal(storage.map.size, 1);
  const loaded = loadStoredPlaybackPosition(storage);
  assert.equal(loaded.trackId, 'second');
  assert.equal(loaded.position, 9);
  assert.equal(resumePositionFor(loaded, 'first', 200), 0, 'the old track must not resume');
});

test('PlayerContext writes on a throttle, not every frame', () => {
  const source = fs.readFileSync('src/context/PlayerContext.tsx', 'utf8');

  const interval = /POSITION_WRITE_INTERVAL_MS\s*=\s*(\d+)/.exec(source);
  assert.ok(interval, 'PlayerContext must define a write interval');
  assert.ok(
    Number(interval[1]) >= 1000,
    `write interval ${interval[1]}ms is too aggressive for localStorage`,
  );

  assert.ok(
    !/requestAnimationFrame[\s\S]{0,400}?saveStoredPlaybackPosition/.test(source),
    'the position must never be written from an animation frame',
  );
});

test('PlayerContext persists on the lifecycle events a phone actually fires', () => {
  const source = fs.readFileSync('src/context/PlayerContext.tsx', 'utf8');

  for (const event of ['visibilitychange', 'pagehide', 'beforeunload']) {
    assert.ok(source.includes(event), `PlayerContext must persist on ${event}`);
  }
  assert.ok(
    source.includes('persistPlaybackPosition'),
    'PlayerContext must route writes through a single persist helper',
  );
  assert.ok(
    source.includes('resumePositionFor'),
    'PlayerContext must decide the resume point through resumePositionFor',
  );
});

test('restoration does not auto-start playback', () => {
  const source = fs.readFileSync('src/context/PlayerContext.tsx', 'utf8');

  // The restore seam seeds currentTime but must leave isPlaying false; the
  // brief is explicit that reopening the app must not begin playing.
  const seamStart = source.indexOf('[restoredQueue]');
  assert.ok(seamStart > 0, 'PlayerContext must restore the stored queue');
  const seam = source.slice(seamStart, seamStart + 2500);

  assert.ok(seam.includes('initialResumeSeconds'), 'restore seam must compute a resume point');
  assert.ok(
    /setCurrentTime|useState<number>\(initialResumeSeconds\)/.test(seam),
    'restore seam must seed the current time from the resume point',
  );
  assert.ok(
    /const \[isPlaying, setIsPlaying\] = useState<boolean>\(false\)/.test(seam),
    'isPlaying must start false so a restored session comes back paused',
  );
  assert.ok(
    !/initialResumeSeconds[\s\S]{0,200}setIsPlaying\(true\)/.test(source),
    'restoring a position must not start playback',
  );
  // The clock anchor is moved to the resume point without flipping it to
  // running, so lyric synchronisation lines up with the restored time.
  assert.ok(
    /updateAnchor\(\s*\n?\s*initialResumeSeconds,\s*\n?\s*false/.test(source),
    'the playback clock must be anchored at the resume point in the paused state',
  );
});
