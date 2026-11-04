import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';

/** Runs the real Worker and Durable Object in workerd on this machine. Nothing leaves it. */
export async function startServer(port, persistDir = mkdtempSync(join(tmpdir(), 'auralis-connect-'))) {
  writeFileSync('.dev.vars', 'DEV_AUTH=local-tests-only\nSTAGING_SECRET=local-secret\n');
  const process_ = spawn(process.platform === 'win32' ? 'npx.cmd' : 'npx', ['wrangler', 'dev', '--port', String(port), '--ip', '127.0.0.1',
    '--persist-to', persistDir, '--log-level', 'error', '--show-interactive-dev-session=false'],
    { env: { ...process.env, WRANGLER_SEND_METRICS: 'false', CI: '1' }, stdio: 'pipe', shell: process.platform === 'win32' });
  let output = '';
  process_.stdout.on('data', (d) => { output += d; });
  process_.stderr.on('data', (d) => { output += d; });
  for (let i = 0; i < 300; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/healthz`)).ok) return { process: process_, persistDir, port }; } catch { /* starting */ }
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`server didn't start: ${output.slice(-600)}`);
}

export async function stopServer(server) {
  if (process.platform === 'win32') spawn('taskkill', ['/pid', String(server.process.pid), '/T', '/F'], { stdio: 'ignore' });
  else server.process.kill('SIGKILL');
  await new Promise((r) => setTimeout(r, 1200));
}

export class Client {
  constructor(ws) {
    this.ws = ws; this.frames = []; this.waiting = []; this.nextId = 1; this.closed = null;
    ws.on('message', (data) => {
      if (data.toString() === 'pong') return;
      const frame = JSON.parse(data.toString());
      this.frames.push(frame);
      this.waiting = this.waiting.filter((w) => !(w.match(frame) && (w.resolve(frame), true)));
    });
    ws.on('close', (code) => { this.closed = code; this.waiting.forEach((w) => w.reject(new Error(`closed ${code}`))); this.waiting = []; });
    ws.on('error', () => {});
  }
  next(match, ms = 4000) {
    const found = this.frames.find(match);
    if (found) { this.frames.splice(this.frames.indexOf(found), 1); return Promise.resolve(found); }
    return new Promise((resolve, reject) => {
      const entry = { match, resolve: (f) => { clearTimeout(timer); this.frames.splice(this.frames.indexOf(f), 1); resolve(f); }, reject };
      const timer = setTimeout(() => { this.waiting = this.waiting.filter((w) => w !== entry); reject(new Error('timed out')); }, ms);
      this.waiting.push(entry);
    });
  }
  raw(text) { this.ws.send(text); }
  call(frame, ms) {
    const r = this.nextId++;
    this.ws.send(JSON.stringify({ ...frame, r }));
    return this.next((f) => f.r === r, ms);
  }
  closedWith(ms = 4000) {
    if (this.closed !== null) return Promise.resolve(this.closed);
    return new Promise((resolve) => { this.ws.once('close', resolve); setTimeout(() => resolve(null), ms); });
  }
  close() { this.ws.terminate(); }
}

export function connect(port, uid, device, { token = `dev:${uid}`, host = '127.0.0.1', headers = {} } = {}) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${host}:${port}/v1/connect?device=${device}`, { headers: token === null ? headers : { ...headers, authorization: `Bearer ${token}` } });
    const client = new Client(ws);
    ws.once('open', () => resolve(client));
    ws.once('unexpected-response', (_, res) => reject(Object.assign(new Error('refused'), { status: res.statusCode })));
    ws.once('error', reject);
  });
}

export const deviceDoc = (id, extra = {}) => ({ id, name: id, nickname: null, platform: 'ANDROID', type: 'PHONE', appVersion: '2', canPlay: true,
  online: true, createdAt: 1, ...extra });

export const sessionDoc = (extra = {}) => ({
  version: 1, ownerId: 'phone', ownerName: 'Pixel', ownerType: 'PHONE', epoch: 1, leaseUntil: Date.now() + 60_000, track: { id: 's' }, playing: true,
  buffering: false, positionMs: 0, positionAt: Date.now(), durationMs: 180000, queueRev: 1, queueIndex: 0, shuffle: false, repeat: 'OFF', context: null,
  transfer: null, commands: {}, updatedBy: 'phone', ...extra,
});
