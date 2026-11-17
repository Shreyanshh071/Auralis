package com.auralis.music.data.sync

import org.json.JSONObject
import java.io.IOException

// Settings on the account sync service (docs/library-sync/account-sync-proposal.md). The merge rule
// is AccountPreferencesMerge, applied by the service; this file only moves the maps. Nothing here
// runs unless a build names the account sync service.

/** A settings exchange with the service: this device's edits in, the platform's merged map out. */
class CloudPreferencesBackend(
    private val platform: String,
    private val call: suspend (uid: String, frame: JSONObject) -> JSONObject
) : AccountPreferencesBackend {
    /** What the service said last: whether old app versions may still need the Firestore bridge. */
    @Volatile var bridge: Boolean = true
        private set

    override suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference> =
        exchange(uid, edits, seed, legacy = false)

    suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>, legacy: Boolean): Map<String, AccountPreference> {
        val frame = JSONObject().put("t", "settings").put("v", 2).put("platform", platform)
            .put("edits", JSONObject(AccountPreferencesMerge.encode(edits))).put("seed", JSONObject(seed))
        if (legacy) frame.put("legacy", true)
        val reply = call(uid, frame)
        if (reply.optString("t") == "err") {
            when (reply.optString("code")) {
                "authority" -> throw LibraryAuthorityChanged(LibraryAuthority.of(reply.optString("state")), reply.optInt("epoch"))
                else -> throw IOException("Settings sync refused: ${reply.optString("code")}")
            }
        }
        bridge = reply.optBoolean("bridge", true)
        return decode(reply.optJSONObject("values") ?: JSONObject())
    }

    private fun decode(values: JSONObject): Map<String, AccountPreference> =
        AccountPreferencesMerge.decode(values.keys().asSequence().associateWith { key ->
            values.optJSONObject(key)?.let { p -> mapOf("value" to p.opt("value"), "revision" to p.opt("revision"), "device" to p.opt("device")) }
        })
}

/**
 * The service first, then, while old app versions may still exist, the Firestore copy they use.
 *
 * - **Mirror:** when this device sends edits, the merged map is also merged into Firestore by
 *   [firestore] (read, merge by revision, write only if changed), so old apps see it at their next
 *   poll and a newer edit made by an old app is never overwritten.
 * - **Bridge:** the first exchange after start does the same once even without edits, so changes
 *   made by old apps since then reach the service. Pulls caused by notifications never touch
 *   Firestore.
 * - **Old-app changes found** are sent on to the service marked `legacy`, which keeps the bridge
 *   alive for this account. After 30 days with none, the service says `bridge: false` and this
 *   device stops reading and writing Firestore for settings.
 *
 * A Firestore failure (for example an exhausted quota) never fails the exchange: the service
 * already holds the edit, and the next mirror sends the whole map again.
 */
class BridgedPreferencesBackend(
    private val cloud: CloudPreferencesBackend,
    private val firestore: AccountPreferencesBackend?,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) : AccountPreferencesBackend {
    private val bridged = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val retryAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Whether the last exchange for [uid] read or wrote Firestore (for tests and logs). */
    @Volatile var lastUsedFirestore = false
        private set

    override suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference> {
        lastUsedFirestore = false
        var merged = cloud.exchange(uid, edits, seed)
        val fs = firestore ?: return merged
        if (!cloud.bridge) return merged
        val waiting = retryAt[uid]
        // Edits always try (the old apps need them); otherwise once per start, or after a failure's wait.
        val due = edits.isNotEmpty() || (if (waiting != null) now() >= waiting else !bridged.containsKey(uid))
        if (!due) return merged
        val mirrored = try {
            lastUsedFirestore = true
            fs.exchange(uid, merged.filterValues { it.revision >= 1 }, merged.mapValues { it.value.value })
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            log("Settings mirror to Firestore failed (${e.javaClass.simpleName}); the service has the change")
            retryAt[uid] = now() + RETRY_MS
            return merged
        }
        bridged[uid] = now(); retryAt.remove(uid)
        // Entries Firestore has that the service doesn't, or newer ones: made by an old app version.
        val fromOldApps = mirrored.filter { (key, value) -> value.revision >= 1 && merged[key]?.let { value > it } != false }
        if (fromOldApps.isNotEmpty()) merged = cloud.exchange(uid, fromOldApps, emptyMap(), legacy = true)
        return merged
    }

    private companion object {
        const val RETRY_MS = 15 * 60_000L
    }
}
