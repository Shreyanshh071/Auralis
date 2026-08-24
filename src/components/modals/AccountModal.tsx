import React, { useState } from 'react';
import {
  X,
  Cloud,
  LogOut,
  MonitorPlay,
  Loader2,
  Check,
  AlertTriangle,
  Sun,
  Moon,
  Monitor,
  User,
  ListMusic,
  Download,
  ShieldCheck,
  FileText,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { usePlayer } from '../../context/PlayerContext';
import { isSignInCancellation } from '../../services/googleSignIn';
import { importYouTubePlaylist, extractPlaylistId } from '../../services/youtubeImporter';
import { searchYouTube } from '../../services/youtube';
import type { Track, ThemeMode } from '../../types/music';
import type { LegalTab } from './LegalModal';
import { YouTubeSyncModal } from './YouTubeSyncModal';

interface AccountModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Opens the shared Legal modal at the requested tab. */
  onOpenLegal: (tab: LegalTab) => void;
}

/** Upper bound on how many pasted lines we resolve, so a giant paste cannot fan
 *  out into hundreds of live searches. Communicated to the user, never silent. */
const UNIVERSAL_IMPORT_LINE_CAP = 50;

/**
 * Turn pasted text into a list of search queries.
 *
 * Two shapes are accepted so the "universal" importer is genuinely universal:
 *   • a JSON array — of strings, or of objects with title/name/track (+ artist)
 *   • newline-separated "Artist - Title" (or just "Title") lines, which is what
 *     Spotify / Apple Music / any tracklist copy-paste looks like
 *
 * We deliberately do NOT claim to log into Spotify or Apple Music — there is no
 * API access here. We resolve whatever text the user pastes against YouTube.
 */
function parseTracklist(raw: string): string[] {
  const trimmed = raw.trim();
  if (!trimmed) return [];

  // Try JSON first.
  try {
    const parsed = JSON.parse(trimmed);
    if (Array.isArray(parsed)) {
      const queries = parsed
        .map((item) => {
          if (typeof item === 'string') return item;
          if (item && typeof item === 'object') {
            const o = item as Record<string, unknown>;
            const title = (o.title || o.name || o.track || o.song) as string | undefined;
            const artist = (o.artist || o.artists || o.author) as string | undefined;
            if (title && artist) return `${artist} ${title}`;
            if (title) return title;
          }
          return '';
        })
        .filter((q): q is string => Boolean(q && q.trim()));
      if (queries.length > 0) return queries.slice(0, UNIVERSAL_IMPORT_LINE_CAP);
    }
  } catch {
    // Not JSON — fall through to line parsing.
  }

  return trimmed
    .split('\n')
    .map((l) => l.trim())
    // Drop obvious non-track lines (comments, numbering-only) but keep "Artist - Title".
    .filter((l) => l.length > 0 && !l.startsWith('#'))
    .slice(0, UNIVERSAL_IMPORT_LINE_CAP);
}

type ImportPhase = 'idle' | 'importing' | 'done' | 'error';

export const AccountModal: React.FC<AccountModalProps> = ({ isOpen, onClose, onOpenLegal }) => {
  const {
    user,
    isAuthAvailable,
    authError,
    isSyncing,
    lastSyncedAt,
    signInWithGoogle,
    logout,
  } = useAuth();
  const {
    settings,
    updateSettings,
    theme,
    setTheme,
    sleepTimerRemaining,
    setSleepTimer,
    importPlaylistToState,
    showToast,
  } = usePlayer();

  const [isLoggingIn, setIsLoggingIn] = useState(false);
  const [isYouTubeSyncOpen, setIsYouTubeSyncOpen] = useState(false);

  // Universal importer state
  const [importInput, setImportInput] = useState('');
  const [importPhase, setImportPhase] = useState<ImportPhase>('idle');
  const [importMessage, setImportMessage] = useState<string | null>(null);

  if (!isOpen) return null;

  const handleLogin = async () => {
    if (!isAuthAvailable) {
      showToast(authError ?? 'Sign-in is not configured for this build.', 'error');
      return;
    }
    try {
      setIsLoggingIn(true);
      await signInWithGoogle();
      showToast('Signed in with Google', 'success');
    } catch (err: any) {
      if (!isSignInCancellation(err)) {
        showToast(err?.message || 'Sign-in failed.', 'error');
      }
    } finally {
      setIsLoggingIn(false);
    }
  };

  const handleLogout = async () => {
    try {
      await logout();
      showToast('Signed out', 'info');
    } catch {
      showToast('Error signing out', 'error');
    }
  };

  const handleToggleCloudSync = () => {
    const next = !settings.cloudSyncEnabled;
    updateSettings({ cloudSyncEnabled: next });
    showToast(
      next ? 'Cloud sync enabled' : 'Cloud sync paused — local changes stay on this device',
      'info',
    );
  };

  const handleUniversalImport = async () => {
    const raw = importInput.trim();
    if (!raw || importPhase === 'importing') return;

    setImportPhase('importing');
    setImportMessage(null);

    // 1) A single YouTube / YT Music playlist link → real playlist fetch.
    const singleLine = !raw.includes('\n');
    const playlistId = singleLine ? extractPlaylistId(raw) : null;
    if (playlistId) {
      try {
        const playlist = await importYouTubePlaylist(raw);
        if (playlist && playlist.tracks.length > 0) {
          importPlaylistToState(playlist);
          setImportPhase('done');
          setImportMessage(`Imported "${playlist.title}" (${playlist.tracks.length} tracks).`);
          showToast(`Imported ${playlist.tracks.length} tracks`, 'success');
          setImportInput('');
          return;
        }
        setImportPhase('error');
        setImportMessage('That playlist could not be read from any public instance. Try again later, or paste the tracklist instead.');
        return;
      } catch {
        setImportPhase('error');
        setImportMessage('Playlist import failed. Try pasting the tracklist as text instead.');
        return;
      }
    }

    // 2) A pasted tracklist (Spotify / Apple Music / plain text / JSON).
    const queries = parseTracklist(raw);
    if (queries.length === 0) {
      setImportPhase('error');
      setImportMessage('Nothing to import. Paste a YouTube playlist link, or one "Artist - Title" per line.');
      return;
    }

    const resolved: Track[] = [];
    for (const q of queries) {
      try {
        const hits = await searchYouTube(q);
        if (hits[0]) resolved.push(hits[0]);
      } catch {
        // Skip an unresolvable line; keep going so one bad line can't abort all.
      }
    }

    if (resolved.length === 0) {
      setImportPhase('error');
      setImportMessage('Could not resolve any of those tracks on YouTube.');
      return;
    }

    importPlaylistToState({
      id: `import-${Date.now()}`,
      title: 'Imported Playlist',
      description: `Imported ${resolved.length} of ${queries.length} tracks`,
      cover: resolved[0]?.thumbnail,
      tracks: resolved,
      createdAt: Date.now(),
      isCustom: true,
    });
    setImportPhase('done');
    const capped = queries.length >= UNIVERSAL_IMPORT_LINE_CAP;
    setImportMessage(
      `Imported ${resolved.length} of ${queries.length} tracks${capped ? ` (capped at ${UNIVERSAL_IMPORT_LINE_CAP})` : ''}.`,
    );
    showToast(`Imported ${resolved.length} tracks`, 'success');
    setImportInput('');
  };

  const themeOptions: { id: ThemeMode; label: string; icon: typeof Sun }[] = [
    { id: 'dark', label: 'Dark', icon: Moon },
    { id: 'light', label: 'Light', icon: Sun },
    { id: 'system', label: 'System', icon: Monitor },
  ];

  const sleepOptions = [0, 15, 30, 45, 60];
  const activeSleepMinutes =
    sleepTimerRemaining && sleepTimerRemaining > 0 ? Math.ceil(sleepTimerRemaining / 60) : 0;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in">
      <div className="relative w-full max-w-lg max-h-[88vh] flex flex-col rounded-3xl bg-[var(--bg-popover)] border border-[var(--border-medium)] shadow-2xl text-[var(--text-primary)] overflow-hidden">
        {/* Header — Auralis brand title + close */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-[var(--border-subtle)]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-2xl bg-[var(--m3-primary)] flex items-center justify-center text-[var(--m3-on-primary)] font-black shadow-sm">
              A
            </div>
            <div>
              <h2 className="font-display font-black text-lg leading-none">Auralis</h2>
              <p className="text-[11px] text-[var(--text-muted)] mt-0.5">Account &amp; settings</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
            aria-label="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-5 space-y-6">
          {/* ── Account / Login ─────────────────────────────────────────── */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Account
            </h3>

            {!isAuthAvailable ? (
              <div className="flex items-start gap-2.5 p-3.5 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-600 dark:text-amber-300">
                <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
                <p className="text-xs leading-relaxed">
                  {authError ?? 'Sign-in is not configured for this build.'} Cloud sync and Google
                  sign-in are unavailable until Firebase is set up.
                </p>
              </div>
            ) : user ? (
              <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] space-y-3">
                <div className="flex items-center gap-3">
                  {user.photoURL ? (
                    <img
                      src={user.photoURL}
                      alt={user.displayName || 'User'}
                      className="w-11 h-11 rounded-full object-cover ring-1 ring-emerald-500/50"
                    />
                  ) : (
                    <div className="w-11 h-11 rounded-full bg-[var(--m3-primary)] flex items-center justify-center text-sm font-bold text-[var(--m3-on-primary)]">
                      {(user.displayName || user.email || 'U').charAt(0).toUpperCase()}
                    </div>
                  )}
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-bold truncate">{user.displayName || 'Auralis User'}</p>
                    <p className="text-[11px] text-[var(--text-muted)] truncate">{user.email}</p>
                  </div>
                  <button
                    onClick={handleLogout}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-[11px] font-semibold text-rose-500 hover:text-rose-400 hover:bg-rose-500/10 border border-[var(--border-subtle)] transition cursor-pointer flex-shrink-0"
                  >
                    <LogOut className="w-3.5 h-3.5" />
                    Sign Out
                  </button>
                </div>
              </div>
            ) : (
              <button
                onClick={handleLogin}
                disabled={isLoggingIn}
                className="w-full flex items-center justify-center gap-2.5 py-2.5 px-4 rounded-2xl bg-[var(--text-primary)] text-[var(--text-inverse)] font-bold text-sm hover:opacity-90 active:scale-[0.99] transition shadow-sm cursor-pointer disabled:opacity-60"
              >
                {isLoggingIn ? (
                  <Loader2 className="w-4 h-4 animate-spin" />
                ) : (
                  <svg className="w-4 h-4" viewBox="0 0 24 24">
                    <path fill="#4285F4" d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.82-2.4 3.68v3.05h3.88c2.27-2.09 3.66-5.17 3.66-9.17z" />
                    <path fill="#34A853" d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.1-6.72-4.93H1.25v3.15C3.26 21.36 7.36 24 12 24z" />
                    <path fill="#FBBC05" d="M5.28 14.27c-.25-.72-.38-1.49-.38-2.27s.13-1.55.38-2.27V6.58H1.25C.45 8.18 0 10.02 0 12s.45 3.82 1.25 5.42l4.03-3.15z" />
                    <path fill="#EA4335" d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.36 0 3.26 2.64 1.25 6.58l4.03 3.15c.95-2.83 3.6-4.98 6.72-4.98z" />
                  </svg>
                )}
                Sign in with Google
              </button>
            )}
            {!user && isAuthAvailable && (
              <p className="text-[11px] text-[var(--text-muted)] leading-relaxed px-0.5">
                Google sign-in creates your Auralis identity for cloud sync. It is separate from the
                YouTube import below.
              </p>
            )}
          </section>

          {/* ── Cloud Sync ──────────────────────────────────────────────── */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Cloud Sync
            </h3>
            <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)]">
              <div className="flex items-center justify-between gap-3">
                <div className="flex items-center gap-2.5 min-w-0">
                  <Cloud className="w-4 h-4 text-sky-500 flex-shrink-0" />
                  <div className="min-w-0">
                    <p className="text-xs font-semibold">Auto-sync my library</p>
                    <p className="text-[10px] text-[var(--text-muted)] leading-snug">
                      Favorites &amp; playlists to your private Firestore.
                    </p>
                  </div>
                </div>
                <button
                  role="switch"
                  aria-checked={settings.cloudSyncEnabled && !!user}
                  disabled={!user}
                  onClick={handleToggleCloudSync}
                  className={`relative w-11 h-6 rounded-full transition flex-shrink-0 cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed ${
                    settings.cloudSyncEnabled && user
                      ? 'bg-[var(--m3-primary)]'
                      : 'bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)]'
                  }`}
                  title={user ? 'Toggle cloud sync' : 'Sign in to enable cloud sync'}
                >
                  <span
                    className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${
                      settings.cloudSyncEnabled && user ? 'translate-x-5' : 'translate-x-0'
                    }`}
                  />
                </button>
              </div>
              {/* Honest status line — never claim a sync that didn't happen. */}
              <div className="mt-2.5 pt-2.5 border-t border-[var(--border-subtle)] text-[10px]">
                {!user ? (
                  <span className="text-[var(--text-muted)]">Sign in to enable cloud sync.</span>
                ) : !settings.cloudSyncEnabled ? (
                  <span className="text-[var(--text-muted)]">
                    Paused — local changes stay on this device.
                  </span>
                ) : isSyncing ? (
                  <span className="flex items-center gap-1.5 text-sky-400">
                    <Loader2 className="w-3 h-3 animate-spin" /> Syncing…
                  </span>
                ) : lastSyncedAt ? (
                  <span className="flex items-center gap-1.5 text-emerald-400">
                    <Check className="w-3 h-3" /> Synced
                  </span>
                ) : (
                  <span className="text-[var(--text-muted)]">Not yet synced this session.</span>
                )}
              </div>
            </div>
          </section>

          {/* ── Playlist Import ─────────────────────────────────────────── */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Import Playlists
            </h3>

            {/* YouTube Music Sync (OAuth) */}
            <button
              onClick={() => setIsYouTubeSyncOpen(true)}
              className="w-full flex items-center gap-3 p-3.5 rounded-2xl bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] border border-[var(--border-subtle)] transition cursor-pointer text-left"
            >
              <div className="p-2 rounded-xl bg-red-500/10 text-red-500 flex-shrink-0">
                <MonitorPlay className="w-4 h-4" />
              </div>
              <div className="min-w-0 flex-1">
                <p className="text-xs font-semibold">YouTube Music Sync</p>
                <p className="text-[10px] text-[var(--text-muted)] leading-snug">
                  Connect with Google (read-only) to import your liked songs &amp; playlists.
                </p>
              </div>
              <ListMusic className="w-4 h-4 text-[var(--text-muted)] flex-shrink-0" />
            </button>

            {/* Universal Importer */}
            <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] space-y-2.5">
              <div className="flex items-center gap-2">
                <Download className="w-4 h-4 text-[var(--m3-primary)]" />
                <p className="text-xs font-semibold">Universal Importer</p>
              </div>
              <p className="text-[10px] text-[var(--text-muted)] leading-relaxed">
                Paste a YouTube / YT Music playlist link, or a tracklist copied from Spotify, Apple
                Music, or anywhere — one <span className="font-mono">Artist - Title</span> per line
                (JSON arrays work too). Tracks are matched on YouTube.
              </p>
              <textarea
                value={importInput}
                onChange={(e) => {
                  setImportInput(e.target.value);
                  if (importPhase !== 'idle') {
                    setImportPhase('idle');
                    setImportMessage(null);
                  }
                }}
                rows={3}
                placeholder={'https://music.youtube.com/playlist?list=…\n— or —\nDaft Punk - Get Lucky\nTame Impala - The Less I Know The Better'}
                className="w-full resize-y rounded-xl bg-[var(--bg-input)] border border-[var(--border-subtle)] focus:border-[var(--border-strong)] focus:outline-none focus:ring-2 focus:ring-[var(--border-subtle)] px-3 py-2 text-xs text-[var(--text-primary)] placeholder-[var(--text-muted)] font-mono leading-relaxed transition"
              />
              {importMessage && (
                <div
                  className={`flex items-start gap-1.5 text-[10px] leading-snug ${
                    importPhase === 'error' ? 'text-amber-500' : 'text-emerald-500'
                  }`}
                >
                  {importPhase === 'error' ? (
                    <AlertTriangle className="w-3 h-3 mt-px flex-shrink-0" />
                  ) : (
                    <Check className="w-3 h-3 mt-px flex-shrink-0" />
                  )}
                  <span>{importMessage}</span>
                </div>
              )}
              <button
                onClick={handleUniversalImport}
                disabled={!importInput.trim() || importPhase === 'importing'}
                className="w-full flex items-center justify-center gap-2 py-2 px-3 rounded-xl bg-[var(--m3-primary)] text-[var(--m3-on-primary)] text-xs font-bold hover:bg-[var(--m3-primary-hover)] transition cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed active:scale-[0.99]"
              >
                {importPhase === 'importing' ? (
                  <>
                    <Loader2 className="w-3.5 h-3.5 animate-spin" /> Importing…
                  </>
                ) : (
                  <>
                    <Download className="w-3.5 h-3.5" /> Import
                  </>
                )}
              </button>
            </div>
          </section>

          {/* ── Settings & Integrations ─────────────────────────────────── */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Settings
            </h3>

            {/* Theme */}
            <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] space-y-2.5">
              <p className="text-xs font-semibold">Theme</p>
              <div className="grid grid-cols-3 gap-2">
                {themeOptions.map((opt) => {
                  const Icon = opt.icon;
                  const active = theme === opt.id;
                  return (
                    <button
                      key={opt.id}
                      onClick={() => setTheme(opt.id)}
                      className={`flex flex-col items-center gap-1.5 py-2.5 rounded-xl text-[11px] font-semibold transition cursor-pointer border ${
                        active
                          ? 'bg-[var(--m3-primary-12)] text-[var(--m3-primary)] border-[var(--m3-primary-24)]'
                          : 'bg-[var(--bg-surface-elevated)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
                      }`}
                    >
                      <Icon className="w-4 h-4" />
                      {opt.label}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Sleep timer */}
            <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] space-y-2.5">
              <div className="flex items-center justify-between">
                <p className="text-xs font-semibold">Sleep timer</p>
                {activeSleepMinutes > 0 && (
                  <span className="text-[10px] font-mono text-[var(--m3-primary)]">
                    {Math.floor(sleepTimerRemaining! / 60)}:
                    {String(sleepTimerRemaining! % 60).padStart(2, '0')} left
                  </span>
                )}
              </div>
              <div className="flex flex-wrap gap-2">
                {sleepOptions.map((min) => {
                  const active = min === 0 ? activeSleepMinutes === 0 : activeSleepMinutes === min;
                  return (
                    <button
                      key={min}
                      onClick={() => setSleepTimer(min === 0 ? null : min)}
                      className={`px-3 py-1.5 rounded-xl text-[11px] font-semibold transition cursor-pointer border ${
                        active
                          ? 'bg-[var(--m3-primary-12)] text-[var(--m3-primary)] border-[var(--m3-primary-24)]'
                          : 'bg-[var(--bg-surface-elevated)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
                      }`}
                    >
                      {min === 0 ? 'Off' : `${min}m`}
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Legal */}
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => onOpenLegal('privacy')}
                className="flex items-center justify-center gap-2 py-2.5 rounded-2xl bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] border border-[var(--border-subtle)] text-xs font-semibold text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
              >
                <ShieldCheck className="w-3.5 h-3.5" />
                Privacy
              </button>
              <button
                onClick={() => onOpenLegal('terms')}
                className="flex items-center justify-center gap-2 py-2.5 rounded-2xl bg-[var(--bg-card)] hover:bg-[var(--bg-card-hover)] border border-[var(--border-subtle)] text-xs font-semibold text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
              >
                <FileText className="w-3.5 h-3.5" />
                Terms
              </button>
            </div>
          </section>
        </div>
      </div>

      {/* Nested YouTube Sync modal, launched from the Import section above. */}
      <YouTubeSyncModal isOpen={isYouTubeSyncOpen} onClose={() => setIsYouTubeSyncOpen(false)} />
    </div>
  );
};
