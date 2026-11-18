// Listen Together over WebSockets: one Durable Object per room code. Rules live in together-core.js.
// Storage only hears about real changes (a new song, someone joining, a request): playback ticks and
// heartbeats ride on the sender's socket attachment, which costs nothing, and are saved when it leaves.
import { DurableObject } from 'cloudflare:workers';
import { RateLimit } from './core.js';
import { MAX_FRAME_BYTES, MEMBER_VOLATILE, ROOM_VOLATILE, create, deleteRec, emptyRoom, join, leave, recommend, setRecStatus,
  updateMember, updateRoom, upvote, validRoomCode, view } from './together-core.js';

const CLOSE_REPLACED = 4409;
const CLOSE_EXPIRED = 4401;
const CLOSE_TOO_FAST = 4429;
const CLOSE_TOO_LARGE = 4413;
const CLOSE_ROOM_CLOSED = 4000;
const REFUSALS_BEFORE_CLOSE = 200;
/** A room nobody is connected to is deleted after this. */
const EMPTY_ROOM_MS = 30 * 60_000;
/** A room whose host has been gone this long is closed, like Firestore's stale-host check. */
const HOST_GONE_MS = 6 * 3_600_000;
const CHECK_MS = 3_600_000;

export async function togetherRoute(request, env, url, authenticate) {
  if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') return new Response('websocket only', { status: 426 });
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';
  if (env.TOGETHER_UPGRADES && !(await env.TOGETHER_UPGRADES.limit({ key: ip })).success) {
    return new Response('too many upgrades', { status: 429, headers: { 'Retry-After': '60' } });
  }
  const code = url.searchParams.get('room');
  if (!validRoomCode(code)) return new Response('bad room', { status: 400 });
  const token = /^Bearer (.+)$/.exec(request.headers.get('authorization') ?? '')?.[1];
  // Guests may be signed in anonymously, as they are with Firestore today.
  const who = await authenticate(token, env, url, { anonymous: true });
  if (!who) return new Response('unauthorized', { status: 401 });
  const room = env.TOGETHER.get(env.TOGETHER.idFromName(`room:${code}`));
  return room.fetch('https://room/socket', { headers: { upgrade: 'websocket', 'x-uid': who.uid, 'x-room': code, 'x-expires': String(who.expiresAtMs) } });
}

const pick = (doc, keys) => Object.fromEntries(keys.filter((k) => doc && k in doc).map((k) => [k, doc[k]]));

export class ListenRoom extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)');
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair('ping', 'pong'));
    this.loaded = false;
    this.limits = new Map();
    this.upgrades = new RateLimit(64, 2);
  }

  load() {
    if (this.loaded) return;
    const rows = this.ctx.storage.sql.exec("SELECT v FROM kv WHERE k = 'state'").toArray();
    const saved = rows.length ? JSON.parse(rows[0].v) : null;
    this.state = saved?.state ?? emptyRoom();
    this.code = saved?.code ?? null;
    this.savedAt = saved?.savedAt ?? 0;
    // Waking from hibernation: the newest playback and presence live on the senders' sockets.
    for (const socket of this.ctx.getWebSockets()) {
      const a = socket.deserializeAttachment();
      if (!a) continue;
      if (a.roomVol && a.roomVol.at > this.savedAt && this.state.room?.hostId === a.uid) {
        this.state.room = { ...this.state.room, ...a.roomVol.fields };
      }
      if (a.memberVol && a.memberVol.at > this.savedAt && this.state.members[a.uid]) {
        this.state.members[a.uid] = { ...this.state.members[a.uid], ...a.memberVol.fields };
      }
    }
    this.loaded = true;
  }

  save(now = Date.now()) {
    this.savedAt = now;
    this.ctx.storage.sql.exec("INSERT INTO kv (k, v) VALUES ('state', ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
      JSON.stringify({ state: this.state, code: this.code, savedAt: now }));
  }

  async fetch(request) {
    const uid = request.headers.get('x-uid');
    const expiresAtMs = Number(request.headers.get('x-expires'));
    const now = Date.now();
    if (!(expiresAtMs > now)) return new Response('expired', { status: 401 });
    if (!this.upgrades.allow(now)) return new Response('too many room upgrades', { status: 429, headers: { 'Retry-After': '1' } });
    this.load();
    this.code ??= request.headers.get('x-room');
    // One socket per person per room, as Firestore keyed members by uid.
    for (const socket of this.ctx.getWebSockets()) {
      const a = socket.deserializeAttachment();
      if (a?.uid === uid && !a.replaced) {
        socket.serializeAttachment({ ...a, replaced: true });
        try { socket.close(CLOSE_REPLACED, 'replaced'); } catch { /* closed */ }
      }
    }
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1]);
    pair[1].serializeAttachment({ uid, expiresAtMs });
    this.send(pair[1], { t: 'state', ...view(this.state, uid), now });
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  send(socket, frame) {
    try { socket.send(JSON.stringify(frame)); } catch { /* it closed: its close event cleans up */ }
  }

  sockets() {
    const now = Date.now();
    return this.ctx.getWebSockets().filter((socket) => {
      if (socket.readyState !== WebSocket.READY_STATE_OPEN) return false;
      const a = socket.deserializeAttachment();
      if (!a || a.replaced) return false;
      if (now < a.expiresAtMs) return true;
      try { socket.close(CLOSE_EXPIRED, 'expired'); } catch { /* closed */ }
      return false;
    });
  }

  /** Tells everyone connected what changed; members and requests only reach people in the room. */
  push(changed, now) {
    for (const socket of this.sockets()) {
      const v = view(this.state, socket.deserializeAttachment().uid);
      if (changed.includes('room')) this.send(socket, { t: 'room', room: v.room, now });
      if (changed.includes('members') && v.members) this.send(socket, { t: 'members', members: v.members, now });
      if (changed.includes('recs') && v.recs) this.send(socket, { t: 'recs', recs: v.recs, now });
    }
  }

  async webSocketMessage(socket, message) {
    const a = socket.deserializeAttachment();
    if (!a || a.replaced || socket.readyState !== WebSocket.READY_STATE_OPEN) return;
    const size = typeof message === 'string' ? message.length : message.byteLength;
    if (size > MAX_FRAME_BYTES) return socket.close(CLOSE_TOO_LARGE, 'too large');
    const now = Date.now();
    if (now >= a.expiresAtMs) return socket.close(CLOSE_EXPIRED, 'expired');
    let limit = this.limits.get(socket);
    if (!limit) { limit = new RateLimit(); limit.at = now; this.limits.set(socket, limit); }
    if (!limit.allow(now)) {
      if (limit.refused >= REFUSALS_BEFORE_CLOSE) return socket.close(CLOSE_TOO_FAST, 'too fast');
      return this.send(socket, { t: 'err', code: 'slow', retryMs: 1000 });
    }
    let f;
    try { f = JSON.parse(message); } catch { return this.send(socket, { t: 'err', code: 'bad-frame' }); }
    if (!f || typeof f !== 'object' || Array.isArray(f)) return this.send(socket, { t: 'err', code: 'bad-frame' });
    this.load();
    const uid = a.uid;
    const s = this.state;
    let result;
    switch (f.t) {
      case 'fetch': return this.send(socket, { t: 'state', r: f.r, ...view(s, uid), now });
      case 'create': result = create(s, uid, this.code, f.room, f.member, now); break;
      case 'join': result = join(s, uid, f.member, now); break;
      case 'room': result = updateRoom(s, uid, f.set, now); break;
      case 'member': result = updateMember(s, uid, f.set, now); break;
      case 'leave': result = leave(s, uid); break;
      case 'rec': result = recommend(s, uid, crypto.randomUUID().replace(/-/g, '').slice(0, 20), f.rec, now); break;
      case 'upvote': result = upvote(s, uid, f.id); break;
      case 'rec-status': result = setRecStatus(s, uid, f.id, f.status); break;
      case 'rec-delete': result = deleteRec(s, uid, f.id); break;
      default: return this.send(socket, { t: 'err', r: f.r, code: 'unknown' });
    }
    if (result.error) return this.send(socket, { t: 'err', r: f.r, code: result.error, now });
    this.state = result.state;
    if (result.closed) return this.close(socket, f.r, now);
    if (result.stored === false) {
      // Only playback or presence moved: keep it on this socket so hibernation can't lose it.
      const next = { ...a };
      if (f.t === 'room') next.roomVol = { fields: pick(this.state.room, ROOM_VOLATILE), at: now };
      if (f.t === 'member') next.memberVol = { fields: pick(this.state.members[uid], MEMBER_VOLATILE), at: now };
      socket.serializeAttachment(next);
    } else {
      this.save(now);
    }
    if (f.t === 'create') await this.ctx.storage.setAlarm(now + CHECK_MS);
    this.send(socket, { t: 'ok', r: f.r, now, id: result.id, state: f.t === 'join' || f.t === 'create' ? view(this.state, uid) : undefined });
    this.push(result.changed, now);
  }

  /** The host closed the room: everyone hears it ended, then nothing of it is kept. */
  async close(by, r, now) {
    this.send(by, { t: 'ok', r, now });
    for (const socket of this.sockets()) {
      this.send(socket, { t: 'room', room: null, now });
      try { socket.close(CLOSE_ROOM_CLOSED, 'room closed'); } catch { /* closed */ }
    }
    await this.forget();
  }

  async forget() {
    this.state = emptyRoom();
    this.savedAt = 0;
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS kv (k TEXT PRIMARY KEY, v TEXT NOT NULL)');
  }

  async alarm() {
    this.load();
    const now = Date.now();
    const open = this.sockets();
    if (!this.state.room) return this.forget();
    const host = this.state.members[this.state.room.hostId];
    const hostConnected = open.some((s) => s.deserializeAttachment().uid === this.state.room.hostId);
    if (open.length === 0 && now - this.savedAt >= EMPTY_ROOM_MS) return this.forget();
    if (!hostConnected && now - (host?.serverSeen ?? 0) >= HOST_GONE_MS) {
      for (const socket of open) {
        this.send(socket, { t: 'room', room: null, now });
        try { socket.close(CLOSE_ROOM_CLOSED, 'room closed'); } catch { /* closed */ }
      }
      return this.forget();
    }
    await this.ctx.storage.setAlarm(now + (open.length === 0 ? EMPTY_ROOM_MS : CHECK_MS));
  }

  webSocketClose(socket) {
    try { socket.close(1000, 'closed'); } catch { /* already closed */ }
    return this.gone(socket);
  }

  webSocketError(socket) { return this.gone(socket); }

  async gone(socket) {
    this.limits.delete(socket);
    this.load();
    const a = socket.deserializeAttachment();
    if (!this.state.room) return;
    const now = Date.now();
    // The newest playback or presence may live only on this socket.
    if ((a?.roomVol?.at ?? 0) > this.savedAt || (a?.memberVol?.at ?? 0) > this.savedAt) this.save(now);
    if (this.sockets().filter((s) => s !== socket).length === 0) {
      const due = await this.ctx.storage.getAlarm();
      if (!due || due > now + EMPTY_ROOM_MS) await this.ctx.storage.setAlarm(now + EMPTY_ROOM_MS);
    }
  }
}
