import { signInAnonymously, type User } from 'firebase/auth';
import {
  doc,
  getDoc,
  setDoc,
  updateDoc,
  deleteDoc,
  onSnapshot,
  collection,
  type Unsubscribe,
} from 'firebase/firestore';
import { auth, db, isFirebaseConfigured, firebaseConfigError } from './firebase';
import type { Track } from '../types/music';
import type { RoomState, RoomMember, RoomPlaybackUpdate } from '../types/listenTogether';
import {
  normalizeRoomCode,
  isValidRoomCode,
  sanitizeDisplayName,
  getMemberAvatarColor,
} from '../lib/listenTogether';

/**
 * Ensures the client has an active Firebase Auth user.
 * If the user is not signed in with Google, signs them in anonymously
 * so they receive a genuine `request.auth.uid` for Firestore security rules.
 */
export async function ensureAuthUser(): Promise<User> {
  if (!isFirebaseConfigured || !auth) {
    throw new Error(firebaseConfigError ?? 'Firebase is not configured.');
  }

  if (auth.currentUser) {
    return auth.currentUser;
  }

  try {
    const credential = await signInAnonymously(auth);
    return credential.user;
  } catch (err: any) {
    console.error('[Listen Together] Anonymous auth failed:', err);
    if (err?.code === 'auth/admin-restricted-operation' || err?.code === 'auth/operation-not-allowed') {
      throw new Error(
        'Anonymous Authentication is not enabled in Firebase Console. Please enable it under Authentication → Sign-in method.'
      );
    }
    throw new Error(`Authentication failed: ${err?.message || 'Unknown error'}`);
  }
}

/**
 * Creates a new Listen Together room in Firestore with host presence.
 */
export async function createRoomInFirestore(params: {
  code: string;
  hostName?: string;
  currentTrack: Track | null;
  queue: Track[];
  queueIndex: number;
  isPlaying: boolean;
  playbackPosition: number;
  playbackRate: number;
}): Promise<{ roomId: string; hostId: string }> {
  if (!db) {
    throw new Error('Firestore is not initialized.');
  }

  const user = await ensureAuthUser();
  const normalizedCode = normalizeRoomCode(params.code);

  if (!isValidRoomCode(normalizedCode)) {
    throw new Error('Invalid room code format.');
  }

  const roomRef = doc(db, 'rooms', normalizedCode);
  const existingDoc = await getDoc(roomRef);

  if (existingDoc.exists() && existingDoc.data()?.status === 'active') {
    throw new Error('A room with this code is already active. Please generate a new code.');
  }

  const now = Date.now();
  const displayName = sanitizeDisplayName(params.hostName || user.displayName, user.uid);

  const initialRoomState: RoomState = {
    id: normalizedCode,
    code: normalizedCode,
    hostId: user.uid,
    hostName: displayName,
    currentTrack: params.currentTrack,
    queue: params.queue.slice(0, 50),
    queueIndex: Math.max(0, params.queueIndex),
    isPlaying: params.isPlaying,
    playbackPosition: Math.max(0, params.playbackPosition),
    playbackRate: params.playbackRate || 1.0,
    updatedAt: now,
    createdAt: now,
    stateVersion: 1,
    status: 'active',
  };

  // Write room document
  await setDoc(roomRef, initialRoomState);

  // Write host member record
  const memberRef = doc(db, 'rooms', normalizedCode, 'members', user.uid);
  const hostMember: RoomMember = {
    id: user.uid,
    name: displayName,
    isHost: true,
    joinedAt: now,
    lastSeen: now,
    avatarColor: getMemberAvatarColor(user.uid),
  };
  await setDoc(memberRef, hostMember);

  return { roomId: normalizedCode, hostId: user.uid };
}

/**
 * Joins an existing active room and adds the member to the members subcollection.
 */
export async function joinRoomInFirestore(
  code: string,
  memberName?: string
): Promise<{ roomId: string; memberId: string; roomState: RoomState }> {
  if (!db) {
    throw new Error('Firestore is not initialized.');
  }

  const user = await ensureAuthUser();
  const normalizedCode = normalizeRoomCode(code);

  if (!isValidRoomCode(normalizedCode)) {
    throw new Error('Invalid room code. Room codes must be 6 alphanumeric characters.');
  }

  const roomRef = doc(db, 'rooms', normalizedCode);
  const snapshot = await getDoc(roomRef);

  if (!snapshot.exists()) {
    throw new Error('Room not found. Check the code and try again.');
  }

  const roomData = snapshot.data() as RoomState;
  if (roomData.status !== 'active') {
    throw new Error('This room has been closed by the host.');
  }

  const now = Date.now();
  const displayName = sanitizeDisplayName(memberName || user.displayName, user.uid);
  const isHost = user.uid === roomData.hostId;

  const memberRef = doc(db, 'rooms', normalizedCode, 'members', user.uid);
  const member: RoomMember = {
    id: user.uid,
    name: displayName,
    isHost,
    joinedAt: now,
    lastSeen: now,
    avatarColor: getMemberAvatarColor(user.uid),
  };

  await setDoc(memberRef, member);

  return { roomId: normalizedCode, memberId: user.uid, roomState: roomData };
}

/**
 * Updates the room playback state (used by host on track change, pause, resume, seek, or queue change).
 */
export async function updateRoomPlaybackState(
  roomId: string,
  update: RoomPlaybackUpdate
): Promise<void> {
  if (!db) return;
  const roomRef = doc(db, 'rooms', roomId);
  const payload: Record<string, any> = {
    updatedAt: Date.now(),
  };

  if (update.currentTrack !== undefined) payload.currentTrack = update.currentTrack;
  if (update.queue !== undefined) payload.queue = update.queue.slice(0, 50);
  if (update.queueIndex !== undefined) payload.queueIndex = update.queueIndex;
  if (update.isPlaying !== undefined) payload.isPlaying = update.isPlaying;
  if (update.playbackPosition !== undefined) payload.playbackPosition = update.playbackPosition;
  if (update.playbackRate !== undefined) payload.playbackRate = update.playbackRate;
  if (update.stateVersion !== undefined) payload.stateVersion = update.stateVersion;

  await updateDoc(roomRef, payload);
}

/**
 * Lightweight position/seek update from host (transfers only position and timestamp).
 */
export async function updateRoomPlaybackPosition(
  roomId: string,
  playbackPosition: number,
  stateVersion: number,
  updatedAt: number = Date.now()
): Promise<void> {
  if (!db) return;
  const roomRef = doc(db, 'rooms', roomId);
  await updateDoc(roomRef, {
    playbackPosition: Math.max(0, playbackPosition),
    updatedAt,
    stateVersion,
  });
}

/**
 * Updates a member's heartbeat timestamp in the members subcollection.
 */
export async function updateMemberHeartbeat(
  roomId: string,
  memberId: string,
  extra?: Partial<RoomMember>
): Promise<void> {
  if (!db) return;
  const memberRef = doc(db, 'rooms', roomId, 'members', memberId);
  await setDoc(
    memberRef,
    {
      lastSeen: Date.now(),
      ...(extra || {}),
    },
    { merge: true }
  );
}

/**
 * Removes a member from the room's members subcollection.
 */
export async function removeMemberFromRoom(roomId: string, memberId: string): Promise<void> {
  if (!db) return;
  try {
    const memberRef = doc(db, 'rooms', roomId, 'members', memberId);
    await deleteDoc(memberRef);
  } catch (err) {
    console.warn('[Listen Together] Could not delete member record:', err);
  }
}

/**
 * Closes the room (host departure rule: status becomes 'closed').
 */
export async function closeRoomInFirestore(roomId: string): Promise<void> {
  if (!db) return;
  try {
    const roomRef = doc(db, 'rooms', roomId);
    await updateDoc(roomRef, {
      status: 'closed',
      updatedAt: Date.now(),
    });
  } catch (err) {
    console.warn('[Listen Together] Could not close room:', err);
  }
}

/**
 * Subscribes to real-time room state updates.
 */
export function subscribeToRoom(
  roomId: string,
  onUpdate: (state: RoomState | null) => void,
  onError?: (err: Error) => void
): Unsubscribe {
  if (!db) {
    return () => {};
  }

  const roomRef = doc(db, 'rooms', roomId);
  return onSnapshot(
    roomRef,
    (snapshot) => {
      if (!snapshot.exists()) {
        onUpdate(null);
        return;
      }
      onUpdate(snapshot.data() as RoomState);
    },
    (err) => {
      console.error('[Listen Together] Room subscription error:', err);
      onError?.(err);
    }
  );
}

/**
 * Subscribes to the room's members subcollection.
 */
export function subscribeToRoomMembers(
  roomId: string,
  onUpdate: (members: RoomMember[]) => void,
  onError?: (err: Error) => void
): Unsubscribe {
  if (!db) {
    return () => {};
  }

  const membersColRef = collection(db, 'rooms', roomId, 'members');
  return onSnapshot(
    membersColRef,
    (snapshot) => {
      const members: RoomMember[] = [];
      snapshot.forEach((docSnap) => {
        members.push(docSnap.data() as RoomMember);
      });
      onUpdate(members);
    },
    (err) => {
      console.error('[Listen Together] Members subscription error:', err);
      onError?.(err);
    }
  );
}
