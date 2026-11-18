#!/usr/bin/env node
// Listen Together smoke test against a deployed Worker with synthetic staging identities
// (staging-<id>, never a real account). Reads AURALIS_STAGING_URL and AURALIS_STAGING_SECRET from the
// environment or .measure.env (ignored by Git). Prints step results only, never the secret.
import { existsSync, readFileSync } from 'node:fs';
import WebSocket from 'ws';

if (existsSync('.measure.env')) {
  for (const line of readFileSync('.measure.env', 'utf8').split('\n')) {
    const at = line.indexOf('=');
    if (at > 0 && !process.env[line.slice(0, at)]) process.env[line.slice(0, at)] = line.slice(at + 1).trim();
  }
}
const base = (process.env.AURALIS_STAGING_URL ?? '').replace(/\/$/, '');
const secret = process.env.AURALIS_STAGING_SECRET ?? '';
if (!base.startsWith('https://') || !secret) { console.error('Set AURALIS_STAGING_URL (https) and AURALIS_STAGING_SECRET'); process.exit(2); }

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const code = [...Array(6)].map(() => ALPHABET[Math.floor(Math.random() * 32)]).join('');
const run = Date.now().toString(36);

function open(id) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/v1/together?room=${code}`, { headers: { authorization: `Bearer staging:${secret}:lt-${id}-${run}` } });
    const c = { ws, frames: [], waiters: [], id: 1 };
    ws.on('message', (d) => {
      const f = JSON.parse(d.toString()); c.frames.push(f);
      c.waiters = c.waiters.filter((w) => !(w.m(f) && (w.r(f), true)));
    });
    c.next = (m, ms = 8000) => {
      const hit = c.frames.find(m); if (hit) { c.frames.splice(c.frames.indexOf(hit), 1); return Promise.resolve(hit); }
      return new Promise((r, j) => { const t = setTimeout(() => j(new Error('timed out')), ms); c.waiters.push({ m, r: (f) => { clearTimeout(t); r(f); } }); });
    };
    c.call = (f) => { const r = c.id++; ws.send(JSON.stringify({ ...f, r })); return c.next((x) => x.r === r); };
    ws.once('open', () => resolve(c));
    ws.once('unexpected-response', (_, res) => reject(new Error(`refused ${res.statusCode}`)));
    ws.once('error', reject);
  });
}

const step = async (name, fn) => {
  const started = Date.now();
  try { await fn(); console.log(`ok   ${name} (${Date.now() - started} ms)`); }
  catch (e) { console.log(`FAIL ${name}: ${e.message}`); process.exitCode = 1; throw e; }
};

const now = () => Date.now();
let host, guest;
try {
  await step('host connects and creates a room', async () => {
    host = await open('host'); await host.next((f) => f.t === 'state');
    const r = await host.call({ t: 'create', room: { hostName: 'Smoke', currentTrack: { id: 't1' }, queue: [], isPlaying: true, playbackPosition: 0, updatedAt: now(), settings: {} }, member: { name: 'Smoke host', lastSeen: now() } });
    if (r.t !== 'ok') throw new Error(r.code);
  });
  await step('guest joins and both see the roster', async () => {
    guest = await open('guest'); await guest.next((f) => f.t === 'state');
    const r = await guest.call({ t: 'join', member: { name: 'Smoke guest', lastSeen: now() } });
    if (r.t !== 'ok') throw new Error(r.code);
    await host.next((f) => f.t === 'members' && f.members.length === 2);
  });
  await step('host playback reaches the guest with server time', async () => {
    const sent = now();
    await host.call({ t: 'room', set: { isPlaying: false, playbackPosition: 45_000, updatedAt: sent } });
    const f = await guest.next((x) => x.t === 'room' && x.room?.playbackPosition === 45_000);
    if (!(f.room.serverUpdatedAt > 0)) throw new Error('no server time');
    console.log(`     push latency ≈ ${now() - sent} ms`);
  });
  await step('guest heartbeat answers with server time', async () => {
    const sent = now(); const r = await guest.call({ t: 'member', set: { lastSeen: sent } });
    console.log(`     round trip ${now() - sent} ms, clock offset ≈ ${r.now - (sent + (now() - sent) / 2)} ms`);
  });
  await step('song request: guest suggests, host accepts', async () => {
    const r = await guest.call({ t: 'rec', rec: { track: { id: 'x', title: 'X' }, recommendedByName: 'Smoke guest' } });
    await host.next((f) => f.t === 'recs' && f.recs.length === 1);
    await host.call({ t: 'rec-status', id: r.id, status: 'accepted' });
    await guest.next((f) => f.t === 'recs' && f.recs[0]?.status === 'accepted');
  });
  await step('host leaving closes the room for the guest', async () => {
    await host.call({ t: 'leave' });
    await guest.next((f) => f.t === 'room' && f.room === null);
  });
} catch { /* reported above */ } finally {
  host?.ws.terminate(); guest?.ws.terminate();
}
