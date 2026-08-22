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
  User,
  Plus,
} from 'lucide-react';

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
    dominantColor,
  } = usePlayer();

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
      onClick={() => setIsNowPlayingOpen(true)}
      className="fixed bottom-[64px] md:bottom-0 left-3 right-3 md:left-0 md:right-0 z-40 bg-[#161b12]/95 md:bg-[#0e110c]/95 backdrop-blur-2xl border border-[#2d3623] md:border-t md:border-x-0 md:border-b-0 md:border-white/[0.06] rounded-full md:rounded-none px-3 md:px-6 py-2 md:py-2.5 shadow-2xl transition-all cursor-pointer select-none"
    >
      <div className="max-w-7xl mx-auto flex items-center justify-between gap-3">
        {/* Left: Circular Artwork with Circular Progress Ring */}
        <div className="flex items-center gap-3 min-w-0 flex-1 max-w-sm">
          <div className="relative w-11 h-11 flex-shrink-0 flex items-center justify-center">
            {/* SVG Circular Progress Bar */}
            <svg className="w-11 h-11 -rotate-90 pointer-events-none" viewBox="0 0 46 46">
              <circle
                cx="23"
                cy="23"
                r={radius}
                className="stroke-[#2c3520] fill-none"
                strokeWidth="2.5"
              />
              <circle
                cx="23"
                cy="23"
                r={radius}
                className="stroke-[#dbe7b5] fill-none transition-all duration-150"
                strokeWidth="2.5"
                strokeDasharray={circumference}
                strokeDashoffset={strokeDashoffset}
                strokeLinecap="round"
              />
            </svg>

            {/* Inner Circular Artwork */}
            <div className="absolute inset-1 rounded-full overflow-hidden bg-neutral-900 border border-[#2b331f]">
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
            <h4 className="text-xs sm:text-sm font-bold text-[#f2f6dc] truncate">
              {currentTrack.title}
            </h4>
            <p className="text-[11px] text-[#9ba582] truncate mt-0.5">{currentTrack.artist}</p>
          </div>
        </div>

        {/* Mobile Quick Action Buttons (User, Plus, Heart, Play) */}
        <div className="flex md:hidden items-center gap-2 flex-shrink-0 text-[#a2ad87]">
          <button
            onClick={(e) => {
              e.stopPropagation();
              togglePlay();
            }}
            className="p-1.5 rounded-full hover:bg-white/10 text-white"
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
            className="p-1.5 rounded-full hover:bg-white/10 hover:text-white"
            title="Lyrics"
          >
            <Mic2 className="w-4 h-4" />
          </button>

          <button
            onClick={handleFavorite}
            className="p-1.5 rounded-full hover:bg-white/10 transition"
          >
            <Heart
              className={`w-4 h-4 ${
                favorite ? 'fill-rose-500 text-rose-500' : 'text-[#a2ad87]'
              }`}
            />
          </button>
        </div>

        {/* Desktop Center Controls */}
        <div className="hidden md:flex flex-col items-center flex-1 max-w-lg">
          <div className="flex items-center gap-5">
            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleShuffle();
              }}
              className={`p-1.5 rounded-full transition ${
                isShuffle ? 'text-[#dbe7b5]' : 'text-neutral-400 hover:text-white'
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
              className="p-1.5 rounded-full hover:bg-white/10 text-neutral-300 hover:text-white transition active:scale-95"
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
              className="p-2.5 rounded-full bg-[#dbe7b5] text-[#1a200f] hover:scale-105 active:scale-95 transition shadow flex items-center justify-center"
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
              className="p-1.5 rounded-full hover:bg-white/10 text-neutral-300 hover:text-white transition active:scale-95"
              title="Next"
            >
              <SkipForward className="w-4 h-4 fill-current" />
            </button>

            <button
              onClick={(e) => {
                e.stopPropagation();
                toggleRepeat();
              }}
              className={`p-1.5 rounded-full transition ${
                repeatMode !== 'off' ? 'text-[#dbe7b5]' : 'text-neutral-400 hover:text-white'
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
            <span className="text-[10px] font-mono text-[#8f9a76] w-7 text-right">
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
              className="w-full h-1 rounded-lg bg-[#272f1c] appearance-none cursor-pointer outline-none"
            />
            <span className="text-[10px] font-mono text-[#8f9a76] w-7">
              {formatTime(duration)}
            </span>
          </div>
        </div>

        {/* Desktop Right Controls */}
        <div className="hidden md:flex items-center gap-2 flex-1 max-w-xs justify-end text-[#9ba582]">
          <button
            onClick={openLyrics}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-[#202616] hover:bg-[#2b331f] text-[#dbe7b5] text-xs font-semibold border border-[#2f3821] transition"
            title="Lyrics"
          >
            <Mic2 className="w-3.5 h-3.5" />
            <span>Lyrics</span>
          </button>

          <button
            onClick={openQueue}
            className="p-2 rounded-full hover:bg-white/10 text-[#9ba582] hover:text-white transition"
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
              className="text-[#9ba582] hover:text-white transition"
            >
              {isMuted || volume === 0 ? (
                <VolumeX className="w-4 h-4 text-neutral-500" />
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
              className="w-20 h-1 rounded-lg bg-[#272f1c] appearance-none cursor-pointer outline-none"
            />
          </div>

          <button
            onClick={() => setIsNowPlayingOpen(true)}
            className="p-2 rounded-full hover:bg-white/10 text-[#9ba582] hover:text-white transition"
            title="Expand Fullscreen"
          >
            <Maximize2 className="w-4 h-4" />
          </button>
        </div>
      </div>
    </div>
  );
};
