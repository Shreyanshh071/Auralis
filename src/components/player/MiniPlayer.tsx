import React, { useState } from 'react';
import { usePlayer } from '../../context/PlayerContext';
import {
  Play,
  Pause,
  SkipBack,
  SkipForward,
  Heart,
  Maximize2,
  ListMusic,
  Volume2,
  VolumeX,
  Shuffle,
  Repeat,
  Repeat1,
  Mic2,
  Radio,
} from 'lucide-react';
import { useListenTogether } from '../../context/ListenTogetherContext';

export const MiniPlayer: React.FC = () => {
  const {
    currentTrack,
    isPlaying,
    currentTime,
    duration,
    togglePlay,
    seekTo,
    nextTrack,
    prevTrack,
    volume,
    setVolume,
    isMuted,
    toggleMute,
    repeatMode,
    toggleRepeat,
    isShuffle,
    toggleShuffle,
    isFavorite,
    toggleFavorite,
    setIsNowPlayingOpen,
    setActiveModalTab,
    isLoadingAudio,
  } = usePlayer();
  const { isInRoom, isHost, roomCode, setIsModalOpen: openListenTogether } = useListenTogether();

  const [isScrubbing, setIsScrubbing] = useState(false);
  const [scrubTime, setScrubTime] = useState(0);

  if (!currentTrack) return null;

  const formatTime = (secs: number) => {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  };

  const displayTime = isScrubbing ? scrubTime : currentTime;
  const progressPercent = duration > 0 ? (displayTime / duration) * 100 : 0;
  const favorite = isFavorite(currentTrack.id);

  const handleFavorite = (e: React.MouseEvent) => {
    e.stopPropagation();
    toggleFavorite(currentTrack);
  };

  const openLyrics = (e: React.MouseEvent) => {
    e.stopPropagation();
    setActiveModalTab('lyrics');
    setIsNowPlayingOpen(true);
  };

  const openQueue = (e: React.MouseEvent) => {
    e.stopPropagation();
    setActiveModalTab('queue');
    setIsNowPlayingOpen(true);
  };

  // SVG Circular progress radius
  const radius = 21;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (progressPercent / 100) * circumference;

  return (
    <div
      onClick={() => {
        setActiveModalTab('player');
        setIsNowPlayingOpen(true);
      }}
      className="fixed bottom-[var(--player-bottom)] left-[max(0.75rem,env(safe-area-inset-left,0px))] right-[max(0.75rem,env(safe-area-inset-right,0px))] md:left-0 md:right-0 z-40 overflow-hidden rounded-full md:rounded-none border border-[var(--m3-outline-variant)] md:border-t md:border-x-0 md:border-b-0 backdrop-blur-2xl px-3.5 sm:px-4 md:px-6 py-2 md:py-2.5 shadow-[var(--shadow-player)] transition-colors duration-300 cursor-pointer select-none text-[var(--text-primary)]"
    >
      {/*
        Glass stack, painted bottom-up: the page shows through the backdrop
        blur, then the artwork provides the ambient colour, then a tonal glaze
        and a translucent Material 3 surface sit over it so the controls stay
        readable against any album cover, then a static specular sheen.

        Every layer is a still image or a flat colour — nothing here animates a
        filter, so the whole surface costs one composite rather than per-frame
        work.
      */}
      <div aria-hidden="true" className="absolute inset-0 pointer-events-none">
        <img
          src={currentTrack.thumbnail}
          alt=""
          className="w-full h-full object-cover scale-125 blur-xl opacity-70 dark:opacity-60 transition-opacity duration-500"
        />
      </div>
      <div
        aria-hidden="true"
        className="absolute inset-0 pointer-events-none bg-[var(--m3-player-tint)] transition-colors duration-500"
      />
      <div
        aria-hidden="true"
        className="absolute inset-0 pointer-events-none bg-[var(--bg-player-pill)] md:bg-[var(--bg-player-bar)]"
      />
      <div
        aria-hidden="true"
        className="m3-gloss absolute inset-0 pointer-events-none rounded-full md:rounded-none"
      />

      <div className="relative max-w-7xl mx-auto flex items-center justify-between gap-3">
        {/* Left: Circular Artwork with Circular Progress Ring */}
        <div className="flex items-center gap-3 min-w-0 flex-1 max-w-sm">
          <div className="relative w-11 h-11 flex-shrink-0 flex items-center justify-center">
            {/* SVG Circular Progress Bar */}
            <svg className="w-11 h-11 -rotate-90 pointer-events-none" viewBox="0 0 46 46">
              <circle
                cx="23"
                cy="23"
                r={radius}
                className="stroke-[var(--m3-outline-variant)] fill-none"
                strokeWidth="2.5"
              />
              <circle
                cx="23"
                cy="23"
                r={radius}
                className="stroke-[var(--m3-primary)] fill-none transition-all duration-150"
                strokeWidth="2.5"
                strokeDasharray={circumference}
                strokeDashoffset={strokeDashoffset}
                strokeLinecap="round"
              />
            </svg>

            {/* Inner Circular Artwork */}
            <div className="absolute inset-1 rounded-full overflow-hidden bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)]">
              <img
                src={currentTrack.thumbnail}
                alt={currentTrack.title}
                className="w-full h-full object-cover aspect-square"
                onError={(e) => {
                  const target = e.currentTarget;
                  if (!target.src.includes('hqdefault')) {
                    target.src = `https://i.ytimg.com/vi/${currentTrack.id}/hqdefault.jpg`;
                  }
                }}
              />
              <div
                onClick={(e) => {
                  e.stopPropagation();
                  togglePlay();
                }}
                className="absolute inset-0 bg-black/40 flex items-center justify-center opacity-0 hover:opacity-100 transition-opacity"
              >
                {isPlaying ? (
                  <Pause className="w-3.5 h-3.5 text-white fill-current" />
                ) : (
                  <Play className="w-3.5 h-3.5 text-white fill-current ml-0.5" />
                )}
              </div>
            </div>
          </div>

          <div className="min-w-0 flex-1">
            <h4 className="text-xs sm:text-sm font-bold text-[var(--text-primary)] truncate">
              {currentTrack.title}
            </h4>
            <div className="flex items-center gap-2 mt-0.5">
              {/* --text-secondary, not --text-muted: this sits on a translucent
                  surface over the blurred artwork, so the effective background
                  is unknown. Muted only clears AA over the plain page. */}
              <p className="text-[11px] text-[var(--text-secondary)] truncate">{currentTrack.artist}</p>
              {isInRoom && roomCode && (
                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    openListenTogether(true);
                  }}
                  className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)] text-[10px] font-bold border border-[var(--m3-outline-variant)] hover:bg-[var(--m3-primary-container-hover)] transition cursor-pointer flex-shrink-0"
                >
                  <Radio className="w-2.5 h-2.5 animate-pulse" />
                  <span>{isHost ? `Host (${roomCode})` : 'Together'}</span>
                </button>
              )}
            </div>
          </div>
        </div>

        {/* Mobile Quick Action Buttons.
            Sized as Material 3 icon buttons (36px boxes) rather than the 28px
            they used to be, which was below a comfortable touch target. They
            still fit inside the pill's 62px height. */}
        <div className="flex md:hidden items-center gap-1.5 flex-shrink-0 text-[var(--text-secondary)]">
          <button
            onClick={(e) => {
              e.stopPropagation();
              togglePlay();
            }}
            className="p-2.5 rounded-full bg-[var(--m3-primary)] text-[var(--m3-on-primary)] active:scale-95 transition"
            title={isPlaying ? 'Pause' : 'Play'}
          >
            {isPlaying ? (
              <Pause className="w-4 h-4 fill-current" />
            ) : (
              <Play className="w-4 h-4 fill-current" />
            )}
          </button>

          <button
            onClick={(e) => {
              e.stopPropagation();
              openLyrics(e);
            }}
            className="p-2.5 rounded-full hover:bg-[var(--m3-primary-08)] active:bg-[var(--m3-primary-12)] hover:text-[var(--text-primary)] transition"
            title="Lyrics"
          >
            <Mic2 className="w-4 h-4" />
          </button>

          <button
            onClick={handleFavorite}
            className="p-2.5 rounded-full hover:bg-[var(--m3-primary-08)] active:bg-[var(--m3-primary-12)] transition"
            title={favorite ? 'Remove from Liked' : 'Add to Liked'}
          >
            <Heart
              className={`w-4 h-4 ${
                favorite ? 'fill-rose-500 text-rose-500' : 'text-[var(--text-muted)]'
              }`}
            />
          </button>
        </div>

        {/* Desktop Center Controls */}
        <div className="hidden md:flex flex-col items-center flex-1 max-w-lg">
          <div className="flex items-center gap-4">
            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleShuffle();
              }}
              className={`p-2 rounded-full transition cursor-pointer ${
                isShuffle
                  ? 'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]'
                  : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--m3-primary-08)]'
              }`}
              title="Shuffle"
            >
              <Shuffle className="w-3.5 h-3.5" />
            </button>

            <button
              onClick={(e) => {
                e.stopPropagation();
                prevTrack();
              }}
              className="p-2 rounded-full hover:bg-[var(--m3-primary-08)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition active:scale-95 cursor-pointer"
              title="Previous"
            >
              <SkipBack className="w-4 h-4 fill-current" />
            </button>

            <button
              onClick={(e) => {
                e.stopPropagation();
                togglePlay();
              }}
              disabled={isLoadingAudio}
              className="p-2.5 rounded-full bg-[var(--m3-primary)] text-[var(--m3-on-primary)] hover:bg-[var(--m3-primary-hover)] hover:scale-105 active:scale-95 transition shadow-sm flex items-center justify-center cursor-pointer"
              title={isPlaying ? 'Pause' : 'Play'}
            >
              {isPlaying ? (
                <Pause className="w-4 h-4 fill-current" />
              ) : (
                <Play className="w-4 h-4 fill-current ml-0.5" />
              )}
            </button>

            <button
              onClick={(e) => {
                e.stopPropagation();
                nextTrack();
              }}
              className="p-2 rounded-full hover:bg-[var(--m3-primary-08)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition active:scale-95 cursor-pointer"
              title="Next"
            >
              <SkipForward className="w-4 h-4 fill-current" />
            </button>

            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleRepeat();
              }}
              className={`p-2 rounded-full transition cursor-pointer ${
                repeatMode !== 'off'
                  ? 'bg-[var(--m3-secondary-container)] text-[var(--m3-on-secondary-container)]'
                  : 'text-[var(--text-muted)] hover:text-[var(--text-primary)] hover:bg-[var(--m3-primary-08)]'
              }`}
              title={`Repeat: ${repeatMode}`}
            >
              {repeatMode === 'one' ? (
                <Repeat1 className="w-3.5 h-3.5" />
              ) : (
                <Repeat className="w-3.5 h-3.5" />
              )}
            </button>
          </div>

          <div
            onClick={(e) => e.stopPropagation()}
            className="w-full flex items-center gap-2 mt-1"
          >
            <span className="text-[10px] font-mono text-[var(--text-secondary)] w-7 text-right">
              {formatTime(displayTime)}
            </span>
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
              className="w-full h-1 rounded-lg bg-[var(--m3-outline-variant)] appearance-none cursor-pointer outline-none"
            />
            <span className="text-[10px] font-mono text-[var(--text-secondary)] w-7">
              {formatTime(duration)}
            </span>
          </div>
        </div>

        {/* Desktop Right Controls */}
        <div className="hidden md:flex items-center gap-2 flex-1 max-w-xs justify-end text-[var(--text-secondary)]">
          <button
            onClick={openLyrics}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[var(--m3-secondary-container)] hover:bg-[var(--m3-primary-container-hover)] text-[var(--m3-on-secondary-container)] text-xs font-semibold transition cursor-pointer"
            title="Lyrics"
          >
            <Mic2 className="w-3.5 h-3.5" />
            <span>Lyrics</span>
          </button>

          <button
            onClick={openQueue}
            className="p-2 rounded-full hover:bg-[var(--m3-primary-08)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
            title="Queue"
          >
            <ListMusic className="w-4 h-4" />
          </button>

          <div
            onClick={(e) => e.stopPropagation()}
            className="hidden lg:flex items-center gap-2"
          >
            <button
              onClick={toggleMute}
              className="p-2 rounded-full text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--m3-primary-08)] transition cursor-pointer"
              title={isMuted || volume === 0 ? 'Unmute' : 'Mute'}
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
              className="w-20 h-1 rounded-lg bg-[var(--m3-outline-variant)] appearance-none cursor-pointer outline-none"
            />
          </div>

          <button
            onClick={() => setIsNowPlayingOpen(true)}
            className="p-2 rounded-full hover:bg-[var(--m3-primary-08)] text-[var(--text-secondary)] hover:text-[var(--text-primary)] transition cursor-pointer"
            title="Expand Fullscreen"
          >
            <Maximize2 className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  );
};
