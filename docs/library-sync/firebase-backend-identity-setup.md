# Owner setup: Firestore backend identity

19 November 2026. This was the initial missing-permission handoff. The owner subsequently granted the setup roles and the dedicated staging identity was configured and verified; see [the live pilot report](staging-automatic-pilot-2026-11-19.md).

At the initial check, the temporary Mac login had `roles/firebase.admin`: Firestore access and test-account administration work, but IAM service-account listing/creation, key creation, custom-role creation and project policy changes are not granted. API checks confirmed this; application Firebase configuration is not a service-account key.

The owner can do the small setup below without granting broader IAM administration to the Mac user. Run it in the correct project's Google Cloud Shell (bash), then configure **staging only**. It creates a dedicated identity and a Firestore-only custom role; it does not enable imports or change Firestore rules/billing.

```sh
PROJECT=auralis-70cf8
BRIDGE_ID=auralis-library-staging
BRIDGE_EMAIL="${BRIDGE_ID}@${PROJECT}.iam.gserviceaccount.com"
BRIDGE_ROLE="projects/${PROJECT}/roles/auralisLibraryBridge"

cat > /tmp/auralis-library-bridge-role.yaml <<'ROLE'
title: Auralis Library Bridge
description: Point reads and conditional library mirroring for the account sync backend
stage: GA
includedPermissions:
- datastore.databases.getMetadata
- datastore.entities.get
- datastore.entities.create
- datastore.entities.update
- datastore.entities.delete
ROLE

gcloud iam roles create auralisLibraryBridge --project="$PROJECT" --file=/tmp/auralis-library-bridge-role.yaml
gcloud iam service-accounts create "$BRIDGE_ID" --project="$PROJECT" --display-name='Auralis staging library bridge'
gcloud projects add-iam-policy-binding "$PROJECT" --member="serviceAccount:$BRIDGE_EMAIL" --role="$BRIDGE_ROLE"
(umask 077; gcloud iam service-accounts keys create "$HOME/auralis-library-staging-private.json" --iam-account="$BRIDGE_EMAIL" --project="$PROJECT")
```

If the role/account already exists, inspect it rather than replacing an existing identity or widening an unrelated role. Keep the role at these permissions; do not grant project Owner, Firebase Admin or Auth administration to this backend. `datastore.databases.getMetadata` is specifically needed for confirming genuinely new accounts; `datastore.databases.get` is not the metadata permission.

Set the JSON as secret `LIBRARY_FIRESTORE_SERVICE_ACCOUNT` on **`auralis-connect-staging`**, either through Cloudflare's dashboard (Worker → Settings → Variables and Secrets) or, from a Cloudflare-authorized checkout:

```sh
cd connect-server
npx wrangler secret put LIBRARY_FIRESTORE_SERVICE_ACCOUNT --env staging < /absolute/private/path/auralis-library-staging-private.json
```

The owner can set this directly; the private key does not need to be sent to the Mac or pasted into chat. Confirm only that the staging secret is set. Protect the local key, and delete/revoke a surplus key after checking that the intended secret is safely configured. The dedicated identity continues working when the temporary human project access is revoked; the owner can revoke it separately if the pilot is stopped.

The Mac can then finish automatic staging import using the existing dedicated disposable account and exact UID admission. Production admission remains off. A production service identity and rollout require the later production acceptance step, not reuse of the temporary human OAuth login.
