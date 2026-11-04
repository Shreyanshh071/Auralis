package com.auralis.music.data.sync

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.data.datastore.appearanceSettingsDataStore
import com.auralis.music.data.datastore.settingsDataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

private val Context.accountPreferencesJournal by preferencesDataStore(name = "auralis_account_preferences_sync")

class AndroidAccountPreferences(private val context: Context) {
    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        val auth = try {
            FirebaseAuth.getInstance()
        } catch (e: IllegalStateException) {
            // Local debug builds can omit google-services.json. Optional background sync
            // must not crash the welcome screen when there is no configured Firebase app.
            android.util.Log.w("AccountPreferences", "Settings sync unavailable: Firebase is not configured", e)
            return@launch
        }
        val prefs = context.getSharedPreferences("auralis_preferences_device", Context.MODE_PRIVATE)
        val device = prefs.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("id", it).commit()
        }
        val engine = AccountPreferencesSync(
            DataStoreAccountPreferences(context.appearanceSettingsDataStore, context.settingsDataStore) {
                AccountPreferenceSchema.values(
                    com.auralis.music.data.datastore.AppearanceSettingsDataStore(context).settingsFlow.first(),
                    com.auralis.music.data.datastore.SettingsDataStore(context).settingsFlow.first())
            },
            AccountPreferencesBackend { uid, edits, seed ->
                check(auth.currentUser?.uid == uid && auth.currentUser?.isAnonymous == false)
                val doc = FirebaseFirestore.getInstance().collection("users").document(uid)
                FirebaseFirestore.getInstance().runTransaction { tx ->
                    check(auth.currentUser?.uid == uid)
                    val snap = tx.get(doc)
                    val raw = snap.get("preferences.android") as? Map<*, *>
                    val remote = AccountPreferencesMerge.decode(raw)
                    val merged = AccountPreferencesMerge.merge(remote, edits, seed)
                    if (merged != remote) tx.set(doc, mapOf("preferences" to mapOf("android" to
                        (raw?.entries?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }?.toMap().orEmpty() +
                            AccountPreferencesMerge.encode(merged)))), SetOptions.mergeFields("preferences.android"))
                    merged
                }.await()
            }, DataStorePreferencesJournal(context.accountPreferencesJournal), device)
        launch { engine.status.collect { android.util.Log.i("AccountPreferences", "Phone settings sync: $it") } }
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.takeIf { u -> !u.isAnonymous }?.uid) }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        }.distinctUntilChanged().collectLatest { uid ->
            engine.step(uid)
            if (uid != null) while (isActive) { delay(1_000L); engine.step(uid) }
        }
    }
}
