import React, { useEffect, useState } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import type { Playlist } from '../../types/music';
import {
  History,
  TrendingUp,
  User,
  ListPlus,
  Heart,
  Download,
  CloudUpload,
  Plus,
  Play,
  Trash2,
  ChevronUp,
  ChevronDown,
  Link,
  Music2,
  Info,
  Clock,
  X,
} from 'lucide-react';
import { importYouTubePlaylist } from '../../services/youtubeImporter';
import { isLetterboxedThumbnail } from '../../services/artwork';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';

interface LibraryViewProps {
  openCreatePlaylistModal: () => void;
  /**
   * A playlist to open on mount, set when one is clicked in the sidebar. Cleared
   * through `onPlaylistOpened` once consumed, so returning to the Library later
   * does not reopen it.
   */
  openPlaylistId?: string;
  onPlaylistOpened?: () => void;
  onSelectArtist?: (artistQuery: string) => void;
}

/**
 * Which collection the detail overlay is showing.
 *
 * This is a descriptor rather than a copy of the playlist. The previous version
 * stored a snapshot, so the overlay kept rendering the tracks as they were when
 * it opened: removing a track, or adding one, changed nothing on screen until it
 * was closed and reopened.
 */
type Selection =
  | { kind: 'stored'; id: string }
  | { kind: 'favorites' }
  | { kind: 'recent' }
  | { kind: 'top50' };

export const LibraryView: React.FC<LibraryViewProps> = ({
  openCreatePlaylistModal,
  openPlaylistId,
  onPlaylistOpened,
  onSelectArtist,
}) => {
  const {
    playlists,
    favorites,
    history,
    playTrack,
    getTopTracks,
    importPlaylistToState,
    deletePlaylist,
    removeFromPlaylist,
    reorderPlaylist,
    savedArtists,
    removeArtist,
    savedAlbums,
    removeAlbum,
  } = usePlayer();

  const [selection, setSelection] = useState<Selection | null>(null);
  const [showImportModal, setShowImportModal] = useState<boolean>(false);
  const [ytInput, setYtInput] = useState<string>('');
  const [isImporting, setIsImporting] = useState<boolean>(false);
  const [importError, setImportError] = useState<string>('');
  const [filter, setFilter] = useState<'all' | 'playlists' | 'artists' | 'albums'>('all');
  const [openingAlbumId, setOpeningAlbumId] = useState<string | null>(null);

  const top50 = getTopTracks(50);

  // Open the playlist that was clicked in the sidebar, then hand the request
  // back so it is not replayed the next time this view mounts. Only the id is a
  // dependency on purpose: the callback is an inline arrow in App, so including
  // it would re-run this on every parent render.
  useEffect(() => {
    if (!openPlaylistId) return;
    setSelection({ kind: 'stored', id: openPlaylistId });
    onPlaylistOpened?.();
  }, [openPlaylistId]);

  /**
   * Resolve the current selection against live state on every render.
   *
   * Liked, Recently Played and My Top 50 are views over other state and are
   * assembled here; they are not stored and carry no `isCustom` flag, which is
   * what keeps the delete and remove-track controls off them. A stored playlist
   * that has just been deleted resolves to null, which closes the overlay.
   */
  const resolveSelection = (): Playlist | null => {
    if (!selection) return null;
    switch (selection.kind) {
      case 'favorites':
        return {
          id: 'pl-favorites',
          title: 'Liked',
          description: `${favorites.length} songs`,
          tracks: favorites,
          createdAt: 0,
        };
      case 'recent':
        return {
          id: 'pl-recent',
          title: 'Recently Played',
          description: `${history.length} songs`,
          tracks: history,
          createdAt: 0,
        };
      case 'top50':
        return {
          id: 'pl-top50-view',
          title: 'My Top 50',
          description: `${top50.length} songs ranked by play count`,
          tracks: top50,
          createdAt: 0,
        };
      case 'stored':
        return playlists.find((p) => p.id === selection.id) ?? null;
    }
  };

  const selected = resolveSelection();
  // Only a stored playlist can be edited. `isCustom` is set on every playlist the
  // user creates or imports, and never on the three views above.
  const isEditable = selected?.isCustom === true;

  const formatDuration = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const handleImportPlaylist = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!ytInput.trim()) return;

    setIsImporting(true);
    setImportError('');
    try {
      const pl = await importYouTubePlaylist(ytInput.trim());
      if (pl && pl.tracks.length > 0) {
        importPlaylistToState(pl);
        setShowImportModal(false);
        setYtInput('');
      } else {
        setImportError('Could not fetch playlist. Check the URL and try again.');
      }
    } catch (err) {
      setImportError('Import failed. The playlist may be private or the URL is invalid.');
      console.error(err);
    } finally {
      setIsImporting(false);
    }
  };

  const handleDeletePlaylist = (playlistId: string, e: React.MouseEvent) => {
    e.stopPropagation();
    deletePlaylist(playlistId);
    // The overlay is driven by the selection, and a deleted playlist no longer
    // resolves, so clearing it here just avoids a dangling descriptor.
    setSelection(null);
  };

  const handleAlbumClick = async (albumId: string) => {
    if (openingAlbumId) return;
    setOpeningAlbumId(albumId);
    try {
      const pl = await importYouTubePlaylist(albumId);
      if (pl && pl.tracks.length > 0) {
        playTrack(pl.tracks[0], pl.tracks);
      }
    } catch (err) {
      console.error('Error opening album:', err);
    } finally {
      setOpeningAlbumId(null);
    }
  };

  return (
    <div className="space-y-6 max-w-2xl mx-auto select-none text-[var(--text-primary)]">
      {/* Top Header Bar */}
      <div className="flex items-center justify-between pt-1">
        <h1 className="font-display font-bold text-2xl sm:text-3xl text-[var(--text-primary)] tracking-tight">
          Library
        </h1>
        <div className="flex items-center gap-1 text-[var(--text-secondary)]">
          <button
            onClick={() => setSelection({ kind: 'recent' })}
            className="p-2 rounded-xl hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition cursor-pointer"
            title="Recently Played"
            aria-label="Recently Played"
          >
            <History className="w-5 h-5" />
          </button>
          <button
            onClick={() => setSelection({ kind: 'top50' })}
            className="p-2 rounded-xl hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition cursor-pointer"
            title="Top 50 Most Played"
            aria-label="Top 50 Most Played"
          >
            <TrendingUp className="w-5 h-5" />
          </button>
          {/* Import Playlist. Deliberately a square-cornered icon button like its
              two siblings — as a round avatar-sized chip it read as a profile
              button, which belongs only in the global header. */}
          <button
            onClick={() => setShowImportModal(true)}
            className="p-2 rounded-xl hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition cursor-pointer"
            title="Import Playlist"
            aria-label="Import Playlist"
          >
            <ListPlus className="w-5 h-5" />
          </button>
        </div>
      </div>

      {/* Filter Tabs */}
      <div className="flex items-center gap-2 overflow-x-auto pb-1 scrollbar-none">
        {(
          [
            { id: 'all', label: 'All' },
            { id: 'playlists', label: `Playlists (${playlists.length})` },
            { id: 'artists', label: `Artists (${savedArtists.length})` },
            { id: 'albums', label: `Albums (${savedAlbums.length})` },
          ] as const
        ).map((tab) => (
          <button
            key={tab.id}
            onClick={() => setFilter(tab.id)}
            className={`px-3.5 py-1.5 rounded-xl text-xs font-bold transition whitespace-nowrap cursor-pointer ${
              filter === tab.id
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] shadow-sm'
                : 'bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)]'
            }`}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* 2-Column Grid for Default Tiles (Liked, Recent, Top 50, Import) when filter is 'all' or 'playlists' */}
      {(filter === 'all' || filter === 'playlists') && (
        <div className="grid grid-cols-2 gap-3">
          {/* Liked */}
          <div
            onClick={() => setSelection({ kind: 'favorites' })}
            className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col justify-between cursor-pointer group shadow-sm"
          >
            <div className="w-full aspect-square rounded-xl bg-gradient-to-br from-rose-500/15 to-pink-500/15 border border-[var(--border-subtle)] flex items-center justify-center text-rose-500 mb-2 group-hover:scale-[1.02] transition">
              <Heart className="w-9 h-9 fill-current" />
            </div>
            <div>
              <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)]">Liked</h3>
              <p className="text-[11px] text-[var(--text-muted)]">{favorites.length} songs</p>
            </div>
          </div>

          {/* Downloaded — honest "Coming soon" */}
          <div className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] p-3 flex flex-col justify-between opacity-60 cursor-default relative shadow-sm">
            <div className="absolute top-2 right-2 px-2 py-0.5 rounded-full bg-[var(--bg-surface-elevated)] text-[9px] font-bold text-[var(--text-muted)] uppercase tracking-wider">
              Coming soon
            </div>
            <div className="w-full aspect-square rounded-xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center text-[var(--text-muted)] mb-2">
              <Download className="w-9 h-9" />
            </div>
            <div>
              <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)]">Downloaded</h3>
              <p className="text-[11px] text-[var(--text-muted)]">Offline mode</p>
            </div>
          </div>

          {/* Recently Played */}
          <div
            onClick={() => setSelection({ kind: 'recent' })}
            className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col justify-between cursor-pointer group shadow-sm"
          >
            <div className="w-full aspect-square rounded-xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center text-[var(--m3-primary)] mb-2 group-hover:scale-[1.02] transition">
              <Clock className="w-9 h-9" />
            </div>
            <div>
              <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)]">Recently Played</h3>
              <p className="text-[11px] text-[var(--text-muted)]">{history.length} songs</p>
            </div>
          </div>

          {/* My Top 50 — real play-count ranking */}
          <div
            onClick={() => setSelection({ kind: 'top50' })}
            className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col justify-between cursor-pointer group shadow-sm"
          >
            <div className="w-full aspect-square rounded-xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center text-amber-500 mb-2 group-hover:scale-[1.02] transition">
              <TrendingUp className="w-9 h-9" />
            </div>
            <div>
              <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)]">My Top 50</h3>
              <p className="text-[11px] text-[var(--text-muted)]">{top50.length > 0 ? `${top50.length} songs` : 'Play songs to rank'}</p>
            </div>
          </div>

          {/* Import from YouTube */}
          <div
            onClick={() => setShowImportModal(true)}
            className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col justify-between cursor-pointer group shadow-sm"
          >
            <div className="w-full aspect-square rounded-xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center text-emerald-500 mb-2 group-hover:scale-[1.02] transition">
              <CloudUpload className="w-9 h-9" />
            </div>
            <div>
              <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)]">Import</h3>
              <p className="text-[11px] text-[var(--text-muted)]">YouTube playlists</p>
            </div>
          </div>

          {/* User Playlists */}
          {playlists.map((pl) => {
            const validTracks = pl.tracks.filter((t) => t && t.thumbnail);
            const coverImages = validTracks.map((t) => t.thumbnail).slice(0, 4);

            return (
              <div
                key={pl.id}
                onClick={() => setSelection({ kind: 'stored', id: pl.id })}
                className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col justify-between cursor-pointer group shadow-sm"
              >
                <div className="relative w-full aspect-square rounded-xl overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] mb-2">
                  {coverImages.length >= 4 ? (
                    <div className="w-full h-full grid grid-cols-2 gap-0.5">
                      {coverImages.map((src, i) => (
                        <div key={i} className="w-full h-full overflow-hidden">
                          <img
                            src={src}
                            alt=""
                            className={`w-full h-full object-cover aspect-square ${
                              isLetterboxedThumbnail(src) ? 'scale-[1.35]' : 'scale-100'
                            }`}
                            onError={(e) => {
                              const target = e.currentTarget;
                              if (validTracks[i]?.id) target.src = `https://i.ytimg.com/vi/${validTracks[i].id}/hqdefault.jpg`;
                            }}
                          />
                        </div>
                      ))}
                    </div>
                  ) : coverImages.length > 0 ? (
                    <img
                      src={coverImages[0]}
                      alt=""
                      className={`w-full h-full object-cover aspect-square ${
                        isLetterboxedThumbnail(coverImages[0]) ? 'scale-[1.35]' : 'scale-100'
                      }`}
                      onError={(e) => {
                        const target = e.currentTarget;
                        if (validTracks[0]?.id) target.src = `https://i.ytimg.com/vi/${validTracks[0].id}/hqdefault.jpg`;
                      }}
                    />
                  ) : (
                    <div className="w-full h-full flex items-center justify-center text-[var(--text-muted)]">
                      <Music2 className="w-9 h-9" />
                    </div>
                  )}
                </div>
                <div className="min-w-0">
                  <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)] truncate leading-tight group-hover:text-[var(--m3-primary)] transition">
                    {pl.title}
                  </h3>
                  <p className="text-[11px] text-[var(--text-muted)] mt-0.5">{pl.tracks.length} songs</p>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ---- Saved Artists Section ---- */}
      {(filter === 'all' || filter === 'artists') && (
        <div className="space-y-3 pt-2">
          {filter === 'all' && savedArtists.length > 0 && (
            <h2 className="text-base font-bold text-[var(--text-primary)] flex items-center gap-2">
              <User className="w-4 h-4 text-[var(--m3-primary)]" />
              <span>Followed Artists ({savedArtists.length})</span>
            </h2>
          )}

          {savedArtists.length === 0 && filter === 'artists' ? (
            <div className="flex flex-col items-center justify-center py-16 text-center space-y-2">
              <User className="w-12 h-12 text-[var(--text-muted)]" />
              <h3 className="font-bold text-sm text-[var(--text-primary)]">No Artists Saved</h3>
              <p className="text-xs text-[var(--text-muted)] max-w-xs">
                Search for artists in Explore and tap "+ Follow" to save them to your library.
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {savedArtists.map((artist) => (
                <div
                  key={artist.id}
                  onClick={() => onSelectArtist?.(artist.query || `${artist.name} top songs`)}
                  className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col items-center text-center cursor-pointer group relative shadow-sm"
                >
                  <div className="w-20 h-20 rounded-full overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center text-[var(--text-muted)] mb-2 group-hover:scale-105 transition shadow-sm">
                    {artist.thumbnail ? (
                      <img
                        src={artist.thumbnail}
                        alt={artist.name}
                        className="w-full h-full object-cover"
                        onError={(e) => {
                          e.currentTarget.style.display = 'none';
                        }}
                      />
                    ) : (
                      <span className="text-2xl font-black text-[var(--text-muted)]">
                        {artist.name.charAt(0).toUpperCase()}
                      </span>
                    )}
                  </div>
                  <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)] truncate w-full group-hover:text-[var(--m3-primary)] transition">
                    {artist.name}
                  </h3>
                  <p className="text-[10px] text-[var(--text-muted)] truncate w-full mt-0.5">
                    {artist.subscribers || 'Followed Artist'}
                  </p>

                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      removeArtist(artist.id);
                    }}
                    className="absolute top-2 right-2 p-1.5 rounded-lg text-[var(--text-muted)] hover:text-rose-500 hover:bg-[var(--bg-surface-hover)] opacity-0 group-hover:opacity-100 transition cursor-pointer"
                    title="Unfollow artist"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ---- Saved Albums Section ---- */}
      {(filter === 'all' || filter === 'albums') && (
        <div className="space-y-3 pt-2">
          {filter === 'all' && savedAlbums.length > 0 && (
            <h2 className="text-base font-bold text-[var(--text-primary)] flex items-center gap-2">
              <Music2 className="w-4 h-4 text-[var(--m3-primary)]" />
              <span>Saved Albums ({savedAlbums.length})</span>
            </h2>
          )}

          {savedAlbums.length === 0 && filter === 'albums' ? (
            <div className="flex flex-col items-center justify-center py-16 text-center space-y-2">
              <Music2 className="w-12 h-12 text-[var(--text-muted)]" />
              <h3 className="font-bold text-sm text-[var(--text-primary)]">No Albums Saved</h3>
              <p className="text-xs text-[var(--text-muted)] max-w-xs">
                Browse albums in Explore and tap the bookmark icon to add them to your library.
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {savedAlbums.map((album) => {
                const isOpening = openingAlbumId === album.id;
                return (
                  <div
                    key={album.id}
                    onClick={() => handleAlbumClick(album.id)}
                    className="rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] hover:bg-[var(--bg-card-hover)] transition p-3 flex flex-col justify-between cursor-pointer group relative shadow-sm"
                  >
                    <div className="relative w-full aspect-square rounded-xl overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] mb-2 flex items-center justify-center">
                      {album.thumbnail ? (
                        <img
                          src={album.thumbnail}
                          alt={album.title}
                          className="w-full h-full object-cover group-hover:scale-105 transition duration-300"
                          onError={(e) => {
                            e.currentTarget.style.display = 'none';
                          }}
                        />
                      ) : (
                        <Music2 className="w-9 h-9 text-[var(--text-muted)]" />
                      )}

                      <div
                        className={`absolute inset-0 bg-black/50 flex items-center justify-center transition-opacity ${
                          isOpening ? 'opacity-100' : 'opacity-0 group-hover:opacity-100'
                        }`}
                      >
                        {isOpening ? (
                          <div className="w-6 h-6 rounded-full border-2 border-white border-t-transparent animate-spin" />
                        ) : (
                          <div className="p-2.5 rounded-full bg-[var(--text-primary)] text-[var(--text-inverse)] shadow-lg">
                            <Play className="w-4 h-4 fill-current ml-0.5" />
                          </div>
                        )}
                      </div>
                    </div>

                    <div className="min-w-0">
                      <h3 className="font-bold text-xs sm:text-sm text-[var(--text-primary)] truncate leading-tight group-hover:text-[var(--m3-primary)] transition">
                        {album.title}
                      </h3>
                      <p className="text-[10px] text-[var(--text-muted)] truncate mt-0.5">
                        {album.artist || 'Album'}
                        {typeof album.trackCount === 'number' ? ` · ${album.trackCount} tracks` : ''}
                      </p>
                    </div>

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        removeAlbum(album.id);
                      }}
                      className="absolute top-2 right-2 p-1.5 rounded-lg text-[var(--text-muted)] hover:text-rose-500 hover:bg-black/60 opacity-0 group-hover:opacity-100 transition z-10 cursor-pointer"
                      title="Remove from Library"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* FAB — creates a playlist */}
      <button
        onClick={openCreatePlaylistModal}
        className="fixed bottom-[var(--float-bottom)] right-5 sm:right-6 md:right-8 z-30 w-14 h-14 rounded-2xl bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-[var(--m3-on-primary)] active:scale-95 shadow-2xl flex items-center justify-center transition cursor-pointer"
        title="Create playlist"
        aria-label="Create playlist"
      >
        <Plus className="w-7 h-7 text-[var(--m3-on-primary)] stroke-[2.5]" />
      </button>

      {/* YouTube Import Modal */}
      {showImportModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-md animate-in fade-in">
          <div className="relative w-full max-w-md rounded-3xl bg-[var(--bg-popover)] border border-[var(--border-medium)] p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-[var(--border-subtle)]">
              <div className="flex items-center gap-2 text-[var(--m3-primary)]">
                <svg className="w-5 h-5 text-red-500 fill-current" viewBox="0 0 24 24">
                  <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/>
                </svg>
                <h3 className="font-bold text-base text-[var(--text-primary)]">Import YouTube Playlist</h3>
              </div>
              <button
                onClick={() => { setShowImportModal(false); setImportError(''); }}
                className="p-1 rounded-full text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                title="Close"
                aria-label="Close"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleImportPlaylist} className="space-y-3">
              <p className="text-xs text-[var(--text-muted)]">
                Paste any public YouTube or YouTube Music playlist link to import all songs:
              </p>
              <div className="relative">
                <Link className="absolute left-3 top-3 w-4 h-4 text-[var(--text-muted)]" />
                <input
                  type="text"
                  required
                  value={ytInput}
                  onChange={(e) => { setYtInput(e.target.value); setImportError(''); }}
                  placeholder="https://youtube.com/playlist?list=PL..."
                  className="w-full pl-9 pr-3 py-2.5 bg-[var(--bg-input)] border border-[var(--border-subtle)] rounded-xl text-xs text-[var(--text-primary)] placeholder-[var(--text-muted)] focus:outline-none focus:border-[var(--border-strong)]"
                />
              </div>

              {importError && (
                <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-rose-500/10 border border-rose-500/20 text-xs text-rose-500 dark:text-rose-300">
                  <Info className="w-3.5 h-3.5 flex-shrink-0" />
                  <span>{importError}</span>
                </div>
              )}

              <button
                type="submit"
                disabled={isImporting}
                className="w-full py-2.5 rounded-xl bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-[var(--m3-on-primary)] font-bold text-xs transition flex items-center justify-center gap-2 disabled:opacity-50 cursor-pointer shadow-sm"
              >
                {isImporting ? (
                  <>
                    <div className="w-3.5 h-3.5 rounded-full border-2 border-current border-t-transparent animate-spin" />
                    Importing...
                  </>
                ) : (
                  'Import Playlist'
                )}
              </button>
            </form>

            {/* Honest limitation notice */}
            <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] text-[11px] text-[var(--text-muted)] leading-relaxed">
              <Info className="w-3.5 h-3.5 flex-shrink-0 mt-0.5 text-[var(--m3-primary)]" />
              <span>
                <strong className="text-[var(--text-primary)]">Note:</strong> YouTube Music library sync (liked songs, personal playlists) requires Google OAuth with a backend server, which is planned for a future update. For now, you can import any <strong>public</strong> YouTube playlist by pasting its link above.
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Selected Playlist Modal */}
      {selected && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-md animate-in fade-in">
          <div className="relative w-full max-w-xl max-h-[82vh] rounded-3xl bg-[var(--bg-popover)] border border-[var(--border-medium)] p-5 sm:p-6 flex flex-col shadow-2xl overflow-hidden text-[var(--text-primary)]">
            <div className="flex items-center justify-between pb-3 border-b border-[var(--border-subtle)]">
              <div className="min-w-0">
                <h3 className="font-bold text-xl text-[var(--text-primary)] truncate">{selected.title}</h3>
                <p className="text-xs text-[var(--text-muted)]">{selected.tracks.length} songs</p>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                {isEditable && (
                  <button
                    onClick={(e) => handleDeletePlaylist(selected.id, e)}
                    className="p-1.5 rounded-lg text-[var(--text-muted)] hover:text-rose-500 transition cursor-pointer"
                    title="Delete playlist"
                    aria-label="Delete playlist"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                )}
                <button
                  onClick={() => setSelection(null)}
                  className="p-1.5 rounded-full text-[var(--text-muted)] hover:text-[var(--text-primary)] cursor-pointer"
                  title="Close"
                  aria-label="Close"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>

            {selected.tracks.length > 0 && (
              <div className="flex items-center gap-2 py-3">
                <button
                  onClick={() => {
                    playTrack(selected.tracks[0], selected.tracks);
                    setSelection(null);
                  }}
                  className="flex items-center gap-2 px-5 py-2 rounded-full bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-[var(--m3-on-primary)] font-bold text-xs shadow transition cursor-pointer"
                >
                  <Play className="w-3.5 h-3.5 fill-current" />
                  <span>Play All</span>
                </button>
              </div>
            )}

            <div className="flex-1 overflow-y-auto py-2 space-y-1">
              {selected.tracks.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-10 text-center space-y-2">
                  <Music2 className="w-10 h-10 text-[var(--text-muted)]" />
                  <p className="text-xs text-[var(--text-muted)] max-w-xs leading-relaxed">
                    {selection?.kind === 'top50'
                      ? 'Play more songs to build your top tracks chart.'
                      : selection?.kind === 'recent'
                      ? 'Your recently played songs will appear here.'
                      : selection?.kind === 'favorites'
                      ? 'Tap the heart on any song to save it here.'
                      : 'This playlist is empty. Use the add-to-playlist button on any song to put it here.'}
                  </p>
                </div>
              ) : (
                selected.tracks.map((t, i) => (
                  <div
                    key={`${t.id}-${i}`}
                    onClick={() => {
                      playTrack(t, selected.tracks);
                      setSelection(null);
                    }}
                    className="flex items-center justify-between gap-2 p-2 rounded-xl hover:bg-[var(--bg-surface-hover)] cursor-pointer group"
                  >
                    <div className="flex items-center gap-3 min-w-0 flex-1">
                      <span className="text-xs font-mono text-[var(--text-muted)] w-4 text-center">{i + 1}</span>
                      <div className="w-10 h-10 rounded-lg overflow-hidden bg-neutral-800 flex-shrink-0">
                        <img
                          src={t.thumbnail}
                          alt=""
                          className={`w-full h-full object-cover ${
                            isLetterboxedThumbnail(t.thumbnail) ? 'scale-[1.35]' : 'scale-100'
                          }`}
                          onError={(e) => {
                            const target = e.currentTarget;
                            target.src = `https://i.ytimg.com/vi/${t.id}/hqdefault.jpg`;
                          }}
                        />
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="text-xs sm:text-sm font-semibold text-[var(--text-primary)] truncate group-hover:text-[var(--m3-primary)]">
                          {t.title}
                        </p>
                        <p className="text-[11px] text-[var(--text-muted)] truncate">{t.artist}</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-1 flex-shrink-0 text-[var(--text-muted)]">
                      <span className="text-xs font-mono hidden sm:inline">{formatDuration(t.duration)}</span>

                      <AddToPlaylistButton
                        track={t}
                        className="p-1.5 rounded-lg hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition text-[var(--text-muted)] cursor-pointer"
                      />

                      {isEditable && (
                        <>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              reorderPlaylist(selected.id, i, i - 1);
                            }}
                            disabled={i === 0}
                            className="p-1.5 rounded-lg hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition disabled:opacity-30 disabled:pointer-events-none cursor-pointer"
                            title="Move up"
                            aria-label="Move up in playlist"
                          >
                            <ChevronUp className="w-4 h-4" />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              reorderPlaylist(selected.id, i, i + 1);
                            }}
                            disabled={i === selected.tracks.length - 1}
                            className="p-1.5 rounded-lg hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)] transition disabled:opacity-30 disabled:pointer-events-none cursor-pointer"
                            title="Move down"
                            aria-label="Move down in playlist"
                          >
                            <ChevronDown className="w-4 h-4" />
                          </button>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              removeFromPlaylist(selected.id, t.id);
                            }}
                            className="p-1.5 rounded-lg hover:bg-[var(--bg-surface-hover)] hover:text-rose-500 transition cursor-pointer"
                            title="Remove from this playlist"
                            aria-label="Remove from this playlist"
                          >
                            <Trash2 className="w-4 h-4" />
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
