# Internal-account pilot — plan, safeguards and stop conditions

Date: 14 November 2026. **Not started. Broad rollout stays disabled.** Every production step here
needs explicit approval (see `runbook.md`). This pilot is for the owner's own internal or test
accounts only. No existing user account migrates automatically, and nothing in the code migrates an
account because it exists, signs in, is new, or runs a new app version.

The pilot tests correctness and measures cost. It does **not** answer Phase 0. The Firestore
consumer that exhausted the quota is still unidentified (`phase0-quota.md`); call-rate estimates
from the code are not evidence of it.

## Who is in it

- At most 25 accounts, all owned by the team. Start with 1–2 accounts that have production-shaped
  libraries (imported YouTube playlists, repeated songs, a few hundred likes) on at least one
  Android phone and one desktop.
- Not in it: any account belonging to someone outside the team.

## Two allowlists, both required

| Where | What | Effect |
| --- | --- | --- |
| Worker secret `LIBRARY_PILOT_UIDS` | uids separated by commas or newlines | The service refuses `begin` for any other uid (403). Unset or empty means no account can move. `*` is refused outside local tests. |
| A local file passed as `--allowlist` | one uid per line, `#` comments | The tool refuses any other uid before touching anything, refuses more than 25, and checks stop conditions for every listed account before moving another. |

Set the secret without putting uids in Git or chat history:

```bash
npx wrangler secret put LIBRARY_PILOT_UIDS --env=""
```

## Pilot builds, not public releases

Public Android and desktop builds keep `library.sync.url` blank. Only devices in the pilot get
builds with it set (runbook step C, limited to internal installs). Public users never contact the
library service, so a pilot problem cannot reach them.

A pilot account must not be signed in on an app version without the setting: such a version keeps
syncing through Firestore, which the fence refuses after the cutover. It cannot corrupt anything,
but it will not sync.

## Before each account moves

1. All devices of the account are on pilot builds.
2. `node connect-server/tools/library-migrate.mjs status <uid>` shows `legacy`, `fenced: false`.
3. `report --allowlist <file>` prints `OK: no stop condition hit`. `migrate` runs this itself and
   refuses to continue (exit 3) if any listed account has a stop condition.

## Export and restore (verified)

- `migrate` saves the frozen Firestore source, and right after the flip saves a first export. It
  re-reads the export file and checks its checksum before reporting success.
- `export <uid>` saves another verified export at any time without changing anything.
- `restore <uid> <file>` checks the file's checksum and owner, saves a backup export of the current
  library, freezes the account, restores, verifies the stored checksum, then unfreezes. Devices
  start over from the restored library (new epoch). Edits made after that export are lost; that is
  what restoring means, and the backup lets the restore itself be undone.
- `rollback <uid>` returns the account to Firestore with its latest library (edits kept).

Tested end to end on the emulator and local Worker (`test-results.md`): a restore reproduces the
exported checksum exactly, refuses a damaged file or another account's export, and keeps a backup.

**Drill before the first real cutover:** on the first pilot account, after migrating, run `export`,
make an edit on a device, run `restore` with the first export, and confirm on both devices that the
edit is gone and nothing else changed. Then `restore` with the backup and confirm the edit is back.

## Monitoring

| What | Where | Look for |
| --- | --- | --- |
| Stop conditions | `library-migrate.mjs report --allowlist <file>`, after each cutover and daily during the pilot | `OK` |
| Per-account state | `report` output: state, epoch, rev, live counts, client pushes, retries (`dup`), conflicts (`stale`), refusals (`bad`, `limit`) | counts that move only when someone edits |
| Service events | `npx wrangler tail --env="" --format=json` | `library.admin`, `library.push` (only pushes with conflicts or refusals), `library.refused`. Lines carry a 12-character hash, never the uid or any library content. |
| Cloudflare usage | Dashboard → Durable Objects → `LibraryAccount`: requests, rows read and written, storage | far below 100,000 rows written a day; idle accounts flat |
| Firestore | Firebase console → Firestore → Usage; Rules → monitor | denials only from old app versions of pilot accounts |
| Devices | the account page's sync line | no lasting "couldn't check" or "being moved" message |

## Stop conditions

Stop migrating further accounts, and decide per account whether to `restore` or `rollback`, when
any of these happens. The first five are checked by `report` automatically.

1. `status` shows a problem: on the library service without a fence, or legacy but still fenced.
2. An account stays `migrating` for more than an hour.
3. An account is frozen for a rollback or restore.
4. The service refused any client change (`bad` or `limit` greater than 0).
5. A list (playlists, likes, saved albums or artists) fell below half of its size at import (from
   at least 6), which is how a sync bug deleting things would look.
6. The import left unusable entries behind (the tool already stops before importing them unless
   `--accept-dropped` is given after review).
7. Anyone notices a missing, duplicated, reordered or resurrected playlist or song.
8. A YouTube-linked playlist changes on YouTube without someone editing it.
9. Cloudflare rows written exceed 50,000 in a day, or any free-plan limit warning appears.
10. An updated app keeps writing the Firestore library for a migrated account (Firestore rule
    denials from a pilot build's devices).

## Ending the pilot

Success means every condition above stayed clear for the pilot accounts over normal use (edits on
several devices, offline edits, a reinstall, a sign-out and sign-in). Moving any account beyond the
allowlist, or setting `library.sync.url` in public builds, is a separate decision that needs
explicit approval and should wait for Phase 0.

To end the pilot without continuing: `rollback` each pilot account, then remove the secret.

```bash
npx wrangler secret delete LIBRARY_PILOT_UIDS --env=""
```

## Known gaps before the pilot

- Phase 0 is not answered (needs the console evidence in `phase0-quota.md`).
- Real-device checks done so far: the new desktop build launched on this Mac signed out (rollout
  off) and ran normally with no library-service files. Not yet checked on a real device: an Android
  phone (none was connected), Windows, the Cloudflare path on any real device, signed-in behaviour,
  Android foreground and background socket handling, and account deletion on Android.
- Production behaviour of the Worker (real tokens, hibernation, usage) is unmeasured until step A.
