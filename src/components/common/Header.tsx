import React, { useState, useEffect, useRef } from 'react';
import {
  Search,
  X,
  Music2,
  Command,
  ChevronLeft,
  ChevronRight,
  Loader2,
  AlertTriangle,
  Radio,
  User,
} from 'lucide-react';
import { searchYouTube, SearchUnavailableError } from '../../services/youtube';
import { getSearchSuggestions } from '../../services/searchSuggestions';
import type { Track } from '../../types/music';
import { usePlayer } from '../../context/PlayerContext';
import { useAuth } from '../../context/AuthContext';
import { useListenTogether } from '../../context/ListenTogetherContext';
import { isLetterboxedThumbnail } from '../../services/artwork';

interface HeaderProps {
  onSearchSelect?: (track: Track) => void;
  activeView: string;
  setActiveView: (view: string) => void;
  /**
   * Called when the user submits the search box (Enter). Receives the typed
   * query so it actually reaches the Explore view instead of being dropped.
   */
  onSubmitSearch?: (query: string) => void;
  /** Opens the Account / Profile modal (from the top-right profile icon). */
  onOpenAccount: () => void;
}

/** Human-readable title shown on the mobile header for each docked tab. */
const VIEW_TITLES: Record<string, string> = {
  home: 'Home',
  explore: 'Search',
  search: 'Search',
  library: 'Your Library',
  favorites: 'Liked Songs',
};

// Simple view history stack for real back/forward navigation
const viewHistory: string[] = ['home'];
let viewHistoryIndex = 0;

export const Header: React.FC<HeaderProps> = ({
  onSearchSelect,
  activeView,
  setActiveView,
  onSubmitSearch,
  onOpenAccount,
}) => {
  const [query, setQuery] = useState('');
  const [isSearching, setIsSearching] = useState(false);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [results, setResults] = useState<Track[]>([]);
  const [isOpenDropdown, setIsOpenDropdown] = useState(false);
  /** Non-null when the typeahead search could not reach any provider. */
  const [searchError, setSearchError] = useState<string | null>(null);

  const { playTrack } = usePlayer();
  const { user } = useAuth();
  const { isInRoom, isHost, roomCode, members, setIsModalOpen } = useListenTogether();

  const searchContainerRef = useRef<HTMLDivElement>(null);
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

  const [selectedIndex, setSelectedIndex] = useState<number>(-1);

  // Debounced search suggestions and typeahead tracks (120ms fast debounce)
  useEffect(() => {
    if (!query.trim()) {
      setSuggestions([]);
      setResults([]);
      setSearchError(null);
      setIsOpenDropdown(false);
      setSelectedIndex(-1);
      return;
    }

    let cancelled = false;
    const abortController = new AbortController();
    const capturedQuery = query;

    const timer = setTimeout(async () => {
      setIsSearching(true);
      setSearchError(null);
      try {
        // Fetch suggestions and pre-fetch track preview concurrently
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
    }, 120);

    return () => {
      cancelled = true;
      abortController.abort();
      clearTimeout(timer);
    };
  }, [query]);

  // Click outside search closes the dropdown
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        searchContainerRef.current &&
        !searchContainerRef.current.contains(event.target as Node)
      ) {
        setIsOpenDropdown(false);
        setSelectedIndex(-1);
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
    setSelectedIndex(-1);
    if (onSubmitSearch) {
      onSubmitSearch(suggestionText);
    } else {
      setActiveView('explore');
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    const displayedSuggestions = suggestions.slice(0, 3);
    const displayedSongs = results.slice(0, 5);
    const totalItems = displayedSuggestions.length + displayedSongs.length;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!isOpenDropdown) setIsOpenDropdown(true);
      setSelectedIndex((prev) => (prev + 1 < totalItems ? prev + 1 : 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setSelectedIndex((prev) => (prev - 1 >= 0 ? prev - 1 : totalItems - 1));
    } else if (e.key === 'Escape') {
      setIsOpenDropdown(false);
      setSelectedIndex(-1);
    } else if (e.key === 'Enter') {
      if (selectedIndex >= 0 && selectedIndex < displayedSuggestions.length) {
        handleSelectSuggestion(displayedSuggestions[selectedIndex]);
        return;
      } else if (selectedIndex >= displayedSuggestions.length && selectedIndex < totalItems) {
        const trackIdx = selectedIndex - displayedSuggestions.length;
        if (displayedSongs[trackIdx]) {
          handleSelectTrack(displayedSongs[trackIdx]);
          return;
        }
      }

      const submitted = query.trim();
      if (!submitted) return;
      if (onSubmitSearch) {
        onSubmitSearch(submitted);
      } else {
        setActiveView('explore');
      }
      setIsOpenDropdown(false);
      setSelectedIndex(-1);
    }
  };

  return (
    <header className="sticky top-0 z-30 flex items-center justify-between px-[max(0.75rem,env(safe-area-inset-left,0px))] pr-[max(0.75rem,env(safe-area-inset-right,0px))] sm:px-6 md:px-8 pt-[max(0.75rem,env(safe-area-inset-top,0px))] pb-3 backdrop-blur-2xl bg-[var(--bg-header)] border-b border-[var(--border-subtle)] text-[var(--text-primary)] transition-colors duration-200 gap-2 sm:gap-3 md:gap-4">
      {/* Mobile: the docked-tab view title. The search box is intentionally
          absent here — the bottom dock has a dedicated Search tab, so a second
          search field in the header would be redundant. */}
      <div className="flex md:hidden items-center min-w-0 flex-1">
        <h1 className="text-lg font-black font-display truncate text-[var(--text-primary)]">
          {VIEW_TITLES[activeView] ?? 'Auralis'}
        </h1>
      </div>

      {/* Desktop: navigation history arrows + flexible search. Kept on desktop
          because the desktop sidebar has no search field of its own. */}
      <div className="hidden md:flex items-center gap-2 sm:gap-3 flex-1 min-w-0" ref={searchContainerRef}>
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

              {/* Music Search Suggestions (Top 3 Recommendations) */}
              {suggestions.length > 0 && (
                <div className="py-1">
                  {suggestions.slice(0, 3).map((s, idx) => (
                    <button
                      key={idx}
                      onClick={() => handleSelectSuggestion(s)}
                      className={`w-full flex items-center gap-3 px-3.5 py-2 text-left cursor-pointer transition group ${
                        selectedIndex === idx
                          ? 'bg-[var(--bg-surface-hover)]'
                          : 'hover:bg-[var(--bg-surface-hover)]'
                      }`}
                    >
                      <Search className={`w-3.5 h-3.5 flex-shrink-0 transition ${
                        selectedIndex === idx
                          ? 'text-[var(--m3-primary)]'
                          : 'text-[var(--text-muted)] group-hover:text-[var(--m3-primary)]'
                      }`} />
                      <span className={`text-xs font-medium truncate transition ${
                        selectedIndex === idx
                          ? 'text-[var(--m3-primary)] font-semibold'
                          : 'text-[var(--text-primary)] group-hover:text-[var(--m3-primary)]'
                      }`}>
                        {s}
                      </span>
                    </button>
                  ))}
                </div>
              )}

              {/* Top Track Matches (Directly after suggestions) */}
              {results.length > 0 && (
                <div className={`${suggestions.length > 0 ? 'border-t border-[var(--border-subtle)] mt-1 pt-1.5' : ''} p-1 space-y-0.5`}>
                  <div className="px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] flex items-center justify-between">
                    <span>Songs</span>
                    <span className="font-mono text-[9px]">{results.length} found</span>
                  </div>
                  {results.slice(0, 5).map((track, idx) => {
                    const displayedSuggestionsCount = Math.min(suggestions.length, 3);
                    const isTrackSelected = selectedIndex === displayedSuggestionsCount + idx;
                    return (
                      <button
                        key={track.id}
                        onClick={() => handleSelectTrack(track)}
                        className={`w-full flex items-center gap-2.5 px-2.5 py-1.5 rounded-xl text-left cursor-pointer transition group ${
                          isTrackSelected
                            ? 'bg-[var(--bg-surface-hover)]'
                            : 'hover:bg-[var(--bg-surface-hover)]'
                        }`}
                      >
                        <div className="w-8 h-8 rounded-lg overflow-hidden bg-neutral-800 flex-shrink-0 shadow-sm relative">
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
                          />
                        </div>
                        <div className="flex-1 min-w-0">
                          <p className={`text-xs font-semibold truncate transition ${
                            isTrackSelected
                              ? 'text-[var(--m3-primary)]'
                              : 'text-[var(--text-primary)] group-hover:text-[var(--m3-primary)]'
                          }`}>
                            {track.title}
                          </p>
                          <p className="text-[10px] text-[var(--text-muted)] truncate">{track.artist}</p>
                        </div>
                        <Music2 className="w-3.5 h-3.5 text-[var(--text-muted)] opacity-0 group-hover:opacity-100 transition flex-shrink-0" />
                      </button>
                    );
                  })}
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

        {/* Profile / Account — opens the full Account modal (item 5). The theme
            selector, cloud-sync toggle, YouTube sync, importer, sleep timer and
            legal links all live inside that modal now, so the header stays to a
            single tap target here. */}
        <button
          onClick={onOpenAccount}
          className="p-0.5 rounded-full hover:ring-2 hover:ring-[var(--m3-primary-40)] transition flex items-center justify-center cursor-pointer focus:outline-none flex-shrink-0"
          title={user ? (user.displayName || user.email || 'Account') : 'Account & Sign In'}
          aria-label="Account"
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
      </div>
    </header>
  );
};

