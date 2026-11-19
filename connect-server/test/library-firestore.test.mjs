import { test } from 'node:test';
import assert from 'node:assert/strict';
import { generateKeyPairSync, verify } from 'node:crypto';
import { LibraryFirestore } from '../src/library-firestore.js';
const env = { DEV_AUTH: 'local-tests-only', LIBRARY_FIRESTORE_EMULATOR: 'http://127.0.0.1:9999', FIREBASE_PROJECT_ID: 'demo-auralis' };
const name = 'projects/demo-auralis/databases/(default)/documents/users/test';
for (const status of [401, 403, 429, 500, 503]) test(`backend HTTP ${status} is never an empty import`, async () => {
 const f = new LibraryFirestore(env, async () => new Response('', { status }));
 await assert.rejects(f.get('users/test'), new RegExp(`legacy-${status}`));
});
test('missing document requires a reachable, authorized parent database', async () => {
 for (const parent of [401, 403, 404, 429, 503]) {
  let n = 0;
  const f = new LibraryFirestore(env, async () => new Response('', { status: ++n === 1 ? 404 : parent }));
  await assert.rejects(f.get('users/test'), /legacy-parent/);
 }
 const urls = [];
 const f = new LibraryFirestore(env, async (url) => { urls.push(url); return new Response('', { status: urls.length === 1 ? 404 : 200 }); });
 assert.deepEqual(await f.get('users/test'), { exists: false, updateTime: null, data: null });
 assert.match(urls[1], /databases\/\(default\)$/);
});
test('typed values, including unrelated profile timestamps, decode without losing library fields', async () => {
 const f = new LibraryFirestore(env, async () => Response.json({ name, updateTime: 't1', fields: {
  updatedAt: { timestampValue: '2026-11-19T00:00:00Z' }, favorites: { arrayValue: { values: [{ mapValue: { fields: { id: { stringValue: 'song' } } } }] } },
 } }));
 assert.equal((await f.get('users/test')).data.favorites[0].id, 'song');
});
test('CAS uses POST commit, a library-only mask, a precondition and atomic server-only marker', async () => {
 const claims = [];
 const f = new LibraryFirestore(env, async (url, init) => {
  assert.match(url, /documents:commit$/); assert.equal(init.method, 'POST');
  const b = JSON.parse(init.body); assert.equal(b.writes.length, 2);
  assert.deepEqual(b.writes[0].updateMask.fieldPaths, ['favorites']);
  assert.deepEqual(b.writes[0].currentDocument, { updateTime: 't1' });
  assert.match(b.writes[1].update.name, /libraryBridgeJournal\/test$/);
  assert.equal(b.writes[1].update.fields.id.stringValue, 'pending');
  return Response.json({ writeResults: [{ updateTime: 't2' }, { updateTime: 't2' }] });
 }, async (...a) => claims.push(a));
 assert.equal(await f.compareAndSet('test', { exists: true, updateTime: 't1' }, { favorites: [] }, { id: 'pending', checksum: 'sum' }), 't2');
 assert.deepEqual(claims, [['write', 2]]);
});
test('a missing source is created conditionally; precondition conflicts are retried', async () => {
 const f = new LibraryFirestore(env, async (_, init) => {
  assert.deepEqual(JSON.parse(init.body).writes[0].currentDocument, { exists: false });
  return new Response('', { status: 409 });
 });
 assert.equal(await f.compareAndSet('test', { exists: false }, {}, { id: 'p' }), null);
});
test('budget refusals happen before I/O, and local overrides are ignored outside local auth', async () => {
 let calls = 0;
 const f = new LibraryFirestore(env, async () => { calls++; }, async () => { throw new Error('budget'); });
 await assert.rejects(f.get('users/test'), /budget/); assert.equal(calls, 0);
 const production = new LibraryFirestore({ ...env, DEV_AUTH: undefined }, async () => { calls++; });
 await assert.rejects(production.get('users/test'), /legacy-credentials/); assert.equal(calls, 0);
});
test('invalid and oversized provider responses are refused', async () => {
 for (const body of ['not json', JSON.stringify({ name: name + '-other', updateTime: 't1' }), ' '.repeat(8 * 1024 * 1024 + 1)]) {
  const f = new LibraryFirestore(env, async () => new Response(body));
  await assert.rejects(f.get('users/test'), /legacy-(response|shape|too-large)/);
 }
});
test('backend OAuth signs a project-matched service account assertion, caches it and uses only Google origins', async () => {
 const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
 const key = { project_id: 'demo-auralis', client_email: 'bridge@example.iam.gserviceaccount.com', private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
 let oauth = 0;
 const f = new LibraryFirestore({ FIREBASE_PROJECT_ID: key.project_id, LIBRARY_FIRESTORE_SERVICE_ACCOUNT: JSON.stringify(key) }, async (url, init) => {
  if (url === 'https://oauth2.googleapis.com/token') {
   oauth++;
   const jwt = init.body.get('assertion'); const parts = jwt.split('.');
   assert.ok(verify('RSA-SHA256', Buffer.from(parts.slice(0, 2).join('.')), publicKey, Buffer.from(parts[2], 'base64url')));
   const claims = JSON.parse(Buffer.from(parts[1], 'base64url'));
   assert.equal(claims.iss, key.client_email); assert.equal(claims.aud, url);
   return Response.json({ access_token: 'fixture-token', expires_in: 3600 });
  }
  assert.ok(url.startsWith('https://firestore.googleapis.com/')); assert.equal(init.headers.authorization, 'Bearer fixture-token');
  return Response.json({ name, updateTime: 't1', fields: {} });
 });
 await f.get('users/test'); await f.get('users/test'); assert.equal(oauth, 1);
 const wrong = new LibraryFirestore({ FIREBASE_PROJECT_ID: 'wrong', LIBRARY_FIRESTORE_SERVICE_ACCOUNT: JSON.stringify(key) });
 await assert.rejects(wrong.get('users/test'), /legacy-credentials/);
});
