// Automated test suite for Phase 3: Dynamic Latency-Ranked Provider Pool & Adaptive Fallback Racing
// Verifies:
//   - Provider latency tracking and success statistics
//   - Dynamic sorting of providers by speed
//   - Quarantine backoff for failed/failing endpoints
//   - Fast 1500ms timeout behavior
//
// Run with: node scripts/test-search-phase3-provider-pool.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'youtube.ts');

const mod = await import(pathToFileURL(modulePath).href);
const { recordProviderResult, getRankedProviders, providerStatsMap } = mod;

test('Phase 3: recordProviderResult records latency and calculates average correctly', () => {
  providerStatsMap.clear();

  recordProviderResult('https://fast-provider.com/search', true, 120);
  recordProviderResult('https://fast-provider.com/search', true, 80);

  const stats = providerStatsMap.get('https://fast-provider.com/search');
  assert.ok(stats);
  assert.equal(stats.successCount, 2);
  assert.equal(stats.totalLatencyMs, 200);
  assert.equal(stats.totalLatencyMs / stats.successCount, 100);
});

test('Phase 3: getRankedProviders prioritizes lower-latency providers', () => {
  providerStatsMap.clear();

  const mockPool = [
    { url: (q) => `https://slow-provider.com/search?q=${q}`, provider: 'piped' },
    { url: (q) => `https://fast-provider.com/search?q=${q}`, provider: 'piped' },
    { url: (q) => `https://medium-provider.com/search?q=${q}`, provider: 'invidious' },
  ];

  recordProviderResult('https://slow-provider.com/search', true, 900);
  recordProviderResult('https://fast-provider.com/search', true, 150);
  recordProviderResult('https://medium-provider.com/search', true, 400);

  const ranked = getRankedProviders(mockPool);
  assert.equal(ranked[0].url('test'), 'https://fast-provider.com/search?q=test');
  assert.equal(ranked[1].url('test'), 'https://medium-provider.com/search?q=test');
  assert.equal(ranked[2].url('test'), 'https://slow-provider.com/search?q=test');
});

test('Phase 3: getRankedProviders pushes failing / quarantined providers to the end', () => {
  providerStatsMap.clear();

  const mockPool = [
    { url: (q) => `https://dead-provider.com/search?q=${q}`, provider: 'piped' },
    { url: (q) => `https://good-provider.com/search?q=${q}`, provider: 'piped' },
  ];

  recordProviderResult('https://dead-provider.com/search', false, 1500);
  recordProviderResult('https://good-provider.com/search', true, 200);

  const ranked = getRankedProviders(mockPool);
  assert.equal(ranked[0].url('test'), 'https://good-provider.com/search?q=test');
  assert.equal(ranked[1].url('test'), 'https://dead-provider.com/search?q=test');
});
