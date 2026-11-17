# Production step 1 — deploy the Worker and set the admin secret

Approved scope (14 November 2026): deploy the tested Worker and set `LIBRARY_ADMIN_SECRET`.
`LIBRARY_PILOT_UIDS` stays unset, public builds keep `library.sync.url` blank, and every account
stays legacy. No Firestore rules, pilot cutover, personal-account change or billing change.

**Status: deployed 14 November 2026.** Production `auralis-connect` is version
`fc186825-e0af-456c-8545-919c67ba2595`. `LIBRARY_ADMIN_SECRET` is set (kept only in the ignored,
owner-only `connect-server/.library-admin-production`), and it is the only secret: no
`LIBRARY_PILOT_UIDS`, no `STAGING_SECRET`. No Firestore rules were deployed. No account was
migrated. Public builds still have `library.sync.url` blank.

### Results of the checks run so far (no account needed)

| Check | Result |
| --- | --- |
| `/healthz` | `ok` |
| Connect WebSocket: no token, malformed, staging-style, dev-style token | all refused, HTTP 401 |
| Library discovery: no token, malformed, dev-style token | all refused, HTTP 401 |
| Admin route without the secret | 404 |
| Admin `status` with the secret (synthetic probe uid `library-check-probe`) | `legacy`, epoch 0, rev 0 |
| Admin `begin` with the secret | refused, HTTP 403 "not on the pilot allowlist"; the account stays `legacy` |

The probe uid is not a real account. Its object holds only empty tables.

### Dedicated test-account check (run by the owner, 17 November 2026)

`node tools/library-production-check.mjs` with a dedicated Firebase test account (uid ending
`syn2`). The owner typed the credentials into the script. All 10 checks passed:

| Check | Result |
| --- | --- |
| Worker is up | pass |
| Connect: the test account connects and receives its state | pass |
| Connect: a tampered token is refused | pass (401) |
| Library discovery: the test account is legacy | pass (legacy, epoch 0) |
| Library discovery: no token, malformed token, tampered token | all refused (401) |
| Library: a write for the legacy test account is refused, nothing stored | pass |
| Library admin: hidden without the secret | pass (404) |
| Library admin: with the secret, `begin` is refused (allowlist unset) and the account stays legacy | pass |

The test account was not migrated. Its password was shared in a chat message, so it should be
changed or the account recreated before any pilot use.

**Production step 1 is complete.**

The commands below are how the deployment was done and how to repeat it.

Rollback point: production version `701678d4-6e90-48f9-bbe5-4ef9102110b2` (deployed 3 November).
It has no secrets set.

## Commands

```bash
cd connect-server && npm test
```

```bash
npx wrangler deploy --env=""
```

Set the admin secret. It is generated locally and saved only to the git-ignored, owner-only file
`connect-server/.library-admin-production`:

```bash
S=$(openssl rand -base64 48 | tr -d '\n'); (umask 077; printf 'LIBRARY_ADMIN_SECRET=%s\n' "$S" > .library-admin-production); printf %s "$S" | npx wrangler secret put LIBRARY_ADMIN_SECRET --env=""; unset S
```

Confirm only that one secret exists (no `LIBRARY_PILOT_UIDS`, no `STAGING_SECRET`):

```bash
npx wrangler secret list --env=""
```

## Checks after deployment

Run with a dedicated Firebase **test** account, not your personal one. Create it in the Firebase
console: Authentication → Users → Add user. You type its email and password into the script.
They go only to Google's sign-in, and nothing is printed or saved.

```bash
set -a; . ./.library-admin-production; set +a; node tools/library-production-check.mjs
```

It checks:
- the Worker is up;
- Connect accepts the test account and refuses a tampered token;
- discovery answers `legacy`, epoch 0, for the test account;
- discovery refuses no token, a malformed token and a tampered token;
- a library write for the legacy account is refused;
- admin routes are hidden without the secret;
- with the secret, `begin` is refused because the allowlist is unset, and the account stays legacy.

## Undo

```bash
npx wrangler rollback 701678d4-6e90-48f9-bbe5-4ef9102110b2 --env=""
```

The `LibraryAccount` namespace stays, empty apart from the test account's status lookups. The
admin secret can be removed with `npx wrangler secret delete LIBRARY_ADMIN_SECRET --env=""`.

## Note on the Connect code being deployed

The Connect code going out is the committed server (first added in `4f65bd9d` on 4 November, a
day after the live version was uploaded) plus the library routing. Cloudflare's version tag cannot
be compared with a local bundle, so it is not proven byte-identical to what is live now. It passes
all Connect server tests and runs on staging, where the Connect smoke test passed. If Connect
misbehaves after the deploy, roll back with the command above.
