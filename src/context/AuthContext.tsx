import React, { createContext, useContext, useState, useEffect } from 'react';
import {
  type User,
  signInWithPopup,
  signOut as firebaseSignOut,
  onAuthStateChanged
} from 'firebase/auth';
import { doc, getDoc, setDoc } from 'firebase/firestore';
import { auth, googleProvider, db } from '../services/firebase';
import type { Track, Playlist } from '../types/music';

interface UserCloudData {
  favorites?: Track[];
  playlists?: Playlist[];
  lastSyncedAt?: number;
}

interface AuthContextType {
  user: User | null;
  loading: boolean;
  isSyncing: boolean;
  signInWithGoogle: () => Promise<void>;
  logout: () => Promise<void>;
  fetchCloudData: () => Promise<UserCloudData | null>;
  saveFavoritesToCloud: (favorites: Track[]) => Promise<void>;
  savePlaylistsToCloud: (playlists: Playlist[]) => Promise<void>;
}

const AuthContext = createContext<AuthContextType | null>(null);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [isSyncing, setIsSyncing] = useState<boolean>(false);

  useEffect(() => {
    const unsubscribe = onAuthStateChanged(auth, (currentUser) => {
      setUser(currentUser);
      setLoading(false);
    });
    return () => unsubscribe();
  }, []);

  const signInWithGoogle = async () => {
    try {
      await signInWithPopup(auth, googleProvider);
    } catch (error: any) {
      console.error('Google Sign-in Error:', error);
      throw error;
    }
  };

  const logout = async () => {
    try {
      await firebaseSignOut(auth);
    } catch (error: any) {
      console.error('Sign-out Error:', error);
      throw error;
    }
  };

  const fetchCloudData = async (): Promise<UserCloudData | null> => {
    if (!auth.currentUser) return null;
    setIsSyncing(true);
    try {
      const userRef = doc(db, 'users', auth.currentUser.uid);
      const snapshot = await getDoc(userRef);
      if (snapshot.exists()) {
        return snapshot.data() as UserCloudData;
      }
      return null;
    } catch (error) {
      console.error('Error fetching cloud data:', error);
      return null;
    } finally {
      setIsSyncing(false);
    }
  };

  const saveFavoritesToCloud = async (favorites: Track[]) => {
    if (!auth.currentUser) return;
    try {
      const userRef = doc(db, 'users', auth.currentUser.uid);
      await setDoc(
        userRef,
        {
          favorites,
          lastSyncedAt: Date.now(),
        },
        { merge: true }
      );
    } catch (error) {
      console.error('Error syncing favorites to cloud:', error);
    }
  };

  const savePlaylistsToCloud = async (playlists: Playlist[]) => {
    if (!auth.currentUser) return;
    try {
      const userRef = doc(db, 'users', auth.currentUser.uid);
      await setDoc(
        userRef,
        {
          playlists,
          lastSyncedAt: Date.now(),
        },
        { merge: true }
      );
    } catch (error) {
      console.error('Error syncing playlists to cloud:', error);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        loading,
        isSyncing,
        signInWithGoogle,
        logout,
        fetchCloudData,
        saveFavoritesToCloud,
        savePlaylistsToCloud,
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
