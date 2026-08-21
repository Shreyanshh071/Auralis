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
} from 'lucide-react';

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
  const activeLineRef = useRef<HTMLDivElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const userScrollTimeoutRef = useRef<any>(null);

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
    if (lyrics.syncType !== 'plain' && lyrics.lines.length > 0) {
      fullText = lyrics.lines.map((l) => l.text).join('\n');
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

  // --- Loading state ---
  if (isLoadingLyrics) {
    return (
      <div className="relative flex flex-col items-center justify-center h-full min-h-[350px] gap-3 text-[#dbe7b5] select-none">
        <div className="relative flex items-center justify-center">
          <div className="w-12 h-12 rounded-full border-2 border-[#dbe7b5]/20 border-t-[#dbe7b5] animate-spin" />
          <Sparkles className="w-5 h-5 text-[#dbe7b5] absolute animate-pulse" />
        </div>
        <p className="text-xs font-semibold tracking-wider uppercase text-[#c5cea9]">
          Fetching lyrics...
        </p>
      </div>
    );
  }

  // --- No lyrics state ---
  if (!lyrics || (isPlain && !lyrics.plainLyrics) || (isTimeSynced && lyrics.lines.length === 0)) {
    return (
      <div className="relative flex flex-col items-center justify-center h-full min-h-[350px] px-6 text-center select-none space-y-3">
        <div className="w-14 h-14 rounded-3xl bg-[#1b2214] flex items-center justify-center text-[#9ba582] border border-[#2e3823] shadow-lg">
          <Music className="w-7 h-7" />
        </div>
        <div>
          <h3 className="text-base font-bold text-[#f0f4dc]">No Lyrics Found</h3>
          <p className="text-xs text-[#9ba582] max-w-sm mt-1">
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
          className="px-5 py-2 rounded-full bg-[#202717] hover:bg-[#2c3520] text-xs font-bold text-[#dbe7b5] border border-[#354026] transition shadow-md"
        >
          Search Web for Lyrics
        </button>
      </div>
    );
  }

  // --- Active line for cinema mode ---
  const activeLine = isTimeSynced && activeLyricIndex >= 0 ? lyrics.lines[activeLyricIndex] : null;
  const nextLine = isTimeSynced && activeLyricIndex >= 0 && activeLyricIndex < lyrics.lines.length - 1
    ? lyrics.lines[activeLyricIndex + 1]
    : null;

  // --- Richsync: compute which words are active based on real word timestamps ---
  const getActiveWordCount = (line: typeof lyrics.lines[0]): number => {
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
      <div className="relative flex items-center justify-between px-6 py-3.5 border-b border-white/[0.04] bg-[#0e120a]/80 backdrop-blur-2xl z-20">
        <div className="flex items-center gap-3">
          {/* Sync type badge — honest label */}
          <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-[#1b2214] border border-[#2b3520] shadow-sm">
            {isTimeSynced && (
              <span className="w-2 h-2 rounded-full bg-[#dbe7b5] animate-pulse" />
            )}
            <span className="text-xs font-black text-[#dbe7b5] tracking-wide">
              {syncBadgeLabel}
            </span>
          </div>

          {/* Sync Offset Controls — only for timed lyrics */}
          {isTimeSynced && (
            <div className="hidden sm:flex items-center gap-1 bg-[#181f12] px-2.5 py-1 rounded-full text-xs text-[#9ba582] border border-[#28321c]">
              <Clock className="w-3 h-3" />
              <span className="text-[11px]">Sync:</span>
              <button
                onClick={() => setManualLyricsOffset(lyricsOffset - 0.5)}
                className="hover:text-white px-1.5 font-bold hover:bg-white/10 rounded transition"
                title="Delay lyrics (-0.5s)"
              >
                -
              </button>
              <span className="font-mono text-[#e0e7cd] font-semibold text-[11px] min-w-[28px] text-center">
                {lyricsOffset > 0 ? `+${lyricsOffset.toFixed(1)}s` : `${lyricsOffset.toFixed(1)}s`}
              </span>
              <button
                onClick={() => setManualLyricsOffset(lyricsOffset + 0.5)}
                className="hover:text-white px-1.5 font-bold hover:bg-white/10 rounded transition"
                title="Advance lyrics (+0.5s)"
              >
                +
              </button>
              {lyricsOffset !== 0 && (
                <button
                  onClick={() => setManualLyricsOffset(0)}
                  className="hover:text-white p-0.5 text-[#9ba582] ml-0.5"
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
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-full text-xs font-bold transition border ${
              showSettingsPanel
                ? 'bg-[#dbe7b5] text-[#161c0d] border-[#dbe7b5] shadow-lg'
                : 'bg-[#1b2214] hover:bg-[#262f1d] text-[#9ba582] hover:text-[#dbe7b5] border-[#2c3621]'
            }`}
            title="Lyrics Settings"
          >
            <Sliders className="w-3.5 h-3.5" />
            <span className="hidden sm:inline">Settings</span>
          </button>

          <button
            onClick={handleCopyLyrics}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[#1b2214] hover:bg-[#262f1d] text-xs font-semibold text-[#9ba582] hover:text-[#dbe7b5] border border-[#2c3621] transition"
            title="Copy all lyrics"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
            <span>{copied ? 'Copied' : 'Copy'}</span>
          </button>
        </div>
      </div>

      {/* Settings Popover */}
      {showSettingsPanel && (
        <div className="absolute top-14 right-6 z-40 w-72 p-4 rounded-3xl bg-[#171d10]/95 backdrop-blur-2xl border border-[#2e3a21] shadow-2xl space-y-4">
          <div className="flex items-center justify-between pb-2 border-b border-white/[0.04]">
            <span className="text-xs font-black text-[#dbe7b5] tracking-wide">Lyrics Settings</span>
            <button
              onClick={() => setShowSettingsPanel(false)}
              className="text-xs text-[#9ba582] hover:text-white p-1 rounded-full hover:bg-white/10"
            >
              ✕
            </button>
          </div>

          {/* View Mode */}
          <div className="space-y-1.5">
            <label className="text-[10px] font-bold uppercase tracking-wider text-[#9ba582]">
              Visual Mode
            </label>
            <div className="grid grid-cols-2 gap-1 bg-[#11160c] p-1 rounded-2xl border border-[#252f19]">
              {(['scroll', 'cinema'] as const).map((mode) => (
                <button
                  key={mode}
                  onClick={() => updateSettings({ lyricsMode: mode === 'scroll' ? 'spicy' : 'cinema' })}
                  className={`py-1.5 text-xs font-semibold rounded-xl capitalize transition ${
                    (mode === 'scroll' && settings.lyricsMode !== 'cinema') ||
                    (mode === 'cinema' && settings.lyricsMode === 'cinema')
                      ? 'bg-[#dbe7b5] text-[#161c0d] font-bold shadow-md'
                      : 'text-[#9ba582] hover:text-white'
                  }`}
                >
                  {mode === 'scroll' ? 'Scroll' : 'Cinema'}
                </button>
              ))}
            </div>
          </div>

          {/* Font Size */}
          <div className="space-y-1.5">
            <label className="text-[10px] font-bold uppercase tracking-wider text-[#9ba582]">
              Font Size
            </label>
            <div className="grid grid-cols-3 gap-1 bg-[#11160c] p-1 rounded-2xl border border-[#252f19]">
              {(['small', 'medium', 'large'] as const).map((size) => (
                <button
                  key={size}
                  onClick={() => updateSettings({ lyricsFontSize: size })}
                  className={`py-1.5 text-xs font-semibold rounded-xl capitalize transition ${
                    settings.lyricsFontSize === size
                      ? 'bg-[#dbe7b5] text-[#161c0d] font-bold shadow-md'
                      : 'text-[#9ba582] hover:text-white'
                  }`}
                >
                  {size}
                </button>
              ))}
            </div>
          </div>

          {/* Alignment */}
          <div className="flex items-center justify-between pt-1">
            <span className="text-xs font-semibold text-[#e0e7cd]">Alignment</span>
            <div className="flex bg-[#11160c] rounded-xl p-0.5 border border-[#252f19]">
              <button
                onClick={() => updateSettings({ lyricsAlignment: 'left' })}
                className={`p-1.5 rounded-lg transition ${
                  settings.lyricsAlignment === 'left' ? 'bg-[#dbe7b5] text-[#161c0d]' : 'text-[#9ba582]'
                }`}
                title="Left Align"
              >
                <AlignLeft className="w-3.5 h-3.5" />
              </button>
              <button
                onClick={() => updateSettings({ lyricsAlignment: 'center' })}
                className={`p-1.5 rounded-lg transition ${
                  settings.lyricsAlignment === 'center' ? 'bg-[#dbe7b5] text-[#161c0d]' : 'text-[#9ba582]'
                }`}
                title="Center Align"
              >
                <AlignCenter className="w-3.5 h-3.5" />
              </button>
            </div>
          </div>

          {/* Focus Depth Blur */}
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold text-[#e0e7cd]">Focus Depth Blur</span>
            <button
              onClick={() => updateSettings({ lyricsDepthBlur: !settings.lyricsDepthBlur })}
              className={`w-10 h-5 rounded-full transition relative p-0.5 ${
                settings.lyricsDepthBlur ? 'bg-[#dbe7b5]' : 'bg-[#2b3520]'
              }`}
            >
              <div
                className={`w-4 h-4 rounded-full transition-transform ${
                  settings.lyricsDepthBlur ? 'bg-[#161c0d] translate-x-5' : 'bg-[#8a9573] translate-x-0'
                }`}
              />
            </button>
          </div>

          {/* Sync type info */}
          <div className="flex items-start gap-2 pt-2 border-t border-white/[0.04]">
            <Info className="w-3.5 h-3.5 text-[#9ba582] mt-0.5 flex-shrink-0" />
            <p className="text-[10px] text-[#9ba582] leading-relaxed">
              {isRichSynced
                ? 'This track has word-synced lyrics. Each word highlights as it is sung.'
                : isLineSynced
                  ? 'This track has line-synced lyrics. Each line highlights at its timestamp. Word-level sync requires word-timed data from the lyrics provider.'
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
                          isWordActive ? 'text-white' : 'text-white/25'
                        }`}
                      >
                        {w.word}{' '}
                      </span>
                    );
                  })}
                </p>
              ) : (
                /* Line-sync cinema: entire line bright */
                <p className="font-black text-4xl sm:text-5xl md:text-6xl tracking-tight drop-shadow-2xl text-white">
                  {activeLine.text}
                </p>
              )}
            </div>

            {nextLine && (
              <p
                onClick={() => seekTo(nextLine.time)}
                className="font-bold text-2xl sm:text-3xl text-white/25 hover:text-white/60 transition cursor-pointer"
              >
                {nextLine.text}
              </p>
            )}
          </div>

        /* ====== SCROLL MODE — TIMED LYRICS ====== */
        ) : isTimeSynced && lyrics.lines.length > 0 ? (
          lyrics.lines.map((line, index) => {
            const isActive = index === activeLyricIndex;
            const distance = Math.abs(index - activeLyricIndex);

            // Instrumental gap indicator
            const prevLine = index > 0 ? lyrics.lines[index - 1] : null;
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
                    <div className="flex items-center gap-1.5 px-3 py-1 rounded-full bg-white/5 border border-white/10">
                      <span className="w-1.5 h-1.5 rounded-full bg-[#dbe7b5] animate-bounce" />
                      <span className="w-1.5 h-1.5 rounded-full bg-[#dbe7b5] animate-bounce [animation-delay:0.2s]" />
                      <span className="w-1.5 h-1.5 rounded-full bg-[#dbe7b5] animate-bounce [animation-delay:0.4s]" />
                      <span className="text-[10px] font-mono text-[#dbe7b5] ml-1">Instrumental</span>
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
                              isWordActive ? 'text-white' : 'text-white/25'
                            }`}
                          >
                            {w.word}{' '}
                          </span>
                        );
                      })}
                    </span>
                  ) : (
                    /* === LINE-SYNC: entire line bright or dim, no word animation === */
                    <span
                      className={`font-extrabold tracking-tight block ${
                        isActive
                          ? `text-white drop-shadow-lg ${activeFontSizes[settings.lyricsFontSize] || activeFontSizes.medium}`
                          : `text-[#f0f4dc] ${fontSizes[settings.lyricsFontSize] || fontSizes.medium}`
                      }`}
                    >
                      {line.text}
                    </span>
                  )}

                  {/* Hover: jump-to-time badge */}
                  <div
                    className="opacity-0 group-hover:opacity-100 transition-opacity absolute right-2 top-1/2 -translate-y-1/2 hidden sm:flex items-center gap-1 px-2.5 py-1 rounded-full bg-[#1b2214] text-[#dbe7b5] text-[11px] font-mono border border-[#2d3722] shadow-md"
                  >
                    <Zap className="w-3 h-3 text-[#dbe7b5]" />
                    <span>{formatTime(line.time)}</span>
                  </div>
                </div>
              </React.Fragment>
            );
          })

        /* ====== PLAIN LYRICS ====== */
        ) : (
          <div className="whitespace-pre-line text-xl text-[#f0f4dc] font-semibold leading-loose max-w-xl mx-auto py-8">
            {lyrics.plainLyrics}
          </div>
        )}

        {/* Footer */}
        <div className="pt-16 pb-8 flex items-center justify-center opacity-40 text-[11px] font-mono text-[#9ba582]">
          <span>
            {lyrics.provider === 'lrclib' ? 'LRCLIB' : lyrics.provider === 'youtube' ? 'YouTube Captions' : lyrics.provider}
            {' • '}{syncBadgeLabel}
          </span>
        </div>
      </div>

      {/* Auto-scroll resume floating button */}
      {!autoScroll && isTimeSynced && (
        <button
          onClick={() => setAutoScroll(true)}
          className="absolute bottom-6 right-8 px-5 py-2.5 rounded-full bg-[#dbe7b5] text-[#14190c] text-xs font-black shadow-2xl border border-[#dbe7b5]/50 transition hover:bg-[#c9d79e] active:scale-95 z-30 flex items-center gap-2"
        >
          <Sparkles className="w-3.5 h-3.5" />
          <span>Resume Auto-Scroll</span>
        </button>
      )}
    </div>
  );
};
