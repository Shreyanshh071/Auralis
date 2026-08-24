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
  Radio,
  User,
  MonitorPlay,
} from 'lucide-react';
import { searchYouTube, SearchUnavailableError } from '../../services/youtube';
import { getSearchSuggestions } from '../../services/searchSuggestions';
import type { Track, ThemeMode } from '../../types/music';
import { usePlayer } from '../../context/PlayerContext';
import { useAuth } from '../../context/AuthContext';
import { useListenTogether } from '../../context/ListenTogetherContext';
import { isSignInCancellation } from '../../services/googleSignIn';
import { isLetterboxedThumbnail } from '../../services/artwork';
import { YouTubeSyncModal } from '../modals/YouTubeSyncModal';

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
  const [suggestions, setSuggestions] = useState<string[]>([]);
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
  const { isInRoom, isHost, roomCode, members, setIsModalOpen } = useListenTogether();
  const [isYouTubeSyncOpen, setIsYouTubeSyncOpen] = useState(false);
  
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

  // Debounced search suggestions and typeahead tracks
  useEffect(() => {
    if (!query.trim()) {
      setSuggestions([]);
      setResults([]);
      setSearchError(null);
      setIsOpenDropdown(false);
      return;
    }

    let cancelled = false;
    const abortController = new AbortController();
    const capturedQuery = query;

    const timer = setTimeout(async () => {
      setIsSearching(true);
      setSearchError(null);
      try {
        // Fetch suggestions and track preview concurrently
        const [suggs, tracks] = await Promise.allSettled([
          getSearchSuggestions(capturedQuery, abortController.signal),
          searchYouTube(capturedQuery),
        ]);

        if (cancelled) return;

        if (suggs.status === 'fulfilled') {
          setSuggestions(suggs.value);
        }
        if (tracks.status === 'fulfilled') {
          setResults(tracks.value);
        }
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
    }, 250);

    return () => {
      cancelled = true;
      abortController.abort();
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

  const handleSelectSuggestion = (suggestionText: string) => {
    setQuery(suggestionText);
    setIsOpenDropdown(false);
    if (onSubmitSearch) {
      onSubmitSearch(suggestionText);
    } else {
      setActiveView('explore');
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      const submitted = query.trim();
      if (!submitted) return;
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
      // A user-cancelled sign-in (web popup closed, or native sheet dismissed)
      // is not an error — stay silent. Surface everything else.
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
      setIsUserMenuOpen(false);
      showToast('Signed out', 'info');
    } catch (err) {
      showToast('Error signing out', 'error');
    }
  };

  return (
    <>
    <header className="sticky top-0 z-30 flex items-center justify-between px-[max(0.75rem,env(safe-area-inset-left,0px))] pr-[max(0.75rem,env(safe-area-inset-right,0px))] sm:px-6 md:px-8 pt-[max(0.75rem,env(safe-area-inset-top,0px))] pb-3 backdrop-blur-2xl bg-[var(--bg-header)] border-b border-[var(--border-subtle)] text-[var(--text-primary)] transition-colors duration-200 gap-2 sm:gap-3 md:gap-4">
      {/* Navigation history arrows + Flexible Search */}
      <div className="flex items-center gap-2 sm:gap-3 flex-1 min-w-0" ref={searchContainerRef}>
        <div className="hidden sm:flex items-center gap-1 flex-shrink-0">
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

        <div className="relative w-full min-w-0 max-w-2xl">
          <div className="relative flex items-center">
            {isSearching ? (
              <div className="absolute left-3.5 w-4 h-4 border-2 border-[var(--m3-primary)] border-t-transparent rounded-full animate-spin pointer-events-none" />
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
              className="w-full pl-10 pr-10 py-2 bg-[var(--bg-input)] hover:bg-[var(--bg-card-hover)] focus:bg-[var(--bg-input-focus)] text-xs sm:text-sm text-[var(--text-primary)] placeholder-[var(--text-muted)] rounded-full border border-[var(--border-subtle)] focus:border-[var(--border-strong)] focus:outline-none focus:ring-2 focus:ring-[var(--border-subtle)] transition"
            />
            {query ? (
              <button
                onClick={() => {
                  setQuery('');
                  setSuggestions([]);
                  setResults([]);
                  setSearchError(null);
                  setIsOpenDropdown(false);
                  // Reset the Explore view to its clean history-only state.
                  if (onSubmitSearch) onSubmitSearch('');
                }}
                className="absolute right-3 p-1 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
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

          {/* Instant Search Dropdown: Suggestions + Top Track Matches */}
          {isOpenDropdown && (
            <div className="absolute top-full left-0 right-0 mt-1.5 py-1.5 bg-[var(--bg-popover)] border border-[var(--border-medium)] rounded-2xl shadow-2xl z-50 max-h-96 overflow-y-auto">
              {searchError && !isSearching && (
                <div className="px-4 py-5 text-center space-y-2">
                  <AlertTriangle className="w-5 h-5 text-amber-400 mx-auto" />
                  <p className="text-xs text-[var(--text-secondary)] font-medium">{searchError}</p>
                  <button
                    onClick={() => {
                      const submitted = query.trim();
                      if (!submitted) return;
                      if (onSubmitSearch) onSubmitSearch(submitted);
                      else setActiveView('explore');
                      setIsOpenDropdown(false);
                    }}
                    className="text-[11px] font-semibold text-[var(--m3-primary)] hover:text-[var(--m3-primary-hover)] transition cursor-pointer"
                  >
                    Search in Explore
                  </button>
                </div>
              )}

              {/* Music Search Suggestions */}
              {suggestions.length > 0 && (
                <div className="py-1">
                  {suggestions.map((s, idx) => (
                    <button
                      key={idx}
                      onClick={() => handleSelectSuggestion(s)}
                      className="w-full flex items-center gap-3 px-3.5 py-2 hover:bg-[var(--bg-surface-hover)] text-left cursor-pointer transition group"
                    >
                      <Search className="w-3.5 h-3.5 text-[var(--text-muted)] group-hover:text-[var(--m3-primary)] transition flex-shrink-0" />
                      <span className="text-xs font-medium text-[var(--text-primary)] truncate group-hover:text-[var(--m3-primary)] transition">
                        {s}
                      </span>
                    </button>
                  ))}
                </div>
              )}

              {/* Top Track Matches */}
              {results.length > 0 && (
                <div className={`${suggestions.length > 0 ? 'border-t border-[var(--border-subtle)] mt-1 pt-1.5' : ''} p-1 space-y-0.5`}>
                  <div className="px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] flex items-center justify-between">
                    <span>Songs</span>
                    <span className="font-mono text-[9px]">{results.length} found</span>
                  </div>
                  {results.slice(0, 4).map((track) => (
                    <div
                      key={track.id}
                      onClick={() => handleSelectTrack(track)}
                      className="flex items-center gap-2.5 px-2.5 py-1.5 rounded-xl hover:bg-[var(--bg-surface-hover)] cursor-pointer transition group"
                    >
                      <div className="w-8 h-8 rounded-lg overflow-hidden bg-neutral-800 flex-shrink-0 shadow-sm">
                        <img
                          src={track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`}
                          alt=""
                          loading="lazy"
                          referrerPolicy="no-referrer"
                          className={`w-full h-full object-cover aspect-square ${
                            isLetterboxedThumbnail(track.thumbnail || `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`)
                              ? 'scale-[1.35]'
                              : 'scale-100'
                          }`}
                          onError={(e) => {
                            const target = e.currentTarget;
                            if (!target.src.includes('hqdefault') && track.id) {
                              target.src = `https://i.ytimg.com/vi/${track.id}/hqdefault.jpg`;
                            }
                          }}
                        />
                      </div>
                      <div className="flex-1 min-w-0">
                        <p className="text-xs font-semibold text-[var(--text-primary)] truncate group-hover:text-[var(--m3-primary)] transition">
                          {track.title}
                        </p>
                        <p className="text-[10px] text-[var(--text-muted)] truncate">{track.artist}</p>
                      </div>
                      <Music2 className="w-3.5 h-3.5 text-[var(--text-muted)] opacity-0 group-hover:opacity-100 transition flex-shrink-0" />
                    </div>
                  ))}
                </div>
              )}

              {/* Loading row. Without it the dropdown collapsed to an empty
                  sliver while a query was in flight and then popped back open,
                  which read as the header jumping. Same padding as the
                  "Press Enter" row so the height stays put between states. */}
              {isSearching && suggestions.length === 0 && results.length === 0 && !searchError && (
                <div className="flex items-center justify-center gap-2 px-4 py-6 text-xs font-medium text-[var(--text-muted)]">
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  <span>Searching…</span>
                </div>
              )}

              {suggestions.length === 0 && results.length === 0 && !isSearching && !searchError && (
                <div className="px-4 py-6 text-center text-xs text-[var(--text-muted)] font-medium">
                  Press Enter to search for "{query}"
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Right controls: [ Listen Together ] [ Theme Toggle ] [ Google Sign In (if signed out) ] [ Profile Circle ]
       *
       * The text labels here only appear from `lg` up, not from `sm`. The
       * desktop sidebar starts at `md` and takes 224px away from this header,
       * so the space available for the search box actually *shrinks* at 768px.
       * With labels on at `sm` this whole cluster is 327px of
       * non-shrinking content and the search input collapsed to ~82px on a
       * portrait tablet. Icon-only pills (each with a `title`) keep every
       * control reachable and give the search box ~260px back. */}
      <div className="flex items-center gap-1.5 sm:gap-2.5 md:gap-3 flex-shrink-0">
        {/* Listen Together Button */}
        <button
          onClick={() => setIsModalOpen(true)}
          className={`flex items-center gap-1.5 p-2 lg:px-3 lg:py-1.5 rounded-full text-xs font-semibold transition cursor-pointer flex-shrink-0 ${
            isInRoom
              ? 'bg-[var(--m3-primary-12)] text-[var(--m3-primary)] border border-[var(--m3-primary-24)] hover:bg-[var(--m3-primary-24)] shadow-sm'
              : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
          }`}
          title={isInRoom ? `Listen Together: Room ${roomCode} (${members.length} listening)` : 'Listen Together'}
        >
          <Radio className={`w-4 h-4 ${isInRoom ? 'text-[var(--m3-primary)] animate-pulse' : ''}`} />
          <span className="hidden lg:inline">
            {isInRoom ? (isHost ? `Host (${roomCode})` : `Room ${roomCode}`) : 'Listen Together'}
          </span>
          {isInRoom && (
            <span className="flex items-center justify-center min-w-4 h-4 px-1 rounded-full bg-[var(--m3-secondary-container)] text-[10px] font-bold text-[var(--m3-on-secondary-container)]">
              {members.length}
            </span>
          )}
        </button>

        {/* Theme Toggle Menu */}
        <div className="relative flex-shrink-0" ref={themeMenuRef}>
          <button
            onClick={() => setIsThemeMenuOpen(!isThemeMenuOpen)}
            className="p-2 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
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
                    {isSelected && <Check className="w-3.5 h-3.5 text-[var(--m3-primary)]" />}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Google Sign In Button (when signed out). Hidden below `lg` for the
            same width reason as the label above — the profile circle's dropdown
            carries the identical "Sign In with Google" action there. */}
        {!user && (
          <button
            onClick={handleLogin}
            disabled={isLoggingIn || !isAuthAvailable}
            className="hidden lg:flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] text-xs font-semibold text-[var(--text-primary)] transition cursor-pointer shadow-sm active:scale-95 flex-shrink-0"
            title="Sign In with Google"
          >
            {isLoggingIn ? (
              <Loader2 className="w-3.5 h-3.5 animate-spin text-[var(--m3-primary)]" />
            ) : (
              <svg className="w-3.5 h-3.5 flex-shrink-0" viewBox="0 0 24 24">
                <path fill="#4285F4" d="M23.745 12.27c0-.7-.06-1.4-.19-2.07H12v4.51h6.6c-.29 1.52-1.14 2.82-2.4 3.68v3.05h3.88c2.27-2.09 3.66-5.17 3.66-9.17z" />
                <path fill="#34A853" d="M12 24c3.24 0 5.95-1.08 7.93-2.91l-3.88-3.05c-1.08.72-2.45 1.16-4.05 1.16-3.12 0-5.77-2.1-6.72-4.93H1.25v3.15C3.26 21.36 7.36 24 12 24z" />
                <path fill="#FBBC05" d="M5.28 14.27c-.25-.72-.38-1.49-.38-2.27s.13-1.55.38-2.27V6.58H1.25C.45 8.18 0 10.02 0 12s.45 3.82 1.25 5.42l4.03-3.15z" />
                <path fill="#EA4335" d="M12 4.75c1.77 0 3.35.61 4.6 1.8l3.42-3.42C17.95 1.19 15.24 0 12 0 7.36 0 3.26 2.64 1.25 6.58l4.03 3.15c.95-2.83 3.6-4.98 6.72-4.98z" />
              </svg>
            )}
            <span>Sign In</span>
          </button>
        )}

        {/* User / Google Profile Circle Button at FAR TOP-RIGHT */}
        <div className="relative flex-shrink-0" ref={userMenuRef}>
          <button
            onClick={() => setIsUserMenuOpen(!isUserMenuOpen)}
            className="p-0.5 rounded-full hover:ring-2 hover:ring-[var(--m3-primary-40)] transition flex items-center justify-center cursor-pointer focus:outline-none flex-shrink-0"
            title={user ? (user.displayName || user.email || 'Account') : 'Account & Sign In'}
            aria-label="User Account"
          >
            {user ? (
              user.photoURL ? (
                <img
                  src={user.photoURL}
                  alt={user.displayName || 'User'}
                  className="w-8 h-8 rounded-full object-cover ring-1 ring-emerald-500/50"
                />
              ) : (
                <div className="w-8 h-8 rounded-full bg-[var(--m3-primary)] flex items-center justify-center text-xs font-bold text-[var(--m3-on-primary)] shadow-sm">
                  {(user.displayName || user.email || 'U').charAt(0).toUpperCase()}
                </div>
              )
            ) : (
              <div className="w-8 h-8 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] flex items-center justify-center text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition">
                <User className="w-4 h-4" />
              </div>
            )}
          </button>

          {/* User Dropdown Menu (Account / Sign-in) */}
          {isUserMenuOpen && (
            <div className="absolute right-0 top-full mt-2 w-64 p-2 bg-[var(--bg-popover)] border border-[var(--border-medium)] rounded-2xl shadow-2xl z-50 animate-in fade-in slide-in-from-top-2 duration-150">
              {user ? (
                <>
                  <div className="px-3 py-2.5 border-b border-[var(--border-subtle)] mb-1">
                    <p className="text-xs font-bold text-[var(--text-primary)] truncate">
                      {user.displayName || 'Auralis User'}
                    </p>
                    <p className="text-[11px] text-[var(--text-muted)] truncate">{user.email}</p>
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
                        Auralis favorites &amp; playlists synced via cloud.
                      </p>
                    </div>
                  </div>

                  <button
                    onClick={() => {
                      setIsUserMenuOpen(false);
                      setIsYouTubeSyncOpen(true);
                    }}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-xs font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)] rounded-xl transition cursor-pointer"
                  >
                    <MonitorPlay className="w-3.5 h-3.5 text-red-500" />
                    <span>YouTube Sync</span>
                  </button>

                  <button
                    onClick={handleLogout}
                    className="w-full flex items-center gap-2.5 px-3 py-2 text-xs font-medium text-red-500 hover:text-red-400 hover:bg-red-500/10 rounded-xl transition cursor-pointer"
                  >
                    <LogOut className="w-3.5 h-3.5" />
                    <span>Sign Out</span>
                  </button>
                </>
              ) : (
                <div className="p-3 text-center space-y-3">
                  <div className="w-10 h-10 rounded-full bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center mx-auto text-[var(--text-secondary)]">
                    <User className="w-5 h-5" />
                  </div>
                  <div>
                    <h4 className="text-xs font-bold text-[var(--text-primary)]">Sync your Music</h4>
                    <p className="text-[11px] text-[var(--text-muted)] mt-1">
                      Sign in with Google to sync your playlists and liked songs across devices.
                    </p>
                  </div>
                  <button
                    onClick={handleLogin}
                    disabled={isLoggingIn || !isAuthAvailable}
                    className={`w-full flex items-center justify-center gap-2 py-2 px-3 rounded-xl font-bold text-xs transition shadow-sm cursor-pointer ${
                      isAuthAvailable
                        ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] hover:opacity-90 active:scale-95'
                        : 'bg-[var(--bg-surface-elevated)] text-[var(--text-muted)] border border-[var(--border-subtle)] cursor-not-allowed'
                    }`}
                  >
                    {isLoggingIn ? (
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    ) : (
                      <svg className="w-4 h-4" viewBox="0 0 24 24">
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
                    <span>{isAuthAvailable ? 'Sign In with Google' : 'Sign-in unavailable'}</span>
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </header>

    <YouTubeSyncModal
      isOpen={isYouTubeSyncOpen}
      onClose={() => setIsYouTubeSyncOpen(false)}
    />
    </>
  );
};

