# Automatic library migration verification — 19 November 2026

Subsequent signed-in staging/device evidence and follow-up fixes are recorded in [the live pilot report](staging-automatic-pilot-2026-11-19.md). The local-only scope below describes the earlier implementation run.

Scope: local implementation on `main`; no production or staging deployment, real-account import, account deletion, live credential change or public release. The Mac UI exclusions from the approved integration were preserved. The only `Main.kt` change permits the isolated staging library pilot and pins its Together endpoint to staging.

## Results

| Check | Result and scope |
| --- | --- |
| Full Worker suite | 164 passed, zero failures before the additional cleanup-quota regression; includes automatic bootstrap, mixed old/new edits, global budget admission, crash/lost-ack recovery, adoption, rollback and existing Connect/Together/history behavior |
| Final affected Worker suites | 32 passed, zero failures: 9 automatic migration and 23 existing library tests after the last cleanup/status change |
| Firestore emulator rules/migration | 50 passed, zero failures; real rules, old desktop masked commits, Android transaction shapes, automatic backend import/mirroring, private journal access denial and operator migration/rollback |
| Desktop core | 319 discovered: 318 passed, one existing skipped Apple-artist-photo test, zero failures; real HTTP/WebSocket/file client tests enabled against local workerd |
| Desktop profile/account | 52 passed, zero failures; includes two same-account desktop instances plus account isolation |
| Selected Android sync regressions | 42 passed, zero failures: merge, conflict, restore, favorite preservation and listening chunk cases |
| Canonical Android APK pair | `:app:assembleApkPair` succeeded; root APKs match their internal release/debug build outputs; both APK signatures verify |
| macOS app | `:windows:createDistributable` succeeded; strict deep signature verification passed; bundle audit verified 378 Mach-O files with system or bundle-relative dependencies |
| Worker packaging | Wrangler staging `deploy --dry-run` succeeded with all four Durable Object bindings, including migration v4; no deployment |
| Source parity and Git checks | Mirrored shared/desktop library protocol files match (also checked by E2E test); `git diff --check` and pilot shell syntax check passed |

The normal Android release BuildConfig and Mac launcher config both contain the production library endpoint. The release has `SYNC_PILOT=false`; the Mac retains Metal and all four Netty/gRPC native-transport guards. APKs were refreshed locally, not committed as binaries or published.

## Regressions exercised

- Five concurrent bootstrap calls import one source into one epoch/revision; unrelated account cannot see it.
- Track order and repeated songs survive import. The Pinkman case preserves an old-device track addition while a new-device operation deletes another playlist; surviving library mirrors both ways.
- Saved items, pins and likes/unlikes reconcile against a persistent baseline. Full legacy replacement is supported; malformed/failed source reads are not treated as an empty account.
- Conditional Firestore mirror conflict retries retain concurrent additions without repeated echoes. An atomic server-only marker resolves a lost commit acknowledgement even after a later legacy write and real Worker restart.
- Initial source backup, current library, accepted operations and deduplication survive restart. Local device edits made while waiting are retained and upload after readiness.
- Quota before first import leaves authority pending; quota after cutover retains accepted Cloudflare data. The global backend budget and empty pilot admission prevent Firestore I/O.
- Fenced adoption verifies the original checksum, mirrors newer edits and conditionally releases the fence. A body-supplied different UID cannot change the target account.
- Automatic rollback freezes updated writers and conditionally reconciles/mirrors legacy data before its authority flip. It keeps racing old edits without relying on a project-wide Firestore rule fence, verifies the final archive and leaves reimport paused until explicit operator resume. Manual migration retains its fenced rollback flow.
- Account erase removes the DO source backup and blocks reimport. Successful cleanup removes the Firestore mirror marker. The last added regression checks that quota cannot block erase and the remaining metadata cleanup survives a process restart.

Three older test assertions expected enabled clients to keep using Firestore; these were updated to assert the new waiting/import policy and zero direct library reads/writes. No tests were disabled to make the new policy pass. The first broad Gradle run failed on those stale assertions; the corrected runs above passed.

## Commands and evidence

Logs are local and untracked under `/private/tmp/auralis-auto-*`. Relevant test/build commands:

```sh
cd connect-server
npm test
node --test --test-concurrency=1 test/library-auto.test.mjs test/library.test.mjs
```

```sh
# From firestore-tests, with Firebase CLI available:
firebase emulators:exec --config firebase.json --only firestore --project demo-auralis \
  'node --test --test-concurrency=1 connect.rules.test.mjs library.rules.test.mjs library.migration.test.mjs'
```

```sh
# With connect-server/test/serve.mjs running locally on 8795:
AURALIS_CONNECT_PORT=8795 ./gradlew :windows-core:cleanDesktopTest :windows-core:desktopTest \
  :windows:cleanTest :windows:test --tests 'com.auralis.music.windows.profile.*' --console=plain
./gradlew :app:testReleaseUnitTest --tests '*AccountLibraryMergeTest' --tests '*CloudSyncConflictTest' \
  --tests '*AccountRestoreOnePassTest' --tests '*FavoritePreservationTest' --tests '*ListenChunkMergerTest' \
  :app:assembleApkPair :windows:createDistributable --console=plain
codesign --verify --deep --strict windows/build/compose/binaries/main/app/Auralis.app
python3 scripts/verify-macos-bundle.py --app windows/build/compose/binaries/main/app/Auralis.app
```

## Remaining acceptance gates

The new automatic path has not been exercised against real Firebase IAM/OAuth, a live configured staging Worker, a physical phone, a native signed-in Mac session or a Windows runtime during this change. Earlier phone/settings/history pilot evidence does not validate this new automatic library path. The oldest supported APKs have not been run here; their request shapes were tested instead.

The admin must configure the dedicated backend secret and staging admission, run the signed-in internal pilot across actual devices/old APKs, measure cost, then approve production rollout **before releasing these new clients**. Checked-in import admission remains off. Exact ordering and commands: [automatic migration admin handoff](automatic-migration.md).
