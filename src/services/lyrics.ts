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
 * ("Jay-Z", "twenty-one pilots", "Sk8er Boi") — the previous implementation
 * discarded everything after the first "-" and mangled those.
 */
export function cleanTitle(text: string): string {
  if (!text) return '';
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

  // A trailing production/version qualifier after a "-" or "|" separator, anchored
  // to the end so interior hyphens survive. Run a few times for stacked tags
  // ("Song - Remastered - Official Video").
  const trailing =
    /\s*[-|]\s*(?:official\s*(?:music\s*)?video|official\s*audio|official\s*visuali[sz]er|(?:official\s*)?lyrics?(?:\s*video)?|audio|video|visuali[sz]er|m\/?v|hd|hq|4k|remaster(?:ed)?(?:\s*\d{2,4})?|radio\s*edit|album\s*version|single\s*version|extended(?:\s*(?:mix|version))?|explicit|clean\s*version)\s*$/i;
  for (let i = 0; i < 3 && trailing.test(s); i++) {
    s = s.replace(trailing, '');
  }

  s = s.replace(/\s{2,}/g, ' ').trim();
  // Drop a "-" or "|" left dangling at either end once the content beside it was
  // removed (e.g. "Heat Waves - slowed + reverb" -> "Heat Waves -" -> "Heat Waves").
  s = s.replace(/\s*[-|]\s*$/, '').replace(/^\s*[-|]\s*/, '').trim();
  return s;
}

/** Normalise text for fuzzy comparison: lowercase, strip accents and punctuation. */
export function normalizeForMatch(text: string): string {
  if (!text) return '';
  return text
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '') // combining diacritical marks
    .replace(/&/g, ' and ')
    .replace(/[+,/\\|]/g, ' ')
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
  const cleanWantTrack = cleanTitle(wantTrack);
  const cleanWantArtist = cleanTitle(wantArtist);
  const cleanCandTrack = cleanTitle(candidateTrack);
  const cleanCandArtist = cleanTitle(candidateArtist);

  let titleSim = tokenSimilarity(cleanCandTrack, cleanWantTrack);

  // If candidate track has "Artist - Title" format, check individual segments
  if (cleanCandTrack.includes(' - ') || cleanCandTrack.includes(' | ')) {
    const segments = cleanCandTrack.split(/\s*[-|]\s*/);
    for (const seg of segments) {
      const segSim = tokenSimilarity(cleanTitle(seg), cleanWantTrack);
      if (segSim > titleSim) titleSim = segSim;
    }
  }

  // Also check raw title similarity in case cleanTitle removed a distinctive word
  const rawTitleSim = tokenSimilarity(candidateTrack, wantTrack);
  if (rawTitleSim > titleSim) titleSim = rawTitleSim;

  let artistSim = tokenSimilarity(cleanCandArtist, cleanWantArtist);
  const rawArtistSim = tokenSimilarity(candidateArtist, wantArtist);
  if (rawArtistSim > artistSim) artistSim = rawArtistSim;

  // If candidate track has artist info embedded
  if (candidateTrack.includes(' - ') || candidateTrack.includes(' | ')) {
    const segments = candidateTrack.split(/\s*[-|]\s*/);
    for (const seg of segments) {
      const segSim = tokenSimilarity(seg, wantArtist);
      if (segSim > artistSim) artistSim = segSim;
    }
  }

  if (titleSim >= 0.8) return true;
  return titleSim >= 0.5 && artistSim >= 0.34;
}

export interface RenditionInfo {
  /**
   * A tempo/pitch-altered rip (sped up, slowed, nightcore, 8D, bass boosted).
   * Synced timings from the base recording will not line up with this audio, so
   * synced lyrics must be downgraded to plain rather than shown as synchronized.
   */
  tempoAltered: boolean;
  /**
   * A different rendition (live, acoustic, cover, remix, karaoke, instrumental).
   * The recording — and therefore any line/word timing — differs from the studio
   * version LRCLIB indexes, so synced lyrics are not trustworthy.
   */
  alternateVersion: boolean;
  label?: string;
}

/**
 * Inspect the RAW (pre-clean) title for markers that mean the base-song synced
 * lyrics would be mis-aligned. Runs on the original title, not the cleaned one,
 * because cleanTitle strips these markers for query building.
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

/** Flatten parsed synced lines into plain text, for the honest plain fallback. */
function linesToPlainText(lines: LyricLine[]): string {
  return lines
    .map((l) => l.text)
    .filter((t) => t && t !== '♪')
    .join('\n')
    .trim();
}


/**
 * Result of parsing an LRC string.
 * `hasWordTiming` is true ONLY if the LRC contained genuine enhanced word tags
 * like <00:12.80>word — NOT estimated from line duration.
 */
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

      // Check if enhanced word-level LRC timestamps exist in text
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

      const isInstrumental = /^\(?(instrumental|solo|outro|intro|guitar|synth|interlude)\)?$/i.test(text) ||
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
 * Fetch lyrics from YouTube video timed transcript (Captions API).
 * These are auto-generated speech-to-text caption blocks, NOT word-level music lyrics.
 * Always returns syncType: 'line-sync'.
 */
async function fetchYouTubeCaptions(videoId: string): Promise<LyricLine[] | null> {
  try {
    const res = await fetch(`https://api.allorigins.win/raw?url=${encodeURIComponent(`https://www.youtube.com/api/timedtext?lang=en&v=${videoId}&fmt=json3`)}`);
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
const CACHE_TTL_MS = 1000 * 60 * 30; // 30 minutes
const MAX_CACHE_SIZE = 150;

function getCachedLyrics(key: string): LyricsData | null | undefined {
  const entry = lyricsCache.get(key);
  if (!entry) return undefined;
  if (Date.now() - entry.timestamp > CACHE_TTL_MS) {
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
 * Fetch word/syllable-level synchronized lyrics from AMLL TTML Database (CC0 Public Domain).
 *
 * Checks search endpoint -> validates candidate (title/artist/duration/rendition) ->
 * fetches raw TTML -> parses genuine word timestamps.
 * Returns null if no authentic/confident match is found, triggering fallback to LRCLIB.
 */
export async function fetchAmllLyrics(
  trackName: string,
  artistName: string,
  duration?: number,
  mustDowngradeSync?: boolean,
  videoId?: string
): Promise<LyricsData | null> {
  const cleanedTrack = cleanTitle(trackName);
  const cleanedArtist = cleanTitle(artistName);
  if (!cleanedTrack) return null;

  const searchQuery = `${cleanedTrack} ${cleanedArtist}`.trim();
  const searchUrls = [
    `https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`,
    `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`)}`,
    `https://corsproxy.io/?url=${encodeURIComponent(`https://api.amll.dev/v1/lyrics/search?q=${encodeURIComponent(searchQuery)}`)}`,
  ];

  for (const url of searchUrls) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } });
      if (!res.ok) continue;

      const body = await res.json();
      const items = body?.data?.items || (Array.isArray(body) ? body : []);
      if (!items || items.length === 0) continue;

      // Filter candidates using conservative matching
      const candidates = items.filter((item: any) => {
        const musicNames: string[] = item.musicNames || (item.musicName ? [item.musicName] : []);
        const artistNames: string[] = item.artistNames || (item.artistName ? [item.artistName] : []);

        const titleMatched =
          musicNames.some(m => isRelatedMatch(m, artistNames[0] || '', cleanedTrack, cleanedArtist)) ||
          isRelatedMatch(musicNames[0] || '', artistNames[0] || '', cleanedTrack, cleanedArtist);

        return titleMatched;
      });

      if (candidates.length === 0) continue;

      // Select candidate with the best title/artist match
      const best = candidates[0];
      const matchTrackName = (best.musicNames && best.musicNames[0]) || best.musicName || cleanedTrack;
      const matchArtistName = (best.artistNames && best.artistNames[0]) || best.artistName || cleanedArtist;

      // Fetch the full TTML: first try direct API endpoint, then jsDelivr CDN
      let xmlText: string | null = null;
      if (best.id) {
        try {
          const getRes = await fetch(`https://api.amll.dev/v1/lyrics/get?id=${best.id}`, {
            headers: { 'User-Agent': 'Auralis-Music-Player/2.0' },
          });
          if (getRes.ok) {
            const getData = await getRes.json();
            xmlText = getData?.data?.lyrics || null;
          }
        } catch {
          // Fall back to jsDelivr
        }
      }

      if (!xmlText && best.filename) {
        try {
          const cdnRes = await fetch(
            `https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/raw-lyrics/${best.filename}`
          );
          if (cdnRes.ok) {
            xmlText = await cdnRes.text();
          }
        } catch {
          // CDN failed
        }
      }

      if (!xmlText) continue;

      const parsed = parseTtml(xmlText);
      if (!parsed.lines || parsed.lines.length === 0) continue;

      // Validate duration if available from TTML metadata
      if (duration && duration > 0 && parsed.duration && parsed.duration > 0) {
        const durDiff = Math.abs(parsed.duration - duration);
        // If duration difference is too large (>25s), reject to avoid wrong recording/intro mismatches
        if (durDiff > 25) {
          continue;
        }
      }

      // If rendition is altered (live, acoustic, remix, sped-up), downgrade to plain text
      if (mustDowngradeSync) {
        const plainText = parsed.lines.map(l => l.text).join('\n');
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
    } catch {
      // Continue to next fallback URL or LRCLIB
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
 *
 * Every result is labeled:
 *   - 'richsync'  — genuine syllable/word timestamps from TTML or <mm:ss.ms> LRC
 *   - 'line-sync' — standard [mm:ss.ms] line timestamps
 *   - 'plain'     — unsynced text only
 */
export async function fetchLyrics(
  trackName: string,
  artistName: string,
  duration?: number,
  videoId?: string
): Promise<LyricsData | null> {
  const cacheKey = `${trackName}:${artistName}:${duration || 0}`;
  const cached = getCachedLyrics(cacheKey);
  if (cached !== undefined) return cached;

  const cleanedTrack = cleanTitle(trackName);
  const cleanedArtist = cleanTitle(artistName);

  // A tempo-altered (sped up / slowed / nightcore) or alternate rendition (live,
  // cover, remix, acoustic...) will NOT line up with the base-recording synced
  // timings, so any synced result for those must be presented as
  // plain text rather than as (wrong) synchronized lyrics.
  const rendition = detectRendition(trackName);
  const mustDowngradeSync = rendition.tempoAltered || rendition.alternateVersion;

  // 1. Check AMLL TTML Database for authentic word-level richsync
  if (cleanedTrack) {
    try {
      const amllResult = await fetchAmllLyrics(
        trackName,
        artistName,
        duration,
        mustDowngradeSync,
        videoId
      );
      if (amllResult && (amllResult.lines.length > 0 || amllResult.plainLyrics)) {
        setCachedLyrics(cacheKey, amllResult);
        return amllResult;
      }
    } catch {
      // Graceful fallback to LRCLIB
    }
  }

  // 2. Exact lookup from LRCLIB if track duration is known
  if (duration && duration > 0 && cleanedTrack && cleanedArtist) {
    const getUrls = [
      `https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanedTrack)}&artist_name=${encodeURIComponent(cleanedArtist)}&duration=${Math.round(duration)}`,
      `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanedTrack)}&artist_name=${encodeURIComponent(cleanedArtist)}&duration=${Math.round(duration)}`)}`,
      `https://corsproxy.io/?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanedTrack)}&artist_name=${encodeURIComponent(cleanedArtist)}&duration=${Math.round(duration)}`)}`,
    ];

    for (const url of getUrls) {
      try {
        const res = await fetch(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } });
        if (res.ok) {
          const data = await res.json();
          if (data && (data.syncedLyrics || data.plainLyrics)) {
            if (isRelatedMatch(data.trackName || '', data.artistName || '', cleanedTrack, cleanedArtist)) {
              if (data.syncedLyrics && !mustDowngradeSync) {
                const parsed = parseLrc(data.syncedLyrics);
                const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';

                const result: LyricsData = {
                  syncType,
                  lines: parsed.lines,
                  plainLyrics: data.plainLyrics || undefined,
                  provider: 'lrclib',
                  trackName: data.trackName,
                  artistName: data.artistName,
                };

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

                setCachedLyrics(cacheKey, result);
                return result;
              }

              const plain =
                data.plainLyrics ||
                (data.syncedLyrics ? linesToPlainText(parseLrc(data.syncedLyrics).lines) : '');
              if (plain) {
                const plainResult: LyricsData = {
                  syncType: 'plain',
                  lines: [],
                  plainLyrics: plain,
                  provider: 'lrclib',
                  trackName: data.trackName,
                  artistName: data.artistName,
                };
                setCachedLyrics(cacheKey, plainResult);
                return plainResult;
              }
            }
          }
        }
      } catch {
        // Fall back to next proxy or broad search
      }
    }
    // 2. Canonical get lookup (track_name + artist_name) if exact-duration 404s
    const canonicalUrls = [
      `https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanedTrack)}&artist_name=${encodeURIComponent(cleanedArtist)}`,
      `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanedTrack)}&artist_name=${encodeURIComponent(cleanedArtist)}`)}`,
      `https://corsproxy.io/?url=${encodeURIComponent(`https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanedTrack)}&artist_name=${encodeURIComponent(cleanedArtist)}`)}`,
    ];

    for (const url of canonicalUrls) {
      try {
        const res = await fetch(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } });
        if (res.ok) {
          const data = await res.json();
          if (data && (data.syncedLyrics || data.plainLyrics)) {
            if (isRelatedMatch(data.trackName || '', data.artistName || '', cleanedTrack, cleanedArtist)) {
              // Check that canonical duration is reasonably close (within 30s) if duration is known
              const durDiff = duration && data.duration ? Math.abs(data.duration - duration) : 0;
              if (durDiff <= 30) {
                if (data.syncedLyrics && !mustDowngradeSync) {
                  const parsed = parseLrc(data.syncedLyrics);
                  const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';

                  const result: LyricsData = {
                    syncType,
                    lines: parsed.lines,
                    plainLyrics: data.plainLyrics || undefined,
                    provider: 'lrclib',
                    trackName: data.trackName,
                    artistName: data.artistName,
                  };

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

                  setCachedLyrics(cacheKey, result);
                  return result;
                }

                const plain =
                  data.plainLyrics ||
                  (data.syncedLyrics ? linesToPlainText(parseLrc(data.syncedLyrics).lines) : '');
                if (plain) {
                  const plainResult: LyricsData = {
                    syncType: 'plain',
                    lines: [],
                    plainLyrics: plain,
                    provider: 'lrclib',
                    trackName: data.trackName,
                    artistName: data.artistName,
                  };
                  setCachedLyrics(cacheKey, plainResult);
                  return plainResult;
                }
              }
            }
          }
        }
      } catch {
        // Fall back to next proxy or search
      }
    }
  }

  // 3. Fetch from LRCLIB Search with CORS Proxy Fallback
  const searchUrls = [
    `https://lrclib.net/api/search?q=${encodeURIComponent(`${cleanedTrack} ${cleanedArtist}`)}`,
    `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://lrclib.net/api/search?q=${encodeURIComponent(`${cleanedTrack} ${cleanedArtist}`)}`)}`,
    `https://corsproxy.io/?url=${encodeURIComponent(`https://lrclib.net/api/search?q=${encodeURIComponent(`${cleanedTrack} ${cleanedArtist}`)}`)}`,
  ];

  for (const url of searchUrls) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': 'Auralis-Music-Player/2.0' } });
      if (res.ok) {
        const results = await res.json();
        const matches = Array.isArray(results) ? results : results.results;
        if (matches && matches.length > 0) {
          // Reject unrelated songs: keep only candidates whose title (and, when it
          // disambiguates, artist) actually matches what we asked for. Without this
          // an LRCLIB search could return a different song and we'd show its lyrics.
          let candidates = matches.filter((m: any) =>
            isRelatedMatch(m.trackName || '', m.artistName || '', cleanedTrack, cleanedArtist)
          );
          if (candidates.length === 0) continue; // nothing here matches — try next proxy

          // Sort candidates:
          // 1. If synced lyrics desired, prefer candidates that have syncedLyrics
          // 2. Prefer candidates closest to the actual requested duration
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

          // Prefer the closest duration among the related candidates within 15s tolerance.
          if (duration && duration > 0) {
            const withDur = candidates.filter(
              (m: any) => m.duration && Math.abs(m.duration - duration) <= 15
            );
            if (withDur.length > 0) candidates = withDur;
          }

          const best = candidates.find((m: any) => m.syncedLyrics) || candidates[0];

          if (best.syncedLyrics && !mustDowngradeSync) {
            const parsed = parseLrc(best.syncedLyrics);
            const syncType = parsed.hasWordTiming ? 'richsync' : 'line-sync';

            const result: LyricsData = {
              syncType,
              lines: parsed.lines,
              plainLyrics: best.plainLyrics || undefined,
              provider: 'lrclib',
              trackName: best.trackName,
              artistName: best.artistName,
            };

            console.info('[Lyrics]', {
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

            setCachedLyrics(cacheKey, result);
            return result;
          }

          // Downgrade / plain path: we have the words but not trustworthy timing
          // for THIS rendition, or the match only has plain text. Flatten synced
          // lines to plain rather than invent timing we can't honor.
          const plain =
            best.plainLyrics ||
            (best.syncedLyrics ? linesToPlainText(parseLrc(best.syncedLyrics).lines) : '');
          if (plain) {
            console.info('[Lyrics]', {
              videoId,
              track: trackName,
              artist: artistName,
              provider: 'lrclib',
              syncType: 'plain',
              lineCount: 0,
              hasWordTiming: false,
              matchedTrack: best.trackName,
              matchedArtist: best.artistName,
              downgradedFrom: mustDowngradeSync && best.syncedLyrics ? rendition.label : undefined,
            });

            const plainResult: LyricsData = {
              syncType: 'plain',
              lines: [],
              plainLyrics: plain,
              provider: 'lrclib',
              trackName: best.trackName,
              artistName: best.artistName,
            };
            setCachedLyrics(cacheKey, plainResult);
            return plainResult;
          }
        }
      }
    } catch {
      // Continue to next fallback
    }
  }

  // 4. Fetch from YouTube Captions (last resort, low quality)
  if (videoId) {
    const ytLines = await fetchYouTubeCaptions(videoId);
    if (ytLines && ytLines.length > 0) {
      console.info('[Lyrics]', {
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
      setCachedLyrics(cacheKey, ytResult);
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

  setCachedLyrics(cacheKey, null);
  return null;
}
