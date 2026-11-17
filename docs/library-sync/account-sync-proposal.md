# Account sync on Cloudflare: settings, then listening history

Date: 17 November 2026 (revised). Approved direction: **one shared account-sync service; settings
first, then finalized, batched listening events.** Production rollout stays disabled. The
15-minute polling stopgap is kept apart in `patches/interim-polling-reduction.patch` and is not
applied.

This revision corrects four points from the first draft:

1. It promised freshness between old and new apps that the bridge cannot give.
2. It called the settings mirror "write only"; it needs a read.
3. It ignored the repeated rewrites of listen events.
4. It had no plan for deletion, retention, or retiring old clients.

## Design summary

- The per-account Durable Object built for the library is the account's sync service. Each device
  keeps **one** socket to it, carrying topics: `library`, `settings`, and later `listens`.
- `hello {v: 2}` returns each topic's revision. A device pulls only topics it is behind on. That is
  the catch-up after a reconnect, wake or restart. A device that missed nothing reads nothing.
- A change is pushed to the account's other connected devices with the changed data inline, so
  they apply it without another request.
- Merge rules are today's rules, unchanged:
  - **settings:** per key, the higher revision wins, ties broken by device id
    (`AccountPreferencesMerge`).
  - **listens:** union of event keys `<startMs>_<trackId>`; for the same key the larger `ms` wins.
- Because both rules give the same result whatever the order and however often changes are
  applied, neither topic needs a per-account cutover or a Firestore fence.

## 1. Freshness, stated correctly

| From → to | How the change travels | Freshness |
| --- | --- | --- |
| New app → new app, both open | pushed over the socket | seconds |
| New app → new app, the other closed or in the background | pulled at its next `hello` | when it next opens or reconnects |
| New app → old app | the new app mirrors the change to Firestore; the old app sees it at its own poll | the old app's interval: settings 30 s; listens 1 min on desktop, 15 min on Android (15 min for both with the interim patch) |
| Old app → new app | only when a new app runs the bridge: at its start, and when it mirrors one of its own settings edits | **not prompt.** Often the next time a new app starts, which can be hours later |
| Old app → old app | Firestore, as today | unchanged |

"Changes in seconds" holds only between updated apps. While any device still runs an old version,
that device's changes reach updated apps late. The updated app tells the user this (see 4).

## 2. Firestore mirror: conflict-safe, with its real read cost

The updated app that makes a change writes the mirror. No server writes to Firestore: the Worker
has no Firestore credentials, and none will be added.

**Settings mirror.** Old apps merge per key by revision inside a transaction (Android) or with an
update-time precondition and retry (desktop). A blind write of one key could overwrite a newer edit
an old app made to the same key and push it backwards. So the mirror uses the **same
read-merge-write** the apps use today (`AccountPreferencesBackend` for Firestore):

- It reads `users/{uid}`, merges by revision, and writes `preferences.<platform>` only if something
  changed.
- Any newer entries it finds (made by old apps) are sent on to the sync service. The mirror step is
  therefore also the old → new bridge.
- **Cost: 1 read, plus 1 write when changed, per mirrored edit batch.** Edits within about a second
  are one batch (the existing debounce).

**Listens mirror.** Event keys are unique per listen and only the recording device writes a key, so
a per-key merge write is conflict-safe without a read. Android already writes `set(merge)`; desktop
writes an `updateMask` of `events.<key>`.

- **Cost: 0 reads, 1 write per day document per batch.** Mirrored events carry a device field `d`,
  which old apps ignore.
- Each mirror write bumps that day document's `updatedAt`. Old apps then re-read it at their own
  poll, a cost they already pay today.

**Start-time bridge (old → new),** once per updated-app start while the bridge is active:

- Settings: 1 read of `users/{uid}`, merged as above.
- Listens: a query for day documents updated since the last pull. Cost: the number of documents
  returned, at least 1, typically 1–3 (the device's own mirror writes are among them).

**One-time history import** (listens phase): the first updated device of an account reads all of
its day documents, as a fresh install does today, and sends them to the service. Cost: one read per
day of history, once per account. Imports from two devices at once are harmless (union).

**Read budget during the transition:** about 3 starts × 4 reads, plus about 1 settings edit × 1
read, which is **about 13 reads per active user per day**. Today it is about 250. After the bridge
retires for an account, it is 0.

## 3. Writes, recalculated with repeated event updates

**Firestore writes for listens today.** A listen still playing is rewritten repeatedly: each upload
re-sends recent events (30 minutes back) as day-document writes.

- Desktop uploads every 20 s while playing: up to **180 writes an hour of playback**.
- Android uploads every 2 min: up to **30 an hour**.

**Updated apps send finalized listens only.** A listen is final when it ends, is skipped, or the app
goes to the background; a listen cut short by a crash is finalized from the local database at the
next start. Updated apps batch finals:

- **Service:** one push per batch, at most every 5 minutes while playing, or at once when the app
  goes to the background or closes. Each event is sent once.
- **Firestore mirror:** the same batch, so **at most 12 writes an hour of playback**, and none
  while nothing finished.
- A late correction (the same key with a larger `ms`, for example a finalize after a crash) is
  allowed and merges by largest `ms`. It does not happen in normal play.

**Service storage: one row per account-day, not per event.** The row holds that day's events, the
same layout as the Firestore day documents, capped at 3,000 events like the current rule.

| Action | Rows written |
| --- | --- |
| Listen batch | 1 day row, plus 1 counter row, plus 1 operation-id row: **3** |
| Settings edit batch | 1 per changed key, plus 1 counter row, plus 1 operation-id row: **about 3** |
| Import of history | **1 per day of history** |

**Daily estimate at today's peak** (201 active users, about 2 hours of playback each):

- Listens: 201 × 2 h × 12 batches × 3 rows ≈ **14,500 rows**.
- Settings: negligible.
- That is under the free 100,000.

**Imports:** 675 accounts × about 150 days of history ≈ **100,000 rows**, which is one day's whole
free budget if everyone imported at once. Imports therefore start per build cohort and are capped
per day by the service; over the cap, it answers "try later".

**Storage:** about 3.6 KB per active account-day. At 675 monthly users that is about 0.25 GB a
year, within the 5 GB free total.

## 4. Deletion, retention, and retiring old clients

**Clearing listening stats** (Android "clear stats"; desktop, which today clears locally only):

- It becomes an account-wide marker, `clearedBefore = T`, stored by the service.
- Every updated device deletes its local events from before T when told.
- The service drops day rows before T.
- The bridge ignores Firestore events from before T, so an old app's copy cannot bring them back.
- The clearing device deletes the Firestore day documents as Android does today (one read per day
  document, then deletes).
- An old app still holding local events can re-upload only its recent window (30 minutes). That is
  stated as a known limit while old apps exist.

**Retention:** keep all listening history at today's scale. Revisit if storage passes 2 GB.

**Account deletion:** the existing `erase` removes every topic.

**Settings:** a key that leaves the schema is ignored, as today. No other deletion is needed.

**Retiring the bridge: an explicit decision per account, never automatic.**

- The bridge (start-time read plus mirror) stays on for every account indefinitely. A quiet month
  does not prove every device updated: a phone can sit in a drawer and come back.
- The service records `legacySeenAt` whenever an updated app finds an old-app change. That is
  information for the operator, not a trigger.
- Retiring is per account, by the operator only:
  `library-migrate.mjs retire-bridge <uid> --old-devices-will-stop-syncing`.
  - It prints the warning: old devices of this account stop exchanging settings and listening
    history with updated devices; a device that never changes a setting is undetectable; and the
    last old-app activity seen.
  - The service refuses the call unless it carries the exact acknowledgement text.
  - `restore-bridge <uid>` undoes it. Changes old devices made in the meantime are picked up again
    at the next updated-app start.
- Tested:
  - An old phone returning after 45 quiet days still syncs settings both ways.
  - Listens it recorded while offline, with dates 40–45 days old, still arrive, because the bridge
    looks for them by upload time.
  - After an explicit retirement, the warned consequence happens: its changes no longer reach
    updated apps.
- There is no global off switch (the earlier `ACCOUNT_SYNC_BRIDGE` setting was removed).

**Old clients after retirement:** they keep working with Firestore alone, syncing only with other
old devices. Optionally, a date-based security rule can refuse their settings and listening writes
after a published date, so they show an error instead of silently drifting. That rule needs a
separate approval and costs no reads (`request.time` only).

**Global switches:**

- Build property `account.sync.url`: blank means today's Firestore behaviour.
- Server setting `ACCOUNT_SYNC_TOPICS`: unset means every topic is refused. Production has it
  unset.

## What stays on Firestore

| Feature | On Firestore | How long |
| --- | --- | --- |
| Firebase Authentication | yes (Firebase Auth, not Firestore) | permanently |
| Library | until each account's cutover; then a frozen copy plus the fence | recovery window |
| Settings | bridge only: start-time read, mirrored edits (1 read + 1 write each) | until the account's bridge retires |
| Listening history | bridge only: start-time query, mirrored batches (writes only); existing history as archive | until the account's bridge retires; archive kept until a separate decision |
| Listen Together rooms | yes | separate migration recommended |
| Onboarding picks (`starterTaste`) | yes | negligible |
| Old Connect documents | for old clients and account-deletion cleanup | until old versions retire |

## Order of work

1. Settings: service topic, protocol version 2, shared socket, settings backend with the mirror and
   start-time bridge, retirement marker, desktop and Android wiring, tests. Rollout off.
2. Listens: day rows, finalized batching, import with cap, `clearedBefore`, mirror and bridge,
   wiring, tests. Rollout off.
3. Staging checks for both, then a pilot decision.

## Implementation status (17 November 2026)

Local only. Production is unchanged: the deployed Worker (`fc186825`) predates these topics, and
`ACCOUNT_SYNC_TOPICS` is unset there. `account.sync.url` is blank in both builds.

| Piece | Status | Tests |
| --- | --- | --- |
| Service: protocol v2 `hello` with per-topic revisions; `settings` topic; `listens` topic with day rows, clear marker, import cap; erase covers both; explicit per-account bridge retirement | done | server suite 107/107 |
| Shared client: one socket for all topics (`listen`, `stopWatching(name)`, `stopAll`); `CloudPreferencesBackend` and `BridgedPreferencesBackend`; `ListensCloudSync` and `ListenBatcher` (finished listens only, each once) | done, identical copies in `windows/core` and `shared` (checked by a test) | bridge rules 7/7; end-to-end on a local Worker (settings via the shared socket; listens push, catch-up, clear, import) |
| Settings, desktop: notification-driven, 6-hour safety check, Firestore mirror and start-time bridge | done, behind `account.sync.url` | real `DesktopAccount` tests |
| Settings, Android: same, socket only in the foreground, catch-up on return | done, behind `account.sync.url` | compiles; not run on a phone |
| Listening history, desktop: one-time import, start-time bridge, finished-listen batches every 5 minutes, mirror marked `d`, notifications, account-wide clear | done, behind `account.sync.url` | two real `DesktopAccount` instances on a local Worker: import, notification, mirror, old-app pickup, clear, 0 Firestore reads in steady use |
| Listening history, Android | done, behind `account.sync.url`: one-time import, start-time bridge (by upload time), finished-listen batches at most every 5 minutes (also while in the background, by HTTP, with no reads), mirror marked `d`, socket only in the foreground with catch-up on return, account-wide clear applied to events and recent history | compiles; Android sync unit tests 51/51 (two out-of-date test fakes fixed so they compile again); **not run on a phone** |
| Staging deployment of the new topics | not done | — |

Bugs found and fixed while testing:
- after a failed settings mirror, the next pull retried at once instead of waiting 15 minutes;
- after the one-time import, the cursor skipped the imported days.
