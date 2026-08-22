import type { LyricsData, LyricLine } from '../types/music';

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
    .replace(/[̀-ͯ]/g, '') // combining diacritical marks
    .replace(/&/g, ' and ')
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
 * matches are rejected instead of shown. A strong title match alone is enough
 * (artist tagging varies: "The Weeknd" vs "Weeknd"); otherwise both title and
 * artist must be reasonably close.
 */
export function isRelatedMatch(
  candidateTrack: string,
  candidateArtist: string,
  wantTrack: string,
  wantArtist: string
): boolean {
  const titleSim = tokenSimilarity(candidateTrack, wantTrack);
  const artistSim = tokenSimilarity(candidateArtist, wantArtist);
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

/**
 * Fetch lyrics with multi-tier fallback and honest sync-type labeling.
 *
 * Provider chain:
 *   1. LRCLIB (line-sync or plain)
 *   2. YouTube Captions (line-sync, low quality)
 *
 * Every result is labeled:
 *   - 'richsync'  — ONLY if the LRC data contains genuine <mm:ss.ms>word tags
 *   - 'line-sync' — standard [mm:ss.ms] line timestamps
 *   - 'plain'     — unsynced text only
 *
 * Duration validation: LRCLIB results with duration mismatch > 15s are rejected.
 */
export async function fetchLyrics(
  trackName: string,
  artistName: string,
  duration?: number,
  videoId?: string
): Promise<LyricsData | null> {
  const cleanedTrack = cleanTitle(trackName);
  const cleanedArtist = cleanTitle(artistName);

  // A tempo-altered (sped up / slowed / nightcore) or alternate rendition (live,
  // cover, remix, acoustic...) will NOT line up with the base-recording synced
  // timings LRCLIB indexes, so any synced result for those must be presented as
  // plain text rather than as (wrong) synchronized lyrics.
  const rendition = detectRendition(trackName);
  const mustDowngradeSync = rendition.tempoAltered || rendition.alternateVersion;

  // 1. Fetch from LRCLIB with CORS Proxy Fallback
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

          // Prefer the closest duration among the related candidates. Skipped when
          // no related candidate is within tolerance (e.g. a sped-up rip is shorter
          // than its base track) so we still surface the base lyrics as plain text.
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
      }
    } catch {
      // Continue to next fallback
    }
  }

  // 2. Fetch from YouTube Captions (last resort, low quality)
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

      return {
        syncType: 'line-sync',
        lines: ytLines,
        provider: 'youtube',
        trackName,
        artistName,
      };
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

  return null;
}
