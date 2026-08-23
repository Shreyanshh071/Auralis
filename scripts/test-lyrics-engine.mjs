// Unit tests for pure lyrics timing engine and playback clock.
//
// Run with: node scripts/test-lyrics-engine.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const enginePath = path.join(repoRoot, 'src', 'lib', 'lyricsEngine.ts');
const clockPath = path.join(repoRoot, 'src', 'lib', 'playbackClock.ts');

const engineMod = await import(pathToFileURL(enginePath).href);
const clockMod = await import(pathToFileURL(clockPath).href);

const {
  findActiveLyricIndex,
  getActiveWordCount,
  isWordActive,
  getLineProgress,
  isInstrumentalBreak,
} = engineMod;

const { PlaybackClock } = clockMod;

// ---------------------------------------------------------------------------
// Lyrics Timing Engine: findActiveLyricIndex
// ---------------------------------------------------------------------------

const sampleLines = [
  { time: 10.0, text: 'First line' },
  { time: 15.5, text: 'Second line' },
  { time: 22.0, text: 'Third line' },
  { time: 30.0, text: 'Fourth line' },
];

test('findActiveLyricIndex: returns -1 for empty or null lines', () => {
  assert.equal(findActiveLyricIndex([], 12), -1);
  assert.equal(findActiveLyricIndex(null, 12), -1);
  assert.equal(findActiveLyricIndex(undefined, 12), -1);
});

test('findActiveLyricIndex: returns -1 before first line begins', () => {
  assert.equal(findActiveLyricIndex(sampleLines, 0), -1);
  assert.equal(findActiveLyricIndex(sampleLines, 9.99), -1);
});

test('findActiveLyricIndex: returns exact match at line timestamp', () => {
  assert.equal(findActiveLyricIndex(sampleLines, 10.0), 0);
  assert.equal(findActiveLyricIndex(sampleLines, 15.5), 1);
  assert.equal(findActiveLyricIndex(sampleLines, 22.0), 2);
  assert.equal(findActiveLyricIndex(sampleLines, 30.0), 3);
});

test('findActiveLyricIndex: returns current line during interval between lines', () => {
  assert.equal(findActiveLyricIndex(sampleLines, 12.3), 0);
  assert.equal(findActiveLyricIndex(sampleLines, 18.0), 1);
  assert.equal(findActiveLyricIndex(sampleLines, 29.9), 2);
});

test('findActiveLyricIndex: keeps last line active past the end', () => {
  assert.equal(findActiveLyricIndex(sampleLines, 35.0), 3);
  assert.equal(findActiveLyricIndex(sampleLines, 100.0), 3);
});

test('findActiveLyricIndex: respects manual timing offset', () => {
  // Line 0 is at 10.0s. If current time is 9.5s with +0.5s offset, it activates.
  assert.equal(findActiveLyricIndex(sampleLines, 9.5, 0.5), 0);
  // If current time is 10.2s with -0.5s offset (delayed), it is still before line 0.
  assert.equal(findActiveLyricIndex(sampleLines, 10.2, -0.5), -1);
});

test('findActiveLyricIndex: handles large line lists efficiently', () => {
  const largeLines = Array.from({ length: 500 }, (_, i) => ({
    time: i * 2,
    text: `Line ${i}`,
  }));
  assert.equal(findActiveLyricIndex(largeLines, 0), 0);
  assert.equal(findActiveLyricIndex(largeLines, 450.5), 225);
  assert.equal(findActiveLyricIndex(largeLines, 998.0), 499);
  assert.equal(findActiveLyricIndex(largeLines, 1200), 499);
});

// ---------------------------------------------------------------------------
// Lyrics Timing Engine: Word Timing
// ---------------------------------------------------------------------------

const richLine = {
  time: 10.0,
  text: 'Hello world from Auralis',
  words: [
    { word: 'Hello', time: 10.0 },
    { word: 'world', time: 10.5 },
    { word: 'from', time: 11.2 },
    { word: 'Auralis', time: 12.0 },
  ],
};

test('getActiveWordCount: returns -1 when no words array exists', () => {
  assert.equal(getActiveWordCount(undefined, 10.0), -1);
  assert.equal(getActiveWordCount([], 10.0), -1);
});

test('getActiveWordCount: counts elapsed words monotonically', () => {
  assert.equal(getActiveWordCount(richLine.words, 9.9), 0);
  assert.equal(getActiveWordCount(richLine.words, 10.0), 1);
  assert.equal(getActiveWordCount(richLine.words, 10.7), 2);
  assert.equal(getActiveWordCount(richLine.words, 11.5), 3);
  assert.equal(getActiveWordCount(richLine.words, 12.0), 4);
  assert.equal(getActiveWordCount(richLine.words, 20.0), 4);
});

test('isWordActive: checks individual word timestamp with offset', () => {
  assert.equal(isWordActive(10.5, 10.0, 0), false);
  assert.equal(isWordActive(10.5, 10.5, 0), true);
  assert.equal(isWordActive(10.5, 10.2, 0.4), true);
  assert.equal(isWordActive(10.5, 10.6, -0.2), false);
});

// ---------------------------------------------------------------------------
// Lyrics Timing Engine: Line Progress & Instrumental
// ---------------------------------------------------------------------------

test('getLineProgress: returns normalized [0, 1] progress through line duration', () => {
  const lineA = { time: 10.0, text: 'A' };
  const lineB = { time: 20.0, text: 'B' };

  assert.equal(getLineProgress(lineA, lineB, 9.0), 0);
  assert.equal(getLineProgress(lineA, lineB, 10.0), 0);
  assert.equal(getLineProgress(lineA, lineB, 15.0), 0.5);
  assert.equal(getLineProgress(lineA, lineB, 20.0), 1);
  assert.equal(getLineProgress(lineA, lineB, 25.0), 1);
});

test('isInstrumentalBreak: detects explicit flag or long gap', () => {
  const normalPrev = { time: 10.0, text: 'A' };
  const normalNext = { time: 14.0, text: 'B' };
  const gapNext = { time: 25.0, text: 'C' };
  const markedLine = { time: 30.0, text: '(Instrumental)', isInstrumental: true };

  assert.equal(isInstrumentalBreak(normalNext, normalPrev), false);
  assert.equal(isInstrumentalBreak(gapNext, normalPrev), true);
  assert.equal(isInstrumentalBreak(markedLine, gapNext), true);
});

// ---------------------------------------------------------------------------
// Playback Clock Tests
// ---------------------------------------------------------------------------

test('PlaybackClock: anchors time and calculates interpolated elapsed position', async () => {
  const clock = new PlaybackClock();
  clock.updateAnchor(5.0, true, 1.0, 100);

  assert.ok(clock.getCurrentInterpolatedTime() >= 5.0);

  // Wait 50ms and check elapsed increment
  await new Promise((r) => setTimeout(r, 50));
  const interpolated = clock.getCurrentInterpolatedTime();
  assert.ok(interpolated > 5.0 && interpolated < 5.3, `interpolated: ${interpolated}`);
});

test('PlaybackClock: freezes time when paused without drifting', async () => {
  const clock = new PlaybackClock();
  clock.updateAnchor(10.0, true, 1.0, 100);
  await new Promise((r) => setTimeout(r, 30));

  clock.setPlaying(false);
  const pausedTime = clock.getCurrentInterpolatedTime();

  await new Promise((r) => setTimeout(r, 40));
  assert.equal(clock.getCurrentInterpolatedTime(), pausedTime);
});

test('PlaybackClock: respects playbackRate speed scaling', async () => {
  const clock = new PlaybackClock();
  clock.updateAnchor(0.0, true, 2.0, 100);

  await new Promise((r) => setTimeout(r, 60));
  const elapsed = clock.getCurrentInterpolatedTime();
  // At 2x speed, in ~60ms it should advance ~120ms (0.12s)
  assert.ok(elapsed >= 0.09, `expected >= 0.09s at 2x rate, got ${elapsed}`);
});

test('PlaybackClock: clamps to track duration', async () => {
  const clock = new PlaybackClock();
  clock.updateAnchor(9.95, true, 1.0, 10.0);

  await new Promise((r) => setTimeout(r, 80));
  assert.equal(clock.getCurrentInterpolatedTime(), 10.0);
});

test('PlaybackClock: seekTo updates anchor immediately', () => {
  const clock = new PlaybackClock();
  clock.updateAnchor(10.0, false, 1.0, 100);
  clock.seekTo(45.0);
  assert.equal(clock.getCurrentInterpolatedTime(), 45.0);
});
