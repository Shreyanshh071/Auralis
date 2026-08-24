import React from 'react';
import { usePlayer } from '../../context/PlayerContext';
import { Play, Heart, Shuffle } from 'lucide-react';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';
import { isLetterboxedThumbnail } from '../../services/artwork';

export const FavoritesView: React.FC = () => {
  const {
    favorites,
    playTrack,
    currentTrack,
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
    <div className="space-y-6 max-w-2xl mx-auto animate-in fade-in duration-300 text-[var(--text-primary)]">
      {/* Header Banner */}
      <div className="flex flex-col sm:flex-row items-center sm:items-end gap-5 sm:gap-6 p-5 sm:p-8 rounded-3xl bg-gradient-to-br from-rose-500/20 via-[var(--bg-card)] to-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] shadow-xl">
        <div className="w-32 h-32 sm:w-40 sm:h-40 rounded-3xl bg-gradient-to-br from-rose-500 to-pink-600 flex items-center justify-center text-white shadow-2xl flex-shrink-0">
          <Heart className="w-14 h-14 sm:w-16 sm:h-16 fill-current" />
        </div>

        <div className="space-y-1.5 text-center sm:text-left flex-1 min-w-0">
          <span className="text-[10px] font-bold uppercase tracking-wider text-rose-500">
            Playlist
          </span>
          <h1 className="font-display font-bold text-2xl sm:text-3xl text-[var(--text-primary)] tracking-tight">
            Liked Songs
          </h1>
          <p className="text-xs text-[var(--text-muted)]">
            {favorites.length} songs • {Math.floor(totalDuration / 60)} minutes
          </p>

          {favorites.length > 0 && (
            <div className="flex items-center justify-center sm:justify-start gap-2.5 pt-3">
              <button
                onClick={handlePlayAll}
                className="flex items-center gap-2 px-5 py-2 rounded-full bg-rose-500 hover:bg-rose-600 text-white font-bold text-xs shadow-lg transition active:scale-95 cursor-pointer"
              >
                <Play className="w-3.5 h-3.5 fill-current" />
                <span>Play All</span>
              </button>

              <button
                onClick={handleShuffleAll}
                className="flex items-center gap-2 px-4 py-2 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-primary)] font-bold text-xs border border-[var(--border-subtle)] transition active:scale-95 cursor-pointer shadow-sm"
              >
                <Shuffle className="w-3.5 h-3.5" />
                <span>Shuffle</span>
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Tracklist Table */}
      {favorites.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center space-y-2">
          <Heart className="w-12 h-12 text-[var(--text-muted)] opacity-40 stroke-1" />
          <h3 className="font-bold text-sm text-[var(--text-primary)]">No Liked Songs Yet</h3>
          <p className="text-xs text-[var(--text-muted)] max-w-xs">
            Tap the heart icon on any song to save it here for instant synchronized playback.
          </p>
        </div>
      ) : (
        <div className="divide-y divide-[var(--border-subtle)] rounded-2xl border border-[var(--border-subtle)] bg-[var(--bg-card)] overflow-hidden shadow-sm">
          {favorites.map((track, idx) => {
            const isCurrent = currentTrack?.id === track.id;

            return (
              <div
                key={track.id}
                onClick={() => playTrack(track, favorites)}
                className={`flex items-center justify-between gap-2.5 sm:gap-4 px-3 py-2 sm:px-4 sm:py-2.5 cursor-pointer transition group ${
                  isCurrent
                    ? 'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]'
                    : 'hover:bg-[var(--bg-surface-hover)]'
                }`}
              >
                <div className="flex items-center gap-3 min-w-0 flex-1">
                  <span className="text-[11px] font-mono text-[var(--text-muted)] w-4 text-center flex-shrink-0">
                    {idx + 1}
                  </span>

                  <div className="w-10 h-10 sm:w-11 sm:h-11 rounded-xl overflow-hidden bg-[var(--bg-surface-elevated)] shadow-sm flex-shrink-0">
                    <img
                      src={track.thumbnail}
                      alt=""
                      loading="lazy"
                      className={`w-full h-full object-cover aspect-square ${
                        isLetterboxedThumbnail(track.thumbnail) ? 'scale-[1.35]' : 'scale-100'
                      }`}
                      onError={(e) => {
                        const target = e.currentTarget;
                        if (!target.src.includes('hqdefault')) {
                          target.src = `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`;
                        }
                      }}
                    />
                  </div>

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
                    <p className="text-[11px] text-[var(--text-muted)] truncate mt-0.5">{track.artist}</p>
                  </div>
                </div>

                <div className="flex items-center gap-1 sm:gap-3 flex-shrink-0">
                  <span className="text-[11px] font-mono text-[var(--text-muted)] pr-1 sm:pr-2">
                    {formatDuration(track.duration)}
                  </span>

                  <AddToPlaylistButton
                    track={track}
                    className="p-1.5 rounded-full hover:bg-[var(--bg-surface-elevated)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
                  />

                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      toggleFavorite(track);
                    }}
                    className="p-1.5 rounded-full hover:bg-[var(--bg-surface-elevated)] text-rose-500 hover:text-rose-600 transition cursor-pointer"
                    title="Remove from Liked"
                    aria-label="Remove from Liked"
                  >
                    <Heart className="w-3.5 h-3.5 fill-current" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
