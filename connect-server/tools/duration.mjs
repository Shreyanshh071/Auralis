// Small controlled tests on staging, using the staging-only `stats` frame.
//   node tools/duration.mjs hibernation   how long the object stays awake after a message
//   node tools/duration.mjs isolated <count> <gap-seconds>   single messages with idle time between
//   node tools/duration.mjs closes        3 clean, 7 abrupt and 13 server-replaced closes
// Any combination of those counts adds up differently, so the rise in Cloudflare's error count
// shows which kind of close it counts as an error.
import { appendFileSync, readFileSync } from 'node:fs';
import WebSocket from 'ws';

const env = Object.fromEntries(readFileSync('.measure.env', 'utf8').split(String.fromCharCode(10)).filter(Boolean)
  .map((l) => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1).trim()]; }));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function record(entry) {
  const line = { at: new Date().toISOString(), ...entry };
  console.log(JSON.stringify(line));
  appendFileSync('measure-results.jsonl', `${JSON.stringify(line)}\n`);
}

function open(device) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${env.AURALIS_MEASURE_URL}/v1/connect?device=${device}`, { headers: { authorization: `Bearer ${env.AURALIS_MEASURE_TOKEN}` } });
    let answer = null;
    ws.on('message', (d) => { const f = JSON.parse(d.toString()); if (f.t === 'stats' && answer) { const a = answer; answer = null; a(f); } if (f.t === 'state') resolve({ ws, stats }); });
    ws.once('error', reject);
    ws.once('unexpected-response', (_, res) => reject(new Error(`HTTP ${res.statusCode}`)));
    const stats = () => new Promise((done, fail) => {
      answer = done;
      ws.send(JSON.stringify({ t: 'stats', r: 1 }));
      setTimeout(() => fail(new Error('no answer')), 8000);
    });
  });
}

const closed = (ws) => new Promise((resolve) => { if (ws.readyState === WebSocket.CLOSED) resolve(); ws.once('close', resolve); setTimeout(resolve, 4000); });

async function hibernation() {
  const { ws, stats } = await open('duration-gaps');
  let last = await stats();
  for (const gap of [1, 3, 5, 8, 10, 12, 15, 20, 30, 60, 90]) {
    await sleep(gap * 1000);
    const at = performance.now();
    const now = await stats();
    record({ test: 'gap', idleSeconds: gap, sameInstance: now.instance === last.instance, roundTripMs: Math.round(performance.now() - at), handled: now.handled, sockets: now.sockets });
    last = now;
  }
  ws.close(1000);
  await closed(ws);
}

async function isolated(count, gapSeconds) {
  let socket = await open('duration-isolated');
  let closeCode = null;
  const watch = () => socket.ws.once('close', (code) => { closeCode = code; });
  watch();
  const started = Date.now();
  let last = await socket.stats();
  let woke = 0;
  let reconnects = 0;
  let silent = 0;
  const trips = [];
  for (let i = 0; i < count; i += 1) {
    await sleep(gapSeconds * 1000);
    if (socket.ws.readyState !== WebSocket.OPEN) {
      reconnects += 1;
      record({ test: 'socket-closed', afterMessage: i, afterSeconds: Math.round((Date.now() - started) / 1000), code: closeCode });
      socket = await open('duration-isolated');
      watch();
    }
    const at = performance.now();
    try {
      const now = await socket.stats();
      trips.push(Math.round(performance.now() - at));
      if (now.instance !== last.instance) woke += 1;
      last = now;
    } catch {
      // What the client's reply timeout catches: the socket looks open but nothing comes back.
      silent += 1;
      record({ test: 'silent-socket', afterMessage: i, afterSeconds: Math.round((Date.now() - started) / 1000), readyState: socket.ws.readyState });
      socket.ws.terminate();
      await sleep(200);
    }
  }
  trips.sort((a, b) => a - b);
  record({ test: 'isolated', messages: count, gapSeconds, answered: trips.length, silent, freshInstances: woke, reconnects, medianMs: trips[Math.floor(trips.length / 2)], maxMs: trips.at(-1) });
  socket.ws.close(1000);
  await closed(socket.ws);
}

async function closes() {
  for (let i = 0; i < 3; i += 1) { const { ws } = await open(`close-clean-${i}`); await sleep(300); ws.close(1000); await closed(ws); }
  record({ test: 'closes', kind: 'clean', count: 3 });
  await sleep(15_000);
  for (let i = 0; i < 7; i += 1) { const { ws } = await open(`close-abrupt-${i}`); await sleep(300); ws.terminate(); await sleep(500); }
  record({ test: 'closes', kind: 'abrupt', count: 7 });
  await sleep(15_000);
  let previous = await open('close-replaced');
  for (let i = 0; i < 13; i += 1) { const next = await open('close-replaced'); await closed(previous.ws); previous = next; await sleep(300); }
  previous.ws.close(1000);
  await closed(previous.ws);
  record({ test: 'closes', kind: 'server-replaced', count: 13 });
}

const [mode, a, b] = process.argv.slice(2);
if (mode === 'hibernation') await hibernation();
else if (mode === 'isolated') await isolated(Number(a), Number(b));
else if (mode === 'closes') await closes();
else { console.error('hibernation | isolated <count> <gap-seconds> | closes'); process.exit(2); }
process.exit(0);
