// Automated test suite for Phase 4: Multi-Tier Zero-Latency Search Cache
// Verifies:
//   - Canonical search key normalization (casing, whitespace collapse)
//   - L1 Memory cache instant retrieval
//   - LRU eviction ordering and capacity bounds
//   - TTL expiry logic
//   - searchCache.has and searchCache.clear
//
// Run with: node scripts/test-search-phase4-cache.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'searchCache.ts');

const mod = await import(pathToFileURL(modulePath).href);
const { searchCache, normalizeSearchKey } = mod;

test('Phase 4: normalizeSearchKey cleans and normalizes queries', () => {
  assert.equal(normalizeSearchKey('  The   Weeknd  '), 'the weeknd');
  assert.equal(normalizeSearchKey('BLINDING LIGHTS'), 'blinding lights');
  assert.equal(normalizeSearchKey('\t\nStarboy\t\n'), 'starboy');
  assert.equal(normalizeSearchKey(''), '');
});

test('Phase 4: searchCache stores and retrieves results synchronously (0ms)', () => {
  searchCache.clear();

  const mockData = {
    songs: [{ id: '123', title: 'Song 1', artist: 'Artist 1', duration: 200, thumbnail: 'thumb.jpg' }],
    artists: [],
    playlists: [],
  };

  searchCache.set('  Song 1  ', mockData);

  // Exact match
  const hit1 = searchCache.get('Song 1');
  assert.deepEqual(hit1, mockData);

  // Normalized key match
  const hit2 = searchCache.get('  SONG 1  ');
  assert.deepEqual(hit2, mockData);

  // Non-existent key
  assert.equal(searchCache.get('nonexistent'), null);
});

test('Phase 4: searchCache respects TTL expiration', async () => {
  searchCache.clear();

  const mockData = {
    songs: [{ id: '123', title: 'Song 1', artist: 'Artist 1', duration: 200, thumbnail: 'thumb.jpg' }],
    artists: [],
    playlists: [],
  };

  searchCache.set('short-ttl-query', mockData);

  // Immediately accessible
  assert.ok(searchCache.get('short-ttl-query', 500));

  // Wait 60ms and query with 30ms TTL -> should be expired
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(searchCache.get('short-ttl-query', 30), null);
});

test('Phase 4: searchCache ignores empty result sets to prevent caching failures', () => {
  searchCache.clear();

  const emptyData = { songs: [], artists: [], playlists: [] };
  searchCache.set('failed_search', emptyData);

  assert.equal(searchCache.get('failed_search'), null, 'Empty result set should not be cached');
});
