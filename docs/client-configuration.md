# Local client configuration and key alerts

Do not commit real configuration files or key values. Client applications still bundle their
configured API keys: local files prevent new repository disclosures, not extraction from an APK
or desktop application. Never place service-account keys in a client application.

## Firebase builds

- Android: download the Android app's `google-services.json` from the Firebase console and place
  it at `android/app/google-services.json` (ignored). `google-services.example.json` documents
  the shape; it is not a replacement for the downloaded configuration.
- Windows: copy `windows/firebase-desktop.example.json` to
  `windows/local-resources/firebase-desktop.json` and fill in the project ID and desktop client
  API key. Gradle includes local resources in the application. Do not put this file back in
  `windows/src/main/resources/`.
- CI: provision these same files from your CI secret store before building.

Firebase client API keys identify a project and are not authorization credentials. Confirm
the actual key's API and application restrictions in Google Cloud; security of Firestore data
depends on Security Rules and, where configured, App Check. Do not assume an existing key is
safe for unrelated Google Cloud APIs.

## PoToken builds

The vendored Botguard integration uses a separate API key. It is not a Firebase key and must
be authorized for the integration's endpoints. Configure one of:

1. `AURALIS_POTOKEN_API_KEY` in the build environment.
2. Gradle property `auralisPotokenApiKey` (prefer a private user Gradle properties file over CLI).
3. `POTOKEN_API_KEY` in ignored root `secrets.properties` (see `secrets.properties.example`).

An unconfigured build compiles, but PoToken initialization fails explicitly before a network
request. Features depending on minting a PoToken need a configured build. Configuration does
not make the bundled key secret. The upstream integration key may belong to its provider;
verify ownership before attempting rotation and do not substitute another project's key.

## Existing GitHub alerts

Current-file cleanup does not revoke keys or remove historical commits. Verify ownership and
actual restrictions in Google Cloud. For a key you own that requires rotation, configure a
replacement with the necessary restrictions, validate affected clients, then revoke the old
key and resolve the GitHub alert as revoked. Do not mark an alert resolved solely because its
literal was removed from the latest source.

If the Firebase key is intentionally public and appropriately restricted, document that
assessment when resolving the alert rather than claiming it has been revoked. If historical
removal is still required, coordinate a history rewrite with repository collaborators; rewriting
and force-pushing shared history is a separate operation and is not part of this cleanup.

Run `./scripts/check-tracked-secrets.ps1` before committing. It scans the current tracked files
for Google API key literals and prints only matching paths. It does not scan Git history or
replace a full secret scanner.

References: [Firebase API keys](https://firebase.google.com/docs/projects/api-keys),
[GitHub secret scanning](https://docs.github.com/en/code-security/concepts/secret-security/secret-scanning).
