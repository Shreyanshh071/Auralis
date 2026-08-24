/**
 * Local Recommendations Engine
 *
 * Generates personalized music discovery sections using ONLY local listening
 * data (play counts, favorites, history, saved artists/albums). No YouTube
 * account is required and no private listening history is sent to a server.
 *
 * The engine produces multiple named sections that HomeView renders progressively:
 * - "Because you listen to {Artist}" — related tracks from top affinity artist
 * - "Quick picks" — smart mix of recent favorites and new discoveries
 * - "Artists you might like" — adjacent artist discovery queries
 * - "Explore your sound" — mood/style discovery (warm-start and cold-start)
 */

import type { Track, SavedArtist, SavedAlbum } from '../types/music';
import { searchYouTube } from '../services/youtube';

// ─── Types ───────────────────────────────────────────────────────────────────

export interface PlayCountEntry {
  count: number;
  lastPlayed: number;
  track: Track;
}

export type PlayCountMap = Record<string, PlayCountEntry>;

export interface RecommendationSection {
  id: string;
  title: string;
  subtitle?: string;
  icon?: 'flame' | 'heart' | 'sparkles' | 'music' | 'compass' | 'headphones';
  tracks: Track[];
  isLoading?: boolean;
  error?: string | null;
}

export interface TasteProfile {
  topArtists: { name: string; score: number; image: string }[];
  topTracks: Track[];
  favoriteArtists: string[];
  recentArtists: string[];
  hasListeningData: boolean;
}

// ─── In-Memory Cache ─────────────────────────────────────────────────────────

interface CacheEntry {
  tracks: Track[];
  timestamp: number;
}

const queryCache = new Map<string, CacheEntry>();
const CACHE_TTL = 20 * 60 * 1000; // 20 minutes

function getCached(key: string): Track[] | null {
  const entry = queryCache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.timestamp > CACHE_TTL) {
    queryCache.delete(key);
    return null;
  }
  return entry.tracks;
}

function setCache(key: string, tracks: Track[]): void {
  queryCache.set(key, { tracks, timestamp: Date.now() });
}

// ─── Taste Profiling ─────────────────────────────────────────────────────────

function extractPrimaryArtist(raw: string): string {
  return raw.split(',')[0].split('feat')[0].split('ft.')[0].split('&')[0].trim();
}

export function buildTasteProfile(
  playCounts: PlayCountMap,
  history: Track[],
  favorites: Track[],
  savedArtists: SavedArtist[],
): TasteProfile {
  const artistScores: Record<string, { score: number; image: string }> = {};
  const now = Date.now();
  const thirtyDays = 30 * 24 * 60 * 60 * 1000;

  // Score from play counts (weighted by frequency and recency)
  Object.values(playCounts).forEach((entry) => {
    if (!entry.track?.artist) return;
    const artist = extractPrimaryArtist(entry.track.artist);
    const key = artist.toLowerCase();
    const age = now - entry.lastPlayed;
    const recencyBoost = age < thirtyDays ? 1 + (1 - age / thirtyDays) : 0.5;
    const score = entry.count * recencyBoost;

    if (!artistScores[key]) {
      artistScores[key] = { score: 0, image: entry.track.thumbnail };
    }
    artistScores[key].score += score;
    if (entry.lastPlayed > (now - 7 * 24 * 60 * 60 * 1000)) {
      artistScores[key].image = entry.track.thumbnail; // Use most recent image
    }
  });

  // Boost from favorites (+3 per favorited track)
  favorites.forEach((track) => {
    if (!track.artist) return;
    const artist = extractPrimaryArtist(track.artist);
    const key = artist.toLowerCase();
    if (!artistScores[key]) {
      artistScores[key] = { score: 0, image: track.thumbnail };
    }
    artistScores[key].score += 3;
  });

  // Boost from saved artists (+4)
  savedArtists.forEach((sa) => {
    const key = sa.name.toLowerCase();
    if (!artistScores[key]) {
      artistScores[key] = { score: 0, image: sa.thumbnail || '' };
    }
    artistScores[key].score += 4;
  });

  const topArtists = Object.entries(artistScores)
    .map(([key, val]) => ({
      name: key.charAt(0).toUpperCase() + key.slice(1),
      score: val.score,
      image: val.image,
    }))
    .sort((a, b) => b.score - a.score)
    .slice(0, 10);

  // Top tracks from play counts (recency-weighted)
  const topTracks = Object.values(playCounts)
    .filter((e) => e.track?.id)
    .map((e) => {
      const age = now - e.lastPlayed;
      const recency = age < thirtyDays ? 1 + (1 - age / thirtyDays) : 0.5;
      return { track: e.track, score: e.count * recency };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, 20)
    .map((e) => e.track);

  const favoriteArtists = [...new Set(favorites.map((t) => extractPrimaryArtist(t.artist)))];
  const recentArtists = [...new Set(history.slice(0, 10).map((t) => extractPrimaryArtist(t.artist)))];

  return {
    topArtists,
    topTracks,
    favoriteArtists,
    recentArtists,
    hasListeningData: topArtists.length > 0 || history.length > 0,
  };
}

// ─── Query Helpers ───────────────────────────────────────────────────────────

/** Search with cache + dedup against existing track IDs.
 *  Never throws — returns an empty array on failure so a single broken search
 *  does not take down other recommendation sections. */
async function cachedSearch(
  query: string,
  existingIds: Set<string>,
  limit = 8,
): Promise<Track[]> {
  const cacheKey = query.toLowerCase().trim();

  let results = getCached(cacheKey);
  if (!results) {
    try {
      results = await searchYouTube(query);
      setCache(cacheKey, results);
    } catch {
      // Network failure, provider unreachable, etc. — return empty rather
      // than propagating the error so other sections keep working.
      return [];
    }
  }

  return results
    .filter((t) => !existingIds.has(t.id))
    .slice(0, limit);
}

/** Collect all IDs from existing sections to prevent cross-section dupes */
function collectExistingIds(sections: RecommendationSection[], currentTrack?: Track | null): Set<string> {
  const ids = new Set<string>();
  if (currentTrack) ids.add(currentTrack.id);
  sections.forEach((s) => s.tracks.forEach((t) => ids.add(t.id)));
  return ids;
}

// ─── Section Generators ──────────────────────────────────────────────────────

/** "Because you listen to {Artist}" */
export async function fetchBecauseYouListenTo(
  artistName: string,
  existingIds: Set<string>,
): Promise<RecommendationSection> {
  const section: RecommendationSection = {
    id: `because-${artistName.toLowerCase().replace(/\s+/g, '-')}`,
    title: `Because you listen to ${artistName}`,
    icon: 'heart',
    tracks: [],
  };

  try {
    // Several phrasings, tried in order until we have enough. A bare artist
    // name sometimes returns their channel/topic rather than songs, so the
    // "songs"/"hits" variants act as resilient fallbacks.
    const queries = [
      `${artistName} songs`,
      `${artistName} best songs`,
      `${artistName} greatest hits`,
      `${artistName}`,
    ];
    const allTracks: Track[] = [];
    for (const q of queries) {
      const results = await cachedSearch(q, existingIds, 6);
      results.forEach((t) => {
        if (!allTracks.some((e) => e.id === t.id)) {
          allTracks.push(t);
          existingIds.add(t.id);
        }
      });
      if (allTracks.length >= 8) break;
    }
    section.tracks = allTracks.slice(0, 8);
    // No "No tracks found" error here: an empty personalised section is simply
    // dropped by the caller so the Home feed never shows a broken yellow card.
  } catch {
    // Swallow — an empty section is filtered out upstream, never surfaced.
  }

  return section;
}

/** "More like this" — based on secondary artist affinity */
export async function fetchMoreLikeThis(
  artistNames: string[],
  existingIds: Set<string>,
): Promise<RecommendationSection> {
  const section: RecommendationSection = {
    id: 'more-like-this',
    title: 'More like this',
    subtitle: 'Based on your taste',
    icon: 'sparkles',
    tracks: [],
  };

  try {
    const allTracks: Track[] = [];
    for (const artist of artistNames.slice(0, 3)) {
      // "<artist> songs" resolves to actual tracks more reliably than the bare
      // "<artist> music" phrasing, which sometimes surfaces topic channels.
      let results = await cachedSearch(`${artist} songs`, existingIds, 4);
      if (results.length === 0) {
        results = await cachedSearch(`${artist} popular songs`, existingIds, 4);
      }
      results.forEach((t) => {
        if (!allTracks.some((e) => e.id === t.id)) {
          allTracks.push(t);
          existingIds.add(t.id);
        }
      });
    }
    section.tracks = allTracks.slice(0, 8);
    // No error on empty — the caller drops empty sections silently.
  } catch {
    // Swallow; an empty section is filtered out upstream.
  }

  return section;
}

/** "Your recent taste" — tracks from recently played artists */
export async function fetchRecentTaste(
  recentArtists: string[],
  existingIds: Set<string>,
): Promise<RecommendationSection> {
  const section: RecommendationSection = {
    id: 'recent-taste',
    title: 'Your recent taste',
    subtitle: 'More from artists you play',
    icon: 'flame',
    tracks: [],
  };

  try {
    const allTracks: Track[] = [];
    for (const artist of recentArtists.slice(0, 4)) {
      let results = await cachedSearch(`${artist} songs`, existingIds, 3);
      if (results.length === 0) {
        results = await cachedSearch(`${artist} best songs`, existingIds, 3);
      }
      results.forEach((t) => {
        if (!allTracks.some((e) => e.id === t.id)) {
          allTracks.push(t);
          existingIds.add(t.id);
        }
      });
    }
    section.tracks = allTracks.slice(0, 8);
    // No error on empty — the caller drops empty sections silently.
  } catch {
    // Swallow; an empty section is filtered out upstream.
  }

  return section;
}

// ─── Cold-Start Discovery ────────────────────────────────────────────────────

const DISCOVERY_CATEGORIES = [
  { id: 'lofi-chill', title: 'Midnight Lofi & Chill', query: 'lofi chill beats relaxing', icon: 'music' as const },
  { id: 'indie-alt', title: 'Indie & Alternative Currents', query: 'indie alternative best songs 2024', icon: 'compass' as const },
  { id: 'neo-soul', title: 'Neo-Soul & Warm Grooves', query: 'neo soul smooth R&B grooves', icon: 'heart' as const },
  { id: 'electronic', title: 'Electronic & Ambient Soundscapes', query: 'electronic ambient chill music', icon: 'sparkles' as const },
  { id: 'acoustic-folk', title: 'Acoustic & Folk Essentials', query: 'acoustic folk singer songwriter', icon: 'headphones' as const },
  { id: 'hiphop-rap', title: 'Hip-Hop & Rap Essentials', query: 'hip hop rap best songs hits', icon: 'flame' as const },
];

export async function fetchColdStartDiscovery(
  existingIds: Set<string>,
): Promise<RecommendationSection[]> {
  const sections: RecommendationSection[] = [];

  // Pick 3 random categories for variety on each load
  const shuffled = [...DISCOVERY_CATEGORIES].sort(() => Math.random() - 0.5);
  const selected = shuffled.slice(0, 3);

  for (const cat of selected) {
    const section: RecommendationSection = {
      id: `discover-${cat.id}`,
      title: cat.title,
      icon: cat.icon,
      tracks: [],
    };

    try {
      const results = await cachedSearch(cat.query, existingIds, 6);
      results.forEach((t) => existingIds.add(t.id));
      section.tracks = results;
    } catch {
      // Leave the section empty; it is dropped below rather than shown broken.
    }

    if (section.tracks.length > 0) {
      sections.push(section);
    }
  }

  return sections;
}

// ─── Main Orchestrator ───────────────────────────────────────────────────────

export interface RecommendationResult {
  quickPicks: Track[];
  sections: RecommendationSection[];
}

/**
 * Generate all recommendation sections for the Home feed.
 *
 * @param profile - Taste profile built from local listening data
 * @param currentTrack - Currently playing track (for dedup)
 * @param history - Recent listening history
 * @param onSectionReady - Callback fired each time a section finishes loading (for progressive rendering)
 */
export async function generateRecommendations(
  profile: TasteProfile,
  currentTrack: Track | null | undefined,
  history: Track[],
  onSectionReady?: (section: RecommendationSection) => void,
): Promise<RecommendationResult> {
  const allSections: RecommendationSection[] = [];
  const existingIds = new Set<string>();
  if (currentTrack) existingIds.add(currentTrack.id);
  history.slice(0, 5).forEach((t) => existingIds.add(t.id));

  // Quick picks: mix of recent history and top tracks
  const quickPicks: Track[] = [];
  if (currentTrack) quickPicks.push(currentTrack);
  history.slice(0, 3).forEach((t) => {
    if (!quickPicks.some((q) => q.id === t.id)) quickPicks.push(t);
  });

  if (!profile.hasListeningData) {
    // ── Cold Start ──
    try {
      // Add a general discovery for quick picks
      const discoveryPicks = await cachedSearch('best new music 2024 playlist', existingIds, 8);
      discoveryPicks.forEach((t) => {
        if (!quickPicks.some((q) => q.id === t.id)) {
          quickPicks.push(t);
          existingIds.add(t.id);
        }
      });
    } catch {
      // Silent fail for quick picks; cold start sections below will show
    }

    const coldSections = await fetchColdStartDiscovery(existingIds);
    coldSections.forEach((s) => {
      allSections.push(s);
      onSectionReady?.(s);
    });

    return { quickPicks: quickPicks.slice(0, 8), sections: allSections };
  }

  // ── Warm Start: Personalized Sections (loaded in parallel) ──

  const sectionPromises: Promise<RecommendationSection | null>[] = [];

  // 1. "Because you listen to {Top Artist}"
  if (profile.topArtists.length > 0) {
    const topArtist = profile.topArtists[0].name;
    sectionPromises.push(
      fetchBecauseYouListenTo(topArtist, existingIds)
        .then((section) => {
          if (section.tracks.length > 0) {
            onSectionReady?.(section);
            // Also augment quick picks from top artist
            section.tracks.slice(0, 3).forEach((t) => {
              if (!quickPicks.some((q) => q.id === t.id)) quickPicks.push(t);
            });
            return section;
          }
          return null;
        })
        .catch(() => null),
    );
  }

  // 2. "More like this" from secondary artists
  if (profile.topArtists.length > 1) {
    const secondaryArtists = profile.topArtists.slice(1, 4).map((a) => a.name);
    sectionPromises.push(
      fetchMoreLikeThis(secondaryArtists, existingIds)
        .then((section) => {
          if (section.tracks.length > 0) {
            onSectionReady?.(section);
            return section;
          }
          return null;
        })
        .catch(() => null),
    );
  }

  // 3. "Your recent taste" from recently played artists
  if (profile.recentArtists.length > 0) {
    sectionPromises.push(
      fetchRecentTaste(profile.recentArtists, existingIds)
        .then((section) => {
          if (section.tracks.length > 0) {
            onSectionReady?.(section);
            return section;
          }
          return null;
        })
        .catch(() => null),
    );
  }

  // 4. Add one discovery section even for warm users (for diversity)
  sectionPromises.push(
    fetchColdStartDiscovery(existingIds)
      .then((discoverySections) => {
        if (discoverySections.length > 0) {
          const discovery = discoverySections[0];
          discovery.title = 'Explore your sound';
          discovery.subtitle = 'Something different for you';
          onSectionReady?.(discovery);
          return discovery;
        }
        return null;
      })
      .catch(() => null),
  );

  // Wait for all sections independently — one failure doesn't block the rest.
  const settled = await Promise.allSettled(sectionPromises);
  for (const result of settled) {
    if (result.status === 'fulfilled' && result.value) {
      allSections.push(result.value);
    }
  }

  // Resilience net: if every personalised query came back empty (offline burst,
  // provider hiccup, or an artist name that resolves poorly), fall back to the
  // popular/trending discovery genres so the Home feed is never blank. These are
  // real search results, not placeholders — they simply aren't personalised.
  if (allSections.length === 0) {
    try {
      const fallback = await fetchColdStartDiscovery(existingIds);
      fallback.forEach((s) => {
        allSections.push(s);
        onSectionReady?.(s);
      });
    } catch {
      // Nothing more we can do; HomeView still shows quick picks / its own retry.
    }
  }

  // Backfill quick picks with a trending mix if local data left them thin, so
  // the top shelf always has something to play.
  if (quickPicks.length < 4) {
    try {
      const trending = await cachedSearch('top hits this week', existingIds, 8);
      trending.forEach((t) => {
        if (!quickPicks.some((q) => q.id === t.id)) {
          quickPicks.push(t);
          existingIds.add(t.id);
        }
      });
    } catch {
      // Quick picks stay as-is; not fatal.
    }
  }

  return { quickPicks: quickPicks.slice(0, 8), sections: allSections };
}
