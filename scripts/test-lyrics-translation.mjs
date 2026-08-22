// Unit and behaviour tests for Lyrics Translation:
//   - HTML entity decoding
//   - Non-translatable symbol detection
//   - Per-line translation caching
//   - Timing & word preservation during line translation
//   - Honest failure handling (no faked translations)
//
// Run with: node scripts/test-lyrics-translation.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'lyricsTranslation.ts');

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
  SUPPORTED_LANGUAGES,
  decodeHtmlEntities,
  isNonTranslatable,
  translateText,
  translateLyricLines,
  translatePlainLyrics,
  translateLyricsData,
} = mod;

// ---------------------------------------------------------------------------
// Supported languages
// ---------------------------------------------------------------------------
test('SUPPORTED_LANGUAGES contains major language options with codes and native names', () => {
  assert.ok(Array.isArray(SUPPORTED_LANGUAGES));
  assert.ok(SUPPORTED_LANGUAGES.length >= 8);

  const codes = SUPPORTED_LANGUAGES.map((l) => l.code);
  assert.ok(codes.includes('en'));
  assert.ok(codes.includes('es'));
  assert.ok(codes.includes('fr'));
  assert.ok(codes.includes('ja'));
  assert.ok(codes.includes('ko'));
  assert.ok(codes.includes('hi'));
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
test('decodeHtmlEntities decodes common HTML entities', () => {
  assert.equal(decodeHtmlEntities('Don&#39;t stop'), "Don't stop");
  assert.equal(decodeHtmlEntities('&quot;Hello&quot; &amp; Goodbye'), '"Hello" & Goodbye');
  assert.equal(decodeHtmlEntities('&lt;tag&gt;'), '<tag>');
  assert.equal(decodeHtmlEntities(''), '');
});

test('isNonTranslatable flags music symbols, numbers, and whitespace', () => {
  assert.equal(isNonTranslatable('♪'), true);
  assert.equal(isNonTranslatable('♫'), true);
  assert.equal(isNonTranslatable('   '), true);
  assert.equal(isNonTranslatable('12345'), true);
  assert.equal(isNonTranslatable('... --- ...'), true);
  assert.equal(isNonTranslatable('Hello world'), false);
  assert.equal(isNonTranslatable('Je t\'aime'), false);
});

// ---------------------------------------------------------------------------
// translateText
// ---------------------------------------------------------------------------
test('translateText uses cache and handles provider responses', async () => {
  const originalFetch = globalThis.fetch;
  try {
    let fetchCount = 0;
    globalThis.fetch = async (url) => {
      fetchCount++;
      return {
        ok: true,
        json: async () => ({
          responseData: {
            translatedText: 'Hola mundo',
          },
        }),
      };
    };

    const first = await translateText('Hello world', 'es');
    assert.equal(first, 'Hola mundo');
    assert.equal(fetchCount, 1);

    // Second call for the same string & target lang should hit memory cache
    const second = await translateText('Hello world', 'es');
    assert.equal(second, 'Hola mundo');
    assert.equal(fetchCount, 1, 'expected cache hit to skip network fetch');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('translateText handles provider failure honestly by returning null', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => {
      throw new Error('Network error');
    };

    const res = await translateText('Uncached phrase 123456', 'fr');
    assert.equal(res, null, 'expected null on provider failure without faking');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

// ---------------------------------------------------------------------------
// translateLyricLines
// ---------------------------------------------------------------------------
test('translateLyricLines preserves timestamps, words, and instrumental lines', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url) => {
      if (url.includes('api.mymemory.translated.net')) {
        return {
          ok: true,
          json: async () => ({
            responseData: {
              translatedText: 'Bonjour le monde',
            },
          }),
        };
      }
      return { ok: false };
    };

    const inputLines = [
      { time: 10.5, text: 'Hello world', words: [{ word: 'Hello', time: 10.5 }, { word: 'world', time: 11.2 }] },
      { time: 15.0, text: '♪', isInstrumental: true },
      { time: 20.0, text: 'Hello world' },
    ];

    const translated = await translateLyricLines(inputLines, 'fr');
    assert.equal(translated.length, 3);

    // Line 1: translated with words and timestamps intact
    assert.equal(translated[0].time, 10.5);
    assert.equal(translated[0].text, 'Hello world');
    assert.equal(translated[0].translatedText, 'Bonjour le monde');
    assert.deepEqual(translated[0].words, inputLines[0].words);

    // Line 2: instrumental preserved
    assert.equal(translated[1].time, 15.0);
    assert.equal(translated[1].isInstrumental, true);
    assert.equal(translated[1].translatedText, undefined);

    // Line 3: translated
    assert.equal(translated[2].time, 20.0);
    assert.equal(translated[2].translatedText, 'Bonjour le monde');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('translatePlainLyrics preserves line structure', async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async () => ({
      ok: true,
      json: async () => ({
        responseData: {
          translatedText: 'Ligne traduite',
        },
      }),
    });

    const plain = 'Line one\nLine two\n♪';
    const result = await translatePlainLyrics(plain, 'fr');
    const lines = result.split('\n');
    assert.equal(lines.length, 3);
    assert.equal(lines[0], 'Ligne traduite');
    assert.equal(lines[1], 'Ligne traduite');
    assert.equal(lines[2], '♪');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
