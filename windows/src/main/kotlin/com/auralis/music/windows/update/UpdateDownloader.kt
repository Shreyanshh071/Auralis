package com.auralis.music.windows.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The download, or its published checksum, can't be trusted: the installer is never run. */
internal class UpdateVerificationException(message: String) : IOException(message)

/**
 * Reads the release feed and downloads an installer into [directory], resuming a partial file, and
 * returns it only once its size and SHA-256 match what the release publishes.
 */
internal class UpdateDownloader(client: OkHttpClient, private val policy: UpdateHostPolicy, val directory: File) {
    // Redirects are followed here so that each hop passes the host policy.
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()

    fun open(url: String, headers: Map<String, String> = emptyMap()): Response {
        var current = url.toHttpUrlOrNull() ?: throw IOException("Not a download address.")
        repeat(MAX_REDIRECTS + 1) {
            if (!policy.allows(current)) throw IOException("Refusing an update download from ${current.scheme}://${current.host}.")
            val request = Request.Builder().url(current).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
            val response = http.newCall(request).execute()
            if (!response.isRedirect) return response
            val location = response.header("Location")
            response.close()
            current = location?.let(current::resolve) ?: throw IOException("A redirect had no destination.")
        }
        throw IOException("Too many redirects.")
    }

    /** Every page of the feed (GitHub's `Link: <…>; rel="next"`), each page checked like any other URL. */
    fun fetchFeed(feedUrl: String): JSONArray {
        val releases = JSONArray()
        var next: String? = feedUrl
        var pages = 0
        while (next != null && pages++ < MAX_FEED_PAGES) {
            next = open(next, mapOf("Accept" to "application/vnd.github+json")).use { response ->
                if (!response.isSuccessful) throw IOException(
                    if (response.code == 403 || response.code == 429) "GitHub is limiting release checks right now. Try again later."
                    else "Release check failed (HTTP ${response.code}). Try again.")
                val batch = JSONArray(readText(response.body, MAX_FEED_BYTES))
                for (index in 0 until batch.length()) releases.put(batch.get(index))
                nextPage(response.header("Link"))
            }
        }
        return releases
    }

    /** The asset's own `digest`, else its `<installer>.sha256` asset. Neither published means no install. */
    fun expectedSha256(release: DesktopRelease): String {
        release.installer.sha256?.let { return it }
        val checksum = release.checksum ?: throw UpdateVerificationException(
            "${release.installer.name} has no published SHA-256, so Auralis won't install it.")
        if (checksum.size > MAX_CHECKSUM_BYTES) throw UpdateVerificationException("${checksum.name} is not a checksum file.")
        val text = open(checksum.url).use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't read ${checksum.name} (HTTP ${response.code}).")
            readText(response.body, MAX_CHECKSUM_BYTES)
        }
        return DesktopReleases.sha256FromChecksumFile(text, release.installer.name)
            ?: throw UpdateVerificationException("${checksum.name} doesn't list a SHA-256 for ${release.installer.name}.")
    }

    /** Names of the files a download and install of [release] use, so that cleanup keeps them. */
    fun filesFor(release: DesktopRelease): Set<String> {
        val name = fileName(release.installer.name)
        return setOf(name, "$name.part", "$name.part.sha256", "install-${release.displayVersion}.log")
    }

    /** The finished download of [release], when it is already here and still matches. */
    fun verified(release: DesktopRelease, sha256: String): File? =
        File(directory, fileName(release.installer.name)).takeIf { matches(it, release.installer.size, sha256) }

    fun download(
        release: DesktopRelease, sha256: String,
        checkCancelled: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        val asset = release.installer
        directory.mkdirs()
        val target = File(directory, fileName(asset.name))
        if (target.isFile) {
            if (matches(target, asset.size, sha256)) return target
            target.delete()
        }
        val part = File(directory, target.name + ".part")
        val partKey = File(directory, target.name + ".part.sha256")
        // A partial file only resumes towards the same published file.
        if (!partKey.isFile || partKey.readText().trim() != sha256 || part.length() > asset.size) {
            part.delete()
            partKey.writeText(sha256)
        }
        for (attempt in 0 until 2) {
            val resumed = part.length() > 0
            if (!transfer(asset, part, checkCancelled, progress)) continue
            if (matches(part, asset.size, sha256)) {
                move(part, target)
                partKey.delete()
                return target
            }
            part.delete()
            // A fresh download that fails verification would fail again.
            if (!resumed) break
        }
        part.delete()
        throw UpdateVerificationException("${asset.name} didn't match its published SHA-256, so Auralis won't install it.")
    }

    /** Deletes earlier downloads and installer logs; only [keep] (file names) remain. */
    fun cleanup(keep: Set<String> = emptySet()) {
        directory.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
    }

    /**
     * Appends to [part] until it holds the asset's published size. False when the server can't resume
     * that far (the part file is then emptied for a fresh start).
     */
    private fun transfer(asset: ReleaseAsset, part: File, checkCancelled: () -> Unit, progress: (Long, Long) -> Unit): Boolean {
        val start = part.length()
        if (start == asset.size) return true
        val headers = mutableMapOf("Accept" to "application/octet-stream")
        if (start > 0) headers["Range"] = "bytes=$start-"
        open(asset.url, headers).use { response ->
            val append = when {
                start > 0 && response.code == 206 && response.header("Content-Range").orEmpty().startsWith("bytes $start-") -> true
                response.code == 200 -> false
                response.code == 416 -> { part.delete(); return false }
                else -> throw IOException("Update download failed (HTTP ${response.code}).")
            }
            val body = response.body ?: throw IOException("The update download was empty.")
            var written = if (append) start else 0L
            progress(written, asset.size)
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var reported = written
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (written + read > asset.size) {
                            out.close()
                            part.delete()
                            throw UpdateVerificationException("${asset.name} is larger than its published size, so Auralis won't install it.")
                        }
                        out.write(buffer, 0, read)
                        written += read
                        if (written - reported >= 512 * 1024 || written == asset.size) { progress(written, asset.size); reported = written }
                    }
                }
            }
            // Kept for the next attempt to resume.
            if (written != asset.size) throw IOException("The update download stopped at ${written * 100 / asset.size}%. It will resume later.")
        }
        return true
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_FEED_PAGES = 10
        private const val MAX_FEED_BYTES = 16L * 1024 * 1024
        private const val MAX_CHECKSUM_BYTES = 64L * 1024
        private val safeName = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")

        /** Asset names come from the feed: never let one leave the updates folder. */
        fun fileName(assetName: String): String {
            if (!safeName.matches(assetName) || assetName.contains("..") || assetName.length > 128)
                throw UpdateVerificationException("The installer's file name \"$assetName\" isn't safe to save.")
            return assetName
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun matches(file: File, size: Long, sha256: String): Boolean =
            file.isFile && file.length() == size && sha256(file).equals(sha256, ignoreCase = true)

        fun nextPage(link: String?): String? = link?.split(',')?.map(String::trim)
            ?.firstOrNull { part -> part.split(';').drop(1).any { it.trim() == "rel=\"next\"" } }
            ?.substringBefore(';')?.trim()?.removePrefix("<")?.removeSuffix(">")

        private fun readText(body: ResponseBody?, limit: Long): String {
            val source = body?.source() ?: throw IOException("The response was empty.")
            // request() buffers up to limit + 1 bytes and says whether there were that many.
            if (source.request(limit + 1)) throw IOException("The response was unexpectedly large.")
            return source.buffer.readUtf8()
        }
    }
}
