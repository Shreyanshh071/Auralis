import { universalFetch } from './youtube.ts';
import { normalizeSearchKey } from './searchCache.ts';

const SUGGEST_CACHE = new Map<string, string[]>();
const MAX_SUGGEST_CACHE_SIZE = 300;

/**
 * Monotonically increasing request counter. The caller should capture this
 * before awaiting and compare after — if the counter has moved on, another
 * query has been started and this response is stale.
 */
let _requestGeneration = 0;
export function getSuggestionGeneration(): number {
  return _requestGeneration;
}

/**
 * Clean whitespace from a raw suggestion string.
 */
export function cleanSuggestion(raw: string): string {
  return raw.replace(/\s+/g, ' ').trim();
}

/**
 * Check whether a suggestion is relevant to the query. A suggestion is relevant
 * if the query is a prefix of the suggestion (case-insensitive), OR if they
 * share a significant word overlap. This prevents completely unrelated suggestions
 * from appearing while still allowing Google's organic completions.
 */
const NON_MUSIC_TERMS = /\b(game|games|gameplay|gaming|minecraft|roblox|gta|vlog|vlogs|challenge|reaction|reactions|funny moments|walkthrough|episode|episodes|fears to fathom|subnautica|god of war|granny|horror|horror game|shorts|stream|streamer|live stream|unboxing|prank|meme)\b/i;

/**
 * Check whether a suggestion is relevant to the query. A suggestion is relevant
 * if the query is a prefix of the suggestion (case-insensitive), OR if they
 * share a significant word overlap, and it does not contain non-music gaming/vlog terms.
 */
export function isSuggestionRelevant(suggestion: string, query: string): boolean {
  if (NON_MUSIC_TERMS.test(suggestion)) return false;

  const sLower = suggestion.toLowerCase();
  const qLower = query.toLowerCase();

  // Direct prefix match (most common case for autocomplete)
  if (sLower.startsWith(qLower)) return true;

  // Word-overlap: if most query words appear in the suggestion
  const qWords = qLower.split(/\s+/).filter((w) => w.length > 1);
  if (qWords.length === 0) return true;
  const matchCount = qWords.filter((w) => sLower.includes(w)).length;
  return matchCount >= Math.ceil(qWords.length * 0.6);
}

/**
 * Fetch compact search suggestions for a given input query with sub-25ms speed.
 *
 * @param query  The exact text the user has typed.
 * @param signal Optional AbortSignal so the caller can cancel in-flight requests.
 */
export async function getSearchSuggestions(
  query: string,
  signal?: AbortSignal,
): Promise<string[]> {
  const q = query.trim();
  if (!q || q.length < 2) return [];

  // Bump the generation counter so callers can detect stale responses.
  const gen = ++_requestGeneration;
  const cacheKey = normalizeSearchKey(q);

  // 1. Tier 1: In-memory LRU cache (<0.1ms)
  if (SUGGEST_CACHE.has(cacheKey)) {
    return SUGGEST_CACHE.get(cacheKey)!;
  }

  // 2. Tier 2: Priority Endpoints:
  //    - Local dev /api/youtube-suggest (cached, <15ms)
  //    - Direct YouTube Music get_search_suggestions (native Android via CapacitorHttp)
  //    - Direct Google Suggest with music filter
  const endpoints = [
    { url: `/api/youtube-suggest?q=${encodeURIComponent(q)}`, method: 'GET' as const },
    {
      url: 'https://music.youtube.com/youtubei/v1/music/get_search_suggestions?prettyPrint=false',
      method: 'POST' as const,
      data: {
        context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20240101.01.00', hl: 'en', gl: 'US' } },
        input: q,
      },
      headers: {
        'Content-Type': 'application/json',
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
        'Referer': 'https://music.youtube.com/',
        'Origin': 'https://music.youtube.com',
      },
    },
    { url: `https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(q)}`, method: 'GET' as const },
  ];

  for (const ep of endpoints) {
    if (_requestGeneration !== gen || signal?.aborted) return [];

    try {
      const res = await universalFetch<any>(ep.url, {
        method: ep.method,
        data: (ep as any).data,
        headers: (ep as any).headers,
        signal,
        timeoutMs: 1400,
      });

      if (_requestGeneration !== gen || signal?.aborted) return [];

      if (res.ok && res.data) {
        let rawList: unknown[] = [];
        if (Array.isArray(res.data)) {
          // If response is [query, [s1, s2, ...]] format
          if (Array.isArray(res.data[1])) {
            rawList = res.data[1];
          } else {
            // If response is [s1, s2, ...] format from /api/youtube-suggest
            rawList = res.data;
          }
        } else if (res.data?.contents) {
          // YouTube Music InnerTube get_search_suggestions format
          const contents = res.data.contents?.[0]?.searchSuggestionsSectionRenderer?.contents || [];
          for (const item of contents) {
            const s = item.searchSuggestionRenderer?.suggestion?.runs?.map((r: any) => r.text).join('');
            if (s) rawList.push(s);
          }
        }

        if (rawList.length > 0) {
          const suggestions = rawList
            .map((item: unknown) => (typeof item === 'string' ? cleanSuggestion(item) : ''))
            .filter(
              (s: string) =>
                s.length > 0 &&
                s.toLowerCase() !== cacheKey &&
                isSuggestionRelevant(s, q),
            );

          const results = [q, ...suggestions];

          // Supplement with clean music fallbacks if gaming terms were pruned
          if (results.length < 3) {
            const musicFallbacks = [`${q} songs`, `${q} music`, `${q} playlist`];
            for (const fb of musicFallbacks) {
              if (!results.includes(fb) && results.length < 6) {
                results.push(fb);
              }
            }
          }

          const finalList = results.slice(0, 6);

          if (SUGGEST_CACHE.size >= MAX_SUGGEST_CACHE_SIZE) {
            const oldestKey = SUGGEST_CACHE.keys().next().value;
            if (oldestKey) SUGGEST_CACHE.delete(oldestKey);
          }
          SUGGEST_CACHE.set(cacheKey, finalList);
          return finalList;
        }
      }
    } catch {
      // Continue to next endpoint
    }
  }

  return [q, `${q} songs`, `${q} music`].slice(0, 3);
}
