import type { RoomMember } from '../types/listenTogether';

/** Length of standard room codes */
export const ROOM_CODE_LENGTH = 6;

/**
 * Unambiguous 32-character alphanumeric set.
 * Excludes easily confused characters (0, O, 1, I).
 */
export const ROOM_CODE_CHARSET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** Member presence timeout in milliseconds (35 seconds) */
export const MEMBER_PRESENCE_TIMEOUT_MS = 35000;

/** Default drift tolerance before performing a seek (1.5 seconds) */
export const DEFAULT_DRIFT_THRESHOLD_SECONDS = 1.5;

/** Minimum jump threshold in seconds to detect an explicit seek action */
export const SEEK_DETECTION_THRESHOLD_SECONDS = 1.0;

/**
 * Generates a random 6-character alphanumeric room code.
 */
export function generateRoomCode(length: number = ROOM_CODE_LENGTH): string {
  let code = '';
  const charsetLength = ROOM_CODE_CHARSET.length;
  for (let i = 0; i < length; i++) {
    const randomIndex = Math.floor(Math.random() * charsetLength);
    code += ROOM_CODE_CHARSET[randomIndex];
  }
  return code;
}

/**
 * Normalizes user input room code:
 * Trims whitespace, removes hyphens/spaces, and converts to uppercase.
 */
export function normalizeRoomCode(code: string): string {
  if (!code) return '';
  return code.trim().replace(/[\s-]+/g, '').toUpperCase();
}

/**
 * Validates whether a room code is formatted properly (6 alphanumeric characters).
 */
export function isValidRoomCode(code: string): boolean {
  const normalized = normalizeRoomCode(code);
  if (normalized.length !== ROOM_CODE_LENGTH) return false;
  const validRegex = /^[A-Z0-9]{6}$/;
  return validRegex.test(normalized);
}

/**
 * Calculates the expected current playback position in seconds based on
 * host playback state and elapsed wall-clock time.
 */
export function calculateExpectedPlaybackPosition(params: {
  isPlaying: boolean;
  playbackPosition: number;
  playbackRate?: number;
  updatedAt: number;
  now: number;
  duration?: number;
}): number {
  const {
    isPlaying,
    playbackPosition,
    playbackRate = 1.0,
    updatedAt,
    now,
    duration = Infinity,
  } = params;

  if (!isPlaying) {
    return Math.max(0, Math.min(playbackPosition, duration > 0 ? duration : playbackPosition));
  }

  const elapsedMs = Math.max(0, now - updatedAt);
  const elapsedSeconds = (elapsedMs / 1000) * (playbackRate > 0 ? playbackRate : 1.0);
  const calculatedTime = playbackPosition + elapsedSeconds;

  if (Number.isFinite(duration) && duration > 0) {
    return Math.min(calculatedTime, duration);
  }

  return Math.max(0, calculatedTime);
}

/**
 * Calculates the absolute drift in seconds between local player time and expected room time.
 */
export function calculateDrift(localCurrentTime: number, expectedPosition: number): number {
  return Math.abs(localCurrentTime - expectedPosition);
}

/**
 * Determines whether a listener should perform a resync action (seek or play/pause state change).
 */
export function shouldResync(params: {
  isPlaying: boolean;
  localIsPlaying: boolean;
  localTime: number;
  expectedTime: number;
  driftThreshold?: number;
}): boolean {
  const {
    isPlaying,
    localIsPlaying,
    localTime,
    expectedTime,
    driftThreshold = DEFAULT_DRIFT_THRESHOLD_SECONDS,
  } = params;

  // If play state differs, we must resync
  if (isPlaying !== localIsPlaying) {
    return true;
  }

  // If drift exceeds threshold, we must seek to resync
  const drift = calculateDrift(localTime, expectedTime);
  return drift > driftThreshold;
}

/**
 * Detects whether a sudden change in currentTime constitutes an explicit seek jump
 * (as opposed to normal linear playback progression over time).
 */
export function isSeekJump(params: {
  currentTime: number;
  lastKnownTime: number;
  elapsedWallClockSec: number;
  isPlaying: boolean;
  playbackRate?: number;
  seekThreshold?: number;
}): boolean {
  const {
    currentTime,
    lastKnownTime,
    elapsedWallClockSec,
    isPlaying,
    playbackRate = 1.0,
    seekThreshold = SEEK_DETECTION_THRESHOLD_SECONDS,
  } = params;

  const rate = playbackRate > 0 ? playbackRate : 1.0;
  const expectedAdvance = isPlaying ? Math.max(0, elapsedWallClockSec) * rate : 0;
  const expectedCurrentTime = lastKnownTime + expectedAdvance;
  const delta = Math.abs(currentTime - expectedCurrentTime);

  return delta > seekThreshold;
}

/**
 * Filters active room members, dropping stale members who haven't sent a heartbeat within timeout.
 */
export function filterActiveMembers(
  members: RoomMember[],
  now: number,
  timeoutMs: number = MEMBER_PRESENCE_TIMEOUT_MS
): RoomMember[] {
  if (!Array.isArray(members)) return [];
  return members.filter((member) => {
    if (!member || typeof member.lastSeen !== 'number') return false;
    return now - member.lastSeen <= timeoutMs;
  });
}

/**
 * Sanitizes a user display name for room presence, falling back to a clean guest identifier.
 */
export function sanitizeDisplayName(name?: string | null, fallbackId?: string): string {
  const trimmed = (name ?? '').trim();
  if (trimmed.length > 0) {
    return trimmed.slice(0, 30);
  }
  if (fallbackId) {
    const shortId = fallbackId.slice(0, 4).toUpperCase();
    return `Guest-${shortId}`;
  }
  return 'Guest Listener';
}

/**
 * Generates an invite URL with a `?room=CODE` query parameter.
 */
export function generateInviteUrl(baseUrl: string, roomCode: string): string {
  const normalized = normalizeRoomCode(roomCode);
  try {
    const url = new URL(baseUrl);
    url.searchParams.set('room', normalized);
    return url.toString();
  } catch {
    // If baseUrl is relative or invalid
    const base = baseUrl.split('?')[0].split('#')[0];
    return `${base}?room=${encodeURIComponent(normalized)}`;
  }
}

/**
 * Extracts a room code from a URL search string, full URL, or hash string.
 */
export function extractRoomCodeFromUrl(urlOrSearch: string): string | null {
  if (!urlOrSearch) return null;

  try {
    // Try standard URL parsing
    const url = urlOrSearch.startsWith('http')
      ? new URL(urlOrSearch)
      : new URL(urlOrSearch, 'https://auralis.app');

    const fromQuery = url.searchParams.get('room');
    if (fromQuery && isValidRoomCode(fromQuery)) {
      return normalizeRoomCode(fromQuery);
    }

    // Check hash for #room=CODE or #/room/CODE
    if (url.hash) {
      const match = url.hash.match(/room[=/]([A-Z0-9]{6})/i);
      if (match && match[1] && isValidRoomCode(match[1])) {
        return normalizeRoomCode(match[1]);
      }
    }
  } catch {
    // Fallback regex scan
    const match = urlOrSearch.match(/[?&#]room=([A-Z0-9]{6})/i);
    if (match && match[1] && isValidRoomCode(match[1])) {
      return normalizeRoomCode(match[1]);
    }
  }

  return null;
}

/**
 * Curated avatar colors for room members.
 */
export const AVATAR_COLORS: string[] = [
  '#ec4899', // Pink
  '#8b5cf6', // Violet
  '#3b82f6', // Blue
  '#10b981', // Emerald
  '#f59e0b', // Amber
  '#06b6d4', // Cyan
  '#84cc16', // Lime
  '#f97316', // Orange
];

/**
 * Deterministically picks an avatar color for a member ID.
 */
export function getMemberAvatarColor(memberId: string): string {
  if (!memberId) return AVATAR_COLORS[0];
  let hash = 0;
  for (let i = 0; i < memberId.length; i++) {
    hash = (hash << 5) - hash + memberId.charCodeAt(i);
    hash |= 0;
  }
  const index = Math.abs(hash) % AVATAR_COLORS.length;
  return AVATAR_COLORS[index];
}
