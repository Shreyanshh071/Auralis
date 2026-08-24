/**
 * Fast search suggestions service for Auralis.
 * Queries YouTube / Google Suggest endpoints to provide instant music-focused
 * autocomplete suggestions (e.g. 'imposter syndrome', 'imposter syndrome slowed').
 *
 * Key invariants:
 * - The suggestions returned always relate to the EXACT query the caller typed.
 * - A response for a stale (older) query never overwrites a fresher one.
 * - The user's typed query is always the first suggestion.
 * - Fallback suggestions use the EXACT typed query, never a truncated form.
 */

async function fetchWithTimeout(
  url: string,
  options: RequestInit = {},
  timeoutMs = 2500,
): Promise<Response> {
  const controller = new AbortController();
  const merged = AbortSignal.any
    ? { ...options, signal: options.signal ? AbortSignal.any([options.signal, controller.signal]) : controller.signal }
    : { ...options, signal: controller.signal };
  const id = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, merged);
    clearTimeout(id);
    return response;
  } catch (error) {
    clearTimeout(id);
    throw error;
  }
}

const SUGGEST_CACHE = new Map<string, string[]>();

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
function cleanSuggestion(raw: string): string {
  return raw.replace(/\s+/g, ' ').trim();
}

/**
 * Check whether a suggestion is relevant to the query. A suggestion is relevant
 * if the query is a prefix of the suggestion (case-insensitive), OR if they
 * share a significant word overlap. This prevents completely unrelated suggestions
 * from appearing while still allowing Google's organic completions.
 */
function isSuggestionRelevant(suggestion: string, query: string): boolean {
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
 * Fetch compact search suggestions for a given input query.
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

  const cacheKey = q.toLowerCase();
  if (SUGGEST_CACHE.has(cacheKey)) {
    return SUGGEST_CACHE.get(cacheKey)!;
  }

  // Suggestion endpoints — direct Google Suggest, then CORS proxy fallback.
  const endpoints = [
    `https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(q)}`,
    `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(q)}`)}`,
  ];

  for (const url of endpoints) {
    // Bail early if this request has been superseded.
    if (_requestGeneration !== gen) return [];
    if (signal?.aborted) return [];

    try {
      const res = await fetchWithTimeout(url, { signal }, 2000);
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data) && Array.isArray(data[1])) {
          // Stale check after await
          if (_requestGeneration !== gen) return [];

          const suggestions = data[1]
            .map((item: unknown) => (typeof item === 'string' ? cleanSuggestion(item) : ''))
            .filter(
              (s: string) =>
                s.length > 0 &&
                s.toLowerCase() !== cacheKey &&
                isSuggestionRelevant(s, q),
            )
            .slice(0, 7);

          // Always ensure the typed query itself is at the top.
          const results = [q, ...suggestions].slice(0, 6);
          SUGGEST_CACHE.set(cacheKey, results);
          return results;
        }
      }
    } catch {
      // Continue to next endpoint or return local fallback.
    }
  }

  // Fallback: just the user's exact typed query — never pad with generic
  // suffixes like "slowed" / "sped up" / "live" which are distracting when
  // the user has a specific song in mind.
  return [q];
}
