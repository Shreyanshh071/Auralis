// Behaviour tests for typed search parsing: Piped / Invidious item classification,
// bucketing + de-duplication, subscriber-count formatting, and malformed-input
// guards.
//
// Run with:  node scripts/test-search-parsing.mjs
//
// Imports src/services/youtube.ts directly; Node strips the type annotations
// (built in from Node 22.18 / 23.6 onward), safe because tsconfig sets
// "erasableSyntaxOnly". The assertions run against the exact functions the app
// uses to turn raw provider payloads into typed results — nothing is
// re-implemented here. Only pure functions are exercised; searchAll /
// searchYouTube (network I/O) are never called.
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
      `This test loads a .ts module directly, which needs Node 22.18+ (running ${process.versions.node}).`,
    );
  }
  throw err;
}

const { parsePipedSearchItem, parseInvidiousSearchItem, bucketItems, formatCount } = mod;

// ---------------------------------------------------------------------------
// formatCount
// ---------------------------------------------------------------------------
test('formatCount abbreviates by magnitude and trims trailing .0', () => {
  assert.equal(formatCount(0), '0');
  assert.equal(formatCount(999), '999');
  assert.equal(formatCount(1000), '1K');
  assert.equal(formatCount(1200), '1.2K');
  assert.equal(formatCount(1_500_000), '1.5M');
  assert.equal(formatCount(2_000_000), '2M');
  assert.equal(formatCount(3_200_000_000), '3.2B');
});

test('formatCount rejects non-finite / negative input', () => {
  assert.equal(formatCount(-5), '');
  assert.equal(formatCount(NaN), '');
  assert.equal(formatCount(Infinity), '');
});

// ---------------------------------------------------------------------------
// Piped parsing
// ---------------------------------------------------------------------------
test('parsePipedSearchItem: stream → song with artist/title split', () => {
  const parsed = parsePipedSearchItem({
    type: 'stream',
    url: '/watch?v=dQw4w9WgXcQ',
    title: 'Rick Astley - Never Gonna Give You Up',
    uploaderName: 'RickAstleyVEVO',
    duration: 213,
  });
  assert.ok(parsed && parsed.song, 'expected a song');
  assert.equal(parsed.song.id, 'dQw4w9WgXcQ');
  assert.equal(parsed.song.artist, 'Rick Astley');
  assert.equal(parsed.song.title, 'Never Gonna Give You Up');
  assert.equal(parsed.song.duration, 213);
  assert.equal(parsed.song.source, 'youtube');
  assert.equal(parsed.artist, undefined);
  assert.equal(parsed.playlist, undefined);
});

test('parsePipedSearchItem: channel → artist with id, subs, query', () => {
  const parsed = parsePipedSearchItem({
    type: 'channel',
    url: '/channel/UCuAXFkgsw1L7xaCfnd5JJOw',
    name: 'Rick Astley',
    thumbnail: 'https://yt3.ggpht.com/abc',
    subscribers: 3_200_000,
  });
  assert.ok(parsed && parsed.artist, 'expected an artist');
  assert.equal(parsed.artist.id, 'UCuAXFkgsw1L7xaCfnd5JJOw');
  assert.equal(parsed.artist.name, 'Rick Astley');
  assert.equal(parsed.artist.subscribers, '3.2M subscribers');
  assert.equal(parsed.artist.query, 'Rick Astley top songs');
  assert.equal(parsed.artist.thumbnail, 'https://yt3.ggpht.com/abc');
});

test('parsePipedSearchItem: channel without a resolvable id falls back to a slug', () => {
  const parsed = parsePipedSearchItem({ type: 'channel', url: '/@rickastley', name: 'Rick Astley' });
  assert.ok(parsed && parsed.artist);
  assert.equal(parsed.artist.id, 'piped:Rick Astley');
  assert.equal(parsed.artist.subscribers, undefined, 'no subs reported → omitted, not faked');
});

test('parsePipedSearchItem: playlist → playlist with list id + protocol-relative thumb upgraded', () => {
  const parsed = parsePipedSearchItem({
    type: 'playlist',
    url: '/playlist?list=PLabc123def456',
    name: 'Best of Rick',
    uploaderName: 'Fan Uploads',
    videos: 20,
    thumbnail: '//i.ytimg.com/vi/x/hqdefault.jpg',
  });
  assert.ok(parsed && parsed.playlist, 'expected a playlist');
  assert.equal(parsed.playlist.id, 'PLabc123def456');
  assert.equal(parsed.playlist.title, 'Best of Rick');
  assert.equal(parsed.playlist.author, 'Fan Uploads');
  assert.equal(parsed.playlist.trackCount, 20);
  assert.equal(parsed.playlist.thumbnail, 'https://i.ytimg.com/vi/x/hqdefault.jpg');
});

test('parsePipedSearchItem: unknown type (radio) is skipped', () => {
  assert.equal(parsePipedSearchItem({ type: 'radio', name: 'Mix' }), null);
});

test('parsePipedSearchItem: guards malformed input', () => {
  assert.equal(parsePipedSearchItem(null), null);
  assert.equal(parsePipedSearchItem({}), null); // no type, no usable stream fields
  assert.equal(parsePipedSearchItem({ type: 'playlist', url: '/playlist?list=PL1' }), null); // no title
  assert.equal(parsePipedSearchItem({ type: 'channel', url: '/channel/UC1' }), null); // no name
  // Stream with an implausibly short video id is rejected.
  assert.equal(parsePipedSearchItem({ type: 'stream', url: '/watch?v=ab', title: 'x' }), null);
});

// ---------------------------------------------------------------------------
// Invidious parsing
// ---------------------------------------------------------------------------
test('parseInvidiousSearchItem: video → song', () => {
  const parsed = parseInvidiousSearchItem({
    type: 'video',
    videoId: 'abc12',
    title: 'Some Song',
    author: 'Some Artist',
    lengthSeconds: 200,
  });
  assert.ok(parsed && parsed.song);
  assert.equal(parsed.song.id, 'abc12');
  assert.equal(parsed.song.title, 'Some Song');
  assert.equal(parsed.song.artist, 'Some Artist');
  assert.equal(parsed.song.duration, 200);
});

test('parseInvidiousSearchItem: channel → artist, largest thumb chosen + upgraded', () => {
  const parsed = parseInvidiousSearchItem({
    type: 'channel',
    author: 'Adele',
    authorId: 'UCadele',
    subCount: 1_500_000,
    authorThumbnails: [{ url: '//yt3.ggpht.com/small' }, { url: '//yt3.ggpht.com/large' }],
  });
  assert.ok(parsed && parsed.artist);
  assert.equal(parsed.artist.id, 'UCadele');
  assert.equal(parsed.artist.name, 'Adele');
  assert.equal(parsed.artist.subscribers, '1.5M subscribers');
  assert.equal(parsed.artist.thumbnail, 'https://yt3.ggpht.com/large');
  assert.equal(parsed.artist.query, 'Adele top songs');
});

test('parseInvidiousSearchItem: playlist → playlist', () => {
  const parsed = parseInvidiousSearchItem({
    type: 'playlist',
    playlistId: 'OLAK5uy_abc',
    title: '25',
    author: 'Adele',
    videoCount: 11,
    playlistThumbnail: 'https://i.ytimg.com/x.jpg',
  });
  assert.ok(parsed && parsed.playlist);
  assert.equal(parsed.playlist.id, 'OLAK5uy_abc');
  assert.equal(parsed.playlist.title, '25');
  assert.equal(parsed.playlist.author, 'Adele');
  assert.equal(parsed.playlist.trackCount, 11);
});

test('parseInvidiousSearchItem: guards malformed input', () => {
  assert.equal(parseInvidiousSearchItem(null), null);
  assert.equal(parseInvidiousSearchItem({ type: 'channel' }), null); // no author
  assert.equal(parseInvidiousSearchItem({ type: 'playlist', title: 'x' }), null); // no id
});

// ---------------------------------------------------------------------------
// bucketItems: classification, de-dup, caps
// ---------------------------------------------------------------------------
test('bucketItems sorts a mixed Piped payload into typed buckets', () => {
  const items = [
    { type: 'channel', url: '/channel/UC1', name: 'Artist One' },
    { type: 'stream', url: '/watch?v=vid00001', title: 'Song A', uploaderName: 'A', duration: 100 },
    { type: 'playlist', url: '/playlist?list=PL1abcdef', name: 'PL One' },
    { type: 'stream', url: '/watch?v=vid00002', title: 'Song B', uploaderName: 'B', duration: 120 },
    { type: 'radio', name: 'skip me' },
  ];
  const out = bucketItems(items, 'piped');
  assert.equal(out.songs.length, 2);
  assert.equal(out.artists.length, 1);
  assert.equal(out.playlists.length, 1);
  assert.equal(out.artists[0].name, 'Artist One');
  assert.equal(out.playlists[0].id, 'PL1abcdef');
});

test('bucketItems de-dupes repeated ids within a bucket', () => {
  const items = [
    { type: 'stream', url: '/watch?v=dupe00001', title: 'Song', uploaderName: 'A', duration: 100 },
    { type: 'stream', url: '/watch?v=dupe00001', title: 'Song', uploaderName: 'A', duration: 100 },
    { type: 'channel', url: '/channel/UCdup', name: 'Dupe' },
    { type: 'channel', url: '/channel/UCdup', name: 'Dupe' },
  ];
  const out = bucketItems(items, 'piped');
  assert.equal(out.songs.length, 1, 'duplicate song id collapsed');
  assert.equal(out.artists.length, 1, 'duplicate channel id collapsed');
});

test('bucketItems tolerates a non-array payload', () => {
  const out = bucketItems(null, 'piped');
  assert.deepEqual(out, { songs: [], artists: [], playlists: [] });
});
