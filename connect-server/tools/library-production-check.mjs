#!/usr/bin/env node
// Read-only checks of the production Worker after step 1, using a dedicated Firebase TEST account.
// You type the test account's email and password here; they go only to Google's sign-in endpoint,
// and neither they nor the token are printed or saved. Nothing is migrated or written to the
// library: every library call below is expected to be refused or to answer "legacy".
//
//   node tools/library-production-check.mjs
//
// Optional: LIBRARY_ADMIN_SECRET in the environment also checks that the admin route works and that
// no account can be moved (the pilot allowlist is unset).
import { readFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import WebSocket from 'ws';

const BASE = 'https://auralis-connect.auralis018.workers.dev';
const firebase = JSON.parse(readFileSync(new URL('../../windows/local-resources/firebase-desktop.json', import.meta.url), 'utf8'));

function ask(question, hidden = false) {
  return new Promise((resolve) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    if (hidden) rl._writeToOutput = (text) => { if (text.startsWith(question)) process.stdout.write(question); };
    rl.question(question, (answer) => { rl.close(); if (hidden) process.stdout.write('\n'); resolve(answer.trim()); });
  });
}

let failed = 0;
async function step(name, work) {
  try { const note = await work(); console.log(`PASS ${name}${note ? ` (${note})` : ''}`); }
  catch (e) { failed += 1; console.log(`FAIL ${name}: ${e.message}`); }
}
const check = (ok, message) => { if (!ok) throw new Error(message); };

const email = await ask('Test account email: ');
const password = await ask('Test account password: ', true);
const signIn = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${firebase.apiKey}`, {
  method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email, password, returnSecureToken: true }) });
if (!signIn.ok) { console.log(`Sign-in failed (HTTP ${signIn.status}). Check the test account's email and password.`); process.exit(1); }
const { idToken, localId } = await signIn.json();
console.log(`Signed in to the test account (uid ends …${localId.slice(-4)})`);
const tampered = idToken.slice(0, -4) + (idToken.endsWith('AAAA') ? 'BBBB' : 'AAAA');

await step('Worker is up', async () => { check((await (await fetch(`${BASE}/healthz`)).text()) === 'ok', 'healthz did not answer ok'); });

await step('Connect: the test account connects and receives its state', () => new Promise((resolve, reject) => {
  const ws = new WebSocket(`${BASE.replace('https', 'wss')}/v1/connect?device=library-check`, { headers: { authorization: `Bearer ${idToken}` } });
  const timer = setTimeout(() => { ws.terminate(); reject(new Error('no state frame within 10 s')); }, 10_000);
  ws.on('message', (data) => {
    if (JSON.parse(data.toString()).t === 'state') { clearTimeout(timer); ws.close(); resolve('state received'); }
  });
  ws.once('unexpected-response', (_, res) => { clearTimeout(timer); reject(new Error(`refused: HTTP ${res.statusCode}`)); });
  ws.once('error', (e) => { clearTimeout(timer); reject(e); });
}));

await step('Connect: a tampered token is refused', () => new Promise((resolve, reject) => {
  const ws = new WebSocket(`${BASE.replace('https', 'wss')}/v1/connect?device=library-check`, { headers: { authorization: `Bearer ${tampered}` } });
  ws.once('unexpected-response', (_, res) => (res.statusCode === 401 ? resolve('401') : reject(new Error(`HTTP ${res.statusCode}`))));
  ws.once('open', () => { ws.close(); reject(new Error('a tampered token was accepted')); });
  ws.once('error', () => {});
}));

await step('Library discovery: the test account is legacy', async () => {
  const response = await fetch(`${BASE}/v1/library/authority`, { headers: { authorization: `Bearer ${idToken}` } });
  check(response.status === 200, `HTTP ${response.status}`);
  const answer = await response.json();
  check(answer.state === 'legacy' && answer.epoch === 0, `answered ${JSON.stringify(answer)}`);
  return 'legacy, epoch 0';
});

for (const [name, header] of [['no token', null], ['a malformed token', 'Bearer not-a-token'], ['a tampered token', `Bearer ${tampered}`]]) {
  await step(`Library discovery: ${name} is refused`, async () => {
    const response = await fetch(`${BASE}/v1/library/authority`, { headers: header ? { authorization: header } : {} });
    check(response.status === 401, `HTTP ${response.status}`);
    return '401';
  });
}

await step('Library: a write for the legacy test account is refused, nothing stored', async () => {
  const response = await fetch(`${BASE}/v1/library/rpc?device=library-check`, { method: 'POST', headers: { authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ t: 'push', v: 1, epoch: 0, ops: [{ id: 'check', t: 'fav.put', v: { id: 'check', title: 'check', artist: 'check' } }] }) });
  const answer = await response.json();
  check(answer.code === 'authority' && answer.state === 'legacy', `answered ${JSON.stringify(answer)}`);
  return 'refused: legacy';
});

await step('Library admin: hidden without the secret', async () => {
  const response = await fetch(`${BASE}/v1/library/admin/${localId}/status`, { method: 'POST', body: '{}' });
  check(response.status === 404, `HTTP ${response.status}`);
  return '404';
});

if (process.env.LIBRARY_ADMIN_SECRET) {
  await step('Library admin: works with the secret, and no account can be moved (allowlist unset)', async () => {
    const headers = { 'x-library-admin': process.env.LIBRARY_ADMIN_SECRET };
    const status = await (await fetch(`${BASE}/v1/library/admin/${localId}/status`, { method: 'POST', headers, body: '{}' })).json();
    check(status.state === 'legacy', `status ${status.state}`);
    const begin = await fetch(`${BASE}/v1/library/admin/${localId}/begin`, { method: 'POST', headers, body: '{}' });
    check(begin.status === 403, `begin answered HTTP ${begin.status}; it must be refused`);
    const after = await (await fetch(`${BASE}/v1/library/admin/${localId}/status`, { method: 'POST', headers, body: '{}' })).json();
    check(after.state === 'legacy' && after.epoch === 0, 'the refused begin changed the account');
    return 'begin refused, still legacy';
  });
}

console.log(failed ? `${failed} check(s) FAILED` : 'All checks passed');
process.exit(failed ? 1 : 0);
