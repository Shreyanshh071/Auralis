import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { type User, onAuthStateChanged } from 'firebase/auth';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import {
  auth,
  db,
  isFirebaseConfigured,
  firebaseConfigError,
} from '../services/firebase';
import {
  signInWithGoogle as googleSignIn,
  signOutEverywhere,
  isSignInCancellation,
} from '../services/googleSignIn';
import {
  getYouTubeConnectionState,
  setYouTubeConnectionState,
  clearYouTubeConnectionState,
  fetchYouTubeChannelInfo,
  requestYouTubeAccessToken,
  type YouTubeConnectionState,
} from '../services/youtubeSync';
import type { Track, Playlist } from '../types/music';

/** Current shape of the `users/{uid}` document. Bump when the shape changes. */
export const CLOUD_SCHEMA_VERSION = 1;

export interface UserCloudData {
  favorites?: Track[];
  playlists?: Playlist[];
  /** Last write to the document, from any field. Epoch milliseconds. */
  updatedAt?: number;
  /** Last write to `favorites`. Epoch milliseconds. */
  favoritesUpdatedAt?: number;
  /** Last write to `playlists`. Epoch milliseconds. */
  playlistsUpdatedAt?: number;
  schemaVersion?: number;
  /** Legacy field written by earlier builds; read-only compatibility. */
  lastSyncedAt?: number;
}

/**
 * Thrown when a Firestore read failed. Callers MUST treat this differently from
 * "the document does not exist": on a read failure the local state has not been
 * reconciled, so writing local data back would risk overwriting cloud data.
 */
export class CloudReadError extends Error {
  constructor(cause: unknown) {
    super(
      cause instanceof Error
        ? `Could not read cloud data: ${cause.message}`
        : 'Could not read cloud data.'
    );
    this.name = 'CloudReadError';
  }
}

interface AuthContextType {
  user: User | null;
  loading: boolean;
  isSyncing: boolean;
  /** False when Firebase env vars are missing; sign-in is genuinely unavailable. */
  isAuthAvailable: boolean;
  /** Why auth/sync is unavailable, or the last sync failure. Null when healthy. */
  authError: string | null;
  /** Timestamp of the last successful cloud write, or null if none this session. */
  lastSyncedAt: number | null;
  signInWithGoogle: () => Promise<void>;
  logout: () => Promise<void>;
  /**
   * Reads `users/{uid}`.
   * Resolves to the document data, or `null` when the document does not exist.
   * Rejects with `CloudReadError` when the read itself failed.
   */
  fetchCloudData: () => Promise<UserCloudData | null>;
  saveFavoritesToCloud: (favorites: Track[]) => Promise<void>;
  savePlaylistsToCloud: (playlists: Playlist[]) => Promise<void>;
  // YouTube sync
  youtubeState: YouTubeConnectionState;
  youtubeConnecting: boolean;
  /** The access token for the YouTube Data API, if available (in-memory only). */
  youtubeAccessToken: string | null;
  connectYouTube: () => Promise<string>;
  disconnectYouTube: () => void;
}

const AuthContext = createContext<AuthContextType | null>(null);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  // With Firebase unconfigured there is no auth state to wait for.
  const [loading, setLoading] = useState<boolean>(isFirebaseConfigured);
  const [isSyncing, setIsSyncing] = useState<boolean>(false);
  const [authError, setAuthError] = useState<string | null>(firebaseConfigError);
  const [lastSyncedAt, setLastSyncedAt] = useState<number | null>(null);
  // YouTube sync state
  const [youtubeState, setYoutubeState] = useState<YouTubeConnectionState>(() => getYouTubeConnectionState());
  const [youtubeConnecting, setYoutubeConnecting] = useState(false);
  const [youtubeAccessToken, setYoutubeAccessToken] = useState<string | null>(null);

  useEffect(() => {
    if (!auth) return;
    const unsubscribe = onAuthStateChanged(auth, (currentUser) => {
      setUser(currentUser);
      setLoading(false);
      if (!currentUser) setLastSyncedAt(null);
    });
    return () => unsubscribe();
  }, []);

  const signInWithGoogle = useCallback(async () => {
    if (!auth) {
      // Do not pretend sign-in is possible.
      throw new Error(firebaseConfigError ?? 'Firebase is not configured.');
    }
    try {
      await googleSignIn(auth);
      setAuthError(null);
    } catch (error) {
      // Cancellation is a user choice, not a failure — do not log it as an
      // error. It is still re-thrown so the caller skips the success toast.
      if (!isSignInCancellation(error)) {
        console.error('Google Sign-in Error:', error);
      }
      throw error;
    }
  }, []);

  const logout = useCallback(async () => {
    if (!auth) return;
    try {
      await signOutEverywhere(auth);
      setLastSyncedAt(null);
      // Also clear YouTube state on logout
      setYoutubeAccessToken(null);
      clearYouTubeConnectionState();
      setYoutubeState({ connected: false });
    } catch (error) {
      console.error('Sign-out Error:', error);
      throw error;
    }
  }, []);

  /**
   * Connect YouTube account by re-authenticating with the youtube.readonly scope.
   * Returns the access token for the YouTube Data API.
   */
  const connectYouTube = useCallback(async (): Promise<string> => {
    if (!auth) throw new Error('Firebase is not configured.');
    setYoutubeConnecting(true);
    try {
      const accessToken = await requestYouTubeAccessToken(auth);
      setYoutubeAccessToken(accessToken);

      // Fetch channel info to confirm the connection and show the channel name
      try {
        const channelInfo = await fetchYouTubeChannelInfo(accessToken);
        const newState: YouTubeConnectionState = {
          connected: true,
          channelName: channelInfo.name,
        };
        setYoutubeState(newState);
        setYouTubeConnectionState(newState);
      } catch {
        // Channel info fetch failed — still connected but without channel name
        const newState: YouTubeConnectionState = { connected: true };
        setYoutubeState(newState);
        setYouTubeConnectionState(newState);
      }

      return accessToken;
    } catch (error) {
      if (!isSignInCancellation(error)) {
        console.error('YouTube connect error:', error);
      }
      throw error;
    } finally {
      setYoutubeConnecting(false);
    }
  }, []);

  const disconnectYouTube = useCallback(() => {
    setYoutubeAccessToken(null);
    clearYouTubeConnectionState();
    setYoutubeState({ connected: false });
  }, []);

  const fetchCloudData = useCallback(async (): Promise<UserCloudData | null> => {
    if (!auth?.currentUser || !db) return null;
    setIsSyncing(true);
    try {
      const userRef = doc(db, 'users', auth.currentUser.uid);
      const snapshot = await getDoc(userRef);
      setAuthError(null);
      return snapshot.exists() ? (snapshot.data() as UserCloudData) : null;
    } catch (error) {
      console.error('Error fetching cloud data:', error);
      const readError = new CloudReadError(error);
      setAuthError(readError.message);
      // Surfaced as a rejection so the caller does NOT open the write gate.
      throw readError;
    } finally {
      setIsSyncing(false);
    }
  }, []);

  const saveFavoritesToCloud = useCallback(async (favorites: Track[]) => {
    if (!auth?.currentUser || !db) return;
    const now = Date.now();
    try {
      const userRef = doc(db, 'users', auth.currentUser.uid);
      await setDoc(
        userRef,
        {
          favorites,
          favoritesUpdatedAt: now,
          updatedAt: now,
          schemaVersion: CLOUD_SCHEMA_VERSION,
        },
        { merge: true }
      );
      setLastSyncedAt(now);
      setAuthError(null);
    } catch (error) {
      console.error('Error syncing favorites to cloud:', error);
      setAuthError('Favorites could not be saved to the cloud.');
    }
  }, []);

  const savePlaylistsToCloud = useCallback(async (playlists: Playlist[]) => {
    if (!auth?.currentUser || !db) return;
    const now = Date.now();
    try {
      const userRef = doc(db, 'users', auth.currentUser.uid);
      await setDoc(
        userRef,
        {
          playlists,
          playlistsUpdatedAt: now,
          updatedAt: now,
          schemaVersion: CLOUD_SCHEMA_VERSION,
        },
        { merge: true }
      );
      setLastSyncedAt(now);
      setAuthError(null);
    } catch (error) {
      console.error('Error syncing playlists to cloud:', error);
      setAuthError('Playlists could not be saved to the cloud.');
    }
  }, []);

  return (
    <AuthContext.Provider
      value={{
        user,
        loading,
        isSyncing,
        isAuthAvailable: isFirebaseConfigured,
        authError,
        lastSyncedAt,
        signInWithGoogle,
        logout,
        fetchCloudData,
        saveFavoritesToCloud,
        savePlaylistsToCloud,
        youtubeState,
        youtubeConnecting,
        youtubeAccessToken,
        connectYouTube,
        disconnectYouTube,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
