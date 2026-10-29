package com.auralis.music.windows.profile

import com.auralis.music.data.sync.*
import com.auralis.music.windows.together.FirestoreRest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

internal class DesktopPreferencesBackend(private val api: FirestoreRest) : AccountPreferencesBackend {
    override suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference> = withContext(Dispatchers.IO) {
        repeat(5) {
            val path = "users/$uid"
            val current = api.accountGet(path)
            check(api.uid == uid) { "Account changed during preference sync" }
            val raw = current?.first?.optJSONObject("preferences")?.optJSONObject("windows")?.toMap().orEmpty()
            val remote = AccountPreferencesMerge.decode(raw)
            val merged = AccountPreferencesMerge.merge(remote, edits, seed)
            if (merged == remote) return@withContext merged
            val fields = JSONObject().put("preferences", JSONObject().put("windows", JSONObject(raw + AccountPreferencesMerge.encode(merged))))
            val write = api.replaceWrite(path, fields, updateTime = current?.second, mustNotExist = current == null)
                .put("updateMask", JSONObject().put("fieldPaths", JSONArray().put("preferences.windows")))
            try {
                check(api.uid == uid)
                api.accountCommit(JSONArray().put(write))
                return@withContext merged
            } catch (e: FirestoreRest.ServiceException) {
                if (e.status !in listOf(409, 412)) throw e
            }
        }
        throw java.io.IOException("Preference sync contention; will retry")
    }
}

internal fun startDesktopPreferencesSync(account: DesktopAccount, local: DataStoreAccountPreferences,
    journal: AccountPreferencesJournal, device: String, scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
    val engine = AccountPreferencesSync(local, DesktopPreferencesBackend(account.firestore), journal, device)
    launch { engine.status.collect { com.auralis.music.util.Log.i("AccountPreferences", "PC settings sync: $it") } }
    account.state.map { it.profile?.uid?.takeIf(String::isNotBlank) }.distinctUntilChanged().collectLatest { uid ->
        engine.step(uid)
        if (uid != null) while (isActive) { delay(1_000L); engine.step(uid) }
    }
}
