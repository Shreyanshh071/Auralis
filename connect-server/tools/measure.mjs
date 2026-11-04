// Measures what Cloudflare's free plan really counts, against the staging Worker.
// See docs/connect-staging-plan.md. Every result is appended to measure-results.jsonl.
//
//   node tools/measure.mjs check                 one connection, one write, latency
//   node tools/measure.mjs messages <count>      send exactly this many messages, then stop
//   node tools/measure.mjs to-limit [count]      send until something refuses, or until count
//   node tools/measure.mjs connects <count>      open and close this many connections
//   node tools/measure.mjs idle <minutes>        hold one socket with pings and no messages
//   node tools/measure.mjs usage <since-date>    read Cloudflare's analytics
//   node tools/measure.mjs secret <wss-url>      new secret: saved to .measure.env, printed for wrangler
//
// .measure.env, or the environment, supplies:
// AURALIS_MEASURE_URL   ws://127.0.0.1:8798 or wss://<worker>.workers.dev
// AURALIS_MEASURE_TOKEN staging:<secret>:<account>
// CF_ACCOUNT_ID, CF_API_TOKEN  for usage only: an Account Analytics read token
import { randomBytes } from 'node:crypto';
import { appendFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import WebSocket from 'ws';

if (existsSync('.measure.env')) {
  for (const line of readFileSync('.measure.env', 'utf8').split(String.fromCharCode(10))) {
    const at = line.indexOf('=');
    if (at > 0 && !process.env[line.slice(0, at)]) process.env[line.slice(0, at)] = line.slice(at + 1).trim();
  }
}

const SOCKETS = 12;
const SILENCE_LIMIT = 1_000; // messages sent with no reply before calling it a failure
const PER_SOCKET_SECOND = 9; // just under the server's 10 a second
const PING_MS = 45_000;
const url = (process.env.AURALIS_MEASURE_URL ?? '').replace(/\/$/, '');
const token = process.env.AURALIS_MEASURE_TOKEN ?? '';

function record(entry) {
  const line = { at: new Date().toISOString(), ...entry };
  console.log(JSON.stringify(line));
  appendFileSync('measure-results.jsonl', `${JSON.stringify(line)}\n`);
}

function needTarget() {
  if (!url || !token) { console.error('Set AURALIS_MEASURE_URL and AURALIS_MEASURE_TOKEN'); process.exit(2); }
  if (url.startsWith('ws://') && !url.includes('127.0.0.1') && !url.includes('localhost')) {
    console.error('Refusing to send a token over plain ws to a remote host'); process.exit(2);
  }
}

function open(device) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${url}/v1/connect?device=${device}`, { headers: { authorization: `Bearer ${token}` } });
    const socket = { ws, replies: 0, refused: [], closed: null, ready: null };
    socket.ready = new Promise((ready) => {
      ws.on('message', (data) => {
        const frame = JSON.parse(data.toString());
        if (frame.t === 'state') ready();
        else if (frame.t === 'err') socket.refused.push(frame.code);
        else socket.replies += 1;
        socket.last?.(frame);
      });
    });
    ws.once('open', () => resolve(socket));
    ws.once('unexpected-response', (_, res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => reject(Object.assign(new Error(`HTTP ${res.statusCode}`), { status: res.statusCode, body: body.slice(0, 200) })));
    });
    ws.once('error', (error) => { if (ws.readyState !== WebSocket.OPEN) reject(error); });
    ws.on('close', (code) => { socket.closed = code; });
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const closeAll = async (sockets) => { sockets.forEach((s) => s.ws.close()); await sleep(1500); };

async function check() {
  const socket = await open('measure-check');
  await socket.ready;
  const started = performance.now();
  const reply = new Promise((resolve) => { socket.last = resolve; });
  socket.ws.send(JSON.stringify({ t: 'get-intent', r: 1 }));
  await reply;
  record({ test: 'check', roundTripMs: Math.round(performance.now() - started) });
  await closeAll([socket]);
}

/** Sends at a steady pace across sockets. Stops at `count`, or at the first sign of a limit. */
async function send(count, stopOnRefusal) {
  const sockets = [];
  for (let i = 0; i < SOCKETS; i += 1) { const s = await open(`measure-${i}`); await s.ready; sockets.push(s); }
  record({ test: 'sending', sockets: sockets.length, target: count });
  const started = Date.now();
  let sent = 0;
  let failure = null;
  const perTick = SOCKETS * PER_SOCKET_SECOND / 10;
  while (sent < count && !failure) {
    const tick = Date.now();
    for (let i = 0; i < perTick && sent < count; i += 1) {
      const socket = sockets[sent % sockets.length];
      if (socket.ws.readyState !== WebSocket.OPEN) { failure = { socketClosed: socket.closed }; break; }
      socket.ws.send(JSON.stringify({ t: 'get-intent', r: sent }));
      sent += 1;
    }
    const answered = sockets.reduce((n, s) => n + s.replies, 0);
    if (!failure && stopOnRefusal && sent - answered > SILENCE_LIMIT) failure = { stoppedAnswering: sent - answered };
    if (stopOnRefusal) {
      const refused = sockets.find((s) => s.refused.length);
      if (refused) failure = { refused: refused.refused.slice(0, 3) };
    }
    if (sent % 20_000 < perTick) record({ test: 'progress', sent, replies: sockets.reduce((n, s) => n + s.replies, 0) });
    await sleep(Math.max(0, 100 - (Date.now() - tick)));
  }
  await sleep(3000);
  const replies = sockets.reduce((n, s) => n + s.replies, 0);
  let reconnect = null;
  if (failure) {
    try { const s = await open('measure-after'); await s.ready; s.ws.close(); reconnect = 'worked'; } catch (e) { reconnect = { status: e.status ?? e.message, body: e.body }; }
  }
  record({ test: stopOnRefusal ? 'to-limit' : 'messages', sent, replies, unanswered: sent - replies, seconds: Math.round((Date.now() - started) / 1000),
    failure, reconnect, closeCodes: [...new Set(sockets.map((s) => s.closed))] });
  await closeAll(sockets);
}

async function connects(count) {
  let opened = 0;
  let failed = null;
  for (let i = 0; i < count && !failed; i += 1) {
    try { const s = await open(`measure-c${i % 8}`); await s.ready; s.ws.close(); opened += 1; await sleep(150); } catch (e) { failed = e.status ?? e.message; }
  }
  record({ test: 'connects', opened, failed });
}

async function idle(minutes) {
  const socket = await open('measure-idle');
  await socket.ready;
  const started = Date.now();
  let pongs = 0;
  socket.ws.on('pong', () => { pongs += 1; });
  const pinger = setInterval(() => socket.ws.readyState === WebSocket.OPEN && socket.ws.ping(), Math.min(PING_MS, minutes * 15_000));
  while (Date.now() - started < minutes * 60_000 && socket.closed === null) await sleep(1000);
  clearInterval(pinger);
  record({ test: 'idle', minutes: Math.round((Date.now() - started) / 60_000), pongs, closedEarly: socket.closed });
  socket.ws.close();
  await sleep(1000);
}

async function usage(since) {
  const { CF_ACCOUNT_ID: account, CF_API_TOKEN: apiToken } = process.env;
  if (!account || !apiToken) { console.error('Set CF_ACCOUNT_ID and CF_API_TOKEN'); process.exit(2); }
  const query = async (text) => {
    const res = await fetch('https://api.cloudflare.com/client/v4/graphql', { method: 'POST',
      headers: { authorization: `Bearer ${apiToken}`, 'content-type': 'application/json' }, body: JSON.stringify({ query: text }) });
    return res.json();
  };
  // Field names beyond these aren't documented, so print what the schema offers as well.
  const shape = await query('{ __type(name: "AccountDurableObjectsPeriodicGroupsSum") { fields { name } } }');
  const invocations = await query(`{ viewer { accounts(filter: {accountTag: "${account}"}) {
    durableObjectsInvocationsAdaptiveGroups(filter: {date_geq: "${since}"}, limit: 100) { sum { requests responseBodySize } dimensions { date } }
    durableObjectsPeriodicGroups(filter: {date_geq: "${since}"}, limit: 100) { sum { cpuTime } dimensions { date } }
    durableObjectsStorageGroups(filter: {date_geq: "${since}"}, limit: 1) { max { storedBytes } }
  } } }`);
  record({ test: 'usage', since, periodicFields: shape?.data?.__type?.fields?.map((f) => f.name), result: invocations });
}

const [mode, value] = process.argv.slice(2);
try {
  if (mode === 'usage') await usage(value);
  else if (mode === 'secret') {
    if (!value?.startsWith('wss://')) { console.error('secret needs the wss:// address of the staging Worker'); process.exit(2); }
    const secret = randomBytes(24).toString('hex');
    writeFileSync('.measure.env', `AURALIS_MEASURE_URL=${value}
AURALIS_MEASURE_TOKEN=staging:${secret}:measure
`);
    process.stdout.write(secret);
  } else {
    needTarget();
    if (mode === 'check') await check();
    else if (mode === 'messages') await send(Number(value), false);
    else if (mode === 'to-limit') await send(Number(value ?? 3_000_000), true);
    else if (mode === 'connects') await connects(Number(value));
    else if (mode === 'idle') await idle(Number(value));
    else { console.error('check | messages <n> | to-limit [n] | connects <n> | idle <minutes> | usage <date>'); process.exit(2); }
  }
} catch (error) {
  // Being refused is often the result being measured, so it is recorded like any other.
  record({ test: mode, refused: error.status ?? error.message, body: error.body });
  process.exit(1);
}
process.exit(0);
