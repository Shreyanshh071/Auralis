// Unit tests for music-first search redesign:
// - Title cleaning & video noise filtering
// - Artist name cleaning
// - Deduplication
// - Search suggestions autocomplete format
// - Category filtering logic
//
// Run with: node scripts/test-search-redesign.mjs

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ytServicePath = path.join(repoRoot, 'src', 'services', 'youtube.ts');
const suggestServicePath = path.join(repoRoot, 'src', 'services', 'searchSuggestions.ts');

const ytMod = await import(pathToFileURL(ytServicePath).href);
const suggestMod = await import(pathToFileURL(suggestServicePath).href);

const { cleanTrackTitle, cleanArtistName, parsePipedSearchItem, parseInvidiousSearchItem } = ytMod;
const { getSearchSuggestions } = suggestMod;

// ---------------------------------------------------------------------------
// Title Cleaning Tests
// ---------------------------------------------------------------------------

test('cleanTrackTitle: removes common YouTube video noise', () => {
  assert.equal(cleanTrackTitle('Blinding Lights (Official Music Video)'), 'Blinding Lights');
  assert.equal(cleanTrackTitle('Shape of You [Official Video]'), 'Shape of You');
  assert.equal(cleanTrackTitle('Imposter Syndrome (Official Audio)'), 'Imposter Syndrome');
  assert.equal(cleanTrackTitle('Starboy (Lyric Video)'), 'Starboy');
  assert.equal(cleanTrackTitle('As It Was [4K UHD]'), 'As It Was');
  assert.equal(cleanTrackTitle('Levitating (Visualizer)'), 'Levitating');
  assert.equal(cleanTrackTitle('Heat Waves (Lyrics)'), 'Heat Waves');
  assert.equal(cleanTrackTitle('Bad Guy [HQ Audio]'), 'Bad Guy');
});

test('cleanTrackTitle: strictly preserves genuine musical versions and renditions', () => {
  assert.equal(cleanTrackTitle('Imposter Syndrome (Slowed + Reverb)'), 'Imposter Syndrome (Slowed + Reverb)');
  assert.equal(cleanTrackTitle('Imposter Syndrome (Sped Up)'), 'Imposter Syndrome (Sped Up)');
  assert.equal(cleanTrackTitle('Hotel California (Live on MTV 1994)'), 'Hotel California (Live on MTV 1994)');
  assert.equal(cleanTrackTitle('Sweater Weather (Acoustic)'), 'Sweater Weather (Acoustic)');
  assert.equal(cleanTrackTitle('Levitating (feat. DaBaby)'), 'Levitating (feat. DaBaby)');
  assert.equal(cleanTrackTitle('Save Your Tears (Remix)'), 'Save Your Tears (Remix)');
});

// ---------------------------------------------------------------------------
// Artist Cleaning Tests
// ---------------------------------------------------------------------------

test('cleanArtistName: strips Topic and VEVO channel suffixes', () => {
  assert.equal(cleanArtistName('Ed Sheeran - Topic'), 'Ed Sheeran');
  assert.equal(cleanArtistName('TheWeekndVEVO'), 'TheWeeknd');
  assert.equal(cleanArtistName('Queen Official'), 'Queen Official');
  assert.equal(cleanArtistName('Sidney Gish'), 'Sidney Gish');
});

// ---------------------------------------------------------------------------
// Stream / Provider Parsing Tests
// ---------------------------------------------------------------------------

test('parsePipedSearchItem: cleans song titles and artists from piped stream items', () => {
  const item = {
    type: 'stream',
    url: '/watch?v=dQw4w9WgXcQ',
    title: 'Rick Astley - Never Gonna Give You Up (Official Music Video)',
    uploaderName: 'RickAstleyVEVO',
    duration: 213,
  };

  const parsed = parsePipedSearchItem(item);
  assert.ok(parsed?.song);
  assert.equal(parsed.song.id, 'dQw4w9WgXcQ');
  assert.equal(parsed.song.title, 'Never Gonna Give You Up');
  assert.equal(parsed.song.artist, 'Rick Astley');
  assert.equal(parsed.song.duration, 213);
});

test('parseInvidiousSearchItem: cleans song titles and artists from invidious video items', () => {
  const item = {
    type: 'video',
    videoId: '4NRXx6U8ABQ',
    title: 'The Weeknd - Blinding Lights (Official Audio)',
    author: 'The Weeknd - Topic',
    lengthSeconds: 200,
  };

  const parsed = parseInvidiousSearchItem(item);
  assert.ok(parsed?.song);
  assert.equal(parsed.song.id, '4NRXx6U8ABQ');
  assert.equal(parsed.song.title, 'Blinding Lights');
  assert.equal(parsed.song.artist, 'The Weeknd');
  assert.equal(parsed.song.duration, 200);
});

// ---------------------------------------------------------------------------
// Search Suggestions Tests
// ---------------------------------------------------------------------------

test('getSearchSuggestions: returns compact query suggestions starting with query', async () => {
  const suggestions = await getSearchSuggestions('imposter syn');
  assert.ok(Array.isArray(suggestions));
  assert.ok(suggestions.length > 0);
  assert.equal(suggestions[0].toLowerCase(), 'imposter syn');
});

test('getSearchSuggestions: returns empty array for empty / 1-char query', async () => {
  assert.deepEqual(await getSearchSuggestions(''), []);
  assert.deepEqual(await getSearchSuggestions('a'), []);
});
