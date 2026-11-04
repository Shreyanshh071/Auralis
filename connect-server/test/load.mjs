// Holds thousands of real sockets on the local runtime and measures what the protocol actually
// costs. This measures our traffic and the server code, not Cloudflare's network.
//   node test/load.mjs <devices> <seconds> [writes a second, default the 50 a device-hour in the plan]
import { connect, deviceDoc, sessionDoc, startServer, stopServer } from './harness.mjs';

const devices = Number(process.argv[2] ?? 2000);
const seconds = Number(process.argv[3] ?? 30);
const PORT = 8797;
const server = await startServer(PORT);
const run = `load${Date.now()}`;

const clients = [];
const startConnect = Date.now();
const failures = { connect: 0 };
for (let i = 0; i < devices; i += 1) {
  // One account in ten has a second device, a realistic share of multi-device listeners.
  const multi = i % 20 >= 18;
  const uid = multi ? `${run}m${Math.floor(i / 20)}` : `${run}s${i}`;
  const id = multi ? `d${i % 2}` : 'd0';
  const batch = connect(PORT, uid, id).then(async (c) => {
    c.ws.setMaxListeners?.(0);
    await c.next((f) => f.t === 'state', 20_000);
    await c.call({ t: 'hello', d: deviceDoc(id) }, 20_000);
    clients.push({ c, id, version: 0, multi });
  }).catch(() => { failures.connect += 1; });
  if (i % 50 === 49) await batch;
}
await new Promise((r) => setTimeout(r, 1500));
console.log(`connected ${clients.length}/${devices} in ${Date.now() - startConnect}ms, ${failures.connect} failed`);

let sent = 0;
let bytes = 0;
const latencies = [];
const sessionBytes = JSON.stringify(sessionDoc()).length;
const started = Date.now();
const rate = Number(process.argv[4] ?? Math.ceil(devices * 50 / 3600));
let conflicts = 0;
while (Date.now() - started < seconds * 1000) {
  const tickStart = Date.now();
  const burst = [];
  for (let i = 0; i < Math.ceil(rate / 10); i += 1) {
    const entry = clients[Math.floor(Math.random() * clients.length)];
    const s = sessionDoc({ version: entry.version + 1, positionMs: sent, updatedBy: entry.id, ownerId: entry.id });
    const at = performance.now();
    sent += 1;
    bytes += sessionBytes;
    burst.push(entry.c.call({ t: 'update', base: entry.version, s }, 8000).then((reply) => {
      latencies.push(performance.now() - at);
      if (reply.t === 'ok') entry.version = reply.v;
      else if (reply.t === 'conflict') { entry.version = reply.s?.version ?? 0; conflicts += 1; }
    }).catch(() => { failures.send = (failures.send ?? 0) + 1; }));
  }
  await Promise.all(burst);
  await new Promise((r) => setTimeout(r, Math.max(0, 100 - (Date.now() - tickStart))));
}
latencies.sort((a, b) => a - b);
const at = (p) => latencies[Math.floor(latencies.length * p)]?.toFixed(1);
console.log(`${sent} writes in ${seconds}s (${(sent / seconds).toFixed(0)}/s), p50 ${at(0.5)}ms, p95 ${at(0.95)}ms, p99 ${at(0.99)}ms, conflicts ${conflicts}, failed ${failures.send ?? 0}`);
const closes = {};
for (const e of clients) if (e.c.closed !== null) closes[e.c.closed] = (closes[e.c.closed] ?? 0) + 1;
console.log(`session frame ${sessionBytes} bytes; connections still open ${clients.filter((e) => e.c.closed === null).length}; closed by code ${JSON.stringify(closes)}`);
clients.forEach((e) => e.c.close());
await stopServer(server);
process.exit(0);
