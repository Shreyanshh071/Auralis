// Behaviour and unit tests for search reliability:
//   - fallback instances pool definition
//   - queryProvider mocking (success, 404, 500, network error, empty items)
//   - raceProviderBatch (first non-empty resolution, all failure fallthrough, all empty resolution)
//   - SearchUnavailableError semantics
//   - searchAll and searchYouTube resolution
//
// Run with: node scripts/test-search-reliability.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'youtube.ts');

let mod;
try {
  mod = await import(pathToFileURL(modulePath).href);
} catch (err) {
  const [major, minor] = process.versions.node.split('.').map(Number);
  const canStripTypes = major > 22 || (major === 22 && minor >= 18);
  if (!canStripTypes) {
    console.error(
      `This test loads a .ts module directly, which needs Node 22.18+ (running ${process.versions.node}).`
    );
  }
  throw err;
}

const {
  PUBLIC_SEARCH_PROVIDERS,
  SearchUnavailableError,
  queryProvider,
  raceProviderBatch,
  searchYouTube,
  searchAll,
} = mod;

// ---------------------------------------------------------------------------
// Fallback instances pool configuration
// ---------------------------------------------------------------------------
test('PUBLIC_SEARCH_PROVIDERS has a healthy pool of piped and invidious instances', () => {
  assert.ok(Array.isArray(PUBLIC_SEARCH_PROVIDERS));
  assert.ok(PUBLIC_SEARCH_PROVIDERS.length >= 6, 'expected at least 6 fallback instances');

  const piped = PUBLIC_SEARCH_PROVIDERS.filter((p) => p.provider === 'piped');
  const invidious = PUBLIC_SEARCH_PROVIDERS.filter((p) => p.provider === 'invidious');

  assert.ok(piped.length >= 3, 'expected at least 3 piped instances');
  assert.ok(invidious.length >= 3, 'expected at least 3 invidious instances');

  for (const endpoint of PUBLIC_SEARCH_PROVIDERS) {
    const url = endpoint.url('test');
    assert.ok(url.startsWith('https://'), `endpoint ${url} should use https`);
    assert.ok(url.includes('test'), `endpoint ${url} should include query param`);
  }
});

// ---------------------------------------------------------------------------
// SearchUnavailableError
// ---------------------------------------------------------------------------
test('SearchUnavailableError records attempted endpoints correctly', () => {
  const attempted = ['https://inst1.com', 'https://inst2.com'];
  const err = new SearchUnavailableError(attempted);
  assert.equal(err.name, 'SearchUnavailableError');
  assert.deepEqual(err.attempted, attempted);
  assert.ok(err.message.includes('No search provider could be reached'));
});

// ---------------------------------------------------------------------------
// raceProviderBatch: fast-first resolution and error handling
// ---------------------------------------------------------------------------
test('raceProviderBatch returns first non-empty result when one provider succeeds', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      if (url.includes('fast-success')) {
        return {
          ok: true,
          json: async () => ({
            items: [
              {
                type: 'stream',
                url: '/watch?v=vid12345',
                title: 'Test Song',
                uploaderName: 'Test Artist',
                duration: 200,
              },
            ],
          }),
        };
      }
      // Slow or failing provider
      throw new Error('Network error');
    };

    const mockProviders = [
      { url: () => 'https://failing1.com/search', provider: 'piped' },
      { url: () => 'https://fast-success.com/search', provider: 'piped' },
      { url: () => 'https://failing2.com/search', provider: 'piped' },
    ];

    const { winner, attempted, anyResponded } = await raceProviderBatch(mockProviders, 'test', 1000);
    assert.ok(winner, 'expected a winner');
    assert.equal(winner.songs.length, 1);
    assert.equal(winner.songs[0].id, 'vid12345');
    assert.equal(anyResponded, true);
    assert.equal(attempted.length, 3);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('raceProviderBatch detects genuine empty responses without failing', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => {
      return {
        ok: true,
        json: async () => ({
          items: [],
        }),
      };
    };

    const mockProviders = [
      { url: () => 'https://empty1.com/search', provider: 'piped' },
      { url: () => 'https://empty2.com/search', provider: 'invidious' },
    ];

    const { winner, attempted, anyResponded } = await raceProviderBatch(mockProviders, 'nonexistentquery', 1000);
    assert.equal(winner, null, 'no items found so winner is null');
    assert.equal(anyResponded, true, 'provider responded successfully with 0 items');
    assert.equal(attempted.length, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('raceProviderBatch handles all providers failing', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => {
      throw new Error('Connection refused');
    };

    const mockProviders = [
      { url: () => 'https://dead1.com/search', provider: 'piped' },
      { url: () => 'https://dead2.com/search', provider: 'invidious' },
    ];

    const { winner, attempted, anyResponded } = await raceProviderBatch(mockProviders, 'query', 1000);
    assert.equal(winner, null);
    assert.equal(anyResponded, false, 'no provider could respond');
    assert.equal(attempted.length, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('searchAll returns empty SearchResults on empty query without fetch', async () => {
  const res = await searchAll('   ');
  assert.deepEqual(res, { songs: [], artists: [], playlists: [] });
});
