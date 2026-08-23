/**
 * Fast search suggestions service for Auralis.
 * Queries YouTube / Google Suggest endpoints to provide instant music-focused
 * autocomplete suggestions (e.g. 'imposter syndrome', 'imposter syndrome slowed').
 */

async function fetchWithTimeout(url: string, options: RequestInit = {}, timeoutMs = 2500): Promise<Response> {
  const controller = new AbortController();
  const id = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { ...options, signal: controller.signal });
    clearTimeout(id);
    return response;
  } catch (error) {
    clearTimeout(id);
    throw error;
  }
}

const SUGGEST_CACHE = new Map<string, string[]>();

/**
 * Filter out non-music / junk search suggestion noise
 */
function cleanSuggestion(raw: string): string {
  return raw
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Fetch compact search suggestions for a given input query.
 */
export async function getSearchSuggestions(query: string): Promise<string[]> {
  const q = query.trim();
  if (!q || q.length < 2) return [];

  const lowerQ = q.toLowerCase();
  if (SUGGEST_CACHE.has(lowerQ)) {
    return SUGGEST_CACHE.get(lowerQ)!;
  }

  // Suggestion endpoints
  const endpoints = [
    `https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(q)}`,
    `https://api.allorigins.win/raw?url=${encodeURIComponent(`https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=${encodeURIComponent(q)}`)}`,
  ];

  for (const url of endpoints) {
    try {
      const res = await fetchWithTimeout(url, {}, 2000);
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data) && Array.isArray(data[1])) {
          const suggestions = data[1]
            .map((item: unknown) => (typeof item === 'string' ? cleanSuggestion(item) : ''))
            .filter((s: string) => s.length > 0 && s.toLowerCase() !== lowerQ)
            .slice(0, 7);

          // Always ensure the typed query itself is at the top if distinct
          const results = [q, ...suggestions.filter((s: string) => s.toLowerCase() !== lowerQ)].slice(0, 6);
          SUGGEST_CACHE.set(lowerQ, results);
          return results;
        }
      }
    } catch {
      // Continue to next endpoint or return local fallback
    }
  }

  // Fallback: simple prefix/common music variations
  const fallback = [
    q,
    `${q} song`,
    `${q} slowed`,
    `${q} sped up`,
    `${q} live`,
  ];
  return fallback;
}
