package com.auralis.music.domain.model

data class AiTranslationSettings(
    val provider: String = "OpenRouter",
    val apiKey: String = "",
    val model: String = "google/gemini-2.5-flash-lite",
    val translationMode: String = "Translation",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val targetLanguage: String = "English (US)",
    val customBaseUrl: String = "https://openrouter.ai/api/v1"
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "Default"
        const val STANDARD_SYSTEM_PROMPT =
            "You are an expert multilingual music lyric translator. Translate the following song lyrics line-by-line into the requested target language. Preserve the exact line-by-line structure, rhythm, and artistic meaning without adding timestamps, markdown explanations, or numbers."
    }
}
