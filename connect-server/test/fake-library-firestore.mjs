// REST fixture: no real Firebase credentials or user data.
import { createServer } from 'node:http';
const encode = (v) => v == null ? { nullValue: null } : Array.isArray(v) ? { arrayValue: { values: v.map(encode) } }
 : typeof v === 'object' ? { mapValue: { fields: fields(v) } } : typeof v === 'string' ? { stringValue: v }
 : typeof v === 'boolean' ? { booleanValue: v } : { integerValue: String(v) };
const fields = (v) => Object.fromEntries(Object.entries(v).map(([k, x]) => [k, encode(x)]));
const decode = (v) => 'nullValue' in v ? null : 'mapValue' in v ? plain(v.mapValue.fields) : 'arrayValue' in v
 ? (v.arrayValue.values ?? []).map(decode) : v.stringValue ?? v.booleanValue ?? Number(v.integerValue);
const plain = (f) => Object.fromEntries(Object.entries(f ?? {}).map(([k, v]) => [k, decode(v)]));
export async function fakeFirestore() {
 const docs = new Map(); let version = 0;
 const api = { docs, calls: [], commits: 0, status: 0, conflict: null };
 api.put = (path, data) => docs.set(path, { data: structuredClone(data), updateTime: `2026-11-19T00:00:00.${String(++version).padStart(9, '0')}Z` });
 api.data = (path) => structuredClone(docs.get(path)?.data);
 const prefix = '/v1/projects/demo-auralis/databases/(default)';
 const server = createServer(async (req, res) => {
  api.calls.push(req.url);
  const send = (status, body = {}) => { res.writeHead(status, { 'content-type': 'application/json' }); res.end(JSON.stringify(body)); };
  try {
   if (req.headers.authorization !== 'Bearer owner') return send(401);
   if (api.status) return send(api.status);
   const url = new URL(req.url, 'http://localhost');
   if (req.method === 'GET' && url.pathname === prefix) return send(200, { name: prefix.slice(4) });
   if (req.method === 'POST' && url.pathname === prefix + '/documents:commit') {
    let raw = ''; for await (const b of req) raw += b;
    const { writes } = JSON.parse(raw);
    if (api.conflict) { const fn = api.conflict; api.conflict = null; fn(); return send(409); }
    for (const w of writes) {
     const path = w.update.name.split('/documents/')[1]; const held = docs.get(path);
     if (w.currentDocument?.updateTime && w.currentDocument.updateTime !== held?.updateTime) return send(409);
     if (w.currentDocument?.exists === false && held) return send(409);
    }
    const results = [];
    for (const w of writes) {
     const path = w.update.name.split('/documents/')[1]; const value = plain(w.update.fields);
     api.put(path, w.updateMask ? { ...docs.get(path)?.data, ...value } : value);
     results.push({ updateTime: docs.get(path).updateTime });
    }
    api.commits++;
    if (api.loseAck) { api.loseAck = false; req.socket.destroy(); return; }
    return send(200, { writeResults: results });
   }
   const path = decodeURIComponent(url.pathname.split('/documents/')[1] ?? ''); const held = docs.get(path);
   if (req.method === 'DELETE') {
    if (!held) return send(404);
    if (url.searchParams.has('currentDocument.updateTime') && url.searchParams.get('currentDocument.updateTime') !== held.updateTime) return send(412);
    docs.delete(path); return send(200);
   }
   if (req.method === 'GET') return held ? send(200, { name: `${prefix.slice(4)}/documents/${path}`, fields: fields(held.data), updateTime: held.updateTime }) : send(404);
   send(400);
  } catch { send(500); }
 });
 await new Promise((r) => server.listen(0, '127.0.0.1', r));
 api.origin = `http://127.0.0.1:${server.address().port}`;
 api.close = () => new Promise((r) => server.close(r));
 return api;
}
