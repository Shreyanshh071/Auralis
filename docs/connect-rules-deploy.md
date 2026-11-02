# Deploying the Connect intent rules

Status: not deployed. Project: `auralis-70cf8`. Nothing here has been run against production.

## What changes

Only `users/{uid}/connect/{docId}` gains one document, `intent`:

- `validConnectIntent()`: exactly `version`, `epoch`, `at`, `by`, with types checked, `at` at most
  two minutes ahead of the server, `by` at most 64 characters.
- `get`, `create` and `update` accept `intent`; `update` also refuses a lower `version`.
- `delete` already covers it. The rule for the session, queue, devices and everything else is
  unchanged: `git diff db35d3c1 -- firestore.rules` shows only these lines.

## Why either order is safe

Clients write the intent separately from the session, so a refused intent write or read changes
nothing but how a conflict is judged.

| | Rules live | Rules not live, or rolled back |
| --- | --- | --- |
| Released app | Unchanged: it never touches `intent` | Unchanged |
| Updated app | Full reconciliation | Sync and control work; conflicts judged by ownership, as released apps do. It gives up after 3 tries per action |

Deploying the rules first is still the plan, so the first updated device records actions from its
first tap.

## Before deploying

1. Open Firebase console, Firestore, Rules, for `auralis-70cf8`, and compare the live rules with
   `git show db35d3c1:firestore.rules`. They should match, because the last rules change before this
   one is from 2026-10-23. If they differ, stop: this file would overwrite whatever is live.
2. Save a copy of the live rules outside the repo. The console's history is the second copy.
3. `cd firestore-tests && npm test`, then both compatibility runs:

   ```bash
   firebase emulators:exec --config firebase.json --only firestore --project demo-auralis "node --test connect.compat.test.mjs"
   ```

   ```bash
   git show db35d3c1:firestore.rules > ../old.rules && CONNECT_RULES=../old.rules CONNECT_INTENT=refused firebase emulators:exec --config firebase.json --only firestore --project demo-auralis "node --test connect.compat.test.mjs"
   ```

4. Check the active project and account: `firebase use` must print `auralis-70cf8`.
5. Deploy outside the busiest hours, and not close to 1:30 PM IST in winter (12:30 PM in summer),
   when the daily quota resets.

## Deploying

`--only firestore:rules` touches the rules alone, nothing else in the project:

```bash
firebase deploy --only firestore:rules --project auralis-70cf8
```

Rules can take a minute to apply, and up to ten for connections already open.

## Checking it worked

1. With a released app open, play, pause and hand off between two devices. Nothing should change.
2. With an updated app, tap a song and confirm `users/{uid}/connect/intent` appears on your own account.
3. Watch the console's Firestore usage for an hour. Writes should not step up, and there should be
   no rise in denied requests.

## Rolling back

Roll back if released apps start failing to sync, denied requests rise, or anything else unexplained
appears. Music keeps playing in every case, because no playback path uses Firestore.

1. Firebase console, Firestore, Rules, history, pick the previous version, Restore. Or:

   ```bash
   git show db35d3c1:firestore.rules > firestore.rules && firebase deploy --only firestore:rules --project auralis-70cf8 && git checkout -- firestore.rules
   ```

2. No client update is needed. Updated apps drop back to ownership rules by themselves.
3. Existing `intent` documents stay, unreadable and harmless, each about 100 bytes. They take effect
   again if the rules return, and older ones lose to newer session versions.

## Quota

Free tier: 20,000 writes and 50,000 reads a day, shared across all users.

- Rules add no billed reads: `validConnectIntent()` uses no `get()`.
- Each playback action on a device costs one session write and one intent write. With nobody else
  online, the session write waits 5 s so fast skipping costs one. A song ending on its own costs nothing.
- Seeks, queue edits, automatic song changes and position reports record no intent.
- There are no listeners or polling on the intent. One read happens per real conflict on reconnect.
- For scale, a released app heartbeats 8,640 times a day per device. Fifty actions a day cost 100
  writes, about 1% of that. That is why releasing the heartbeat fixes matters far more than this.
