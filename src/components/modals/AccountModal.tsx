import React, { useState } from 'react';
import {
  X,
  LogOut,
  MonitorPlay,
  Loader2,
  Sun,
  Moon,
  User,
  ListMusic,
  ShieldCheck,
  FileText,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { usePlayer } from '../../context/PlayerContext';
import { isSignInCancellation } from '../../services/googleSignIn';
import type { ThemeMode } from '../../types/music';
import type { LegalTab } from './LegalModal';
import { YouTubeSyncModal } from './YouTubeSyncModal';

interface AccountModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** Opens the shared Legal modal at the requested tab. */
  onOpenLegal: (tab: LegalTab) => void;
}

export const AccountModal: React.FC<AccountModalProps> = ({ isOpen, onClose, onOpenLegal }) => {
  const {
    user,
    isAuthAvailable,
    authError,
    signInWithGoogle,
    logout,
  } = useAuth();
  const {
    theme,
    setTheme,
    sleepTimerRemaining,
    setSleepTimer,
    showToast,
  } = usePlayer();

  const [isLoggingIn, setIsLoggingIn] = useState(false);
  const [isYouTubeSyncOpen, setIsYouTubeSyncOpen] = useState(false);

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

  const themeOptions: { id: ThemeMode; label: string; icon: typeof Sun }[] = [
    { id: 'dark', label: 'Dark', icon: Moon },
    { id: 'light', label: 'Light', icon: Sun },
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
            className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
            title="Close"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Modal body */}
        <div className="flex-1 overflow-y-auto p-6 space-y-6">
          {/* ── Account Identity & Auth ─────────────────────────────────── */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Account
            </h3>

            {user ? (
              <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] flex items-center justify-between gap-3">
                <div className="flex items-center gap-3 min-w-0">
                  {user.photoURL ? (
                    <img
                      src={user.photoURL}
                      alt={user.displayName || 'User avatar'}
                      className="w-10 h-10 rounded-full object-cover border border-[var(--border-subtle)] flex-shrink-0"
                      referrerPolicy="no-referrer"
                    />
                  ) : (
                    <div className="w-10 h-10 rounded-full bg-[var(--m3-primary-12)] text-[var(--m3-primary)] flex items-center justify-center font-bold flex-shrink-0">
                      <User className="w-5 h-5" />
                    </div>
                  )}
                  <div className="min-w-0">
                    <p className="font-bold text-sm truncate">
                      {user.displayName || 'Signed-in user'}
                    </p>
                    <p className="text-xs text-[var(--text-muted)] truncate">{user.email}</p>
                  </div>
                </div>
                <div className="flex items-center gap-1.5 flex-shrink-0">
                  <button
                    onClick={handleLogout}
                    className="flex items-center gap-1 px-3 py-1.5 rounded-xl border border-red-500/30 text-red-400 hover:bg-red-500/10 text-xs font-semibold transition cursor-pointer"
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
                Google sign-in creates your Auralis identity.
              </p>
            )}
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
          </section>

          {/* ── Settings & Integrations ─────────────────────────────────── */}
          <section className="space-y-3">
            <h3 className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Settings
            </h3>

            {/* Theme */}
            <div className="p-3.5 rounded-2xl bg-[var(--bg-card)] border border-[var(--border-subtle)] space-y-2.5">
              <p className="text-xs font-semibold">Theme</p>
              <div className="grid grid-cols-2 gap-2">
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
