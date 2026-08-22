import React from 'react';
import {
  Home,
  Compass,
  Library,
  Heart,
  ListMusic,
  Moon,
  Plus,
  Radio,
} from 'lucide-react';
import { usePlayer } from '../../context/PlayerContext';
import { useAuth } from '../../context/AuthContext';

interface SidebarProps {
  activeView: string;
  setActiveView: (view: string) => void;
  openCreatePlaylistModal: () => void;
  /** Opens one specific playlist in the Library. */
  openPlaylist: (playlistId: string) => void;
}

export const Sidebar: React.FC<SidebarProps> = ({
  activeView,
  setActiveView,
  openCreatePlaylistModal,
  openPlaylist,
}) => {
  const { favorites, playlists, sleepTimerRemaining } = usePlayer();
  const { user, isAuthAvailable, isSyncing, authError, lastSyncedAt } = useAuth();

  /**
   * Real sync state. This replaces a previously hardcoded green "Auralis Cloud
   * Ready" dot that was shown regardless of whether Firebase was configured, a
   * user was signed in, or any write had ever succeeded.
   */
  const syncStatus: { dot: string; label: string; pulse: boolean } = !isAuthAvailable
    ? { dot: 'bg-neutral-600', label: 'Cloud sync not configured', pulse: false }
    : authError
      ? { dot: 'bg-amber-500', label: 'Cloud sync problem', pulse: false }
      : !user
        ? { dot: 'bg-neutral-600', label: 'Saved on this device only', pulse: false }
        : isSyncing
          ? { dot: 'bg-sky-500', label: 'Syncing…', pulse: true }
          : lastSyncedAt
            ? { dot: 'bg-emerald-500', label: 'Favorites & playlists synced', pulse: false }
            : { dot: 'bg-neutral-600', label: 'Signed in — not yet synced', pulse: false };

  const mainNav = [
    { id: 'home', label: 'Home', icon: Home },
    { id: 'explore', label: 'Explore', icon: Compass },
    { id: 'library', label: 'Library', icon: Library },
  ];

  return (
    <aside className="hidden md:flex w-56 h-full bg-[#0d0d11] border-r border-white/[0.04] flex-col flex-shrink-0 z-20 select-none">
      {/* Brand Header */}
      <div className="px-5 py-5 pb-3">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-neutral-800 border border-white/[0.08] flex items-center justify-center text-white flex-shrink-0">
            <Radio className="w-4 h-4" />
          </div>
          <div>
            <h1 className="font-display font-bold text-sm text-white tracking-tight leading-none">
              Auralis
            </h1>
            <p className="text-[10px] text-neutral-500 font-medium tracking-wide mt-0.5">
              Music
            </p>
          </div>

        </div>
      </div>

      {/* Main Navigation */}
      <div className="px-2 py-2 space-y-0.5">
        {mainNav.map((item) => {
          const Icon = item.icon;
          const isActive = activeView === item.id;
          return (
            <button
              key={item.id}
              onClick={() => setActiveView(item.id)}
              className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-semibold transition-colors duration-150 ${
                isActive
                  ? 'text-white bg-white/[0.08]'
                  : 'text-neutral-400 hover:text-neutral-200 hover:bg-white/[0.03]'
              }`}
            >
              <Icon className="w-4 h-4" />
              <span>{item.label}</span>
            </button>
          );
        })}
      </div>

      <div className="mx-4 my-2 border-t border-white/[0.04]" />

      {/* Quick Library & Favorites */}
      <div className="px-2 py-1 flex-1 overflow-y-auto space-y-4">
        <div>
          <div className="px-3 mb-1">
            <span className="text-[10px] font-bold uppercase tracking-wider text-neutral-500">
              Your Music
            </span>
          </div>

          <button
            onClick={() => setActiveView('favorites')}
            className={`w-full flex items-center gap-2.5 px-3 py-1.5 rounded-lg text-xs font-medium transition ${
              activeView === 'favorites'
                ? 'text-white bg-white/[0.08]'
                : 'text-neutral-400 hover:text-white hover:bg-white/[0.03]'
            }`}
          >
            <div className="w-5 h-5 rounded bg-gradient-to-br from-rose-500 to-pink-600 flex items-center justify-center text-white shadow-sm flex-shrink-0">
              <Heart className="w-2.5 h-2.5 fill-current" />
            </div>
            <span className="truncate flex-1 text-left">Liked Songs</span>
            <span className="text-[10px] font-mono text-neutral-500">{favorites.length}</span>
          </button>
        </div>

        {/* Playlists */}
        <div>
          <div className="flex items-center justify-between px-3 mb-1">
            <span className="text-[10px] font-bold uppercase tracking-wider text-neutral-500">
              Playlists
            </span>
            <button
              onClick={openCreatePlaylistModal}
              className="p-0.5 hover:bg-white/10 rounded text-neutral-400 hover:text-white transition"
              title="Create Playlist"
            >
              <Plus className="w-3 h-3" />
            </button>
          </div>

          <div className="space-y-0.5">
            {playlists.map((pl) => (
              <button
                key={pl.id}
                onClick={() => openPlaylist(pl.id)}
                title={pl.title}
                className="w-full flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium text-neutral-400 hover:text-neutral-200 hover:bg-white/[0.03] transition truncate text-left"
              >
                <ListMusic className="w-3.5 h-3.5 text-neutral-500 flex-shrink-0" />
                <span className="truncate flex-1">{pl.title}</span>
                <span className="text-[10px] text-neutral-600 font-mono">{pl.tracks.length}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* User Account & Cloud Sync footer */}
      <div className="p-3 border-t border-white/[0.04]">
        {/* Sleep Timer Indicator if active */}
        {sleepTimerRemaining !== null && (
          <div className="mb-2 flex items-center justify-between px-2.5 py-1.5 rounded-lg bg-neutral-900 border border-white/[0.06] text-xs text-neutral-300">
            <div className="flex items-center gap-1.5">
              <Moon className="w-3 h-3 text-neutral-400" />
              <span className="text-[11px]">Sleep Timer</span>
            </div>
            <span className="font-mono text-[11px] font-bold text-neutral-200">
              {Math.floor(sleepTimerRemaining / 60)}:
              {(sleepTimerRemaining % 60).toString().padStart(2, '0')}
            </span>
          </div>
        )}

        <div className="flex items-center gap-2 px-1" title={authError ?? undefined}>
          <div
            className={`w-2 h-2 rounded-full flex-shrink-0 ${syncStatus.dot} ${
              syncStatus.pulse ? 'animate-pulse' : ''
            }`}
          />
          <span className="text-[10px] text-neutral-400 font-medium truncate">
            {syncStatus.label}
          </span>
        </div>
      </div>
    </aside>
  );
};

