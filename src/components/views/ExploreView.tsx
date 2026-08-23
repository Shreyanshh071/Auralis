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
  Music2,
  AlertTriangle,
  RefreshCw,
  Users,
  ListMusic,
  ListEnd,
  Disc3,
  Loader2,
  Bookmark,
  X,
} from 'lucide-react';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';

const DEFAULT_QUERY = 'trending music 2025 top hits';

const EMPTY_RESULTS: SearchResults = { songs: [], artists: [], playlists: [] };

type SearchCategory = 'all' | 'songs' | 'artists' | 'albums' | 'playlists';

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
  const [activeCategory, setActiveCategory] = useState<SearchCategory>('all');
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
    addToQueue,
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
    const q = searchQuery.trim();
    if (!q) return;
    setIsLoading(true);
    setHasSearched(true);
    setError(null);
    setPlaylistError(null);
    setLastQuery(q);
    try {
      const res = await searchAll(q);
      setResults(res);
    } catch (e) {
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
    if (openingPlaylistId) return;
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
    if (!secs || secs <= 0) return '--:--';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const { songs, artists, playlists } = results;
  const hasAnyResult = songs.length > 0 || artists.length > 0 || playlists.length > 0;

  // Filter categories
  const showSongs = (activeCategory === 'all' || activeCategory === 'songs') && songs.length > 0;
  const showArtists = (activeCategory === 'all' || activeCategory === 'artists') && artists.length > 0;
  const showPlaylists = (activeCategory === 'all' || activeCategory === 'playlists' || activeCategory === 'albums') && playlists.length > 0;

  return (
    <div className="space-y-6 animate-in fade-in duration-300 text-[var(--text-primary)] max-w-5xl mx-auto">
      {/* Header & Search Bar */}
      <div className="space-y-3 pt-1">
        <div className="flex items-center gap-3">
          <div className="p-2.5 rounded-2xl bg-[var(--m3-primary-08)] border border-[var(--m3-outline-variant)] text-[var(--m3-primary)]">
            <Compass className="w-5 h-5" />
          </div>
          <div>
            <h1 className="font-display font-black text-2xl sm:text-3xl text-[var(--text-primary)] tracking-tight">
              Search &amp; Explore
            </h1>
            <p className="text-xs text-[var(--text-muted)]">
              Discover songs, artists, and playlists with synchronized lyrics
            </p>
          </div>
        </div>

        {/* Search Input Bar */}
        <div className="relative w-full max-w-2xl">
          <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-[var(--text-muted)] pointer-events-none" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && performSearch(query)}
            placeholder="Search songs, artists, albums, or vibes..."
            className="w-full pl-10 pr-24 py-2.5 sm:py-3 bg-[var(--bg-input)] hover:bg-[var(--bg-card-hover)] focus:bg-[var(--bg-input-focus)] text-xs sm:text-sm text-[var(--text-primary)] placeholder-[var(--text-muted)] rounded-2xl border border-[var(--border-subtle)] focus:border-[var(--border-strong)] focus:outline-none focus:ring-2 focus:ring-[var(--m3-primary-16)] transition shadow-sm"
          />
          {query && (
            <button
              onClick={() => {
                setQuery('');
                setResults(EMPTY_RESULTS);
                setHasSearched(false);
              }}
              className="absolute right-16 top-1/2 -translate-y-1/2 p-1 rounded-full text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
          <button
            onClick={() => performSearch(query)}
            className="absolute right-1.5 top-1/2 -translate-y-1/2 px-3 py-1.5 rounded-xl bg-[var(--text-primary)] text-[var(--text-inverse)] text-xs font-bold transition shadow-sm hover:opacity-90 active:scale-95 cursor-pointer"
          >
            Search
          </button>
        </div>
      </div>

      {/* Category Filter Pills (Music-First: All, Songs, Artists, Albums, Playlists) */}
      <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none flex-nowrap">
        {(
          [
            { id: 'all', label: 'All' },
            { id: 'songs', label: 'Songs' },
            { id: 'artists', label: 'Artists' },
            { id: 'albums', label: 'Albums' },
            { id: 'playlists', label: 'Playlists' },
          ] as const
        ).map((cat) => {
          const isActive = activeCategory === cat.id;
          return (
            <button
              key={cat.id}
              onClick={() => setActiveCategory(cat.id)}
              className={`px-4 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap transition cursor-pointer flex-shrink-0 ${
                isActive
                  ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] font-bold shadow-sm'
                  : 'bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)]'
              }`}
            >
              {cat.label}
            </button>
          );
        })}
      </div>

      {/* Genre Tags (Quick discovery presets) */}
      <div className="flex items-center gap-1.5 overflow-x-auto pb-1 scrollbar-none">
        <button
          onClick={() => {
            setSelectedGenre(null);
            setQuery('');
            performSearch(DEFAULT_QUERY);
          }}
          className={`px-3 py-1 rounded-full text-[11px] font-medium whitespace-nowrap transition cursor-pointer ${
            selectedGenre === null && query === DEFAULT_QUERY
              ? 'bg-[var(--m3-primary-16)] text-[var(--m3-primary)] border border-[var(--m3-primary-40)]'
              : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)]'
          }`}
        >
          Top Hits
        </button>

        {GENRES.map((g) => (
          <button
            key={g.id}
            onClick={() => handleGenreClick(g.query, g.name)}
            className={`px-3 py-1 rounded-full text-[11px] font-medium whitespace-nowrap transition cursor-pointer ${
              selectedGenre === g.name
                ? 'bg-[var(--m3-primary-16)] text-[var(--m3-primary)] border border-[var(--m3-primary-40)]'
                : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)]'
            }`}
          >
            {g.name}
          </button>
        ))}
      </div>

      {/* Playlist open error notification */}
      {playlistError && (
        <div className="flex items-start gap-2.5 px-3.5 py-2.5 rounded-2xl bg-amber-500/10 border border-amber-500/25 text-amber-600 dark:text-amber-200 text-xs">
          <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-amber-500" />
          <span>{playlistError}</span>
        </div>
      )}

      {/* Loading state */}
      {isLoading ? (
        <div className="flex items-center justify-center py-20 text-[var(--text-muted)] gap-3">
          <div className="w-7 h-7 rounded-full border-2 border-[var(--m3-primary)] border-t-transparent animate-spin" />
          <span className="text-xs font-medium">Finding songs...</span>
        </div>
      ) : error ? (
        /* Error State */
        <div className="flex flex-col items-center justify-center py-16 px-6 text-center">
          <div className="w-12 h-12 rounded-2xl bg-amber-500/10 border border-amber-500/25 flex items-center justify-center mb-3">
            <AlertTriangle className="w-6 h-6 text-amber-500" />
          </div>
          <h3 className="text-sm font-bold text-[var(--text-primary)]">Search unavailable</h3>
          <p className="text-xs text-[var(--text-muted)] mt-1 max-w-md">{error}</p>
          <button
            onClick={() => performSearch(lastQuery || DEFAULT_QUERY)}
            className="mt-4 px-4 py-2 rounded-xl bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-xs font-bold text-[var(--m3-on-primary)] transition shadow-sm flex items-center gap-2 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            <span>Retry Search</span>
          </button>
        </div>
      ) : !hasAnyResult && hasSearched ? (
        /* Empty State */
        <div className="flex flex-col items-center justify-center py-20 text-center space-y-2">
          <Search className="w-8 h-8 text-[var(--text-muted)] opacity-40" />
          <p className="text-xs font-medium text-[var(--text-muted)]">
            No music found for “{lastQuery}”. Try a different title or artist.
          </p>
        </div>
      ) : (
        /* Music Results */
        <div className="space-y-8">
          {/* ---- Artists Section ---- */}
          {showArtists && (
            <section className="space-y-3">
              <div className="flex items-center justify-between">
                <h2 className="font-display font-bold text-base sm:text-lg text-[var(--text-primary)] flex items-center gap-2">
                  <Users className="w-4 h-4 text-[var(--m3-primary)]" />
                  <span>Artists</span>
                </h2>
              </div>

              <div className="flex gap-3 overflow-x-auto pb-2 scrollbar-none">
                {artists.map((artist) => {
                  const isSaved = isArtistSaved(artist.id);
                  return (
                    <div
                      key={artist.id}
                      className="group flex flex-col items-center gap-1.5 w-24 sm:w-28 flex-shrink-0"
                    >
                      <button
                        onClick={() => handleArtistClick(artist)}
                        className="flex flex-col items-center gap-1.5 w-full focus:outline-none cursor-pointer"
                        title={`Search ${artist.name}`}
                      >
                        <div className="relative w-20 h-20 sm:w-24 sm:h-24 rounded-full overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] group-hover:border-[var(--m3-primary-40)] transition shadow-sm flex items-center justify-center">
                          <span className="text-xl font-black text-[var(--text-muted)] select-none">
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
                          <p className="text-xs font-bold text-[var(--text-primary)] truncate group-hover:text-[var(--m3-primary)]">
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
                            ? 'bg-[var(--m3-primary-24)] text-[var(--m3-primary)] border-[var(--m3-primary-40)]'
                            : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
                        }`}
                      >
                        {isSaved ? 'Following' : '+ Follow'}
                      </button>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ---- Playlists & Albums Section ---- */}
          {showPlaylists && (
            <section className="space-y-3">
              <div className="flex items-center justify-between">
                <h2 className="font-display font-bold text-base sm:text-lg text-[var(--text-primary)] flex items-center gap-2">
                  <ListMusic className="w-4 h-4 text-[var(--m3-primary)]" />
                  <span>Albums &amp; Playlists</span>
                </h2>
              </div>

              <div className="flex gap-3.5 overflow-x-auto pb-2 scrollbar-none">
                {playlists.map((pl) => {
                  const opening = openingPlaylistId === pl.id;
                  const isSaved = isAlbumSaved(pl.id);
                  return (
                    <div
                      key={pl.id}
                      className="group relative flex flex-col gap-1.5 w-32 sm:w-36 flex-shrink-0 text-left"
                    >
                      <button
                        onClick={() => handlePlaylistClick(pl)}
                        disabled={!!openingPlaylistId}
                        className="group flex flex-col gap-1.5 w-full text-left focus:outline-none disabled:opacity-60 cursor-pointer"
                        title={`Play “${pl.title}”`}
                      >
                        <div className="relative w-32 h-32 sm:w-36 sm:h-36 rounded-2xl overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] group-hover:border-[var(--m3-primary-40)] transition shadow-sm flex items-center justify-center">
                          {pl.thumbnail ? (
                            <img
                              src={pl.thumbnail}
                              alt={pl.title}
                              className="w-full h-full object-cover group-hover:scale-105 transition duration-300"
                              onError={(e) => {
                                e.currentTarget.style.display = 'none';
                              }}
                            />
                          ) : (
                            <Disc3 className="w-8 h-8 text-[var(--text-muted)]" />
                          )}
                          <div
                            className={`absolute inset-0 bg-black/45 flex items-center justify-center transition-opacity ${
                              opening ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                            }`}
                          >
                            {opening ? (
                              <Loader2 className="w-6 h-6 text-white animate-spin" />
                            ) : (
                              <div className="p-2.5 rounded-full bg-[var(--m3-primary)] text-[var(--m3-on-primary)] shadow-md">
                                <Play className="w-4 h-4 fill-current ml-0.5" />
                              </div>
                            )}
                          </div>
                        </div>
                      </button>

                      <div className="flex items-start justify-between min-w-0 gap-1">
                        <div className="min-w-0 flex-1">
                          <p className="text-xs font-bold text-[var(--text-primary)] truncate group-hover:text-[var(--m3-primary)]">
                            {pl.title}
                          </p>
                          <p className="text-[10px] text-[var(--text-muted)] truncate">
                            {pl.author || 'Album'}
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
                          className={`p-1 rounded-lg border transition flex-shrink-0 cursor-pointer ${
                            isSaved
                              ? 'bg-[var(--m3-primary-24)] text-[var(--m3-primary)] border-[var(--m3-primary-40)]'
                              : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
                          }`}
                          title={isSaved ? 'Saved in Library' : 'Save to Library'}
                        >
                          <Bookmark className={`w-3 h-3 ${isSaved ? 'fill-current' : ''}`} />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* ---- Compact Songs List (Music-First, High-Density) ---- */}
          {showSongs && (
            <section className="space-y-3">
              <div className="flex items-center justify-between">
                <h2 className="font-display font-bold text-base sm:text-lg text-[var(--text-primary)] flex items-center gap-2">
                  <Music2 className="w-4 h-4 text-[var(--m3-primary)]" />
                  <span>{selectedGenre ? `${selectedGenre} Songs` : 'Songs'}</span>
                </h2>
                <span className="text-[11px] font-mono text-[var(--text-muted)]">
                  {songs.length} {songs.length === 1 ? 'song' : 'songs'}
                </span>
              </div>

              {/* Compact Vertical Song Rows (Screenshots 1 & 2 layout) */}
              <div className="divide-y divide-[var(--border-subtle)] bg-[var(--bg-card)] rounded-2xl border border-[var(--border-subtle)] overflow-hidden shadow-sm">
                {songs.map((track, idx) => {
                  const isCurrent = currentTrack?.id === track.id;
                  const favorite = isFavorite(track.id);

                  return (
                    <div
                      key={track.id}
                      onClick={() => playTrack(track, songs)}
                      className={`group flex items-center justify-between gap-2.5 sm:gap-4 px-3 py-2 sm:px-4 sm:py-2.5 transition cursor-pointer ${
                        isCurrent
                          ? 'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]'
                          : 'hover:bg-[var(--bg-surface-hover)]'
                      }`}
                    >
                      {/* Left: Thumbnail & Title/Artist */}
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        {/* Artwork with quick-play hover state */}
                        <div className="relative w-10 h-10 sm:w-11 sm:h-11 rounded-xl overflow-hidden bg-[var(--bg-surface-elevated)] flex-shrink-0 shadow-sm">
                          <img
                            src={track.thumbnail}
                            alt={track.title}
                            loading="lazy"
                            className="w-full h-full object-cover group-hover:scale-105 transition duration-200"
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
                            {isCurrent && isPlaying ? (
                              <div className="flex items-end gap-0.5 h-3">
                                <span className="w-0.5 bg-white rounded-full animate-bounce" style={{ height: '70%' }} />
                                <span className="w-0.5 bg-white rounded-full animate-bounce [animation-delay:0.15s]" style={{ height: '100%' }} />
                                <span className="w-0.5 bg-white rounded-full animate-bounce [animation-delay:0.3s]" style={{ height: '50%' }} />
                              </div>
                            ) : (
                              <Play className="w-4 h-4 text-white fill-current ml-0.5" />
                            )}
                          </div>
                        </div>

                        {/* Title & Artist */}
                        <div className="min-w-0 flex-1">
                          <p
                            className={`text-xs sm:text-sm font-bold truncate ${
                              isCurrent
                                ? 'text-[var(--m3-primary)]'
                                : 'text-[var(--text-primary)] group-hover:text-[var(--m3-primary)] transition'
                            }`}
                          >
                            {track.title}
                          </p>
                          <div className="flex items-center gap-1.5 text-[11px] text-[var(--text-muted)] truncate mt-0.5">
                            <span className="truncate">{track.artist}</span>
                            {track.album && (
                              <>
                                <span className="text-[var(--text-subtle)]">•</span>
                                <span className="truncate hidden sm:inline">{track.album}</span>
                              </>
                            )}
                          </div>
                        </div>
                      </div>

                      {/* Right: Duration & Actions */}
                      <div className="flex items-center gap-0.5 sm:gap-2 flex-shrink-0">
                        <span className="text-[11px] font-mono text-[var(--text-muted)] pr-1 sm:pr-2">
                          {formatDuration(track.duration)}
                        </span>

                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            addToQueue(track);
                          }}
                          className="p-1.5 rounded-full hover:bg-[var(--bg-surface-elevated)] transition text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                          title="Add to queue"
                          aria-label="Add to queue"
                        >
                          <ListEnd className="w-3.5 h-3.5" />
                        </button>

                        <AddToPlaylistButton
                          track={track}
                          className="p-1.5 rounded-full hover:bg-[var(--bg-surface-elevated)] transition text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                        />

                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            toggleFavorite(track);
                          }}
                          className="p-1.5 rounded-full hover:bg-[var(--bg-surface-elevated)] transition text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                          title={favorite ? 'Remove from Liked' : 'Add to Liked'}
                          aria-label={favorite ? 'Remove from Liked' : 'Add to Liked'}
                        >
                          <Heart
                            className={`w-3.5 h-3.5 ${
                              favorite ? 'fill-rose-500 text-rose-500' : 'text-[var(--text-muted)]'
                            }`}
                          />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>
            </section>
          )}

          {/* Category-specific empty state if user selects a tab with 0 matches */}
          {activeCategory === 'artists' && artists.length === 0 && (
            <div className="py-12 text-center text-xs text-[var(--text-muted)]">
              No artist matches found for “{lastQuery}”.
            </div>
          )}
          {activeCategory === 'songs' && songs.length === 0 && (
            <div className="py-12 text-center text-xs text-[var(--text-muted)]">
              No song matches found for “{lastQuery}”.
            </div>
          )}
          {(activeCategory === 'albums' || activeCategory === 'playlists') && playlists.length === 0 && (
            <div className="py-12 text-center text-xs text-[var(--text-muted)]">
              No album or playlist matches found for “{lastQuery}”.
            </div>
          )}
        </div>
      )}
    </div>
  );
};
