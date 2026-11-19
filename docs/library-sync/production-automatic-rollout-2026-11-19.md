# Production automatic library rollout — 19 November 2026

**Live.** The owner explicitly authorized production deployment and broad automatic migration. `auralis-connect` now uses `LIBRARY_AUTO_IMPORT=true` and production secret `LIBRARY_AUTO_UIDS=*`. New accounts do not need individual UID registration or an operator migration command.

Final public-admission version: **`249ab0b6-282c-4f17-9c7f-230a83df75af`**, deployed 19 November at **16:40 IST**. The tested code was enabled in `56c4a208-4708-4223-b132-4624e4835e0a`; the final secret change broadened admission. Before this run, production was `fc6385db-d4ad-4a91-a66e-f2f729d00c3b`.

## What users do

Use the updated normal APK/desktop build and sign in to the same **Auralis/Firebase account** on their devices. No staging endpoint or internal allowlist is needed. On their next library sync, the authenticated backend imports and verifies the legacy source, then updated devices use Cloudflare. Other devices of that account reuse the same authority and migration job. Existing library/source backups are not bulk overwritten.

This is lazy automatic migration, not a claim that every inactive account has already moved. Accounts which never launch an updated client continue using Firestore. Everyone does not have to update simultaneously. The version label alone does not identify the implementation: distribute the builds containing the current bootstrap client.

Old ↔ old, new ↔ new and old ↔ new all concern **the same Auralis account**. Old apps retain their Firestore path; automatic accounts use the compatibility bridge for old/new changes, normally on the five-minute legacy check cycle while updated devices are connected. Updated devices use notifications. Existing provider quotas and the backend budget can delay first imports/legacy mirroring, with retry and preserved local/accepted cloud data.

## Production configuration

- Dedicated identity: `auralis-library-production`, separate from staging and from the temporary human login.
- Custom `auralisLibraryBridge` role: only database metadata get and document get/create/update/delete. No Firebase Auth administration or project IAM grant to the backend.
- Key configured as production `LIBRARY_FIRESTORE_SERVICE_ACCOUNT`. Owner-only key backup outside Git; never bundled with an app. IAM propagation initially returned 403, then point reads and database metadata verification succeeded before deployment.
- Existing local production admin credential was stale (admin 404). Refreshed `LIBRARY_ADMIN_SECRET` and updated the owner-only, git-ignored `connect-server/.library-admin-production`. Other operators need the current credential through a secure channel; normal app login does not use it. Previous value was retained privately for audit.
- Production secrets: `LIBRARY_ADMIN_SECRET`, `LIBRARY_AUTO_UIDS`, `LIBRARY_FIRESTORE_SERVICE_ACCOUNT`, and the pre-existing manual `LIBRARY_PILOT_UIDS`. **No `STAGING_SECRET` or human OAuth token was added to production.**
- Production `ACCOUNT_SYNC_TOPICS=listens`; existing Connect/Together routes retained.
- Daily backend reservations remain 5,000 reads and 5,000 writes. No paid plan/billing change. The new `LibraryLegacyBudget` namespace uses migration v4; earlier migrations retained.
- Checked-in production import flag is true so a later normal deployment does not silently disable admission. The separate staging default remains false; its existing explicit staging deployment is unchanged by this rollout.

## Live verification

1. Backed up the approved disposable account's actual Firestore source before production import. It contained two playlists, 113 tracks, one like, album, artist and pin, plus the existing deletion record.
2. **10/10 production canary checks:** health, concurrent real-Firebase bootstrap, initial-source checksum, current verified export, missing/malformed-token refusal, hidden admin routes, authenticated Connect and library WebSockets, and idempotent bootstrap.
3. Launched the **actual normal production Mac app** against a new isolated profile containing only the approved test Firebase session. No personal Mac profile, YouTube cookies or local library were copied. It restored the 113-track library at Cloudflare epoch 1.
4. A production edit reached the native Mac repository and another WebSocket in **1,018 ms**. Verified the backend mirror through a real owner-authenticated Firestore read. Removed both temporary probe favorites afterward. An initial local assertion used a flat favorite ID rather than the repository's `favorite.track.id` encoding; the edit had arrived, and the corrected check passed without app changes.
5. After widening admission to `*`, created a **new real Firebase test identity which was never individually allowlisted**. Three concurrent bootstrap requests initialized one isolated empty library at epoch 1. Erased that disposable cloud fixture, deleted its Firebase identity, verified `USER_NOT_FOUND`, and removed its generated local credentials. Wildcard admission stayed enabled.
6. Final existing canary library retained two playlists/113 tracks and one favorite, with no probe favorites left. No production rollback was run, as requested by the owner.

## Previously manual migration recovered

Inventoried only the existing `libraryAuthority` collection, not every user library. Found one previously manual, fenced Cloudflare account. Privately backed up its current verified Cloudflare export and frozen Firestore source; the source matched the original import checksum.

Adopted its backend compatibility bridge and conditionally released its fence. The account retained all current Cloudflare library content: 18 playlists/3,743 tracks/165 favorites, including its three prior playlist deletions. Its stale Firestore copy had 21 playlists/3,894 tracks; those three already-deleted playlists were not restored. Canonical content matched the pre-adoption cloud export except the expected tombstone-retention timestamp refresh. Read back and verified the legacy mirror against the resulting cloud checksum.

This was a one-time repair of the earlier manual pilot, not a requirement to process every new user manually. Old APK writes for that account are no longer blocked by its old fence. Firestore security rules were not deployed or changed in this run.

## Evidence and remaining scope

The rollout follows the signed-in phone/Mac staging pilot, **166/166** Worker tests, **50/50** emulator tests and **12/12** final staging smoke checks in [the staging report](staging-automatic-pilot-2026-11-19.md). This production change enabled configuration and credentials; it did not change app code or require a different staging build for ordinary users.

Actual Windows runtime and actual shipped older APK binaries remain unverified here. The native legacy Firestore path, old desktop request shape, staging physical-device paths and real production endpoints were tested. Whole-fleet load/cost and old-version behavior still need observation; the daily cap is a reservation limit, not a guarantee about old APKs' independent quota consumption.

The owner declined rollback preparation/execution. Retain version/data records; a Worker version change is not an automatic undo of migrated libraries, and Cloudflare disallows direct rollback across a Durable Object lifecycle change. No rollback command was executed. Any later migration reversal must preserve edits already accepted on Cloudflare.

Private account backups, keys and test credentials were kept outside Git. The production canary app was stopped afterward with its isolated profile retained. The regular phone package, personal app data and staging Worker were not modified by this production rollout. The temporary human IAM setup access can now be revoked independently; the production service identity must retain its Firestore role for ongoing sync.
