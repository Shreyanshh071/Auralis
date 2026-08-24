import React, { useState, useEffect } from 'react';
import {
  X,
  MonitorPlay,
  Loader2,
  Check,
  AlertTriangle,
  Music2,
  Heart,
  ListMusic,
  Download,
  Unplug,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { usePlayer } from '../../context/PlayerContext';
import {
  fetchYouTubePlaylists,
  fetchYouTubeLikedSongs,
  importYouTubePlaylistAsAuralis,
  setYouTubeConnectionState,
  YouTubeApiError,
} from '../../services/youtubeSync';
import { isSignInCancellation } from '../../services/googleSignIn';

interface YouTubeSyncModalProps {
  isOpen: boolean;
  onClose: () => void;
}

interface YTPlaylistInfo {
  id: string;
  title: string;
  trackCount: number;
  thumbnail?: string;
}

type ImportStatus = 'idle' | 'loading' | 'success' | 'error';

export const YouTubeSyncModal: React.FC<YouTubeSyncModalProps> = ({ isOpen, onClose }) => {
  const {
    user,
    youtubeState,
    youtubeConnecting,
    youtubeAccessToken,
    connectYouTube,
    disconnectYouTube,
  } = useAuth();
  const { importPlaylistToState, showToast } = usePlayer();

  const [playlists, setPlaylists] = useState<YTPlaylistInfo[]>([]);
  const [loadingPlaylists, setLoadingPlaylists] = useState(false);
  const [likedImportStatus, setLikedImportStatus] = useState<ImportStatus>('idle');
  const [importingPlaylistId, setImportingPlaylistId] = useState<string | null>(null);
  const [importedPlaylistIds, setImportedPlaylistIds] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);

  // Fetch playlists when connected and access token is available
  useEffect(() => {
    if (!youtubeAccessToken || !youtubeState.connected) {
      setPlaylists([]);
      return;
    }
    let cancelled = false;
    setLoadingPlaylists(true);
    setError(null);
    fetchYouTubePlaylists(youtubeAccessToken)
      .then((pls) => {
        if (!cancelled) setPlaylists(pls);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(
            err instanceof YouTubeApiError
              ? err.message
              : 'Could not load your YouTube playlists.',
          );
        }
      })
      .finally(() => {
        if (!cancelled) setLoadingPlaylists(false);
      });
    return () => { cancelled = true; };
  }, [youtubeAccessToken, youtubeState.connected]);

  const handleConnect = async () => {
    setError(null);
    try {
      await connectYouTube();
      showToast('YouTube connected!', 'success');
    } catch (err: any) {
      if (!isSignInCancellation(err)) {
        const message =
          err instanceof YouTubeApiError
            ? err.message
            : err?.message || 'Could not connect YouTube.';
        setError(message);
        showToast(message, 'error');
      }
    }
  };

  const handleDisconnect = () => {
    disconnectYouTube();
    setPlaylists([]);
    setImportedPlaylistIds(new Set());
    setLikedImportStatus('idle');
    setError(null);
    showToast('YouTube disconnected', 'info');
  };

  const handleImportLiked = async () => {
    let token = youtubeAccessToken;
    if (!token) {
      try {
        token = await connectYouTube();
      } catch (err: any) {
        if (!isSignInCancellation(err)) {
          const message =
            err instanceof YouTubeApiError
              ? err.message
              : err?.message || 'Could not connect YouTube.';
          setError(message);
          showToast(message, 'error');
        }
        return;
      }
    }
    if (!token) return;

    setLikedImportStatus('loading');
    setError(null);
    try {
      const tracks = await fetchYouTubeLikedSongs(token);
      if (tracks.length === 0) {
        showToast('No liked songs found on YouTube.', 'info');
        setLikedImportStatus('idle');
        return;
      }
      importPlaylistToState({
        id: `yt-liked-${Date.now()}`,
        title: 'YouTube Liked Songs',
        description: `Imported ${tracks.length} liked songs from YouTube`,
        cover: tracks[0]?.thumbnail,
        tracks,
        createdAt: Date.now(),
        isCustom: true,
      });
      setLikedImportStatus('success');
      setYouTubeConnectionState({ ...youtubeState, lastImportedAt: Date.now() });
      showToast(`Imported ${tracks.length} liked songs!`, 'success');
    } catch (err: any) {
      setLikedImportStatus('error');
      setError(err?.message || 'Failed to import liked songs.');
    }
  };

  const handleImportPlaylist = async (pl: YTPlaylistInfo) => {
    if (importingPlaylistId) return;

    let token = youtubeAccessToken;
    if (!token) {
      try {
        token = await connectYouTube();
      } catch (err: any) {
        if (!isSignInCancellation(err)) {
          const message =
            err instanceof YouTubeApiError
              ? err.message
              : err?.message || 'Could not connect YouTube.';
          setError(message);
          showToast(message, 'error');
        }
        return;
      }
    }
    if (!token) return;

    setImportingPlaylistId(pl.id);
    setError(null);
    try {
      const playlist = await importYouTubePlaylistAsAuralis(token, pl);
      importPlaylistToState(playlist);
      setImportedPlaylistIds((prev) => new Set(prev).add(pl.id));
      setYouTubeConnectionState({ ...youtubeState, lastImportedAt: Date.now() });
      showToast(`Imported "${pl.title}" (${playlist.tracks.length} songs)`, 'success');
    } catch (err: any) {
      setError(err?.message || `Failed to import "${pl.title}".`);
    } finally {
      setImportingPlaylistId(null);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm animate-in fade-in duration-150 p-4">
      <div className="relative w-full max-w-lg max-h-[85vh] overflow-hidden rounded-3xl bg-[var(--bg-modal)] border border-[var(--border-medium)] shadow-2xl flex flex-col animate-in slide-in-from-bottom-4 duration-200">
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-[var(--border-subtle)]">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-xl bg-red-500/10 text-red-500">
              <MonitorPlay className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-sm font-bold text-[var(--text-primary)]">YouTube Sync</h2>
              <p className="text-[11px] text-[var(--text-muted)]">
                {youtubeState.connected
                  ? `Connected${youtubeState.channelName ? ` as ${youtubeState.channelName}` : ''}`
                  : 'Import your YouTube music'}
              </p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {/* Error Banner */}
          {error && (
            <div className="flex items-start gap-2.5 p-3 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-600 dark:text-amber-300">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
              <p className="text-xs leading-relaxed">{error}</p>
            </div>
          )}

          {/* Not Connected State */}
          {!youtubeState.connected ? (
            <div className="flex flex-col items-center text-center py-8 space-y-4">
              <div className="w-16 h-16 rounded-2xl bg-red-500/10 border border-red-500/30 flex items-center justify-center">
                <MonitorPlay className="w-8 h-8 text-red-500" />
              </div>
              <div>
                <h3 className="text-sm font-bold text-[var(--text-primary)]">
                  Connect your YouTube account
                </h3>
                <p className="text-xs text-[var(--text-muted)] mt-1 max-w-xs">
                  Import your liked songs, playlists, and music from YouTube Music.
                  We only request read-only access.
                </p>
              </div>
              <button
                onClick={handleConnect}
                disabled={youtubeConnecting || !user}
                className="flex items-center gap-2 px-5 py-2.5 rounded-2xl bg-red-500 hover:bg-red-600 text-white text-xs font-bold transition shadow-sm cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed active:scale-95"
              >
                {youtubeConnecting ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : (
                  <MonitorPlay className="w-4 h-4" />
                )}
                {youtubeConnecting ? 'Connecting…' : 'Connect YouTube'}
              </button>
              {!user && (
                <p className="text-[11px] text-[var(--text-muted)]">
                  Sign in with Google first to connect YouTube.
                </p>
              )}
            </div>
          ) : (
            /* Connected State */
            <>
              {/* Session Refresh Notice if token is expired/missing */}
              {!youtubeAccessToken && (
                <div className="flex items-center justify-between p-3 rounded-2xl bg-amber-500/10 border border-amber-500/30">
                  <div className="min-w-0 pr-2">
                    <p className="text-xs font-semibold text-amber-600 dark:text-amber-300">
                      Session expired
                    </p>
                    <p className="text-[10px] text-[var(--text-muted)]">
                      Reconnect your YouTube session to load your playlists.
                    </p>
                  </div>
                  <button
                    onClick={handleConnect}
                    disabled={youtubeConnecting}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-red-500 hover:bg-red-600 text-white text-xs font-semibold transition cursor-pointer disabled:opacity-50 flex-shrink-0"
                  >
                    {youtubeConnecting ? (
                      <Loader2 className="w-3 h-3 animate-spin" />
                    ) : (
                      <MonitorPlay className="w-3 h-3" />
                    )}
                    {youtubeConnecting ? 'Connecting…' : 'Reconnect'}
                  </button>
                </div>
              )}

              {/* Import Liked Songs */}
              <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)]">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2.5">
                    <Heart className="w-4 h-4 text-rose-500" />
                    <div>
                      <p className="text-xs font-semibold text-[var(--text-primary)]">Liked Songs</p>
                      <p className="text-[10px] text-[var(--text-muted)]">
                        Import all your YouTube liked videos as a playlist
                      </p>
                    </div>
                  </div>
                  <button
                    onClick={handleImportLiked}
                    disabled={likedImportStatus === 'loading' || likedImportStatus === 'success'}
                    className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-semibold transition cursor-pointer ${
                      likedImportStatus === 'success'
                        ? 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/30'
                        : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-primary)] border border-[var(--border-subtle)]'
                    } disabled:opacity-50 disabled:cursor-not-allowed`}
                  >
                    {likedImportStatus === 'loading' ? (
                      <Loader2 className="w-3 h-3 animate-spin" />
                    ) : likedImportStatus === 'success' ? (
                      <Check className="w-3 h-3" />
                    ) : (
                      <Download className="w-3 h-3" />
                    )}
                    {likedImportStatus === 'success' ? 'Imported' : 'Import'}
                  </button>
                </div>
              </div>

              {/* Playlists List */}
              <div>
                <h3 className="text-xs font-bold text-[var(--text-primary)] flex items-center gap-2 mb-2">
                  <ListMusic className="w-3.5 h-3.5 text-[var(--text-muted)]" />
                  Your Playlists
                </h3>
                {loadingPlaylists ? (
                  <div className="flex items-center justify-center py-8 text-[var(--text-muted)]">
                    <Loader2 className="w-5 h-5 animate-spin" />
                  </div>
                ) : playlists.length === 0 ? (
                  <p className="text-xs text-[var(--text-muted)] text-center py-4">
                    No playlists found on your YouTube account.
                  </p>
                ) : (
                  <div className="space-y-1.5 max-h-64 overflow-y-auto">
                    {playlists.map((pl) => {
                      const isImported = importedPlaylistIds.has(pl.id);
                      const isImporting = importingPlaylistId === pl.id;
                      return (
                        <div
                          key={pl.id}
                          className="flex items-center justify-between gap-3 p-2.5 rounded-2xl bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] border border-[var(--border-subtle)] transition"
                        >
                          <div className="flex items-center gap-2.5 min-w-0 flex-1">
                            {pl.thumbnail ? (
                              <img
                                src={pl.thumbnail}
                                alt=""
                                className="w-10 h-10 rounded-xl object-cover bg-neutral-800 flex-shrink-0"
                              />
                            ) : (
                              <div className="w-10 h-10 rounded-xl bg-[var(--bg-surface-elevated)] flex items-center justify-center flex-shrink-0">
                                <Music2 className="w-4 h-4 text-[var(--text-muted)]" />
                              </div>
                            )}
                            <div className="min-w-0">
                              <p className="text-xs font-semibold text-[var(--text-primary)] truncate">
                                {pl.title}
                              </p>
                              <p className="text-[10px] text-[var(--text-muted)]">
                                {pl.trackCount} {pl.trackCount === 1 ? 'video' : 'videos'}
                              </p>
                            </div>
                          </div>
                          <button
                            onClick={() => handleImportPlaylist(pl)}
                            disabled={isImporting || isImported}
                            className={`flex items-center gap-1 px-2.5 py-1 rounded-lg text-[10px] font-semibold transition cursor-pointer flex-shrink-0 ${
                              isImported
                                ? 'bg-emerald-500/10 text-emerald-500 border border-emerald-500/30'
                                : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] border border-[var(--border-subtle)]'
                            } disabled:opacity-50 disabled:cursor-not-allowed`}
                          >
                            {isImporting ? (
                              <Loader2 className="w-3 h-3 animate-spin" />
                            ) : isImported ? (
                              <Check className="w-3 h-3" />
                            ) : (
                              <Download className="w-3 h-3" />
                            )}
                            {isImported ? 'Done' : 'Import'}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* Disconnect */}
              <button
                onClick={handleDisconnect}
                className="w-full flex items-center justify-center gap-2 py-2 px-3 rounded-2xl text-xs font-semibold text-rose-500 hover:text-rose-400 hover:bg-rose-500/10 border border-[var(--border-subtle)] transition cursor-pointer"
              >
                <Unplug className="w-3.5 h-3.5" />
                Disconnect YouTube
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
};
