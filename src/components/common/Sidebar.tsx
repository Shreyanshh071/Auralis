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
import { useListenTogether } from '../../context/ListenTogetherContext';

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
  const { isInRoom, roomCode, members, setIsModalOpen } = useListenTogether();

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
    <aside className="hidden md:flex w-56 h-full bg-[var(--bg-sidebar)] border-r border-[var(--border-subtle)] flex-col flex-shrink-0 z-20 select-none transition-colors duration-200">
      {/* Brand Header */}
      <div className="px-5 py-5 pb-3">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-lg bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] flex items-center justify-center text-[var(--text-primary)] flex-shrink-0">
            <Radio className="w-4 h-4 text-purple-500" />
          </div>
          <div>
            <h1 className="font-display font-bold text-sm text-[var(--text-primary)] tracking-tight leading-none">
              Auralis
            </h1>
            <p className="text-[10px] text-[var(--text-muted)] font-medium tracking-wide mt-0.5">
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
              className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-semibold transition-colors duration-150 cursor-pointer ${
                isActive
                  ? 'text-[var(--text-primary)] bg-[var(--bg-surface-hover)] font-bold shadow-sm'
                  : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)]'
              }`}
            >
              <Icon className={`w-4 h-4 ${isActive ? 'text-purple-400' : ''}`} />
              <span>{item.label}</span>
            </button>
          );
        })}

        {/* Listen Together Sidebar Item */}
        <button
          onClick={() => setIsModalOpen(true)}
          className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-xs font-semibold transition-colors duration-150 cursor-pointer ${
            isInRoom
              ? 'text-purple-400 bg-purple-500/10 font-bold border border-purple-500/20'
              : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)]'
          }`}
          title={isInRoom ? `Listen Together Room ${roomCode}` : 'Listen Together'}
        >
          <Radio className={`w-4 h-4 ${isInRoom ? 'text-purple-400 animate-pulse' : ''}`} />
          <span className="flex-1 text-left">Listen Together</span>
          {isInRoom && (
            <span className="text-[10px] font-mono font-bold bg-purple-500/20 text-purple-300 px-1.5 py-0.2 rounded-full">
              {members.length}
            </span>
          )}
        </button>
      </div>

      <div className="mx-4 my-2 border-t border-[var(--border-subtle)]" />

      {/* Quick Library & Favorites */}
      <div className="px-2 py-1 flex-1 overflow-y-auto space-y-4">
        <div>
          <div className="px-3 mb-1">
            <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Your Music
            </span>
          </div>

          <button
            onClick={() => setActiveView('favorites')}
            className={`w-full flex items-center gap-2.5 px-3 py-1.5 rounded-lg text-xs font-medium transition cursor-pointer ${
              activeView === 'favorites'
                ? 'text-[var(--text-primary)] bg-[var(--bg-surface-hover)] font-bold'
                : 'text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)]'
            }`}
          >
            <div className="w-5 h-5 rounded bg-gradient-to-br from-rose-500 to-pink-600 flex items-center justify-center text-white shadow-sm flex-shrink-0">
              <Heart className="w-2.5 h-2.5 fill-current" />
            </div>
            <span className="truncate flex-1 text-left">Liked Songs</span>
            <span className="text-[10px] font-mono text-[var(--text-muted)]">{favorites.length}</span>
          </button>
        </div>

        {/* Playlists */}
        <div>
          <div className="flex items-center justify-between px-3 mb-1">
            <span className="text-[10px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
              Playlists
            </span>
            <button
              onClick={openCreatePlaylistModal}
              className="p-0.5 hover:bg-[var(--bg-surface-hover)] rounded text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
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
                className="w-full flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-medium text-[var(--text-secondary)] hover:text-[var(--text-primary)] hover:bg-[var(--bg-surface-hover)] transition truncate text-left cursor-pointer"
              >
                <ListMusic className="w-3.5 h-3.5 text-[var(--text-muted)] flex-shrink-0" />
                <span className="truncate flex-1">{pl.title}</span>
                <span className="text-[10px] text-[var(--text-subtle)] font-mono">{pl.tracks.length}</span>
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* User Account & Cloud Sync footer */}
      <div className="p-3 border-t border-[var(--border-subtle)]">
        {/* Sleep Timer Indicator if active */}
        {sleepTimerRemaining !== null && (
          <div className="mb-2 flex items-center justify-between px-2.5 py-1.5 rounded-lg bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] text-xs text-[var(--text-primary)]">
            <div className="flex items-center gap-1.5">
              <Moon className="w-3 h-3 text-[var(--text-muted)]" />
              <span className="text-[11px]">Sleep Timer</span>
            </div>
            <span className="font-mono text-[11px] font-bold text-[var(--text-primary)]">
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
          <span className="text-[10px] text-[var(--text-muted)] font-medium truncate">
            {syncStatus.label}
          </span>
        </div>
      </div>
    </aside>
  );
};

