/**
 * Search History Service
 *
 * Persists recent search queries in localStorage so the Search page can show
 * a clean, history-based empty state instead of auto-loading trending content.
 *
 * Storage format: JSON array of strings, newest first, max 15 entries.
 */

const STORAGE_KEY = 'auralis_search_history';
const MAX_ENTRIES = 15;

function readHistory(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((item): item is string => typeof item === 'string' && item.trim().length > 0);
  } catch {
    return [];
  }
}

function writeHistory(entries: string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // localStorage full or unavailable — fail silently.
  }
}

/**
 * Returns the most recent search queries (up to 15), newest first.
 */
export function getSearchHistory(): string[] {
  return readHistory();
}

/**
 * Adds a query to the top of the search history.
 * Deduplicates case-insensitively — if a matching entry already exists,
 * it is moved to the top rather than duplicated.
 */
export function addToSearchHistory(query: string): void {
  const q = query.trim();
  if (!q) return;

  const existing = readHistory();
  // Remove any case-insensitive duplicate
  const filtered = existing.filter((e) => e.toLowerCase() !== q.toLowerCase());
  // Prepend
  const updated = [q, ...filtered].slice(0, MAX_ENTRIES);
  writeHistory(updated);
}

/**
 * Removes a single entry from the history (case-insensitive match).
 */
export function removeFromSearchHistory(query: string): void {
  const q = query.trim().toLowerCase();
  if (!q) return;
  const existing = readHistory();
  writeHistory(existing.filter((e) => e.toLowerCase() !== q));
}

/**
 * Clears all search history.
 */
export function clearSearchHistory(): void {
  writeHistory([]);
}
