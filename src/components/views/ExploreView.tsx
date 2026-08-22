import React, { useState, useEffect } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import { GENRES, searchAll, SearchUnavailableError } from '../../services/youtube';
import { importYouTubePlaylist } from '../../services/youtubeImporter';
import type { Artist, PlaylistResult, SearchResults, Track } from '../../types/music';
import {
  Play,
  Search,
  Heart,
  Compass,
  TrendingUp,
  AlertTriangle,
  RefreshCw,
  Users,
  ListMusic,
  Loader2,
  Bookmark,
} from 'lucide-react';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';

const DEFAULT_QUERY = 'trending music 2025 top hits';

const EMPTY_RESULTS: SearchResults = { songs: [], artists: [], playlists: [] };

interface ExploreViewProps {
  initialQuery?: string;
  /**
   * Changes whenever a new search is submitted from outside this view (e.g. the
   * header). Included in the effect deps so submitting the *same* query twice
   * still re-runs the search.
   */
  queryNonce?: number;
}

export const ExploreView: React.FC<ExploreViewProps> = ({ initialQuery = '', queryNonce = 0 }) => {
  const [query, setQuery] = useState(initialQuery);
  const [results, setResults] = useState<SearchResults>(EMPTY_RESULTS);
  const [isLoading, setIsLoading] = useState(false);
  const [selectedGenre, setSelectedGenre] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState(false);
  /** Non-null when the last search could not reach any provider. */
  const [error, setError] = useState<string | null>(null);
  /** The query behind the currently displayed results, so Retry re-runs it. */
  const [lastQuery, setLastQuery] = useState<string>('');
  /** Id of the playlist currently being resolved (import in flight), for the spinner. */
  const [openingPlaylistId, setOpeningPlaylistId] = useState<string | null>(null);
  /** Non-null when opening a playlist failed — shown inline, never faked. */
  const [playlistError, setPlaylistError] = useState<string | null>(null);

  const {
    playTrack,
    currentTrack,
    isPlaying,
    isFavorite,
    toggleFavorite,
    isArtistSaved,
    saveArtist,
    removeArtist,
    isAlbumSaved,
    saveAlbum,
    removeAlbum,
  } = usePlayer();

  // Run the incoming query, or fall back to trending on a plain visit.
  useEffect(() => {
    if (initialQuery) {
      setQuery(initialQuery);
      setSelectedGenre(null);
      performSearch(initialQuery);
    } else {
      performSearch(DEFAULT_QUERY);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialQuery, queryNonce]);

  const performSearch = async (searchQuery: string) => {
    setIsLoading(true);
    setHasSearched(true);
    setError(null);
    setPlaylistError(null);
    setLastQuery(searchQuery);
    try {
      const res = await searchAll(searchQuery);
      setResults(res);
    } catch (e) {
      // No hardcoded fallback: report the real failure and clear stale results.
      setResults(EMPTY_RESULTS);
      setError(
        e instanceof SearchUnavailableError
          ? 'No search provider could be reached, so results are unavailable. Check your connection and try again.'
          : e instanceof Error
            ? e.message
            : 'Search failed for an unknown reason.'
      );
    } finally {
      setIsLoading(false);
    }
  };

  const handleGenreClick = (genreQuery: string, genreName: string) => {
    setSelectedGenre(genreName);
    setQuery(genreQuery);
    performSearch(genreQuery);
  };

  const handleArtistClick = (artist: Artist) => {
    setSelectedGenre(null);
    setQuery(artist.name);
    performSearch(artist.query);
  };

  const handlePlaylistClick = async (pl: PlaylistResult) => {
    if (openingPlaylistId) return; // one at a time
    setOpeningPlaylistId(pl.id);
    setPlaylistError(null);
    try {
      const resolved = await importYouTubePlaylist(pl.id);
      if (resolved && resolved.tracks.length > 0) {
        playTrack(resolved.tracks[0], resolved.tracks);
      } else {
        setPlaylistError(`Couldn't open “${pl.title}” — it may be private, empty, or unavailable right now.`);
      }
    } catch {
      setPlaylistError(`Couldn't open “${pl.title}”. Please try again.`);
    } finally {
      setOpeningPlaylistId(null);
    }
  };

  const formatDuration = (secs: number) => {
    // Providers do not always report a length. Show that honestly rather than 0:00.
    if (!secs || secs <= 0) return '--:--';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const { songs, artists, playlists } = results;
  const hasAnyResult = songs.length > 0 || artists.length > 0 || playlists.length > 0;

  return (
    <div className="space-y-8 pb-32 animate-in fade-in duration-300 text-[var(--text-primary)]">
      {/* Header & Search */}
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <div className="p-3 rounded-2xl bg-purple-500/10 border border-purple-500/20 text-purple-500 dark:text-purple-400">
            <Compass className="w-6 h-6" />
          </div>
          <div>
            <h1 className="font-display font-black text-3xl text-[var(--text-primary)]">Explore & Discover</h1>
            <p className="text-xs text-[var(--text-muted)]">
              Search songs, artists, and playlists across YouTube, and stream with synced lyrics
            </p>
          </div>
        </div>

        {/* Search Input */}
        <div className="relative max-w-xl">
          <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-[var(--text-muted)] pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && performSearch(query)}
            placeholder="Search any song, artist, album, or vibe..."
            className="w-full pl-12 pr-28 py-3.5 bg-[var(--bg-input)] hover:bg-[var(--bg-card-hover)] focus:bg-[var(--bg-input-focus)] text-sm text-[var(--text-primary)] placeholder-[var(--text-muted)] rounded-2xl border border-[var(--border-subtle)] focus:border-[var(--border-strong)] focus:outline-none focus:ring-2 focus:ring-purple-500/20 transition shadow-sm"
          />
          <button
            onClick={() => performSearch(query)}
            className="absolute right-2 top-1/2 -translate-y-1/2 px-4 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 text-xs font-bold text-white transition shadow-md cursor-pointer"
          >
            Search
          </button>
        </div>
      </div>

      {/* Genre Chips */}
      <div className="flex items-center gap-2 overflow-x-auto pb-2 scrollbar-none">
        <button
          onClick={() => {
            setSelectedGenre(null);
            setQuery('');
            performSearch(DEFAULT_QUERY);
          }}
          className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition cursor-pointer ${
            selectedGenre === null
              ? 'bg-purple-600 text-white shadow-md'
              : 'bg-[var(--bg-surface)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)]'
          }`}
        >
          All Top Charts
        </button>

        {GENRES.map((g) => (
          <button
            key={g.id}
            onClick={() => handleGenreClick(g.query, g.name)}
            className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition cursor-pointer ${
              selectedGenre === g.name
                ? 'bg-purple-600 text-white shadow-md'
                : 'bg-[var(--bg-surface)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)]'
            }`}
          >
            {g.name}
          </button>
        ))}
      </div>

      {/* A playlist failed to open — surfaced honestly, dismissable by the next action. */}
      {playlistError && (
        <div className="flex items-start gap-3 px-4 py-3 rounded-2xl bg-amber-500/10 border border-amber-500/25 text-amber-600 dark:text-amber-200 text-sm">
          <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-amber-500" />
          <span>{playlistError}</span>
        </div>
      )}

      {isLoading ? (
        <div className="flex items-center justify-center py-20 text-[var(--text-muted)] gap-3">
          <div className="w-8 h-8 rounded-full border-2 border-purple-500 border-t-transparent animate-spin" />
          <span className="text-sm font-medium">Searching...</span>
        </div>
      ) : error ? (
        /* ---- Real error state: no provider could be reached ---- */
        <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
          <div className="w-14 h-14 rounded-2xl bg-amber-500/10 border border-amber-500/25 flex items-center justify-center mb-4">
            <AlertTriangle className="w-7 h-7 text-amber-500" />
          </div>
          <h3 className="text-base font-bold text-[var(--text-primary)]">Search unavailable</h3>
          <p className="text-sm text-[var(--text-muted)] mt-1.5 max-w-md">{error}</p>
          <button
            onClick={() => performSearch(lastQuery || DEFAULT_QUERY)}
            className="mt-5 px-5 py-2.5 rounded-xl bg-purple-600 hover:bg-purple-500 text-xs font-bold text-white transition shadow-md flex items-center gap-2 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry</span>
          </button>
        </div>
      ) : !hasAnyResult && hasSearched ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Search className="w-10 h-10 text-[var(--text-muted)] opacity-50 mb-3" />
          <p className="text-sm text-[var(--text-muted)]">
            No results{lastQuery ? ` for “${lastQuery}”` : ''}. Try a different search term.
          </p>
        </div>
      ) : (
        <div className="space-y-10">
          {/* ---- Artists ---- */}
          {artists.length > 0 && (
            <section className="space-y-4">
              <h2 className="font-display font-bold text-xl text-[var(--text-primary)] flex items-center gap-2">
                <Users className="w-5 h-5 text-purple-500 dark:text-purple-400" />
                <span>Artists</span>
              </h2>
              <div className="flex gap-4 overflow-x-auto pb-2 scrollbar-none">
                {artists.map((artist) => {
                  const isSaved = isArtistSaved(artist.id);
                  return (
                    <div
                      key={artist.id}
                      className="group flex flex-col items-center gap-2 w-28 flex-shrink-0"
                    >
                      <button
                        onClick={() => handleArtistClick(artist)}
                        className="flex flex-col items-center gap-2 w-full focus:outline-none cursor-pointer"
                        title={`Search ${artist.name}`}
                      >
                        <div className="relative w-24 h-24 rounded-full overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] group-hover:border-purple-500/50 transition shadow-sm flex items-center justify-center">
                          <span className="text-2xl font-black text-[var(--text-muted)] select-none">
                            {artist.name.charAt(0).toUpperCase()}
                          </span>
                          {artist.thumbnail && (
                            <img
                              src={artist.thumbnail}
                              alt={artist.name}
                              className="absolute inset-0 w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                              onError={(e) => {
                                e.currentTarget.style.display = 'none';
                              }}
                            />
                          )}
                        </div>
                        <div className="text-center min-w-0 w-full">
                          <p className="text-xs font-bold text-[var(--text-primary)] truncate group-hover:text-purple-500 dark:group-hover:text-purple-300">
                            {artist.name}
                          </p>
                          {artist.subscribers && (
                            <p className="text-[10px] text-[var(--text-muted)] truncate">{artist.subscribers}</p>
                          )}
                        </div>
                      </button>

                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          if (isSaved) {
                            removeArtist(artist.id);
                          } else {
                            saveArtist(artist);
                          }
                        }}
                        className={`px-2.5 py-0.5 rounded-full text-[10px] font-bold border transition cursor-pointer ${
                          isSaved
                            ? 'bg-purple-600/25 text-purple-600 dark:text-purple-300 border-purple-500/40 hover:bg-rose-500/20 hover:text-rose-500 hover:border-rose-500/30'
                            : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
                        }`}
                        title={isSaved ? 'Remove from Library' : 'Save to Library'}
                      >
                        {isSaved ? 'Following' : '+ Follow'}
                      </button>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ---- Playlists & Albums ---- */}
          {playlists.length > 0 && (
            <section className="space-y-4">
              <h2 className="font-display font-bold text-xl text-[var(--text-primary)] flex items-center gap-2">
                <ListMusic className="w-5 h-5 text-purple-500 dark:text-purple-400" />
                <span>Playlists & Albums</span>
              </h2>
              <div className="flex gap-4 overflow-x-auto pb-2 scrollbar-none">
                {playlists.map((pl) => {
                  const opening = openingPlaylistId === pl.id;
                  const isSaved = isAlbumSaved(pl.id);
                  return (
                    <div
                      key={pl.id}
                      className="group relative flex flex-col gap-2 w-40 flex-shrink-0 text-left"
                    >
                      <button
                        onClick={() => handlePlaylistClick(pl)}
                        disabled={!!openingPlaylistId}
                        className="group flex flex-col gap-2 w-full text-left focus:outline-none disabled:opacity-60 cursor-pointer"
                        title={`Play “${pl.title}”`}
                      >
                        <div className="relative w-40 h-40 rounded-2xl overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] group-hover:border-purple-500/50 transition shadow-sm flex items-center justify-center">
                          {pl.thumbnail ? (
                            <img
                              src={pl.thumbnail}
                              alt={pl.title}
                              className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
                              onError={(e) => {
                                e.currentTarget.style.display = 'none';
                              }}
                            />
                          ) : (
                            <ListMusic className="w-10 h-10 text-[var(--text-muted)]" />
                          )}
                          <div
                            className={`absolute inset-0 bg-black/50 flex items-center justify-center transition-opacity ${
                              opening ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                            }`}
                          >
                            {opening ? (
                              <Loader2 className="w-7 h-7 text-white animate-spin" />
                            ) : (
                              <div className="p-3 rounded-full bg-purple-600 text-white shadow-lg">
                                <Play className="w-5 h-5 fill-current ml-0.5" />
                              </div>
                            )}
                          </div>
                        </div>
                      </button>

                      <div className="flex items-start justify-between min-w-0 gap-1">
                        <div className="min-w-0 flex-1">
                          <p className="text-xs font-bold text-[var(--text-primary)] truncate group-hover:text-purple-500 dark:group-hover:text-purple-300">
                            {pl.title}
                          </p>
                          <p className="text-[10px] text-[var(--text-muted)] truncate">
                            {pl.author ? pl.author : 'Playlist'}
                            {typeof pl.trackCount === 'number' ? ` · ${pl.trackCount} tracks` : ''}
                          </p>
                        </div>
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            if (isSaved) {
                              removeAlbum(pl.id);
                            } else {
                              saveAlbum(pl);
                            }
                          }}
                          className={`p-1.5 rounded-lg border transition flex-shrink-0 cursor-pointer ${
                            isSaved
                              ? 'bg-purple-600/30 text-purple-600 dark:text-purple-300 border-purple-500/50'
                              : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
                          }`}
                          title={isSaved ? 'Saved in Library' : 'Save to Library'}
                        >
                          <Bookmark className={`w-3.5 h-3.5 ${isSaved ? 'fill-current' : ''}`} />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ---- Songs ---- */}
          {songs.length > 0 && (
            <section className="space-y-4">
              <div className="flex items-center justify-between">
                <h2 className="font-display font-bold text-xl text-[var(--text-primary)] flex items-center gap-2">
                  <TrendingUp className="w-5 h-5 text-purple-500 dark:text-purple-400" />
                  <span>{selectedGenre ? `${selectedGenre} Hits` : 'Songs'}</span>
                </h2>
                <span className="text-xs text-[var(--text-muted)]">
                  {songs.length} {songs.length === 1 ? 'track' : 'tracks'}
                </span>
              </div>

              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
                {songs.map((track: Track) => {
                  const isCurrent = currentTrack?.id === track.id;
                  const favorite = isFavorite(track.id);

                  return (
                    <div
                      key={track.id}
                      onClick={() => playTrack(track, songs)}
                      className={`group relative flex flex-col p-4 rounded-3xl border transition-all duration-300 cursor-pointer ${
                        isCurrent
                          ? 'bg-purple-500/10 border-purple-500/40 shadow-xl'
                          : 'bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] border-[var(--border-subtle)] hover:border-[var(--border-medium)] shadow-sm hover:scale-[1.02]'
                      }`}
                    >
                      <div className="relative w-full aspect-square rounded-2xl overflow-hidden bg-[var(--bg-surface-elevated)] shadow-sm mb-3">
                        <img
                          src={track.thumbnail}
                          alt={track.title}
                          className="w-full h-full object-cover aspect-square group-hover:scale-105 transition-transform duration-500"
                          onError={(e) => {
                            const target = e.currentTarget;
                            if (!target.src.includes('hqdefault')) {
                              target.src = `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`;
                            }
                          }}
                        />

                        <div
                          className={`absolute inset-0 bg-black/40 flex items-center justify-center transition-opacity ${
                            isCurrent && isPlaying ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                          }`}
                        >
                          <div className="p-3 rounded-full bg-purple-600 text-white shadow-lg">
                            <Play className="w-6 h-6 fill-current ml-0.5" />
                          </div>
                        </div>
                      </div>

                      <div className="flex items-start justify-between gap-2 min-w-0">
                        <div className="min-w-0 flex-1">
                          <h3
                            className={`text-sm font-bold truncate ${
                              isCurrent ? 'text-purple-600 dark:text-purple-300' : 'text-[var(--text-primary)] group-hover:text-purple-500 dark:group-hover:text-purple-300'
                            }`}
                          >
                            {track.title}
                          </h3>
                          <p className="text-xs text-[var(--text-muted)] truncate mt-0.5">{track.artist}</p>
                        </div>

                        <div className="flex items-center flex-shrink-0">
                          <AddToPlaylistButton
                            track={track}
                            className="p-1.5 rounded-full hover:bg-[var(--bg-surface-hover)] transition text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                          />

                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              toggleFavorite(track);
                            }}
                            className="p-1.5 rounded-full hover:bg-[var(--bg-surface-hover)] transition text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                            title={favorite ? 'Remove from Liked' : 'Add to Liked'}
                          >
                            <Heart
                              className={`w-4 h-4 ${
                                favorite ? 'fill-red-500 text-red-500' : 'text-[var(--text-muted)]'
                              }`}
                            />
                          </button>
                        </div>
                      </div>

                      <div className="flex items-center justify-between mt-3 pt-2 border-t border-[var(--border-subtle)] text-[10px] font-mono text-[var(--text-muted)]">
                        <span>{formatDuration(track.duration)}</span>
                        {track.album && <span className="truncate ml-2">{track.album}</span>}
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  );
};
