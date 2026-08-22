import { initializeApp, getApps, getApp, type FirebaseApp } from 'firebase/app';
import {
  getAuth,
  initializeAuth,
  indexedDBLocalPersistence,
  browserLocalPersistence,
  GoogleAuthProvider,
  type Auth,
} from 'firebase/auth';
import { getFirestore, type Firestore } from 'firebase/firestore';
import { Capacitor } from '@capacitor/core';

/**
 * Firebase web configuration.
 *
 * Every value comes from Vite environment variables — there are no hardcoded
 * fallbacks. Firebase web config is not secret (it ships in the client bundle),
 * but hardcoding it pins the app to one project and silently masks a missing or
 * wrong setup, which produced confusing runtime auth failures.
 *
 * Copy `.env.example` to `.env` and fill in the values from
 * Firebase console → Project settings → Your apps → Web app → SDK setup.
 *
 * IMPORTANT: `VITE_FIREBASE_APP_ID` must be the **Web** app ID
 * (`1:<sender>:web:<hash>`). An Android app ID (`1:<sender>:android:<hash>`) is
 * not valid for the JS SDK. The Android native app is configured separately via
 * `android/app/google-services.json`, which is a different file and is left as is.
 */

const env = import.meta.env as unknown as Record<string, string | undefined>;

const read = (key: string): string => (env[key] ?? '').trim();

/** Keys required for Firebase Auth + Firestore to work at all. */
const REQUIRED_KEYS = [
  'VITE_FIREBASE_API_KEY',
  'VITE_FIREBASE_AUTH_DOMAIN',
  'VITE_FIREBASE_PROJECT_ID',
  'VITE_FIREBASE_APP_ID',
] as const;

const missingKeys = REQUIRED_KEYS.filter((key) => read(key) === '');

/**
 * Rejects an app ID that is not a Web app ID.
 *
 * This is guarding a mistake that actually happened: the config previously
 * hardcoded `1:30030184374:android:b4dabb16a9c3a96e71cb17`, an **Android** app ID,
 * as the JS SDK's `appId`. The SDK accepts it at `initializeApp()` and only fails
 * later with an opaque auth/Firestore error, so the root cause is very hard to
 * see. Failing loudly here turns a confusing runtime failure into a precise
 * configuration message.
 *
 * Returns null when the value is acceptable, or an explanation when it is not.
 */
const describeAppIdProblem = (appId: string): string | null => {
  if (appId === '') return null; // already covered by the missing-key check
  if (/:web:/.test(appId)) return null;

  const platform = /:android:/.test(appId)
    ? 'an Android'
    : /:ios:/.test(appId)
      ? 'an iOS'
      : null;

  if (platform !== null) {
    return `VITE_FIREBASE_APP_ID is ${platform} app ID. The Firebase JS SDK requires the Web app ID, which looks like 1:<sender>:web:<hash>. Get it from Firebase console → Project settings → Your apps → Web app → SDK setup and configuration. The Android app is configured separately via android/app/google-services.json.`;
  }

  return `VITE_FIREBASE_APP_ID does not look like a Firebase Web app ID (expected 1:<sender>:web:<hash>).`;
};

const appIdProblem = describeAppIdProblem(read('VITE_FIREBASE_APP_ID'));

/** True only when every required variable is present and the app ID is a Web app ID. */
export const isFirebaseConfigured = missingKeys.length === 0 && appIdProblem === null;

/** Human-readable reason Firebase is unavailable, or null when it is configured. */
export const firebaseConfigError: string | null = isFirebaseConfigured
  ? null
  : missingKeys.length > 0
    ? `Firebase is not configured. Missing environment ${
        missingKeys.length === 1 ? 'variable' : 'variables'
      }: ${missingKeys.join(', ')}. Copy .env.example to .env and fill it in.`
    : appIdProblem;

let app: FirebaseApp | null = null;
let authInstance: Auth | null = null;
let dbInstance: Firestore | null = null;

/**
 * Resolves the Auth instance with persistence appropriate to the platform.
 *
 * On native (the Capacitor Android WebView) the session is explicitly pinned to
 * durable storage so it survives an app restart, matching the plugin's
 * recommendation for the Firebase JS SDK. On web, `getAuth()` already resolves
 * to durable persistence and `initializeAuth` must not run twice (Fast Refresh),
 * so it is used as-is. The try/catch tolerates a repeated init on native too.
 */
const createAuth = (firebaseApp: FirebaseApp): Auth => {
  if (!Capacitor.isNativePlatform()) return getAuth(firebaseApp);
  try {
    return initializeAuth(firebaseApp, {
      persistence: [indexedDBLocalPersistence, browserLocalPersistence],
    });
  } catch {
    return getAuth(firebaseApp);
  }
};

if (isFirebaseConfigured) {
  const firebaseConfig = {
    apiKey: read('VITE_FIREBASE_API_KEY'),
    authDomain: read('VITE_FIREBASE_AUTH_DOMAIN'),
    projectId: read('VITE_FIREBASE_PROJECT_ID'),
    // Optional: only needed if Cloud Storage is used. Empty string is tolerated.
    storageBucket: read('VITE_FIREBASE_STORAGE_BUCKET') || undefined,
    messagingSenderId: read('VITE_FIREBASE_MESSAGING_SENDER_ID') || undefined,
    appId: read('VITE_FIREBASE_APP_ID'),
  };

  app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApp();
  authInstance = createAuth(app);
  dbInstance = getFirestore(app);
} else {
  // Deliberately not throwing: the rest of the app works without an account.
  // Consumers must check `isFirebaseConfigured` and surface the real reason.
  console.warn(`[Auralis] ${firebaseConfigError}`);
}

/** Firebase Auth instance, or null when Firebase is not configured. */
export const auth = authInstance;

/** Firestore instance, or null when Firebase is not configured. */
export const db = dbInstance;

/** Provider object is inert until used with a configured `auth`. */
export const googleProvider = new GoogleAuthProvider();

/**
 * OAuth client ID, only needed by native Google Sign-In plugins.
 * Null when unset — callers must handle that rather than assume a value.
 */
export const GOOGLE_CLIENT_ID: string | null = read('VITE_GOOGLE_CLIENT_ID') || null;
