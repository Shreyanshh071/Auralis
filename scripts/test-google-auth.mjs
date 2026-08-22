// Behaviour + wiring tests for Auralis Google Authentication (web + native).
//
// Run with:  node scripts/test-google-auth.mjs
//
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (rel) => fs.readFileSync(path.join(repoRoot, rel), 'utf8');

// ---------------------------------------------------------------------------
// 1. Cancellation classification.
// Mirror of `isSignInCancellation` in src/services/googleSignIn.ts. The source
// is TypeScript and cannot be imported by a plain .mjs runner, so the pure
// contract is re-implemented here and a source assertion below guards drift.
// ---------------------------------------------------------------------------
const CANCELLATION_CODES = new Set([
  'auth/popup-closed-by-user',
  'auth/cancelled-popup-request',
  'auth/user-cancelled',
  '12501',
]);
const CANCELLATION_MESSAGE_HINTS = ['cancel', 'dismiss'];

function isSignInCancellation(error) {
  if (!error) return false;
  const code = typeof error.code === 'string' ? error.code : '';
  if (CANCELLATION_CODES.has(code)) return true;
  const raw = error.message ?? (typeof error === 'string' ? error : '');
  const message = String(raw).toLowerCase();
  return CANCELLATION_MESSAGE_HINTS.some((hint) => message.includes(hint));
}

test('cancellation: web popup closed/cancelled codes are cancellations', () => {
  assert.equal(isSignInCancellation({ code: 'auth/popup-closed-by-user' }), true);
  assert.equal(isSignInCancellation({ code: 'auth/cancelled-popup-request' }), true);
  assert.equal(isSignInCancellation({ code: 'auth/user-cancelled' }), true);
});

test('cancellation: native Google/Credential-Manager cancellations are cancellations', () => {
  assert.equal(isSignInCancellation({ code: '12501' }), true);
  assert.equal(isSignInCancellation({ message: 'The user canceled the sign-in flow.' }), true);
  assert.equal(isSignInCancellation({ message: 'activity is cancelled by the user.' }), true);
  assert.equal(isSignInCancellation({ message: 'User dismissed the account chooser' }), true);
  assert.equal(isSignInCancellation('Sign in cancelled'), true);
});

test('cancellation: genuine failures are NOT treated as cancellations', () => {
  assert.equal(isSignInCancellation({ code: 'auth/network-request-failed' }), false);
  assert.equal(isSignInCancellation({ code: 'auth/internal-error' }), false);
  assert.equal(isSignInCancellation({ message: 'DEVELOPER_ERROR (10): SHA-1 not registered' }), false);
  assert.equal(isSignInCancellation({ message: 'No ID token returned' }), false);
  assert.equal(isSignInCancellation(null), false);
  assert.equal(isSignInCancellation(undefined), false);
});

// ---------------------------------------------------------------------------
// 2. Service wiring: single-source-of-truth JS SDK, platform-branched flow.
// ---------------------------------------------------------------------------
test('googleSignIn service branches on platform and bridges native token to the JS SDK', () => {
  const src = read('src/services/googleSignIn.ts');
  assert.ok(src.includes('Capacitor.isNativePlatform'), 'must branch on native vs web');
  assert.ok(src.includes('signInWithPopup'), 'web path must use the Firebase JS SDK popup');
  assert.ok(
    src.includes('@capacitor-firebase/authentication') && /await import\(/.test(src),
    'native path must dynamically import @capacitor-firebase/authentication',
  );
  assert.ok(src.includes('skipNativeAuth: true'), 'native path must keep the JS SDK as the source of truth');
  assert.ok(src.includes('GoogleAuthProvider.credential'), 'native token must become a Google credential');
  assert.ok(src.includes('signInWithCredential'), 'credential must be exchanged for a JS SDK session');
  assert.ok(src.includes('export async function signOutEverywhere'), 'must expose a unified sign-out');
  assert.ok(
    src.includes('FirebaseAuthentication.signOut'),
    'native sign-out must clear the native session too',
  );
  assert.ok(src.includes('export function isSignInCancellation'), 'must export cancellation classifier');
});

test('googleSignIn source lists the same cancellation codes as this test (drift guard)', () => {
  const src = read('src/services/googleSignIn.ts');
  for (const code of CANCELLATION_CODES) {
    assert.ok(src.includes(code), `source must handle cancellation code/marker ${code}`);
  }
  for (const hint of CANCELLATION_MESSAGE_HINTS) {
    assert.ok(src.includes(`'${hint}'`), `source must handle cancellation hint ${hint}`);
  }
});

// ---------------------------------------------------------------------------
// 3. AuthContext delegates to the service (no duplicate auth logic).
// ---------------------------------------------------------------------------
test('AuthContext delegates to the googleSignIn service and drops direct popup import', () => {
  const src = read('src/context/AuthContext.tsx');
  assert.ok(src.includes("from '../services/googleSignIn'"), 'must import the auth service');
  assert.ok(src.includes('signOutEverywhere'), 'logout must use the unified sign-out');
  assert.ok(src.includes('isSignInCancellation'), 'must not log user cancellations as errors');
  assert.ok(
    !/signInWithPopup/.test(src),
    'AuthContext must not call signInWithPopup directly anymore (moved to the service)',
  );
  assert.ok(src.includes('onAuthStateChanged'), 'JS SDK auth-state listener must remain the source of truth');
});

// ---------------------------------------------------------------------------
// 4. Native persistence so the session survives an app restart.
// ---------------------------------------------------------------------------
test('firebase.ts pins durable auth persistence on native platforms', () => {
  const src = read('src/services/firebase.ts');
  assert.ok(src.includes('initializeAuth'), 'native auth must use initializeAuth for explicit persistence');
  assert.ok(src.includes('indexedDBLocalPersistence'), 'native persistence must be durable (indexedDB)');
  assert.ok(src.includes('Capacitor.isNativePlatform'), 'persistence choice must be platform-aware');
});

// ---------------------------------------------------------------------------
// 5. Native build + Capacitor configuration.
// ---------------------------------------------------------------------------
test('capacitor.config.json configures FirebaseAuthentication and drops the dead GoogleAuth block', () => {
  const config = JSON.parse(read('capacitor.config.json'));
  const fa = config.plugins?.FirebaseAuthentication;
  assert.ok(fa, 'FirebaseAuthentication plugin config must exist');
  assert.equal(fa.skipNativeAuth, true, 'skipNativeAuth must be true (JS SDK is source of truth)');
  assert.ok(Array.isArray(fa.providers) && fa.providers.includes('google.com'), 'google.com provider must be enabled');
  assert.ok(!config.plugins?.GoogleAuth, 'the inert @codetrix GoogleAuth block must be removed');
});

test('android/variables.gradle enables the Google Sign-In runtime dependencies', () => {
  const gradle = read('android/variables.gradle');
  assert.ok(/rgcfaIncludeGoogle\s*=\s*true/.test(gradle), 'rgcfaIncludeGoogle must be true or Google sign-in crashes at runtime');
});

test('package.json depends on the Capacitor Firebase authentication plugin', () => {
  const pkg = JSON.parse(read('package.json'));
  const deps = { ...pkg.dependencies, ...pkg.devDependencies };
  assert.ok(deps['@capacitor-firebase/authentication'], 'plugin must be a dependency');
});
