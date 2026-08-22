import React, { useState, useEffect, useRef } from 'react';
import {
  Search,
  X,
  Music2,
  Command,
  ChevronLeft,
  ChevronRight,
  LogOut,
  Cloud,
  Loader2,
  AlertTriangle,
  Sun,
  Moon,
  Monitor,
  Check,
} from 'lucide-react';
import { searchYouTube, SearchUnavailableError } from '../../services/youtube';
import type { Track, ThemeMode } from '../../types/music';
import { usePlayer } from '../../context/PlayerContext';
import { useAuth } from '../../context/AuthContext';

interface HeaderProps {
  onSearchSelect?: (track: Track) => void;
  activeView: string;
  setActiveView: (view: string) => void;
  /**
   * Called when the user submits the search box (Enter). Receives the typed
   * query so it actually reaches the Explore view instead of being dropped.
   */
  onSubmitSearch?: (query: string) => void;
}

// Simple view history stack for real back/forward navigation
const viewHistory: string[] = ['home'];
let viewHistoryIndex = 0;

export const Header: React.FC<HeaderProps> = ({
  onSearchSelect,
  activeView,
  setActiveView,
  onSubmitSearch,
}) => {
  const [query, setQuery] = useState('');
  const [isSearching, setIsSearching] = useState(false);
  const [results, setResults] = useState<Track[]>([]);
  const [isOpenDropdown, setIsOpenDropdown] = useState(false);
  const [isUserMenuOpen, setIsUserMenuOpen] = useState(false);
  const [isThemeMenuOpen, setIsThemeMenuOpen] = useState(false);
  const [isLoggingIn, setIsLoggingIn] = useState(false);
  /** Non-null when the typeahead search could not reach any provider. */
  const [searchError, setSearchError] = useState<string | null>(null);

  const { playTrack, showToast, theme, effectiveTheme, setTheme } = usePlayer();
  const { user, isSyncing, isAuthAvailable, authError, lastSyncedAt, signInWithGoogle, logout } =
    useAuth();
  
  const searchContainerRef = useRef<HTMLDivElement>(null);
  const userMenuRef = useRef<HTMLDivElement>(null);
  const themeMenuRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Track view changes in history
  useEffect(() => {
    if (viewHistory[viewHistoryIndex] !== activeView) {
      viewHistory.splice(viewHistoryIndex + 1, viewHistory.length, activeView);
      viewHistoryIndex = viewHistory.length - 1;
    }
  }, [activeView]);

  const canGoBack = viewHistoryIndex > 0;
  const canGoForward = viewHistoryIndex < viewHistory.length - 1;

  const goBack = () => {
    if (canGoBack) {
      viewHistoryIndex--;
      setActiveView(viewHistory[viewHistoryIndex]);
    }
  };

  const goForward = () => {
    if (canGoForward) {
      viewHistoryIndex++;
      setActiveView(viewHistory[viewHistoryIndex]);
    }
  };

  // Global Ctrl+K shortcut
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, []);

  // Debounced typeahead search. A failure is reported in the dropdown; it is never
  // replaced with placeholder content.
  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      setSearchError(null);
      setIsOpenDropdown(false);
      return;
    }

    let cancelled = false;
    const timer = setTimeout(async () => {
      setIsSearching(true);
      setSearchError(null);
      try {
        const res = await searchYouTube(query);
        if (cancelled) return;
        setResults(res);
        setIsOpenDropdown(true);
      } catch (e) {
        if (cancelled) return;
        setResults([]);
        setSearchError(
          e instanceof SearchUnavailableError
            ? 'Search is unavailable right now.'
            : 'Search failed. Please try again.'
        );
        setIsOpenDropdown(true);
      } finally {
        if (!cancelled) setIsSearching(false);
      }
    }, 180);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query]);

  // Click outside search, user menu, and theme menu
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        searchContainerRef.current &&
        !searchContainerRef.current.contains(event.target as Node)
      ) {
        setIsOpenDropdown(false);
      }
      if (
        userMenuRef.current &&
        !userMenuRef.current.contains(event.target as Node)
      ) {
        setIsUserMenuOpen(false);
      }
      if (
        themeMenuRef.current &&
        !themeMenuRef.current.contains(event.target as Node)
      ) {
        setIsThemeMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const handleSelectTrack = (track: Track) => {
    playTrack(track);
    setIsOpenDropdown(false);
    setQuery('');
    if (onSearchSelect) onSearchSelect(track);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      const submitted = query.trim();
      if (!submitted) return;
      // Hand the actual query to the app so Explore runs it. Previously this only
      // switched views and the typed text was discarded.
      if (onSubmitSearch) {
        onSubmitSearch(submitted);
      } else {
        setActiveView('explore');
      }
      setIsOpenDropdown(false);
    }
  };

  const handleLogin = async () => {
    if (!isAuthAvailable) {
      // Do not show a success state for something that cannot happen.
      showToast(authError ?? 'Sign-in is not configured for this build.', 'error');
      return;
    }
    try {
      setIsLoggingIn(true);
      await signInWithGoogle();
      showToast('Signed in with Google', 'success');
    } catch (err: any) {
      if (err?.code !== 'auth/popup-closed-by-user') {
        showToast(err?.message || 'Sign-in failed.', 'error');
      }
    } finally {
      setIsLoggingIn(false);
    }
  };

  const handleLogout = async () => {
    try {
      await logout();
      setIsUserMenuOpen(false);
      showToast('Signed out', 'info');
    } catch (err) {
      showToast('Error signing out', 'error');
    }
  };

  return (
    <header className="sticky top-0 z-30 flex items-center justify-between px-4 sm:px-8 py-3.5 backdrop-blur-2xl bg-[var(--bg-header)] border-b border-[var(--border-subtle)] text-[var(--text-primary)] transition-colors duration-200">
      {/* Navigation history arrows + Search */}
      <div className="flex items-center gap-4 flex-1 max-w-xl" ref={searchContainerRef}>
        <div className="hidden sm:flex items-center gap-1">
          <button
            onClick={goBack}
            disabled={!canGoBack}
            className={`p-1.5 rounded-full transition ${
              canGoBack
                ? 'hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                : 'text-[var(--text-subtle)] opacity-40 cursor-not-allowed'
            }`}
            title="Go Back"
          >
            <ChevronLeft className="w-4 h-4" />
          </button>
          <button
            onClick={goForward}
            disabled={!canGoForward}
            className={`p-1.5 rounded-full transition ${
              canGoForward
                ? 'hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
                : 'text-[var(--text-subtle)] opacity-40 cursor-not-allowed'
            }`}
            title="Go Forward"
          >
            <ChevronRight className="w-4 h-4" />
          </button>
        </div>

        <div className="relative w-full">
          <div className="relative flex items-center">
            {isSearching ? (
              <div className="absolute left-3.5 w-4 h-4 border-2 border-purple-500 border-t-transparent rounded-full animate-spin pointer-events-none" />
            ) : (
              <Search className="absolute left-3.5 w-4 h-4 text-[var(--text-muted)] pointer-events-none" />
            )}
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
              onFocus={() => query.trim() && setIsOpenDropdown(true)}
              placeholder="What do you want to play?"
              className="w-full pl-10 pr-16 py-2 bg-[var(--bg-input)] hover:bg-[var(--bg-card-hover)] focus:bg-[var(--bg-input-focus)] text-xs sm:text-sm text-[var(--text-primary)] placeholder-[var(--text-muted)] rounded-full border border-[var(--border-subtle)] focus:border-[var(--border-strong)] focus:outline-none focus:ring-2 focus:ring-[var(--border-subtle)] transition"
            />
            {query ? (
              <button
                onClick={() => {
                  setQuery('');
                  setResults([]);
                  setSearchError(null);
                }}
                className="absolute right-3 p-1 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            ) : (
              <div className="absolute right-3 hidden sm:flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] text-[10px] font-mono text-[var(--text-muted)]">
                <Command className="w-2.5 h-2.5" />
                <span>K</span>
              </div>
            )}
          </div>

          {/* Instant Search Dropdown */}
          {isOpenDropdown && (
            <div className="absolute top-full left-0 right-0 mt-1.5 py-1.5 bg-[var(--bg-popover)] border border-[var(--border-medium)] rounded-2xl shadow-2xl z-50 max-h-96 overflow-y-auto">
              <div className="px-3.5 py-1.5 text-[11px] font-semibold tracking-wide text-[var(--text-muted)] border-b border-[var(--border-subtle)] flex items-center justify-between">
                <span>{isSearching ? 'Searching...' : searchError ? 'Search error' : 'Top Matches'}</span>
                {results.length > 0 && <span className="text-[10px] text-[var(--text-subtle)] font-mono">{results.length} found</span>}
              </div>

              {searchError && !isSearching && (
                <div className="px-4 py-6 text-center space-y-2.5">
                  <AlertTriangle className="w-6 h-6 text-amber-400 mx-auto" />
                  <p className="text-xs text-[var(--text-secondary)] font-medium">{searchError}</p>
                  <button
                    onClick={() => {
                      const submitted = query.trim();
                      if (!submitted) return;
                      if (onSubmitSearch) onSubmitSearch(submitted);
                      else setActiveView('explore');
                      setIsOpenDropdown(false);
                    }}
                    className="text-[11px] font-semibold text-purple-400 hover:text-purple-300 transition"
                  >
                    Retry in Explore
                  </button>
                </div>
              )}

              {results.length === 0 && !isSearching && !searchError && (
                <div className="px-4 py-8 text-center text-xs text-[var(--text-muted)] font-medium">
                  No matching tracks found for "{query}"
                </div>
              )}

              <div className="p-1 space-y-0.5">
                {results.map((track) => (
                  <div
                    key={track.id}
                    onClick={() => handleSelectTrack(track)}
                    className="flex items-center gap-3 px-3 py-2 rounded-xl hover:bg-[var(--bg-surface-hover)] cursor-pointer transition group"
                  >
                    <img
                      src={track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`}
                      alt=""
                      loading="lazy"
                      referrerPolicy="no-referrer"
                      className="w-9 h-9 rounded-lg object-cover bg-neutral-800 flex-shrink-0 shadow-sm"
                      onError={(e) => {
                        const target = e.currentTarget;
                        if (!target.src.includes('hqdefault') && track.id) {
                          target.src = `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`;
                        }
                      }}
                    />
                    <div className="flex-1 min-w-0">
                      <p className="text-xs sm:text-sm font-semibold text-[var(--text-primary)] truncate group-hover:text-purple-400 transition">
                        {track.title}
                      </p>
                      <p className="text-[11px] text-[var(--text-muted)] truncate">{track.artist}</p>
                    </div>
                    <Music2 className="w-3.5 h-3.5 text-[var(--text-muted)] opacity-0 group-hover:opacity-100 transition" />
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Right controls: Navigation links, Theme Toggle, & Google Auth */}
      <div className="flex items-center gap-2 sm:gap-3">
        <button
          onClick={() => setActiveView('explore')}
          className="hidden md:flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-semibold text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)] transition"
        >
          <span>Explore</span>
        </button>

        <button
          onClick={() => setActiveView('library')}
          className="hidden md:flex items-center gap-2 px-3 py-1.5 rounded-full text-xs font-semibold text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)] transition"
        >
          <span>Library</span>
        </button>

        {/* Theme Toggle Menu */}
        <div className="relative" ref={themeMenuRef}>
          <button
            onClick={() => setIsThemeMenuOpen(!isThemeMenuOpen)}
            className="p-2 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition"
            title={`Theme: ${theme.charAt(0).toUpperCase() + theme.slice(1)} (${effectiveTheme})`}
            aria-label="Toggle theme mode"
          >
            {theme === 'system' ? (
              <Monitor className="w-4 h-4" />
            ) : effectiveTheme === 'dark' ? (
              <Moon className="w-4 h-4" />
            ) : (
              <Sun className="w-4 h-4 text-amber-500" />
            )}
          </button>

          {/* Theme Dropdown */}
          {isThemeMenuOpen && (
            <div className="absolute right-0 top-full mt-2 w-40 p-1.5 bg-[var(--bg-popover)] border border-[var(--border-medium)] rounded-2xl shadow-2xl z-50 animate-in fade-in slide-in-from-top-2 duration-150">
              <div className="px-2.5 py-1.5 text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] border-b border-[var(--border-subtle)] mb-1">
                Theme
              </div>
              {(
                [
                  { id: 'dark', label: 'Dark', icon: Moon },
                  { id: 'light', label: 'Light', icon: Sun },
                  { id: 'system', label: 'System', icon: Monitor },
                ] as const
              ).map((opt) => {
                const IconComponent = opt.icon;
                const isSelected = theme === opt.id;
                return (
                  <button
                    key={opt.id}
                    onClick={() => {
                      setTheme(opt.id);
                      setIsThemeMenuOpen(false);
                    }}
                    className={`w-full flex items-center justify-between px-2.5 py-2 rounded-xl text-xs font-semibold transition cursor-pointer ${
                      isSelected
                        ? 'bg-[var(--bg-surface-hover)] text-[var(--text-primary)] font-bold'
                        : 'text-[var(--text-secondary)] hover:bg-[var(--bg-surface-hover)] hover:text-[var(--text-primary)]'
                    }`}
                  >
                    <div className="flex items-center gap-2">
                      <IconComponent className="w-3.5 h-3.5" />
                      <span>{opt.label}</span>
                    </div>
                    {isSelected && <Check className="w-3.5 h-3.5 text-purple-400" />}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* User / Google Sign-in Menu */}
        <div className="relative" ref={userMenuRef}>
          {user ? (
            <div className="flex items-center gap-2">
              <button
                onClick={() => setIsUserMenuOpen(!isUserMenuOpen)}
                className="flex items-center gap-2.5 p-1 sm:px-2.5 sm:py-1 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] transition text-left"
              >
                {user.photoURL ? (
                  <img
                    src={user.photoURL}
                    alt={user.displayName || 'User'}
                    className="w-7 h-7 rounded-full object-cover ring-1 ring-emerald-500/50"
                  />
                ) : (
                  <div className="w-7 h-7 rounded-full bg-gradient-to-tr from-purple-600 to-emerald-500 flex items-center justify-center text-xs font-bold text-white">
                    {(user.displayName || user.email || 'U').charAt(0).toUpperCase()}
                  </div>
                )}
                <span className="hidden sm:inline text-xs font-medium text-[var(--text-primary)] max-w-[100px] truncate">
                  {user.displayName?.split(' ')[0] || 'Account'}
                </span>
                {isSyncing && (
                  <Loader2 className="w-3 h-3 text-emerald-400 animate-spin" />
                )}
              </button>

              {/* User Dropdown */}
              {isUserMenuOpen && (
                <div className="absolute right-0 top-full mt-2 w-56 p-2 bg-[var(--bg-popover)] border border-[var(--border-medium)] rounded-2xl shadow-2xl z-50 animate-in fade-in slide-in-from-top-2 duration-150">
                  <div className="px-3 py-2.5 border-b border-[var(--border-subtle)] mb-1">
                    <p className="text-xs font-semibold text-[var(--text-primary)] truncate">
                      {user.displayName || 'Auralis User'}
                    </p>
                    <p className="text-[11px] text-[var(--text-muted)] truncate">{user.email}</p>
                    {/*
                      Honest scope statement. This is Firebase Google sign-in with
                      Firestore sync of Auralis favorites and playlists only. It is
                      NOT YouTube Music account sync — no YouTube Music library,
                      artists, albums, or playlists are read or written.
                    */}
                    <div className="mt-2 space-y-1">
                      {authError ? (
                        <div className="flex items-start gap-1.5 text-[10px] text-amber-400">
                          <AlertTriangle className="w-3 h-3 mt-px flex-shrink-0" />
                          <span className="leading-snug">{authError}</span>
                        </div>
                      ) : isSyncing ? (
                        <div className="flex items-center gap-1.5 text-[10px] text-sky-400">
                          <Loader2 className="w-3 h-3 animate-spin" />
                          <span>Syncing…</span>
                        </div>
                      ) : lastSyncedAt ? (
                        <div className="flex items-center gap-1.5 text-[10px] text-emerald-400">
                          <Cloud className="w-3 h-3" />
                          <span>Favorites &amp; playlists synced</span>
                        </div>
                      ) : (
                        <div className="flex items-center gap-1.5 text-[10px] text-[var(--text-muted)]">
                          <Cloud className="w-3 h-3" />
                          <span>Not yet synced</span>
                        </div>
                      )}
                      <p className="text-[10px] text-[var(--text-muted)] leading-snug">
                        Auralis favorites and playlists only — not YouTube Music library sync.
                      </p>
                    </div>
                  </div>

                  <button
                    onClick={handleLogout}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-xs font-medium text-red-500 hover:text-red-400 hover:bg-red-500/10 rounded-xl transition"
                  >
                    <LogOut className="w-3.5 h-3.5" />
                    <span>Sign Out</span>
                  </button>
                </div>
              )}
            </div>
          ) : (
            <button
              onClick={handleLogin}
              disabled={isLoggingIn || !isAuthAvailable}
              title={isAuthAvailable ? 'Sign in with Google' : (authError ?? undefined)}
              className={`flex items-center gap-2 px-3.5 py-1.5 rounded-full font-semibold text-xs transition shadow-sm ${
                isAuthAvailable
                  ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] hover:opacity-90 active:scale-95'
                  : 'bg-[var(--bg-surface-elevated)] text-[var(--text-muted)] border border-[var(--border-subtle)] cursor-not-allowed'
              }`}
            >
              {isLoggingIn ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <svg className="w-3.5 h-3.5" viewBox="0 0 24 24">
                  <path
                    fill="#4285F4"
                    d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.82-2.4 3.68v3.05h3.88c2.27-2.09 3.66-5.17 3.66-9.17z"
                  />
                  <path
                    fill="#34A853"
                    d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.1-6.72-4.93H1.25v3.15C3.26 21.36 7.36 24 12 24z"
                  />
                  <path
                    fill="#FBBC05"
                    d="M5.28 14.27c-.25-.72-.38-1.49-.38-2.27s.13-1.55.38-2.27V6.58H1.25C.45 8.18 0 10.02 0 12s.45 3.82 1.25 5.42l4.03-3.15z"
                  />
                  <path
                    fill="#EA4335"
                    d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.36 0 3.26 2.64 1.25 6.58l4.03 3.15c.95-2.83 3.6-4.98 6.72-4.98z"
                  />
                </svg>
              )}
              <span>{isAuthAvailable ? 'Sign In' : 'Sign-in unavailable'}</span>
            </button>
          )}
        </div>
      </div>
    </header>
  );
};

