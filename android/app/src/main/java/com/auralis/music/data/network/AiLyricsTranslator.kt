package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.domain.model.AiTranslationSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AiLyricsTranslator {

    private const val TAG = "AiLyricsTranslator"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // In-memory cache keyed by "trackKey::targetLang::mode::provider"
    private val translationCache = ConcurrentHashMap<String, LyricsData>()

    /**
     * Translates a [LyricsData] track into target language while strictly preserving 1:1 line synchronization.
     */
    suspend fun translateLyrics(
        trackId: String,
        lyrics: LyricsData,
        settings: AiTranslationSettings
    ): LyricsData? = withContext(Dispatchers.IO) {
        if (lyrics.lines.isEmpty()) return@withContext null

        val cacheKey = "${trackId}::${settings.targetLanguage}::${settings.translationMode}::${settings.provider}"
        translationCache[cacheKey]?.let { return@withContext it }

        try {
            val originalLines = lyrics.lines
            val linesText = originalLines.joinToString("\n") { it.text }

            val effectivePrompt = "You are an expert music phonetic transcription and romanization engine. Transliterate and transcribe the following song lyrics line-by-line into clean, natural Latin phonetic script (e.g. Hinglish for Hindi/Bhojpuri/Punjabi, Romaji for Japanese, Pinyin for Chinese, Revised Romanization for Korean). DO NOT translate the meaning into English words. Output ONLY the phonetic singing lyrics written in English letters so users can easily sing along. Maintain the exact same line count and line-by-line correspondence. Do not include markdown, explanations, or notes."

            var translatedText: String? = null

            if (settings.apiKey.isNotBlank()) {
                translatedText = when (settings.provider) {
                    "OpenRouter" -> callOpenAiCompatible(
                        baseUrl = if (settings.customBaseUrl.isNotBlank()) settings.customBaseUrl.trimEnd('/') else "https://openrouter.ai/api/v1",
                        model = settings.model.ifBlank { "google/gemini-2.5-flash-lite" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey,
                        extraHeaders = mapOf(
                            "HTTP-Referer" to "https://github.com/Shreyanshh071/Auralis",
                            "X-Title" to "Auralis Music"
                        )
                    )
                    "OpenAI" -> callOpenAiCompatible(
                        baseUrl = "https://api.openai.com/v1",
                        model = settings.model.ifBlank { "gpt-4o-mini" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey
                    )
                    "Perplexity" -> callOpenAiCompatible(
                        baseUrl = "https://api.perplexity.ai",
                        model = settings.model.ifBlank { "sonar" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey
                    )
                    "Claude" -> callClaudeApi(linesText, effectivePrompt, settings)
                    "Gemini" -> callGeminiApi(linesText, effectivePrompt, settings)
                    "XAi" -> callOpenAiCompatible(
                        baseUrl = "https://api.x.ai/v1",
                        model = settings.model.ifBlank { "grok-2-latest" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey
                    )
                    "Mistral" -> callOpenAiCompatible(
                        baseUrl = "https://api.mistral.ai/v1",
                        model = settings.model.ifBlank { "mistral-small-latest" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey
                    )
                    "DeepL" -> callDeepLApi(originalLines.map { it.text }, settings)
                    "Custom" -> callOpenAiCompatible(
                        baseUrl = if (settings.customBaseUrl.isNotBlank()) settings.customBaseUrl.trimEnd('/') else "http://localhost:11434/v1",
                        model = settings.model.ifBlank { "default" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey
                    )
                    else -> callOpenAiCompatible(
                        baseUrl = "https://openrouter.ai/api/v1",
                        model = settings.model.ifBlank { "google/gemini-2.5-flash-lite" },
                        linesText = linesText,
                        systemPrompt = effectivePrompt,
                        apiKey = settings.apiKey
                    )
                }
            }

            // Fallback: Instant Native Phonetic Romanization (Hinglish/Romaji)
            if (translatedText.isNullOrBlank()) {
                val transliterated = originalLines.map { line ->
                    if (com.auralis.music.data.parser.IndicScriptNormalizer.containsIndicScript(line.text)) {
                        com.auralis.music.data.parser.IndicScriptNormalizer.transliterateToReadableHinglish(line.text)
                    } else {
                        line.text
                    }
                }
                translatedText = transliterated.joinToString("\n")
            }

            if (translatedText.isNullOrBlank()) return@withContext null

            val translatedLines = translatedText.lines().map { it.trim() }
            if (translatedLines.isEmpty()) return@withContext null

            val resultLines = mutableListOf<LyricLine>()
            for (i in originalLines.indices) {
                val orig = originalLines[i]
                val transText = if (i < translatedLines.size) translatedLines[i] else null
                val isDifferent = !transText.isNullOrBlank() && !transText.equals(orig.text, ignoreCase = true)
                resultLines.add(
                    LyricLine(
                        time = orig.time,
                        text = orig.text,
                        translatedText = if (isDifferent) transText else null,
                        words = orig.words,
                        isInstrumental = orig.isInstrumental
                    )
                )
            }

            val translatedLyrics = LyricsData(
                syncType = lyrics.syncType,
                lines = resultLines,
                plainLyrics = lyrics.plainLyrics,
                translatedPlainLyrics = resultLines.mapNotNull { it.translatedText }.joinToString("\n"),
                translatedLanguage = settings.targetLanguage,
                provider = lyrics.provider,
                trackName = lyrics.trackName,
                artistName = lyrics.artistName
            )

            translationCache[cacheKey] = translatedLyrics
            translatedLyrics
        } catch (e: Exception) {
            Log.e(TAG, "Translation error for provider ${settings.provider}: ${e.message}", e)
            null
        }
    }

    private fun callFreeGoogleTranslate(linesText: String, targetLanguage: String): String? {
        try {
            val langCode = when (targetLanguage.lowercase().trim()) {
                "english", "en" -> "en"
                "hindi", "hi" -> "hi"
                "spanish", "es" -> "es"
                "french", "fr" -> "fr"
                "german", "de" -> "de"
                "japanese", "ja" -> "ja"
                "korean", "ko" -> "ko"
                "chinese", "zh" -> "zh-CN"
                "bhojpuri", "bho" -> "bho"
                "punjabi", "pa" -> "pa"
                "tamil", "ta" -> "ta"
                "telugu", "te" -> "te"
                else -> "en"
            }
            val encoded = java.net.URLEncoder.encode(linesText, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$langCode&dt=t&q=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val rootArray = JSONArray(body)
                val sentencesArray = rootArray.optJSONArray(0) ?: return null
                val sb = StringBuilder()
                for (i in 0 until sentencesArray.length()) {
                    val sentence = sentencesArray.optJSONArray(i)
                    val trans = sentence?.optString(0)
                    if (!trans.isNullOrBlank()) {
                        sb.append(trans)
                    }
                }
                return sb.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Free translate error: ${e.message}")
            return null
        }
    }

    private fun callOpenAiCompatible(
        baseUrl: String,
        model: String,
        linesText: String,
        systemPrompt: String,
        apiKey: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        val endpoint = "${baseUrl.trimEnd('/')}/chat/completions"

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", linesText)
                })
            })
            put("temperature", 0.3)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${apiKey.trim()}")

        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        val request = reqBuilder
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "HTTP ${response.code} from $endpoint: ${response.body?.string()}")
            return null
        }

        val respStr = response.body?.string() ?: return null
        val respJson = JSONObject(respStr)
        val choices = respJson.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null

        val message = choices.getJSONObject(0).optJSONObject("message")
        return message?.optString("content")
    }

    private fun callClaudeApi(
        linesText: String,
        systemPrompt: String,
        settings: AiTranslationSettings
    ): String? {
        val endpoint = "https://api.anthropic.com/v1/messages"
        val model = settings.model.ifBlank { "claude-3-5-haiku-20241022" }

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("max_tokens", 4096)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", linesText)
                })
            })
            put("temperature", 0.3)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", settings.apiKey.trim())
            .header("anthropic-version", "2023-06-01")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Claude HTTP ${response.code}: ${response.body?.string()}")
            return null
        }

        val respStr = response.body?.string() ?: return null
        val respJson = JSONObject(respStr)
        val contentArr = respJson.optJSONArray("content") ?: return null
        if (contentArr.length() == 0) return null

        return contentArr.getJSONObject(0).optString("text")
    }

    private fun callGeminiApi(
        linesText: String,
        systemPrompt: String,
        settings: AiTranslationSettings
    ): String? {
        val model = settings.model.ifBlank { "gemini-2.5-flash" }.removePrefix("google/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=${settings.apiKey.trim()}"

        val jsonBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().put("text", systemPrompt))
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", linesText))
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val respStr = response.body?.string() ?: return null
        val respJson = JSONObject(respStr)
        val candidates = respJson.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null

        val content = candidates.getJSONObject(0).optJSONObject("content")
        val parts = content?.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null

        return parts.getJSONObject(0).optString("text")
    }

    private fun callDeepLApi(
        lines: List<String>,
        settings: AiTranslationSettings
    ): String? {
        val isFree = settings.apiKey.trim().endsWith(":fx")
        val endpoint = if (isFree) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"

        val langCode = when (settings.targetLanguage.lowercase()) {
            "english (us)" -> "EN-US"
            "english (uk)" -> "EN-GB"
            "spanish" -> "ES"
            "french" -> "FR"
            "german" -> "DE"
            "japanese" -> "JA"
            "korean" -> "KO"
            "chinese (simplified)", "chinese" -> "ZH"
            "russian" -> "RU"
            "italian" -> "IT"
            "portuguese" -> "PT-BR"
            else -> "EN-US"
        }

        val jsonBody = JSONObject().apply {
            put("text", JSONArray(lines))
            put("target_lang", langCode)
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "DeepL-Auth-Key ${settings.apiKey.trim()}")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val respStr = response.body?.string() ?: return null
        val respJson = JSONObject(respStr)
        val translations = respJson.optJSONArray("translations") ?: return null

        val outLines = mutableListOf<String>()
        for (i in 0 until translations.length()) {
            outLines.add(translations.getJSONObject(i).optString("text"))
        }
        return outLines.joinToString("\n")
    }

    fun clearCache() {
        translationCache.clear()
    }
}
