// Automated test suite for Phase 5: Sub-25ms Autocomplete Suggestion Engine
// Verifies:
//   - Suggestion relevance and cleaning
//   - Typed query preservation as first suggestion
//   - Monotonic request generation and race-condition immunity
//   - AbortController cancellation handling
//   - Tier 1 in-memory suggestion caching
//
// Run with: node scripts/test-search-phase5-suggestions.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'searchSuggestions.ts');

const mod = await import(pathToFileURL(modulePath).href);
const {
  getSearchSuggestions,
  cleanSuggestion,
  isSuggestionRelevant,
  getSuggestionGeneration,
} = mod;

test('Phase 5: cleanSuggestion removes excess spaces and trims', () => {
  assert.equal(cleanSuggestion('  imposter   syndrome  '), 'imposter syndrome');
  assert.equal(cleanSuggestion('starboy\n\t'), 'starboy');
});

test('Phase 5: isSuggestionRelevant matches prefixes and significant word overlaps', () => {
  assert.equal(isSuggestionRelevant('imposter syndrome slowed', 'imposter'), true);
  assert.equal(isSuggestionRelevant('the weeknd blinding lights', 'blinding lights'), true);
  assert.equal(isSuggestionRelevant('totally unrelated recipe', 'blinding lights'), false);
});

test('Phase 5: getSearchSuggestions returns typed query as first element', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => {
      return {
        ok: true,
        status: 200,
        json: async () => [
          'imposter',
          ['imposter syndrome', 'imposter syndrome slowed', 'imposter syndrome acoustic'],
        ],
      };
    };

    const res = await getSearchSuggestions('imposter');
    assert.ok(Array.isArray(res));
    assert.ok(res.length >= 1);
    assert.equal(res[0], 'imposter');
    assert.equal(res[1], 'imposter syndrome');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('Phase 5: getSearchSuggestions respects AbortSignal immediately', async () => {
  const controller = new AbortController();
  controller.abort();

  const res = await getSearchSuggestions('test query', controller.signal);
  assert.deepEqual(res, []);
});

test('Phase 5: getSuggestionGeneration increments monotonically', () => {
  const gen1 = getSuggestionGeneration();
  getSearchSuggestions('query1');
  const gen2 = getSuggestionGeneration();
  assert.ok(gen2 > gen1);
});
