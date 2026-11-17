# Phase 0 — Firestore quota attribution

Date: 14 November 2026. Status: **code inventory complete; live attribution NOT established.**

## What is known

- The project is documented as Spark (free tier): `docs/connect.md` ("Cost (Spark / free tier)") and
  `docs/connect-rules-deploy.md` ("20,000 writes and 50,000 reads a day, shared across all users").
  This comes from the repository, not from the console. It has not been confirmed against the
  billing page.
- The diagnosis in `HANDOFF.md` saw HTTP 429 `RESOURCE_EXHAUSTED` on a document **read**. On Spark
  that is consistent with the 50,000 reads/day project quota. It does not say which feature spent it.
- No usage data is available in this workspace: `gcloud` and `firebase` CLIs are not installed and
  no console export is checked in. A read-only probe using the saved desktop session was attempted
  on 14 November and blocked by the session's permission policy, so current quota state is unknown.

## What is not known (do not assume)

- Which feature consumes the most reads. The table below gives rates derived from code. It cannot
  say how many devices run each loop or for how long.
- Whether writes (20,000/day) are also being exhausted.

## Steady read/write rates derived from code

Rates are per device while the app process is running and signed in to an account.

| # | Feature | Where | Firestore cost | Shipped? |
| --- | --- | --- | --- | --- |
| 1 | Settings sync poll | `AccountPreferencesSync` (`pollMs = 30_000`), stepped every 1 s by `startDesktopPreferencesSync` and `AndroidAccountPreferences` | **1 read of `users/{uid}` every 30 s = 120 reads/hour**, on Android and desktop. Android reads inside a transaction. | Yes |
| 2 | Desktop listening check | `DesktopAccount.pullNewListens`, 60 s loop in `AuralisDesktopApp` | 1 query per minute, minimum 1 read each, more while a day document is inside the 10-minute overlap = **60+ reads/hour** | Yes |
| 3 | Desktop listening upload | `pushListens`, sampled every 20 s while playing | **up to 180 writes/hour of playback**; each bumps `updatedAt`, which makes #2 and #5 re-read that day document | Yes |
| 4 | Desktop library check | uncommitted 60 s `syncCloudLibrary` call | 1 read of `users/{uid}` per minute = **60 reads/hour** | **No (working tree only)** |
| 5 | Android listening sync | `StatsCloudSync`: query every 15 min, snapshot listener on recently updated days, upload sampled every 2 min | 4+ reads/hour, 1 read per remote change, up to 30 writes/hour of playback | Yes |
| 6 | Library sync | Android `restoreLibraryFromCloud`/`backupLibraryToCloud`, desktop `pullCloudBackup`/`pushLibrary` | 1 read at start; per local edit 1 read + 1 write (Android: transaction + 1 confirming read) | Yes |
| 7 | Listen Together, desktop | `DesktopTogether.pollOnce`, `pollMs = 1000` | Guest: 1 room read/s plus members+recommendations lists every 2.5 s. Host: both lists every 1 s, room write at least every 5 s. Roughly **7,000–10,000 reads/hour per desktop in a room**, more with more members | Yes |
| 8 | Listen Together, Android | `ListenTogetherManager` snapshot listeners | 1 read per changed document per listener; driven by host writes | Yes |
| 9 | Full history restore | `pullCloudBackup` → `api.list("users/$uid/listening")` | 1 read per day document, every desktop start that has not pulled this session | Yes |
| 10 | Dev checks | `RoomPlaybackCheck`, `RoomServiceCheck` use `FirestoreRest.fromResource()` | Same as #7 against the production project while a check runs | Dev only |

### What these rates imply

- Per signed-in desktop left running: #1 + #2 = at least **180 reads/hour ≈ 4,300 reads/day** with no
  user activity. Twelve always-on desktops would spend the whole 50,000 reads/day.
- Per Android process kept alive (playback service): #1 + #5 ≈ 124 reads/hour.
- One desktop in a Listen Together room for six hours can spend the daily read quota alone.
- **The account library itself (#6) is change-driven and is the smallest steady consumer on this
  list.** Moving only the library to Cloudflare removes #4 and #6. It does not remove #1, #2, #3,
  #5, #7, #8 or #9.

**Conclusion that the code supports:** unless the console shows otherwise, library migration alone
is unlikely to stop the 429s. Candidates #1 and #2 (always-on polling) and #7 (room polling) are
each larger per device-hour. This is an inference from rates, not a measurement.

## Evidence needed from the console (exact)

1. **Firebase console → Project settings → Usage and billing**: plan name (Spark or Blaze).
2. **Firebase console → Firestore Database → Usage tab**: reads, writes and deletes per day for the
   last 30 days, and the hour-by-hour graph for one day on which sync failed.
3. **Google Cloud console → IAM & Admin → Quotas & System Limits**, filter service "Cloud Firestore
   API": which quota shows 100% (free daily reads, writes or deletes).
4. **Google Cloud console → Monitoring → Metrics Explorer**:
   `firestore.googleapis.com/document/read_count` grouped by `type` (LOOKUP vs QUERY), and
   `firestore.googleapis.com/document/write_count` grouped by `op`, both for the same day.
   LOOKUP-heavy points to #1/#4/#6/#7-guest; QUERY-heavy points to #2/#5/#7-lists/#9.
5. **Firestore → Key Visualizer** (if available on the plan) for that day: whether `users/` or
   `rooms/` key ranges are hot.
6. **Firebase console → Firestore → Rules → Monitor rules**: allowed/denied evaluations per day.
7. From Authentication: number of accounts and roughly how many devices are active daily.

With items 2–4 the top consumer can be named. Without them it cannot.

## Recommended follow-ups outside the library migration

Not implemented here; each is a separate change needing its own approval.

- Settings sync (#1): pull on start, on local edit and at a long interval (15 min or more), not
  every 30 s.
- Desktop listening check (#2): lengthen to match Android's 15 minutes, or move to a notification.
- Desktop listening upload (#3): upload when a listen finishes or every few minutes, not every 20 s.
- Listen Together on desktop (#7): move off 1-second REST polling (`docs/listen-together-websocket-audit.md`).

## Decision taken in this change (D3)

The uncommitted 60 s desktop library check (#4) is reduced to 15 minutes with failure backoff, so
the migration-capable release does not add a new always-on poll for Legacy accounts. See
`spec.md` D3.

## Console evidence received (17 November 2026)

- Plan: **Spark** (no cost), confirmed in the Firebase console.
- Authentication usage: **201 daily active users at peak** (around 11 November), 675 monthly.
- Quotas page, current day: 7,584 of 50,000 reads (15 %), 1,754 of 20,000 writes (9 %), 6 deletes.

At the peak, 50,000 reads ÷ 201 users ≈ **250 reads per active user per day**. Settings sync alone
read the account document every 30 s while an app was open (120 reads an hour). About two hours of
use a day accounts for the whole budget. The desktop listening check adds 60 an hour. The library
is about 1 read at start plus 1 per save. This still matches rates; it is not a per-feature
measurement, because Firestore does not break reads down by collection.

## Interim change, kept apart (17 November 2026)

**Not applied to the working tree and not released.** It is saved as
`patches/interim-polling-reduction.patch`, against `HEAD`. It was tested on a clean checkout:
settings sync tests 10/10, desktop and Android compile. Apply it with `git apply` if a stopgap is
wanted before the notification design (`account-sync-proposal.md`) ships. What it does:

- **Settings sync** (Android and desktop, `AccountPreferencesSync`): local edits still go up within
  about a second, and the first step after start or sign-in still pulls. The background check for
  other devices' changes is now every **15 minutes instead of every 30 seconds**. Failures back off
  from 15 s to 15 minutes instead of retrying every 15 s. An idle open app goes from about 120 to
  about 4 reads an hour.
- **Desktop listening check:** every **15 minutes instead of every minute**, matching Android, with
  a backoff after failures (1, 2, 4, 8, then 15 minutes). From about 60 to about 4 queries an hour.

**How to confirm:** after these builds reach most users, reads per daily active user (daily reads
÷ DAU) should fall from about 250 to well under 100. If it does not, something else is the
consumer and needs measuring.

Trade-off: a settings change made on another device now arrives within 15 minutes, or at the next
app start, instead of within 30 seconds.
