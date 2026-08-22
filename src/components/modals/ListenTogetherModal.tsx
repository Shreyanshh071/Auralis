import React, { useState, useEffect } from 'react';
import {
  Users,
  X,
  Copy,
  Check,
  Share2,
  Radio,
  Crown,
  LogOut,
  Sparkles,
  Loader2,
  AlertCircle,
  Headphones,
  Music2,
  Wifi,
  WifiOff,
} from 'lucide-react';
import { useListenTogether } from '../../context/ListenTogetherContext';
import { usePlayer } from '../../context/PlayerContext';
import { useAuth } from '../../context/AuthContext';
import {
  generateInviteUrl,
  normalizeRoomCode,
  isValidRoomCode,
} from '../../lib/listenTogether';

export const ListenTogetherModal: React.FC = () => {
  const {
    isInRoom,
    isHost,
    roomCode,
    roomState,
    members,
    isConnecting,
    syncStatus,
    driftMs,
    error,
    createRoom,
    joinRoom,
    leaveRoom,
    clearError,
    isModalOpen,
    setIsModalOpen,
    inviteCodeToOpen,
    setInviteCodeToOpen,
  } = useListenTogether();

  const { currentTrack, showToast } = usePlayer();
  const { user } = useAuth();

  const [activeTab, setActiveTab] = useState<'join' | 'host'>('join');
  const [codeInput, setCodeInput] = useState<string>('');
  const [displayNameInput, setDisplayNameInput] = useState<string>('');
  const [copiedCode, setCopiedCode] = useState<boolean>(false);
  const [copiedLink, setCopiedLink] = useState<boolean>(false);

  // Auto-populate input when an invite code is opened via URL
  useEffect(() => {
    if (inviteCodeToOpen) {
      setCodeInput(inviteCodeToOpen);
      setActiveTab('join');
    }
  }, [inviteCodeToOpen]);

  // Set default display name from auth user
  useEffect(() => {
    if (user?.displayName && !displayNameInput) {
      setDisplayNameInput(user.displayName);
    }
  }, [user?.displayName]);

  if (!isModalOpen) return null;

  const handleClose = () => {
    clearError();
    setIsModalOpen(false);
    setInviteCodeToOpen(null);
  };

  const handleCreateRoom = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await createRoom(displayNameInput.trim() || undefined);
    } catch {
      // Error handled by context
    }
  };

  const handleJoinRoom = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!codeInput.trim()) return;
    try {
      await joinRoom(codeInput.trim(), displayNameInput.trim() || undefined);
      setInviteCodeToOpen(null);
    } catch {
      // Error handled by context
    }
  };

  const handleCopyCode = async () => {
    if (!roomCode) return;
    try {
      await navigator.clipboard.writeText(roomCode);
      setCopiedCode(true);
      showToast('Room code copied to clipboard!', 'success');
      setTimeout(() => setCopiedCode(false), 2000);
    } catch {
      showToast(roomCode, 'info');
    }
  };

  const handleCopyLink = async () => {
    if (!roomCode) return;
    try {
      const inviteUrl = generateInviteUrl(window.location.origin, roomCode);
      await navigator.clipboard.writeText(inviteUrl);
      setCopiedLink(true);
      showToast('Invite link copied to clipboard!', 'success');
      setTimeout(() => setCopiedLink(false), 2000);
    } catch {
      showToast('Failed to copy link', 'error');
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-md animate-in fade-in">
      <div className="relative w-full max-w-lg rounded-3xl bg-[var(--bg-popover)] border border-[var(--border-medium)] p-6 shadow-2xl space-y-6 text-[var(--text-primary)] max-h-[90vh] overflow-y-auto">
        {/* Header */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="p-2.5 rounded-2xl bg-purple-500/10 text-purple-500 dark:text-purple-400 border border-purple-500/20">
              <Radio className="w-5 h-5" />
            </div>
            <div>
              <h3 className="font-display font-black text-xl text-[var(--text-primary)]">
                Listen Together
              </h3>
              <p className="text-xs text-[var(--text-muted)]">
                {isInRoom
                  ? isHost
                    ? 'You are hosting this session'
                    : 'Listening in real-time with your friends'
                  : 'Sync audio playback in real-time with friends'}
              </p>
            </div>
          </div>
          <button
            onClick={handleClose}
            className="p-1.5 rounded-full hover:bg-[var(--bg-surface-hover)] text-[var(--text-muted)] hover:text-[var(--text-primary)] transition cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Error Alert */}
        {error && (
          <div className="p-3.5 rounded-2xl bg-rose-500/10 border border-rose-500/20 flex items-center gap-3 text-rose-500 dark:text-rose-400 text-xs">
            <AlertCircle className="w-4 h-4 flex-shrink-0" />
            <span className="flex-1">{error}</span>
            <button
              onClick={clearError}
              className="text-[11px] font-bold underline hover:opacity-80 cursor-pointer"
            >
              Dismiss
            </button>
          </div>
        )}

        {/* ACTIVE ROOM VIEW */}
        {isInRoom && roomCode ? (
          <div className="space-y-6">
            {/* Room Code & Share Card */}
            <div className="p-5 rounded-2xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)]">
                  Room Code
                </span>
                <div className="flex items-center gap-1.5">
                  <div
                    className={`w-2 h-2 rounded-full ${
                      syncStatus === 'synced'
                        ? 'bg-emerald-500 animate-pulse'
                        : syncStatus === 'drift-correcting'
                          ? 'bg-amber-500 animate-pulse'
                          : 'bg-rose-500'
                    }`}
                  />
                  <span className="text-[10px] font-medium text-[var(--text-muted)] capitalize">
                    {syncStatus === 'synced'
                      ? 'In Sync'
                      : syncStatus === 'drift-correcting'
                        ? 'Adjusting Sync'
                        : syncStatus}
                  </span>
                </div>
              </div>

              <div className="flex items-center justify-between gap-3 bg-[var(--bg-input)] px-4 py-3 rounded-xl border border-[var(--border-subtle)]">
                <span className="font-mono text-2xl font-black tracking-widest text-[var(--text-primary)]">
                  {roomCode}
                </span>
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleCopyCode}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] border border-[var(--border-subtle)] text-xs font-semibold text-[var(--text-primary)] transition cursor-pointer shadow-sm"
                    title="Copy Code"
                  >
                    {copiedCode ? <Check className="w-3.5 h-3.5 text-emerald-500" /> : <Copy className="w-3.5 h-3.5" />}
                    <span>{copiedCode ? 'Copied' : 'Code'}</span>
                  </button>
                  <button
                    onClick={handleCopyLink}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-500 text-xs font-bold text-white transition cursor-pointer shadow-md"
                    title="Copy Invite Link"
                  >
                    {copiedLink ? <Check className="w-3.5 h-3.5 text-white" /> : <Share2 className="w-3.5 h-3.5" />}
                    <span>{copiedLink ? 'Copied' : 'Invite Link'}</span>
                  </button>
                </div>
              </div>
            </div>

            {/* Now Playing in Room */}
            {(roomState?.currentTrack || currentTrack) && (
              <div className="p-4 rounded-2xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] space-y-2">
                <div className="flex items-center justify-between">
                  <span className="text-[11px] font-bold uppercase tracking-wider text-[var(--text-muted)] flex items-center gap-1.5">
                    <Music2 className="w-3.5 h-3.5 text-purple-400" />
                    Now Playing
                  </span>
                  {isHost ? (
                    <span className="text-[10px] font-semibold text-purple-400 bg-purple-500/10 px-2 py-0.5 rounded-full border border-purple-500/20">
                      Host Controls Playback
                    </span>
                  ) : (
                    <span className="text-[10px] text-[var(--text-muted)] font-mono">
                      {driftMs > 0 ? `~${driftMs}ms offset` : 'synchronized'}
                    </span>
                  )}
                </div>
                <div className="flex items-center gap-3 pt-1">
                  <img
                    src={(roomState?.currentTrack || currentTrack)?.thumbnail}
                    alt=""
                    className="w-12 h-12 rounded-xl object-cover shadow-md flex-shrink-0"
                  />
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-bold text-[var(--text-primary)] truncate">
                      {(roomState?.currentTrack || currentTrack)?.title}
                    </p>
                    <p className="text-xs text-[var(--text-muted)] truncate">
                      {(roomState?.currentTrack || currentTrack)?.artist}
                    </p>
                  </div>
                </div>
              </div>
            )}

            {/* Members Roster */}
            <div className="space-y-3">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-bold uppercase tracking-wider text-[var(--text-muted)] flex items-center gap-1.5">
                  <Users className="w-3.5 h-3.5" />
                  Members ({members.length})
                </span>
                <span className="text-[10px] text-[var(--text-muted)]">Active presence</span>
              </div>

              <div className="space-y-2 max-h-44 overflow-y-auto pr-1">
                {members.map((member) => (
                  <div
                    key={member.id}
                    className="flex items-center justify-between p-2.5 rounded-xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)]"
                  >
                    <div className="flex items-center gap-3 min-w-0">
                      <div
                        className="w-8 h-8 rounded-full flex items-center justify-center text-white font-bold text-xs shadow-sm flex-shrink-0"
                        style={{ backgroundColor: member.avatarColor || '#8b5cf6' }}
                      >
                        {member.name.slice(0, 1).toUpperCase()}
                      </div>
                      <div className="min-w-0">
                        <div className="flex items-center gap-1.5">
                          <span className="text-xs font-bold text-[var(--text-primary)] truncate">
                            {member.name}
                          </span>
                          {member.isHost && (
                            <span title="Host" className="flex items-center">
                              <Crown className="w-3 h-3 text-amber-400 flex-shrink-0" />
                            </span>
                          )}
                          {member.id === user?.uid && (
                            <span className="text-[9px] font-semibold text-[var(--text-muted)] bg-[var(--bg-surface-hover)] px-1.5 py-0.2 rounded">
                              You
                            </span>
                          )}
                        </div>
                        <span className="text-[10px] text-[var(--text-muted)] block">
                          {member.isHost ? 'Room Host' : 'Listener'}
                        </span>
                      </div>
                    </div>

                    <div className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse flex-shrink-0" />
                  </div>
                ))}
              </div>
            </div>

            {/* Leave / Close Room */}
            <div className="pt-2">
              <button
                onClick={leaveRoom}
                className="w-full flex items-center justify-center gap-2 py-3 rounded-2xl bg-rose-500/10 hover:bg-rose-500/20 text-rose-500 dark:text-rose-400 border border-rose-500/20 text-xs font-bold transition cursor-pointer shadow-sm"
              >
                <LogOut className="w-4 h-4" />
                <span>{isHost ? 'End Session for Everyone' : 'Leave Listen Together'}</span>
              </button>
            </div>
          </div>
        ) : (
          /* JOIN / HOST TABS */
          <div className="space-y-5">
            {/* Tabs */}
            <div className="flex p-1 bg-[var(--bg-input)] rounded-2xl border border-[var(--border-subtle)]">
              <button
                type="button"
                onClick={() => {
                  clearError();
                  setActiveTab('join');
                }}
                className={`flex-1 py-2 rounded-xl text-xs font-bold transition cursor-pointer ${
                  activeTab === 'join'
                    ? 'bg-[var(--bg-surface-elevated)] text-[var(--text-primary)] shadow-sm'
                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                }`}
              >
                Join with Code
              </button>
              <button
                type="button"
                onClick={() => {
                  clearError();
                  setActiveTab('host');
                }}
                className={`flex-1 py-2 rounded-xl text-xs font-bold transition cursor-pointer ${
                  activeTab === 'host'
                    ? 'bg-[var(--bg-surface-elevated)] text-[var(--text-primary)] shadow-sm'
                    : 'text-[var(--text-muted)] hover:text-[var(--text-primary)]'
                }`}
              >
                Host a Session
              </button>
            </div>

            {/* Display Name input */}
            <div className="space-y-1.5">
              <label className="text-xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                Your Display Name
              </label>
              <input
                type="text"
                value={displayNameInput}
                onChange={(e) => setDisplayNameInput(e.target.value)}
                placeholder={user?.displayName || 'e.g. Alex'}
                maxLength={30}
                className="w-full px-4 py-2.5 bg-[var(--bg-input)] rounded-2xl border border-[var(--border-subtle)] text-sm text-[var(--text-primary)] placeholder-[var(--text-muted)] focus:border-[var(--border-strong)] focus:outline-none"
              />
            </div>

            {/* JOIN TAB */}
            {activeTab === 'join' && (
              <form onSubmit={handleJoinRoom} className="space-y-4">
                <div className="space-y-1.5">
                  <label className="text-xs font-bold uppercase tracking-wider text-[var(--text-muted)]">
                    6-Character Room Code
                  </label>
                  <input
                    type="text"
                    required
                    value={codeInput}
                    onChange={(e) => setCodeInput(normalizeRoomCode(e.target.value))}
                    placeholder="e.g. K9X2P4"
                    maxLength={6}
                    autoFocus
                    className="w-full px-4 py-3 bg-[var(--bg-input)] rounded-2xl border border-[var(--border-subtle)] text-lg font-mono font-bold tracking-widest text-center uppercase text-[var(--text-primary)] placeholder-[var(--text-muted)] focus:border-purple-500 focus:outline-none"
                  />
                  <p className="text-[11px] text-[var(--text-muted)] text-center">
                    Enter the code shared by the host or click an invite link
                  </p>
                </div>

                <div className="flex items-center justify-end gap-3 pt-2">
                  <button
                    type="button"
                    onClick={handleClose}
                    className="px-5 py-2.5 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-xs font-semibold text-[var(--text-primary)] border border-[var(--border-subtle)] transition cursor-pointer shadow-sm"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={isConnecting || !isValidRoomCode(codeInput)}
                    className="flex items-center gap-2 px-6 py-2.5 rounded-full bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-xs font-bold text-white shadow-lg transition cursor-pointer"
                  >
                    {isConnecting ? (
                      <>
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        <span>Joining...</span>
                      </>
                    ) : (
                      <>
                        <Headphones className="w-3.5 h-3.5" />
                        <span>Join Session</span>
                      </>
                    )}
                  </button>
                </div>
              </form>
            )}

            {/* HOST TAB */}
            {activeTab === 'host' && (
              <form onSubmit={handleCreateRoom} className="space-y-4">
                <div className="p-4 rounded-2xl bg-[var(--bg-surface-elevated)] border border-[var(--border-subtle)] space-y-2">
                  <div className="flex items-center gap-2 text-purple-400 font-bold text-xs">
                    <Sparkles className="w-4 h-4" />
                    <span>Host Controls Playback</span>
                  </div>
                  <p className="text-xs text-[var(--text-muted)] leading-relaxed">
                    Starting a session generates a shareable room code. When you play, pause, seek,
                    or change songs, all listeners in your room will sync with you automatically.
                  </p>
                </div>

                <div className="flex items-center justify-end gap-3 pt-2">
                  <button
                    type="button"
                    onClick={handleClose}
                    className="px-5 py-2.5 rounded-full bg-[var(--bg-surface-elevated)] hover:bg-[var(--bg-surface-hover)] text-xs font-semibold text-[var(--text-primary)] border border-[var(--border-subtle)] transition cursor-pointer shadow-sm"
                  >
                    Cancel
                  </button>
                  <button
                    type="submit"
                    disabled={isConnecting}
                    className="flex items-center gap-2 px-6 py-2.5 rounded-full bg-purple-600 hover:bg-purple-500 disabled:opacity-50 text-xs font-bold text-white shadow-lg transition cursor-pointer"
                  >
                    {isConnecting ? (
                      <>
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        <span>Creating...</span>
                      </>
                    ) : (
                      <>
                        <Radio className="w-3.5 h-3.5" />
                        <span>Create & Host Room</span>
                      </>
                    )}
                  </button>
                </div>
              </form>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
