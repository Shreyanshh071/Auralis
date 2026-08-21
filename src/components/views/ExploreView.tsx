import React, { useState, useEffect } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import { GENRES, searchYouTube } from '../../services/youtube';
import type { Track } from '../../types/music';
import { Play, Search, Heart, Compass, TrendingUp } from 'lucide-react';


interface ExploreViewProps {
  initialQuery?: string;
}

export const ExploreView: React.FC<ExploreViewProps> = ({ initialQuery = '' }) => {
  const [query, setQuery] = useState(initialQuery);
  const [tracks, setTracks] = useState<Track[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [selectedGenre, setSelectedGenre] = useState<string | null>(null);
  const [hasSearched, setHasSearched] = useState(false);

  const { playTrack, currentTrack, isPlaying, isFavorite, toggleFavorite } = usePlayer();

  // Auto-search trending on mount if no initial query
  useEffect(() => {
    if (initialQuery) {
      setQuery(initialQuery);
      performSearch(initialQuery);
    } else {
      performSearch('trending music 2025 top hits');
    }
  }, [initialQuery]);

  const performSearch = async (searchQuery: string) => {
    setIsLoading(true);
    setHasSearched(true);
    try {
      const res = await searchYouTube(searchQuery);
      setTracks(res);
    } catch (e) {
      console.error(e);
    } finally {
      setIsLoading(false);
    }
  };

  const handleGenreClick = (genreQuery: string, genreName: string) => {
    setSelectedGenre(genreName);
    setQuery(genreQuery);
    performSearch(genreQuery);
  };

  const formatDuration = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  return (
    <div className="space-y-8 pb-32 animate-in fade-in duration-300">
      {/* Header & Search */}
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <div className="p-3 rounded-2xl bg-purple-500/10 border border-purple-500/20 text-purple-400">
            <Compass className="w-6 h-6" />
          </div>
          <div>
            <h1 className="font-display font-black text-3xl text-white">Explore & Discover</h1>
            <p className="text-xs text-neutral-400">
              Browse global YouTube charts, curated genre stations, and stream with synced lyrics
            </p>
          </div>
        </div>

        {/* Search Input */}
        <div className="relative max-w-xl">
          <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-5 h-5 text-neutral-400" />
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && performSearch(query)}
            placeholder="Search any song, artist, album, or vibe..."
            className="w-full pl-12 pr-28 py-3.5 bg-neutral-900/80 hover:bg-neutral-900 text-sm text-neutral-100 placeholder-neutral-500 rounded-2xl border border-white/10 focus:border-purple-500 focus:outline-none focus:ring-2 focus:ring-purple-500/20 transition shadow-lg"
          />
          <button
            onClick={() => performSearch(query)}
            className="absolute right-2 top-1/2 -translate-y-1/2 px-4 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 text-xs font-bold text-white transition shadow-md"
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
            performSearch('trending music 2025 top hits');
          }}
          className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition ${
            selectedGenre === null
              ? 'bg-purple-600 text-white shadow-md'
              : 'bg-white/5 hover:bg-white/10 text-neutral-400 hover:text-white border border-white/5'
          }`}
        >
          All Top Charts
        </button>

        {GENRES.map((g) => (
          <button
            key={g.id}
            onClick={() => handleGenreClick(g.query, g.name)}
            className={`px-4 py-2 rounded-xl text-xs font-bold whitespace-nowrap transition ${
              selectedGenre === g.name
                ? 'bg-purple-600 text-white shadow-md'
                : 'bg-white/5 hover:bg-white/10 text-neutral-400 hover:text-white border border-white/5'
            }`}
          >
            {g.name}
          </button>
        ))}
      </div>

      {/* Results Section */}
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="font-display font-bold text-xl text-white flex items-center gap-2">
            <TrendingUp className="w-5 h-5 text-purple-400" />
            <span>{selectedGenre ? `${selectedGenre} Hits` : 'Popular Tracks & Charts'}</span>
          </h2>
          {tracks.length > 0 && (
            <span className="text-xs text-neutral-500">{tracks.length} tracks found</span>
          )}
        </div>

        {isLoading ? (
          <div className="flex items-center justify-center py-20 text-neutral-400 gap-3">
            <div className="w-8 h-8 rounded-full border-2 border-purple-500 border-t-transparent animate-spin" />
            <span className="text-sm font-medium">Fetching YouTube streams...</span>
          </div>
        ) : tracks.length === 0 && hasSearched ? (
          <div className="flex flex-col items-center justify-center py-20 text-center">
            <Search className="w-10 h-10 text-neutral-600 mb-3" />
            <p className="text-sm text-neutral-400">No results found. Try a different search term.</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
            {tracks.map((track) => {
              const isCurrent = currentTrack?.id === track.id;
              const favorite = isFavorite(track.id);

              return (
                <div
                  key={track.id}
                  onClick={() => playTrack(track, tracks)}
                  className={`group relative flex flex-col p-4 rounded-3xl border transition-all duration-300 cursor-pointer ${
                    isCurrent
                      ? 'bg-purple-600/15 border-purple-500/40 shadow-xl'
                      : 'bg-neutral-900/40 hover:bg-white/10 border-white/5 hover:border-white/15 hover:scale-[1.02]'
                  }`}
                >
                  <div className="relative w-full aspect-square rounded-2xl overflow-hidden bg-neutral-800 shadow-md mb-3">
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
                          isCurrent ? 'text-purple-300' : 'text-white group-hover:text-purple-200'
                        }`}
                      >
                        {track.title}
                      </h3>
                      <p className="text-xs text-neutral-400 truncate mt-0.5">{track.artist}</p>
                    </div>

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleFavorite(track);
                      }}
                      className="p-1.5 rounded-full hover:bg-white/10 transition text-neutral-400 hover:text-white"
                    >
                      <Heart
                        className={`w-4 h-4 ${
                          favorite ? 'fill-red-500 text-red-500' : 'text-neutral-400'
                        }`}
                      />
                    </button>
                  </div>

                  <div className="flex items-center justify-between mt-3 pt-2 border-t border-white/5 text-[10px] font-mono text-neutral-500">
                    <span>{formatDuration(track.duration)}</span>
                    <span>{track.views || 'YouTube'}</span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};
