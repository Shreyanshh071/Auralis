import React, { useState } from 'react';
import { ListPlus, Check, Plus, X } from 'lucide-react';
import { usePlayer } from '../../context/PlayerContext';
import type { Track } from '../../types/music';

interface AddToPlaylistButtonProps {
  track: Track;
  /** Trigger button classes, so each view keeps its own palette. */
  className?: string;
  /** Trigger icon classes. */
  iconClassName?: string;
}

/**
 * Puts a single track into one of the user's playlists.
 *
 * This is the caller `addToPlaylist` never had. The function existed in
 * PlayerContext and was exposed on the context, but nothing in the UI invoked
 * it, so a playlist created in the app could never be filled.
 *
 * The picker is bundled with its trigger and owns its own open state, so a track
 * row can drop it in as a single element without lifting anything.
 */
export const AddToPlaylistButton: React.FC<AddToPlaylistButtonProps> = ({
  track,
  className,
  iconClassName,
}) => {
  const { playlists, addToPlaylist, createPlaylist } = usePlayer();

  const [isOpen, setIsOpen] = useState<boolean>(false);
  const [isNaming, setIsNaming] = useState<boolean>(false);
  const [newTitle, setNewTitle] = useState<string>('');

  const close = () => {
    setIsOpen(false);
    setIsNaming(false);
    setNewTitle('');
  };

  const handleCreate = (e: React.FormEvent) => {
    e.preventDefault();
    e.stopPropagation();
    const title = newTitle.trim();
    if (!title) return;

    // Created with the track already in it. A separate addToPlaylist call could
    // not work here: it resolves the playlist from the list rendered before this
    // click, which does not yet contain the new one.
    createPlaylist(title, undefined, [track]);
    close();
  };

  const triggerClasses =
    className ?? 'p-2 rounded-full hover:bg-white/10 transition text-current';

  return (
    <>
      <button
        type="button"
        onClick={(e) => {
          e.stopPropagation();
          setIsOpen(true);
        }}
        className={triggerClasses}
        title="Add to playlist"
        aria-label="Add to playlist"
      >
        <ListPlus className={iconClassName ?? 'w-4 h-4'} />
      </button>

      {isOpen && (
        // Above the now-playing and library overlays, both of which sit at z-50.
        <div
          className="fixed inset-0 z-[60] flex items-end sm:items-center justify-center p-0 sm:p-4 bg-black/85 backdrop-blur-md animate-in fade-in"
          onClick={(e) => {
            e.stopPropagation();
            close();
          }}
        >
          <div
            onClick={(e) => e.stopPropagation()}
            className="relative w-full sm:max-w-sm max-h-[80vh] rounded-t-3xl sm:rounded-3xl bg-[#14180e] border border-[#2c3720] p-5 shadow-2xl flex flex-col"
          >
            <div className="flex items-start justify-between gap-3 pb-3 border-b border-white/[0.05]">
              <div className="min-w-0">
                <h3 className="font-bold text-base text-[#f0f4dc]">Add to playlist</h3>
                <p className="text-[11px] text-[#8f9b75] truncate mt-0.5">{track.title}</p>
              </div>
              <button
                type="button"
                onClick={close}
                className="p-1 rounded-full text-[#8f9b75] hover:text-white transition flex-shrink-0"
                title="Close"
                aria-label="Close"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto py-2 space-y-1 min-h-0">
              {playlists.length === 0 ? (
                <p className="px-1 py-4 text-xs text-[#8f9b75] leading-relaxed">
                  You have no playlists yet. Create one below and this song goes straight into it.
                </p>
              ) : (
                playlists.map((pl) => {
                  const alreadyIn = pl.tracks.some((t) => t.id === track.id);
                  return (
                    <button
                      type="button"
                      key={pl.id}
                      disabled={alreadyIn}
                      onClick={(e) => {
                        e.stopPropagation();
                        addToPlaylist(pl.id, track);
                        close();
                      }}
                      className={`w-full flex items-center justify-between gap-3 px-3 py-2.5 rounded-xl text-left transition ${
                        alreadyIn
                          ? 'bg-[#1b2214] cursor-default'
                          : 'hover:bg-[#1f2615] cursor-pointer'
                      }`}
                    >
                      <span className="min-w-0">
                        <span className="block text-xs font-semibold text-[#f0f4dc] truncate">
                          {pl.title}
                        </span>
                        <span className="block text-[11px] text-[#8f9b75]">
                          {pl.tracks.length} {pl.tracks.length === 1 ? 'song' : 'songs'}
                        </span>
                      </span>
                      {alreadyIn ? (
                        <span className="flex items-center gap-1 text-[10px] font-bold uppercase tracking-wider text-[#9ba582] flex-shrink-0">
                          <Check className="w-3.5 h-3.5" />
                          Added
                        </span>
                      ) : (
                        <Plus className="w-4 h-4 text-[#8f9b75] flex-shrink-0" />
                      )}
                    </button>
                  );
                })
              )}
            </div>

            <div className="pt-3 border-t border-white/[0.05]">
              {isNaming ? (
                <form onSubmit={handleCreate} className="flex items-center gap-2">
                  <input
                    type="text"
                    required
                    autoFocus
                    value={newTitle}
                    onChange={(e) => setNewTitle(e.target.value)}
                    placeholder="New playlist name"
                    className="flex-1 min-w-0 px-3 py-2 bg-[#1b2214] border border-[#2c3621] rounded-xl text-xs text-[#f0f4dc] placeholder-[#6b7558] focus:outline-none focus:border-[#dbe7b5]"
                  />
                  <button
                    type="submit"
                    className="px-4 py-2 rounded-xl bg-[#dbe7b5] text-[#14190c] font-bold text-xs hover:bg-[#c9d79e] transition flex-shrink-0"
                  >
                    Create
                  </button>
                </form>
              ) : (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    setIsNaming(true);
                  }}
                  className="w-full flex items-center gap-2 px-3 py-2.5 rounded-xl text-xs font-semibold text-[#dbe7b5] hover:bg-[#1f2615] transition"
                >
                  <Plus className="w-4 h-4" />
                  New playlist
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  );
};
