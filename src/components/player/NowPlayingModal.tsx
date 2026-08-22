import React, { useState } from 'react';
import { usePlayer, PLAYBACK_RATES } from '../../context/PlayerContext';
import { SyncedLyrics } from '../lyrics/SyncedLyrics';
import { AudioVisualizer } from '../visualizer/AudioVisualizer';
import {
  ChevronDown,
  ChevronUp,
  Heart,
  Shuffle,
  SkipBack,
  Play,
  Pause,
  SkipForward,
  Repeat,
  Repeat1,
  ListMusic,
  Activity,
  Moon,
  Volume2,
  VolumeX,
  Trash2,
  Mic2,
  Radio,
} from 'lucide-react';
import { AddToPlaylistButton } from '../modals/AddToPlaylistButton';

export const NowPlayingModal: React.FC = () => {
  const {
    currentTrack,
    isPlaying,
    currentTime,
    duration,
    togglePlay,
    seekTo,
    nextTrack,
    prevTrack,
    repeatMode,
    toggleRepeat,
    isShuffle,
    toggleShuffle,
    isFavorite,
    toggleFavorite,
    isNowPlayingOpen,
    setIsNowPlayingOpen,
    activeModalTab,
    setActiveModalTab,
    queue,
    queueIndex,
    playTrack,
    removeFromQueue,
    reorderQueue,
    clearQueue,
    volume,
    setVolume,
    isMuted,
    toggleMute,
    playbackRate,
    setPlaybackRate,
    dominantColor,
    sleepTimerRemaining,
    setSleepTimer,
  } = usePlayer();

  const [showSleepModal, setShowSleepModal] = useState(false);
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [scrubTime, setScrubTime] = useState(0);
  const [mobileTab, setMobileTab] = useState<'player' | 'lyrics' | 'queue' | 'visualizer'>('player');

  if (!isNowPlayingOpen || !currentTrack) return null;

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const displayTime = isScrubbing ? scrubTime : currentTime;
  const progressPercent = duration > 0 ? (displayTime / duration) * 100 : 0;
  const favorite = isFavorite(currentTrack.id);

  // Step through the supported speeds, wrapping back to the slowest. Rates the
  // player can't honour are impossible here because the list is the same one the
  // context clamps to.
  const cyclePlaybackRate = () => {
    const i = PLAYBACK_RATES.indexOf(playbackRate);
    const next = PLAYBACK_RATES[(i + 1) % PLAYBACK_RATES.length];
    setPlaybackRate(next);
  };

  /**
   * Exactly one tab may render in the right-hand panel.
   *
   * The previous conditions OR-ed `mobileTab` with `activeModalTab`, so on a phone
   * with the Queue tab selected while `activeModalTab` was still 'lyrics', both the
   * lyrics view and the queue rendered stacked inside the same panel. Deriving a
   * single value makes that impossible.
   *
   * The mobile tab bar owns the panel whenever it is off 'player'; otherwise the
   * desktop tab selection applies.
   */
  const panelTab: 'lyrics' | 'queue' | 'visualizer' | 'info' =
    mobileTab !== 'player' ? mobileTab : activeModalTab;

  return (
    <div className="fixed inset-0 z-50 flex flex-col bg-[var(--bg-base)] text-[var(--text-primary)] overflow-hidden select-none animate-in fade-in duration-200">
      {/* Dynamic Ambient Background Glow */}
      <div
        className="absolute inset-0 pointer-events-none opacity-20 transition-all duration-1000"
        style={{
          background: `radial-gradient(circle at 50% 30%, ${dominantColor} 0%, rgba(14, 17, 12, 0.98) 70%)`,
        }}
      />

      {/* Top Header Bar */}
      <div className="relative z-10 flex items-center justify-between px-5 sm:px-8 py-4 border-b border-[var(--border-subtle)]">
        <button
          onClick={() => setIsNowPlayingOpen(false)}
          className="p-2 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
          title="Minimize player"
        >
          <ChevronDown className="w-6 h-6" />
        </button>

        {/* Mobile Tab Switcher */}
        <div className="flex lg:hidden items-center p-1 bg-[var(--bg-surface-elevated)] rounded-full border border-[var(--border-subtle)]">
          <button
            onClick={() => setMobileTab('player')}
            className={`px-4 py-1 rounded-full text-xs font-semibold transition cursor-pointer ${
              mobileTab === 'player'
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#191f0f] font-bold shadow-sm'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            Track
          </button>
          <button
            onClick={() => setMobileTab('lyrics')}
            className={`flex items-center gap-1 px-3.5 py-1 rounded-full text-xs font-semibold transition cursor-pointer ${
              mobileTab === 'lyrics'
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#191f0f] font-bold shadow-sm'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            <Mic2 className="w-3.5 h-3.5" />
            <span>Lyrics</span>
          </button>
          <button
            onClick={() => setMobileTab('queue')}
            className={`px-3.5 py-1 rounded-full text-xs font-semibold transition cursor-pointer ${
              mobileTab === 'queue'
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#191f0f] font-bold shadow-sm'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            Queue
          </button>
        </div>

        {/* Desktop Tab Switcher */}
        <div className="hidden lg:flex items-center p-1 bg-[var(--bg-surface-elevated)] rounded-full border border-[var(--border-subtle)]">
          <button
            onClick={() => setActiveModalTab('lyrics')}
            className={`flex items-center gap-2 px-4 py-1.5 rounded-full text-xs font-semibold transition cursor-pointer ${
              activeModalTab === 'lyrics'
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#191f0f] font-bold shadow'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            <Mic2 className="w-3.5 h-3.5" />
            <span>Lyrics</span>
          </button>
          <button
            onClick={() => setActiveModalTab('queue')}
            className={`flex items-center gap-2 px-4 py-1.5 rounded-full text-xs font-semibold transition cursor-pointer ${
              activeModalTab === 'queue'
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#191f0f] font-bold shadow'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            <ListMusic className="w-3.5 h-3.5" />
            <span>Queue ({queue.length})</span>
          </button>
          <button
            onClick={() => setActiveModalTab('visualizer')}
            className={`flex items-center gap-2 px-4 py-1.5 rounded-full text-xs font-semibold transition cursor-pointer ${
              activeModalTab === 'visualizer'
                ? 'bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#191f0f] font-bold shadow'
                : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
            }`}
          >
            <Activity className="w-3.5 h-3.5" />
            <span>Visualizer</span>
          </button>
        </div>

        {/* Sleep Timer Tool */}
        <div className="flex items-center gap-2">
          <button
            onClick={cyclePlaybackRate}
            className={`px-2.5 py-1.5 rounded-full text-xs font-bold tabular-nums text-center min-w-[2.9rem] transition cursor-pointer ${
              playbackRate !== 1
                ? 'bg-[var(--bg-surface-elevated)] text-purple-600 dark:text-[#dbe7b5] border border-[var(--border-subtle)]'
                : 'hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
            }`}
            title="Playback speed"
            aria-label={`Playback speed ${playbackRate}x, tap to change`}
          >
            {playbackRate}&times;
          </button>
          <button
            onClick={() => setShowSleepModal(!showSleepModal)}
            className={`p-2 rounded-full transition relative cursor-pointer ${
              sleepTimerRemaining !== null
                ? 'bg-[var(--bg-surface-elevated)] text-purple-600 dark:text-[#dbe7b5] border border-[var(--border-subtle)]'
                : 'hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)]'
            }`}
            title="Sleep Timer"
          >
            <Moon className="w-5 h-5" />
          </button>
        </div>
      </div>

      {/* Sleep Timer Popover */}
      {showSleepModal && (
        <div className="absolute top-16 right-5 sm:right-8 z-30 w-60 p-4 rounded-2xl bg-[var(--bg-popover)] border border-[var(--border-medium)] shadow-2xl space-y-3">
          <div className="flex items-center justify-between pb-2 border-b border-[var(--border-subtle)]">
            <span className="text-xs font-bold uppercase tracking-wider text-purple-600 dark:text-[#dbe7b5]">
              Sleep Timer
            </span>
            {sleepTimerRemaining !== null && (
              <button
                onClick={() => setSleepTimer(null)}
                className="text-[11px] text-rose-500 hover:underline cursor-pointer"
              >
                Turn off
              </button>
            )}
          </div>
          <div className="grid grid-cols-2 gap-2">
            {[15, 30, 45, 60].map((mins) => (
              <button
                key={mins}
                onClick={() => {
                  setSleepTimer(mins);
                  setShowSleepModal(false);
                }}
                className="px-3 py-2 rounded-xl bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-xs font-semibold text-[var(--text-primary)] border border-[var(--border-subtle)] transition cursor-pointer"
              >
                {mins} Mins
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Main Content Area */}
      <div className="relative z-10 flex-1 grid grid-cols-1 lg:grid-cols-12 gap-8 px-6 py-4 sm:p-8 lg:p-12 overflow-hidden max-w-7xl mx-auto w-full">
        {/* Left Side: Artwork & Controls */}
        <div
          className={`${
            mobileTab === 'player' ? 'flex' : 'hidden lg:flex'
          } lg:col-span-5 flex-col justify-between max-w-sm sm:max-w-md mx-auto w-full h-full pb-4`}
        >
          {/* Centered Large Square Artwork */}
          <div className="flex-1 flex items-center justify-center my-auto py-2">
            <div className="relative group w-72 h-72 sm:w-80 sm:h-80 max-w-[82vw] aspect-square rounded-3xl overflow-hidden shadow-2xl border border-[var(--border-subtle)] bg-[var(--bg-surface-elevated)] flex-shrink-0">
              <img
                src={currentTrack.thumbnail}
                alt={currentTrack.title}
                className="w-full h-full object-cover aspect-square scale-[1.04]"
                onError={(e) => {
                  const target = e.currentTarget;
                  if (!target.src.includes('hqdefault')) {
                    target.src = `https://i.ytimg.com/vi/${currentTrack.id}/hqdefault.jpg`;
                  }
                }}
              />
            </div>
          </div>

          {/* Bottom Details & Controls Block */}
          <div className="space-y-4 sm:space-y-5">
            {/* Title & Heart */}
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0 flex-1">
                <h2 className="font-display font-extrabold text-xl sm:text-2xl text-[var(--text-primary)] truncate leading-tight">
                  {currentTrack.title}
                </h2>
                <p className="text-sm text-[var(--text-muted)] truncate mt-1 font-medium">
                  {currentTrack.artist}
                </p>
              </div>

              <div className="flex items-center flex-shrink-0">
                <AddToPlaylistButton
                  track={currentTrack}
                  className="p-2.5 rounded-full hover:bg-[var(--bg-surface-hover)] transition text-[var(--text-secondary)] hover:text-[var(--text-primary)] cursor-pointer"
                  iconClassName="w-6 h-6"
                />

                <button
                  onClick={() => toggleFavorite(currentTrack)}
                  className="p-2.5 rounded-full hover:bg-[var(--bg-surface-hover)] transition cursor-pointer"
                  title={favorite ? 'Remove from Liked' : 'Add to Liked'}
                >
                  <Heart
                    className={`w-6 h-6 ${
                      favorite ? 'fill-rose-500 text-rose-500' : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                    }`}
                  />
                </button>
              </div>
            </div>

            {/* Scrubber */}
            <div className="space-y-1.5">
              <div className="relative flex items-center">
                <input
                  type="range"
                  min={0}
                  max={duration || 100}
                  value={displayTime}
                  onMouseDown={() => setIsScrubbing(true)}
                  onTouchStart={() => setIsScrubbing(true)}
                  onChange={(e) => {
                    const val = Number(e.target.value);
                    setScrubTime(val);
                    if (!isScrubbing) seekTo(val);
                  }}
                  onMouseUp={() => {
                    setIsScrubbing(false);
                    seekTo(scrubTime);
                  }}
                  onTouchEnd={() => {
                    setIsScrubbing(false);
                    seekTo(scrubTime);
                  }}
                  className="w-full h-1.5 rounded-lg bg-[var(--border-medium)] appearance-none cursor-pointer outline-none"
                />
              </div>

              <div className="flex justify-between text-xs font-mono text-[var(--text-muted)]">
                <span>{formatTime(displayTime)}</span>
                <span>{formatTime(duration)}</span>
              </div>
            </div>

            {/* Playback Controls */}
            <div className="flex items-center justify-between px-2 pt-1">
              <button
                onClick={toggleShuffle}
                className={`p-2.5 rounded-full transition cursor-pointer ${
                  isShuffle ? 'text-purple-600 dark:text-[#dbe7b5]' : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                }`}
                title="Shuffle"
              >
                <Shuffle className="w-5 h-5" />
              </button>

              <button
                onClick={prevTrack}
                className="p-2.5 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition active:scale-95 cursor-pointer"
                title="Previous"
              >
                <SkipBack className="w-6 h-6 fill-current" />
              </button>

              <button
                onClick={togglePlay}
                className="p-4 rounded-full bg-[var(--text-primary)] text-[var(--text-inverse)] dark:bg-[#dbe7b5] dark:text-[#171e0d] hover:scale-105 active:scale-95 transition flex items-center justify-center shadow-xl cursor-pointer"
                title={isPlaying ? 'Pause' : 'Play'}
              >
                {isPlaying ? (
                  <Pause className="w-7 h-7 fill-current" />
                ) : (
                  <Play className="w-7 h-7 fill-current ml-0.5" />
                )}
              </button>

              <button
                onClick={nextTrack}
                className="p-2.5 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition active:scale-95 cursor-pointer"
                title="Next"
              >
                <SkipForward className="w-6 h-6 fill-current" />
              </button>

              <button
                onClick={toggleRepeat}
                className={`p-2.5 rounded-full transition cursor-pointer ${
                  repeatMode !== 'off' ? 'text-purple-600 dark:text-[#dbe7b5]' : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                }`}
                title={`Repeat: ${repeatMode}`}
              >
                {repeatMode === 'one' ? (
                  <Repeat1 className="w-5 h-5" />
                ) : (
                  <Repeat className="w-5 h-5" />
                )}
              </button>
            </div>

            {/* Desktop Volume Control */}
            <div className="hidden sm:flex items-center gap-3 px-3.5 py-2 rounded-xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] shadow-sm">
              <button
                onClick={toggleMute}
                className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
              >
                {isMuted || volume === 0 ? (
                  <VolumeX className="w-4 h-4 text-[var(--text-muted)]" />
                ) : (
                  <Volume2 className="w-4 h-4" />
                )}
              </button>
              <input
                type="range"
                min={0}
                max={100}
                value={isMuted ? 0 : volume}
                onChange={(e) => setVolume(Number(e.target.value))}
                className="w-full h-1.5 rounded-lg bg-[var(--border-medium)] appearance-none cursor-pointer outline-none"
              />
              <span className="text-xs font-mono text-[var(--text-muted)] w-7 text-right">
                {isMuted ? '0%' : `${volume}%`}
              </span>
            </div>
          </div>
        </div>

        {/* Right Side: Tab Panel (Lyrics, Queue, Visualizer) */}
        <div
          className={`${
            mobileTab !== 'player' ? 'flex' : 'hidden lg:flex'
          } lg:col-span-7 h-full flex-col rounded-3xl bg-[var(--bg-card)]/90 border border-[var(--border-subtle)] overflow-hidden shadow-sm`}
        >
          {panelTab === 'lyrics' && <SyncedLyrics />}

          {panelTab === 'queue' && (
            <div className="flex flex-col h-full overflow-hidden">
              <div className="flex items-center justify-between p-4 sm:p-5 border-b border-[var(--border-subtle)]">
                <div>
                  <h3 className="font-bold text-base text-[var(--text-primary)]">Up Next</h3>
                  <p className="text-xs text-[var(--text-muted)]">{queue.length} tracks in queue</p>
                </div>
                {queue.length > 1 && (
                  <button
                    onClick={clearQueue}
                    className="flex items-center gap-1.5 px-3 py-1 rounded-lg bg-[var(--bg-surface-elevated)] hover:bg-rose-500/20 text-[var(--text-secondary)] hover:text-rose-500 text-xs font-semibold border border-[var(--border-subtle)] transition cursor-pointer"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    <span>Clear</span>
                  </button>
                )}
              </div>

              <div className="flex-1 overflow-y-auto p-2.5 sm:p-3 space-y-1">
                {queue.map((track, idx) => {
                  const isCurrent = idx === queueIndex;
                  return (
                    <div
                      key={`${track.id}-${idx}`}
                      onClick={() => playTrack(track)}
                      className={`flex items-center justify-between p-2.5 rounded-2xl cursor-pointer transition ${
                        isCurrent ? 'bg-purple-500/15 dark:bg-[#27311d]' : 'hover:bg-[var(--bg-surface-hover)]'
                      }`}
                    >
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        <span className="text-xs font-mono text-[var(--text-muted)] w-4 text-center">
                          {idx + 1}
                        </span>
                        <img
                          src={track.thumbnail}
                          alt={track.title}
                          className="w-10 h-10 rounded-xl object-cover bg-neutral-800 flex-shrink-0"
                        />
                        <div className="min-w-0 flex-1">
                          <p
                            className={`text-xs sm:text-sm font-semibold truncate ${
                              isCurrent ? 'text-purple-600 dark:text-[#dbe7b5]' : 'text-[var(--text-primary)]'
                            }`}
                          >
                            {track.title}
                          </p>
                          <p className="text-[11px] text-[var(--text-muted)] truncate">{track.artist}</p>
                        </div>
                      </div>

                      <div className="flex items-center gap-2">
                        <span className="text-xs font-mono text-[var(--text-muted)]">
                          {formatTime(track.duration)}
                        </span>
                        {queue.length > 1 && (
                          <>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                reorderQueue(idx, idx - 1);
                              }}
                              disabled={idx === 0}
                              className="p-1.5 rounded-lg text-[var(--text-muted)] hover:text-[var(--text-primary)] transition disabled:opacity-30 disabled:pointer-events-none cursor-pointer"
                              title="Move up"
                              aria-label="Move up in queue"
                            >
                              <ChevronUp className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                reorderQueue(idx, idx + 1);
                              }}
                              disabled={idx === queue.length - 1}
                              className="p-1.5 rounded-lg text-[var(--text-muted)] hover:text-[var(--text-primary)] transition disabled:opacity-30 disabled:pointer-events-none cursor-pointer"
                              title="Move down"
                              aria-label="Move down in queue"
                            >
                              <ChevronDown className="w-3.5 h-3.5" />
                            </button>
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                removeFromQueue(idx);
                              }}
                              className="p-1.5 rounded-lg text-[var(--text-muted)] hover:text-rose-500 transition cursor-pointer"
                              title="Remove from queue"
                              aria-label="Remove from queue"
                            >
                              <Trash2 className="w-3.5 h-3.5" />
                            </button>
                          </>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {panelTab === 'visualizer' && <AudioVisualizer />}
        </div>
      </div>
    </div>
  );
};
