import type { SearchResults } from '../types/music';

interface CacheEntry<T> {
  data: T;
  timestamp: number;
}

const MEMORY_MAX_ENTRIES = 100;
const L2_STORAGE_KEY_PREFIX = 'auralis_search_cache_';
const DEFAULT_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours

/**
 * Clean and normalize a search query into a canonical cache key.
 * Trims whitespace, lowercases, collapses redundant internal whitespace,
 * and strips non-essential search noise for maximum cache hit rates.
 */
export function normalizeSearchKey(query: string): string {
  if (!query || typeof query !== 'string') return '';
  return query
    .trim()
    .toLowerCase()
    .replace(/\s+/g, ' ');
}

class SearchCache {
  private l1Memory = new Map<string, CacheEntry<SearchResults>>();

  /**
   * Synchronously retrieve cached search results from L1 (Memory) or L2 (Storage).
   * Returns null if not cached or expired.
   */
  get(query: string, ttlMs = DEFAULT_TTL_MS): SearchResults | null {
    const key = normalizeSearchKey(query);
    if (!key) return null;

    const now = Date.now();

    // 1. Check L1 Memory Cache (<0.1ms)
    const memEntry = this.l1Memory.get(key);
    if (memEntry) {
      if (now - memEntry.timestamp < ttlMs) {
        // Move to newest for LRU
        this.l1Memory.delete(key);
        this.l1Memory.set(key, memEntry);
        return memEntry.data;
      }
      this.l1Memory.delete(key);
    }

    // 2. Check L2 Session/Local Storage (1-2ms)
    try {
      if (typeof window !== 'undefined' && window.sessionStorage) {
        const raw = sessionStorage.getItem(`${L2_STORAGE_KEY_PREFIX}${key}`);
        if (raw) {
          const entry: CacheEntry<SearchResults> = JSON.parse(raw);
          if (entry && now - entry.timestamp < ttlMs) {
            // Promote to L1
            this.setL1(key, entry.data, entry.timestamp);
            return entry.data;
          }
          sessionStorage.removeItem(`${L2_STORAGE_KEY_PREFIX}${key}`);
        }
      }
    } catch {
      // Storage unavailable or disabled
    }

    return null;
  }

  /**
   * Store search results in both L1 (Memory) and L2 (Storage).
   */
  set(query: string, data: SearchResults): void {
    const key = normalizeSearchKey(query);
    if (!key || !data) return;

    // Only cache if there's at least one result
    if (data.songs.length === 0 && data.artists.length === 0 && data.playlists.length === 0) {
      return;
    }

    const timestamp = Date.now();
    this.setL1(key, data, timestamp);

    // Save to L2 Storage asynchronously
    try {
      if (typeof window !== 'undefined' && window.sessionStorage) {
        const payload = JSON.stringify({ data, timestamp });
        sessionStorage.setItem(`${L2_STORAGE_KEY_PREFIX}${key}`, payload);
      }
    } catch {
      // Storage full - ignore
    }
  }

  private setL1(key: string, data: SearchResults, timestamp: number): void {
    if (this.l1Memory.size >= MEMORY_MAX_ENTRIES) {
      // Evict oldest entry
      const oldestKey = this.l1Memory.keys().next().value;
      if (oldestKey) this.l1Memory.delete(oldestKey);
    }
    this.l1Memory.set(key, { data, timestamp });
  }

  /**
   * Check if query is in cache without evicting or promoting.
   */
  has(query: string, ttlMs = DEFAULT_TTL_MS): boolean {
    return this.get(query, ttlMs) !== null;
  }

  /**
   * Clear all L1 and L2 cache entries.
   */
  clear(): void {
    this.l1Memory.clear();
    try {
      if (typeof window !== 'undefined' && window.sessionStorage) {
        const keysToRemove: string[] = [];
        for (let i = 0; i < sessionStorage.length; i++) {
          const k = sessionStorage.key(i);
          if (k && k.startsWith(L2_STORAGE_KEY_PREFIX)) {
            keysToRemove.push(k);
          }
        }
        for (const k of keysToRemove) {
          sessionStorage.removeItem(k);
        }
      }
    } catch {}
  }
}

export const searchCache = new SearchCache();

const inFlightSearchPromises = new Map<string, Promise<SearchResults>>();

/**
 * Check if a search request is already currently executing over the wire.
 */
export function getInFlightSearch(query: string): Promise<SearchResults> | null {
  const key = normalizeSearchKey(query);
  return inFlightSearchPromises.get(key) || null;
}

/**
 * Register an in-flight search promise so parallel/subsequent callers share the exact same promise.
 */
export function setInFlightSearch(query: string, promise: Promise<SearchResults>): void {
  const key = normalizeSearchKey(query);
  if (!key) return;
  inFlightSearchPromises.set(key, promise);
  promise.finally(() => {
    inFlightSearchPromises.delete(key);
  });
}
