import { Capacitor } from '@capacitor/core';
import {
  GoogleAuthProvider,
  signInWithCredential,
  signInWithPopup,
  signOut as firebaseSignOut,
  type Auth,
} from 'firebase/auth';
import { googleProvider } from './firebase';

/**
 * Google Sign-In, unified across web and native (Capacitor/Android).
 *
 * The **Firebase JS SDK is the single source of truth** for auth state and
 * Firestore access on both platforms. `onAuthStateChanged` (wired in
 * `AuthContext`) fires for either path, and cloud sync is unchanged.
 *
 * - **Web:** the Firebase JS SDK popup. Reliable in a real browser.
 * - **Native:** `signInWithPopup` is unreliable-to-broken inside an Android
 *   WebView, so the Google ID token is obtained through the native Google
 *   Sign-In flow (`@capacitor-firebase/authentication`) and then exchanged for a
 *   JS SDK session via `signInWithCredential`. `skipNativeAuth` keeps the native
 *   Firebase SDK out of the loop so there is exactly one session to reason about.
 */

/**
 * Firebase Auth error codes and native-plugin conditions that mean the user
 * deliberately dismissed the sign-in flow, rather than a genuine failure.
 * A cancellation must never be surfaced as an error.
 */
const CANCELLATION_CODES = new Set<string>([
  'auth/popup-closed-by-user',
  'auth/cancelled-popup-request',
  'auth/user-cancelled',
  '12501', // legacy Google Sign-In SDK: SIGN_IN_CANCELLED
]);

/** Lower-cased substrings that indicate a user-initiated cancellation. */
const CANCELLATION_MESSAGE_HINTS = [
  'cancel', // "canceled" / "cancelled" / "user canceled the sign-in flow"
  'dismiss', // Credential Manager: user dismissed the account chooser
];

/**
 * True when an error represents a user-initiated cancellation rather than a
 * real failure. Callers should treat a cancellation as a silent no-op (no error
 * toast, no logged error).
 */
export function isSignInCancellation(error: unknown): boolean {
  if (!error) return false;
  const code =
    typeof (error as { code?: unknown }).code === 'string'
      ? ((error as { code: string }).code)
      : '';
  if (CANCELLATION_CODES.has(code)) return true;

  const rawMessage =
    (error as { message?: unknown }).message ??
    (typeof error === 'string' ? error : '');
  const message = String(rawMessage).toLowerCase();
  return CANCELLATION_MESSAGE_HINTS.some((hint) => message.includes(hint));
}

/**
 * Signs in with Google and establishes a Firebase JS SDK session.
 *
 * Throws on failure and on cancellation. Callers should first check
 * `isSignInCancellation(error)` and, if true, treat the throw as a no-op.
 */
export async function signInWithGoogle(auth: Auth): Promise<void> {
  if (!Capacitor.isNativePlatform()) {
    await signInWithPopup(auth, googleProvider);
    return;
  }

  // Native path — loaded only on device, never in the web bundle at runtime.
  const { FirebaseAuthentication } = await import(
    '@capacitor-firebase/authentication'
  );
  const result = await FirebaseAuthentication.signInWithGoogle({
    // Do the native Google flow but keep Firebase state in the JS SDK only.
    skipNativeAuth: true,
  });

  const idToken = result.credential?.idToken;
  if (!idToken) {
    // Almost always means the app's signing SHA-1 is not registered in the
    // Firebase project, so Google returned no ID token. Surface it clearly.
    throw new Error(
      'Google sign-in returned no ID token. Register the app SHA-1 fingerprint in the Firebase console (Project settings > your Android app) and re-download google-services.json.'
    );
  }

  const credential = GoogleAuthProvider.credential(
    idToken,
    result.credential?.accessToken
  );
  await signInWithCredential(auth, credential);
}

/**
 * Signs out of the Firebase JS SDK, and — on native — clears the native Google
 * Sign-In session too, so the next sign-in re-prompts for an account. A native
 * sign-out failure must not block the JS SDK sign-out.
 */
export async function signOutEverywhere(auth: Auth): Promise<void> {
  if (Capacitor.isNativePlatform()) {
    try {
      const { FirebaseAuthentication } = await import(
        '@capacitor-firebase/authentication'
      );
      await FirebaseAuthentication.signOut();
    } catch (error) {
      console.warn('[Auralis] Native sign-out failed; continuing.', error);
    }
  }
  await firebaseSignOut(auth);
}
