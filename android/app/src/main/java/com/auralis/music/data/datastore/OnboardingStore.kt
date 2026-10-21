package com.auralis.music.data.datastore

import android.content.Context
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.NewUserSeedProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * First-launch progress (tour seen, artists picked) and the picked artists with their songs.
 * Small SharedPreferences file read synchronously at startup, so the first frame already knows
 * whether to show the tour; the Room database is untouched.
 */
object OnboardingStore {
    private const val PREFS = "auralis_onboarding"
    private const val TOUR_SEEN = "tour_seen_v1"
    private const val PICKER_DONE = "picker_done_v1"
    private const val ARTISTS = "starter_artists"
    private const val TRACKS = "starter_tracks"
    private const val NEW_ACCOUNT = "new_account"
    private const val TOUR_PENDING = "account_tour_pending"
    private const val ACCOUNT_TOUR_SEEN = "account_tour_seen"

    @Serializable
    private data class SavedArtist(val id: String, val name: String, val thumbnail: String? = null)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    data class State(val tourSeen: Boolean = false, val pickerDone: Boolean = false, val accountId: String = "", val starterVersion: Int = 0, val isNewAccount: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null
    private var signedOutTourCompletedThisSession = false

    private fun accountKey(key: String) = "$key:${_state.value.accountId}"

    /**
     * @param signedInAtLaunch someone updating from a version without onboarding is already
     * signed in: an app update must not replay the tour. Artist choices remain account-scoped.
     */
    fun init(context: Context, signedInAtLaunch: Boolean) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        signedOutTourCompletedThisSession = false
        val uid = try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty() } catch (_: Exception) { "" }
        if (signedInAtLaunch && !p.contains(TOUR_SEEN)) {
            p.edit().putBoolean(TOUR_SEEN, true).apply()
        }
        bindAccount(uid)
    }

    /** Keep starter choices separate when accounts switch on the same phone. */
    fun bindAccount(uid: String) {
        val p = prefs ?: return
        val artists = decode(p.getString("$ARTISTS:$uid", null), ListSerializer(SavedArtist.serializer()))
        val tracks = decode(p.getString("$TRACKS:$uid", null), ListSerializer(Track.serializer()))
        NewUserSeedProvider.setStarter(artists.map { it.name }, tracks)
        _state.value = State(p.getBoolean(TOUR_SEEN, false) && !p.getBoolean("$TOUR_PENDING:$uid", false),
            p.getBoolean("$PICKER_DONE:$uid", false), uid,
            starterVersion = _state.value.starterVersion + 1,
            isNewAccount = p.getBoolean("$NEW_ACCOUNT:$uid", false))
    }

    /** Called before publishing the signed-in profile, for both email and Google auth. */
    fun onAuthenticated(uid: String, newlyCreated: Boolean) {
        if (uid.isBlank()) return
        if (signedOutTourCompletedThisSession) prefs?.edit()
            ?.putBoolean("$ACCOUNT_TOUR_SEEN:$uid", true)?.apply()
        if (newlyCreated) prefs?.edit()
            ?.putBoolean("$NEW_ACCOUNT:$uid", true)
            ?.putBoolean("$TOUR_PENDING:$uid", !signedOutTourCompletedThisSession)
            ?.apply()
        // A tour immediately before this sign-in counts, but cannot cover later accounts.
        signedOutTourCompletedThisSession = false
        bindAccount(uid)
    }

    /** An older login with no saved taste or listening is also a newcomer to Auralis. */
    fun requireNewcomerTour(uid: String) {
        val p = prefs ?: return
        if (uid != _state.value.accountId || p.getBoolean("$ACCOUNT_TOUR_SEEN:$uid", false)) return
        p.edit().putBoolean("$TOUR_PENDING:$uid", true).apply()
        _state.value = _state.value.copy(tourSeen = false)
    }

    fun markTourSeen() {
        prefs?.edit()?.putBoolean(TOUR_SEEN, true)
            ?.putBoolean(accountKey(ACCOUNT_TOUR_SEEN), true)
            ?.putBoolean(accountKey(TOUR_PENDING), false)?.apply()
        signedOutTourCompletedThisSession = _state.value.accountId.isBlank()
        _state.value = _state.value.copy(tourSeen = true)
    }

    /** Skipped picker: Home loads the general feed without inventing artist preferences. */
    fun skipPicker() {
        prefs?.edit()?.putBoolean(accountKey(PICKER_DONE), true)
            ?.putBoolean(accountKey(NEW_ACCOUNT), false)?.apply()
        _state.value = _state.value.copy(pickerDone = true, isNewAccount = false)
    }

    fun savePicks(artists: List<Artist>, tracks: List<Track>) {
        val saved = artists.map { SavedArtist(it.id, it.name, it.thumbnail) }
        val artistJson = json.encodeToString(ListSerializer(SavedArtist.serializer()), saved)
        val trackJson = json.encodeToString(ListSerializer(Track.serializer()), tracks)
        prefs?.edit()
            ?.putString(accountKey(ARTISTS), artistJson)
            ?.putString(accountKey(TRACKS), trackJson)
            ?.putBoolean(accountKey(PICKER_DONE), true)
            ?.putBoolean(accountKey(NEW_ACCOUNT), false)
            ?.apply()
        NewUserSeedProvider.setStarter(artists.map { it.name }, tracks)
        _state.value = _state.value.copy(pickerDone = true, isNewAccount = false, starterVersion = _state.value.starterVersion + 1)
        // Firestore queues this small merge if the connection goes away after the local save.
        val uid = _state.value.accountId
        if (uid.isNotBlank()) try {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().collection("users").document(uid)
                .set(mapOf("starterTaste" to mapOf("artists" to artistJson, "tracks" to trackJson)),
                    com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener { android.util.Log.w("Onboarding", "Starter taste upload deferred", it) }
        } catch (e: Exception) { android.util.Log.w("Onboarding", "Starter taste saved locally", e) }
    }

    /** Part of the existing account restore, before the new-user picker decision. */
    fun restorePicks(uid: String, cloud: Map<*, *>?) {
        val artistJson = cloud?.get("artists") as? String ?: return
        val trackJson = cloud["tracks"] as? String ?: "[]"
        if (decode(artistJson, ListSerializer(SavedArtist.serializer())).isEmpty()) return
        prefs?.edit()?.putString("$ARTISTS:$uid", artistJson)?.putString("$TRACKS:$uid", trackJson)
            ?.putBoolean("$PICKER_DONE:$uid", true)?.apply()
        // A slow restore from a previous account must not replace the current listener's taste.
        if (_state.value.accountId == uid) bindAccount(uid)
    }

    private fun <T> decode(raw: String?, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        if (raw.isNullOrBlank()) emptyList()
        else try { json.decodeFromString(serializer, raw) } catch (_: Exception) { emptyList() }
}
