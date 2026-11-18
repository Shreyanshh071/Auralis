// Listen Together rooms: the same room, members and song requests the app kept in Firestore, with
// the same rules (firestore.rules → /rooms), held by one Durable Object per room. Everything that
// decides anything is here so it can be tested without a server.

export const ROOM_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
export const MAX_FRAME_BYTES = 64 * 1024;
export const MAX_ROOM_BYTES = 48 * 1024;
export const MAX_MEMBER_BYTES = 4 * 1024;
export const MAX_REC_BYTES = 4 * 1024;
export const MAX_MEMBERS = 30;
export const MAX_RECS = 100;
export const REC_STATUSES = ['pending', 'accepted', 'played', 'declined'];

/** Fields the server owns: a client never sets them. */
const ROOM_OWNED = ['id', 'code', 'hostId', 'serverUpdatedAt', 'expireAt'];
const MEMBER_OWNED = ['id', 'isHost', 'serverSeen', 'expireAt'];
/** Fields that change every few seconds. They ride on the writer's socket, not in storage. */
export const ROOM_VOLATILE = ['isPlaying', 'playbackPosition', 'playbackRate', 'updatedAt', 'serverUpdatedAt', 'seekVersion'];
export const MEMBER_VOLATILE = ['lastSeen', 'serverSeen'];

export const validRoomCode = (code) => typeof code === 'string' && code.length === 6 && [...code].every((c) => ROOM_ALPHABET.includes(c));

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const bytes = (v) => JSON.stringify(v).length;
const fail = (code) => ({ error: code });

export const emptyRoom = () => ({ room: null, members: {}, recs: {} });

/** Splits [fields] into what must be stored and what may ride on the socket. */
export function split(fields, volatile) {
  const stored = {};
  const vol = {};
  for (const [k, v] of Object.entries(fields)) (volatile.includes(k) ? vol : stored)[k] = v;
  return { stored, vol };
}

/** True when applying [fields] to [doc] changes something outside [volatile]. */
export function changesStored(doc, fields, volatile) {
  return Object.entries(fields).some(([k, v]) => !volatile.includes(k) && JSON.stringify(doc?.[k] ?? null) !== JSON.stringify(v ?? null));
}

function cleanMember(member, uid, isHost, now) {
  if (!isObject(member)) return null;
  const clean = { ...member };
  for (const k of MEMBER_OWNED) delete clean[k];
  const doc = { ...clean, id: uid, isHost, serverSeen: now };
  return bytes(doc) <= MAX_MEMBER_BYTES ? doc : null;
}

export function create(state, uid, code, room, member, now) {
  if (state.room) return fail('exists');
  if (!isObject(room)) return fail('invalid');
  const clean = { ...room };
  for (const k of ROOM_OWNED) delete clean[k];
  const doc = { ...clean, id: code, code, hostId: uid, status: 'active', serverUpdatedAt: now };
  if (bytes(doc) > MAX_ROOM_BYTES) return fail('too-large');
  const host = cleanMember(member, uid, true, now);
  if (!host) return fail('invalid');
  return { state: { room: doc, members: { [uid]: host }, recs: {} }, changed: ['room', 'members', 'recs'] };
}

export function join(state, uid, member, now) {
  if (!state.room) return fail('not-found');
  if (state.room.status !== 'active') return fail('closed');
  const known = state.members[uid];
  if (!known && Object.keys(state.members).length >= MAX_MEMBERS) return fail('full');
  const doc = cleanMember({ ...(known ?? {}), ...(isObject(member) ? member : {}) }, uid, state.room.hostId === uid, now);
  if (!doc) return fail('invalid');
  return { state: { ...state, members: { ...state.members, [uid]: doc } }, changed: ['members'] };
}

/** The host updates the room. A `status` of "closed" closes it. */
export function updateRoom(state, uid, set, now) {
  if (!state.room) return fail('not-found');
  if (state.room.hostId !== uid) return fail('denied');
  if (!isObject(set)) return fail('invalid');
  const clean = { ...set };
  for (const k of ROOM_OWNED) delete clean[k];
  if ('status' in clean && !['active', 'closed'].includes(clean.status)) return fail('invalid');
  const room = { ...state.room, ...clean };
  if ('updatedAt' in clean) room.serverUpdatedAt = now;
  if (bytes(room) > MAX_ROOM_BYTES) return fail('too-large');
  return { state: { ...state, room }, changed: ['room'], stored: changesStored(state.room, clean, ROOM_VOLATILE) };
}

/** A member updates their own record. `network` merges, so slow and readyFor don't overwrite each other. */
export function updateMember(state, uid, set, now) {
  if (!state.room) return fail('not-found');
  const known = state.members[uid];
  if (!known) return fail('not-member');
  if (!isObject(set)) return fail('invalid');
  const clean = { ...set };
  for (const k of MEMBER_OWNED) delete clean[k];
  if (isObject(clean.network)) clean.network = { ...(isObject(known.network) ? known.network : {}), ...clean.network };
  const doc = { ...known, ...clean };
  if ('lastSeen' in clean) doc.serverSeen = now;
  if (bytes(doc) > MAX_MEMBER_BYTES) return fail('too-large');
  return { state: { ...state, members: { ...state.members, [uid]: doc } }, changed: ['members'], stored: changesStored(known, clean, MEMBER_VOLATILE) };
}

/** A member leaves. The host leaving closes the room for everyone. */
export function leave(state, uid) {
  if (!state.room) {
    if (!state.members[uid]) return fail('not-member');
    const { [uid]: _, ...members } = state.members;
    return { state: { ...state, members }, changed: ['members'] };
  }
  if (state.room.hostId === uid) return { state: emptyRoom(), changed: ['room', 'members', 'recs'], closed: true };
  if (!state.members[uid]) return fail('not-member');
  const { [uid]: _, ...members } = state.members;
  return { state: { ...state, members }, changed: ['members'] };
}

const isMember = (state, uid) => Boolean(state.members[uid]) || state.room?.hostId === uid;

export function recommend(state, uid, id, rec, now) {
  if (!state.room) return fail('not-found');
  if (!isMember(state, uid)) return fail('not-member');
  if (!isObject(rec) || !isObject(rec.track)) return fail('invalid');
  if (Object.keys(state.recs).length >= MAX_RECS) return fail('full');
  const doc = {
    id, track: rec.track, recommendedByUid: uid,
    recommendedByName: typeof rec.recommendedByName === 'string' ? rec.recommendedByName.slice(0, 80) : '',
    note: typeof rec.note === 'string' ? rec.note.slice(0, 280) : '',
    upvotes: [uid], createdAt: now, status: 'pending',
  };
  if (bytes(doc) > MAX_REC_BYTES) return fail('too-large');
  return { state: { ...state, recs: { ...state.recs, [id]: doc } }, changed: ['recs'], id };
}

export function upvote(state, uid, id) {
  if (!isMember(state, uid)) return fail('not-member');
  const rec = state.recs[id];
  if (!rec) return fail('not-found');
  const upvotes = rec.upvotes.includes(uid) ? rec.upvotes.filter((u) => u !== uid) : [...rec.upvotes, uid];
  return { state: { ...state, recs: { ...state.recs, [id]: { ...rec, upvotes } } }, changed: ['recs'] };
}

export function setRecStatus(state, uid, id, status) {
  if (state.room?.hostId !== uid) return fail('denied');
  const rec = state.recs[id];
  if (!rec) return fail('not-found');
  if (!REC_STATUSES.includes(status)) return fail('invalid');
  return { state: { ...state, recs: { ...state.recs, [id]: { ...rec, status } } }, changed: ['recs'] };
}

export function deleteRec(state, uid, id) {
  const rec = state.recs[id];
  if (!rec) return fail('not-found');
  if (rec.recommendedByUid !== uid && state.room?.hostId !== uid) return fail('denied');
  const { [id]: _, ...recs } = state.recs;
  return { state: { ...state, recs }, changed: ['recs'] };
}

/** What a client sees: members and song requests only once it is in the room. */
export function view(state, uid) {
  const inside = isMember(state, uid);
  return {
    room: state.room,
    members: inside ? Object.values(state.members) : null,
    recs: inside ? Object.values(state.recs) : null,
  };
}
