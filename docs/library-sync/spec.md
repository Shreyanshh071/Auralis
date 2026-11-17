# Account library sync on Cloudflare — technical specification

Date: 14 November 2026. Resolves decisions D1–D5 of `HANDOFF.md`. Nothing here is deployed.

Scope: playlists and their tracks, liked songs, saved albums, saved artists, pins and playlist
deletion records. Listening history, settings sync, Connect and Listen Together stay where they are.

## Summary of the design

- The **server** (`LibraryAccount`, one SQLite Durable Object per Firebase UID) is an ordered,
  durable store. It assigns revisions, deduplicates operations, enforces per-playlist
  preconditions, and owns each account's authority state.
- The **client** keeps deciding what a difference means. It runs the existing three-way
  `AccountLibraryMerge` against a local **mirror** of the server state, then sends the difference as
  operations. The merge code and its tests are unchanged.
- Devices are told about changes over a hibernating WebSocket. There is no polling.

## D1 — Authority ownership

**Decision:** the `LibraryAccount` object is the single system of record for an account's state.

| State | Meaning | Library writes accepted by |
| --- | --- | --- |
| `legacy` (default, also for an object never touched) | Firestore is the library | Firestore |
| `migrating` | Cutover or rollback in progress | Nobody. Clients keep edits locally. |
| `cloudflare` | The object is the library | `LibraryAccount` |

Each transition increments `epoch`. Clients send the epoch they believe in with every push; a
mismatch is refused with the current state, so a stale cache cannot write.

The migration job's checkpoints (`step`, source update time, source checksum, counts) are stored in
the same object, so the orchestrator is stateless and resumable.

Derived state, in a fixed order:

1. Firestore fence document `libraryAuthority/{uid}` (D4). Created after the object enters
   `migrating`, removed only after the object is back to `legacy`.
2. Discovery answers. They are read from the object on request, never stored elsewhere.

Fail-closed combinations: object `legacy` with a fence present means Firestore writes are refused
and the object refuses too, so clients queue; the orchestrator repairs it by resuming. Object
`cloudflare` without a fence cannot be reached by the orchestrator's ordering; if it is ever
observed, updated clients still follow the object and old clients could write Firestore, so the
`status` command reports it as an error to fix before any further cutover.

**Tradeoff:** discovery costs one Worker request and one Durable Object request per app start per
account. A central table would make cohort queries easier but adds a second source of truth.
Cohort tracking is left to the orchestrator's own input list.

## D2 — Discovery activation ("rollout off")

**Decision:** a build-time endpoint, blank by default.

- Gradle property `library.sync.url` (root `gradle.properties`), passed to Android as
  `BuildConfig.LIBRARY_SYNC_URL` and to desktop as `-Dauralis.librarySyncUrl`. It is **blank** in
  this change.
- Blank means rollout off: the client never contacts discovery, never opens a library socket, and
  runs today's Firestore sync unchanged. This is the only mode that can ship before a production
  deployment is approved.
- Non-blank activates discovery for that build. `GET {url}/v1/library/authority` with the Firebase
  ID token returns `{ state, epoch }`.

Client rules once activated (`LibraryAuthorityCache`, per UID, persisted):

| Cached | Discovery now | Client does |
| --- | --- | --- |
| anything | answers | Follows the answer and stores it with the time. |
| `cloudflare` | unreachable | Stays on Cloudflare. Never writes that account's library to Firestore again. |
| `migrating` | unreachable | Queues. No cloud library writes. |
| `legacy`, younger than 6 hours | unreachable | Continues Firestore sync. The security-rule fence is the real guard. |
| `legacy` older than 6 hours, or nothing cached | unreachable | No library sync; local edits stay pending; retries with backoff. |

A Firestore `PERMISSION_DENIED` on a library write makes the client ask discovery again at once.
An epoch lower than the cached one is ignored (stale answer). A stored answer is reused for 15
minutes without a request, except `migrating`, which is asked about again on every attempt.

**Tradeoff:** a remote switch would allow activation without a new build, but needs its own trusted
channel, and a build with a blank URL is the simplest thing to prove safe. The cost is one more
release to turn discovery on.

## D3 — Legacy polling policy

**Decision:** the desktop library check for Legacy accounts runs every **15 minutes**, not every
minute. A failed sync retries after 1, 2, 4, 8 and then 15 minutes. Startup and local edits still
sync at once, as before. The listening check keeps its shipped one-minute cadence; changing it is
outside this migration (see `phase0-quota.md`).

On the Cloudflare path there is no periodic check at all.

**Tradeoff:** an idle Legacy desktop sees another device's library edit up to 15 minutes late
instead of 1. In return it spends 4 reads/hour instead of 60. Android has never had a periodic
library pull, so desktop moves closer to it.

## D4 — Fence placement

**Decision:** a separate, server-only document `libraryAuthority/{uid}`. Clients cannot read or
write it. While it exists, rules on `users/{uid}` refuse any create or update that changes a
library field (`playlists`, `favorites`, `savedArtists`, `savedAlbums`, `librarySync`). Other root
fields (`preferences`, `starterTaste`, timestamps) stay writable, and subcollections are untouched.

This is a design choice. Fence metadata in the root document would also work if rules forbade
clients from changing or removing it and denied deletion while fenced. The separate document was
chosen because:

- deleting and re-creating `users/{uid}` cannot remove it, so root deletion (used by account
  deletion on Android) can stay allowed;
- it does not consume one of the root document's 16 keys or need a protected-field rule that old
  whole-document writes could trip over;
- backend credentials need write access to one small collection only.

**Cost:** `exists()` is one billed read for each write to `users/{uid}`, including refused ones.
Root-document writes are change-driven (library pushes and settings edits), so this is small next
to the polling in `phase0-quota.md`, but it is not zero and old clients that keep retrying add to it.

Old clients after the fence see a permission error on library writes and can still read the frozen
document.

## D5 — First-sync operation conversion

**Decision:** operations are derived from state by the existing merge. No edit log is added to the
apps.

One sync cycle, serialized per account:

1. **Resend** any persisted in-flight batch with its original operation IDs (covers a lost ACK).
2. **Pull** changes after the mirror's cursor and apply them to the mirror. If the server says the
   cursor is too old, take a full snapshot and replace the mirror.
3. **Merge:** `AccountLibraryMerge.merge(local, mirror, baseline, now, protected, duplicates)`.
   The baseline is the same `SyncBaseline` the device already keeps for Firestore, under the same
   key. Its fingerprints still match because import preserves ids, titles and track order.
4. **Diff** `mirror → plan.account` into operations, persist them as the in-flight batch, send.
5. On ACK, apply accepted operations to the mirror and clear the batch. If every operation was
   accepted or was a duplicate, save the new baseline. If any was `stale`, keep the old baseline
   and run the cycle again (bounded).
6. Apply the plan's local changes exactly as the Firestore path does.

The first Cloudflare sync of a device is therefore the same merge it would have run against the
final Firestore document:

- **Trusted baseline present:** items in the baseline and missing locally become removals; new
  local items become additions; a playlist changed on both sides keeps this device's order and
  appends the other side's extra tracks.
- **No baseline** (fresh install, wiped data): nothing is a deletion. Both sides combine.
- **Baseline that looks lost** (three or more items and at least half missing): not trusted, by the
  merge's existing rule.
- Service-linked (YouTube two-way) playlists stay protected: tracks are only added to them here and
  the service sync carries on. Restoring a playlist does not create a service link.

Downloaded changes do not echo: after step 6 the local library equals the merge result, so the next
diff is empty.

### Operations

| Operation | Payload | Server rule |
| --- | --- | --- |
| `fav.put` / `alb.put` / `art.put` | item | Upsert. Last accepted operation for that id wins. |
| `fav.del` / `alb.del` / `art.del` | id | Remove and keep a removal record. |
| `pl.put` | id, `ifRev`, metadata, optional full ordered track list | Refused as `stale` when the playlist's revision is newer than `ifRev`. Clears any deletion record. Duplicate tracks and order are stored as sent. |
| `pl.del` | id, `ifRev`, `at` | Same precondition. Records the deletion time. |
| `pins.set` | pins, `at` | Applied only when `at` is newer than the stored time. |

Every operation has a client-generated id. A batch carries the device id, protocol version and
epoch. The server applies a batch in one transaction, bumps the revision once, records the
operation ids, and only then acknowledges and notifies other devices. A repeated id returns `dup`
with the revision it was first applied at.

Concurrent edits, explicitly:

- Like on one device and unlike on another: whichever the server accepts last wins. A removal is
  only ever sent by a device whose baseline contained the item.
- Two devices edit the same playlist: the second push is `stale`; that device pulls, the merge
  combines both (its order first, the other's extra tracks appended), and it pushes again.
- Edit on one device and delete on another: the delete is `stale` if the edit landed first; on
  retry the existing merge rule applies (a deletion known to the baseline wins).

### Limits and retention

| Item | Limit |
| --- | --- |
| Protocol version | 1 |
| Operations per batch | 500 |
| Request / frame size | 4 MiB |
| Playlists per account | 1,000 |
| Tracks per playlist | 10,000 |
| Liked songs / saved albums / saved artists | 20,000 each |
| One track or item record | 4 KiB |
| Snapshot / delta page | about 512 KiB |
| Operation-id memory | 30 days, at most 5,000 ids |
| Removal records (items) and playlist deletion records | 180 days, at most 5,000 |
| Supported offline window | 180 days |

Removal records expire when a device next asks, never on a timer, and nothing is scanned until the
oldest record is actually due. A device offline longer than the retention gets `reset` and takes a
full snapshot. With a trusted
baseline its removals are still honoured; removals made elsewhere that have aged out are not
replayed to it, so such an item can come back. This matches the Firestore behaviour after
`DELETION_MEMORY_MS` (180 days).

### Storage

Tables are `WITHOUT ROWID`, so a write is one billed row. There is no secondary index.

- `meta(k, v)`: revision, authority, epoch, migration checkpoints, pins.
- `playlists(id, rev, deleted_at, body)`: metadata; a deleted playlist keeps its row as the record.
- `playlist_chunks(playlist_id, seq, body)`: ordered tracks, 200 per row. An edit rewrites only the
  chunks whose content changed. A like never touches these rows.
- `items(kind, id, pos, rev, deleted, body)`: liked songs, albums, artists.
- `ops(id, rev, at)`: accepted operation ids.

Deltas are "everything with a revision above the cursor". A push notification carries the delta
itself, so a connected device at the previous revision applies it without reading the database.
A reconnecting device that is already current costs one `meta` read. One that is behind scans the
account's rows once.

**Tradeoff:** no index on `rev` makes a catch-up pull scan the account (at most a few thousand
rows; the free plan allows 5 million row reads a day) and keeps writes at one billed row each (the
free plan allows 100,000 a day, which is the scarce limit, mainly during imports).

## Transport

- `GET /v1/library/authority` — discovery.
- `GET /v1/library` (WebSocket upgrade, `?device=`) — frames below; `ping`/`pong` is answered
  without waking the object.
- `POST /v1/library/rpc?device=` — the same frames, one per request, for clients without a socket.
- `/v1/library/admin/{uid}/{action}` — migration and recovery, guarded by `LIBRARY_ADMIN_SECRET`.
  Without that secret configured the routes answer 404.

Frames: `hello {v}` → `ready {state, epoch, rev}`; `pull {cursor, page}` → `delta {...}` or
`reset`; `push {v, epoch, ops}` → `ack {rev, results}`; `erase {v, epoch}` → `erased`; server
pushes `changed {from, rev, delta}` and `authority {state, epoch}`. Errors: `authority`, `epoch`,
`version`, `invalid`, `slow`. The full reference is in `connect-server/PROTOCOL.md`.

Anonymous Firebase credentials are rejected, as for Connect.

## Client lifecycle

- **Desktop:** socket open while signed in and the account is `cloudflare`; reconnect with
  exponential backoff and jitter (1 s to 5 min) and a fresh ID token.
- **Android:** socket open while the app process is in the foreground. In the background there is
  no socket; the app syncs when it returns to the foreground, at start, and after a local edit.
  Changes made elsewhere while it is in the background arrive at the next of those moments.
- Cursor, mirror, in-flight batch, baseline and authority cache are stored per UID. Sign-out keeps
  them (they belong to that account); a different account never reads them. A cycle checks the
  signed-in UID again before every local write.
- Every request carries a token asked for by UID: the token provider returns nothing when that
  UID is not the signed-in account, so one account's sync can never be sent with another's token.
- Account deletion (Android) sends `erase` while the sign-in still exists. The object removes the
  library and then refuses that account for good, so a device still signed in for the rest of its
  token's hour cannot upload it again. If the service cannot be reached, deletion stops before the
  sign-in is deleted and can be retried.

## Per-account cutover

Run by `connect-server/tools/library-migrate.mjs`. Every step is idempotent.

1. `begin`: object → `migrating`, epoch + 1, checkpoint `fence`.
2. Create `libraryAuthority/{uid}`.
3. Read `users/{uid}` after the fence. A failed read (quota, network) stops here and is retried
   later. A successful "not found" is a valid empty source. Record `updateTime`.
4. `import`: the object stores the library and the source checksum. Refused unless `migrating`.
   Repeating it with the same checksum is a no-op; a different checksum replaces the import.
5. Verify: the object recomputes checksum and counts from its own rows; the tool compares them
   with the source. The frozen source is also saved to a local file.
6. `commit` with the checksum: object → `cloudflare`, epoch + 1. Refused if the stored checksum
   differs.

Abort before step 6: `abort` (object → `legacy`, imported rows removed), then delete the fence.
After step 6 there is no automatic way back; see the runbook's rollback.

New accounts go through the same steps (step 3 finds no document). Nothing treats an unknown
account as Cloudflare by default.

## Out of scope

Listening history, settings sync, Listen Together and Connect are not moved. A bridge for old
clients is not built: after an account's cutover, old app versions stop syncing its library until
updated.
