// A small comparison on staging. Each keepalive type has its own account, so its own object.
// Counts are chosen so one dashboard reading separates them: 41 protocol pings, 60 text pings and
// 120 connections add up differently in every combination, with a connection counted once or twice.
//   node tools/keepalive.mjs <minutes> <connections>
import { appendFileSync, readFileSync } from 'node:fs';
import WebSocket from 'ws';

const env = Object.fromEntries(readFileSync('.measure.env', 'utf8').split(String.fromCharCode(10)).filter(Boolean)
  .map((l) => { const i = l.indexOf('='); return [l.slice(0, i), l.slice(i + 1).trim()]; }));
const minutes = Number(process.argv[2] ?? 30);
const connections = Number(process.argv[3] ?? 120);
const secret = env.AURALIS_MEASURE_TOKEN.split(':')[1];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const started = Date.now();
const seconds = () => Math.round((Date.now() - started) / 1000);
function record(entry) {
  const line = { at: new Date().toISOString(), t: seconds(), ...entry };
  console.log(JSON.stringify(line));
  appendFileSync('measure-results.jsonl', `${JSON.stringify(line)}\n`);
}

const plan = [
  { name: 'protocol', account: 'ka-protocol', pingMs: 45_000 },
  { name: 'beside-protocol', account: 'ka-protocol' },
  { name: 'text', account: 'ka-text', textMs: 30_000 },
  { name: 'none', account: 'ka-none' },
  { name: 'control-echo', url: 'wss://ws.postman-echo.com/raw', pingMs: 45_000 },
];
const stats = Object.fromEntries(plan.map((p) => [p.name, { opened: 0, drops: [], pings: 0, pongs: 0 }]));

function run(p) {
  const s = stats[p.name];
  const connect = () => {
    const ws = p.url ? new WebSocket(p.url) : new WebSocket(`${env.AURALIS_MEASURE_URL}/v1/connect?device=${p.name}`,
      { headers: { authorization: `Bearer staging:${secret}:${p.account}` } });
    let timer = null;
    ws.on('open', () => {
      s.opened += 1;
      const every = p.pingMs ?? p.textMs;
      if (every) timer = setInterval(() => {
        if (ws.readyState !== WebSocket.OPEN) return;
        s.pings += 1;
        if (p.pingMs) ws.ping(); else ws.send('ping');
      }, every);
    });
    ws.on('pong', () => { s.pongs += 1; });
    ws.on('message', (d) => { if (d.toString() === 'pong') s.pongs += 1; });
    ws.on('error', (e) => record({ socket: p.name, event: 'error', message: e.message.slice(0, 80) }));
    ws.on('close', (code) => {
      clearInterval(timer);
      if (Date.now() - started >= minutes * 60_000) return;
      s.drops.push(seconds());
      record({ socket: p.name, event: 'dropped', code, pingsSoFar: s.pings, pongsSoFar: s.pongs });
      setTimeout(connect, 1500);
    });
    return ws;
  };
  return connect();
}

record({ event: 'start', minutes, connections });
plan.forEach(run);
await sleep(minutes * 60_000);
record({ event: 'keepalive-finished', stats });

let opened = 0;
for (let i = 0; i < connections; i += 1) {
  await new Promise((resolve) => {
    const ws = new WebSocket(`${env.AURALIS_MEASURE_URL}/v1/connect?device=count-${i % 8}`, { headers: { authorization: `Bearer staging:${secret}:ka-count` } });
    ws.on('message', () => { opened += 1; ws.close(1000); });
    ws.on('close', resolve);
    ws.on('error', resolve);
    setTimeout(resolve, 5000);
  });
  await sleep(100);
}
record({ event: 'connections-finished', asked: connections, opened });
process.exit(0);
