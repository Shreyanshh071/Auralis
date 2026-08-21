import { initializeApp, getApps, getApp } from 'firebase/app';
import { getAuth, GoogleAuthProvider } from 'firebase/auth';
import { getFirestore } from 'firebase/firestore';

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY || "AIzaSyBSJXXQQXkQ0o-uACoLpZTHiuzhHD0VKo8",
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN || "auralis-70cf8.firebaseapp.com",
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID || "auralis-70cf8",
  storageBucket: import.meta.env.VITE_FIREBASE_STORAGE_BUCKET || "auralis-70cf8.firebasestorage.app",
  messagingSenderId: import.meta.env.VITE_FIREBASE_MESSAGING_SENDER_ID || "30030184374",
  appId: import.meta.env.VITE_FIREBASE_APP_ID || "1:30030184374:android:b4dabb16a9c3a96e71cb17",
};

// Initialize Firebase
const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApp();
export const auth = getAuth(app);
export const googleProvider = new GoogleAuthProvider();
export const db = getFirestore(app);

export const GOOGLE_CLIENT_ID = "30030184374-pe7h8deq7qp2josb62junld16udgnnin.apps.googleusercontent.com";
