import React, { useEffect, useLayoutEffect, useRef, useState, useCallback } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import {
  Clock,
  Copy,
  Check,
  RotateCcw,
  Music,
  Sliders,
  AlignLeft,
  AlignCenter,
  Zap,
  Sparkles,
  Info,
  Languages,
  Loader2,
  ChevronDown,
} from 'lucide-react';
import {
  SUPPORTED_LANGUAGES,
  translateLyricLines,
  translatePlainLyrics,
} from '../../services/lyricsTranslation';
import type { LyricLine } from '../../types/music';
import { globalPlaybackClock } from '../../lib/playbackClock';

interface SyncedLyricsProps {
  fullscreen?: boolean;
}

export const SyncedLyrics: React.FC<SyncedLyricsProps> = ({ fullscreen = false }) => {
  const {
    currentTrack,
    lyrics,
    isLoadingLyrics,
    activeLyricIndex,
    isPlaying,
    currentTime,
    seekTo,
    lyricsOffset,
    setManualLyricsOffset,
    settings,
    updateSettings,
  } = usePlayer();

  const [copied, setCopied] = useState(false);
  const [autoScroll, setAutoScroll] = useState(true);
  const [showSettingsPanel, setShowSettingsPanel] = useState(false);
  const [showLangMenu, setShowLangMenu] = useState(false);
  const [isTranslationActive, setIsTranslationActive] = useState(() => {
    return localStorage.getItem('auralis_lyrics_translate_enabled') === 'true';
  });
  const [targetLang, setTargetLang] = useState(() => {
    return localStorage.getItem('auralis_lyrics_target_lang') || 'en';
  });
  const [isTranslating, setIsTranslating] = useState(false);
  const [translatedLines, setTranslatedLines] = useState<LyricLine[] | null>(null);
  const [translatedPlain, setTranslatedPlain] = useState<string | null>(null);
  const [translationError, setTranslationError] = useState<string | null>(null);

  const activeLineRef = useRef<HTMLDivElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);

  // Refs for rAF-driven word highlighting (no React re-renders).
  // Entries are nulled/dropped when a span unmounts, so the per-frame loop only
  // ever walks spans that are still in the document.
  const wordSpansRef = useRef<Map<string, (HTMLSpanElement | null)[]>>(new Map());
  const cinemaWordSpansRef = useRef<(HTMLSpanElement | null)[]>([]);
  const rafIdRef = useRef<number>(0);
  const lyricsOffsetRef = useRef(lyricsOffset);
  lyricsOffsetRef.current = lyricsOffset;
  // Read inside observers/callbacks that must not re-subscribe on every toggle.
  const autoScrollRef = useRef(autoScroll);
  autoScrollRef.current = autoScroll;

  // Translate lyrics whenever lyrics, currentTrack, targetLang, or translation active changes
  useEffect(() => {
    if (!lyrics || !isTranslationActive) {
      setTranslatedLines(null);
      setTranslatedPlain(null);
      setTranslationError(null);
      return;
    }

    let isCancelled = false;
    setIsTranslating(true);
    setTranslationError(null);

    async function runTranslation() {
      try {
        if (lyrics?.syncType === 'plain' && lyrics.plainLyrics) {
          const res = await translatePlainLyrics(lyrics.plainLyrics, targetLang);
          if (!isCancelled) {
            setTranslatedPlain(res);
            setTranslatedLines(null);
          }
        } else if (lyrics && lyrics.lines.length > 0) {
          const res = await translateLyricLines(lyrics.lines, targetLang);
          if (!isCancelled) {
            setTranslatedLines(res);
            setTranslatedPlain(null);
          }
        }
      } catch (err) {
        if (!isCancelled) {
          setTranslationError('Translation currently unavailable.');
        }
      } finally {
        if (!isCancelled) {
          setIsTranslating(false);
        }
      }
    }

    runTranslation();

    return () => {
      isCancelled = true;
    };
  }, [lyrics, currentTrack?.id, isTranslationActive, targetLang]);

  const handleToggleTranslation = () => {
    const next = !isTranslationActive;
    setIsTranslationActive(next);
    localStorage.setItem('auralis_lyrics_translate_enabled', String(next));
  };

  const handleSelectLanguage = (langCode: string) => {
    setTargetLang(langCode);
    localStorage.setItem('auralis_lyrics_target_lang', langCode);
    setIsTranslationActive(true);
    localStorage.setItem('auralis_lyrics_translate_enabled', 'true');
    setShowLangMenu(false);
  };

  // User vs programmatic scroll detection
  const isProgrammaticScrollRef = useRef(false);
  const programmaticScrollTimerRef = useRef<any>(null);
  const isUserInteractingRef = useRef(false);
  const userGestureTimeoutRef = useRef<any>(null);
  const isInitialScrollRef = useRef(true);
  const expectedScrollTopRef = useRef<number | null>(null);

  const endProgrammaticScroll = useCallback(() => {
    isProgrammaticScrollRef.current = false;
    expectedScrollTopRef.current = null;
    if (programmaticScrollTimerRef.current) {
      clearTimeout(programmaticScrollTimerRef.current);
      programmaticScrollTimerRef.current = null;
    }
  }, []);

  // Center active lyric line mathematically within the container viewport
  const scrollToActiveLine = useCallback((smooth = true) => {
    const container = containerRef.current;
    const activeEl = activeLineRef.current;
    if (!container || !activeEl) return;

    // Use getBoundingClientRect delta to be completely immune to offsetParent changes or transforms
    const containerRect = container.getBoundingClientRect();
    const activeRect = activeEl.getBoundingClientRect();
    const lineTopInContainer = activeRect.top - containerRect.top + container.scrollTop;
    const lineHeight = activeRect.height || activeEl.offsetHeight;
    const containerHeight = container.clientHeight;

    // Center active line at 50% of container viewport height
    const targetScrollTop = lineTopInContainer - (containerHeight / 2) + (lineHeight / 2);
    const maxScrollTop = container.scrollHeight - containerHeight;
    const clampedScrollTop = Math.max(0, Math.min(targetScrollTop, maxScrollTop));

    // Already centered within 1px
    if (Math.abs(container.scrollTop - clampedScrollTop) < 1) {
      endProgrammaticScroll();
      return;
    }

    isProgrammaticScrollRef.current = true;
    expectedScrollTopRef.current = clampedScrollTop;
    if (programmaticScrollTimerRef.current) {
      clearTimeout(programmaticScrollTimerRef.current);
    }
    // Safety net fallback
    programmaticScrollTimerRef.current = setTimeout(endProgrammaticScroll, smooth ? 1200 : 100);

    container.scrollTo({
      top: clampedScrollTop,
      behavior: smooth ? 'smooth' : 'auto',
    });
  }, [endProgrammaticScroll]);

  // Track changes / initial load reset
  useEffect(() => {
    isInitialScrollRef.current = true;
    isUserInteractingRef.current = false;
    endProgrammaticScroll();
    setAutoScroll(true);
  }, [currentTrack?.id, endProgrammaticScroll]);

  // Auto-scroll to active lyric line on line changes or resume.
  useLayoutEffect(() => {
    if (!autoScroll || activeLyricIndex < 0 || !activeLineRef.current || !containerRef.current) return;

    const isFirst = isInitialScrollRef.current;
    scrollToActiveLine(!isFirst);
    if (isFirst) {
      isInitialScrollRef.current = false;
    }
  }, [activeLyricIndex, autoScroll, scrollToActiveLine]);

  // Anything that changes line metrics has to re-center
  useEffect(() => {
    if (!autoScrollRef.current) return;
    scrollToActiveLine(false);
  }, [
    scrollToActiveLine,
    settings.lyricsFontSize,
    settings.lyricsAlignment,
    settings.lyricsDepthBlur,
    settings.lyricsMode,
    isTranslationActive,
    translatedLines,
  ]);

  // Container resize (rotation, window resize, panel/tab layout changes)
  useEffect(() => {
    const container = containerRef.current;
    if (!container || typeof ResizeObserver === 'undefined') return;

    let frame = 0;
    const observer = new ResizeObserver(() => {
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        frame = 0;
        if (autoScrollRef.current) scrollToActiveLine(false);
      });
    });
    observer.observe(container);

    return () => {
      if (frame) cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [lyrics, scrollToActiveLine]);

  // Handle user manual scroll gesture — pause auto-scroll without fighting
  const handleUserGesture = useCallback(() => {
    isUserInteractingRef.current = true;
    if (userGestureTimeoutRef.current) {
      clearTimeout(userGestureTimeoutRef.current);
    }
    userGestureTimeoutRef.current = setTimeout(() => {
      isUserInteractingRef.current = false;
    }, 1200);
    endProgrammaticScroll();
    setAutoScroll(false);
  }, [endProgrammaticScroll]);

  const handleScroll = useCallback(() => {
    if (isUserInteractingRef.current) {
      setAutoScroll(false);
      return;
    }
    if (!isProgrammaticScrollRef.current) {
      return;
    }
    const container = containerRef.current;
    const expected = expectedScrollTopRef.current;
    if (!container || expected === null) return;
    if (Math.abs(container.scrollTop - expected) <= 3) {
      endProgrammaticScroll();
    }
  }, [endProgrammaticScroll]);

  // Resume auto-scroll button handler: immediately re-enable and center active line
  const handleResumeAutoScroll = () => {
    isUserInteractingRef.current = false;
    if (userGestureTimeoutRef.current) clearTimeout(userGestureTimeoutRef.current);
    setAutoScroll(true);
    requestAnimationFrame(() => {
      scrollToActiveLine(true);
    });
  };

  const handleCopyLyrics = () => {
    if (!lyrics) return;
    let fullText = '';
    const displayLines = translatedLines || lyrics.lines;
    if (lyrics.syncType !== 'plain' && displayLines.length > 0) {
      fullText = displayLines
        .map((l) => (l.translatedText ? `${l.text}\n(${l.translatedText})` : l.text))
        .join('\n');
    } else if (translatedPlain) {
      fullText = `${lyrics.plainLyrics}\n\n--- Translation (${targetLang.toUpperCase()}) ---\n\n${translatedPlain}`;
    } else if (lyrics.plainLyrics) {
      fullText = lyrics.plainLyrics;
    }

    if (fullText) {
      navigator.clipboard.writeText(`${currentTrack?.title} - ${currentTrack?.artist}\n\n${fullText}`);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  // Typography sizes
  const fontSizes = {
    small: 'text-base sm:text-xl md:text-2xl font-bold leading-relaxed',
    medium: 'text-lg sm:text-2xl md:text-3xl font-extrabold leading-relaxed',
    large: 'text-xl sm:text-3xl md:text-4xl lg:text-5xl font-black leading-tight',
  };

  const activeFontSizes = {
    small: 'text-xl sm:text-3xl md:text-4xl font-extrabold tracking-tight leading-snug',
    medium: 'text-2xl sm:text-4xl md:text-5xl lg:text-6xl font-black tracking-tight leading-none',
    large: 'text-3xl sm:text-5xl md:text-6xl lg:text-7xl font-black tracking-tight leading-none',
  };

  // --- Derived state ---
  const isLineSynced = lyrics?.syncType === 'line-sync';
  const isRichSynced = lyrics?.syncType === 'richsync';
  const isTimeSynced = isLineSynced || isRichSynced;
  const isPlain = lyrics?.syncType === 'plain';

  // Sync type badge label
  const syncBadgeLabel = isRichSynced ? 'Word Synced' : isLineSynced ? 'Line Synced' : 'Lyrics';

  // --- Richsync word highlighting -------------------------------------------
  // Direct DOM class toggling on the registered word <span>s. Deliberately kept
  // out of React state so nothing re-renders per frame. Declared above the
  // loading/empty early returns below so the hook order stays stable when
  // lyrics arrive or the track changes.
  const applyWordHighlight = useCallback((adjustedTime: number) => {
    // Scroll-mode word spans (only the active line renders any)
    wordSpansRef.current.forEach((spans) => {
      for (const span of spans) {
        if (!span) continue;
        const wordTime = Number(span.dataset.wordTime);
        if (Number.isFinite(wordTime)) {
          const active = adjustedTime >= wordTime;
          if (active) {
            span.classList.add('lyrics-word-active');
            span.classList.remove('lyrics-word-inactive');
          } else {
            span.classList.remove('lyrics-word-active');
            span.classList.add('lyrics-word-inactive');
          }
        }
      }
    });

    // Cinema-mode word spans
    for (const span of cinemaWordSpansRef.current) {
      if (!span) continue;
      const wordTime = Number(span.dataset.wordTime);
      if (Number.isFinite(wordTime)) {
        const active = adjustedTime >= wordTime;
        if (active) {
          span.classList.add('lyrics-word-active');
          span.classList.remove('lyrics-word-inactive');
        } else {
          span.classList.remove('lyrics-word-active');
          span.classList.add('lyrics-word-inactive');
        }
      }
    }
  }, []);

  // One-shot resync. The rAF loop below only runs while playing, so without
  // this a seek (or a line change) while paused would leave the previous line's
  // words highlighted. Layout effect so freshly mounted spans get their state
  // before the first paint instead of flashing in fully dimmed.
  useLayoutEffect(() => {
    if (!isRichSynced) return;
    applyWordHighlight(globalPlaybackClock.getCurrentInterpolatedTime() + lyricsOffsetRef.current);
  }, [
    isRichSynced,
    applyWordHighlight,
    activeLyricIndex,
    currentTime,
    isPlaying,
    lyricsOffset,
    settings.lyricsMode,
    isTranslationActive,
    translatedLines,
  ]);

  // The per-frame loop: reads the interpolating clock and toggles the two CSS
  // classes. Only alive while richsync words exist and playback is running.
  useEffect(() => {
    if (!isRichSynced || !isPlaying) {
      if (rafIdRef.current) cancelAnimationFrame(rafIdRef.current);
      rafIdRef.current = 0;
      return;
    }

    const tick = () => {
      // When tab is hidden/backgrounded, pause DOM updates to save CPU/GPU resources
      if (document.hidden) {
        rafIdRef.current = requestAnimationFrame(tick);
        return;
      }

      applyWordHighlight(globalPlaybackClock.getCurrentInterpolatedTime() + lyricsOffsetRef.current);

      rafIdRef.current = requestAnimationFrame(tick);
    };

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        applyWordHighlight(globalPlaybackClock.getCurrentInterpolatedTime() + lyricsOffsetRef.current);
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    rafIdRef.current = requestAnimationFrame(tick);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      if (rafIdRef.current) cancelAnimationFrame(rafIdRef.current);
      rafIdRef.current = 0;
    };
  }, [isRichSynced, isPlaying, applyWordHighlight]);

  // Helpers to register word span refs for the loop above. React calls these
  // with null on unmount — dropping the entry then is what keeps the map from
  // growing for the whole session and the loop from walking detached spans.
  const registerWordRef = useCallback((lineKey: string, index: number, el: HTMLSpanElement | null) => {
    const map = wordSpansRef.current;
    if (!el) {
      const existing = map.get(lineKey);
      if (existing) {
        existing[index] = null;
        if (existing.every((span) => span === null)) map.delete(lineKey);
      }
      return;
    }
    let spans = map.get(lineKey);
    if (!spans) {
      spans = [];
      map.set(lineKey, spans);
    }
    spans[index] = el;
  }, []);

  const registerCinemaWordRef = useCallback((index: number, el: HTMLSpanElement | null) => {
    cinemaWordSpansRef.current[index] = el;
  }, []);

  // --- Loading state ---
  if (isLoadingLyrics) {
    return (
      <div className="relative flex flex-col items-center justify-center h-full min-h-[350px] gap-3 text-[var(--m3-primary)] select-none">
        <div className="relative flex items-center justify-center">
          <div className="w-12 h-12 rounded-full border-2 border-current/20 border-t-current animate-spin" />
          <Sparkles className="w-5 h-5 text-current absolute animate-pulse" />
        </div>
        <p className="text-xs font-semibold tracking-wider uppercase text-[var(--text-muted)]">
          Fetching lyrics...
        </p>
      </div>
    );
  }

  // --- No lyrics state ---
  if (!lyrics || (isPlain && !lyrics.plainLyrics) || (isTimeSynced && lyrics.lines.length === 0)) {
    return (
      <div className="relative flex flex-col items-center justify-center h-full min-h-[350px] px-6 text-center select-none space-y-3">
        <div className="w-14 h-14 rounded-3xl bg-[var(--bg-surface-elevated)] flex items-center justify-center text-[var(--text-muted)] border border-[var(--border-subtle)] shadow-lg">
          <Music className="w-7 h-7" />
        </div>
        <div>
          <h3 className="text-base font-bold text-[var(--text-primary)]">No Lyrics Found</h3>
          <p className="text-xs text-[var(--text-muted)] max-w-sm mt-1">
            Lyrics are not available for this track.
          </p>
        </div>
        <button
          onClick={() => {
            if (currentTrack) {
              window.open(
                `https://www.google.com/search?q=${encodeURIComponent(`${currentTrack.title} ${currentTrack.artist} lyrics`)}`,
                '_blank'
              );
            }
          }}
          className="px-5 py-2 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-xs font-bold text-[var(--text-primary)] border border-[var(--border-subtle)] transition shadow-md cursor-pointer"
        >
          Search Web for Lyrics
        </button>
      </div>
    );
  }

  // Effective display lines (with translation if available)
  const displayLines = translatedLines || lyrics.lines;
  // --- Active line for cinema mode ---
  const activeLine = isTimeSynced && activeLyricIndex >= 0 ? displayLines[activeLyricIndex] : null;
  const nextLine = isTimeSynced && activeLyricIndex >= 0 && activeLyricIndex < displayLines.length - 1
    ? displayLines[activeLyricIndex + 1]
    : null;

  // --- Richsync: rAF loop drives per-word highlighting via direct DOM updates ---
  // (declared above the early returns — see applyWordHighlight)

  return (
    <div className="relative flex flex-col h-full overflow-hidden select-text">
      {/* Control Header Bar */}
      <div className="relative flex items-center justify-between gap-1.5 sm:gap-3 px-3 sm:px-6 py-2 border-b border-white/10 bg-transparent z-20 text-white">
        <div className="flex items-center gap-1.5 sm:gap-2.5 min-w-0 flex-1 flex-wrap">
          {/* Sync type badge */}
          <div className="flex items-center gap-1.5 px-2.5 sm:px-3 py-1 rounded-full bg-white/10 border border-white/10 shadow-sm flex-shrink-0">
            {isTimeSynced && (
              <span className="w-2 h-2 rounded-full bg-white animate-pulse" />
            )}
            <span className="text-[11px] sm:text-xs font-bold text-white tracking-wide">
              {syncBadgeLabel}
            </span>
          </div>

          {/* Translation Toggle & Language Selector */}
          <div className="relative flex items-center flex-shrink-0">
            <button
              onClick={handleToggleTranslation}
              className={`flex items-center gap-1 px-2.5 py-1 rounded-l-full text-[11px] sm:text-xs font-bold transition border cursor-pointer ${
                isTranslationActive
                  ? 'bg-white text-black border-white shadow-md'
                  : 'bg-white/10 hover:bg-white/20 text-white/80 hover:text-white border-white/10'
              }`}
              title={isTranslationActive ? 'Disable translation' : 'Translate lyrics'}
            >
              {isTranslating ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <Languages className="w-3.5 h-3.5" />
              )}
              <span className="hidden sm:inline">Translate</span>
              {isTranslationActive && <span className="uppercase text-[10px] font-mono">({targetLang})</span>}
            </button>
            <button
              onClick={() => setShowLangMenu(!showLangMenu)}
              className={`px-1.5 py-1 rounded-r-full text-xs font-bold transition border-y border-r cursor-pointer ${
                isTranslationActive
                  ? 'bg-white text-black border-white'
                  : 'bg-white/10 hover:bg-white/20 text-white/80 border-white/10'
              }`}
              title="Select translation language"
            >
              <ChevronDown className="w-3 h-3" />
            </button>

            {/* Language Selection Dropdown */}
            {showLangMenu && (
              <div className="absolute top-10 left-0 z-50 w-48 py-2 rounded-2xl bg-black/90 backdrop-blur-2xl border border-white/15 shadow-2xl space-y-0.5 max-h-60 overflow-y-auto">
                <div className="px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-white/60 border-b border-white/10">
                  Translate To
                </div>
                {SUPPORTED_LANGUAGES.map((lang) => (
                  <button
                    key={lang.code}
                    onClick={() => handleSelectLanguage(lang.code)}
                    className={`w-full text-left px-3 py-1.5 text-xs flex items-center justify-between hover:bg-white/10 transition cursor-pointer ${
                      targetLang === lang.code ? 'text-white font-bold bg-white/15' : 'text-white/80'
                    }`}
                  >
                    <span>{lang.name}</span>
                    <span className="text-[10px] text-white/50 font-mono uppercase">{lang.code}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Sync Offset Controls — only for timed lyrics */}
          {isTimeSynced && (
            <div className="flex items-center gap-1 bg-white/10 px-2 py-0.5 sm:px-2.5 sm:py-1 rounded-full text-xs text-white/80 border border-white/10 shadow-sm flex-shrink-0">
              <Clock className="w-3 h-3 text-white/70" />
              <span className="text-[10px] sm:text-[11px] hidden xs:inline">Sync:</span>
              <button
                onClick={() => setManualLyricsOffset(Number((lyricsOffset - 0.5).toFixed(1)))}
                className="hover:text-white px-1 sm:px-1.5 font-bold hover:bg-white/10 rounded transition cursor-pointer"
                title="Delay lyrics (-0.5s)"
              >
                -
              </button>
              <span className="font-mono text-white font-semibold text-[10px] sm:text-[11px] min-w-[24px] sm:min-w-[28px] text-center">
                {lyricsOffset > 0 ? `+${lyricsOffset.toFixed(1)}s` : `${lyricsOffset.toFixed(1)}s`}
              </span>
              <button
                onClick={() => setManualLyricsOffset(Number((lyricsOffset + 0.5).toFixed(1)))}
                className="hover:text-white px-1 sm:px-1.5 font-bold hover:bg-white/10 rounded transition cursor-pointer"
                title="Advance lyrics (+0.5s)"
              >
                +
              </button>
              {lyricsOffset !== 0 && (
                <button
                  onClick={() => setManualLyricsOffset(0)}
                  className="hover:text-white p-0.5 text-white/50 ml-0.5 cursor-pointer hover:bg-white/10 rounded"
                  title="Reset offset"
                >
                  <RotateCcw className="w-2.5 h-2.5" />
                </button>
              )}
            </div>
          )}
        </div>

        {/* Settings + Copy */}
        <div className="flex items-center gap-1.5 sm:gap-2 flex-shrink-0">
          <button
            onClick={() => setShowSettingsPanel(!showSettingsPanel)}
            className={`flex items-center gap-1.5 p-1.5 sm:px-3 sm:py-1.5 rounded-full text-xs font-bold transition border cursor-pointer ${
              showSettingsPanel
                ? 'bg-white text-black border-white shadow-lg'
                : 'bg-white/10 hover:bg-white/20 text-white/80 hover:text-white border-white/10'
            }`}
            title="Lyrics Settings"
            aria-label="Lyrics Settings"
          >
            <Sliders className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Settings</span>
          </button>

          <button
            onClick={handleCopyLyrics}
            className="flex items-center gap-1.5 p-1.5 sm:px-3 sm:py-1.5 rounded-full bg-white/10 hover:bg-white/20 text-xs font-semibold text-white/80 hover:text-white border border-white/10 transition cursor-pointer flex-shrink-0"
            title="Copy all lyrics"
            aria-label="Copy all lyrics"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span className="hidden sm:inline">{copied ? 'Copied' : 'Copy'}</span>
          </button>
        </div>
      </div>

      {/* Settings Popover */}
      {showSettingsPanel && (
        <div className="absolute top-12 sm:top-14 right-2 sm:right-6 z-40 w-72 max-w-[calc(100vw-1rem)] p-4 rounded-3xl bg-[var(--bg-popover)] backdrop-blur-2xl border border-[var(--border-medium)] shadow-2xl space-y-4 text-[var(--text-primary)]">
          <div className="flex items-center justify-between pb-2 border-b border-[var(--border-subtle)]">
            <span className="text-xs font-black text-[var(--m3-primary)] tracking-wide">Lyrics Settings</span>
            <button
              onClick={() => setShowSettingsPanel(false)}
              className="text-xs text-[var(--text-muted)] hover:text-[var(--text-primary)] p-1 rounded-full hover:bg-[var(--bg-surface-hover)] cursor-pointer"
            >
              ✕
            </button>
          </div>

          {/* View Mode */}
          <div className="space-y-1.5">
            <label className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Visual Mode
            </label>
            <div className="grid grid-cols-2 gap-1 bg-[var(--bg-surface-elevated)] p-1 rounded-2xl border border-[var(--border-subtle)]">
              {(['scroll', 'cinema'] as const).map((mode) => (
                <button
                  key={mode}
                  onClick={() => updateSettings({ lyricsMode: mode === 'scroll' ? 'spicy' : 'cinema' })}
                  className={`py-1.5 text-xs font-semibold rounded-xl capitalize transition cursor-pointer ${
                    (mode === 'scroll' && settings.lyricsMode !== 'cinema') ||
                    (mode === 'cinema' && settings.lyricsMode === 'cinema')
                      ? 'bg-[var(--m3-primary)] text-[var(--m3-on-primary)] font-bold shadow-md'
                      : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                  }`}
                >
                  {mode === 'scroll' ? 'Scroll' : 'Cinema'}
                </button>
              ))}
            </div>
          </div>

          {/* Font Size */}
          <div className="space-y-1.5">
            <label className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Font Size
            </label>
            <div className="grid grid-cols-3 gap-1 bg-[var(--bg-surface-elevated)] p-1 rounded-2xl border border-[var(--border-subtle)]">
              {(['small', 'medium', 'large'] as const).map((size) => (
                <button
                  key={size}
                  onClick={() => updateSettings({ lyricsFontSize: size })}
                  className={`py-1.5 text-xs font-semibold rounded-xl capitalize transition cursor-pointer ${
                    settings.lyricsFontSize === size
                      ? 'bg-[var(--m3-primary)] text-[var(--m3-on-primary)] font-bold shadow-md'
                      : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                  }`}
                >
                  {size}
                </button>
              ))}
            </div>
          </div>

          {/* Alignment */}
          <div className="flex items-center justify-between pt-1">
            <span className="text-xs font-semibold text-[var(--text-primary)]">Alignment</span>
            <div className="flex bg-[var(--bg-surface-elevated)] rounded-xl p-0.5 border border-[var(--border-subtle)]">
              <button
                onClick={() => updateSettings({ lyricsAlignment: 'left' })}
                className={`p-1.5 rounded-lg transition cursor-pointer ${
                  settings.lyricsAlignment === 'left' ? 'bg-[var(--m3-primary)] text-[var(--m3-on-primary)]' : 'text-[var(--text-muted)]'
                }`}
                title="Left Align"
              >
                <AlignLeft className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={() => updateSettings({ lyricsAlignment: 'center' })}
                className={`p-1.5 rounded-lg transition cursor-pointer ${
                  settings.lyricsAlignment === 'center' ? 'bg-[var(--m3-primary)] text-[var(--m3-on-primary)]' : 'text-[var(--text-muted)]'
                }`}
                title="Center Align"
              >
                <AlignCenter className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          {/* Focus Depth Blur */}
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-[var(--text-primary)]">Focus Depth Blur</span>
            <button
              onClick={() => updateSettings({ lyricsDepthBlur: !settings.lyricsDepthBlur })}
              className={`w-10 h-5 rounded-full transition relative p-0.5 cursor-pointer ${
                settings.lyricsDepthBlur ? 'bg-[var(--m3-primary)]' : 'bg-[var(--border-strong)]'
              }`}
            >
              <div
                className={`w-4 h-4 rounded-full transition-transform ${
                  settings.lyricsDepthBlur ? 'bg-[var(--m3-on-primary)] translate-x-5' : 'bg-neutral-400 translate-x-0'
                }`}
              />
            </button>
          </div>

          {/* Sync Offset Slider (Fine tuning) */}
          {isTimeSynced && (
            <div className="space-y-1.5 pt-1 border-t border-[var(--border-subtle)]">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
                  Sync Timing Offset
                </span>
                <span className="font-mono text-xs font-bold text-[var(--m3-primary)]">
                  {lyricsOffset > 0 ? `+${lyricsOffset.toFixed(1)}s` : `${lyricsOffset.toFixed(1)}s`}
                </span>
              </div>
              <div className="flex items-center gap-2">
                <input
                  type="range"
                  min="-5"
                  max="5"
                  step="0.1"
                  value={lyricsOffset}
                  onChange={(e) => setManualLyricsOffset(Number(parseFloat(e.target.value).toFixed(1)))}
                  className="w-full h-1.5 rounded-lg bg-[var(--bg-surface-elevated)] appearance-none cursor-pointer outline-none accent-[var(--m3-primary)]"
                />
                {lyricsOffset !== 0 && (
                  <button
                    onClick={() => setManualLyricsOffset(0)}
                    className="p-1 rounded-lg hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
                    title="Reset to 0.0s"
                  >
                    <RotateCcw className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>
          )}

          {/* Sync type info */}
          <div className="flex items-start gap-2 pt-2 border-t border-[var(--border-subtle)]">
            <Info className="w-3.5 h-3.5 text-[var(--text-muted)] mt-0.5 flex-shrink-0" />
            <p className="text-[10px] text-[var(--text-muted)] leading-relaxed">
              {isRichSynced
                ? 'This track has word-synced lyrics. Each word highlights as it is sung.'
                : isLineSynced
                  ? 'This track has line-synced lyrics. Each line highlights at its timestamp.'
                  : 'This track has plain lyrics without timing data.'}
            </p>
          </div>
        </div>
      )}

      {/* Main Lyrics Viewport */}
      <div
        ref={containerRef}
        onWheel={handleUserGesture}
        onTouchMove={handleUserGesture}
        onPointerDown={(e) => {
          if (e.target === containerRef.current) {
            handleUserGesture();
          }
        }}
        onScroll={handleScroll}
        className={`relative flex-1 overflow-y-auto px-4 sm:px-12 py-[45vh] space-y-8 scrollbar-none ${
          settings.lyricsAlignment === 'center' ? 'text-center' : 'text-left'
        }`}
      >
        {/* Translation error / notice if any */}
        {translationError && isTranslationActive && (
          <div className="flex items-center justify-center gap-2 py-2 px-4 rounded-xl bg-amber-500/10 border border-amber-500/20 text-xs text-amber-600 dark:text-amber-300 max-w-md mx-auto mb-4">
            <Info className="w-4 h-4 flex-shrink-0" />
            <span>{translationError}</span>
          </div>
        )}

        {/* ====== CINEMA MODE ====== */}
        {settings.lyricsMode === 'cinema' && isTimeSynced ? (
          activeLine ? (
            <div className="flex flex-col items-center justify-center min-h-[55vh] space-y-8 text-center px-4">
              <div
                onClick={() => seekTo(activeLine.time)}
                className="cursor-pointer transition-all duration-300 transform scale-105"
              >
                {isRichSynced && activeLine.words && activeLine.words.length > 0 ? (
                  /* Richsync cinema: per-word activation */
                  <p className="font-black text-4xl sm:text-5xl md:text-6xl tracking-tight drop-shadow-2xl">
                    {activeLine.words.map((w, wi) => (
                      <span
                        key={wi}
                        ref={(el) => registerCinemaWordRef(wi, el)}
                        data-word-time={w.time}
                        className="transition-colors duration-150 lyrics-word-inactive"
                      >
                        {w.word}{' '}
                      </span>
                    ))}
                  </p>
                ) : (
                  /* Line-sync cinema: entire line bright */
                  <p className="font-black text-4xl sm:text-5xl md:text-6xl tracking-tight drop-shadow-2xl text-[var(--text-primary)] dark:text-white">
                    {activeLine.text}
                  </p>
                )}

                {/* Cinema Mode: Translated Subtitle */}
                {isTranslationActive && activeLine.translatedText && (
                  <p className="font-semibold text-lg sm:text-2xl text-[var(--m3-primary)] italic mt-3 drop-shadow-md">
                    {activeLine.translatedText}
                  </p>
                )}
              </div>

              {nextLine && (
                <div
                  onClick={() => seekTo(nextLine.time)}
                  className="cursor-pointer transition hover:opacity-90 space-y-1"
                >
                  <p className="font-bold text-2xl sm:text-3xl text-[var(--text-muted)] opacity-40 hover:opacity-80 transition">
                    {nextLine.text}
                  </p>
                  {isTranslationActive && nextLine.translatedText && (
                    <p className="font-medium text-base sm:text-lg text-[var(--m3-primary)] italic">
                      {nextLine.translatedText}
                    </p>
                  )}
                </div>
              )}
            </div>
          ) : (
            /* Cinema Mode Intro State: Before First Line */
            <div className="flex flex-col items-center justify-center min-h-[55vh] space-y-6 text-center px-4">
              <div className="flex items-center gap-2 px-4 py-1.5 rounded-full bg-white/10 backdrop-blur-md border border-white/10">
                <span className="w-2 h-2 rounded-full bg-[var(--m3-primary)] animate-ping" />
                <span className="text-xs font-bold text-white tracking-wider uppercase">Intro</span>
              </div>
              {displayLines[0] && (
                <div
                  onClick={() => seekTo(displayLines[0].time)}
                  className="cursor-pointer space-y-1 opacity-50 hover:opacity-90 transition"
                >
                  <p className="text-xs font-mono text-[var(--text-muted)]">Upcoming:</p>
                  <p className="font-bold text-2xl sm:text-3xl text-white">
                    {displayLines[0].text}
                  </p>
                </div>
              )}
            </div>
          )

        /* ====== SCROLL MODE — TIMED LYRICS ====== */
        ) : isTimeSynced && displayLines.length > 0 ? (
          displayLines.map((line, index) => {
            const isActive = index === activeLyricIndex;
            const distance = Math.abs(index - activeLyricIndex);

            // Instrumental gap indicator
            const prevLine = index > 0 ? displayLines[index - 1] : null;
            const isLongGap = prevLine && (line.time - prevLine.time > 8);

            // Depth blur styling
            let blurStyle = 'none';
            let opacityVal = 0.28;
            let scaleStyle = 'scale(0.97)';

            if (settings.lyricsDepthBlur) {
              if (isActive) {
                blurStyle = 'blur(0px)';
                opacityVal = 1;
                scaleStyle = 'scale(1.02)';
              } else if (distance === 1) {
                blurStyle = 'blur(1px)';
                opacityVal = 0.55;
                scaleStyle = 'scale(0.99)';
              } else if (distance === 2) {
                blurStyle = 'blur(2px)';
                opacityVal = 0.35;
                scaleStyle = 'scale(0.97)';
              } else {
                blurStyle = 'blur(3px)';
                opacityVal = 0.2;
                scaleStyle = 'scale(0.95)';
              }
            } else {
              opacityVal = isActive ? 1 : 0.35;
              scaleStyle = isActive ? 'scale(1.02)' : 'scale(1)';
            }

            return (
              <React.Fragment key={`${line.time}-${index}`}>
                {/* Instrumental Break Indicator */}
                {isLongGap && (
                  <div className="flex items-center gap-2 py-4 opacity-40">
                    <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)]">
                      <span className="w-1.5 h-1.5 rounded-full bg-[var(--m3-primary)] animate-bounce" />
                      <span className="w-1.5 h-1.5 rounded-full bg-[var(--m3-primary)] animate-bounce [animation-delay:0.2s]" />
                      <span className="w-1.5 h-1.5 rounded-full bg-[var(--m3-primary)] animate-bounce [animation-delay:0.4s]" />
                      <span className="text-[10px] font-mono text-[var(--m3-primary)] ml-1">Instrumental</span>
                    </div>
                  </div>
                )}

                <div
                  ref={isActive ? activeLineRef : null}
                  onClick={() => seekTo(line.time)}
                  className="relative group py-2.5 px-2 cursor-pointer transition-all duration-500 ease-out origin-left"
                  style={{
                    filter: blurStyle,
                    opacity: opacityVal,
                    transform: scaleStyle,
                  }}
                >
                  <div className="flex flex-col gap-1">
                    {/* === RICHSYNC: per-word activation from real timestamps === */}
                    {isActive && isRichSynced && line.words && line.words.length > 0 ? (
                      <span
                        className={`font-black tracking-tight block drop-shadow-md ${
                          activeFontSizes[settings.lyricsFontSize] || activeFontSizes.medium
                        }`}
                      >
                        {line.words.map((w, wi) => (
                          <span
                            key={wi}
                            ref={(el) => registerWordRef(`${line.time}-${index}`, wi, el)}
                            data-word-time={w.time}
                            className="transition-colors duration-150 lyrics-word-inactive"
                          >
                            {w.word}{' '}
                          </span>
                        ))}
                      </span>
                    ) : (
                      /* === LINE-SYNC: entire line bright or dim === */
                      <span
                        className={`font-extrabold tracking-tight block ${
                          isActive
                            ? `text-white drop-shadow-[0_4px_16px_rgba(0,0,0,0.5)] ${activeFontSizes[settings.lyricsFontSize] || activeFontSizes.medium}`
                            : `text-white/40 ${fontSizes[settings.lyricsFontSize] || fontSizes.medium}`
                        }`}
                      >
                        {line.text}
                      </span>
                    )}

                    {/* Bilingual Translated Line */}
                    {isTranslationActive && line.translatedText && (
                      <span
                        className={`font-semibold italic block transition-all duration-300 ${
                          settings.lyricsAlignment === 'center' ? 'text-center' : 'text-left'
                        } ${
                          isActive
                            ? 'text-[var(--m3-primary)] opacity-100 text-sm sm:text-base md:text-lg drop-shadow'
                            : 'text-[var(--m3-primary)] opacity-70 text-xs sm:text-sm md:text-base'
                        }`}
                      >
                        {line.translatedText}
                      </span>
                    )}
                  </div>

                  {/* Hover: jump-to-time badge */}
                  <div
                    className="opacity-0 group-hover:opacity-100 transition-opacity absolute right-2 top-1/2 -translate-y-1/2 hidden sm:flex items-center gap-1 px-2.5 py-1 rounded-full bg-[var(--bg-surface-elevated)] text-[var(--m3-primary)] text-[11px] font-mono border border-[var(--border-subtle)] shadow-md"
                  >
                    <Zap className="w-3 h-3 text-[var(--m3-primary)]" />
                    <span>{formatTime(line.time)}</span>
                  </div>
                </div>
              </React.Fragment>
            );
          })

        /* ====== PLAIN LYRICS ====== */
        ) : (
          <div className="space-y-6 max-w-xl mx-auto py-8">
            <div className="whitespace-pre-line text-xl text-[var(--text-primary)] font-semibold leading-loose">
              {lyrics.plainLyrics}
            </div>
            {isTranslationActive && translatedPlain && (
              <div className="pt-6 border-t border-[var(--border-subtle)] whitespace-pre-line text-lg text-[var(--m3-primary)] font-medium italic leading-relaxed">
                <div className="text-xs font-mono text-[var(--text-muted)] uppercase mb-2">
                  Translation ({targetLang.toUpperCase()})
                </div>
                {translatedPlain}
              </div>
            )}
          </div>
        )}

        {/* Footer */}
        <div className="pt-16 pb-8 flex items-center justify-center opacity-40 text-[11px] font-mono text-[var(--text-muted)]">
          <span>
            {lyrics.provider === 'lrclib' ? 'LRCLIB' : lyrics.provider === 'youtube' ? 'YouTube Captions' : lyrics.provider}
            {' • '}{syncBadgeLabel}
            {isTranslationActive && ` • Translated (${targetLang.toUpperCase()})`}
          </span>
        </div>
      </div>

      {/* Auto-scroll resume floating button.
          Plain offsets: the NowPlayingModal wrapper already applies the
          safe-area padding, so adding env() again pushed it inward twice. */}
      {!autoScroll && isTimeSynced && (
        <button
          onClick={handleResumeAutoScroll}
          className="absolute bottom-4 sm:bottom-6 right-4 sm:right-8 px-4 sm:px-5 py-2 sm:py-2.5 rounded-full bg-[var(--m3-primary)] hover:bg-[var(--m3-primary-hover)] text-[var(--m3-on-primary)] text-xs font-black shadow-2xl border border-[var(--m3-primary-40)] transition active:scale-95 z-30 flex items-center gap-2 cursor-pointer"
        >
          <Sparkles className="w-3.5 h-3.5" />
          <span>Resume Auto-Scroll</span>
        </button>
      )}
    </div>
  );
};
