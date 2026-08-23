import React, { useState, useEffect } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import { searchYouTube, SearchUnavailableError } from '../../services/youtube';
import type { Track } from '../../types/music';
import {
  History,
  TrendingUp,
  User,
  ChevronRight,
  Mic2,
  Play,
  Headphones,
  Sparkles,
  Heart,
  Music2,
  Flame,
  AlertTriangle,
  RefreshCw,
  X,
} from 'lucide-react';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';

interface HomeViewProps {
  onSelectGenre: (genreQuery: string) => void;
  setActiveView: (view: string) => void;
}

interface SpeedDialItem {
  id: string;
  name: string;
  type: 'artist' | 'track' | 'more';
  image?: string;
  track?: Track;
  artistQuery?: string;
}

export const HomeView: React.FC<HomeViewProps> = ({ onSelectGenre, setActiveView }) => {
  const {
    playTrack,
    currentTrack,
    isPlaying,
    setIsNowPlayingOpen,
    setActiveModalTab,
    history,
    favorites,
    isFavorite,
    toggleFavorite,
    getTopTracks,
    getTopArtists,
  } = usePlayer();

  const [activeChip, setActiveChip] = useState<string>('all');
  const [speedDialPage, setSpeedDialPage] = useState<number>(0);
  const [recommendedTracks, setRecommendedTracks] = useState<Track[]>([]);
  const [isLoadingRecs, setIsLoadingRecs] = useState<boolean>(false);
  /** Set when the recommendation search could not reach any provider. */
  const [recsError, setRecsError] = useState<string | null>(null);
  /** Bumped to re-run the recommendation effect on an explicit retry. */
  const [recsAttempt, setRecsAttempt] = useState<number>(0);
  /** Set when a chip / speed-dial search could not reach any provider. */
  const [actionError, setActionError] = useState<string | null>(null);

  const filterChips = [
    { id: 'podcasts', name: 'Podcasts', query: 'podcasts music' },
    { id: 'romance', name: 'Romance', query: 'romantic love songs' },
    { id: 'relax', name: 'Relax', query: 'relax chill ambient beats' },
    { id: 'feelgood', name: 'Feel good', query: 'happy feel good hits' },
    { id: 'energize', name: 'Energize', query: 'high energy workout hype' },
    { id: 'focus', name: 'Focus', query: 'lofi study focus beats' },
  ];

  // Fetch recommendations based on the active or most recently played artist.
  //
  // There is deliberately no hardcoded fallback list here. If no provider can be
  // reached the section shows a real error with a retry; if a provider answers with
  // nothing the section shows a real empty state.
  useEffect(() => {
    let cancelled = false;

    const fetchDynamicRecommendations = async () => {
      const activeArtist = currentTrack?.artist || (history.length > 0 ? history[0].artist : null);
      if (!activeArtist) {
        setRecommendedTracks([]);
        setRecsError(null);
        setIsLoadingRecs(false);
        return;
      }

      setIsLoadingRecs(true);
      setRecsError(null);
      try {
        const cleanArtist = activeArtist.split(',')[0].split('feat')[0].trim();
        const results = await searchYouTube(`${cleanArtist} top songs hits`);
        if (cancelled) return;

        // Combine the user's recent songs + freshly fetched recommendations,
        // deduplicated. `results` may legitimately be empty.
        const merged: Track[] = [];
        if (currentTrack) merged.push(currentTrack);
        history.slice(0, 3).forEach((t) => {
          if (!merged.some((m) => m.id === t.id)) merged.push(t);
        });
        results.forEach((t) => {
          if (!merged.some((m) => m.id === t.id)) merged.push(t);
        });
        setRecommendedTracks(merged.slice(0, 8));
      } catch (err) {
        if (cancelled) return;
        setRecommendedTracks([]);
        setRecsError(
          err instanceof SearchUnavailableError
            ? 'Search is unavailable right now, so recommendations could not be loaded.'
            : 'Recommendations could not be loaded.'
        );
      } finally {
        if (!cancelled) setIsLoadingRecs(false);
      }
    };

    fetchDynamicRecommendations();
    return () => {
      cancelled = true;
    };
  }, [currentTrack?.id, recsAttempt]);

  // ---- Speed Dial: driven by real play counts ----
  const topTracks = getTopTracks(24);
  const topArtists = getTopArtists(12);
  const hasListeningData = topTracks.length > 0;

  const buildSpeedDialPages = (): SpeedDialItem[][] => {
    if (!hasListeningData) return [];

    const allItems: SpeedDialItem[] = [];
    let trackIdx = 0;
    let artistIdx = 0;

    while (allItems.length < 24 && (trackIdx < topTracks.length || artistIdx < topArtists.length)) {
      if (artistIdx < topArtists.length && allItems.length % 3 === 0) {
        const art = topArtists[artistIdx++];
        allItems.push({
          id: `artist-${art.name}-${artistIdx}`,
          name: art.name,
          type: 'artist',
          artistQuery: art.name,
          image: art.image,
        });
      } else if (trackIdx < topTracks.length) {
        const trk = topTracks[trackIdx++];
        allItems.push({
          id: `track-${trk.id}-${trackIdx}`,
          name: trk.title,
          type: 'track',
          track: trk,
          image: trk.thumbnail,
        });
      } else {
        break;
      }
    }

    const pages: SpeedDialItem[][] = [];
    for (let p = 0; p < 3; p++) {
      const pageSlice = allItems.slice(p * 8, (p + 1) * 8);
      if (pageSlice.length > 0) {
        pageSlice.push({ id: `more-${p}`, name: 'Explore More', type: 'more' });
        pages.push(pageSlice);
      }
    }
    return pages;
  };

  const speedDialPages = buildSpeedDialPages();
  const currentSpeedDialItems = speedDialPages[speedDialPage] || [];

  const describeSearchFailure = (err: unknown) =>
    err instanceof SearchUnavailableError
      ? 'Search is unavailable right now. Check your connection and try again.'
      : 'That search failed. Please try again.';

  const handleSpeedDialClick = async (item: SpeedDialItem) => {
    if (item.type === 'track' && item.track) {
      playTrack(item.track);
      return;
    }
    if (item.type === 'artist' && item.artistQuery) {
      setActionError(null);
      try {
        const tracks = await searchYouTube(`${item.artistQuery} top songs`);
        if (tracks.length > 0) {
          playTrack(tracks[0], tracks);
        } else {
          setActionError(`No songs found for ${item.artistQuery}.`);
        }
      } catch (err) {
        setActionError(describeSearchFailure(err));
      }
      return;
    }
    setActiveView('explore');
  };

  const handleChipClick = async (chip: { id: string; name?: string; query: string }) => {
    setActiveChip(chip.id);
    setActionError(null);
    try {
      const tracks = await searchYouTube(chip.query);
      if (tracks.length > 0) {
        playTrack(tracks[0], tracks);
      } else {
        setActionError(`No songs found for “${chip.name ?? chip.query}”.`);
      }
    } catch (err) {
      setActionError(describeSearchFailure(err));
    }
  };

  const formatDuration = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <div className="space-y-6 pb-40 max-w-2xl mx-auto select-none text-[var(--text-primary)]">
      {/* Top Header Bar */}
      <div className="flex items-center justify-between pt-1">
        <h1 className="font-display font-bold text-2xl sm:text-3xl text-[var(--text-primary)] tracking-tight">
          Home
        </h1>
      </div>

      {/* Filter Chips */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
        {filterChips.map((chip) => {
          const isActive = activeChip === chip.id;
          return (
            <button
              key={chip.id}
              onClick={() => handleChipClick(chip)}
              className={`px-4 py-1.5 rounded-full text-xs font-medium whitespace-nowrap transition-colors cursor-pointer ${
                isActive
                  ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#1b200f] font-bold shadow-sm'
                  : 'bg-[var(--bg-surface)] text-[var(--text-secondary)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)]'
              }`}
            >
              {chip.name}
            </button>
          );
        })}
      </div>

      {/* Real failure notice for chip / speed-dial searches. Not shown otherwise. */}
      {actionError && (
        <div className="flex items-start gap-2.5 px-3.5 py-2.5 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-600 dark:text-amber-300">
          <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-amber-500" />
          <p className="text-xs leading-relaxed flex-1">{actionError}</p>
          <button
            onClick={() => setActionError(null)}
            className="p-0.5 rounded-full hover:bg-black/10 dark:hover:bg-white/10 transition flex-shrink-0 cursor-pointer"
            title="Dismiss"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        </div>
      )}

      <section className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-display font-bold text-lg text-[var(--text-primary)] tracking-wide">
            Speed dial
          </h2>
          {speedDialPages.length > 1 && (
            <span className="text-[10px] text-[var(--text-muted)] font-mono">
              {speedDialPage + 1} / {speedDialPages.length}
            </span>
          )}
        </div>

        {!hasListeningData ? (
          /* ---- Onboarding empty state ---- */
          <div className="flex flex-col items-center justify-center py-10 px-6 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] text-center space-y-3 shadow-sm">
            <div className="w-14 h-14 rounded-2xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center">
              <Headphones className="w-7 h-7 text-[var(--text-muted)]" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-[var(--text-primary)]">Your Speed Dial is empty</h3>
              <p className="text-xs text-[var(--text-muted)] mt-1 max-w-xs">
                Play some songs to fill this section with your most-listened tracks and favorite artists.
              </p>
            </div>
            <button
              onClick={() => setActiveView('explore')}
              className="mt-2 px-5 py-2 rounded-full bg-purple-600 text-white hover:bg-purple-700 dark:bg-[#dbe7b5] dark:text-[#14190c] dark:hover:bg-[#c9d79e] text-xs font-bold transition cursor-pointer shadow-sm"
            >
              Explore Music
            </button>
          </div>
        ) : (
          <>
            {/* 3x3 Speed Dial Grid */}
            <div className="grid grid-cols-3 gap-2 sm:gap-3 transition-all duration-300">
              {currentSpeedDialItems.map((item) => {
                if (item.type === 'more') {
                  return (
                    <div
                      key={item.id}
                      onClick={() => setActiveView('explore')}
                      className="aspect-square rounded-2xl bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] border border-[var(--border-subtle)] flex items-center justify-center cursor-pointer transition active:scale-95 shadow-sm"
                      title="Explore all songs & charts"
                    >
                      <div className="grid grid-cols-3 gap-1.5 p-2">
                        {[0,1,2,3,4,5,6,7,8].map(i => (
                          <div key={i} className={`w-2 h-2 rounded-full ${[0,1,2,4,6,8].includes(i) ? 'bg-purple-500 dark:bg-[#dbe7b5]' : 'bg-transparent'}`} />
                        ))}
                      </div>
                    </div>
                  );
                }

                if (item.type === 'artist') {
                  return (
                    <div
                      key={item.id}
                      onClick={() => handleSpeedDialClick(item)}
                      className="group relative aspect-square rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] flex flex-col items-center justify-center p-2.5 cursor-pointer hover:bg-[var(--bg-card-hover)] transition overflow-hidden active:scale-95 shadow-sm"
                    >
                      <div className="w-14 h-14 sm:w-16 sm:h-16 rounded-full overflow-hidden mb-2 bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center flex-shrink-0 shadow-inner">
                        {item.image ? (
                          <img
                            src={item.image}
                            alt=""
                            className="w-full h-full object-cover grayscale group-hover:grayscale-0 transition duration-300"
                            onError={(e) => {
                              e.currentTarget.style.display = 'none';
                            }}
                          />
                        ) : (
                          <span className="text-base font-black text-purple-500 dark:text-[#dbe7b5]">
                            {item.name.charAt(0).toUpperCase()}
                          </span>
                        )}
                      </div>
                      <div className="flex items-center gap-1 w-full justify-center px-1">
                        <span className="text-[11px] font-bold text-[var(--text-primary)] truncate text-center leading-none">
                          {item.name}
                        </span>
                        <ChevronRight className="w-3 h-3 text-[var(--text-muted)] flex-shrink-0" />
                      </div>
                    </div>
                  );
                }

                return (
                  <div
                    key={item.id}
                    onClick={() => handleSpeedDialClick(item)}
                    className="group relative aspect-square rounded-2xl overflow-hidden bg-[var(--bg-card)] border border-[var(--border-subtle)] cursor-pointer hover:border-[var(--border-strong)] transition active:scale-95 shadow-sm"
                  >
                    <img
                      src={item.image}
                      alt=""
                      className="w-full h-full object-cover scale-[1.04] group-hover:scale-105 transition duration-300"
                      onError={(e) => {
                        const target = e.currentTarget;
                        if (item.track?.id && !target.src.includes('hqdefault')) {
                          target.src = `https://i.ytimg.com/vi/${item.track.id}/hqdefault.jpg`;
                        }
                      }}
                    />
                    <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/35 to-transparent flex items-end p-2 sm:p-2.5">
                      <p className="text-[11px] font-semibold text-white leading-tight line-clamp-2">{item.name}</p>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Pagination Dots */}
            {speedDialPages.length > 1 && (
              <div className="flex items-center justify-center gap-2 pt-1.5">
                {speedDialPages.map((_, idx) => (
                  <button
                    key={idx}
                    onClick={() => setSpeedDialPage(idx)}
                    className={`h-2 rounded-full transition-all duration-300 cursor-pointer ${
                      speedDialPage === idx
                        ? 'w-5 bg-purple-500 dark:bg-[#dbe7b5]'
                        : 'w-2 bg-[var(--border-strong)] hover:bg-[var(--text-muted)]'
                    }`}
                  />
                ))}
              </div>
            )}
          </>
        )}
      </section>

      {/* Quick Picks Section — Always Recommends Songs */}
      <section className="space-y-3 pt-2">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <h2 className="font-display font-bold text-lg text-[var(--text-primary)] tracking-wide">
              Quick picks
            </h2>
            {currentTrack && (
              <span className="hidden sm:inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] text-[10px] font-semibold text-[var(--text-secondary)]">
                <Flame className="w-3 h-3 text-amber-500 dark:text-[#dbe7b5]" />
                <span>Recommended for you</span>
              </span>
            )}
          </div>

          {recommendedTracks.length > 0 && (
            <button
              onClick={() => {
                if (recommendedTracks.length > 0) playTrack(recommendedTracks[0], recommendedTracks);
              }}
              className="px-3.5 py-1 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-primary)] text-xs font-semibold border border-[var(--border-subtle)] transition flex items-center gap-1.5 cursor-pointer shadow-sm"
            >
              <Play className="w-3 h-3 fill-current" />
              <span>Play all</span>
            </button>
          )}
        </div>

        {isLoadingRecs ? (
          <div className="flex items-center justify-center py-6 gap-2 text-[var(--text-muted)] text-xs">
            <div className="w-4 h-4 rounded-full border-2 border-purple-500/30 border-t-purple-500 dark:border-[#dbe7b5]/30 dark:border-t-[#dbe7b5] animate-spin" />
            <span>Finding recommendations...</span>
          </div>
        ) : recsError ? (
          /* ---- Real error state: provider unreachable ---- */
          <div className="flex flex-col items-center justify-center py-8 px-6 rounded-2xl bg-[var(--bg-card)] border border-amber-500/30 text-center space-y-3 shadow-sm">
            <div className="w-12 h-12 rounded-2xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-center">
              <AlertTriangle className="w-6 h-6 text-amber-500" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-[var(--text-primary)]">Couldn’t load recommendations</h3>
              <p className="text-xs text-[var(--text-muted)] mt-1 max-w-xs">{recsError}</p>
            </div>
            <button
              onClick={() => setRecsAttempt((n) => n + 1)}
              className="mt-1 px-5 py-2 rounded-full bg-purple-600 text-white hover:bg-purple-700 dark:bg-[#dbe7b5] dark:text-[#14190c] dark:hover:bg-[#c9d79e] text-xs font-bold transition flex items-center gap-1.5 cursor-pointer shadow-sm"
            >
              <RefreshCw className="w-3.5 h-3.5" />
              <span>Retry</span>
            </button>
          </div>
        ) : recommendedTracks.length === 0 ? (
          /* ---- Real empty state: nothing to recommend yet ---- */
          <div className="flex flex-col items-center justify-center py-8 px-6 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] text-center space-y-3 shadow-sm">
            <div className="w-12 h-12 rounded-2xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center">
              <Music2 className="w-6 h-6 text-[var(--text-muted)]" />
            </div>
            <div>
              <h3 className="text-sm font-bold text-[var(--text-primary)]">No recommendations yet</h3>
              <p className="text-xs text-[var(--text-muted)] mt-1 max-w-xs">
                Play a song and Auralis will build quick picks from what you listen to.
              </p>
            </div>
            <button
              onClick={() => setActiveView('explore')}
              className="mt-1 px-5 py-2 rounded-full bg-purple-600 text-white hover:bg-purple-700 dark:bg-[#dbe7b5] dark:text-[#14190c] dark:hover:bg-[#c9d79e] text-xs font-bold transition cursor-pointer shadow-sm"
            >
              Explore Music
            </button>
          </div>
        ) : (
          <div className="space-y-1.5">
            {recommendedTracks.map((track) => {
              const isCurrent = currentTrack?.id === track.id;
              const favorite = isFavorite(track.id);

              return (
                <div
                  key={track.id}
                  onClick={() => playTrack(track, recommendedTracks)}
                  className={`flex items-center justify-between p-2 rounded-2xl cursor-pointer transition ${
                    isCurrent
                      ? 'bg-purple-500/10 border border-purple-500/30 dark:bg-[#252d19] dark:border-[#3a4727]'
                      : 'hover:bg-[var(--bg-card-hover)] border border-transparent'
                  }`}
                >
                  <div className="flex items-center gap-3 min-w-0 flex-1">
                    <div className="relative w-12 h-12 rounded-xl overflow-hidden bg-neutral-800 flex-shrink-0 shadow-sm">
                      <img
                        src={track.thumbnail}
                        alt=""
                        className="w-full h-full object-cover aspect-square scale-[1.04]"
                        onError={(e) => {
                          const target = e.currentTarget;
                          if (!target.src.includes('hqdefault')) {
                            target.src = `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`;
                          }
                        }}
                      />
                      <div className={`absolute inset-0 bg-black/40 flex items-center justify-center transition-opacity ${isCurrent && isPlaying ? 'opacity-100' : 'opacity-0 hover:opacity-100'}`}>
                        <Play className="w-4 h-4 text-white dark:text-[#dbe7b5] fill-current ml-0.5" />
                      </div>
                    </div>

                    <div className="min-w-0 flex-1">
                      <p className={`text-xs sm:text-sm font-bold truncate ${isCurrent ? 'text-purple-600 dark:text-[#dbe7b5]' : 'text-[var(--text-primary)]'}`}>
                        {track.title}
                      </p>
                      <p className="text-[11px] text-[var(--text-muted)] truncate mt-0.5">
                        {track.artist} • {formatDuration(track.duration)}
                      </p>
                    </div>
                  </div>

                  <div className="flex items-center gap-1 text-[var(--text-muted)]">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleFavorite(track);
                      }}
                      className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition cursor-pointer"
                      title={favorite ? 'Liked' : 'Like'}
                    >
                      <Heart className={`w-4 h-4 ${favorite ? 'fill-red-500 text-red-500' : 'text-[var(--text-muted)]'}`} />
                    </button>

                    <AddToPlaylistButton
                      track={track}
                      className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition text-[var(--text-muted)] cursor-pointer"
                    />

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        playTrack(track, recommendedTracks);
                        setActiveModalTab('lyrics');
                        setIsNowPlayingOpen(true);
                      }}
                      className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition cursor-pointer"
                      title="Spicy Lyrics"
                    >
                      <Mic2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
};
