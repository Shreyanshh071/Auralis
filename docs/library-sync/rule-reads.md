# Extra Firestore reads from the library fence

Date: 14 November 2026. Derived from the rules and client code, not measured in production.
Firestore's documentation says each `get()`/`exists()` in rules is a billed read, and is billed
even when the request is denied.

## Who pays, and when

The fence in `firestore.rules` applies to every account once those rules are deployed (runbook step
B), pilot or not:

```
allow create, update: if isOwner(uid) && hasValidUserShape() && withinUserSizeLimit()
  && (!changesLibrary() || !libraryFenced(uid));
```

`libraryFenced` does one `exists()` lookup. The rules language evaluates `||` lazily, so the lookup
only runs when `changesLibrary()` is true. That means a write to `users/{uid}` that changes
`playlists`, `favorites`, `savedAlbums`, `savedArtists` or `librarySync`. The documentation does not
say whether an unevaluated call is billed. Check this after deployment by comparing daily reads
before and after step B.

| Write | Extra reads |
| --- | --- |
| Library save (Android transaction merge, desktop `POST :commit`), when the merge changed something | **1** |
| Library sync where nothing changed | 0 (no write is sent) |
| Settings sync (`preferences.android`, `preferences.windows`), starter picks | 0 |
| Listening history, Connect devices, Listen Together rooms | 0 (other documents, other rules) |
| A library write that the fence refuses (old app on a pilot account) | 1, per attempt |
| Updated app on a migrated account | 0 (it does not write the Firestore library) |

## How big

A library save costs 1 read + 1 write today: the transaction's read on Android, or the read before
the write on desktop. With the fence it costs **2 reads + 1 write**. Library reads and writes stay
the same otherwise.

- Extra reads per day equals the number of library saves per day across all users. That is at most
  the library share of the daily write count, which the Firestore Usage tab would show; Phase 0
  data is needed for the real number.
- Library saves happen after edits (debounced 3 s on Android, 5 s on desktop) and on a desktop
  check that finds something different. A burst of edits is usually one save.
- Example: 200 users each saving 10 times a day is 2,000 extra reads, which is 4 % of the Spark
  plan's 50,000. That example is invented, not measured.
- Refused writes from old apps of pilot accounts: old app versions save the library after local
  edits (and old desktop also at start and on manual refresh). This costs one read per edit burst
  per old device of a pilot account. That is negligible with internal accounts only.

## Option that costs no reads (not implemented, needs a decision)

The fence could use a Firebase Auth custom claim instead of a document:

```
&& (!changesLibrary() || request.auth.token.libraryFenced != true)
```

- **Cost:** zero extra reads for everyone, pilot or not.
- **Catch:** a claim only reaches a device when its ID token refreshes, which can take up to an
  hour. The migration would set the claim, wait at least 60 minutes (the token lifetime) with the
  account in `migrating`, and only then take the final read. Old apps keep writing Firestore during
  that hour, so their edits are included.
- **Rollback:** remove the claim. Old apps may be refused for up to an hour afterwards. That
  delays sync but loses nothing.
- **Requires:** the migration tool setting claims with the service account, which it already uses.

Recommendation: given the quota pressure, consider the claim before step B, and decide after
Phase 0. With internal accounts only, the document fence's cost is the per-save read paid by all
other users, as quantified above.
