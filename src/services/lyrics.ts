import type { LyricsData, LyricLine } from '../types/music';
import { parseTtml } from '../lib/ttmlParser.ts';

/**
 * Clean a track or artist name for matching and display.
 *
 * Removes bracketed annotations, feat./ft. credits, fan-rip descriptors
 * (sped up / slowed / nightcore / ...), and a TRAILING production or version
 * qualifier that follows a " - " or " | " separator ("- Official Video",
 * "- Remastered 2011", "| Lyrics"). It deliberately does NOT truncate at the
 * first hyphen, because real titles and artists contain interior hyphens
 * ("Jay-Z", "twenty-one pilots", "Sk8er Boi").
 */
export function cleanTitle(text: string): string {
  if (!text || typeof text !== 'string') return '';
  let s = text;

  // Bracketed / parenthetical annotations: (Official Video), [4K], (Remix)...
  s = s.replace(/\([^)]*\)/g, ' ').replace(/\[[^\]]*\]/g, ' ');

  // feat. / ft. / featuring credits run to the end of the segment.
  s = s.replace(/\s*(?:feat\.?|ft\.?|featuring)\s+.*$/i, ' ');

  // Fan-rip descriptors are never part of the real title.
  s = s.replace(
    /\b(?:sped\s*up|slowed(?:\s*(?:down|\+?\s*reverb))?|reverb|nightcore|daycore|bass\s*boosted|8d\s*audio|chopped\s*(?:and|&|n)\s*screwed)\b/gi,
    ' '
  );

  // A trailing production/version qualifier after a "-" or "|" separator
  const trailing =
    /\s*[-|]\s*(?:official\s*(?:music\s*)?video|official\s*audio|official\s*visuali[sz]er|(?:official\s*)?lyrics?(?:\s*video)?|audio|video|visuali[sz]er|m\/?v|hd|hq|4k|remaster(?:ed)?(?:\s*\d{2,4})?|radio\s*edit|album\s*version|single\s*version|extended(?:\s*(?:mix|version))?|explicit|clean\s*version)\s*$/i;
  for (let i = 0; i < 3 && trailing.test(s); i++) {
    s = s.replace(trailing, '');
  }

  // Strip a TRAILING run of production/upload descriptor words even when there
  // is no "-"/"|" separator in front of them. YouTube titles very often append
  // things like "8K Full Video Song", "Full Video", "Lyrical Video" directly.
  // To stay safe on real titles that merely END in one common word ("Love
  // Song", "Swan Song"), a run is only removed when it is either ≥2 descriptor
  // words long OR contains a "strong" descriptor (a resolution / clearly
  // non-lexical marker). A single weak trailing word is left alone.
  s = stripTrailingDescriptorRun(s);

  s = s.replace(/\s{2,}/g, ' ').trim();
  // Drop dangling separator at either end
  s = s.replace(/\s*[-|–—:]\s*$/, '').replace(/^\s*[-|–—:]\s*/, '').trim();
  return s;
}

// Weak descriptors: common enough to appear inside real titles, so only removed
// as part of a longer descriptor run, never on their own.
const WEAK_DESCRIPTOR =
  /^(?:full|complete|music|video|videos|audio|song|songs|version|studio|hd|hq)$/i;
// Strong descriptors: resolution tags and markers that are essentially never
// part of a song's real name, so a single one is enough to strip the run.
const STRONG_DESCRIPTOR =
  /^(?:official|lyrical|lyric|lyrics|visuali[sz]er|visuals?|jukebox|remaster(?:ed)?|4k|8k|2k|uhd|fhd|hdr|1080p?|720p?|480p|60fps|reprise|teaser|trailer|promo|mv)$/i;

/** Remove a qualifying trailing run of descriptor tokens (see cleanTitle). */
function stripTrailingDescriptorRun(input: string): string {
  const tokens = input.split(/\s+/);
  let cut = tokens.length;
  let removed = 0;
  let sawStrong = false;
  while (cut > 0) {
    const tok = tokens[cut - 1].replace(/[.,!]+$/, '');
    if (STRONG_DESCRIPTOR.test(tok)) {
      sawStrong = true;
    } else if (!WEAK_DESCRIPTOR.test(tok)) {
      break;
    }
    cut--;
    removed++;
  }
  // Keep at least one token, and only cut when the run is clearly noise.
  if (cut >= 1 && (removed >= 2 || sawStrong)) {
    return tokens.slice(0, cut).join(' ');
  }
  return input;
}

/** Clean YouTube channel noise / suffixes from artist name. */
export function cleanArtistName(raw: string): string {
  if (!raw || typeof raw !== 'string') return '';
  let cleaned = raw
    .replace(/\s*-\s*Topic$/i, '')
    .replace(/VEVO$/i, '')
    .replace(/\s+Official$/i, '')
    .trim();
  return cleaned || raw;
}

/**
 * Normalise text for fuzzy comparison: lowercase, strip accents and punctuation.
 */
export function normalizeForMatch(text: string): string {
  if (!text || typeof text !== 'string') return '';
  return text
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '') // combining diacritical marks
    .replace(/&/g, ' and ')
    .replace(/[+,/\\|~:•–—]/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

/** Word-token Dice coefficient of two strings, in [0, 1]. */
export function tokenSimilarity(a: string, b: string): number {
  const ta = normalizeForMatch(a).split(' ').filter(Boolean);
  const tb = normalizeForMatch(b).split(' ').filter(Boolean);
  if (ta.length === 0 && tb.length === 0) return 1;
  if (ta.length === 0 || tb.length === 0) return 0;
  const setB = new Map<string, number>();
  for (const w of tb) setB.set(w, (setB.get(w) ?? 0) + 1);
  let overlap = 0;
  for (const w of ta) {
    const n = setB.get(w) ?? 0;
    if (n > 0) {
      overlap++;
      setB.set(w, n - 1);
    }
  }
  return (2 * overlap) / (ta.length + tb.length);
}

export interface CanonicalMetadata {
  trackName: string;
  artistName: string;
  duration?: number;
  album?: string;
  artworkUrl?: string;
}

export interface TrackArtistPair {
  track: string;
  artist: string;
}

const RECORD_LABELS = new Set([
  't-series', 'tseries', 'zee music company', 'zeemusiccompany', 'sony music india',
  'sonymusicindia', 'sony music', 'sonymusic', 'speed records', 'saregama',
  'saregama music', 'tips official', 'tips music', 'yrf', 'yash raj films',
  'aditya music', 'lahari music', 'lyrical lemonade', 'spinnin records',
  'spinnin\' records', 'monstercat', 'warner music', 'universal music',
  'universal music group', 'universal music india', 'atlantic records',
  'def jam', 'eros now', 'times music', 'white hill music', 'geet mp3',
  'desi music factory', 'dm - desi music factory', 'vyrl originals', 'svf',
]);

/** Check if an artist name represents a YouTube record label or publisher channel rather than the actual musician. */
export function isRecordLabelOrUploader(name: string): boolean {
  if (!name || typeof name !== 'string') return false;
  const norm = normalizeForMatch(name).replace(/\s+/g, '');
  if (RECORD_LABELS.has(normalizeForMatch(name)) || RECORD_LABELS.has(norm)) return true;
  return /(tseries|sonymusic|zeemusic|saregama|yrf|tipsofficial|spinninrecords|monstercat|vevo|officialchannel|records|musiccompany|official$)/i.test(norm);
}

/**
 * YouTube "tag" tokens that are boilerplate, not part of a song's identity:
 * upload descriptors and market/genre labels that appear across thousands of
 * unrelated uploads ("Latest Punjabi Songs 2025", "Official Video"). Two
 * DIFFERENT songs often share only these tokens, which would otherwise inflate
 * title similarity into a false match. Deliberately excludes ordinary words
 * that can be real one-word titles ("new", "stay", "believer", "numb").
 */
const TITLE_BOILERPLATE = new Set([
  'official', 'video', 'audio', 'music', 'lyrical', 'lyric', 'lyrics', 'visualizer',
  'visualiser', 'visuals', 'visual', 'full', 'hd', 'hq', '4k', '8k', '2k', 'uhd', 'fhd',
  'teaser', 'trailer', 'promo', 'mv', 'feat', 'ft', 'prod', 'remaster', 'remastered',
  'jukebox', 'latest', 'punjabi', 'hindi', 'tamil', 'telugu', 'bhojpuri', 'bollywood',
  'song', 'songs', 'records', 'record', 'entertainment', 'presents', 'ost', 'soundtrack',
]);

/** Distinctive title tokens: normalized words minus boilerplate, years, and the artist's own name. */
function distinctiveTitleTokens(title: string, ...artists: string[]): string[] {
  const artistTokens = new Set(
    artists.flatMap((a) => normalizeForMatch(a).split(' ')).filter(Boolean)
  );
  return normalizeForMatch(title)
    .split(' ')
    .filter(
      (t) => t && !TITLE_BOILERPLATE.has(t) && !/^\d{2,4}$/.test(t) && !artistTokens.has(t)
    );
}

/** Whether a raw segment carries any real (non-boilerplate) title content. */
function segmentHasRealTitle(segment: string): boolean {
  return distinctiveTitleTokens(segment).length > 0;
}

/**
 * Extract plausible (track, artist) candidate pairs from messy YouTube / streaming metadata.
 * Handles:
 * - Direct: "Loving Machine", "TV Girl"
 * - Multi-artist: "Karun x Arpit Bala x ReVo LEKHAK" -> individual pairs
 * - Embedded separator: "Tv Girl -Loving Machine" -> ("Loving Machine", "Tv Girl") and ("Tv Girl", "Loving Machine")
 * - Enclosed: "Loving Machine |TV Girl|" -> ("Loving Machine", "TV Girl")
 * - Inverted: "TV Girl", "Loving Machine" -> ("Loving Machine", "TV Girl")
 */
export function extractTrackAndArtistPairs(rawTrack: string, rawArtist: string): TrackArtistPair[] {
  const pairs: TrackArtistPair[] = [];
  const seen = new Set<string>();

  const addPair = (t: string, a: string) => {
    const cleanT = cleanTitle(t);
    const cleanA = cleanArtistName(a);
    if (!cleanT) return;
    const key = `${cleanT.toLowerCase()}:::${cleanA.toLowerCase()}`;
    if (!seen.has(key)) {
      seen.add(key);
      pairs.push({ track: cleanT, artist: cleanA });
    }
  };

  // 1. Direct cleaned inputs
  addPair(rawTrack, rawArtist);

  // 2. Multi-artist decomposition in rawArtist (e.g. "Karun, Arpit Bala, Lambo Drive" or "Arpit Bala x Karun")
  if (rawArtist) {
    const subArtists = rawArtist
      .split(/\s*(?:,|&|\bx\b|\bX\b|\bfeat\.?\b|\bft\.?\b|\band\b|\/|•)\s*/i)
      .map(cleanArtistName)
      .filter(Boolean);
    for (const sub of subArtists) {
      addPair(rawTrack, sub);
    }
  }

  // 3. Check for separators in rawTrack ("Artist - Title" or "Title - Artist" or "Title | Artist" or "Artist : Title")
  const sepMatch = rawTrack.match(/^(.*?)\s*[-–—:|~•]\s*(.*)$/);
  if (sepMatch) {
    const p1 = sepMatch[1].trim();
    const p2 = sepMatch[2].trim();
    if (p1 && p2) {
      addPair(p2, p1); // Artist - Title
      addPair(p1, p2); // Title - Artist

      const sub1 = p1
        .split(/\s*(?:,|&|\bx\b|\bX\b|\bfeat\.?\b|\bft\.?\b|\band\b|\/|•)\s*/i)
        .map(cleanArtistName)
        .filter(Boolean);
      for (const s of sub1) {
        addPair(p2, s);
      }
      const sub2 = p2
        .split(/\s*(?:,|&|\bx\b|\bX\b|\bfeat\.?\b|\bft\.?\b|\band\b|\/|•)\s*/i)
        .map(cleanArtistName)
        .filter(Boolean);
      for (const s of sub2) {
        addPair(p1, s);
      }
    }
  }

  // 4. Check for pipe enclosure: "Title |Artist|"
  const pipeMatch = rawTrack.match(/^(.*?)\s*\|([^|]+)\|\s*$/);
  if (pipeMatch) {
    addPair(pipeMatch[1], pipeMatch[2]);
  }

  // 5. Inverted candidate (when source swapped title & artist)
  if (rawArtist) {
    addPair(rawArtist, rawTrack);
  }

  // 6. Multi-pipe / double-pipe jukebox titles: "Song || Extra || tag" or
  //    "Song | descriptor | descriptor". The real song is almost always the
  //    first segment, so add it against the given artist.
  if (/[|｜]/.test(rawTrack)) {
    const segments = rawTrack
      .split(/\s*[|｜]+\s*/)
      .map((seg) => seg.trim())
      .filter(Boolean);
    if (segments.length > 1) {
      addPair(segments[0], rawArtist);
      // Also try each early segment as a title (some uploaders put the artist
      // first: "Artist | Song | tag") — but ONLY segments that carry real title
      // content. Pure tag segments ("Latest Punjabi Songs 2025") must never
      // become search queries: they fuzzy-match any unrelated song sharing that
      // boilerplate, producing wrong lyrics.
      for (const seg of segments.slice(0, 3)) {
        if (segmentHasRealTitle(seg)) {
          addPair(seg, rawArtist);
        }
      }
    }
  }

  // 7. Artist name duplicated inside the title. YouTube uploaders often append
  //    (or prepend) the artist to the track title: "BAARISHEIN Anuv Jain" with
  //    artist "Anuv Jain". Add a candidate with that duplicate removed.
  if (rawArtist) {
    const stripped = stripArtistFromTitle(cleanTitle(rawTrack), cleanArtistName(rawArtist));
    if (stripped) {
      addPair(stripped, rawArtist);
    }
  }

  return pairs;
}

/**
 * If a cleaned title still contains the artist name as a leading or trailing
 * run of words, remove that run and return the remaining title. Returns null
 * when nothing was removed or removal would empty the title.
 */
function stripArtistFromTitle(title: string, artist: string): string | null {
  if (!title || !artist) return null;
  const titleTokens = title.split(/\s+/);
  const artistTokens = normalizeForMatch(artist).split(' ').filter(Boolean);
  if (artistTokens.length === 0 || titleTokens.length <= artistTokens.length) return null;

  const normTitle = titleTokens.map((t) => normalizeForMatch(t));
  const artistStr = artistTokens.join(' ');

  // Trailing artist run
  if (normTitle.slice(-artistTokens.length).join(' ') === artistStr) {
    const remaining = titleTokens.slice(0, titleTokens.length - artistTokens.length).join(' ').trim();
    return remaining || null;
  }
  // Leading artist run
  if (normTitle.slice(0, artistTokens.length).join(' ') === artistStr) {
    const remaining = titleTokens.slice(artistTokens.length).join(' ').trim();
    return remaining || null;
  }
  return null;
}

/**
 * For heavily-segmented titles ("Movie: Song 8K Full Video | Artist, Artist" or
 * "Song || collab || tag"), the real song title and the real artist are often
 * DIFFERENT segments, and the channel owner is frequently a label — so neither
 * the direct pair nor the owner is reliable. This enumerates (segment-as-title,
 * segment-or-owner-as-artist) candidates.
 *
 * These are intentionally kept OUT of the fuzzy /search tier (a junk segment
 * like a producer credit can fuzzy-match a wrong same-artist song). They are
 * only ever consumed by the EXACT-DURATION get path, which is self-guarding:
 * a wrong-duration song can't be returned, and isRelatedMatch still gates.
 */
export function extractSegmentPairs(rawTrack: string, rawArtist: string): TrackArtistPair[] {
  if (!/[|｜:：/／~•]/.test(rawTrack)) return [];

  const rawSegs = rawTrack
    .split(/\s*[|｜:：/／~•]+\s*/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (rawSegs.length < 2) return [];

  // Clean each segment as a potential title; drop empties, pure numbers and
  // codes ("dl91", "2024"), which are never song titles.
  const titleSegs = rawSegs
    .map((s) => cleanTitle(s))
    .filter((s) => s && s.length >= 3 && !/^\d+$/.test(s) && !/^[a-z]{1,2}\d+$/i.test(s));

  // Candidate artists: the channel owner, plus every segment decomposed into
  // individual performers ("Arijit Singh, Palak Muchhal" -> two artists).
  const splitArtists = (s: string) =>
    s
      .split(/\s*(?:,|&|\bx\b|\bX\b|\bfeat\.?\b|\bft\.?\b|\band\b|\/|•)\s*/i)
      .map(cleanArtistName)
      .filter(Boolean);
  const artistCandidates = [
    ...(rawArtist ? [cleanArtistName(rawArtist)] : []),
    ...rawSegs.flatMap(splitArtists),
  ].filter(Boolean);

  const pairs: TrackArtistPair[] = [];
  const seen = new Set<string>();
  const push = (track: string, artist: string) => {
    if (!track || !artist) return;
    const key = `${track.toLowerCase()}:::${artist.toLowerCase()}`;
    if (seen.has(key)) return;
    seen.add(key);
    pairs.push({ track, artist });
  };

  // Pair each plausible title segment with each candidate artist. Bounded so a
  // pathological 8-segment title can't explode into dozens of network probes;
  // the exact-duration path short-circuits on the first real hit anyway.
  for (const t of titleSegs.slice(0, 4)) {
    for (const a of artistCandidates.slice(0, 4)) {
      if (normalizeForMatch(t) === normalizeForMatch(a)) continue; // title == artist: useless
      push(t, a);
    }
  }
  return pairs.slice(0, 10);
}

/**
 * Whether a lyrics candidate plausibly IS the requested song, so unrelated
 * matches (e.g. wrong artists with same title) are rejected instead of shown.
 */
export function isRelatedMatch(
  candidateTrack: string,
  candidateArtist: string,
  wantTrack: string,
  wantArtist: string
): boolean {
  if (!candidateTrack || (!wantTrack && !wantArtist)) return false;

  const cleanWantTrack = cleanTitle(wantTrack);
  const cleanWantArtist = cleanArtistName(wantArtist);
  const cleanCandTrack = cleanTitle(candidateTrack);
  const cleanCandArtist = cleanArtistName(candidateArtist);

  const normWantTrack = normalizeForMatch(cleanWantTrack);
  const normWantArtist = normalizeForMatch(cleanWantArtist);
  const normCandTrack = normalizeForMatch(cleanCandTrack);
  const normCandArtist = normalizeForMatch(cleanCandArtist);

  // Precision gate: a song's identity is its FIRST title segment (before any
  // "|"/":"), not the trailing pipes of collaborators, labels and upload tags.
  // If the two first-segment "cores" (distinctive tokens, minus boilerplate and
  // either artist's name) are BOTH non-empty yet share nothing, these are
  // different songs that merely carry the same YouTube boilerplate — reject
  // before the fuzzy branches below can be fooled by that shared noise.
  const firstSeg = (s: string) => s.split(/\s*[|｜:：]+\s*/)[0] || s;
  const wantCore = distinctiveTitleTokens(cleanTitle(firstSeg(wantTrack)), cleanWantArtist, cleanCandArtist);
  const candCore = distinctiveTitleTokens(cleanTitle(firstSeg(candidateTrack)), cleanWantArtist, cleanCandArtist);
  if (wantCore.length > 0 && candCore.length > 0) {
    const wantSet = new Set(wantCore);
    if (!candCore.some((t) => wantSet.has(t))) {
      // Allow transliteration/concatenation differences ("raatkirani") before
      // rejecting; only a truly disjoint core means a different song.
      const wj = wantCore.join('');
      const cj = candCore.join('');
      if (!wj.includes(cj) && !cj.includes(wj)) return false;
    }
  }

  let titleSim = tokenSimilarity(cleanCandTrack, cleanWantTrack);
  let artistSim = tokenSimilarity(cleanCandArtist, cleanWantArtist);

  // Check if candidate track has segments ("Artist - Title" or "Title - Artist")
  if (candidateTrack.includes(' - ') || candidateTrack.includes(' | ') || candidateTrack.includes('-')) {
    const candSegments = candidateTrack.split(/\s*[-–—:|]\s*/);
    for (const seg of candSegments) {
      const segTitleSim = tokenSimilarity(cleanTitle(seg), cleanWantTrack);
      if (segTitleSim > titleSim) titleSim = segTitleSim;
      const segArtistSim = tokenSimilarity(cleanTitle(seg), cleanWantArtist);
      if (segArtistSim > artistSim) artistSim = segArtistSim;
    }
  }

  // Check if want track has segments
  if (wantTrack.includes(' - ') || wantTrack.includes(' | ') || wantTrack.includes('-')) {
    const wantSegments = wantTrack.split(/\s*[-–—:|]\s*/);
    for (const seg of wantSegments) {
      const segTitleSim = tokenSimilarity(cleanTitle(seg), cleanCandTrack);
      if (segTitleSim > titleSim) titleSim = segTitleSim;
      const segArtistSim = tokenSimilarity(cleanTitle(seg), cleanCandArtist);
      if (segArtistSim > artistSim) artistSim = segArtistSim;
    }
  }

  // Sub-artist token check: check if any word/artist in wantArtist is in candidateArtist or vice-versa
  const hasArtistOverlap =
    artistSim >= 0.25 ||
    (normWantArtist && normCandArtist && (normCandArtist.includes(normWantArtist) || normWantArtist.includes(normCandArtist))) ||
    (normWantTrack && normCandArtist && normWantTrack.includes(normCandArtist)) ||
    (normCandTrack && normWantArtist && normCandTrack.includes(normWantArtist));

  const isGenericOrTopicArtist =
    !cleanCandArtist ||
    !cleanWantArtist ||
    cleanWantArtist === 'Various Artists' ||
    cleanWantArtist.toLowerCase() === 'unknown artist' ||
    isRecordLabelOrUploader(cleanCandArtist) ||
    isRecordLabelOrUploader(cleanWantArtist) ||
    /topic|channel|vevo|official/i.test(cleanCandArtist) ||
    /topic|channel|vevo|official/i.test(cleanWantArtist);

  // 1. High Title Match (>= 0.8)
  if (titleSim >= 0.8) {
    if (isGenericOrTopicArtist || hasArtistOverlap) {
      return true;
    }
    // Check if inverted match (e.g. candidate artist is wantTrack)
    const invertedArtistSim = tokenSimilarity(cleanCandArtist, cleanWantTrack);
    if (invertedArtistSim >= 0.5) return true;
    // If title match is high and candidate artist is not a known collision
    if (titleSim >= 0.85 && cleanWantTrack.length >= 8 && artistSim >= 0.15) {
      return true;
    }
    if (titleSim >= 0.95 && isGenericOrTopicArtist) {
      return true;
    }
    return false;
  }

  // 2. Good Title Match (>= 0.6) with solid artist match or topic channel
  if (titleSim >= 0.6 && (hasArtistOverlap || isGenericOrTopicArtist)) {
    return true;
  }

  // 3. Moderate Title Match (>= 0.45) with strong artist match
  if (titleSim >= 0.45 && (artistSim >= 0.4 || (normWantArtist && normCandArtist && normCandArtist === normWantArtist))) {
    return true;
  }

  // 4. Inverted candidate comparison (when source metadata swapped title & artist)
  const invertedTitleSim = tokenSimilarity(cleanCandTrack, cleanWantArtist);
  const invertedArtistSim = tokenSimilarity(cleanCandArtist, cleanWantTrack);
  if (invertedTitleSim >= 0.75 && (invertedArtistSim >= 0.3 || isGenericOrTopicArtist)) {
    return true;
  }

  // 5. Embedded Artist - Title in wantTrack or candidateTrack:
  if (normCandTrack && normCandArtist && normWantTrack) {
    if (normWantTrack.includes(normCandTrack) && (normWantTrack.includes(normCandArtist) || hasArtistOverlap || isGenericOrTopicArtist)) {
      return true;
    }
  }
  if (normWantTrack && normWantArtist && normCandTrack) {
    if (normCandTrack.includes(normWantTrack) && (normCandTrack.includes(normWantArtist) || hasArtistOverlap || isGenericOrTopicArtist)) {
      return true;
    }
  }

  return false;
}

export interface RenditionInfo {
  tempoAltered: boolean;
  alternateVersion: boolean;
  label?: string;
}

/**
 * Inspect the RAW (pre-clean) title for markers that mean the base-song synced
 * lyrics would be mis-aligned. Runs on the original title, not the cleaned one.
 */
export function detectRendition(rawTitle: string): RenditionInfo {
  const t = ` ${(rawTitle || '').toLowerCase()} `;
  const tempoRx =
    /\b(sped\s*up|slowed|nightcore|daycore|bass\s*boosted|8d\s*audio|chopped\s*(and|&|n)\s*screwed)\b/;
  const tempo = tempoRx.test(t) || /slowed[^.]{0,12}reverb|reverb[^.]{0,12}slowed/.test(t);
  const altRx =
    /\b(live|acoustic|unplugged|cover|karaoke|instrumental|remix|rework|re-?work|mashup|orchestral|stripped|piano\s*version)\b/;
  const alt = altRx.test(t);
  let label: string | undefined;
  if (tempo) label = 'tempo-altered';
  else if (alt) label = 'alternate-version';
  return { tempoAltered: tempo, alternateVersion: alt, label };
}

/**
 * Whether a track is almost certainly NOT a single song — a jukebox, mashup,
 * "nonstop"/"best of" compilation, full album, or multi-hour lofi/study mix.
 * These never have matching synced lyrics, so we skip the network and return
 * an immediate honest "no lyrics" instead of hanging on doomed lookups.
 */
export function isProbablyNotASong(rawTitle: string, duration?: number): boolean {
  // Longer than 25 minutes: no single song, definitely a mix/album/podcast.
  if (duration && duration > 1500) return true;

  const t = ` ${(rawTitle || '').toLowerCase()} `;
  const compilationRx =
    /\b(jukebox|nonstop|non[-\s]?stop|mashup|mega\s?mix|dj\s?mix|compilation|playlist|full\s+album|all\s+songs|top\s+\d+\s+songs?|best\s+of|superhit\s+songs?|hit\s+songs?\s+(?:collection|jukebox)|video\s+jukebox|audio\s+jukebox|\d+\s+(?:songs?|hits)\b|hours?\s+of|lofi\s+(?:mix|playlist)|study\s+(?:mix|music)|relax(?:ing)?\s+(?:mix|music|songs))\b/;
  if (compilationRx.test(t)) {
    // A compilation keyword plus a long-ish runtime is conclusive. Short clips
    // that merely mention "best of" in the title are left to normal lookup.
    if (!duration || duration > 420) return true;
  }
  return false;
}

/** Flatten parsed synced lines into plain text. */
function linesToPlainText(lines: LyricLine[]): string {
  return lines
    .map((l) => l.text)
    .filter((t) => t && t !== '♪')
    .join('\n')
    .trim();
}

/** Result of parsing an LRC string. */
interface ParsedLrc {
  lines: LyricLine[];
  hasWordTiming: boolean;
}

/**
 * Parse standard or enhanced LRC format string into synchronized LyricLine array.
 * Returns `hasWordTiming: true` ONLY when the input contains actual `<mm:ss.ms>word`
 * enhanced LRC tags. Standard `[mm:ss.ms] text` lines produce `hasWordTiming: false`.
 */
export function parseLrc(lrcText: string): ParsedLrc {
  if (!lrcText) return { lines: [], hasWordTiming: false };

  const lines = lrcText.split('\n');
  const result: LyricLine[] = [];
  const timeRegex = /\[(\d{2,}):(\d{2})(?:\.(\d{2,3}))?\]/g;
  const wordTimeRegex = /<(\d{2,}):(\d{2})(?:\.(\d{2,3}))?>([^<]*)/g;
  let foundWordTiming = false;

  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;

    timeRegex.lastIndex = 0;
    const matches = [...trimmed.matchAll(timeRegex)];
    if (matches.length > 0) {
      let text = trimmed.replace(timeRegex, '').trim();
      if (!text && matches.length === 1) continue;

      const words: { word: string; time: number }[] = [];
      if (text.includes('<') && text.includes('>')) {
        let wordMatch: RegExpExecArray | null;
        wordTimeRegex.lastIndex = 0;
        while ((wordMatch = wordTimeRegex.exec(text)) !== null) {
          const wMin = parseInt(wordMatch[1], 10);
          const wSec = parseInt(wordMatch[2], 10);
          const wMs = wordMatch[3] ? parseInt(wordMatch[3].padEnd(3, '0').slice(0, 3), 10) : 0;
          const wTime = wMin * 60 + wSec + wMs / 1000;
          const wWord = wordMatch[4].trim();
          if (wWord) {
            words.push({ word: wWord, time: wTime });
          }
        }
        if (words.length > 0) {
          foundWordTiming = true;
        }
        text = text.replace(/<[^>]+>/g, '').trim();
      }

      const isInstrumental =
        /^\(?(instrumental|solo|outro|intro|guitar|synth|interlude)\)?$/i.test(text) ||
        (text.startsWith('(') && text.endsWith(')') && /solo|instrumental|intro|outro|groove|break/i.test(text));

      for (const match of matches) {
        const minutes = parseInt(match[1], 10);
        const seconds = parseInt(match[2], 10);
        const milliseconds = match[3] ? parseInt(match[3].padEnd(3, '0').slice(0, 3), 10) : 0;
        const totalSeconds = minutes * 60 + seconds + milliseconds / 1000;

        result.push({
          time: totalSeconds,
          text: text || '♪',
          words: words.length > 0 ? words : undefined,
          isInstrumental,
        });
      }
    }
  }

  return {
    lines: result.sort((a, b) => a.time - b.time),
    hasWordTiming: foundWordTiming,
  };
}

/**
 * Fetch with an explicit timeout.
 * Aborts and fails fast if the server hangs.
 */
export async function fetchWithTimeout(
  url: string,
  options: RequestInit = {},
  timeoutMs = 3500
): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      ...options,
      signal: controller.signal,
    });
    return res;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Fetch lyrics from YouTube video timed transcript (Captions API).
 */
async function fetchYouTubeCaptions(videoId: string): Promise<LyricLine[] | null> {
  try {
    const res = await fetchWithTimeout(
      `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://www.youtube.com/api/timedtext?lang=en&v=${videoId}&fmt=json3`)}`,
      {},
      2500
    );
    if (!res.ok) return null;

    const data = await res.json();
    if (!data.events || !Array.isArray(data.events)) return null;

    const lines: LyricLine[] = [];
    for (const evt of data.events) {
      if (!evt.segs || evt.segs.length === 0) continue;
      const text = evt.segs.map((s: any) => s.utf8 || '').join('').replace(/\n/g, ' ').trim();
      if (!text || text === '\n') continue;

      const startTime = (evt.tStartMs || 0) / 1000;
      lines.push({
        time: startTime,
        text,
      });
    }

    return lines.length > 0 ? lines : null;
  } catch {
    return null;
  }
}

// In-memory cache for recent lyrics lookups
const lyricsCache = new Map<string, { data: LyricsData | null; timestamp: number }>();
const CACHE_SUCCESS_TTL_MS = 1000 * 60 * 30; // 30 minutes for valid lyrics
const CACHE_FAIL_TTL_MS = 1000 * 60 * 2;     // 2 minutes for failed lookups
const MAX_CACHE_SIZE = 150;

function getCachedLyrics(key: string): LyricsData | null | undefined {
  const entry = lyricsCache.get(key);
  if (!entry) return undefined;
  const ttl = entry.data ? CACHE_SUCCESS_TTL_MS : CACHE_FAIL_TTL_MS;
  if (Date.now() - entry.timestamp > ttl) {
    lyricsCache.delete(key);
    return undefined;
  }
  return entry.data;
}

function setCachedLyrics(key: string, data: LyricsData | null): void {
  if (lyricsCache.size >= MAX_CACHE_SIZE) {
    const oldestKey = lyricsCache.keys().next().value;
    if (oldestKey) lyricsCache.delete(oldestKey);
  }
  lyricsCache.set(key, { data, timestamp: Date.now() });
}

// In-memory cache for canonical metadata lookups
const canonicalMetadataCache = new Map<string, { data: CanonicalMetadata | null; timestamp: number }>();
const CANONICAL_CACHE_TTL_MS = 1000 * 60 * 60; // 1 hour

const DESCRIPTOR_SEGMENT = /^(?:full\s*(?:video|audio|song|track)?|official\s*(?:video|music\s*video|audio|lyrics?|lyric\s*video)?|lyrical\s*(?:video|audio)?|audio\s*song|video\s*song|visuali[sz]er|m\/?v|4k|8k|hd|hq|jukebox|promo|teaser)$/i;
const TRIBUTE_PATTERN = /\b(?:tribute|performs|lullaby|piano dreamers|karaoke|cover|acoustic version|instrumental version|string quartet|orchestra performs)\b/i;

function filterRealSegments(raw: string): string[] {
  if (!raw) return [];
  return raw
    .split(/\s*[-–—:|~•]+\s*/)
    .map((s) => s.trim())
    .filter((s) => s && !DESCRIPTOR_SEGMENT.test(s));
}

/**
 * Resolve canonical track and artist metadata using iTunes / Apple Music search API.
 * Standardizes noisy YouTube video titles, movie tags, and record label channels
 * into clean track title, official artist, and studio duration tags.
 */
export async function resolveCanonicalMetadata(
  rawTrack: string,
  rawArtist: string
): Promise<CanonicalMetadata | null> {
  if (!rawTrack && !rawArtist) return null;

  const cacheKey = `${(rawTrack || '').toLowerCase()}:::${(rawArtist || '').toLowerCase()}`;
  const cached = canonicalMetadataCache.get(cacheKey);
  if (cached && Date.now() - cached.timestamp < CANONICAL_CACHE_TTL_MS) {
    return cached.data;
  }

  const cleanT = cleanTitle(rawTrack);
  const cleanA = cleanArtistName(rawArtist);
  const isLabel = isRecordLabelOrUploader(rawArtist);

  const segs = filterRealSegments(rawTrack);
  const firstSegClean = cleanTitle(segs[0] || cleanT);
  const remainingSegs = segs.slice(1).map(cleanTitle).filter((s) => s && !isRecordLabelOrUploader(s));

  const queries: string[] = [];

  // Direct queries
  if (!isLabel && cleanA && cleanA.toLowerCase() !== 'unknown artist') {
    queries.push(`${cleanT} ${cleanA}`);
    if (firstSegClean && firstSegClean !== cleanT) {
      queries.push(`${firstSegClean} ${cleanA}`);
    }
  } else {
    queries.push(cleanT);
    if (firstSegClean && firstSegClean !== cleanT) {
      queries.push(firstSegClean);
    }
  }

  if (firstSegClean && remainingSegs.length > 0) {
    queries.push(`${firstSegClean} ${remainingSegs[0]}`);
  }

  // Inverted queries (when rawArtist is the real song title and rawTrack has movie/singer segments)
  if (cleanA && cleanA.toLowerCase() !== 'unknown artist' && !isLabel) {
    for (const seg of [firstSegClean, ...remainingSegs].slice(0, 3)) {
      if (seg) queries.push(`${cleanA} ${seg}`);
    }
    queries.push(cleanA);
  }

  const uniqueQueries = [...new Set(queries.filter(Boolean))].slice(0, 5);

  for (const q of uniqueQueries) {
    try {
      const res = await fetchWithTimeout(
        `https://itunes.apple.com/search?term=${encodeURIComponent(q)}&entity=song&limit=10`,
        {},
        3000
      );
      if (!res.ok) continue;

      const body = await res.json();
      const results: any[] = body?.results || [];
      if (!results || results.length === 0) continue;

      // Score and rank candidates from iTunes
      const candidates = results
        .map((item: any) => {
          if (!item || !item.trackName || !item.artistName) return null;
          const itemTrack = cleanTitle(item.trackName);
          const itemArtist = cleanArtistName(item.artistName);
          const itemAlbum = item.collectionName ? cleanTitle(item.collectionName) : '';

          const isTribute = TRIBUTE_PATTERN.test(`${itemArtist} ${itemAlbum}`);

          const titleSimDirect = Math.max(
            tokenSimilarity(itemTrack, cleanT),
            tokenSimilarity(itemTrack, firstSegClean)
          );
          const artistSimDirect = tokenSimilarity(itemArtist, cleanA);
          const albumSimDirect = itemAlbum && cleanA ? tokenSimilarity(itemAlbum, cleanA) : 0;

          const titleSimInverted = tokenSimilarity(itemTrack, cleanA);
          let artistSimInverted = 0;
          for (const seg of [firstSegClean, ...remainingSegs]) {
            const sim = tokenSimilarity(itemArtist, seg);
            if (sim > artistSimInverted) artistSimInverted = sim;
          }

          let score = 0;
          let isDirect = true;

          if (titleSimDirect >= 0.6) {
            score = titleSimDirect * 3;
            if (!isLabel && cleanA && cleanA.toLowerCase() !== 'unknown artist') {
              if (artistSimDirect >= 0.35) {
                score += artistSimDirect * 6;
              } else if (albumSimDirect >= 0.5 && !isTribute) {
                score += albumSimDirect * 2.5;
              } else if (!isRecordLabelOrUploader(itemArtist)) {
                score -= 4;
              }
            }
            if (isRelatedMatch(itemTrack, itemArtist, rawTrack, rawArtist)) {
              score += 2;
            }
          } else if (titleSimInverted >= 0.65) {
            score = titleSimInverted * 3 + artistSimInverted * 3;
            isDirect = false;
          }

          if (isTribute && !TRIBUTE_PATTERN.test(`${cleanA} ${cleanT}`)) {
            score -= 5;
          }

          return { item, score, titleSim: isDirect ? titleSimDirect : titleSimInverted };
        })
        .filter((c): c is NonNullable<typeof c> => c !== null && c.score > 0)
        .sort((a, b) => b.score - a.score);

      const best = candidates[0];
      if (best && (best.score >= 2.5 || (isLabel && best.titleSim >= 0.75))) {
        const match = best.item;
        const canonical: CanonicalMetadata = {
          trackName: match.trackName,
          artistName: match.artistName,
          duration: match.trackTimeMillis ? Math.round(match.trackTimeMillis / 1000) : undefined,
          album: match.collectionName,
          artworkUrl: match.artworkUrl100 ? match.artworkUrl100.replace('100x100bb', '600x600bb') : undefined,
        };

        canonicalMetadataCache.set(cacheKey, { data: canonical, timestamp: Date.now() });
        return canonical;
      }
    } catch {
      // Continue to next query if network or parse error
    }
  }

  canonicalMetadataCache.set(cacheKey, { data: null, timestamp: Date.now() });
  return null;
}

/**
 * Fetch word/syllable-level synchronized lyrics from AMLL TTML Database.
 */
export async function fetchAmllLyrics(
  trackName: string,
  artistName: string,
  duration?: number,
  mustDowngradeSync?: boolean,
  videoId?: string
): Promise<LyricsData | null> {
  const candidatePairs = extractTrackAndArtistPairs(trackName, artistName);
  if (candidatePairs.length === 0) return null;

  for (const pair of candidatePairs.slice(0, 2)) {
    const searchQuery = `${pair.track} ${pair.artist}`.trim();
    if (!searchQuery) continue;

    // lrclib.net and api.amll.dev both serve `Access-Control-Allow-Origin: *`,
    // so the browser can call them directly. The old corsproxy.io / allorigins
    // fallbacks are dead (403 paywall / >3.5s timeout) and only added latency.
    const searchUrls = [
      `https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`,
    ];

    for (const url of searchUrls) {
      try {
        const res = await fetchWithTimeout(
          url,
          {},
          3500
        );
        if (!res.ok) continue;

        const body = await res.json();
        const items = body?.data?.items || (Array.isArray(body) ? body : []);
        if (!items || items.length === 0) {
          break; // primary search worked but found 0 items
        }

        // Filter candidates using conservative matching
        const candidates = items.filter((item: any) => {
          const musicNames: string[] = item.musicNames || (item.musicName ? [item.musicName] : []);
          const artistNames: string[] = item.artistNames || (item.artistName ? [item.artistName] : []);

          return (
            musicNames.some((m) => isRelatedMatch(m, artistNames[0] || '', pair.track, pair.artist)) ||
            isRelatedMatch(musicNames[0] || '', artistNames[0] || '', pair.track, pair.artist)
          );
        });

        if (candidates.length === 0) break;

        const best = candidates[0];
        const matchTrackName = (best.musicNames && best.musicNames[0]) || best.musicName || pair.track;
        const matchArtistName = (best.artistNames && best.artistNames[0]) || best.artistName || pair.artist;

        let xmlText: string | null = null;
        if (best.id) {
          try {
            const getRes = await fetchWithTimeout(
              `https://api.amll.dev/v1/lyrics/get?id=${best.id}`,
              {},
              3500
            );
            if (getRes.ok) {
              const getData = await getRes.json();
              xmlText = getData?.data?.lyrics || null;
            }
          } catch {}
        }

        if (!xmlText && best.filename) {
          try {
            const cdnRes = await fetchWithTimeout(
              `https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/raw-lyrics/${best.filename}`,
              {},
              3500
            );
            if (cdnRes.ok) {
              xmlText = await cdnRes.text();
            }
          } catch {}
        }

        if (!xmlText) continue;

        const parsed = parseTtml(xmlText);
        if (!parsed.lines || parsed.lines.length === 0) continue;

        // Validate duration if available from TTML metadata
        if (duration && duration > 0 && parsed.duration && parsed.duration > 0) {
          const durDiff = Math.abs(parsed.duration - duration);
          if (durDiff > 35) {
            continue;
          }
        }

        if (mustDowngradeSync) {
          const plainText = parsed.lines.map((l) => l.text).join('\n');
          return {
            syncType: 'plain',
            lines: [],
            plainLyrics: plainText,
            provider: 'amll',
            trackName: matchTrackName,
            artistName: matchArtistName,
          };
        }

        const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';

        console.info('[Lyrics:AMLL]', {
          videoId,
          track: trackName,
          artist: artistName,
          provider: 'amll',
          syncType,
          lineCount: parsed.lines.length,
          hasWordTiming: parsed.hasWordTiming,
          matchedTrack: matchTrackName,
          matchedArtist: matchArtistName,
        });

        return {
          syncType,
          lines: parsed.lines,
          plainLyrics: undefined,
          provider: 'amll',
          trackName: matchTrackName,
          artistName: matchArtistName,
        };
      } catch {}
    }
  }

  return null;
}

const MAX_SYNC_DURATION_DIFF = 24; // Max seconds duration gap allowed for timed lyrics.
// YouTube music videos add intro/outro padding vs the album track, so a modest
// gap is expected and the sung timing still lines up (the user can nudge the
// offset). Only a large gap means a genuinely different edit → downgrade.

/**
 * Fetch lyrics from LRCLIB with Exact, Canonical, and Search fallback tiers.
 */
export async function fetchLrclibLyrics(
  trackName: string,
  artistName: string,
  duration?: number,
  mustDowngradeSync?: boolean,
  videoId?: string
): Promise<LyricsData | null> {
  const candidatePairs = extractTrackAndArtistPairs(trackName, artistName);
  if (candidatePairs.length === 0) return null;

  // Segment-derived pairs for heavily-tagged titles. Only used in the
  // exact-duration path below (self-guarding), never in the fuzzy search tier.
  const segmentPairs = duration && duration > 0 ? extractSegmentPairs(trackName, artistName) : [];

  // Tier 1: Exact lookup from LRCLIB if duration is known. Segment pairs are
  // appended here: a wrong segment can only match if a real song by that
  // (title, artist) exists at this EXACT duration, so precision stays high.
  if (duration && duration > 0) {
    const exactPairs = [...candidatePairs.slice(0, 3), ...segmentPairs];
    const exactSeen = new Set<string>();
    for (const pair of exactPairs) {
      if (!pair.track || !pair.artist) continue;
      const dedupeKey = `${pair.track.toLowerCase()}:::${pair.artist.toLowerCase()}`;
      if (exactSeen.has(dedupeKey)) continue;
      exactSeen.add(dedupeKey);
      const getUrls = [
        `https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}&duration=${Math.round(duration)}`,
      ];

      for (const url of getUrls) {
        try {
          const res = await fetchWithTimeout(url, {}, 3500);
          if (res.status === 404) break;
          if (res.ok) {
            const data = await res.json();
            if (data && (data.syncedLyrics || data.plainLyrics)) {
              if (isRelatedMatch(data.trackName || '', data.artistName || '', pair.track, pair.artist)) {
                const durDiff = duration && data.duration ? Math.abs(data.duration - duration) : 0;
                if (data.syncedLyrics && !mustDowngradeSync && durDiff <= MAX_SYNC_DURATION_DIFF) {
                  const parsed = parseLrc(data.syncedLyrics);
                  const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';
                  console.info('[Lyrics:ExactGet]', {
                    videoId,
                    track: trackName,
                    artist: artistName,
                    provider: 'lrclib',
                    syncType,
                    lineCount: parsed.lines.length,
                    hasWordTiming: parsed.hasWordTiming,
                    matchedTrack: data.trackName,
                    matchedArtist: data.artistName,
                    matchedDuration: data.duration,
                    requestedDuration: duration,
                  });
                  return {
                    syncType,
                    lines: parsed.lines,
                    plainLyrics: data.plainLyrics || undefined,
                    provider: 'lrclib',
                    trackName: data.trackName,
                    artistName: data.artistName,
                  };
                }

                const plain = data.plainLyrics || (data.syncedLyrics ? linesToPlainText(parseLrc(data.syncedLyrics).lines) : '');
                if (plain) {
                  return {
                    syncType: 'plain',
                    lines: [],
                    plainLyrics: plain,
                    provider: 'lrclib',
                    trackName: data.trackName,
                    artistName: data.artistName,
                  };
                }
              }
            }
            break;
          }
        } catch {}
      }
    }
  }

  // Tier 2: Canonical GET lookup (track_name + artist_name)
  for (const pair of candidatePairs.slice(0, 3)) {
    if (!pair.track || !pair.artist) continue;
    const canonicalUrls = [
      `https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}`,
    ];

    for (const url of canonicalUrls) {
      try {
        const res = await fetchWithTimeout(url, {}, 3500);
        if (res.status === 404) break;
        if (res.ok) {
          const data = await res.json();
          if (data && (data.syncedLyrics || data.plainLyrics)) {
            if (isRelatedMatch(data.trackName || '', data.artistName || '', pair.track, pair.artist)) {
              const durDiff = duration && data.duration ? Math.abs(data.duration - duration) : 0;
              if (data.syncedLyrics && !mustDowngradeSync && durDiff <= MAX_SYNC_DURATION_DIFF) {
                const parsed = parseLrc(data.syncedLyrics);
                const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';
                console.info('[Lyrics:CanonicalGet]', {
                  videoId,
                  track: trackName,
                  artist: artistName,
                  provider: 'lrclib',
                  syncType,
                  lineCount: parsed.lines.length,
                  hasWordTiming: parsed.hasWordTiming,
                  matchedTrack: data.trackName,
                  matchedArtist: data.artistName,
                  matchedDuration: data.duration,
                  requestedDuration: duration,
                });
                return {
                  syncType,
                  lines: parsed.lines,
                  plainLyrics: data.plainLyrics || undefined,
                  provider: 'lrclib',
                  trackName: data.trackName,
                  artistName: data.artistName,
                };
              }

              const plain = data.plainLyrics || (data.syncedLyrics ? linesToPlainText(parseLrc(data.syncedLyrics).lines) : '');
              if (plain) {
                return {
                  syncType: 'plain',
                  lines: [],
                  plainLyrics: plain,
                  provider: 'lrclib',
                  trackName: data.trackName,
                  artistName: data.artistName,
                };
              }
            }
          }
          break;
        }
      } catch {}
    }
  }

  // Tier 3: Search GET lookup with intelligent query fallbacks
  const searchQueries: string[] = [];
  for (const pair of candidatePairs) {
    if (pair.track && pair.artist) {
      searchQueries.push(`${pair.track} ${pair.artist}`);
    }
    if (pair.track) {
      searchQueries.push(pair.track);
    }
  }

  const uniqueQueries = [...new Set(searchQueries)].slice(0, 4);

  for (const q of uniqueQueries) {
    const searchUrls = [
      `https://lrclib.net/api/search?q=${encodeURIComponent(q)}`,
    ];

    for (const url of searchUrls) {
      try {
        const res = await fetchWithTimeout(url, {}, 3500);
        if (res.ok) {
          const results = await res.json();
          const matches = Array.isArray(results) ? results : results.results;
          if (matches && matches.length > 0) {
            let candidates = matches.filter((m: any) =>
              isRelatedMatch(m.trackName || '', m.artistName || '', trackName, artistName)
            );
            if (candidates.length === 0) break;

            candidates.sort((a: any, b: any) => {
              if (!mustDowngradeSync) {
                const aSynced = Boolean(a.syncedLyrics);
                const bSynced = Boolean(b.syncedLyrics);
                if (aSynced !== bSynced) return aSynced ? -1 : 1;
              }
              if (duration && duration > 0) {
                const aDiff = a.duration ? Math.abs(a.duration - duration) : 999;
                const bDiff = b.duration ? Math.abs(b.duration - duration) : 999;
                return aDiff - bDiff;
              }
              return 0;
            });

            if (duration && duration > 0) {
              const withDur = candidates.filter(
                (m: any) => m.duration && Math.abs(m.duration - duration) <= MAX_SYNC_DURATION_DIFF
              );
              if (withDur.length > 0) {
                candidates = withDur;
              } else {
                // If duration difference is large (e.g. 389s vs 210s), do not serve desynchronized timestamps.
                // Downgrade to plain lyrics so the user sees the words without broken timing.
                const bestCand = candidates[0];
                const plain = bestCand.plainLyrics || (bestCand.syncedLyrics ? linesToPlainText(parseLrc(bestCand.syncedLyrics).lines) : '');
                if (plain) {
                  return {
                    syncType: 'plain',
                    lines: [],
                    plainLyrics: plain,
                    provider: 'lrclib',
                    trackName: bestCand.trackName,
                    artistName: bestCand.artistName,
                  };
                }
              }
            }

            const best = candidates.find((m: any) => m.syncedLyrics) || candidates[0];

            if (best.syncedLyrics && !mustDowngradeSync) {
              const parsed = parseLrc(best.syncedLyrics);
              const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';
              console.info('[Lyrics:Search]', {
                videoId,
                track: trackName,
                artist: artistName,
                provider: 'lrclib',
                syncType,
                lineCount: parsed.lines.length,
                hasWordTiming: parsed.hasWordTiming,
                matchedTrack: best.trackName,
                matchedArtist: best.artistName,
                matchedDuration: best.duration,
                requestedDuration: duration,
              });
              return {
                syncType,
                lines: parsed.lines,
                plainLyrics: best.plainLyrics || undefined,
                provider: 'lrclib',
                trackName: best.trackName,
                artistName: best.artistName,
              };
            }

            const plain = best.plainLyrics || (best.syncedLyrics ? linesToPlainText(parseLrc(best.syncedLyrics).lines) : '');
            if (plain) {
              return {
                syncType: 'plain',
                lines: [],
                plainLyrics: plain,
                provider: 'lrclib',
                trackName: best.trackName,
                artistName: best.artistName,
              };
            }
          }
          break;
        }
      } catch {}
    }
  }

  return null;
}

/**
 * Fetch lyrics with multi-tier fallback and honest sync-type labeling.
 *
 * Provider chain:
 *   1. AMLL (word-level richsync TTML)
 *   2. LRCLIB (exact-duration -> canonical -> search line-sync or plain)
 *   3. YouTube Captions (line-sync, low quality fallback)
 */
export async function fetchLyrics(
  trackName: string,
  artistName: string,
  duration?: number,
  videoId?: string
): Promise<LyricsData | null> {
  const normKey = `${cleanTitle(trackName).toLowerCase()}:::${cleanArtistName(artistName).toLowerCase()}:::${duration ? Math.round(duration) : 0}`;
  const cached = getCachedLyrics(normKey);
  if (cached !== undefined) return cached;

  // Fast path: compilations / jukeboxes / hour-long mixes never have matching
  // lyrics. Return immediately so the UI shows an honest "No Lyrics Found"
  // instead of hanging on lookups that are guaranteed to miss.
  if (isProbablyNotASong(trackName, duration)) {
    console.info('[Lyrics]', {
      videoId,
      track: trackName,
      artist: artistName,
      provider: 'none',
      syncType: 'none',
      note: 'Skipped — detected compilation / mix, not a single song',
    });
    setCachedLyrics(normKey, null);
    return null;
  }

  const rendition = detectRendition(trackName);
  const mustDowngradeSync = rendition.tempoAltered || rendition.alternateVersion;

  // 0. Resolve canonical metadata from iTunes to normalize YouTube noise
  let canonical: CanonicalMetadata | null = null;
  try {
    canonical = await resolveCanonicalMetadata(trackName, artistName);
    if (canonical) {
      console.info('[Lyrics:CanonicalMeta]', {
        videoId,
        originalTrack: trackName,
        originalArtist: artistName,
        canonicalTrack: canonical.trackName,
        canonicalArtist: canonical.artistName,
        canonicalDuration: canonical.duration,
      });
    }
  } catch {}

  // 1. Check AMLL TTML Database for authentic word-level richsync
  // Tier 1a: With Canonical Metadata (exact studio track tags)
  if (canonical) {
    try {
      const amllResult = await fetchAmllLyrics(
        canonical.trackName,
        canonical.artistName,
        canonical.duration || duration,
        mustDowngradeSync,
        videoId
      );
      if (amllResult && (amllResult.lines.length > 0 || amllResult.plainLyrics)) {
        setCachedLyrics(normKey, amllResult);
        return amllResult;
      }
    } catch {}
  }

  // Tier 1b: With Raw Input Metadata
  try {
    const amllResult = await fetchAmllLyrics(
      trackName,
      artistName,
      duration,
      mustDowngradeSync,
      videoId
    );
    if (amllResult && (amllResult.lines.length > 0 || amllResult.plainLyrics)) {
      setCachedLyrics(normKey, amllResult);
      return amllResult;
    }
  } catch {
    // Graceful fallback to LRCLIB
  }

  // 2. Multi-tier LRCLIB lookup
  // Tier 2a: With Canonical Metadata
  if (canonical) {
    try {
      const lrclibResult = await fetchLrclibLyrics(
        canonical.trackName,
        canonical.artistName,
        canonical.duration || duration,
        mustDowngradeSync,
        videoId
      );
      if (lrclibResult && (lrclibResult.lines.length > 0 || lrclibResult.plainLyrics)) {
        setCachedLyrics(normKey, lrclibResult);
        return lrclibResult;
      }
    } catch {}
  }

  // Tier 2b: With Raw Input Metadata
  try {
    const lrclibResult = await fetchLrclibLyrics(
      trackName,
      artistName,
      duration,
      mustDowngradeSync,
      videoId
    );
    if (lrclibResult && (lrclibResult.lines.length > 0 || lrclibResult.plainLyrics)) {
      setCachedLyrics(normKey, lrclibResult);
      return lrclibResult;
    }
  } catch {
    // Graceful fallback to YouTube captions
  }

  // 3. YouTube Captions fallback (last resort)
  if (videoId) {
    const ytLines = await fetchYouTubeCaptions(videoId);
    if (ytLines && ytLines.length > 0) {
      console.info('[Lyrics:YouTubeCaptions]', {
        videoId,
        track: trackName,
        artist: artistName,
        provider: 'youtube',
        syncType: 'line-sync',
        lineCount: ytLines.length,
        hasWordTiming: false,
        note: 'YouTube auto-captions — not music lyrics',
      });

      const ytResult: LyricsData = {
        syncType: 'line-sync',
        lines: ytLines,
        provider: 'youtube',
        trackName,
        artistName,
      };
      setCachedLyrics(normKey, ytResult);
      return ytResult;
    }
  }

  console.info('[Lyrics]', {
    videoId,
    track: trackName,
    artist: artistName,
    provider: 'none',
    syncType: 'none',
    note: 'No lyrics found from any provider',
  });

  setCachedLyrics(normKey, null);
  return null;
}
