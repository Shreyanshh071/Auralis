// Cloudflare adapter: the Worker checks the token before anything reaches an account, and each
// account is one Durable Object, so one account's traffic can't reach another's. Everything that
// decides anything is in core.js.
import { DurableObject } from 'cloudflare:workers';
import { googleKeys, stagingIdentity, verifyToken } from './auth.js';
import { MAX_DEVICES, MAX_FRAME_BYTES, MAX_SOCKETS, RateLimit, UNSAVED_VERSIONS, decideUpdate, intentAccepted, mustSave,
  recoverSession, validDevice, validDeviceId, validQueue } from './core.js';

const CLOSE_REPLACED = 4409;
const CLOSE_EXPIRED = 4401;
const CLOSE_TOO_FAST = 4429;
const CLOSE_TOO_LARGE = 4413;
const REFUSALS_BEFORE_CLOSE = 200;
const MAX_ATTACHED_SESSION_BYTES = 12 * 1024;
const SEEN_SAVE_MS = 6 * 3_600_000;
const FORGET_DEVICE_MS = 30 * 86_400_000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/healthz') return new Response('ok');
    if (url.pathname !== '/v1/connect') return new Response('not found', { status: 404 });
    if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return new Response('websocket only', { status: 426 });
    const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';
    if (!(await env.CONNECT_UPGRADES.limit({ key: ip })).success) {
      return new Response('too many upgrades', { status: 429, headers: { 'Retry-After': '60' } });
    }
    const device = url.searchParams.get('device');
    if (!validDeviceId(device)) return new Response('bad device', { status: 400 });
    const token = /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '')?.[1];
    const who = await authenticate(token, env, url);
    if (!who) return new Response('unauthorized', { status: 401 });
    const account = env.CONNECT.get(env.CONNECT.idFromName(who.uid));
    // Only these headers, set here, reach the account: nothing a client sent is believed.
    return account.fetch('https://account/socket', { headers: { upgrade: 'websocket', 'x-uid': who.uid, 'x-device': device,
      'x-expires': String(who.expiresAtMs) } });
  },
};

async function authenticate(token, env, url) {
  // Exists only for local tests: wrangler.toml never sets it, and it refuses any non-local host.
  if (env.DEV_AUTH === 'local-tests-only' && (url.hostname === '127.0.0.1' || url.hostname === 'localhost')) {
    const dev = /^dev:([A-Za-z0-9_-]+)(?::(-?\d+))?$/.exec(token ?? '');
    if (dev) return { uid: dev[1], expiresAtMs: Date.now() + Number(dev[2] ?? 3_600_000) };
  }
  if (!token) return null;
  const staging = stagingIdentity(token, env.STAGING_SECRET);
  if (staging) return staging;
  try {
    return await verifyToken(token, env.FIREBASE_PROJECT_ID, await googleKeys());
  } catch {
    return null;
  }
}

export class ConnectAccount extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)');
    // Keepalive that never wakes this object. WebSocket protocol pings closed sockets on staging.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    this.loaded = false;
    this.limits = new Map();
    this.upgrades = new RateLimit(64, 1);
    // Staging diagnostics: a new id means the object left memory since the last message.
    this.instance = crypto.randomUUID();
    this.handled = 0;
  }

  read(key) {
    const rows = this.ctx.storage.sql.exec('SELECT v FROM kv WHERE k = ?', key).toArray();
    return rows.length ? JSON.parse(rows[0].v) : null;
  }

  write(key, value) {
    this.ctx.storage.sql.exec('INSERT INTO kv (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v', key, JSON.stringify(value));
  }

  /**
   * Hibernation loses memory, so unsaved state lives on the writer's socket attachment, which costs
   * no storage write. Waking takes the newest of storage and every open socket's attachment.
   */
  load() {
    if (this.loaded) return;
    const saved = this.read('session');
    let newest = null;
    let intent = this.read('intent');
    for (const socket of this.ctx.getWebSockets()) {
      const attached = socket.deserializeAttachment();
      if (attached?.session && attached.session.version > (newest?.version ?? -1)) newest = attached.session;
      if (attached?.intent && attached.intent.version >= (intent?.version ?? -1)) intent = attached.intent;
    }
    if (newest && newest.version >= (saved?.version ?? 0)) this.session = newest;
    else this.session = this.ctx.getWebSockets().length === 0 ? recoverSession(saved) : saved;
    this.savedVersion = saved?.version ?? 0;
    this.queue = this.read('queue');
    this.intent = intent;
    this.devices = new Map();
    for (const row of this.ctx.storage.sql.exec("SELECT v FROM kv WHERE k LIKE 'device:%'").toArray()) {
      const device = JSON.parse(row.v);
      this.devices.set(device.id, device);
    }
    this.loaded = true;
  }

  async fetch(request) {
    const uid = request.headers.get('x-uid');
    const device = request.headers.get('x-device');
    const expiresAtMs = Number(request.headers.get('x-expires'));
    const now = Date.now();
    if (!(expiresAtMs > now)) return new Response('expired', { status: 401 });
    if (!this.upgrades.allow(now)) {
      return new Response('too many account upgrades', { status: 429, headers: { 'Retry-After': '1' } });
    }
    this.load();
    const open = this.sockets();
    const others = open.filter((socket) => socket.deserializeAttachment()?.device !== device);
    for (const socket of open) {
      if (!others.includes(socket)) {
        socket.serializeAttachment({ ...socket.deserializeAttachment(), replaced: true });
        socket.close(CLOSE_REPLACED, 'replaced');
      }
    }
    if (others.length >= MAX_SOCKETS) return new Response('too many devices', { status: 429 });
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    pair[1].serializeAttachment({ uid, device, expiresAtMs });
    this.sendState(pair[1]);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  sendState(socket, id) {
    this.send(socket, { t: 'state', r: id, s: this.session ?? null, q: this.queue ?? null, dv: this.deviceList(), now: Date.now() });
  }

  send(socket, frame) {
    try { socket.send(JSON.stringify(frame)); } catch { /* it closed: its close event cleans up */ }
  }

  /** Closing sockets stay listed until their client answers, which a vanished client never does. */
  sockets() {
    const now = Date.now();
    return this.ctx.getWebSockets().filter((socket) => {
      if (socket.readyState !== WebSocket.READY_STATE_OPEN) return false;
      const attachment = socket.deserializeAttachment();
      if (!attachment || attachment.replaced) return false;
      if (now < attachment.expiresAtMs) return true;
      const device = this.devices.get(attachment.device);
      if (device) device.online = false;
      socket.close(CLOSE_EXPIRED, 'expired');
      return false;
    });
  }

  others(except) {
    return this.sockets().filter((s) => s !== except);
  }

  deviceList() {
    const now = Date.now();
    const connected = new Set(this.sockets().map((s) => s.deserializeAttachment()?.device));
    return [...this.devices.values()].map((d) => (connected.has(d.id) ? { ...d, seenAt: now } : d));
  }

  async webSocketMessage(socket, message) {
    const attachment = socket.deserializeAttachment();
    if (!attachment || attachment.replaced || socket.readyState !== WebSocket.READY_STATE_OPEN) return;
    const size = typeof message === 'string' ? message.length : message.byteLength;
    if (size > MAX_FRAME_BYTES) return socket.close(CLOSE_TOO_LARGE, 'too large');
    const now = Date.now();
    if (now >= attachment.expiresAtMs) return socket.close(CLOSE_EXPIRED, 'expired');
    let limit = this.limits.get(socket);
    if (!limit) { limit = new RateLimit(); limit.at = now; this.limits.set(socket, limit); }
    if (!limit.allow(now)) {
      if (limit.refused >= REFUSALS_BEFORE_CLOSE) return socket.close(CLOSE_TOO_FAST, 'too fast');
      return this.send(socket, { t: 'err', code: 'slow', retryMs: 1000 });
    }
    let frame;
    try { frame = JSON.parse(message); } catch { return this.send(socket, { t: 'err', code: 'bad-frame' }); }
    if (!frame || typeof frame !== 'object' || Array.isArray(frame)) return this.send(socket, { t: 'err', code: 'bad-frame' });
    this.load();
    const r = frame.r;
    this.handled += 1;
    if (frame.t === 'stats' && this.env.STAGING_SECRET) {
      return this.send(socket, { t: 'stats', r, instance: this.instance, handled: this.handled, sockets: this.sockets().length, now });
    }
    switch (frame.t) {
      case 'update': return this.update(socket, attachment, frame, now);
      case 'fetch': return this.sendState(socket, r);
      case 'hello': return this.presence(socket, attachment, frame, now);
      case 'intent':
        if (!intentAccepted(this.intent, frame.i, now)) return this.send(socket, { t: 'err', r, code: 'refused' });
        this.intent = frame.i;
        socket.serializeAttachment({ ...attachment, intent: frame.i });
        return this.send(socket, { t: 'ok', r, now });
      case 'get-intent': return this.send(socket, { t: 'intent', r, i: this.intent ?? null, now });
      case 'remove': return this.removeDevice(socket, frame, now);
      default: return this.send(socket, { t: 'err', r, code: 'unknown' });
    }
  }

  update(socket, attachment, frame, now) {
    const r = frame.r;
    if (frame.q !== undefined && frame.q !== null && !validQueue(frame.q)) return this.send(socket, { t: 'err', r, code: 'invalid' });
    const before = this.session;
    const decision = decideUpdate(before, frame.base, frame.s, attachment.device, now);
    if (decision.result === 'conflict') return this.send(socket, { t: 'conflict', r, s: before ?? null, q: this.queue ?? null, now });
    if (decision.result === 'refused') return this.send(socket, { t: 'err', r, code: decision.code });
    const session = decision.session;
    this.session = session;
    if (frame.q) { this.queue = frame.q; this.write('queue', frame.q); }
    const fits = JSON.stringify(session).length <= MAX_ATTACHED_SESSION_BYTES;
    if (!mustSave(before, session) && fits && session.version - this.savedVersion < UNSAVED_VERSIONS) {
      socket.serializeAttachment({ ...attachment, session });
    } else {
      this.save(session);
      if (attachment.session) { const { session: _, ...rest } = attachment; socket.serializeAttachment(rest); }
    }
    this.send(socket, { t: 'ok', r, v: session.version, now });
    const push = JSON.stringify({ t: 'session', s: session, q: frame.q ?? undefined, now });
    for (const other of this.others(socket)) { try { other.send(push); } catch { /* closing */ } }
  }

  save(session) {
    this.write('session', session);
    this.savedVersion = session.version;
  }

  presence(socket, attachment, frame, now) {
    const device = frame.d;
    if (!validDevice(device, attachment.device)) return this.send(socket, { t: 'err', r: frame.r, code: 'invalid' });
    const known = this.devices.get(device.id);
    if (!known && this.devices.size >= MAX_DEVICES) this.forgetOldest(now);
    const stored = { ...device, seenAt: now, nickname: device.nickname ?? null };
    const changed = !known || ['name', 'nickname', 'type', 'appVersion', 'canPlay', 'online', 'platform'].some((k) => known[k] !== stored[k]);
    this.devices.set(device.id, stored);
    // Being connected is the heartbeat: storage only hears about real changes.
    if (changed || now - known.seenAt > SEEN_SAVE_MS) this.write(`device:${device.id}`, stored);
    this.send(socket, { t: 'ok', r: frame.r, now });
    if (changed) this.pushDevices(socket);
  }

  forgetOldest(now) {
    for (const d of [...this.devices.values()].sort((a, b) => a.seenAt - b.seenAt)) {
      if (this.devices.size < MAX_DEVICES) return;
      if (now - d.seenAt > FORGET_DEVICE_MS || this.devices.size >= MAX_DEVICES) this.dropDevice(d.id);
    }
  }

  dropDevice(id) {
    this.devices.delete(id);
    this.ctx.storage.sql.exec('DELETE FROM kv WHERE k = ?', `device:${id}`);
  }

  removeDevice(socket, frame, now) {
    if (!validDeviceId(frame.id)) return this.send(socket, { t: 'err', r: frame.r, code: 'invalid' });
    this.dropDevice(frame.id);
    this.send(socket, { t: 'ok', r: frame.r, now });
    this.pushDevices(socket);
  }

  pushDevices(except) {
    const frame = { t: 'devices', dv: this.deviceList(), now: Date.now() };
    for (const other of this.others(except)) this.send(other, frame);
  }

  webSocketClose(socket) {
    // Staging showed clean closes left hanging without this, so always finish the handshake.
    try { socket.close(1000, 'closed'); } catch { /* already closed */ }
    this.gone(socket);
  }

  webSocketError(socket) { this.gone(socket); }

  gone(socket) {
    this.limits.delete(socket);
    this.load();
    const remaining = this.others(socket);
    const attachment = socket.deserializeAttachment();
    // The newest state may live only on this socket.
    if (attachment?.session && attachment.session.version > (this.session?.version ?? -1)) this.session = attachment.session;
    if (attachment?.intent && attachment.intent.version >= (this.intent?.version ?? -1)) this.intent = attachment.intent;
    if (this.session && this.session.version > this.savedVersion &&
      (remaining.length === 0 || attachment?.session?.version === this.session.version)) this.save(this.session);
    if (attachment?.intent && attachment.intent === this.intent) this.write('intent', this.intent);
    else if (remaining.length === 0 && this.intent) this.write('intent', this.intent);
    const device = this.devices.get(attachment?.device);
    if (device && !attachment.replaced && !remaining.some((other) => other.deserializeAttachment()?.device === device.id)) {
      device.seenAt = Date.now();
      if (device.seenAt >= attachment.expiresAtMs) device.online = false;
      this.write(`device:${device.id}`, device);
    }
    this.pushDevices(socket);
  }
}
