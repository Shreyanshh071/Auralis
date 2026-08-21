import React, { useState } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import type { Playlist } from '../../types/music';
import {
  History,
  TrendingUp,
  User,
  Heart,
  Download,
  RotateCcw,
  CloudUpload,
  Plus,
  Play,
  Trash2,
  Link,
  Music2,
  Info,
  Clock,
} from 'lucide-react';
import { importYouTubePlaylist } from '../../services/youtubeImporter';

interface LibraryViewProps {
  openCreatePlaylistModal: () => void;
}

export const LibraryView: React.FC<LibraryViewProps> = ({ openCreatePlaylistModal }) => {
  const {
    playlists,
    favorites,
    history,
    playTrack,
    currentTrack,
    getTopTracks,
    importPlaylistToState,
    deletePlaylist,
    showToast,
  } = usePlayer();

  const [selectedPlaylist, setSelectedPlaylist] = useState<Playlist | null>(null);
  const [showImportModal, setShowImportModal] = useState<boolean>(false);
  const [ytInput, setYtInput] = useState<string>('');
  const [isImporting, setIsImporting] = useState<boolean>(false);
  const [importError, setImportError] = useState<string>('');

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
    if (selectedPlaylist?.id === playlistId) {
      setSelectedPlaylist(null);
    }
  };

  const top50 = getTopTracks(50);

  return (
    <div className="space-y-6 pb-44 max-w-2xl mx-auto select-none text-[#e2e8c0]">
      {/* Top Header Bar */}
      <div className="flex items-center justify-between pt-1">
        <h1 className="font-display font-bold text-2xl sm:text-3xl text-[#f3f7d8] tracking-tight">
          Library
        </h1>
        <div className="flex items-center gap-4 text-[#c4cca5]">
          <button
            onClick={() => setSelectedPlaylist({
              id: 'pl-recent',
              title: 'Recently Played',
              description: `${history.length} songs`,
              tracks: history,
              createdAt: Date.now(),
            })}
            className="p-1 hover:text-white transition"
            title="Recently Played"
          >
            <History className="w-5 h-5" />
          </button>
          <button
            onClick={() => setSelectedPlaylist({
              id: 'pl-top50-view',
              title: 'My Top 50',
              description: `${top50.length} songs ranked by play count`,
              tracks: top50,
              createdAt: Date.now(),
            })}
            className="p-1 hover:text-white transition"
            title="Top 50 Most Played"
          >
            <TrendingUp className="w-5 h-5" />
          </button>
          <button
            onClick={() => setShowImportModal(true)}
            className="w-7 h-7 rounded-full bg-[#272c1c] border border-[#3e462c] flex items-center justify-center text-xs font-semibold text-[#dbe7b5] hover:bg-[#343b26] transition"
            title="Import YouTube Playlist"
          >
            <User className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* 2-Column Grid */}
      <div className="grid grid-cols-2 gap-3">
        {/* Liked */}
        <div
          onClick={() => setSelectedPlaylist({
            id: 'pl-favorites',
            title: 'Liked',
            description: `${favorites.length} songs`,
            tracks: favorites,
            createdAt: Date.now(),
          })}
          className="rounded-2xl bg-[#171b11] border border-[#272f1c] hover:bg-[#202717] transition p-3 flex flex-col justify-between cursor-pointer group"
        >
          <div className="w-full aspect-square rounded-xl bg-[#232a19] border border-[#333e25] flex items-center justify-center text-[#dbe7b5] mb-2 group-hover:scale-[1.02] transition">
            <Heart className="w-9 h-9 fill-current" />
          </div>
          <div>
            <h3 className="font-bold text-xs sm:text-sm text-[#f0f4dc]">Liked</h3>
            <p className="text-[11px] text-[#8f9b75]">{favorites.length} songs</p>
          </div>
        </div>

        {/* Downloaded — honest "Coming soon" */}
        <div className="rounded-2xl bg-[#171b11] border border-[#272f1c] p-3 flex flex-col justify-between opacity-60 cursor-default relative">
          <div className="absolute top-2 right-2 px-2 py-0.5 rounded-full bg-[#2b331f] text-[9px] font-bold text-[#9ba582] uppercase tracking-wider">
            Coming soon
          </div>
          <div className="w-full aspect-square rounded-xl bg-[#232a19] border border-[#333e25] flex items-center justify-center text-[#dbe7b5] mb-2">
            <Download className="w-9 h-9" />
          </div>
          <div>
            <h3 className="font-bold text-xs sm:text-sm text-[#f0f4dc]">Downloaded</h3>
            <p className="text-[11px] text-[#8f9b75]">Offline mode</p>
          </div>
        </div>

        {/* Recently Played (was "Cached") */}
        <div
          onClick={() => setSelectedPlaylist({
            id: 'pl-recent',
            title: 'Recently Played',
            description: `${history.length} songs`,
            tracks: history,
            createdAt: Date.now(),
          })}
          className="rounded-2xl bg-[#171b11] border border-[#272f1c] hover:bg-[#202717] transition p-3 flex flex-col justify-between cursor-pointer group"
        >
          <div className="w-full aspect-square rounded-xl bg-[#232a19] border border-[#333e25] flex items-center justify-center text-[#dbe7b5] mb-2 group-hover:scale-[1.02] transition">
            <Clock className="w-9 h-9" />
          </div>
          <div>
            <h3 className="font-bold text-xs sm:text-sm text-[#f0f4dc]">Recently Played</h3>
            <p className="text-[11px] text-[#8f9b75]">{history.length} songs</p>
          </div>
        </div>

        {/* My Top 50 — real play-count ranking */}
        <div
          onClick={() => setSelectedPlaylist({
            id: 'pl-top50-view',
            title: 'My Top 50',
            description: `${top50.length} songs ranked by play count`,
            tracks: top50,
            createdAt: Date.now(),
          })}
          className="rounded-2xl bg-[#171b11] border border-[#272f1c] hover:bg-[#202717] transition p-3 flex flex-col justify-between cursor-pointer group"
        >
          <div className="w-full aspect-square rounded-xl bg-[#232a19] border border-[#333e25] flex items-center justify-center text-[#dbe7b5] mb-2 group-hover:scale-[1.02] transition">
            <TrendingUp className="w-9 h-9" />
          </div>
          <div>
            <h3 className="font-bold text-xs sm:text-sm text-[#f0f4dc]">My Top 50</h3>
            <p className="text-[11px] text-[#8f9b75]">{top50.length > 0 ? `${top50.length} songs` : 'Play songs to rank'}</p>
          </div>
        </div>

        {/* Import from YouTube */}
        <div
          onClick={() => setShowImportModal(true)}
          className="rounded-2xl bg-[#171b11] border border-[#272f1c] hover:bg-[#202717] transition p-3 flex flex-col justify-between cursor-pointer group"
        >
          <div className="w-full aspect-square rounded-xl bg-[#232a19] border border-[#333e25] flex items-center justify-center text-[#dbe7b5] mb-2 group-hover:scale-[1.02] transition">
            <CloudUpload className="w-9 h-9" />
          </div>
          <div>
            <h3 className="font-bold text-xs sm:text-sm text-[#f0f4dc]">Import</h3>
            <p className="text-[11px] text-[#8f9b75]">YouTube playlists</p>
          </div>
        </div>

        {/* User Playlists */}
        {playlists.map((pl) => {
          const validTracks = pl.tracks.filter((t) => t && t.thumbnail);
          const coverImages = validTracks.map((t) => t.thumbnail).slice(0, 4);

          return (
            <div
              key={pl.id}
              onClick={() => setSelectedPlaylist(pl)}
              className="rounded-2xl bg-[#171b11] border border-[#272f1c] hover:bg-[#202717] transition p-3 flex flex-col justify-between cursor-pointer group"
            >
              <div className="relative w-full aspect-square rounded-xl overflow-hidden bg-[#222a19] border border-[#303a23] mb-2">
                {coverImages.length >= 4 ? (
                  <div className="w-full h-full grid grid-cols-2 gap-0.5">
                    {coverImages.map((src, i) => (
                      <img
                        key={i}
                        src={src}
                        alt=""
                        className="w-full h-full object-cover aspect-square"
                        onError={(e) => {
                          const target = e.currentTarget;
                          if (validTracks[i]?.id) target.src = `https://i.ytimg.com/vi/${validTracks[i].id}/hqdefault.jpg`;
                        }}
                      />
                    ))}
                  </div>
                ) : coverImages.length > 0 ? (
                  <img
                    src={coverImages[0]}
                    alt=""
                    className="w-full h-full object-cover aspect-square"
                    onError={(e) => {
                      const target = e.currentTarget;
                      if (validTracks[0]?.id) target.src = `https://i.ytimg.com/vi/${validTracks[0].id}/hqdefault.jpg`;
                    }}
                  />
                ) : (
                  <div className="w-full h-full flex items-center justify-center text-[#dbe7b5]">
                    <Music2 className="w-9 h-9 text-[#8f9b75]" />
                  </div>
                )}
              </div>
              <div className="min-w-0">
                <h3 className="font-bold text-xs sm:text-sm text-[#f0f4dc] truncate leading-tight group-hover:text-[#dbe7b5] transition">
                  {pl.title}
                </h3>
                <p className="text-[11px] text-[#8f9b75] mt-0.5">{pl.tracks.length} songs</p>
              </div>
            </div>
          );
        })}
      </div>

      {/* FAB */}
      <button
        onClick={() => setShowImportModal(true)}
        className="fixed bottom-24 right-5 z-40 w-14 h-14 rounded-2xl bg-[#59693d] hover:bg-[#6c7f4a] active:scale-95 text-[#14190c] shadow-2xl flex items-center justify-center transition"
        title="Import YouTube Playlist"
      >
        <Plus className="w-7 h-7 text-[#f3f7d8] stroke-[2.5]" />
      </button>

      {/* YouTube Import Modal — honest, no fake account sync */}
      {showImportModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-in fade-in">
          <div className="relative w-full max-w-md rounded-3xl bg-[#14180e] border border-[#2c3720] p-6 shadow-2xl space-y-4">
            <div className="flex items-center justify-between pb-2 border-b border-white/[0.04]">
              <div className="flex items-center gap-2 text-[#dbe7b5]">
                <svg className="w-5 h-5 text-red-500 fill-current" viewBox="0 0 24 24">
                  <path d="M23.498 6.186a3.016 3.016 0 0 0-2.122-2.136C19.505 3.545 12 3.545 12 3.545s-7.505 0-9.377.505A3.017 3.017 0 0 0 .502 6.186C0 8.07 0 12 0 12s0 3.93.502 5.814a3.016 3.016 0 0 0 2.122 2.136c1.871.505 9.376.505 9.376.505s7.505 0 9.377-.505a3.015 3.015 0 0 0 2.122-2.136C24 15.93 24 12 24 12s0-3.93-.502-5.814zM9.545 15.568V8.432L15.818 12l-6.273 3.568z"/>
                </svg>
                <h3 className="font-bold text-base text-[#f0f4dc]">Import YouTube Playlist</h3>
              </div>
              <button
                onClick={() => { setShowImportModal(false); setImportError(''); }}
                className="p-1 rounded-full text-[#8f9b75] hover:text-white"
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleImportPlaylist} className="space-y-3">
              <p className="text-xs text-[#9ba582]">
                Paste any public YouTube or YouTube Music playlist link to import all songs:
              </p>
              <div className="relative">
                <Link className="absolute left-3 top-3 w-4 h-4 text-[#8f9b75]" />
                <input
                  type="text"
                  required
                  value={ytInput}
                  onChange={(e) => { setYtInput(e.target.value); setImportError(''); }}
                  placeholder="https://youtube.com/playlist?list=PL..."
                  className="w-full pl-9 pr-3 py-2.5 bg-[#1b2214] border border-[#2c3621] rounded-xl text-xs text-[#f0f4dc] placeholder-[#6b7558] focus:outline-none focus:border-[#dbe7b5]"
                />
              </div>

              {importError && (
                <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-rose-500/10 border border-rose-500/20 text-xs text-rose-300">
                  <Info className="w-3.5 h-3.5 flex-shrink-0" />
                  <span>{importError}</span>
                </div>
              )}

              <button
                type="submit"
                disabled={isImporting}
                className="w-full py-2.5 rounded-xl bg-[#dbe7b5] text-[#14190c] font-bold text-xs hover:bg-[#c9d79e] transition flex items-center justify-center gap-2 disabled:opacity-50"
              >
                {isImporting ? (
                  <>
                    <div className="w-3.5 h-3.5 rounded-full border-2 border-[#14190c] border-t-transparent animate-spin" />
                    Importing...
                  </>
                ) : (
                  'Import Playlist'
                )}
              </button>
            </form>

            {/* Honest limitation notice */}
            <div className="flex items-start gap-2 px-3 py-2.5 rounded-lg bg-[#1b2214] border border-[#2c3621] text-[11px] text-[#8f9b75] leading-relaxed">
              <Info className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" />
              <span>
                <strong className="text-[#dbe7b5]">Note:</strong> YouTube Music library sync (liked songs, personal playlists) requires Google OAuth with a backend server, which is planned for a future update. For now, you can import any <strong>public</strong> YouTube playlist by pasting its link above.
              </span>
            </div>
          </div>
        </div>
      )}

      {/* Selected Playlist Modal */}
      {selectedPlaylist && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-in fade-in">
          <div className="relative w-full max-w-xl max-h-[82vh] rounded-3xl bg-[#13170e] border border-[#2b351f] p-5 sm:p-6 flex flex-col shadow-2xl overflow-hidden">
            <div className="flex items-center justify-between pb-3 border-b border-white/[0.04]">
              <div>
                <h3 className="font-bold text-xl text-[#f0f4dc]">{selectedPlaylist.title}</h3>
                <p className="text-xs text-[#8f9b75]">{selectedPlaylist.tracks.length} songs</p>
              </div>
              <div className="flex items-center gap-2">
                {selectedPlaylist.isCustom && (
                  <button
                    onClick={(e) => handleDeletePlaylist(selectedPlaylist.id, e)}
                    className="p-1.5 rounded-lg text-[#8f9b75] hover:text-rose-400 transition"
                    title="Delete playlist"
                  >
                    <Trash2 className="w-4 h-4" />
                  </button>
                )}
                <button
                  onClick={() => setSelectedPlaylist(null)}
                  className="p-1.5 rounded-full text-[#8f9b75] hover:text-white"
                >
                  ✕
                </button>
              </div>
            </div>

            {selectedPlaylist.tracks.length > 0 && (
              <div className="flex items-center gap-2 py-3">
                <button
                  onClick={() => {
                    playTrack(selectedPlaylist.tracks[0], selectedPlaylist.tracks);
                    setSelectedPlaylist(null);
                  }}
                  className="flex items-center gap-2 px-5 py-2 rounded-full bg-[#dbe7b5] text-[#14190c] font-bold text-xs shadow hover:bg-[#c9d79e] transition"
                >
                  <Play className="w-3.5 h-3.5 fill-current" />
                  <span>Play All</span>
                </button>
              </div>
            )}

            <div className="flex-1 overflow-y-auto py-2 space-y-1">
              {selectedPlaylist.tracks.length === 0 ? (
                <div className="flex flex-col items-center justify-center py-10 text-center space-y-2">
                  <Music2 className="w-10 h-10 text-[#8f9b75]" />
                  <p className="text-xs text-[#8f9b75]">
                    {selectedPlaylist.id.includes('top50')
                      ? 'Play more songs to build your top tracks chart.'
                      : selectedPlaylist.id.includes('recent')
                      ? 'Your recently played songs will appear here.'
                      : 'No tracks in this playlist.'}
                  </p>
                </div>
              ) : (
                selectedPlaylist.tracks.map((t, i) => (
                  <div
                    key={`${t.id}-${i}`}
                    onClick={() => {
                      playTrack(t, selectedPlaylist.tracks);
                      setSelectedPlaylist(null);
                    }}
                    className="flex items-center justify-between p-2 rounded-xl hover:bg-[#1f2615] cursor-pointer group"
                  >
                    <div className="flex items-center gap-3 min-w-0 flex-1">
                      <span className="text-xs font-mono text-[#8f9b75] w-4 text-center">{i + 1}</span>
                      <img
                        src={t.thumbnail}
                        alt=""
                        className="w-10 h-10 rounded-lg object-cover bg-neutral-800 flex-shrink-0"
                        onError={(e) => {
                          const target = e.currentTarget;
                          target.src = `https://i.ytimg.com/vi/${t.id}/hqdefault.jpg`;
                        }}
                      />
                      <div className="min-w-0 flex-1">
                        <p className="text-xs sm:text-sm font-semibold text-[#f0f4dc] truncate group-hover:text-[#dbe7b5]">
                          {t.title}
                        </p>
                        <p className="text-[11px] text-[#8f9b75] truncate">{t.artist}</p>
                      </div>
                    </div>
                    <span className="text-xs font-mono text-[#8f9b75]">{formatDuration(t.duration)}</span>
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
