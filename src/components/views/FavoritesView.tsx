import React from 'react';
import { usePlayer } from '../../context/PlayerContext';
import { Play, Heart, Shuffle, Music } from 'lucide-react';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';

export const FavoritesView: React.FC = () => {
  const {
    favorites,
    playTrack,
    currentTrack,
    isPlaying,
    toggleFavorite,
  } = usePlayer();

  const handlePlayAll = () => {
    if (favorites.length === 0) return;
    playTrack(favorites[0], favorites);
  };

  const handleShuffleAll = () => {
    if (favorites.length === 0) return;
    const shuffled = [...favorites].sort(() => Math.random() - 0.5);
    playTrack(shuffled[0], shuffled);
  };

  const formatDuration = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const totalDuration = favorites.reduce((acc, t) => acc + (t.duration || 0), 0);

  return (
    <div className="space-y-8 pb-32 animate-in fade-in duration-300">
      {/* Header Banner */}
      <div className="flex flex-col sm:flex-row items-center sm:items-end gap-6 p-8 rounded-3xl bg-gradient-to-br from-red-600/30 via-neutral-900/80 to-neutral-950 border border-white/10 shadow-2xl">
        <div className="w-44 h-44 rounded-3xl bg-gradient-to-br from-red-500 to-pink-600 flex items-center justify-center text-white shadow-2xl flex-shrink-0">
          <Heart className="w-20 h-20 fill-current" />
        </div>

        <div className="space-y-2 text-center sm:text-left flex-1">
          <span className="text-xs font-bold uppercase tracking-wider text-red-400">
            Playlist
          </span>
          <h1 className="font-display font-black text-4xl sm:text-5xl text-white">Liked Songs</h1>
          <p className="text-sm text-neutral-400">
            {favorites.length} songs • {Math.floor(totalDuration / 60)} minutes
          </p>

          {favorites.length > 0 && (
            <div className="flex items-center justify-center sm:justify-start gap-3 pt-4">
              <button
                onClick={handlePlayAll}
                className="flex items-center gap-2 px-6 py-3 rounded-full bg-red-500 hover:bg-red-400 text-white font-bold text-sm shadow-xl transition hover:scale-105 active:scale-95"
              >
                <Play className="w-4 h-4 fill-current" />
                <span>Play All</span>
              </button>

              <button
                onClick={handleShuffleAll}
                className="flex items-center gap-2 px-5 py-3 rounded-full bg-white/10 hover:bg-white/15 text-white font-bold text-sm border border-white/10 transition"
              >
                <Shuffle className="w-4 h-4" />
                <span>Shuffle</span>
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Tracklist Table */}
      {favorites.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-20 text-center">
          <Heart className="w-16 h-16 text-neutral-600 mb-4 stroke-1" />
          <h3 className="text-lg font-bold text-neutral-300">No Liked Songs Yet</h3>
          <p className="text-xs text-neutral-500 max-w-sm mt-1">
            Tap the heart icon on any song to save it here for instant synchronized playback.
          </p>
        </div>
      ) : (
        <div className="rounded-3xl border border-white/5 bg-neutral-900/30 overflow-hidden">
          <div className="p-2 space-y-1">
            {favorites.map((track, idx) => {
              const isCurrent = currentTrack?.id === track.id;

              return (
                <div
                  key={track.id}
                  onClick={() => playTrack(track, favorites)}
                  className={`flex items-center justify-between p-3 rounded-2xl cursor-pointer transition group ${
                    isCurrent
                      ? 'bg-purple-600/20 border border-purple-500/30'
                      : 'hover:bg-white/5'
                  }`}
                >
                  <div className="flex items-center gap-4 min-w-0 flex-1">
                    <span className="text-xs font-mono text-neutral-500 w-5 text-center">
                      {idx + 1}
                    </span>

                    <img
                      src={track.thumbnail}
                      alt={track.title}
                      className="w-11 h-11 rounded-xl object-cover bg-neutral-800 shadow-sm flex-shrink-0"
                    />

                    <div className="min-w-0 flex-1">
                      <p
                        className={`text-sm font-semibold truncate ${
                          isCurrent ? 'text-purple-300' : 'text-neutral-200 group-hover:text-white'
                        }`}
                      >
                        {track.title}
                      </p>
                      <p className="text-xs text-neutral-400 truncate">{track.artist}</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 sm:gap-4">
                    <span className="text-xs font-mono text-neutral-500">
                      {formatDuration(track.duration)}
                    </span>

                    <AddToPlaylistButton
                      track={track}
                      className="p-2 rounded-full hover:bg-white/10 text-neutral-400 hover:text-white transition"
                    />

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleFavorite(track);
                      }}
                      className="p-2 rounded-full hover:bg-white/10 text-red-500 hover:text-red-400 transition"
                      title="Remove from favorites"
                    >
                      <Heart className="w-4 h-4 fill-current" />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
};
