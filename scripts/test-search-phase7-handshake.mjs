// Automated test suite for Phase 7: UI Handshake & In-Flight Promise Sharing
// Verifies:
//   - Simultaneous search calls share a single in-flight network Promise
//   - No duplicate network requests when Header and ExploreView query the same term
//   - Fast in-flight cleanup after promise resolution
//
// Run with: node scripts/test-search-phase7-handshake.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const cacheModulePath = path.join(repoRoot, 'src', 'services', 'searchCache.ts');
const ytModulePath = path.join(repoRoot, 'src', 'services', 'youtube.ts');

const cacheMod = await import(pathToFileURL(cacheModulePath).href);
const ytMod = await import(pathToFileURL(ytModulePath).href);

const { searchCache, getInFlightSearch, setInFlightSearch } = cacheMod;
const { searchAll } = ytMod;

test('Phase 7: getInFlightSearch / setInFlightSearch shares active in-flight promises', async () => {
  let resolvePromise;
  const sharedPromise = new Promise((resolve) => {
    resolvePromise = resolve;
  });

  setInFlightSearch('blinding lights', sharedPromise);

  const active1 = getInFlightSearch('blinding lights');
  const active2 = getInFlightSearch('  BLINDING LIGHTS  ');

  assert.equal(active1, sharedPromise);
  assert.equal(active2, sharedPromise);

  // Resolve promise -> inFlight map should clean up
  resolvePromise({ songs: [], artists: [], playlists: [] });
  await sharedPromise;

  assert.equal(getInFlightSearch('blinding lights'), null);
});

test('Phase 7: searchAll deduplicates simultaneous calls to the same query', async () => {
  searchCache.clear();

  let fetchCallCount = 0;
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => {
      fetchCallCount++;
      await new Promise((r) => setTimeout(r, 40));
      return {
        ok: true,
        json: async () => ({
          songs: [{ id: 'test1', title: 'Test 1', artist: 'Artist', duration: 200, thumbnail: 'thumb.jpg' }],
          artists: [],
          playlists: [],
        }),
      };
    };

    // Trigger two searches concurrently (simulating Header typeahead + Enter to ExploreView)
    const [res1, res2] = await Promise.all([
      searchAll('starboy'),
      searchAll('  STARBOY  '),
    ]);

    assert.equal(fetchCallCount, 1, 'Expected only 1 network fetch for simultaneous identical searches');
    assert.deepEqual(res1, res2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
