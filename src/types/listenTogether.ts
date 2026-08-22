import type { Track } from './music';

export type RoomStatus = 'active' | 'closed';

export type SyncStatus = 'synced' | 'buffering' | 'drift-correcting' | 'disconnected' | 'idle';

export interface RoomMember {
  id: string; // Firebase Auth UID
  name: string;
  isHost: boolean;
  joinedAt: number; // epoch ms
  lastSeen: number; // epoch ms heartbeat
  avatarColor?: string;
}

export interface RoomState {
  id: string; // 6-character alphanumeric room code
  code: string;
  hostId: string;
  hostName: string;
  currentTrack: Track | null;
  queue: Track[];
  queueIndex: number;
  isPlaying: boolean;
  playbackPosition: number; // seconds
  playbackRate: number;
  updatedAt: number; // epoch ms
  createdAt: number; // epoch ms
  stateVersion: number;
  status: RoomStatus;
}

export interface RoomPlaybackUpdate {
  currentTrack?: Track | null;
  queue?: Track[];
  queueIndex?: number;
  isPlaying?: boolean;
  playbackPosition?: number;
  playbackRate?: number;
  updatedAt?: number;
  stateVersion?: number;
}

export interface ListenTogetherContextType {
  // Room state
  isInRoom: boolean;
  isHost: boolean;
  roomCode: string | null;
  roomState: RoomState | null;
  members: RoomMember[];
  isConnecting: boolean;
  syncStatus: SyncStatus;
  driftMs: number;
  error: string | null;

  // Actions
  createRoom: (hostDisplayName?: string) => Promise<string>;
  joinRoom: (code: string, memberDisplayName?: string) => Promise<void>;
  leaveRoom: () => Promise<void>;
  clearError: () => void;

  // UI state
  isModalOpen: boolean;
  setIsModalOpen: (open: boolean) => void;
  inviteCodeToOpen: string | null;
  setInviteCodeToOpen: (code: string | null) => void;
}
