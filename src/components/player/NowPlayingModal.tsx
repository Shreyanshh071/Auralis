import React, { useState } from 'react';
import { usePlayer, PLAYBACK_RATES } from '../../context/PlayerContext';
import { useListenTogether } from '../../context/ListenTogetherContext';
import { SyncedLyrics } from '../lyrics/SyncedLyrics';
import { AudioVisualizer } from '../visualizer/AudioVisualizer';
import { isLetterboxedThumbnail } from '../../services/artwork';
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
    sleepTimerRemaining,
    setSleepTimer,
  } = usePlayer();
  const { isInRoom, isHost, roomCode, members, setIsModalOpen: openListenTogether } = useListenTogether();

  const [showSleepModal, setShowSleepModal] = useState(false);
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [scrubTime, setScrubTime] = useState(0);
  const [mobileTab, setMobileTab] = useState<'player' | 'lyrics' | 'queue' | 'visualizer'>('player');

  // Synchronize mobileTab whenever activeModalTab is set externally (e.g. from MiniPlayer body / Lyrics / Queue buttons)
  React.useEffect(() => {
    if (activeModalTab === 'player') {
      setMobileTab('player');
    } else if (activeModalTab === 'lyrics' || activeModalTab === 'queue' || activeModalTab === 'visualizer') {
      setMobileTab(activeModalTab);
    }
  }, [activeModalTab, isNowPlayingOpen]);

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
    mobileTab !== 'player' ? mobileTab :
    (activeModalTab !== 'player' ? activeModalTab : 'lyrics');

  const handleSelectMobileTab = (tab: 'player' | 'lyrics' | 'queue' | 'visualizer') => {
    setMobileTab(tab);
    if (tab !== 'player') {
      setActiveModalTab(tab);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex flex-col h-[100dvh] pt-[env(safe-area-inset-top,0px)] pb-[env(safe-area-inset-bottom,0px)] pl-[env(safe-area-inset-left,0px)] pr-[env(safe-area-inset-right,0px)] bg-[var(--bg-base)] text-[var(--text-primary)] overflow-hidden select-none animate-in fade-in duration-200">
      {/* Music-Reactive Atmospheric Background from Track Artwork */}
      <div className="absolute inset-0 pointer-events-none overflow-hidden select-none z-0">
        {/* Layer 1: High-saturation blurred artwork bloom — crossfades on track change */}
        <div className="absolute inset-[-20%] opacity-80 dark:opacity-75 transition-opacity duration-1000 ease-out">
          <img
            key={currentTrack.thumbnail || currentTrack.id}
            src={currentTrack.thumbnail}
            alt=""
            className="w-full h-full object-cover scale-150 filter blur-[70px] sm:blur-[90px] saturate-[2.6] animate-in fade-in duration-1000"
            onError={(e) => {
              const target = e.currentTarget;
              if (!target.src.includes('hqdefault')) {
                target.src = `https://i.ytimg.com/vi/${currentTrack.id}/hqdefault.jpg`;
              }
            }}
          />
        </div>

        {/* Layer 2: Dynamic tonal radiance gradient matching the album's extracted palette */}
        <div
          aria-hidden="true"
          className="absolute inset-0 bg-gradient-to-b from-[var(--m3-np-tint-a)] via-[var(--m3-np-tint-b)] to-transparent opacity-85 transition-colors duration-700 ease-out"
        />

        {/* Layer 3: Dynamic radial glow centered on the upper artwork area */}
        <div
          aria-hidden="true"
          className="absolute top-0 left-0 right-0 h-[65%] bg-[radial-gradient(circle_at_50%_35%,var(--m3-np-tint-a)_0%,transparent_75%)] opacity-70 transition-colors duration-700 ease-out"
        />

        {/* Layer 4: Vignette scrim — transparent/light at top/center so colors radiate boldly, smooth dark gradient at bottom for controls contrast */}
        <div className="absolute inset-0 bg-gradient-to-b from-black/15 via-black/30 via-45% to-black/85 transition-colors duration-500" />
      </div>

      {/* Top Header Bar (Clean, Centered, Minimalist like Photo 1) */}
      <div className="relative z-10 flex items-center justify-between gap-3 px-4 sm:px-8 py-3 sm:py-4 border-b border-white/10 dark:border-white/10">
        <button
          onClick={() => {
            if (mobileTab !== 'player') {
              setMobileTab('player');
            } else {
              setIsNowPlayingOpen(false);
            }
          }}
          className="p-2 rounded-full hover:bg-white/10 text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer flex-shrink-0"
          title={mobileTab !== 'player' ? 'Back to player' : 'Minimize player'}
        >
          <ChevronDown className="w-5 h-5 sm:w-6 sm:h-6" />
        </button>

        {/* Center: Clean Now Playing Title & Artist */}
        <div className="flex flex-col items-center text-center min-w-0 flex-1 px-2">
          <span className="text-[10px] sm:text-[11px] font-bold uppercase tracking-widest text-[var(--text-muted)]">
            Now Playing
          </span>
          <span className="text-xs sm:text-sm font-semibold text-[var(--text-primary)] truncate max-w-[200px] sm:max-w-xs">
            {currentTrack.artist || 'Auralis'}
          </span>
        </div>

        {/* Right Tools: Listen Together & Balance spacer */}
        <div className="flex items-center gap-1 sm:gap-2 flex-shrink-0 min-w-[36px] sm:min-w-[40px] justify-end">
          {isInRoom && (
            <button
              onClick={() => openListenTogether(true)}
              className="p-2 rounded-full hover:bg-white/10 text-[var(--m3-primary)] transition cursor-pointer"
              title={`Listen Together (${members.length} members)`}
            >
              <Radio className="w-4 h-4 sm:w-5 sm:h-5 animate-pulse" />
            </button>
          )}
        </div>
      </div>

      {/* Sleep Timer Popover */}
      {showSleepModal && (
        <>
          <div
            className="fixed inset-0 z-20"
            onClick={() => setShowSleepModal(false)}
          />
          <div className="absolute bottom-20 left-4 sm:left-8 z-30 w-60 p-4 rounded-2xl bg-[var(--bg-popover)] border border-[var(--border-medium)] shadow-2xl space-y-3 backdrop-blur-xl">
            <div className="flex items-center justify-between pb-2 border-b border-[var(--border-subtle)]">
              <span className="text-xs font-bold uppercase tracking-wider text-[var(--m3-primary)]">
                Sleep Timer
              </span>
              {sleepTimerRemaining !== null && (
                <button
                  onClick={() => {
                    setSleepTimer(null);
                    setShowSleepModal(false);
                  }}
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
        </>
      )}      {/* Main Content Area */}
      <div className="relative z-10 flex-1 grid grid-cols-1 lg:grid-cols-12 gap-6 lg:gap-8 px-5 sm:px-8 lg:px-12 pt-2 pb-4 overflow-hidden max-w-7xl mx-auto w-full">
        {/* Left Side: Artwork & Controls */}
        <div
          className={`${
            mobileTab === 'player' ? 'flex' : 'hidden lg:flex'
          } lg:col-span-5 flex-col justify-between max-w-sm sm:max-w-md mx-auto w-full h-full`}
        >
          {/* Large Square Cover Artwork (Matching Reference) */}
          <div className="flex-1 flex items-center justify-center min-h-0 py-1 sm:py-2">
            <div className="relative group w-full max-w-[320px] sm:max-w-[380px] aspect-square rounded-3xl overflow-hidden shadow-[0_12px_48px_rgba(0,0,0,0.45)] border border-white/10 bg-[var(--bg-surface-elevated)] ring-1 ring-black/10">
              <img
                src={currentTrack.thumbnail}
                alt={currentTrack.title}
                className={`w-full h-full object-cover aspect-square transition-transform duration-700 ${
                  isLetterboxedThumbnail(currentTrack.thumbnail) ? 'scale-[1.35]' : 'scale-[1.02]'
                }`}
                onError={(e) => {
                  const target = e.currentTarget;
                  if (!target.src.includes('hqdefault')) {
                    target.src = `https://i.ytimg.com/vi/${currentTrack.id}/hqdefault.jpg`;
                  }
                }}
              />
            </div>
          </div>

          {/* Details & Playback Controls Block (Moved up with tighter, cleaner spacing) */}
          <div className="space-y-3.5 sm:space-y-4 w-full -mt-2 sm:-mt-3 pb-1">
            {/* Title, Artist & Solid White Action Buttons (Matching Reference) */}
            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0 flex-1">
                <h2 className="font-display font-bold text-xl sm:text-2xl text-white truncate leading-tight tracking-tight">
                  {currentTrack.title}
                </h2>
                <p className="text-sm sm:text-base text-white/70 truncate mt-1 font-medium">
                  {currentTrack.artist}
                </p>
              </div>

              {/* Right Side Action Squircles */}
              <div className="flex items-center gap-2 flex-shrink-0">
                <AddToPlaylistButton
                  track={currentTrack}
                  className="w-11 h-11 sm:w-12 sm:h-12 rounded-2xl bg-white text-black hover:bg-white/90 transition flex items-center justify-center shadow-md cursor-pointer"
                  iconClassName="w-5 h-5 text-black"
                />

                <button
                  onClick={() => toggleFavorite(currentTrack)}
                  className="w-11 h-11 sm:w-12 sm:h-12 rounded-2xl bg-white text-black hover:bg-white/90 transition flex items-center justify-center shadow-md cursor-pointer"
                  title={favorite ? 'Remove from Liked' : 'Add to Liked'}
                >
                  <Heart
                    className={`w-5 h-5 ${
                      favorite ? 'fill-rose-500 text-rose-500' : 'text-black fill-black'
                    }`}
                  />
                </button>
              </div>
            </div>

            {/* Scrubber Progress Bar */}
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
                  className="w-full h-1.5 rounded-lg bg-white/20 appearance-none cursor-pointer outline-none accent-white"
                />
              </div>

              <div className="flex justify-between text-xs font-mono text-white/70">
                <span>{formatTime(displayTime)}</span>
                <span>{formatTime(duration)}</span>
              </div>
            </div>

            {/* Playback Controls Row: Circular Prev/Next + Taller Wide White Pill Play */}
            <div className="flex items-center justify-between gap-3 sm:gap-4 pt-1">
              <button
                onClick={prevTrack}
                className="w-14 h-14 sm:w-[64px] sm:h-[64px] rounded-full bg-white/10 hover:bg-white/20 text-white transition flex items-center justify-center cursor-pointer border border-white/10 active:scale-95"
                title="Previous"
              >
                <SkipBack className="w-6 h-6 fill-current" />
              </button>

              <button
                onClick={togglePlay}
                className="flex-1 h-16 sm:h-[72px] rounded-full bg-white text-black hover:bg-white/90 hover:scale-[1.02] active:scale-95 transition flex items-center justify-center gap-2.5 shadow-[0_6px_28px_rgba(0,0,0,0.4)] cursor-pointer"
                title={isPlaying ? 'Pause' : 'Play'}
              >
                {isPlaying ? (
                  <Pause className="w-6 h-6 sm:w-7 sm:h-7 fill-current" />
                ) : (
                  <Play className="w-6 h-6 sm:w-7 sm:h-7 fill-current ml-0.5" />
                )}
                <span className="text-base sm:text-lg font-bold tracking-wide">{isPlaying ? 'Pause' : 'Play'}</span>
              </button>

              <button
                onClick={nextTrack}
                className="w-14 h-14 sm:w-[64px] sm:h-[64px] rounded-full bg-white/10 hover:bg-white/20 text-white transition flex items-center justify-center cursor-pointer border border-white/10 active:scale-95"
                title="Next"
              >
                <SkipForward className="w-6 h-6 fill-current" />
              </button>
            </div>

            {/* Desktop Volume Control */}
            <div className="hidden sm:flex items-center gap-3 px-3.5 py-2 rounded-xl bg-white/5 border border-white/10 shadow-sm">
              <button
                onClick={toggleMute}
                className="text-white/80 hover:text-white transition cursor-pointer"
              >
                {isMuted || volume === 0 ? (
                  <VolumeX className="w-4 h-4 text-white/50" />
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
                className="w-full h-1.5 rounded-lg bg-white/20 appearance-none cursor-pointer outline-none accent-white"
              />
              <span className="text-xs font-mono text-white/70 w-7 text-right">
                {isMuted ? '0%' : `${volume}%`}
              </span>
            </div>
          </div>

          {/* Bottom Corner Toolbar (Matching Reference Photo) */}
          <div className="flex items-center justify-between pt-2 pb-2">
            {/* Left Corner Group: Lyrics, Sleep, Shuffle, Repeat */}
            <div className="flex items-center gap-1 p-1 bg-white/10 backdrop-blur-md rounded-2xl border border-white/10">
              {/* 1. Lyrics */}
              <button
                onClick={() => handleSelectMobileTab(mobileTab === 'lyrics' ? 'player' : 'lyrics')}
                className={`p-2.5 rounded-xl transition cursor-pointer ${
                  mobileTab === 'lyrics' || (activeModalTab === 'lyrics' && mobileTab === 'player')
                    ? 'bg-white/25 text-white font-bold shadow-sm'
                    : 'text-white/70 hover:text-white hover:bg-white/10'
                }`}
                title="Lyrics"
              >
                <Mic2 className="w-5 h-5" />
              </button>

              {/* 2. Sleep Timer */}
              <button
                onClick={() => setShowSleepModal(!showSleepModal)}
                className={`p-2.5 rounded-xl transition cursor-pointer relative ${
                  sleepTimerRemaining !== null
                    ? 'bg-white/25 text-white font-bold shadow-sm'
                    : 'text-white/70 hover:text-white hover:bg-white/10'
                }`}
                title="Sleep Timer"
              >
                <Moon className="w-5 h-5" />
                {sleepTimerRemaining !== null && (
                  <span className="absolute top-1.5 right-1.5 w-1.5 h-1.5 rounded-full bg-white animate-pulse" />
                )}
              </button>

              {/* 3. Shuffle */}
              <button
                onClick={toggleShuffle}
                className={`p-2.5 rounded-xl transition cursor-pointer ${
                  isShuffle
                    ? 'bg-white/25 text-white font-bold shadow-sm'
                    : 'text-white/70 hover:text-white hover:bg-white/10'
                }`}
                title={`Shuffle: ${isShuffle ? 'On' : 'Off'}`}
              >
                <Shuffle className="w-5 h-5" />
              </button>

              {/* 4. Repeat */}
              <button
                onClick={toggleRepeat}
                className={`p-2.5 rounded-xl transition cursor-pointer ${
                  repeatMode !== 'off'
                    ? 'bg-white/25 text-white font-bold shadow-sm'
                    : 'text-white/70 hover:text-white hover:bg-white/10'
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

            {/* Right Corner Button: Circular White Button (Matching Reference Photo) */}
            <button
              onClick={() => handleSelectMobileTab(mobileTab === 'queue' ? 'player' : 'queue')}
              className={`w-12 h-12 rounded-full transition cursor-pointer shadow-md flex items-center justify-center active:scale-95 ${
                mobileTab === 'queue' || (activeModalTab === 'queue' && mobileTab === 'player')
                  ? 'bg-white text-black ring-2 ring-white/50'
                  : 'bg-white text-black hover:bg-white/90'
              }`}
              title={`Queue (${queue.length})`}
            >
              <ListMusic className="w-5 h-5" />
            </button>
          </div>
        </div>

        {/* Right Side: Tab Panel (Lyrics, Queue) */}
        <div
          className={`${
            mobileTab !== 'player' ? 'flex' : 'hidden lg:flex'
          } lg:col-span-7 h-full flex-col ${
            panelTab === 'lyrics'
              ? 'bg-transparent border-0 shadow-none'
              : 'rounded-3xl bg-black/40 backdrop-blur-2xl border border-white/10 shadow-sm'
          } overflow-hidden`}
        >
          {/* Panel Tab Selector (Subtle transparent styling) */}
          <div className="flex items-center justify-between gap-2 px-3 sm:px-4 py-2 sm:py-2.5 z-20">
            <div className="flex items-center gap-1 bg-white/10 backdrop-blur-md p-1 rounded-full border border-white/10 flex-1 sm:flex-initial overflow-x-auto">
              <button
                onClick={() => {
                  setActiveModalTab('lyrics');
                  if (mobileTab !== 'player') setMobileTab('lyrics');
                }}
                className={`flex-1 sm:flex-initial flex items-center justify-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap transition cursor-pointer ${
                  panelTab === 'lyrics'
                    ? 'bg-white text-black font-bold shadow-md'
                    : 'text-white/70 hover:text-white'
                }`}
              >
                <Mic2 className="w-3.5 h-3.5" />
                <span>Lyrics</span>
              </button>
              <button
                onClick={() => {
                  setActiveModalTab('queue');
                  if (mobileTab !== 'player') setMobileTab('queue');
                }}
                className={`flex-1 sm:flex-initial flex items-center justify-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs font-semibold whitespace-nowrap transition cursor-pointer ${
                  panelTab === 'queue'
                    ? 'bg-white text-black font-bold shadow-md'
                    : 'text-white/70 hover:text-white'
                }`}
              >
                <ListMusic className="w-3.5 h-3.5" />
                <span>Queue{queue.length > 0 ? ` (${queue.length})` : ''}</span>
              </button>
            </div>

            {mobileTab !== 'player' && (
              <button
                onClick={() => setMobileTab('player')}
                className="lg:hidden flex items-center gap-1.5 px-3.5 py-1.5 rounded-full bg-white text-black text-xs font-bold shadow-md hover:bg-white/90 transition cursor-pointer whitespace-nowrap flex-shrink-0"
              >
                <span>Player</span>
              </button>
            )}
          </div>
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
                        isCurrent ? 'bg-[var(--m3-secondary-container)]' : 'hover:bg-[var(--bg-surface-hover)]'
                      }`}
                    >
                      <div className="flex items-center gap-3 min-w-0 flex-1">
                        <span className="text-xs font-mono text-[var(--text-muted)] w-4 text-center">
                          {idx + 1}
                        </span>
                        <div className="w-10 h-10 rounded-xl overflow-hidden bg-neutral-800 flex-shrink-0">
                          <img
                            src={track.thumbnail}
                            alt={track.title}
                            className={`w-full h-full object-cover aspect-square ${
                              isLetterboxedThumbnail(track.thumbnail) ? 'scale-[1.35]' : 'scale-100'
                            }`}
                          />
                        </div>
                        <div className="min-w-0 flex-1">
                          <p
                            className={`text-xs sm:text-sm font-semibold truncate ${
                              isCurrent ? 'text-[var(--m3-primary)]' : 'text-[var(--text-primary)]'
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
