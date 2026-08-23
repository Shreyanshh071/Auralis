// Behaviour tests for lyrics title cleaning, candidate matching, and rendition
// detection.
//
// Run with:  node scripts/test-lyrics-matching.mjs
//
// Imports src/services/lyrics.ts directly; Node strips the type annotations
// (built in from Node 22.18 / 23.6 onward), safe because tsconfig sets
// "erasableSyntaxOnly". The assertions run against the exact functions the app
// uses to pick and label lyrics — nothing is re-implemented here. Only the pure
// functions are exercised; fetchLyrics (which does network I/O) is never called.
import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const modulePath = path.join(repoRoot, 'src', 'services', 'lyrics.ts');

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

const {
  cleanTitle,
  normalizeForMatch,
  tokenSimilarity,
  isRelatedMatch,
  detectRendition,
  parseLrc,
} = mod;

// ---------------------------------------------------------------------------
// cleanTitle
// ---------------------------------------------------------------------------

test('cleanTitle keeps interior hyphens that are part of the real name', () => {
  // The whole point of the rewrite: a hyphen inside a name must survive.
  assert.equal(cleanTitle('Jay-Z'), 'Jay-Z');
  assert.equal(cleanTitle('twenty-one pilots'), 'twenty-one pilots');
  assert.equal(cleanTitle('Spider-Man Theme'), 'Spider-Man Theme');
  assert.equal(cleanTitle('Sk8er Boi'), 'Sk8er Boi');
  // A pipe that is genuinely part of nothing trailing-recognised is left alone
  // rather than truncating the title.
  assert.equal(cleanTitle('Song A | Song B'), 'Song A | Song B');
});

test('cleanTitle strips bracketed annotations and feat credits', () => {
  assert.equal(cleanTitle('Blinding Lights (Official Video)'), 'Blinding Lights');
  assert.equal(cleanTitle('Levitating [4K]'), 'Levitating');
  assert.equal(cleanTitle('Song (feat. Someone)'), 'Song');
  assert.equal(cleanTitle('Song feat. Someone Else'), 'Song');
  assert.equal(cleanTitle('Song ft. X'), 'Song');
  assert.equal(cleanTitle('Track featuring Guest'), 'Track');
});

test('cleanTitle strips only a KNOWN trailing qualifier after a separator', () => {
  assert.equal(cleanTitle('Bohemian Rhapsody - Remastered 2011'), 'Bohemian Rhapsody');
  assert.equal(cleanTitle('Song - Official Music Video'), 'Song');
  assert.equal(cleanTitle('Song | Lyrics'), 'Song');
  assert.equal(cleanTitle('Song - Radio Edit'), 'Song');
  // Stacked trailing tags are peeled one after another.
  assert.equal(cleanTitle('Song - Remastered - Official Video'), 'Song');
  // A trailing segment that is NOT a recognised qualifier stays put.
  assert.equal(cleanTitle('Song - My Favourite Part'), 'Song - My Favourite Part');
});

test('cleanTitle removes fan-rip descriptors used for query building', () => {
  assert.equal(cleanTitle('Blinding Lights (sped up)'), 'Blinding Lights');
  assert.equal(cleanTitle('Heat Waves - slowed + reverb'), 'Heat Waves');
  assert.equal(cleanTitle('Some Song (Nightcore)'), 'Some Song');
  assert.equal(cleanTitle('Track (Bass Boosted)'), 'Track');
});

test('cleanTitle is safe on empty / whitespace input', () => {
  assert.equal(cleanTitle(''), '');
  assert.equal(cleanTitle('   '), '');
  assert.equal(cleanTitle(undefined), '');
});

// ---------------------------------------------------------------------------
// normalizeForMatch + tokenSimilarity
// ---------------------------------------------------------------------------

test('normalizeForMatch folds case, accents and punctuation', () => {
  assert.equal(normalizeForMatch('Beyoncé!'), 'beyonce');
  assert.equal(normalizeForMatch('AC/DC'), 'ac dc');
  assert.equal(normalizeForMatch('Simon & Garfunkel'), 'simon and garfunkel');
  assert.equal(normalizeForMatch('  Multiple   Spaces  '), 'multiple spaces');
});

test('tokenSimilarity is 1 for equal, 0 for disjoint, in-between otherwise', () => {
  assert.equal(tokenSimilarity('Blinding Lights', 'blinding lights'), 1);
  assert.equal(tokenSimilarity('Blinding Lights', 'Levitating'), 0);
  const partial = tokenSimilarity('Blinding Lights', 'Blinding');
  assert.ok(partial > 0 && partial < 1, `expected partial overlap, got ${partial}`);
  // Two empty strings are trivially equal; one empty is not.
  assert.equal(tokenSimilarity('', ''), 1);
  assert.equal(tokenSimilarity('Song', ''), 0);
});

// ---------------------------------------------------------------------------
// isRelatedMatch  (rejects unrelated LRCLIB results)
// ---------------------------------------------------------------------------

test('isRelatedMatch accepts the same song even with artist-name variance', () => {
  assert.equal(
    isRelatedMatch('Blinding Lights', 'The Weeknd', 'Blinding Lights', 'Weeknd'),
    true,
  );
  // Strong title match alone is enough (uploaders vary wildly on YouTube).
  assert.equal(
    isRelatedMatch('Blinding Lights', 'Some Topic Channel', 'Blinding Lights', 'The Weeknd'),
    true,
  );
});

test('isRelatedMatch accepts multi-artist delimiters (&, /, comma, +)', () => {
  // Sam Smith & Kim Petras vs Sam Smith/Kim Petras
  assert.equal(
    isRelatedMatch(
      'Unholy (feat. Kim Petras)',
      'Sam Smith/Kim Petras',
      'Unholy',
      'Sam Smith & Kim Petras'
    ),
    true
  );
  // Candidate track with Artist - Title format
  assert.equal(
    isRelatedMatch(
      'Sam Smith, Kim Petras - Unholy',
      'SAM SMITH',
      'Unholy',
      'Sam Smith & Kim Petras'
    ),
    true
  );
  // Comma vs ampersand
  assert.equal(
    isRelatedMatch(
      'Uptown Funk',
      'Mark Ronson, Bruno Mars',
      'Uptown Funk',
      'Mark Ronson ft. Bruno Mars'
    ),
    true
  );
  // Plus sign vs &
  assert.equal(
    isRelatedMatch(
      'STAY',
      'The Kid LAROI + Justin Bieber',
      'Stay',
      'The Kid LAROI & Justin Bieber'
    ),
    true
  );
});

test('isRelatedMatch handles embedded Artist - Title format in candidate track', () => {
  assert.equal(
    isRelatedMatch(
      'The Weeknd - Blinding Lights',
      'The Weeknd',
      'Blinding Lights',
      'The Weeknd'
    ),
    true
  );
  assert.equal(
    isRelatedMatch(
      'Foster The People - Pumped Up Kicks',
      'Foster The People',
      'Pumped Up Kicks',
      'Foster the People'
    ),
    true
  );
});

test('isRelatedMatch rejects a different song', () => {
  assert.equal(
    isRelatedMatch('Levitating', 'Dua Lipa', 'Blinding Lights', 'The Weeknd'),
    false
  );
  // Same artist, wrong track must NOT match
  assert.equal(
    isRelatedMatch('Save Your Tears', 'The Weeknd', 'Blinding Lights', 'The Weeknd'),
    false
  );
  assert.equal(
    isRelatedMatch('Bad Guy', 'Billie Eilish', 'Happier Than Ever', 'Billie Eilish'),
    false
  );
});

// ---------------------------------------------------------------------------
// detectRendition  (drives the honest synced -> plain downgrade)
// ---------------------------------------------------------------------------

test('detectRendition flags tempo-altered rips', () => {
  assert.equal(detectRendition('Blinding Lights (sped up)').tempoAltered, true);
  assert.equal(detectRendition('Heat Waves - slowed + reverb').tempoAltered, true);
  assert.equal(detectRendition('Some Song [Nightcore]').tempoAltered, true);
  assert.equal(detectRendition('Track (8D Audio)').tempoAltered, true);
  assert.equal(detectRendition('Tune (Bass Boosted)').tempoAltered, true);
});

test('detectRendition flags alternate versions', () => {
  assert.equal(detectRendition('Song (Live at Wembley)').alternateVersion, true);
  assert.equal(detectRendition('Song - Acoustic').alternateVersion, true);
  assert.equal(detectRendition('Song (Piano Version)').alternateVersion, true);
  assert.equal(detectRendition('Song (Radiohead Cover)').alternateVersion, true);
  assert.equal(detectRendition('Song - Remix').alternateVersion, true);
  assert.equal(detectRendition('Song (Karaoke)').alternateVersion, true);
});

test('detectRendition leaves ordinary titles untouched', () => {
  const plain = detectRendition('Blinding Lights');
  assert.equal(plain.tempoAltered, false);
  assert.equal(plain.alternateVersion, false);
  assert.equal(plain.label, undefined);
  // Words that merely CONTAIN a marker substring must not trip the detector.
  assert.equal(detectRendition('Alive').alternateVersion, false);
  assert.equal(detectRendition('Livewire').alternateVersion, false);
  assert.equal(detectRendition('Delivery').alternateVersion, false);
});

// ---------------------------------------------------------------------------
// parseLrc  (honest word-timing labelling)
// ---------------------------------------------------------------------------

test('parseLrc reports word timing ONLY for genuine enhanced LRC', () => {
  const enhanced = '[00:12.00] <00:12.00>Hello <00:12.50>world';
  const parsedEnhanced = parseLrc(enhanced);
  assert.equal(parsedEnhanced.hasWordTiming, true);
  assert.ok(parsedEnhanced.lines[0].words && parsedEnhanced.lines[0].words.length === 2);

  const standard = '[00:12.00] Hello world\n[00:15.00] Next line';
  const parsedStandard = parseLrc(standard);
  assert.equal(parsedStandard.hasWordTiming, false);
  assert.equal(parsedStandard.lines.length, 2);
  assert.equal(parsedStandard.lines[0].words, undefined);
});

test('parseLrc sorts lines by time and marks instrumental breaks', () => {
  const lrc = '[00:30.00] second\n[00:10.00] first\n[00:20.00] (Instrumental)';
  const parsed = parseLrc(lrc);
  assert.deepEqual(
    parsed.lines.map((l) => l.text),
    ['first', '(Instrumental)', 'second'],
  );
  assert.equal(parsed.lines[1].isInstrumental, true);
});

test('parseLrc returns an empty, honest result for empty input', () => {
  assert.deepEqual(parseLrc(''), { lines: [], hasWordTiming: false });
});

// ---------------------------------------------------------------------------
// extractTrackAndArtistPairs & Embedded Artist Matching
// ---------------------------------------------------------------------------

test('extractTrackAndArtistPairs derives clean candidate pairs from messy titles', () => {
  const { extractTrackAndArtistPairs } = mod;

  // Hyphen without space: "Tv Girl -Loving Machine"
  const p1 = extractTrackAndArtistPairs('Tv Girl -Loving Machine', 'MusicLand');
  assert.ok(p1.some((p) => p.track === 'Loving Machine' && p.artist === 'Tv Girl'));

  // Pipe enclosure: "Loving Machine |TV Girl|"
  const p2 = extractTrackAndArtistPairs('Loving Machine |TV Girl|', 'mono sketches');
  assert.ok(p2.some((p) => p.track === 'Loving Machine' && p.artist === 'TV Girl'));

  // VEVO Channel: "Glass Animals - Heat Waves (Official Video)"
  const p3 = extractTrackAndArtistPairs('Glass Animals - Heat Waves (Official Video)', 'GlassAnimalsVEVO');
  assert.ok(p3.some((p) => p.track === 'Heat Waves' && p.artist === 'Glass Animals'));

  // Inverted: "TV Girl", "Loving Machine"
  const p4 = extractTrackAndArtistPairs('TV Girl', 'Loving Machine');
  assert.ok(p4.some((p) => p.track === 'Loving Machine' && p.artist === 'TV Girl'));
});

test('isRelatedMatch matches candidate when wantTrack embeds both title and artist', () => {
  // Candidate from LRCLIB: track="Loving Machine", artist="TV Girl"
  // Request from YouTube: track="Tv Girl -Loving Machine", artist="MusicLand"
  assert.equal(
    isRelatedMatch('Loving Machine', 'TV Girl', 'Tv Girl -Loving Machine', 'MusicLand'),
    true
  );

  // Request with pipe: track="Loving Machine |TV Girl|", artist="mono sketches"
  assert.equal(
    isRelatedMatch('Loving Machine', 'TV Girl', 'Loving Machine |TV Girl|', 'mono sketches'),
    true
  );

  // Inverted request: track="TV Girl", artist="Loving Machine"
  assert.equal(
    isRelatedMatch('Loving Machine', 'TV Girl', 'TV Girl', 'Loving Machine'),
    true
  );
});
