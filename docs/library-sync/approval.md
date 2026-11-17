# Library sync — remaining risks and production changes for approval

> Signed-in pilot follow-up: see [signed-in-pilot-2026-11-17.md](signed-in-pilot-2026-11-17.md). The existing test account is now signed in on an isolated Android clone and Mac profile. Real Firebase authentication, settings in the native apps, playback-history transfer both ways, actual background/foreground catch-up, and clear-dialog scope/defaults were verified. No production activation or library migration occurred.

> Real-phone follow-up: [Android device verification, 17 November](android-device-verification-2026-11-17.md). Five tests passed on Android 16. They exposed and verified a fix for erasure when only settings/history are enabled. Final staging client tests are now 11/11. The regular signed release was restored with data preserved; full signed-in UI and battery/Doze checks remain separate.

> 17 November verification: settings/listening history are implemented with public rollout off and tested on staging. Server 107/107; rules/migration 48/48; staging client 10/10; settings bridge fixtures 10/10; desktop profile 50/50; local core 250 tests (one skip); Android sync 51/51. Both builds succeeded. See [current verification and remaining gates](verification-2026-11-17.md). No additional production changes were made.

Date: 14 November 2026. **Step 1 is done** (Worker deployed as version `fc186825`, admin secret set,
allowlist unset; see `production-step1.md`). Nothing else is done: no account is migrated, your personal account is not part of anything, and broad rollout stays disabled. Phase 0
is unresolved: the Firestore quota consumer is not identified, and the library is not shown to be
it.

## Where things stand

| Item | Status |
| --- | --- |
| Implementation (server, rules, migration tool, Android, desktop) | Done locally, rollout off in both builds |
| Independent review | Done; 7 confirmed bugs and 5 minor issues fixed, each with a test |
| Local tests | All pass (`test-results.md`) |
| Staging deploy and staging checks | **Done:** staging Worker deployed; smoke test 12/12, real client 5/5, two desktop instances 8/8 |
| Connect smoke test after staging deploy | **Done:** two devices, presence, a write and ping; round trip 298–343 ms |
| Android lifecycle (foreground socket, background, account deletion on a phone) | **Unverified:** no phone was available |
| Phase 0 (what exhausted the Firestore quota) | **Unresolved:** needs the Firestore Usage tab and the Cloud quota page |

## Phase 0 update (17 November)

Console totals (Spark plan, 201 daily users at peak, about 250 reads per user per day at the limit)
and the code inventory make settings polling and the desktop listening check plausible consumers.
These aggregate numbers do not independently attribute consumption to a feature. The library migration therefore matters for correctness and scale more than for quota.
Settings and listening history get their own proposal (`account-sync-proposal.md`). An interim
15-minute polling patch is kept apart and not released.

## Remaining risks

1. **The migration may not fix the 429s.** The code suggests settings sync (a read every 30 s per
   signed-in device), the desktop listening check (every minute) and desktop Listen Together
   (about a read a second in a room) use far more quota than the library. Unproven until Phase 0.
2. **Every user pays for the fence.** Once the rules deploy, each library save by any user costs
   one extra read: 2 reads and 1 write instead of 1 and 1. Settings, history and Connect writes
   cost nothing extra. Total extra reads equal library saves per day, which is unknown until
   Phase 0. A custom-claim fence would cost zero reads but needs a one-hour wait per cutover
   (`rule-reads.md`).
3. **Android behaviour is unverified on a real phone.** This covers the socket in the foreground,
   syncing when the app returns to the foreground, and account deletion erasing the cloud library.
4. **Old app versions on a pilot account stop syncing its library** after cutover, and show only a
   generic error. Pilot accounts must use pilot builds on every device.
5. **Windows is untested.** Everything ran on macOS.
6. **Production behaviour is unmeasured.** That covers real Firebase tokens on the new routes,
   socket hibernation at the edge, and real request and row usage against the free limits (shared
   with Connect).
7. **The service-account key** needed for cutovers can read and write the whole Firestore
   database; Firestore IAM cannot narrow it to one collection. It must be created for the pilot,
   kept off Git and logs, and deleted afterwards.
8. **Cloudflare point-in-time recovery** for the new namespace is unconfirmed on your plan. Our own
   verified exports are the recovery path until it is.
9. **Removal records last 180 days.** A device offline longer can bring back an item that was
   removed elsewhere. This matches the current Firestore behaviour.

## Exact production changes requested, in order

Each is approved separately. None turns anything on for public users.

### 1. Deploy the Worker (Cloudflare, `auralis-connect`) — DONE 14 November; test-account check passed 17 November

- Adds the `LibraryAccount` SQLite Durable Object class (migration tag `v2`), the `LIBRARY` binding,
  the rate limit `LIBRARY_REQUESTS` (600 per minute per IP, namespace id `10403`), and the routes
  under `/v1/library`. Connect's code, class, storage and URL are unchanged.
- Every account answers `legacy`. Admin routes answer 404 until a secret is set. No account can
  start moving until it is on the allowlist.
- Commands: `npx wrangler deploy --env=""`, then
  `npx wrangler secret put LIBRARY_ADMIN_SECRET --env=""`. Set `LIBRARY_PILOT_UIDS` only when
  step 4 is approved.
- Undo: `npx wrangler rollback`. The empty namespace remains.

### 2. Deploy the Firestore rules (`auralis-70cf8`)

- Adds the server-only `libraryAuthority/{uid}` collection. While such a document exists, writes
  to `users/{uid}` that change `playlists`, `favorites`, `savedAlbums`, `savedArtists` or
  `librarySync` are refused. Everything else is unchanged.
- Cost: one extra read per library save, for every user (risk 2).
- Command: `firebase deploy --only firestore:rules --project auralis-70cf8`.
- Undo: redeploy the previous rules, but only while no account is fenced.
- Alternative to decide first: the custom-claim fence (zero reads, one-hour wait per cutover).

### 3. Pilot builds on pilot devices only

- A build with `library.sync.url=https://auralis-connect.auralis018.workers.dev`, installed only on
  devices signed in to allowlisted internal accounts. Public releases keep it blank, and it is not
  committed.

### 4. Cut over one internal test account

- A dedicated test account you create. Not your personal account.
- Set `LIBRARY_PILOT_UIDS` to that uid. Put the same uid in a local allowlist file. Create a
  temporary service-account key.
- Run `library-migrate.mjs report`, then `migrate`, then the export and restore drill
  (`pilot.md`).
- Stop conditions are checked automatically before any further account moves.
- Undo: `library-migrate.mjs rollback <uid>` (keeps edits) or `restore <uid> <export>`.

### Not requested

- Migrating your personal account or any user account.
- Setting `library.sync.url` in public builds.
- Moving more than the allowlisted internal accounts.
- Enabling billing.

## What is needed from you

1. When convenient: Firestore Usage graphs and the quota page (Phase 0).
2. For a phone check later: an Android phone connected over USB with debugging on, and a dedicated
   Firebase test account you create (Authentication → Users → Add user).
3. Approve or decline steps 1–4 above, and choose between the document fence and the claim fence.
