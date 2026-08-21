import type { LyricsData, LyricLine } from '../types/music';

/**
 * Clean track / artist name for better matching
 */
export function cleanTitle(text: string): string {
  if (!text) return '';
  return text
    .replace(/\(.*?\)/g, '')
    .replace(/\[.*?\]/g, '')
    .replace(/ft\..*$/i, '')
    .replace(/feat\..*$/i, '')
    .replace(/official\s+video/gi, '')
    .replace(/official\s+audio/gi, '')
    .replace(/lyrics?/gi, '')
    .replace(/sped\s+up/gi, '')
    .replace(/slowed/gi, '')
    .replace(/reverb/gi, '')
    .replace(/4k|hd|hq/gi, '')
    .replace(/\|.*$/g, '')
    .replace(/-.*$/g, '')
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
          // Filter: reject results with duration mismatch > 15 seconds
          let candidates = matches;
          if (duration && duration > 0) {
            const filtered = candidates.filter((m: any) => {
              if (!m.duration) return true; // no duration to compare
              return Math.abs(m.duration - duration) <= 15;
            });
            if (filtered.length > 0) candidates = filtered;
          }

          const best = candidates.find((m: any) => m.syncedLyrics) || candidates[0];

          if (best.syncedLyrics) {
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
          } else if (best.plainLyrics) {
            console.info('[Lyrics]', {
              videoId,
              track: trackName,
              artist: artistName,
              provider: 'lrclib',
              syncType: 'plain',
              lineCount: 0,
              hasWordTiming: false,
            });

            return {
              syncType: 'plain',
              lines: [],
              plainLyrics: best.plainLyrics,
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
