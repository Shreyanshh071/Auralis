# Staging checks — what is ready and what is waiting

> Signed-in pilot follow-up: see [signed-in-pilot-2026-11-17.md](signed-in-pilot-2026-11-17.md). The existing test account is now signed in on an isolated Android clone and Mac profile. Real Firebase authentication, settings in the native apps, playback-history transfer both ways, actual background/foreground catch-up, and clear-dialog scope/defaults were verified. No production activation or library migration occurred.

> 17 November continuation: the settings/listening-history staging deployment was already present when Codex resumed. Current client verification and remaining gaps are in [verification-2026-11-17.md](verification-2026-11-17.md). The setup/results below describe the earlier library-only staging work.

Date: 14 November 2026. **Staging is deployed and all checks pass** (results in
`test-results.md`). Production is not touched by anything here.

## What staging is

The `staging` environment in `connect-server/wrangler.toml` is a separate Worker
(`auralis-connect-staging`) with its own Durable Objects. On staging, `STAGING_SECRET` enables
synthetic test identities: a token `staging:<secret>:<id>` signs in as `staging-<id>`, which can
never be a real Firebase uid. The checks below use only those identities and synthetic libraries.
No Firebase account, cookie, YouTube session or personal data is involved.

## What you do (once, at the Mac)

```bash
cd connect-server
npx wrangler login
```

This opens a browser for the Cloudflare account that owns `auralis-connect`. Then tell me to go
ahead with staging. That approval covers the staging Worker only.

## What I then run

Deploy staging and set its three secrets (generated locally, never printed):

```bash
npx wrangler deploy --env staging
```

```bash
node tools/measure.mjs secret wss://auralis-connect-staging.<subdomain>.workers.dev | npx wrangler secret put STAGING_SECRET --env staging
```

```bash
S=$(openssl rand -base64 48 | tr -d '\n'); printf 'LIBRARY_ADMIN_SECRET=%s\n' "$S" >> .measure.env; printf %s "$S" | npx wrangler secret put LIBRARY_ADMIN_SECRET --env staging; unset S
```

```bash
printf 'staging-*' | npx wrangler secret put LIBRARY_PILOT_UIDS --env staging
```

The admin secret is kept only in the ignored `connect-server/.measure.env`, where the tools read
it. `measure.mjs secret` stores the staging secret there too.

Then:

1. **Smoke test, Connect and library:** `node tools/staging-smoke.mjs`. This covers 12 steps:
   - existing Connect (two devices, presence, a write reaching the other, ping);
   - library access control (legacy default, bad or missing token, admin secret, allowlist);
   - synthetic migration with checksum, ordered restore and edits pushed between two devices;
   - a duplicate operation and a concurrent edit;
   - no database work while idle;
   - export and restore, rollback, and account-deletion erase.

   Rehearsed against a staging-configured local server: 12/12 pass.
2. **Two desktop instances, real desktop code:** with `AURALIS_LIBRARY_URL`,
   `AURALIS_STAGING_SECRET` and `LIBRARY_ADMIN_SECRET` set,
   `./gradlew :windows:test --tests 'com.auralis.music.windows.profile.AccountLibraryCloudTest'`.
   This runs two `DesktopAccount` instances with separate data folders on one synthetic account,
   plus a third on another account. Edits reach the other instance by notification, concurrent
   playlist edits keep both, an unlike propagates, and the other account sees nothing. It also
   runs the client end-to-end tests in `:windows-core:desktopTest`. Passes locally (8/8 and 4/4).
3. **Existing Connect, measured path:** `node tools/measure.mjs check` (one connection, one write,
   round-trip time).

## What stays unverified even after staging

- **Android lifecycle:** foreground socket, background behaviour, and returning to the foreground.
  No phone was available. This stays unverified until a phone with a pilot build is used.
- **Signed-in desktop app with real Firebase sign-in on staging:** this needs a dedicated Firebase
  test account, which only you can create (Authentication → Users → Add user). A desktop pointed at
  staging would also read and write that test account's Firestore library while it is legacy. That
  is harmless for a test account, but it uses production Firestore quota. The staging checks above
  use the same client code with synthetic tokens instead.
- **Windows:** all runs are on macOS.
- **Production:** real tokens on the production Worker, hibernation at production scale, billing.
