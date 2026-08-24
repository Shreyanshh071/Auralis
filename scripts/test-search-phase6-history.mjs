// Automated test suite for Phase 6: O(1) In-Memory Search History Engine
// Verifies:
//   - Case-insensitive deduplication and promotion to top
//   - Max entries boundary (15 entries)
//   - Single item removal
//   - History clearing
//   - In-memory cache consistency
//
// Run with: node scripts/test-search-phase6-history.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'searchHistory.ts');

const mod = await import(pathToFileURL(modulePath).href);
const {
  getSearchHistory,
  addToSearchHistory,
  removeFromSearchHistory,
  clearSearchHistory,
} = mod;

test('Phase 6: Search history adds, deduplicates, and maintains newest-first order', () => {
  clearSearchHistory();

  addToSearchHistory('The Weeknd');
  addToSearchHistory('Tame Impala');
  addToSearchHistory('the weeknd'); // Duplicate with different casing

  const history = getSearchHistory();
  assert.equal(history.length, 2);
  assert.equal(history[0], 'the weeknd');
  assert.equal(history[1], 'Tame Impala');
});

test('Phase 6: Search history enforces max 15 entries limit', () => {
  clearSearchHistory();

  for (let i = 1; i <= 20; i++) {
    addToSearchHistory(`Query ${i}`);
  }

  const history = getSearchHistory();
  assert.equal(history.length, 15);
  assert.equal(history[0], 'Query 20');
  assert.equal(history[14], 'Query 6');
});

test('Phase 6: removeFromSearchHistory deletes specific item', () => {
  clearSearchHistory();

  addToSearchHistory('Song A');
  addToSearchHistory('Song B');
  addToSearchHistory('Song C');

  removeFromSearchHistory('song b'); // Case-insensitive deletion

  const history = getSearchHistory();
  assert.deepEqual(history, ['Song C', 'Song A']);
});

test('Phase 6: clearSearchHistory empties the entire list', () => {
  addToSearchHistory('Test 1');
  clearSearchHistory();

  assert.deepEqual(getSearchHistory(), []);
});
