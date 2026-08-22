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
    <div className="space-y-8 pb-32 animate-in fade-in duration-300 text-[var(--text-primary)]">
      {/* Header Banner */}
      <div className="flex flex-col sm:flex-row items-center sm:items-end gap-6 p-8 rounded-3xl bg-gradient-to-br from-rose-500/20 via-[var(--bg-card)] to-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] shadow-xl">
        <div className="w-44 h-44 rounded-3xl bg-gradient-to-br from-rose-500 to-pink-600 flex items-center justify-center text-white shadow-2xl flex-shrink-0">
          <Heart className="w-20 h-20 fill-current" />
        </div>

        <div className="space-y-2 text-center sm:text-left flex-1">
          <span className="text-xs font-bold uppercase tracking-wider text-rose-500">
            Playlist
          </span>
          <h1 className="font-display font-black text-4xl sm:text-5xl text-[var(--text-primary)]">Liked Songs</h1>
          <p className="text-sm text-[var(--text-muted)]">
            {favorites.length} songs • {Math.floor(totalDuration / 60)} minutes
          </p>

          {favorites.length > 0 && (
            <div className="flex items-center justify-center sm:justify-start gap-3 pt-4">
              <button
                onClick={handlePlayAll}
                className="flex items-center gap-2 px-6 py-3 rounded-full bg-rose-500 hover:bg-rose-600 text-white font-bold text-sm shadow-xl transition hover:scale-105 active:scale-95 cursor-pointer"
              >
                <Play className="w-4 h-4 fill-current" />
                <span>Play All</span>
              </button>

              <button
                onClick={handleShuffleAll}
                className="flex items-center gap-2 px-5 py-3 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-primary)] font-bold text-sm border border-[var(--border-subtle)] transition cursor-pointer shadow-sm"
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
          <Heart className="w-16 h-16 text-[var(--text-muted)] opacity-40 mb-4 stroke-1" />
          <h3 className="text-lg font-bold text-[var(--text-primary)]">No Liked Songs Yet</h3>
          <p className="text-xs text-[var(--text-muted)] max-w-sm mt-1">
            Tap the heart icon on any song to save it here for instant synchronized playback.
          </p>
        </div>
      ) : (
        <div className="rounded-3xl border border-[var(--border-subtle)] bg-[var(--bg-card)] overflow-hidden shadow-sm">
          <div className="p-2 space-y-1">
            {favorites.map((track, idx) => {
              const isCurrent = currentTrack?.id === track.id;

              return (
                <div
                  key={track.id}
                  onClick={() => playTrack(track, favorites)}
                  className={`flex items-center justify-between p-3 rounded-2xl cursor-pointer transition group ${
                    isCurrent
                      ? 'bg-purple-500/10 border border-purple-500/30'
                      : 'hover:bg-[var(--bg-card-hover)]'
                  }`}
                >
                  <div className="flex items-center gap-4 min-w-0 flex-1">
                    <span className="text-xs font-mono text-[var(--text-muted)] w-5 text-center">
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
                          isCurrent ? 'text-purple-600 dark:text-purple-300' : 'text-[var(--text-primary)] group-hover:text-purple-500 dark:group-hover:text-purple-300'
                        }`}
                      >
                        {track.title}
                      </p>
                      <p className="text-xs text-[var(--text-muted)] truncate">{track.artist}</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 sm:gap-4">
                    <span className="text-xs font-mono text-[var(--text-muted)]">
                      {formatDuration(track.duration)}
                    </span>

                    <AddToPlaylistButton
                      track={track}
                      className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
                    />

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        toggleFavorite(track);
                      }}
                      className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] text-rose-500 hover:text-rose-600 transition cursor-pointer"
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
