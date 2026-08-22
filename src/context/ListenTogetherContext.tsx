import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useRef,
  useCallback,
  useMemo,
} from 'react';
import type {
  RoomState,
  RoomMember,
  SyncStatus,
  ListenTogetherContextType,
} from '../types/listenTogether';
import { usePlayer } from './PlayerContext';
import { useAuth } from './AuthContext';
import {
  createRoomInFirestore,
  joinRoomInFirestore,
  updateRoomPlaybackState,
  updateRoomPlaybackPosition,
  updateMemberHeartbeat,
  removeMemberFromRoom,
  closeRoomInFirestore,
  subscribeToRoom,
  subscribeToRoomMembers,
  ensureAuthUser,
} from '../services/listenTogether';
import {
  generateRoomCode,
  normalizeRoomCode,
  isValidRoomCode,
  calculateExpectedPlaybackPosition,
  calculateDrift,
  shouldResync,
  isSeekJump,
  filterActiveMembers,
  DEFAULT_DRIFT_THRESHOLD_SECONDS,
} from '../lib/listenTogether';

const ListenTogetherContext = createContext<ListenTogetherContextType | null>(null);

export const ListenTogetherProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const {
    currentTrack,
    isPlaying,
    currentTime,
    playbackRate,
    queue,
    queueIndex,
    playTrack,
    pause,
    resume,
    seekTo,
    showToast,
  } = usePlayer();

  const { user } = useAuth();

  // Room state
  const [isInRoom, setIsInRoom] = useState<boolean>(false);
  const [isHost, setIsHost] = useState<boolean>(false);
  const [roomCode, setRoomCode] = useState<string | null>(null);
  const [roomState, setRoomState] = useState<RoomState | null>(null);
  const [members, setMembers] = useState<RoomMember[]>([]);
  const [isConnecting, setIsConnecting] = useState<boolean>(false);
  const [syncStatus, setSyncStatus] = useState<SyncStatus>('idle');
  const [driftMs, setDriftMs] = useState<number>(0);
  const [error, setError] = useState<string | null>(null);

  // UI state
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);
  const [inviteCodeToOpen, setInviteCodeToOpen] = useState<string | null>(null);

  // References for synchronization & feedback loop guard
  const isApplyingRemoteUpdateRef = useRef<boolean>(false);
  const lastStateVersionRef = useRef<number>(1);
  const localUserIdRef = useRef<string | null>(null);
  const currentTrackIdRef = useRef<string | null>(currentTrack?.id || null);
  const isPlayingRef = useRef<boolean>(isPlaying);
  const currentTimeRef = useRef<number>(currentTime);
  const lastPeriodicSyncTimeRef = useRef<number>(0);
  const isHostRef = useRef<boolean>(isHost);
  const roomCodeRef = useRef<string | null>(roomCode);

  // Seek tracking references on host
  const lastObservedTimeRef = useRef<number>(currentTime);
  const lastObservedTimestampRef = useRef<number>(Date.now());
  const lastTrackIdForSeekRef = useRef<string | null>(currentTrack?.id || null);
  const seekDebounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Keep refs in sync with state for callbacks and timers
  useEffect(() => {
    currentTrackIdRef.current = currentTrack?.id || null;
  }, [currentTrack?.id]);

  useEffect(() => {
    isPlayingRef.current = isPlaying;
  }, [isPlaying]);

  useEffect(() => {
    currentTimeRef.current = currentTime;
  }, [currentTime]);

  useEffect(() => {
    isHostRef.current = isHost;
  }, [isHost]);

  useEffect(() => {
    roomCodeRef.current = roomCode;
  }, [roomCode]);

  const clearError = useCallback(() => {
    setError(null);
  }, []);

  // ---------------------------------------------------------------------------
  // Host Actions & State Dispatcher
  // ---------------------------------------------------------------------------

  /**
   * Host creates a new room with a random 6-character alphanumeric code.
   */
  const createRoom = useCallback(
    async (hostDisplayName?: string): Promise<string> => {
      setIsConnecting(true);
      setError(null);
      try {
        const code = generateRoomCode();
        const authUser = await ensureAuthUser();
        localUserIdRef.current = authUser.uid;

        const { roomId } = await createRoomInFirestore({
          code,
          hostName: hostDisplayName || authUser.displayName || undefined,
          currentTrack,
          queue,
          queueIndex,
          isPlaying,
          playbackPosition: currentTime,
          playbackRate,
        });

        setIsInRoom(true);
        setIsHost(true);
        setRoomCode(roomId);
        lastStateVersionRef.current = 1;
        setSyncStatus('synced');
        showToast(`Room created! Code: ${roomId}`, 'success');
        return roomId;
      } catch (err: any) {
        console.error('[Listen Together] Create room error:', err);
        const msg = err?.message || 'Could not create room.';
        setError(msg);
        showToast(msg, 'error');
        throw err;
      } finally {
        setIsConnecting(false);
      }
    },
    [currentTrack, queue, queueIndex, isPlaying, currentTime, playbackRate, showToast]
  );

  /**
   * Join an existing room with a 6-character code.
   */
  const joinRoom = useCallback(
    async (code: string, memberDisplayName?: string): Promise<void> => {
      setIsConnecting(true);
      setError(null);
      try {
        const normalized = normalizeRoomCode(code);
        if (!isValidRoomCode(normalized)) {
          throw new Error('Please enter a valid 6-character room code.');
        }

        const authUser = await ensureAuthUser();
        localUserIdRef.current = authUser.uid;

        const { roomId, roomState: initialRoom } = await joinRoomInFirestore(
          normalized,
          memberDisplayName || authUser.displayName || undefined
        );

        setIsInRoom(true);
        setIsHost(authUser.uid === initialRoom.hostId);
        setRoomCode(roomId);
        setRoomState(initialRoom);
        lastStateVersionRef.current = initialRoom.stateVersion || 1;

        // Apply initial host state if listener
        if (authUser.uid !== initialRoom.hostId) {
          isApplyingRemoteUpdateRef.current = true;
          if (initialRoom.currentTrack) {
            playTrack(initialRoom.currentTrack, initialRoom.queue || [initialRoom.currentTrack]);
            const expected = calculateExpectedPlaybackPosition({
              isPlaying: initialRoom.isPlaying,
              playbackPosition: initialRoom.playbackPosition || 0,
              playbackRate: initialRoom.playbackRate || 1.0,
              updatedAt: initialRoom.updatedAt || Date.now(),
              now: Date.now(),
              duration: initialRoom.currentTrack.duration,
            });
            setTimeout(() => {
              if (expected > 0.5) seekTo(expected);
              if (!initialRoom.isPlaying) pause();
              isApplyingRemoteUpdateRef.current = false;
            }, 300);
          } else {
            isApplyingRemoteUpdateRef.current = false;
          }
        }

        setSyncStatus('synced');
        showToast(`Joined room ${roomId}`, 'success');
      } catch (err: any) {
        console.error('[Listen Together] Join room error:', err);
        const msg = err?.message || 'Could not join room.';
        setError(msg);
        showToast(msg, 'error');
        throw err;
      } finally {
        setIsConnecting(false);
      }
    },
    [playTrack, seekTo, pause, showToast]
  );

  /**
   * Leave current room (or close if host).
   */
  const leaveRoom = useCallback(async (): Promise<void> => {
    const code = roomCodeRef.current;
    const uid = localUserIdRef.current || user?.uid;

    if (code) {
      if (isHostRef.current) {
        // Host leaves -> close room so all listeners revert to single-user
        await closeRoomInFirestore(code);
      } else if (uid) {
        await removeMemberFromRoom(code, uid);
      }
    }

    setIsInRoom(false);
    setIsHost(false);
    setRoomCode(null);
    setRoomState(null);
    setMembers([]);
    setSyncStatus('idle');
    setDriftMs(0);
    setError(null);
    showToast('Left Listen Together session', 'info');
  }, [user?.uid, showToast]);

  // ---------------------------------------------------------------------------
  // Host Playback Dispatcher (Broadcast changes to Firestore)
  // ---------------------------------------------------------------------------

  // Host: Track Change or Queue Update
  useEffect(() => {
    if (!isInRoom || !isHost || isApplyingRemoteUpdateRef.current || !roomCode) return;

    lastStateVersionRef.current += 1;
    updateRoomPlaybackState(roomCode, {
      currentTrack,
      queue,
      queueIndex,
      isPlaying,
      playbackPosition: currentTime,
      playbackRate,
      stateVersion: lastStateVersionRef.current,
    }).catch((err) => console.warn('[Listen Together] Failed to broadcast track change:', err));
  }, [currentTrack?.id, queueIndex, isHost, isInRoom, roomCode]);

  // Host: Play / Pause toggle
  useEffect(() => {
    if (!isInRoom || !isHost || isApplyingRemoteUpdateRef.current || !roomCode) return;

    lastStateVersionRef.current += 1;
    updateRoomPlaybackState(roomCode, {
      isPlaying,
      playbackPosition: currentTime,
      playbackRate,
      stateVersion: lastStateVersionRef.current,
    }).catch((err) => console.warn('[Listen Together] Failed to broadcast play/pause:', err));
  }, [isPlaying, isHost, isInRoom, roomCode]);

  // Host: Immediate Seek Detection & Broadcast
  useEffect(() => {
    if (!isInRoom || !isHost || isApplyingRemoteUpdateRef.current || !roomCode) {
      lastObservedTimeRef.current = currentTime;
      lastObservedTimestampRef.current = Date.now();
      lastTrackIdForSeekRef.current = currentTrack?.id || null;
      return;
    }

    // If track changed, reset reference without broadcasting seek (track change effect handles it)
    if (lastTrackIdForSeekRef.current !== currentTrack?.id) {
      lastTrackIdForSeekRef.current = currentTrack?.id || null;
      lastObservedTimeRef.current = currentTime;
      lastObservedTimestampRef.current = Date.now();
      return;
    }

    const now = Date.now();
    const elapsedSec = Math.max(0, (now - lastObservedTimestampRef.current) / 1000);

    const jumpDetected = isSeekJump({
      currentTime,
      lastKnownTime: lastObservedTimeRef.current,
      elapsedWallClockSec: elapsedSec,
      isPlaying,
      playbackRate,
      seekThreshold: 1.0,
    });

    lastObservedTimeRef.current = currentTime;
    lastObservedTimestampRef.current = now;

    if (jumpDetected) {
      if (seekDebounceTimerRef.current) {
        clearTimeout(seekDebounceTimerRef.current);
      }
      const seekTime = currentTime;
      const seekTimeCapture = now;

      seekDebounceTimerRef.current = setTimeout(() => {
        lastStateVersionRef.current += 1;
        lastPeriodicSyncTimeRef.current = seekTimeCapture;
        updateRoomPlaybackPosition(
          roomCode,
          seekTime,
          lastStateVersionRef.current,
          seekTimeCapture
        ).catch((err) => console.warn('[Listen Together] Failed to broadcast seek:', err));
      }, 40);
    }
  }, [currentTime, isInRoom, isHost, isPlaying, playbackRate, roomCode, currentTrack?.id]);

  // Host: Periodic lightweight time sync (every 5s while audio is playing)
  useEffect(() => {
    if (!isInRoom || !isHost || !isPlaying || !roomCode) return;

    const interval = setInterval(() => {
      const now = Date.now();
      if (now - lastPeriodicSyncTimeRef.current >= 4500) {
        lastPeriodicSyncTimeRef.current = now;
        lastStateVersionRef.current += 1;
        updateRoomPlaybackPosition(roomCode, currentTimeRef.current, lastStateVersionRef.current, now).catch(
          () => {}
        );
      }
    }, 5000);

    return () => clearInterval(interval);
  }, [isInRoom, isHost, isPlaying, roomCode]);

  // ---------------------------------------------------------------------------
  // Real-time Subscriptions (Room & Members)
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (!isInRoom || !roomCode) return;

    // 1. Subscribe to room document
    const unsubRoom = subscribeToRoom(
      roomCode,
      (remoteState) => {
        if (!remoteState || remoteState.status === 'closed') {
          // Host closed or room deleted
          if (!isHostRef.current) {
            showToast('Host ended the Listen Together session.', 'info');
            setIsInRoom(false);
            setIsHost(false);
            setRoomCode(null);
            setRoomState(null);
            setMembers([]);
            setSyncStatus('idle');
          }
          return;
        }

        setRoomState(remoteState);

        // If listener, process remote host state
        if (!isHostRef.current) {
          // Drop out-of-order state versions
          if (remoteState.stateVersion && remoteState.stateVersion < lastStateVersionRef.current) {
            return;
          }
          if (remoteState.stateVersion) {
            lastStateVersionRef.current = remoteState.stateVersion;
          }

          const now = Date.now();
          const expectedPos = calculateExpectedPlaybackPosition({
            isPlaying: remoteState.isPlaying,
            playbackPosition: remoteState.playbackPosition || 0,
            playbackRate: remoteState.playbackRate || 1.0,
            updatedAt: remoteState.updatedAt || now,
            now,
            duration: remoteState.currentTrack?.duration,
          });

          const localTime = currentTimeRef.current;
          const drift = calculateDrift(localTime, expectedPos);
          setDriftMs(Math.round(drift * 1000));

          // 1. Track change
          if (remoteState.currentTrack && remoteState.currentTrack.id !== currentTrackIdRef.current) {
            isApplyingRemoteUpdateRef.current = true;
            playTrack(remoteState.currentTrack, remoteState.queue || [remoteState.currentTrack]);
            setTimeout(() => {
              if (expectedPos > 0.5) seekTo(expectedPos);
              if (!remoteState.isPlaying) pause();
              lastObservedTimeRef.current = expectedPos;
              lastObservedTimestampRef.current = Date.now();
              isApplyingRemoteUpdateRef.current = false;
              setSyncStatus('synced');
            }, 300);
            return;
          }

          // 2. Play / Pause state synchronization
          if (remoteState.isPlaying !== isPlayingRef.current) {
            isApplyingRemoteUpdateRef.current = true;
            if (remoteState.isPlaying) {
              resume();
            } else {
              pause();
            }
            setTimeout(() => {
              isApplyingRemoteUpdateRef.current = false;
            }, 100);
          }

          // 3. Drift correction (seek if drift > threshold)
          if (drift > DEFAULT_DRIFT_THRESHOLD_SECONDS) {
            setSyncStatus('drift-correcting');
            isApplyingRemoteUpdateRef.current = true;
            seekTo(expectedPos);
            lastObservedTimeRef.current = expectedPos;
            lastObservedTimestampRef.current = Date.now();
            setTimeout(() => {
              isApplyingRemoteUpdateRef.current = false;
              setSyncStatus('synced');
            }, 200);
          } else {
            setSyncStatus('synced');
          }
        }
      },
      (err) => {
        console.error('[Listen Together] Room subscription error:', err);
        setSyncStatus('disconnected');
      }
    );

    // 2. Subscribe to members subcollection
    const unsubMembers = subscribeToRoomMembers(
      roomCode,
      (rawMembers) => {
        const active = filterActiveMembers(rawMembers, Date.now());
        setMembers(active);
      },
      (err) => {
        console.error('[Listen Together] Members error:', err);
      }
    );

    return () => {
      unsubRoom();
      unsubMembers();
    };
  }, [isInRoom, roomCode, playTrack, seekTo, pause, resume, showToast]);

  // ---------------------------------------------------------------------------
  // Presence Heartbeat (Every 10s)
  // ---------------------------------------------------------------------------

  useEffect(() => {
    if (!isInRoom || !roomCode) return;

    const uid = localUserIdRef.current || user?.uid;
    if (!uid) return;

    const heartbeatTimer = setInterval(() => {
      updateMemberHeartbeat(roomCode, uid).catch(() => {});
    }, 10000);

    return () => clearInterval(heartbeatTimer);
  }, [isInRoom, roomCode, user?.uid]);

  // ---------------------------------------------------------------------------
  // Reconnect / Page Visibility Listener (Immediate Drift Check on Focus)
  // ---------------------------------------------------------------------------

  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible' && isInRoom && !isHost && roomState) {
        const now = Date.now();
        const expectedPos = calculateExpectedPlaybackPosition({
          isPlaying: roomState.isPlaying,
          playbackPosition: roomState.playbackPosition || 0,
          playbackRate: roomState.playbackRate || 1.0,
          updatedAt: roomState.updatedAt || now,
          now,
          duration: roomState.currentTrack?.duration,
        });

        const drift = calculateDrift(currentTimeRef.current, expectedPos);
        setDriftMs(Math.round(drift * 1000));

        if (drift > DEFAULT_DRIFT_THRESHOLD_SECONDS) {
          isApplyingRemoteUpdateRef.current = true;
          seekTo(expectedPos);
          if (roomState.isPlaying && !isPlayingRef.current) resume();
          if (!roomState.isPlaying && isPlayingRef.current) pause();
          setTimeout(() => {
            isApplyingRemoteUpdateRef.current = false;
          }, 250);
        }
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, [isInRoom, isHost, roomState, seekTo, resume, pause]);

  // Context value
  const value = useMemo<ListenTogetherContextType>(
    () => ({
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
    }),
    [
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
      inviteCodeToOpen,
    ]
  );

  return (
    <ListenTogetherContext.Provider value={value}>{children}</ListenTogetherContext.Provider>
  );
};

export const useListenTogether = (): ListenTogetherContextType => {
  const context = useContext(ListenTogetherContext);
  if (!context) {
    throw new Error('useListenTogether must be used within a ListenTogetherProvider');
  }
  return context;
};
