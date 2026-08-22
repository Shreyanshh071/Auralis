import React, { useEffect, useRef, useState } from 'react';
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

interface SyncedLyricsProps {
  fullscreen?: boolean;
}

export const SyncedLyrics: React.FC<SyncedLyricsProps> = ({ fullscreen = false }) => {
  const {
    currentTrack,
    lyrics,
    isLoadingLyrics,
    activeLyricIndex,
    currentTime,
    seekTo,
    lyricsOffset,
    setManualLyricsOffset,
    settings,
    updateSettings,
    dominantColor,
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
  const userScrollTimeoutRef = useRef<any>(null);

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

  // Auto-scroll to active lyric line smoothly
  useEffect(() => {
    if (!autoScroll || activeLyricIndex < 0 || !activeLineRef.current || !containerRef.current) return;

    activeLineRef.current.scrollIntoView({
      behavior: 'smooth',
      block: 'center',
    });
  }, [activeLyricIndex, autoScroll]);

  // Handle user manual scroll — pause auto-scroll for 4s
  const handleUserScroll = () => {
    if (!autoScroll) return;
    setAutoScroll(false);

    if (userScrollTimeoutRef.current) clearTimeout(userScrollTimeoutRef.current);
    userScrollTimeoutRef.current = setTimeout(() => {
      setAutoScroll(true);
    }, 4000);
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
    small: 'text-lg sm:text-xl md:text-2xl font-bold leading-relaxed',
    medium: 'text-xl sm:text-2xl md:text-3xl font-extrabold leading-relaxed',
    large: 'text-2xl sm:text-3xl md:text-4xl lg:text-5xl font-black leading-tight',
  };

  const activeFontSizes = {
    small: 'text-2xl sm:text-3xl md:text-4xl font-extrabold tracking-tight leading-snug',
    medium: 'text-3xl sm:text-4xl md:text-5xl lg:text-6xl font-black tracking-tight leading-none',
    large: 'text-4xl sm:text-5xl md:text-6xl lg:text-7xl font-black tracking-tight leading-none',
  };

  // --- Derived state ---
  const isLineSynced = lyrics?.syncType === 'line-sync';
  const isRichSynced = lyrics?.syncType === 'richsync';
  const isTimeSynced = isLineSynced || isRichSynced;
  const isPlain = lyrics?.syncType === 'plain';

  // Sync type badge label
  const syncBadgeLabel = isRichSynced ? 'Word Synced' : isLineSynced ? 'Line Synced' : 'Lyrics';
  const activeLanguageObj = SUPPORTED_LANGUAGES.find((l) => l.code === targetLang) || SUPPORTED_LANGUAGES[0];

  // --- Loading state ---
  if (isLoadingLyrics) {
    return (
      <div className="relative flex flex-col items-center justify-center h-full min-h-[350px] gap-3 text-purple-500 dark:text-[#dbe7b5] select-none">
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

  // --- Richsync: compute which words are active based on real word timestamps ---
  const getActiveWordCount = (line: typeof displayLines[0]): number => {
    if (!isRichSynced || !line.words || line.words.length === 0) return -1;
    const adjustedTime = currentTime + lyricsOffset;
    let count = 0;
    for (const w of line.words) {
      if (adjustedTime >= w.time) {
        count++;
      } else {
        break;
      }
    }
    return count;
  };

  return (
    <div className="relative flex flex-col h-full overflow-hidden select-text">
      {/* Ambient glow backdrop */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden opacity-20">
        <div
          className="absolute -top-[20%] -left-[20%] w-[70vw] h-[70vw] rounded-full blur-[160px] transition-colors duration-1000"
          style={{ background: dominantColor }}
        />
        <div
          className="absolute -bottom-[20%] -right-[20%] w-[60vw] h-[60vw] rounded-full blur-[180px] opacity-70 transition-colors duration-1000"
          style={{ background: dominantColor }}
        />
      </div>

      {/* Control Header Bar */}
      <div className="relative flex items-center justify-between px-4 sm:px-6 py-3 border-b border-[var(--border-subtle)] bg-[var(--bg-header)] backdrop-blur-2xl z-20 text-[var(--text-primary)]">
        <div className="flex items-center gap-2 sm:gap-3 flex-wrap">
          {/* Sync type badge */}
          <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] shadow-sm">
            {isTimeSynced && (
              <span className="w-2 h-2 rounded-full bg-purple-500 dark:bg-[#dbe7b5] animate-pulse" />
            )}
            <span className="text-xs font-black text-purple-600 dark:text-[#dbe7b5] tracking-wide">
              {syncBadgeLabel}
            </span>
          </div>

          {/* Translation Toggle & Language Selector */}
          <div className="relative flex items-center">
            <button
              onClick={handleToggleTranslation}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-l-full text-xs font-bold transition border cursor-pointer ${
                isTranslationActive
                  ? 'bg-purple-600 text-white dark:bg-[#dbe7b5] dark:text-[#161c0d] border-purple-600 dark:border-[#dbe7b5] shadow-md'
                  : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
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
                  ? 'bg-purple-700 text-white dark:bg-[#c9d79e] dark:text-[#161c0d] border-purple-600 dark:border-[#dbe7b5]'
                  : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] border-[var(--border-subtle)]'
              }`}
              title="Select translation language"
            >
              <ChevronDown className="w-3 quasi-3" />
            </button>

            {/* Language Selection Dropdown */}
            {showLangMenu && (
              <div className="absolute top-10 left-0 z-50 w-48 py-2 rounded-2xl bg-[var(--bg-popover)] border border-[var(--border-medium)] shadow-2xl space-y-0.5 max-h-60 overflow-y-auto">
                <div className="px-3 py-1 text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)] border-b border-[var(--border-subtle)]">
                  Translate To
                </div>
                {SUPPORTED_LANGUAGES.map((lang) => (
                  <button
                    key={lang.code}
                    onClick={() => handleSelectLanguage(lang.code)}
                    className={`w-full text-left px-3 py-1.5 text-xs flex items-center justify-between hover:bg-[var(--bg-surface-hover)] transition cursor-pointer ${
                      targetLang === lang.code ? 'text-purple-600 dark:text-[#dbe7b5] font-bold bg-[var(--bg-surface-elevated)]' : 'text-[var(--text-primary)]'
                    }`}
                  >
                    <span>{lang.name}</span>
                    <span className="text-[10px] text-[var(--text-muted)] font-mono uppercase">{lang.code}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Sync Offset Controls — only for timed lyrics */}
          {isTimeSynced && (
            <div className="hidden md:flex items-center gap-1 bg-[var(--bg-surface-elevated)] px-2.5 py-1 rounded-full text-xs text-[var(--text-secondary)] border border-[var(--border-subtle)] shadow-sm">
              <Clock className="w-3 h-3" />
              <span className="text-[11px]">Sync:</span>
              <button
                onClick={() => setManualLyricsOffset(lyricsOffset - 0.5)}
                className="hover:text-[var(--text-primary)] px-1.5 font-bold hover:bg-[var(--bg-surface-hover)] rounded transition cursor-pointer"
                title="Delay lyrics (-0.5s)"
              >
                -
              </button>
              <span className="font-mono text-[var(--text-primary)] font-semibold text-[11px] min-w-[28px] text-center">
                {lyricsOffset > 0 ? `+${lyricsOffset.toFixed(1)}s` : `${lyricsOffset.toFixed(1)}s`}
              </span>
              <button
                onClick={() => setManualLyricsOffset(lyricsOffset + 0.5)}
                className="hover:text-[var(--text-primary)] px-1.5 font-bold hover:bg-[var(--bg-surface-hover)] rounded transition cursor-pointer"
                title="Advance lyrics (+0.5s)"
              >
                +
              </button>
              {lyricsOffset !== 0 && (
                <button
                  onClick={() => setManualLyricsOffset(0)}
                  className="hover:text-[var(--text-primary)] p-0.5 text-[var(--text-muted)] ml-0.5 cursor-pointer"
                  title="Reset offset"
                >
                  <RotateCcw className="w-2.5 h-2.5" />
                </button>
              )}
            </div>
          )}
        </div>

        {/* Settings + Copy */}
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowSettingsPanel(!showSettingsPanel)}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold transition border cursor-pointer ${
              showSettingsPanel
                ? 'bg-purple-600 text-white dark:bg-[#dbe7b5] dark:text-[#161c0d] border-purple-600 dark:border-[#dbe7b5] shadow-lg'
                : 'bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] border-[var(--border-subtle)]'
            }`}
            title="Lyrics Settings"
          >
            <Sliders className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Settings</span>
          </button>

          <button
            onClick={handleCopyLyrics}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-xs font-semibold text-[var(--text-secondary)] hover:text-[var(--text-primary)] border border-[var(--border-subtle)] transition cursor-pointer"
            title="Copy all lyrics"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Copied' : 'Copy'}</span>
          </button>
        </div>
      </div>

      {/* Settings Popover */}
      {showSettingsPanel && (
        <div className="absolute top-14 right-6 z-40 w-72 p-4 rounded-3xl bg-[var(--bg-popover)] backdrop-blur-2xl border border-[var(--border-medium)] shadow-2xl space-y-4 text-[var(--text-primary)]">
          <div className="flex items-center justify-between pb-2 border-b border-[var(--border-subtle)]">
            <span className="text-xs font-black text-purple-600 dark:text-[#dbe7b5] tracking-wide">Lyrics Settings</span>
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
                      ? 'bg-purple-600 text-white dark:bg-[#dbe7b5] dark:text-[#161c0d] font-bold shadow-md'
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
                      ? 'bg-purple-600 text-white dark:bg-[#dbe7b5] dark:text-[#161c0d] font-bold shadow-md'
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
                  settings.lyricsAlignment === 'left' ? 'bg-purple-600 text-white dark:bg-[#dbe7b5] dark:text-[#161c0d]' : 'text-[var(--text-muted)]'
                }`}
                title="Left Align"
              >
                <AlignLeft className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={() => updateSettings({ lyricsAlignment: 'center' })}
                className={`p-1.5 rounded-lg transition cursor-pointer ${
                  settings.lyricsAlignment === 'center' ? 'bg-purple-600 text-white dark:bg-[#dbe7b5] dark:text-[#161c0d]' : 'text-[var(--text-muted)]'
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
                settings.lyricsDepthBlur ? 'bg-purple-600 dark:bg-[#dbe7b5]' : 'bg-[var(--border-strong)]'
              }`}
            >
              <div
                className={`w-4 h-4 rounded-full transition-transform ${
                  settings.lyricsDepthBlur ? 'bg-white dark:bg-[#161c0d] translate-x-5' : 'bg-neutral-400 translate-x-0'
                }`}
              />
            </button>
          </div>

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
        onScroll={handleUserScroll}
        className={`flex-1 overflow-y-auto px-6 sm:px-14 py-20 scroll-smooth space-y-8 ${
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
        {settings.lyricsMode === 'cinema' && isTimeSynced && activeLine ? (
          <div className="flex flex-col items-center justify-center min-h-[55vh] space-y-8 text-center px-4">
            <div
              onClick={() => seekTo(activeLine.time)}
              className="cursor-pointer transition-all duration-300 transform scale-105"
            >
              {isRichSynced && activeLine.words && activeLine.words.length > 0 ? (
                /* Richsync cinema: per-word activation */
                <p className="font-black text-4xl sm:text-5xl md:text-6xl tracking-tight drop-shadow-2xl">
                  {activeLine.words.map((w, wi) => {
                    const adjustedTime = currentTime + lyricsOffset;
                    const isWordActive = adjustedTime >= w.time;
                    return (
                      <span
                        key={wi}
                        className={`transition-colors duration-150 ${
                          isWordActive ? 'text-[var(--text-primary)] dark:text-white' : 'text-[var(--text-muted)] opacity-30'
                        }`}
                      >
                        {w.word}{' '}
                      </span>
                    );
                  })}
                </p>
              ) : (
                /* Line-sync cinema: entire line bright */
                <p className="font-black text-4xl sm:text-5xl md:text-6xl tracking-tight drop-shadow-2xl text-[var(--text-primary)] dark:text-white">
                  {activeLine.text}
                </p>
              )}

              {/* Cinema Mode: Translated Subtitle */}
              {isTranslationActive && activeLine.translatedText && (
                <p className="font-semibold text-lg sm:text-2xl text-purple-600 dark:text-[#dbe7b5]/90 italic mt-3 drop-shadow-md">
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
                  <p className="font-medium text-base sm:text-lg text-purple-500/60 dark:text-[#dbe7b5]/40 italic">
                    {nextLine.translatedText}
                  </p>
                )}
              </div>
            )}
          </div>

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
                      <span className="w-1.5 h-1.5 rounded-full bg-purple-500 dark:bg-[#dbe7b5] animate-bounce" />
                      <span className="w-1.5 h-1.5 rounded-full bg-purple-500 dark:bg-[#dbe7b5] animate-bounce [animation-delay:0.2s]" />
                      <span className="w-1.5 h-1.5 rounded-full bg-purple-500 dark:bg-[#dbe7b5] animate-bounce [animation-delay:0.4s]" />
                      <span className="text-[10px] font-mono text-purple-600 dark:text-[#dbe7b5] ml-1">Instrumental</span>
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
                        {line.words.map((w, wi) => {
                          const adjustedTime = currentTime + lyricsOffset;
                          const isWordActive = adjustedTime >= w.time;
                          return (
                            <span
                              key={wi}
                              className={`transition-colors duration-150 ${
                                isWordActive ? 'text-[var(--text-primary)] dark:text-white' : 'text-[var(--text-muted)] opacity-30'
                              }`}
                            >
                              {w.word}{' '}
                            </span>
                          );
                        })}
                      </span>
                    ) : (
                      /* === LINE-SYNC: entire line bright or dim === */
                      <span
                        className={`font-extrabold tracking-tight block ${
                          isActive
                            ? `text-[var(--text-primary)] dark:text-white drop-shadow-lg ${activeFontSizes[settings.lyricsFontSize] || activeFontSizes.medium}`
                            : `text-[var(--text-secondary)] dark:text-[#f0f4dc] ${fontSizes[settings.lyricsFontSize] || fontSizes.medium}`
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
                            ? 'text-purple-600 dark:text-[#dbe7b5] opacity-100 text-sm sm:text-base md:text-lg drop-shadow'
                            : 'text-purple-500/70 dark:text-[#dbe7b5]/70 opacity-70 text-xs sm:text-sm md:text-base'
                        }`}
                      >
                        {line.translatedText}
                      </span>
                    )}
                  </div>

                  {/* Hover: jump-to-time badge */}
                  <div
                    className="opacity-0 group-hover:opacity-100 transition-opacity absolute right-2 top-1/2 -translate-y-1/2 hidden sm:flex items-center gap-1 px-2.5 py-1 rounded-full bg-[var(--bg-surface-elevated)] text-purple-600 dark:text-[#dbe7b5] text-[11px] font-mono border border-[var(--border-subtle)] shadow-md"
                  >
                    <Zap className="w-3 h-3 text-purple-500 dark:text-[#dbe7b5]" />
                    <span>{formatTime(line.time)}</span>
                  </div>
                </div>
              </React.Fragment>
            );
          })

        /* ====== PLAIN LYRICS ====== */
        ) : (
          <div className="space-y-6 max-w-xl mx-auto py-8">
            <div className="whitespace-pre-line text-xl text-[var(--text-primary)] dark:text-[#f0f4dc] font-semibold leading-loose">
              {lyrics.plainLyrics}
            </div>
            {isTranslationActive && translatedPlain && (
              <div className="pt-6 border-t border-[var(--border-subtle)] whitespace-pre-line text-lg text-purple-600 dark:text-[#dbe7b5]/85 font-medium italic leading-relaxed">
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

      {/* Auto-scroll resume floating button */}
      {!autoScroll && isTimeSynced && (
        <button
          onClick={() => setAutoScroll(true)}
          className="absolute bottom-6 right-8 px-5 py-2.5 rounded-full bg-purple-600 hover:bg-purple-700 text-white dark:bg-[#dbe7b5] dark:text-[#14190c] dark:hover:bg-[#c9d79e] text-xs font-black shadow-2xl border border-purple-500/50 dark:border-[#dbe7b5]/50 transition active:scale-95 z-30 flex items-center gap-2 cursor-pointer"
        >
          <Sparkles className="w-3.5 h-3.5" />
          <span>Resume Auto-Scroll</span>
        </button>
      )}
    </div>
  );
};
