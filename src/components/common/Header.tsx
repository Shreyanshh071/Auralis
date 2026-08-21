import React, { useState, useEffect, useRef } from 'react';
import { Search, X, Music2, Command, ChevronLeft, ChevronRight } from 'lucide-react';
import { searchYouTube } from '../../services/youtube';
import type { Track } from '../../types/music';
import { usePlayer } from '../../context/PlayerContext';

interface HeaderProps {
  onSearchSelect?: (track: Track) => void;
  activeView: string;
  setActiveView: (view: string) => void;
}

// Simple view history stack for real back/forward navigation
const viewHistory: string[] = ['home'];
let viewHistoryIndex = 0;

export const Header: React.FC<HeaderProps> = ({ onSearchSelect, activeView, setActiveView }) => {
  const [query, setQuery] = useState('');
  const [isSearching, setIsSearching] = useState(false);
  const [results, setResults] = useState<Track[]>([]);
  const [isOpenDropdown, setIsOpenDropdown] = useState(false);
  const { playTrack } = usePlayer();
  const searchContainerRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // Track view changes in history
  useEffect(() => {
    if (viewHistory[viewHistoryIndex] !== activeView) {
      // Trim forward history and push new view
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

  // Debounced search
  useEffect(() => {
    if (!query.trim()) {
      setResults([]);
      setIsOpenDropdown(false);
      return;
    }

    const timer = setTimeout(async () => {
      setIsSearching(true);
      try {
        const res = await searchYouTube(query);
        setResults(res);
        setIsOpenDropdown(true);
      } catch (e) {
        console.error(e);
      } finally {
        setIsSearching(false);
      }
    }, 180);

    return () => clearTimeout(timer);
  }, [query]);

  // Click outside
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        searchContainerRef.current &&
        !searchContainerRef.current.contains(event.target as Node)
      ) {
        setIsOpenDropdown(false);
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
    if (e.key === 'Enter' && query.trim()) {
      setActiveView('explore');
      setIsOpenDropdown(false);
    }
  };

  return (
    <header className="sticky top-0 z-30 flex items-center justify-between px-8 py-3.5 backdrop-blur-2xl bg-[#09090b]/80 border-b border-white/[0.04]">
      {/* Navigation history arrows + Search */}
      <div className="flex items-center gap-4 flex-1 max-w-xl" ref={searchContainerRef}>
        <div className="hidden sm:flex items-center gap-1">
          <button
            onClick={goBack}
            disabled={!canGoBack}
            className={`p-1.5 rounded-full transition ${
              canGoBack
                ? 'hover:bg-white/[0.06] text-neutral-400 hover:text-white'
                : 'text-neutral-600 cursor-not-allowed'
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
                ? 'hover:bg-white/[0.06] text-neutral-400 hover:text-white'
                : 'text-neutral-600 cursor-not-allowed'
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
              <Search className="absolute left-3.5 w-4 h-4 text-neutral-400 pointer-events-none" />
            )}
            <input
              ref={inputRef}
              type="text"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
              onFocus={() => query.trim() && setIsOpenDropdown(true)}
              placeholder="What do you want to play?"
              className="w-full pl-10 pr-16 py-2 bg-neutral-900/70 hover:bg-neutral-900 text-xs sm:text-sm text-neutral-100 placeholder-neutral-500 rounded-full border border-white/[0.08] focus:border-neutral-500 focus:outline-none focus:ring-2 focus:ring-white/[0.08] transition"
            />
            {query ? (
              <button
                onClick={() => {
                  setQuery('');
                  setResults([]);
                }}
                className="absolute right-3 p-1 rounded-full hover:bg-neutral-800 text-neutral-400 hover:text-white transition"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            ) : (
              <div className="absolute right-3 hidden sm:flex items-center gap-0.5 px-1.5 py-0.5 rounded bg-white/[0.04] border border-white/[0.06] text-[10px] font-mono text-neutral-500">
                <Command className="w-2.5 h-2.5" />
                <span>K</span>
              </div>
            )}
          </div>

          {/* Instant Search Dropdown */}
          {isOpenDropdown && (
            <div className="absolute top-full left-0 right-0 mt-1.5 py-1.5 bg-[#121215] border border-white/[0.08] rounded-2xl shadow-2xl shadow-black/90 z-50 max-h-96 overflow-y-auto">
              <div className="px-3.5 py-1.5 text-[11px] font-semibold tracking-wide text-neutral-400 border-b border-white/[0.04] flex items-center justify-between">
                <span>{isSearching ? 'Searching YouTube...' : 'Top Matches'}</span>
                {results.length > 0 && <span className="text-[10px] text-neutral-500 font-mono">{results.length} found</span>}
              </div>

              {results.length === 0 && !isSearching && (
                <div className="px-4 py-8 text-center text-xs text-neutral-500 font-medium">
                  No matching tracks found for "{query}"
                </div>
              )}

              <div className="p-1 space-y-0.5">
                {results.map((track) => (
                  <div
                    key={track.id}
                    onClick={() => handleSelectTrack(track)}
                    className="flex items-center gap-3 px-3 py-2 rounded-xl hover:bg-white/[0.06] cursor-pointer transition group"
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
                      <p className="text-xs sm:text-sm font-semibold text-neutral-100 truncate group-hover:text-white transition">
                        {track.title}
                      </p>
                      <p className="text-[11px] text-neutral-400 truncate">{track.artist}</p>
                    </div>
                    <Music2 className="w-3.5 h-3.5 text-neutral-500 opacity-0 group-hover:opacity-100 transition" />
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Right controls */}
      <div className="flex items-center gap-2">
        <button
          onClick={() => setActiveView('explore')}
          className="hidden sm:flex items-center gap-2 px-3.5 py-1.5 rounded-full text-xs font-semibold text-neutral-300 hover:text-white hover:bg-white/[0.06] transition"
        >
          <span>Explore</span>
        </button>

        <button
          onClick={() => setActiveView('library')}
          className="hidden sm:flex items-center gap-2 px-3.5 py-1.5 rounded-full text-xs font-semibold text-neutral-300 hover:text-white hover:bg-white/[0.06] transition"
        >
          <span>Library</span>
        </button>
      </div>
    </header>
  );
};
