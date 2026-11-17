# Account library on Cloudflare — deployment, cutover and rollback

Date: 14 November 2026. **Nothing in this document has been run against production.** Every step
below that touches production needs explicit approval first. Steps are ordered so that each one is
harmless on its own until the next is taken.

Read `spec.md` first. Test evidence is in `test-results.md`.

## What needs approval, and what each approval changes

| # | Step | What changes for users | Reversible by |
| --- | --- | --- | --- |
| A | Deploy the Worker with the `LibraryAccount` namespace | Nothing. Every account answers `legacy`. | Deploying the previous version (the namespace stays, empty) |
| B | Deploy the Firestore rules with the fence | Nothing until a fence document exists. One extra billed read per library write. | Deploying the previous rules |
| C | Install builds with `library.sync.url` set on pilot devices only; public builds stay blank | Pilot devices ask discovery where the library lives. Public users are unaffected. | Reinstalling builds with the property blank |
| D | Cut over allowlisted internal accounts (`pilot.md`) | Those accounts' libraries move. Old app versions stop syncing them. | `rollback` or `restore` per account |
| E | Any account beyond the pilot allowlist, or `library.sync.url` in public builds | **Not prepared and not authorized.** Broad rollout stays disabled. | — |

Billing: none of these steps enables a paid plan. Step A stays within the Workers free plan only
while daily usage fits it (see "Budget"). If it does not, the choice to pay is a separate approval.

## Before step A

1. Phase 0 evidence (`phase0-quota.md`, "Evidence needed from the console"). Without it there is no
   proof this migration stops the 429s; the code inventory suggests settings sync, the desktop
   listening check and Listen Together are larger consumers.
2. Run every test suite in `test-results.md` again on the commit being deployed.
3. Check in the Cloudflare dashboard that rate-limit namespace ids `10403` and `10404` are unused in
   the account (they are new in `wrangler.toml`).
4. Confirm on the Cloudflare plan page that SQLite-backed Durable Objects and their point-in-time
   recovery are available on the account's plan. This was read from documentation on
   14 November 2026, not checked against the account.

## Step A — deploy the Worker

```bash
cd connect-server
npm ci
npm test
npx wrangler deploy --env="" --dry-run
```

After approval:

```bash
npx wrangler deploy --env=""
```

This applies migration `v2` (new SQLite class `LibraryAccount`) and adds the `/v1/library*` routes.
Connect's routes, class and storage are unchanged.

Set the admin secret (generate it locally; do not paste it into chat, tickets or Git):

```bash
openssl rand -base64 48
```

```bash
npx wrangler secret put LIBRARY_ADMIN_SECRET --env=""
```

Check, with a real Firebase ID token for a test account in `ID_TOKEN`:

```bash
curl -s https://auralis-connect.auralis018.workers.dev/healthz
```

```bash
curl -s -H "Authorization: Bearer $ID_TOKEN" https://auralis-connect.auralis018.workers.dev/v1/library/authority
```

Expected: `ok`, then `{"state":"legacy","epoch":0,"v":1}`. An admin call without the secret must
answer 404:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://auralis-connect.auralis018.workers.dev/v1/library/admin/x/status
```

**Undo:** `npx wrangler rollback`. No account data exists yet.

## Step B — deploy the rules

```bash
cd firestore-tests
npm ci
npm test
```

After approval, from the repository root:

```bash
firebase deploy --only firestore:rules --project auralis-70cf8
```

Effect: `libraryAuthority/{uid}` becomes a server-only collection, and writes that change a library
field on `users/{uid}` run one `exists()` lookup. No fence document exists yet, so nothing is
refused.

**Undo:** redeploy the previous `firestore.rules` (`git show <commit>:firestore.rules`). Only do
this while no account is fenced; removing the rule while accounts are on Cloudflare would let old
clients write their Firestore library again.

## Step C — pilot builds with discovery on (pilot devices only)

Build on a machine where the root `gradle.properties` temporarily has (the Worker's origin,
`https://`, no path):

```
library.sync.url=https://auralis-connect.auralis018.workers.dev
```

```bash
./gradlew :app:assembleRelease
```

```bash
./gradlew :windows:createDistributable
```

Install these builds only on devices of allowlisted pilot accounts. Then set the property back to
blank and do not commit it: public releases keep `library.sync.url=` empty, so the public never
contacts the library service. A pilot build asks discovery at start and when a sync is due, and
keeps using Firestore for every account that answers `legacy`. Step A must already be live: a pilot
build that cannot reach discovery and has no cached answer does not sync the library (it keeps
edits locally and retries).

**Undo:** reinstall builds with the property blank. Accounts already cut over would then stop
syncing on those devices, so roll those accounts back first.

## Step D — cut over allowlisted internal accounts

Follow `pilot.md`. Requirements: steps A–C live; the Worker secret `LIBRARY_PILOT_UIDS` names the
account; the same uid is in a local allowlist file; every device of the account runs a pilot build;
a service-account key for the Firebase project is available on the operator's machine only.
Firestore IAM cannot limit a role to one collection, so the key can read and write the whole
database: create it for this job, keep it out of Git and logs, and delete it afterwards.

```bash
export LIBRARY_URL=https://auralis-connect.auralis018.workers.dev
export FIREBASE_PROJECT_ID=auralis-70cf8
export GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/key.json
export LIBRARY_SNAPSHOT_DIR=/absolute/path/outside/the/repo/library-snapshots
read -s LIBRARY_ADMIN_SECRET && export LIBRARY_ADMIN_SECRET
```

Look first (changes nothing):

```bash
node connect-server/tools/library-migrate.mjs status <uid>
```

```bash
node connect-server/tools/library-migrate.mjs report --allowlist /absolute/path/pilot-allowlist.txt
```

Cut over:

```bash
node connect-server/tools/library-migrate.mjs migrate --allowlist /absolute/path/pilot-allowlist.txt <uid>
```

The tool refuses uids not in the file, refuses to start while any listed account has a stop
condition, and stops for review when the source has unusable entries. It prints counts and a
checksum, never the library. It saves the frozen Firestore document and a verified first export to
`LIBRARY_SNAPSHOT_DIR` (the user's whole library: operator-only, keep for the recovery window, then
delete). If it stops with "not done, safe to run again", run the same command again later; the
account stays fenced and clients keep edits locally meanwhile. If it stops with "FAILED", read the
message and run `status`.

Then check on the account's devices: playlists (order, repeated songs, YouTube-imported ones),
likes, saved albums and artists, pins; an edit on one device appears on the other within seconds;
`report` prints `OK`. Run the export and restore drill in `pilot.md` on the first account.

## Step E — not prepared

Moving any account beyond the pilot allowlist, or setting `library.sync.url` in public builds, is
not prepared and not authorized. It needs its own approval after the pilot and after Phase 0.

## Rollback

| Situation | Command | Result |
| --- | --- | --- |
| Migration stuck before the flip, or to be cancelled | `library-migrate.mjs abort <uid>` | Firestore is the library again, exactly as it was. Old clients sync again. |
| After the flip (the account may have edits on Cloudflare) | `library-migrate.mjs rollback <uid>` | Writes are frozen, the latest library is exported, written into `users/{uid}`, verified by checksum, then the fence is lifted. Edits made on Cloudflare are kept. |
| `status` reports "legacy but still fenced" | `library-migrate.mjs abort <uid>` | Lifts the leftover fence. |
| `status` reports "on the library service WITHOUT a fence" | Recreate `libraryAuthority/<uid>` with any content from the Firebase console, then investigate | Stops old clients writing a second library. |
| A bad edit or sync bug damaged the library on the service | `library-migrate.mjs restore <uid> <export file>` | The chosen export is put back exactly; a backup of what it replaced is saved first. |
| Cloudflare outage | Nothing | Clients keep edits locally and retry. Never switch the backend for an outage. |

Do **not** roll back by deleting a fence or by restoring the frozen Firestore snapshot: both lose
every edit made since the cutover.

`rollback` is safe to run again after a crash. An export can also be taken at any time without
changing anything:

```bash
node connect-server/tools/library-migrate.mjs export <uid>
```

`restore` puts a verified export back into an account that is on the library service (see
`pilot.md`).

## Budget (measured locally, see `test-results.md`)

Cloudflare Durable Objects free plan, per day, account-wide: 100,000 requests, 100,000 SQLite rows
written, 5,000,000 rows read (documentation read 14 November 2026). These are shared with Connect.

- Import: about one row per liked song, saved album, saved artist and playlist, plus one per 200
  playlist tracks. A typical library (15 playlists, 300 likes) is 333 rows; a large one (40
  playlists, 1,500 likes) 1,583. That allows roughly 60 large or 300 typical imports a day inside
  the write limit, with nothing else writing.
- A like or unlike: 3 rows written. Adding a song to a playlist: 4.
- An idle or already-current device: 0 rows read, 0 written.
- A device catching up after being offline: reads the account's rows once (318 for the typical
  library).

Firestore: an updated client of a migrated account makes no library read or write (asserted in the
desktop tests). The fence costs one billed read per write that changes a library field, on every
account, from step B on.

These are local measurements of billed rows. Request counts, duration and real traffic have not
been measured in production; watch the Cloudflare dashboard (Durable Objects → metrics) and the
Firestore usage tab after each step.

## What to watch

- `wrangler tail` during a cutover (no library content is logged by the Worker).
- Cloudflare dashboard: requests, rows read/written, storage for the `LibraryAccount` namespace.
- Firebase console: Firestore reads/writes per day; rule denials (old clients hitting the fence).
- On devices: the account page's sync line ("being moved", "couldn't check where your library
  syncs", "update Auralis").

## Not verified live

- Any production deployment, token verification against real Firebase tokens on these routes, and
  hibernation behaviour of the library sockets on Cloudflare's edge.
- Old released APK and desktop binaries against a fenced production account (their write shapes
  were replayed against the emulator).
- Windows runtime behaviour of the desktop build (built and tested on macOS only).
- Cloudflare point-in-time recovery for the namespace.
- Cost of rule `exists()` lookups in production billing.
