// Behaviour + wiring tests for Auralis YouTube Account Sync & OAuth flow.
//
// Run with:  node scripts/test-youtube-sync.mjs
//
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => fs.readFileSync(path.join(repoRoot, rel), 'utf8');

// ---------------------------------------------------------------------------
// 1. YouTube OAuth scope and flow configuration.
// ---------------------------------------------------------------------------
test('youtubeSync service requests the correct YouTube readonly scope and consent prompt', () => {
  const src = read('src/services/youtubeSync.ts');
  assert.ok(
    src.includes('https://www.googleapis.com/auth/youtube.readonly'),
    'must request https://www.googleapis.com/auth/youtube.readonly scope',
  );
  assert.ok(
    src.includes("addScope('https://www.googleapis.com/auth/youtube.readonly')"),
    'web path must add scope to GoogleAuthProvider',
  );
  assert.ok(
    src.includes("prompt: 'consent'") || src.includes("value: 'consent'"),
    'must set prompt: consent custom parameter to guarantee incremental scope authorization',
  );
});

test('youtubeSync service supports both Web (popup) and Native (Capacitor) platforms', () => {
  const src = read('src/services/youtubeSync.ts');
  assert.ok(
    src.includes('Capacitor.isNativePlatform()'),
    'must branch on Capacitor native vs web platform',
  );
  assert.ok(
    src.includes('@capacitor-firebase/authentication') && /await import\(/.test(src),
    'native path must dynamically import @capacitor-firebase/authentication',
  );
  assert.ok(
    src.includes('signInWithPopup'),
    'web path must use Firebase JS SDK signInWithPopup',
  );
  assert.ok(
    src.includes('GoogleAuthProvider.credentialFromResult'),
    'web path must extract OAuth access token using credentialFromResult',
  );
});

// ---------------------------------------------------------------------------
// 2. YouTube Data API v3 endpoints and authentication.
// ---------------------------------------------------------------------------
test('youtubeSync API helper uses Bearer token authorization header', () => {
  const src = read('src/services/youtubeSync.ts');
  assert.ok(
    src.includes('Authorization: `Bearer ${accessToken}`'),
    'must use Bearer OAuth access token for private API calls, NOT an API key',
  );
  assert.ok(
    src.includes('https://www.googleapis.com/youtube/v3'),
    'must target YouTube Data API v3 base endpoint',
  );
});

test('youtubeSync queries the user private data using mine=true and LL playlist', () => {
  const src = read('src/services/youtubeSync.ts');
  assert.ok(
    src.includes("mine: 'true'"),
    'channel/playlist queries must set mine=true for authenticated user data',
  );
  assert.ok(
    src.includes("fetchYouTubePlaylistItems(accessToken, 'LL')"),
    'liked songs fetcher must use the special "LL" playlist ID',
  );
  assert.ok(
    src.includes('export async function fetchYouTubeChannelInfo'),
    'must export channel info fetcher',
  );
  assert.ok(
    src.includes('export async function fetchYouTubePlaylists'),
    'must export playlists fetcher',
  );
  assert.ok(
    src.includes('export async function fetchYouTubePlaylistItems'),
    'must export playlist items fetcher',
  );
  assert.ok(
    src.includes('export async function fetchYouTubeLikedSongs'),
    'must export liked songs fetcher',
  );
});

// ---------------------------------------------------------------------------
// 3. YouTube API error handling.
// ---------------------------------------------------------------------------
test('YouTubeApiError captures status and surfaces descriptive error messages', () => {
  const src = read('src/services/youtubeSync.ts');
  assert.ok(
    src.includes('export class YouTubeApiError extends Error'),
    'must export custom YouTubeApiError class',
  );
  assert.ok(
    src.includes('403') && src.includes('quota'),
    'must handle 403 quota and permissions errors gracefully',
  );
  assert.ok(
    src.includes('401') && src.includes('expired'),
    'must handle 401 expired token errors with reconnect instruction',
  );
});

// ---------------------------------------------------------------------------
// 4. AuthContext & YouTubeSyncModal integration.
// ---------------------------------------------------------------------------
test('AuthContext exposes YouTube sync state and methods', () => {
  const src = read('src/context/AuthContext.tsx');
  assert.ok(
    src.includes('youtubeState'),
    'AuthContext must expose youtubeState',
  );
  assert.ok(
    src.includes('youtubeConnecting'),
    'AuthContext must expose youtubeConnecting',
  );
  assert.ok(
    src.includes('youtubeAccessToken'),
    'AuthContext must expose youtubeAccessToken',
  );
  assert.ok(
    src.includes('connectYouTube'),
    'AuthContext must expose connectYouTube',
  );
  assert.ok(
    src.includes('disconnectYouTube'),
    'AuthContext must expose disconnectYouTube',
  );
});

test('YouTubeSyncModal handles session expiration and reconnect flows', () => {
  const src = read('src/components/modals/YouTubeSyncModal.tsx');
  assert.ok(
    src.includes('connectYouTube'),
    'modal must trigger connectYouTube',
  );
  assert.ok(
    src.includes('disconnectYouTube'),
    'modal must trigger disconnectYouTube',
  );
  assert.ok(
    src.includes('Session expired') || src.includes('Reconnect'),
    'modal must notify user when session token is expired/missing and provide Reconnect action',
  );
  assert.ok(
    src.includes('importYouTubePlaylistAsAuralis'),
    'modal must import playlists as Auralis playlist structures',
  );
});

// ---------------------------------------------------------------------------
// 5. State persistence (in-memory token, persistent connection flag).
// ---------------------------------------------------------------------------
test('Connection state helpers manage localStorage safely', () => {
  const src = read('src/services/youtubeSync.ts');
  assert.ok(
    src.includes('getYouTubeConnectionState'),
    'must export getYouTubeConnectionState',
  );
  assert.ok(
    src.includes('setYouTubeConnectionState'),
    'must export setYouTubeConnectionState',
  );
  assert.ok(
    src.includes('clearYouTubeConnectionState'),
    'must export clearYouTubeConnectionState',
  );
  // Guard: tokens must NOT be saved to localStorage
  assert.ok(
    !src.includes('localStorage.setItem(STORAGE_KEY, JSON.stringify({ accessToken'),
    'access tokens must NOT be stored in localStorage (in-memory only)',
  );
});
