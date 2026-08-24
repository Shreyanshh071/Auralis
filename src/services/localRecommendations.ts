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

const FALLBACK_DISCOVERY_MAP: Record<string, Track[]> = {
  trending: [
    { id: '4NRXx6U8ABQ', title: 'Blinding Lights', artist: 'The Weeknd', album: 'After Hours', duration: 200, thumbnail: 'https://i.ytimg.com/vi/4NRXx6U8ABQ/hqdefault.jpg', color: '#ef4444' },
    { id: 'H5v3kku4y6Q', title: 'As It Was', artist: 'Harry Styles', album: "Harry's House", duration: 167, thumbnail: 'https://i.ytimg.com/vi/H5v3kku4y6Q/hqdefault.jpg', color: '#ec4899' },
    { id: '34Na4j8AVgA', title: 'Starboy', artist: 'The Weeknd ft. Daft Punk', album: 'Starboy', duration: 230, thumbnail: 'https://i.ytimg.com/vi/34Na4j8AVgA/hqdefault.jpg', color: '#3b82f6' },
    { id: 'JGwWNGJdvx8', title: 'Shape of You', artist: 'Ed Sheeran', album: '÷ (Divide)', duration: 233, thumbnail: 'https://i.ytimg.com/vi/JGwWNGJdvx8/hqdefault.jpg', color: '#06b6d4' },
    { id: 'kTJczUoc26U', title: 'Stay', artist: 'The Kid LAROI, Justin Bieber', album: 'Stay', duration: 141, thumbnail: 'https://i.ytimg.com/vi/kTJczUoc26U/hqdefault.jpg', color: '#8b5cf6' },
    { id: 'mRD0-GxqHVo', title: 'Heat Waves', artist: 'Glass Animals', album: 'Dreamland', duration: 238, thumbnail: 'https://i.ytimg.com/vi/mRD0-GxqHVo/hqdefault.jpg', color: '#10b981' },
    { id: 'TUVcZfQe-Kw', title: 'Levitating', artist: 'Dua Lipa', album: 'Future Nostalgia', duration: 203, thumbnail: 'https://i.ytimg.com/vi/TUVcZfQe-Kw/hqdefault.jpg', color: '#ec4899' },
    { id: 'XXYlFuWEuKI', title: 'Save Your Tears', artist: 'The Weeknd', album: 'After Hours', duration: 215, thumbnail: 'https://i.ytimg.com/vi/XXYlFuWEuKI/hqdefault.jpg', color: '#ef4444' },
  ],
  'lofi-chill': [
    { id: 'jfKfPfyJRdk', title: 'Lofi Chill Beats', artist: 'Lofi Girl', album: 'Lofi Records', duration: 180, thumbnail: 'https://i.ytimg.com/vi/jfKfPfyJRdk/hqdefault.jpg', color: '#3b82f6' },
    { id: 'GCdwKhTtNNw', title: 'Sweater Weather', artist: 'The Neighbourhood', album: 'I Love You.', duration: 240, thumbnail: 'https://i.ytimg.com/vi/GCdwKhTtNNw/hqdefault.jpg', color: '#6366f1' },
    { id: 'sBzrzS1Ag_g', title: 'The Less I Know The Better', artist: 'Tame Impala', album: 'Currents', duration: 216, thumbnail: 'https://i.ytimg.com/vi/sBzrzS1Ag_g/hqdefault.jpg', color: '#9333ea' },
    { id: '5qap5aO4i9A', title: 'Lofi Study Chillhop', artist: 'Chillhop Music', album: 'Chillhop Essentials', duration: 195, thumbnail: 'https://i.ytimg.com/vi/5qap5aO4i9A/hqdefault.jpg', color: '#10b981' },
  ],
  'indie-alt': [
    { id: 'sBzrzS1Ag_g', title: 'The Less I Know The Better', artist: 'Tame Impala', album: 'Currents', duration: 216, thumbnail: 'https://i.ytimg.com/vi/sBzrzS1Ag_g/hqdefault.jpg', color: '#9333ea' },
    { id: 'bpOSxM0rNPM', title: 'Do I Wanna Know?', artist: 'Arctic Monkeys', album: 'AM', duration: 272, thumbnail: 'https://i.ytimg.com/vi/bpOSxM0rNPM/hqdefault.jpg', color: '#f59e0b' },
    { id: 'GCdwKhTtNNw', title: 'Sweater Weather', artist: 'The Neighbourhood', album: 'I Love You.', duration: 240, thumbnail: 'https://i.ytimg.com/vi/GCdwKhTtNNw/hqdefault.jpg', color: '#6366f1' },
    { id: 'hTWKbfoikeg', title: 'Smells Like Teen Spirit', artist: 'Nirvana', album: 'Nevermind', duration: 301, thumbnail: 'https://i.ytimg.com/vi/hTWKbfoikeg/hqdefault.jpg', color: '#06b6d4' },
    { id: 'DyDfgMOUjCI', title: 'bad guy', artist: 'Billie Eilish', album: 'WHEN WE ALL FALL ASLEEP, WHERE DO WE GO?', duration: 194, thumbnail: 'https://i.ytimg.com/vi/DyDfgMOUjCI/hqdefault.jpg', color: '#84cc16' },
  ],
  'rock-classics': [
    { id: 'fJ9rUzIMcZQ', title: 'Bohemian Rhapsody', artist: 'Queen', album: 'A Night at the Opera', duration: 354, thumbnail: 'https://i.ytimg.com/vi/fJ9rUzIMcZQ/hqdefault.jpg', color: '#e11d48' },
    { id: '09839DpTctU', title: 'Hotel California', artist: 'Eagles', album: 'Hotel California', duration: 390, thumbnail: 'https://i.ytimg.com/vi/09839DpTctU/hqdefault.jpg', color: '#f59e0b' },
    { id: '1w7OgIMMRc4', title: "Sweet Child O' Mine", artist: "Guns N' Roses", album: "Appetite for Destruction", duration: 356, thumbnail: 'https://i.ytimg.com/vi/1w7OgIMMRc4/hqdefault.jpg', color: '#eab308' },
    { id: 'djV11Xbc914', title: 'Take On Me', artist: 'a-ha', album: 'Hunting High and Low', duration: 227, thumbnail: 'https://i.ytimg.com/vi/djV11Xbc914/hqdefault.jpg', color: '#f97316' },
  ],
};

export async function fetchColdStartDiscovery(
  existingIds: Set<string>,
): Promise<RecommendationSection[]> {
  const sections: RecommendationSection[] = [];

  for (const cat of DISCOVERY_CATEGORIES) {
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
      // Fall through to fallback
    }

    if (section.tracks.length === 0 && FALLBACK_DISCOVERY_MAP[cat.id]) {
      section.tracks = FALLBACK_DISCOVERY_MAP[cat.id].filter((t) => !existingIds.has(t.id));
      section.tracks.forEach((t) => existingIds.add(t.id));
    }

    if (section.tracks.length > 0) {
      sections.push(section);
    }
  }

  // Ensure at least two discovery sections always exist
  if (sections.length === 0) {
    const defaultTrending: RecommendationSection = {
      id: 'discover-trending',
      title: 'Trending & Popular Hits',
      icon: 'flame',
      tracks: FALLBACK_DISCOVERY_MAP.trending || [],
    };
    const defaultChill: RecommendationSection = {
      id: 'discover-chill',
      title: 'Midnight Lofi & Chill',
      icon: 'music',
      tracks: FALLBACK_DISCOVERY_MAP['lofi-chill'] || [],
    };
    sections.push(defaultTrending, defaultChill);
  }

  return sections;
}

// ─── Main Orchestrator ───────────────────────────────────────────────────────

export interface RecommendationResult {
  quickPicks: Track[];
  sections: RecommendationSection[];
}

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
      const discoveryPicks = await cachedSearch('best new music hits playlist', existingIds, 8);
      discoveryPicks.forEach((t) => {
        if (!quickPicks.some((q) => q.id === t.id)) {
          quickPicks.push(t);
          existingIds.add(t.id);
        }
      });
    } catch {}

    if (quickPicks.length < 4 && FALLBACK_DISCOVERY_MAP.trending) {
      FALLBACK_DISCOVERY_MAP.trending.forEach((t) => {
        if (!quickPicks.some((q) => q.id === t.id)) quickPicks.push(t);
      });
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

  // 4. Discovery section
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

  const settled = await Promise.allSettled(sectionPromises);
  for (const result of settled) {
    if (result.status === 'fulfilled' && result.value) {
      allSections.push(result.value);
    }
  }

  // Guaranteed fallback: If all sections returned empty, fill from curated discovery
  if (allSections.length === 0) {
    const fallback = await fetchColdStartDiscovery(existingIds);
    fallback.forEach((s) => {
      allSections.push(s);
      onSectionReady?.(s);
    });
  }

  // Guaranteed quick picks
  if (quickPicks.length < 4 && FALLBACK_DISCOVERY_MAP.trending) {
    FALLBACK_DISCOVERY_MAP.trending.forEach((t) => {
      if (!quickPicks.some((q) => q.id === t.id)) {
        quickPicks.push(t);
        existingIds.add(t.id);
      }
    });
  }

  return { quickPicks: quickPicks.slice(0, 8), sections: allSections };
}
