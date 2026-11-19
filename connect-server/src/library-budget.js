import { DurableObject } from 'cloudflare:workers';

// One tiny admission counter for legacy I/O, not a global library/data object. Existing old-app
// traffic remains outside this budget. Reservations are conservative, including failed attempts.
export class LibraryLegacyBudget extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS budget (day TEXT PRIMARY KEY, reads INTEGER, writes INTEGER) WITHOUT ROWID');
  }
  async fetch(request) {
    const body = await request.json();
    if (!['read', 'write'].includes(body.kind) || !Number.isSafeInteger(body.count) || body.count < 1 || body.count > 5)
      return new Response('invalid', { status: 400 });
    const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles' }).format(new Date());
    const sql = this.ctx.storage.sql;
    const held = sql.exec('SELECT reads, writes FROM budget WHERE day = ?', day).toArray()[0] ?? { reads: 0, writes: 0 };
    const limit = Number(body.kind === 'read' ? this.env.LIBRARY_LEGACY_READ_BUDGET ?? 5000 : this.env.LIBRARY_LEGACY_WRITE_BUDGET ?? 5000);
    const column = body.kind === 'read' ? 'reads' : 'writes';
    if (!(limit >= 0) || held[column] + body.count > limit) return new Response('budget', { status: 429 });
    held[column] += body.count;
    sql.exec('INSERT INTO budget VALUES (?, ?, ?) ON CONFLICT(day) DO UPDATE SET reads=excluded.reads, writes=excluded.writes', day, held.reads, held.writes);
    sql.exec('DELETE FROM budget WHERE day != ?', day);
    return Response.json({ allowed: true });
  }
}
