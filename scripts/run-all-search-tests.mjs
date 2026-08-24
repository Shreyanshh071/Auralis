// Master Automated Test Runner for High-Speed Search Engine (Phases 1 - 9)
// Executes all phase test suites in sequential order and ensures zero regressions.
//
// Run with: node scripts/run-all-search-tests.mjs

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const testSuites = [
  { phase: 'Phase 1', name: 'Universal High-Speed Network Adapter', script: 'scripts/test-search-phase1-adapter.mjs' },
  { phase: 'Phase 2', name: 'Parallel InnerTube & Dev Proxy Engine', script: 'scripts/test-search-phase2-innertube.mjs' },
  { phase: 'Phase 3', name: 'Latency-Ranked Provider Pool & Adaptive Fallbacks', script: 'scripts/test-search-phase3-provider-pool.mjs' },
  { phase: 'Phase 4', name: 'Multi-Tier Zero-Latency Search Cache', script: 'scripts/test-search-phase4-cache.mjs' },
  { phase: 'Phase 5', name: 'Sub-25ms Autocomplete Suggestion Engine', script: 'scripts/test-search-phase5-suggestions.mjs' },
  { phase: 'Phase 6', name: 'O(1) In-Memory Search History Engine', script: 'scripts/test-search-phase6-history.mjs' },
  { phase: 'Phase 7', name: 'UI Handshake & In-Flight Promise Sharing', script: 'scripts/test-search-phase7-handshake.mjs' },
  { phase: 'Phase 8', name: 'Mobile Search Optimization & React Performance', script: 'scripts/test-search-phase8-mobile-render.mjs' },
  { phase: 'Baseline 1', name: 'Search Parsing & Data Bucketing', script: 'scripts/test-search-parsing.mjs' },
  { phase: 'Baseline 2', name: 'Public Provider Reliability & Error Fallbacks', script: 'scripts/test-search-reliability.mjs' },
];

console.log('================================================================');
console.log('   AURALIS HIGH-SPEED SEARCH ENGINE: AUTOMATED VERIFICATION');
console.log('================================================================\n');

let totalPassed = 0;
let totalFailed = 0;
const startTotal = Date.now();

for (const suite of testSuites) {
  const suitePath = path.join(repoRoot, suite.script);
  process.stdout.write(`▶ Running [${suite.phase}] ${suite.name}... `);

  const start = Date.now();
  const res = spawnSync(process.execPath, [suitePath], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
  const elapsed = Date.now() - start;

  if (res.status === 0) {
    console.log(`\x1b[32mPASS\x1b[0m (${elapsed}ms)`);
    totalPassed++;
  } else {
    console.log(`\x1b[31mFAIL\x1b[0m (${elapsed}ms)`);
    console.error(res.stderr || res.stdout);
    totalFailed++;
    process.exit(1);
  }
}

const totalDuration = Date.now() - startTotal;
console.log('\n================================================================');
console.log(` SUMMARY: ${totalPassed} suites passed, ${totalFailed} failed in ${totalDuration}ms`);
console.log(' ALL SEARCH OPTIMIZATIONS VALIDATED WITH ZERO REGRESSIONS!');
console.log('================================================================\n');
