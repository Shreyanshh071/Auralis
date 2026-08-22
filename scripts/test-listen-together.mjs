// Behaviour tests for Listen Together synchronization, seek handling, and room logic.
//
// Run with:  node scripts/test-listen-together.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'lib', 'listenTogether.ts');

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
  ROOM_CODE_LENGTH,
  ROOM_CODE_CHARSET,
  MEMBER_PRESENCE_TIMEOUT_MS,
  DEFAULT_DRIFT_THRESHOLD_SECONDS,
  SEEK_DETECTION_THRESHOLD_SECONDS,
  generateRoomCode,
  normalizeRoomCode,
  isValidRoomCode,
  calculateExpectedPlaybackPosition,
  calculateDrift,
  shouldResync,
  isSeekJump,
  filterActiveMembers,
  sanitizeDisplayName,
  generateInviteUrl,
  extractRoomCodeFromUrl,
  getMemberAvatarColor,
} = mod;

test('generateRoomCode produces a valid 6-character alphanumeric code without confusing characters', () => {
  for (let i = 0; i < 50; i++) {
    const code = generateRoomCode();
    assert.equal(code.length, 6);
    assert.equal(isValidRoomCode(code), true);
    // Ensure no confusing characters (0, O, 1, I)
    assert.equal(/[0O1I]/.test(code), false);
  }
});

test('normalizeRoomCode trims whitespace, removes hyphens, and converts to uppercase', () => {
  assert.equal(normalizeRoomCode(' ab-c1 2d '), 'ABC12D');
  assert.equal(normalizeRoomCode('k9x-2p4'), 'K9X2P4');
  assert.equal(normalizeRoomCode(''), '');
  assert.equal(normalizeRoomCode(null), '');
});

test('isValidRoomCode validates only genuine 6-character alphanumeric codes', () => {
  assert.equal(isValidRoomCode('K9X2P4'), true);
  assert.equal(isValidRoomCode('k9x-2p4'), true); // Normalization handles hyphens & lowercase
  assert.equal(isValidRoomCode('12345'), false); // Too short
  assert.equal(isValidRoomCode('1234567'), false); // Too long
  assert.equal(isValidRoomCode('K9X@P4'), false); // Invalid character
});

test('calculateExpectedPlaybackPosition: pauses accurately at saved position', () => {
  const position = calculateExpectedPlaybackPosition({
    isPlaying: false,
    playbackPosition: 42.5,
    playbackRate: 1.0,
    updatedAt: 1000000,
    now: 1005000, // 5 seconds elapsed
    duration: 200,
  });
  assert.equal(position, 42.5);
});

test('calculateExpectedPlaybackPosition: calculates elapsed position when playing', () => {
  const position = calculateExpectedPlaybackPosition({
    isPlaying: true,
    playbackPosition: 30.0,
    playbackRate: 1.0,
    updatedAt: 1000000,
    now: 1004000, // 4 seconds elapsed
    duration: 200,
  });
  assert.equal(position, 34.0);
});

test('calculateExpectedPlaybackPosition: respects playbackRate scaling', () => {
  const position = calculateExpectedPlaybackPosition({
    isPlaying: true,
    playbackPosition: 10.0,
    playbackRate: 1.5,
    updatedAt: 1000000,
    now: 1004000, // 4 elapsed sec * 1.5 rate = 6 seconds of audio
    duration: 200,
  });
  assert.equal(position, 16.0);
});

test('calculateExpectedPlaybackPosition: clamps to track duration', () => {
  const position = calculateExpectedPlaybackPosition({
    isPlaying: true,
    playbackPosition: 195.0,
    playbackRate: 1.0,
    updatedAt: 1000000,
    now: 1010000, // 10 seconds elapsed -> would be 205s
    duration: 200, // capped at 200s
  });
  assert.equal(position, 200.0);
});

test('calculateDrift and shouldResync: detects drift beyond threshold', () => {
  const localTime = 30.0;
  const expectedTime = 33.0; // 3.0s drift
  const drift = calculateDrift(localTime, expectedTime);
  assert.equal(drift, 3.0);

  // Under threshold:
  assert.equal(
    shouldResync({
      isPlaying: true,
      localIsPlaying: true,
      localTime: 30.0,
      expectedTime: 31.0, // 1.0s drift
      driftThreshold: 1.5,
    }),
    false
  );

  // Over threshold:
  assert.equal(
    shouldResync({
      isPlaying: true,
      localIsPlaying: true,
      localTime: 30.0,
      expectedTime: 32.0, // 2.0s drift
      driftThreshold: 1.5,
    }),
    true
  );

  // State mismatch (playing vs paused) must resync immediately regardless of drift:
  assert.equal(
    shouldResync({
      isPlaying: true,
      localIsPlaying: false,
      localTime: 30.0,
      expectedTime: 30.1,
    }),
    true
  );
});

test('isSeekJump detects abrupt forward and backward seeks vs continuous progression', () => {
  // Normal continuous progression (100ms elapsed -> 0.1s progression)
  assert.equal(
    isSeekJump({
      currentTime: 10.1,
      lastKnownTime: 10.0,
      elapsedWallClockSec: 0.1,
      isPlaying: true,
      playbackRate: 1.0,
    }),
    false
  );

  // Normal paused state (1s elapsed -> 0s progression)
  assert.equal(
    isSeekJump({
      currentTime: 10.0,
      lastKnownTime: 10.0,
      elapsedWallClockSec: 1.0,
      isPlaying: false,
      playbackRate: 1.0,
    }),
    false
  );

  // Abrupt forward seek (+50s jump in 100ms)
  assert.equal(
    isSeekJump({
      currentTime: 60.0,
      lastKnownTime: 10.0,
      elapsedWallClockSec: 0.1,
      isPlaying: true,
      playbackRate: 1.0,
    }),
    true
  );

  // Abrupt backward seek (-50s jump in 100ms)
  assert.equal(
    isSeekJump({
      currentTime: 10.0,
      lastKnownTime: 60.0,
      elapsedWallClockSec: 0.1,
      isPlaying: true,
      playbackRate: 1.0,
    }),
    true
  );

  // Seek while paused
  assert.equal(
    isSeekJump({
      currentTime: 35.0,
      lastKnownTime: 10.0,
      elapsedWallClockSec: 0.5,
      isPlaying: false,
      playbackRate: 1.0,
    }),
    true
  );
});

test('seek convergence & timestamp calculation: accurately accounts for network delay without double-latency', () => {
  // Host seeks to 85.0s at t0
  const t0 = 1000000;
  const seekTargetPosition = 85.0;

  // Guest receives the snapshot at t1 (150ms network + Firestore transit delay)
  const t1 = 1000150;

  const guestExpectedPosition = calculateExpectedPlaybackPosition({
    isPlaying: true,
    playbackPosition: seekTargetPosition,
    playbackRate: 1.0,
    updatedAt: t0,
    now: t1,
    duration: 300,
  });

  // Expected position is 85.0 + 0.15 = 85.15s
  assert.equal(Math.round(guestExpectedPosition * 100) / 100, 85.15);

  // Guest was at 10.0s before seek
  const guestLocalTime = 10.0;
  const drift = calculateDrift(guestLocalTime, guestExpectedPosition);
  assert.ok(drift > DEFAULT_DRIFT_THRESHOLD_SECONDS);

  // Guest resync decision triggers seekTo(85.15)
  assert.equal(
    shouldResync({
      isPlaying: true,
      localIsPlaying: true,
      localTime: guestLocalTime,
      expectedTime: guestExpectedPosition,
      driftThreshold: DEFAULT_DRIFT_THRESHOLD_SECONDS,
    }),
    true
  );
});

test('filterActiveMembers: drops stale members exceeding presence timeout', () => {
  const now = 100000;
  const members = [
    { id: '1', name: 'Alice', isHost: true, joinedAt: 50000, lastSeen: 95000 }, // 5s ago - active
    { id: '2', name: 'Bob', isHost: false, joinedAt: 50000, lastSeen: 60000 }, // 40s ago - stale (>35s)
    { id: '3', name: 'Charlie', isHost: false, joinedAt: 50000, lastSeen: 80000 }, // 20s ago - active
  ];

  const active = filterActiveMembers(members, now, MEMBER_PRESENCE_TIMEOUT_MS);
  assert.equal(active.length, 2);
  assert.equal(active[0].name, 'Alice');
  assert.equal(active[1].name, 'Charlie');
});

test('sanitizeDisplayName: provides safe defaults and character caps', () => {
  assert.equal(sanitizeDisplayName('   Alex  '), 'Alex');
  assert.equal(sanitizeDisplayName('', 'uid123456'), 'Guest-UID1');
  assert.equal(sanitizeDisplayName(null, 'abc9876'), 'Guest-ABC9');
  assert.equal(sanitizeDisplayName(null), 'Guest Listener');
});

test('generateInviteUrl and extractRoomCodeFromUrl: handles full URLs, query strings, and hash fragments', () => {
  const url = generateInviteUrl('https://auralis.app/explore', 'K9X2P4');
  assert.equal(url, 'https://auralis.app/explore?room=K9X2P4');

  // Extract from full URL
  assert.equal(extractRoomCodeFromUrl('https://auralis.app/?room=K9X2P4'), 'K9X2P4');
  // Extract from relative search
  assert.equal(extractRoomCodeFromUrl('?room=k9x-2p4'), 'K9X2P4');
  // Extract from hash fragment
  assert.equal(extractRoomCodeFromUrl('https://auralis.app/#room=K9X2P4'), 'K9X2P4');
  assert.equal(extractRoomCodeFromUrl('https://auralis.app/#/room/K9X2P4'), 'K9X2P4');
  // Return null on invalid input
  assert.equal(extractRoomCodeFromUrl('https://auralis.app/?foo=bar'), null);
});

test('getMemberAvatarColor: returns deterministic color per member ID', () => {
  const color1 = getMemberAvatarColor('user_abc');
  const color2 = getMemberAvatarColor('user_abc');
  const color3 = getMemberAvatarColor('user_xyz');
  assert.equal(color1, color2);
  assert.equal(typeof color1, 'string');
  assert.ok(color1.startsWith('#'));
});
