import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { createSign, generateKeyPairSync } from 'node:crypto';
import { googleKeys, stagingIdentity, verifyToken } from '../src/auth.js';

const PROJECT = 'auralis-70cf8';
const NOW = 1_800_000_000_000;
const s = NOW / 1000;
let keys, privateKey, otherPrivateKey;

const b64 = (o) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
function sign(claims, { key = privateKey, header = { alg: 'RS256', kid: 'k1' } } = {}) {
  const body = `${b64(header)}.${b64(claims)}`;
  return `${body}.${createSign('RSA-SHA256').update(body).sign(key).toString('base64url')}`;
}
const claims = (extra = {}) => ({ iss: `https://securetoken.google.com/${PROJECT}`, aud: PROJECT, sub: 'alice', iat: s - 10, auth_time: s - 100,
  exp: s + 3600, firebase: { sign_in_provider: 'google.com' }, ...extra });

before(() => {
  const pair = generateKeyPairSync('rsa', { modulusLength: 2048 });
  privateKey = pair.privateKey;
  otherPrivateKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
  keys = [{ ...pair.publicKey.export({ format: 'jwk' }), kid: 'k1', alg: 'RS256' }];
});

const verify = (token) => verifyToken(token, PROJECT, keys, NOW);

test('a genuine token proves its account and expiry', async () => {
  assert.deepEqual(await verify(sign(claims())), { uid: 'alice', expiresAtMs: (s + 3600) * 1000 });
});

test('a forged signature, tampered claims or unknown key is refused', async () => {
  assert.equal(await verify(sign(claims(), { key: otherPrivateKey })), null);
  const [h, , sig] = sign(claims()).split('.');
  assert.equal(await verify(`${h}.${b64(claims({ sub: 'victim' }))}.${sig}`), null);
  assert.equal(await verify(sign(claims(), { header: { alg: 'RS256', kid: 'nope' } })), null);
});

test('an unsigned token cannot downgrade the algorithm', async () => {
  const body = `${b64({ alg: 'none', kid: 'k1' })}.${b64(claims())}`;
  assert.equal(await verify(`${body}.`), null);
  assert.equal(await verify(sign(claims(), { header: { alg: 'HS256', kid: 'k1' } })), null);
});

test('expired tokens, other projects and future tokens are refused', async () => {
  assert.equal(await verify(sign(claims({ exp: s - 1 }))), null);
  assert.equal(await verify(sign(claims({ aud: 'other-project' }))), null);
  assert.equal(await verify(sign(claims({ iss: 'https://securetoken.google.com/other' }))), null);
  assert.equal(await verify(sign(claims({ iat: s + 3600 }))), null);
  assert.equal(await verify(sign(claims({ auth_time: s + 3600 }))), null);
  assert.equal(await verify(sign(claims({ sub: '' }))), null);
});

test('anonymous Listen Together guests have no Connect account', async () => {
  assert.equal(await verify(sign(claims({ firebase: { sign_in_provider: 'anonymous' } }))), null);
});

test('garbage is refused rather than thrown', async () => {
  for (const bad of [undefined, '', 'a.b', 'a.b.c', '...', 'dev:alice']) assert.equal(await verify(bad), null);
});

test('the staging login needs the secret, and never reaches a real account', () => {
  assert.equal(stagingIdentity('staging:right:probe', undefined), null, 'Without the secret set, it does not exist');
  assert.equal(stagingIdentity('staging:right:probe', ''), null);
  assert.equal(stagingIdentity('staging:wrong:probe', 'right'), null);
  assert.equal(stagingIdentity('staging:righ:probe', 'right'), null);
  assert.equal(stagingIdentity('staging:right:Bad/Uid', 'right'), null);
  assert.equal(stagingIdentity(undefined, 'right'), null);
  assert.deepEqual(stagingIdentity('staging:right:probe', 'right', 1000), { uid: 'staging-probe', expiresAtMs: 86_401_000 });
});

test('keys are cached for as long as Google says, and survive its outage', async () => {
  let calls = 0;
  let up = true;
  const fetcher = async () => { calls++; return up
    ? new Response(JSON.stringify({ keys: [{ kid: 'k' }] }), { headers: { 'cache-control': 'public, max-age=100' } })
    : new Response('down', { status: 503 }); };
  assert.deepEqual(await googleKeys(1_000, fetcher), [{ kid: 'k' }]);
  await googleKeys(50_000, fetcher);
  assert.equal(calls, 1);
  up = false;
  assert.deepEqual(await googleKeys(200_000, fetcher), [{ kid: 'k' }]);
  assert.equal(calls, 2);
});
