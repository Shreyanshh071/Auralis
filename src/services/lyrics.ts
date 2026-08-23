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

  s = s.replace(/\s{2,}/g, ' ').trim();
  // Drop dangling separator at either end
  s = s.replace(/\s*[-|–—:]\s*$/, '').replace(/^\s*[-|–—:]\s*/, '').trim();
  return s;
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

export interface TrackArtistPair {
  track: string;
  artist: string;
}

/**
 * Extract plausible (track, artist) candidate pairs from messy YouTube / streaming metadata.
 * Handles:
 * - Direct: "Loving Machine", "TV Girl"
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

  // 2. Check for separators in rawTrack ("Artist - Title" or "Title - Artist" or "Artist : Title")
  const sepMatch = rawTrack.match(/^(.*?)\s*[-–—:|~•]\s*(.*)$/);
  if (sepMatch) {
    const p1 = sepMatch[1].trim();
    const p2 = sepMatch[2].trim();
    if (p1 && p2) {
      addPair(p2, p1); // Artist - Title
      addPair(p1, p2); // Title - Artist
    }
  }

  // 3. Check for pipe enclosure: "Title |Artist|"
  const pipeMatch = rawTrack.match(/^(.*?)\s*\|([^|]+)\|\s*$/);
  if (pipeMatch) {
    addPair(pipeMatch[1], pipeMatch[2]);
  }

  // 4. Inverted candidate (when source swapped title & artist)
  if (rawArtist) {
    addPair(rawArtist, rawTrack);
  }

  return pairs;
}

/**
 * Whether a lyrics candidate plausibly IS the requested song, so unrelated
 * matches are rejected instead of shown. Handles title variants, feat credits,
 * and artist delimiters (&, /, +, comma).
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

  let titleSim = tokenSimilarity(cleanCandTrack, cleanWantTrack);
  let artistSim = tokenSimilarity(cleanCandArtist, cleanWantArtist);

  // 1. Direct high title match (>= 0.85) or good title + reasonable artist
  if (titleSim >= 0.85) return true;
  if (titleSim >= 0.7 && (artistSim >= 0.3 || !cleanWantArtist || cleanWantArtist === 'Various Artists')) {
    return true;
  }
  if (titleSim >= 0.5 && artistSim >= 0.5) return true;

  // 2. Inverted candidate comparison (when source metadata swapped title & artist)
  const invertedTitleSim = tokenSimilarity(cleanCandTrack, cleanWantArtist);
  const invertedArtistSim = tokenSimilarity(cleanCandArtist, cleanWantTrack);
  if (invertedTitleSim >= 0.8 && invertedArtistSim >= 0.4) {
    return true;
  }

  // 3. Embedded Artist - Title in wantTrack:
  // e.g. wantTrack: "Tv Girl -Loving Machine" and candidate is track="Loving Machine", artist="TV Girl"
  const normWant = normalizeForMatch(wantTrack);
  const normCandTrack = normalizeForMatch(candidateTrack);
  const normCandArtist = normalizeForMatch(candidateArtist);

  if (normCandTrack && normCandArtist) {
    const hasBoth = normWant.includes(normCandTrack) && normWant.includes(normCandArtist);
    if (hasBoth) return true;
  }

  // 4. Segment checks if wantTrack contains separators
  if (wantTrack.includes(' - ') || wantTrack.includes(' | ') || wantTrack.includes('-')) {
    const segments = wantTrack.split(/\s*[-–—:|]\s*/);
    for (const seg of segments) {
      const segSim = tokenSimilarity(cleanTitle(seg), cleanCandTrack);
      if (segSim > titleSim) titleSim = segSim;
      const segArtistSim = tokenSimilarity(cleanTitle(seg), cleanCandArtist);
      if (segArtistSim > artistSim) artistSim = segArtistSim;
    }
  }

  // 5. Check candidate track if it contains embedded artist
  if (candidateTrack.includes(' - ') || candidateTrack.includes(' | ')) {
    const segments = candidateTrack.split(/\s*[-–—:|]\s*/);
    for (const seg of segments) {
      const segSim = tokenSimilarity(cleanTitle(seg), cleanWantTrack);
      if (segSim > titleSim) titleSim = segSim;
    }
  }

  // Raw title similarity fallback
  const rawTitleSim = tokenSimilarity(candidateTrack, wantTrack);
  if (rawTitleSim > titleSim) titleSim = rawTitleSim;
  const rawArtistSim = tokenSimilarity(candidateArtist, wantArtist);
  if (rawArtistSim > artistSim) artistSim = rawArtistSim;

  if (titleSim >= 0.8) return true;
  return titleSim >= 0.5 && artistSim >= 0.34;
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
      3500
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

    const searchUrls = [
      `https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`,
      `https://corsproxy.io/?url=${encodeURIComponent(`https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`)}`,
      `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`)}`,
    ];

    for (const url of searchUrls) {
      try {
        const res = await fetchWithTimeout(
          url,
          { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } },
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
              { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } },
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
          if (durDiff > 25) {
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

  // Tier 1: Exact lookup from LRCLIB if duration is known
  if (duration && duration > 0) {
    for (const pair of candidatePairs.slice(0, 2)) {
      if (!pair.track || !pair.artist) continue;
      const getUrls = [
        `https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}&duration=${Math.round(duration)}`,
        `https://corsproxy.io/?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}&duration=${Math.round(duration)}`)}`,
        `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}&duration=${Math.round(duration)}`)}`,
      ];

      for (const url of getUrls) {
        try {
          const res = await fetchWithTimeout(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } }, 3500);
          if (res.status === 404) break;
          if (res.ok) {
            const data = await res.json();
            if (data && (data.syncedLyrics || data.plainLyrics)) {
              if (isRelatedMatch(data.trackName || '', data.artistName || '', pair.track, pair.artist)) {
                if (data.syncedLyrics && !mustDowngradeSync) {
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
  for (const pair of candidatePairs.slice(0, 2)) {
    if (!pair.track || !pair.artist) continue;
    const canonicalUrls = [
      `https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}`,
      `https://corsproxy.io/?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}`)}`,
      `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(pair.track)}&artist_name=${encodeURIComponent(pair.artist)}`)}`,
    ];

    for (const url of canonicalUrls) {
      try {
        const res = await fetchWithTimeout(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } }, 3500);
        if (res.status === 404) break;
        if (res.ok) {
          const data = await res.json();
          if (data && (data.syncedLyrics || data.plainLyrics)) {
            if (isRelatedMatch(data.trackName || '', data.artistName || '', pair.track, pair.artist)) {
              const durDiff = duration && data.duration ? Math.abs(data.duration - duration) : 0;
              if (durDiff <= 35) {
                if (data.syncedLyrics && !mustDowngradeSync) {
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

  const uniqueQueries = [...new Set(searchQueries)].slice(0, 3);

  for (const q of uniqueQueries) {
    const searchUrls = [
      `https://lrclib.net/api/search?q=${encodeURIComponent(q)}`,
      `https://corsproxy.io/?url=${encodeURIComponent(`https://lrclib.net/api/search?q=${encodeURIComponent(q)}`)}`,
      `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://lrclib.net/api/search?q=${encodeURIComponent(q)}`)}`,
    ];

    for (const url of searchUrls) {
      try {
        const res = await fetchWithTimeout(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } }, 3500);
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
                (m: any) => m.duration && Math.abs(m.duration - duration) <= 25
              );
              if (withDur.length > 0) candidates = withDur;
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

  const rendition = detectRendition(trackName);
  const mustDowngradeSync = rendition.tempoAltered || rendition.alternateVersion;

  // 1. Check AMLL TTML Database for authentic word-level richsync
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
