package com.auralis.music.windows.together

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant

/** Firebase ID tokens keep the existing Firestore security rules in force (no admin credentials). */
class FirestoreRest(private val project: String, private val apiKey: String, private val transport: ((String, String, JSONObject?, String?) -> JSONObject)? = null) {
    private val root = "projects/$project/databases/(default)/documents"
    private val endpoint = "https://firestore.googleapis.com/v1/$root"
    var uid: String = ""; private set
    private var token = ""
    private var refresh = ""
    private var expiresAt = 0L
    private var anonymous = true
    var offsetMs = 0L; private set

    fun signInEmail(email: String, password: String, create: Boolean = false): JSONObject {
        val result = request("https://identitytoolkit.googleapis.com/v1/accounts:${if (create) "signUp" else "signInWithPassword"}?key=$apiKey", "POST",
            JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true), false)
        token = result.getString("idToken"); refresh = result.getString("refreshToken"); uid = result.getString("localId")
        expiresAt = System.currentTimeMillis() + result.getString("expiresIn").toLong() * 1000 - 60_000
        anonymous = false
        return result
    }
    fun signInGoogle(idToken: String) {
        val body = "id_token=${java.net.URLEncoder.encode(idToken, Charsets.UTF_8)}&providerId=google.com"
        val result = request("https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=$apiKey", "POST",
            JSONObject().put("postBody", body).put("requestUri", "http://localhost").put("returnSecureToken", true), false)
        token = result.getString("idToken"); refresh = result.getString("refreshToken"); uid = result.getString("localId")
        expiresAt = System.currentTimeMillis() + result.getString("expiresIn").toLong() * 1000 - 60_000
        anonymous = false
    }
    fun restoreAccount(refreshToken: String) { refresh = refreshToken; expiresAt = 0; anonymous = false; authenticate() }
    fun accountRefreshToken(): String = refresh
    fun accountProfile(): JSONObject {
        authenticate()
        return request("https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=$apiKey", "POST", JSONObject().put("idToken", token), false).getJSONArray("users").getJSONObject(0)
    }
    fun resetPassword(email: String) {
        request("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$apiKey", "POST", JSONObject().put("requestType", "PASSWORD_RESET").put("email", email.trim()), false)
    }
    fun forgetAccount() { uid = ""; token = ""; refresh = ""; expiresAt = 0 }

    private fun request(url: String, method: String, body: JSONObject? = null, bearer: Boolean = true): JSONObject {
        transport?.let { return it(url, method, body, if (bearer) token else null) }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5000; connection.readTimeout = 8000
        if (bearer) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                connection.doOutput = true
                val form = url.startsWith("https://securetoken.googleapis.com/")
                connection.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json; charset=utf-8")
                val payload = if (form) body.keys().asSequence().joinToString("&") { "${java.net.URLEncoder.encode(it, Charsets.UTF_8)}=${java.net.URLEncoder.encode(body.getString(it), Charsets.UTF_8)}" } else body.toString()
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault("HTTP $status")
                // No tokens or authorization headers in errors or logs.
                throw ServiceException(status, message)
            }
            // runQuery answers with a JSON array; callers read it as "results".
            return if (text.isBlank()) JSONObject() else if (text.trimStart().startsWith("[")) JSONObject().put("results", JSONArray(text)) else JSONObject(text)
        } finally { connection.disconnect() }
    }

    @Synchronized fun authenticate() {
        if (token.isNotEmpty() && System.currentTimeMillis() < expiresAt) return
        if (refresh.isNotEmpty()) {
            val result = request("https://securetoken.googleapis.com/v1/token?key=$apiKey", "POST",
                JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh), false)
            token = result.getString("id_token"); refresh = result.getString("refresh_token")
            uid = result.getString("user_id")
            expiresAt = System.currentTimeMillis() + result.getString("expires_in").toLong() * 1000 - 60_000
        } else {
            val result = request("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$apiKey", "POST", JSONObject().put("returnSecureToken", true), false)
            token = result.getString("idToken"); refresh = result.getString("refreshToken"); uid = result.getString("localId")
            expiresAt = System.currentTimeMillis() + result.getString("expiresIn").toLong() * 1000 - 60_000
        }
    }

    fun get(path: String): JSONObject? {
        authenticate()
        return try { decodeFields(request("$endpoint/$path", "GET").getJSONObject("fields")) }
        catch (e: ServiceException) { if (e.status == 404) null else throw e }
    }

    fun list(path: String): List<Pair<String, JSONObject>> {
        authenticate()
        val result = mutableListOf<Pair<String, JSONObject>>()
        var page = ""
        do {
            val response = request("$endpoint/$path?pageSize=100" + if (page.isEmpty()) "" else "&pageToken=${java.net.URLEncoder.encode(page, Charsets.UTF_8)}", "GET")
            val docs = response.optJSONArray("documents") ?: JSONArray()
            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                result += doc.getString("name").substringAfter("/documents/") to decodeFields(doc.getJSONObject("fields"))
            }
            page = response.optString("nextPageToken")
        } while (page.isNotBlank())
        return result
    }

    /**
     * [mask] narrows a merge to exact field paths (e.g. "events.`<key>`"), so nested map entries
     * are added one by one instead of replacing the whole map; by default the top-level fields.
     */
    /**
     * Documents of the [collection] under [parent] whose number field [field] is greater than
     * [after] (Firestore's single-field index covers this; no composite index needed).
     */
    fun queryGreaterThan(parent: String, collection: String, field: String, after: Long): List<Pair<String, JSONObject>> {
        authenticate()
        val query = JSONObject().put("structuredQuery", JSONObject()
            .put("from", JSONArray().put(JSONObject().put("collectionId", collection)))
            .put("where", JSONObject().put("fieldFilter", JSONObject()
                .put("field", JSONObject().put("fieldPath", field)).put("op", "GREATER_THAN")
                .put("value", JSONObject().put("integerValue", after.toString())))))
        val results = request("$endpoint/$parent:runQuery", "POST", query).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { i ->
            val doc = results.getJSONObject(i).optJSONObject("document") ?: return@mapNotNull null
            doc.getString("name").substringAfter("/documents/") to decodeFields(doc.optJSONObject("fields") ?: JSONObject())
        }
    }

    fun write(path: String, fields: JSONObject, serverStamp: String? = null, merge: Boolean = true, create: Boolean = false,
        mask: List<String>? = null) {
        authenticate()
        val write = JSONObject().put("update", JSONObject().put("name", "$root/$path").put("fields", encodeFields(fields)))
        if (merge) write.put("updateMask", JSONObject().put("fieldPaths", JSONArray(mask ?: fields.keys().asSequence().toList())))
        if (create) write.put("currentDocument", JSONObject().put("exists", false))
        if (serverStamp != null) write.put("updateTransforms", JSONArray().put(JSONObject().put("fieldPath", serverStamp).put("setToServerValue", "REQUEST_TIME")))
        val sent = System.currentTimeMillis()
        val result = request("$endpoint:commit", "POST", JSONObject().put("writes", JSONArray().put(write)))
        val ack = System.currentTimeMillis()
        result.optString("commitTime").takeIf { it.isNotBlank() }?.let { offsetMs = Instant.parse(it).toEpochMilli() - (sent + (ack - sent) / 2) }
    }

    fun delete(path: String) { authenticate(); request("$endpoint/$path", "DELETE") }

    // ---- Account-only calls (Auralis Connect) ------------------------------------------------
    // These never fall back to an anonymous sign-up: without a signed-in account they fail.

    @Synchronized fun authenticateAccount() {
        if (refresh.isEmpty() || anonymous) throw ServiceException(401, "Not signed in to an account")
        authenticate()
    }

    /** The document's fields and its update time (for a conditional write), or null if it doesn't exist. */
    fun accountGet(path: String): Pair<JSONObject, String>? {
        authenticateAccount()
        return try {
            val doc = request("$endpoint/$path", "GET")
            decodeFields(doc.optJSONObject("fields") ?: JSONObject()) to doc.optString("updateTime")
        } catch (e: ServiceException) { if (e.status == 404) null else throw e }
    }

    fun accountList(path: String): List<Pair<String, JSONObject>> { authenticateAccount(); return list(path) }

    fun accountDelete(path: String) { authenticateAccount(); request("$endpoint/$path", "DELETE") }

    /** Commits [writes] atomically and returns the server's commit time in epoch milliseconds. */
    fun accountCommit(writes: JSONArray): Long {
        authenticateAccount()
        val result = request("$endpoint:commit", "POST", JSONObject().put("writes", writes))
        return result.optString("commitTime").takeIf { it.isNotBlank() }?.let { Instant.parse(it).toEpochMilli() } ?: 0L
    }

    /**
     * A full replacement of [path] with [fields]; [serverTimeFields] are set to the server's time.
     * [updateTime]: only if the document is still at that version; [mustNotExist]: only if it doesn't exist.
     */
    fun replaceWrite(path: String, fields: JSONObject, serverTimeFields: List<String> = emptyList(),
        updateTime: String? = null, mustNotExist: Boolean = false): JSONObject {
        val write = JSONObject().put("update", JSONObject().put("name", "$root/$path").put("fields", encodeFields(fields)))
        if (serverTimeFields.isNotEmpty()) write.put("updateTransforms", JSONArray().apply {
            serverTimeFields.forEach { put(JSONObject().put("fieldPath", it).put("setToServerValue", "REQUEST_TIME")) }
        })
        when {
            updateTime != null -> write.put("currentDocument", JSONObject().put("updateTime", updateTime))
            mustNotExist -> write.put("currentDocument", JSONObject().put("exists", false))
        }
        return write
    }

    /** Toggle only our own vote atomically, preserving other participants' concurrent votes. */
    fun transformArray(path: String, field: String, value: String, remove: Boolean) {
        authenticate()
        val transform = JSONObject().put("document", "$root/$path").put("fieldTransforms", JSONArray().put(
            JSONObject().put("fieldPath", field).put(if (remove) "removeAllFromArray" else "appendMissingElements",
                JSONObject().put("values", JSONArray().put(encode(value))))))
        request("$endpoint:commit", "POST", JSONObject().put("writes", JSONArray().put(JSONObject().put("transform", transform))))
    }

    fun deleteAnonymousAccount() {
        if (token.isEmpty() || !anonymous) return
        authenticate()
        request("https://identitytoolkit.googleapis.com/v1/accounts:delete?key=$apiKey", "POST", JSONObject().put("idToken", token), false)
        uid = ""; token = ""; refresh = ""; expiresAt = 0
    }

    class ServiceException(val status: Int, message: String) : IllegalStateException(message)

    companion object {
        fun fromResource(): FirestoreRest {
            val config = FirestoreRest::class.java.classLoader.getResourceAsStream("firebase-desktop.json")
                ?.bufferedReader()?.use { JSONObject(it.readText()) } ?: error("Firebase configuration is missing")
            return FirestoreRest(config.getString("projectId"), config.getString("apiKey"))
        }
        fun encodeFields(fields: JSONObject): JSONObject = JSONObject().apply { fields.keys().forEach { put(it, encode(fields.get(it))) } }
        fun encode(value: Any?): JSONObject = when (value) {
            null, JSONObject.NULL -> JSONObject().put("nullValue", JSONObject.NULL)
            is Boolean -> JSONObject().put("booleanValue", value)
            is Byte, is Short, is Int, is Long -> JSONObject().put("integerValue", value.toString())
            is Number -> JSONObject().put("doubleValue", value)
            is Instant -> JSONObject().put("timestampValue", value.toString())
            is JSONObject -> JSONObject().put("mapValue", JSONObject().put("fields", encodeFields(value)))
            is JSONArray -> JSONObject().put("arrayValue", JSONObject().put("values", JSONArray().apply { for (i in 0 until value.length()) put(encode(value.get(i))) }))
            else -> JSONObject().put("stringValue", value.toString())
        }
        fun decodeFields(fields: JSONObject): JSONObject = JSONObject().apply { fields.keys().forEach { put(it, decode(fields.getJSONObject(it))) } }
        fun decode(value: JSONObject): Any = when {
            value.has("nullValue") -> JSONObject.NULL
            value.has("booleanValue") -> value.getBoolean("booleanValue")
            value.has("integerValue") -> value.getString("integerValue").toLong()
            value.has("doubleValue") -> value.getDouble("doubleValue")
            value.has("timestampValue") -> Instant.parse(value.getString("timestampValue")).toEpochMilli()
            value.has("mapValue") -> decodeFields(value.getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject())
            value.has("arrayValue") -> JSONArray().apply {
                val items = value.getJSONObject("arrayValue").optJSONArray("values") ?: JSONArray()
                for (i in 0 until items.length()) put(decode(items.getJSONObject(i)))
            }
            else -> value.optString("stringValue")
        }
    }
}
