package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.AiTranslationSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.aiTranslationDataStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_ai_translation")

class AiTranslationDataStore(context: Context) {
    private val dataStore = context.aiTranslationDataStore

    companion object {
        val PROVIDER = stringPreferencesKey("ai_provider")
        val API_KEY = stringPreferencesKey("ai_api_key")
        val MODEL = stringPreferencesKey("ai_model")
        val TRANSLATION_MODE = stringPreferencesKey("ai_translation_mode")
        val SYSTEM_PROMPT = stringPreferencesKey("ai_system_prompt")
        val TARGET_LANGUAGE = stringPreferencesKey("ai_target_language")
        val CUSTOM_BASE_URL = stringPreferencesKey("ai_custom_base_url")
    }

    val settingsFlow: Flow<AiTranslationSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AiTranslationSettings(
                provider = preferences[PROVIDER] ?: "OpenRouter",
                apiKey = preferences[API_KEY] ?: "",
                model = preferences[MODEL] ?: "google/gemini-2.5-flash-lite",
                translationMode = preferences[TRANSLATION_MODE] ?: "Translation",
                systemPrompt = preferences[SYSTEM_PROMPT] ?: AiTranslationSettings.DEFAULT_SYSTEM_PROMPT,
                targetLanguage = preferences[TARGET_LANGUAGE] ?: "English (US)",
                customBaseUrl = preferences[CUSTOM_BASE_URL] ?: "https://openrouter.ai/api/v1"
            )
        }

    suspend fun updateSettings(settings: AiTranslationSettings) {
        dataStore.edit { preferences ->
            preferences[PROVIDER] = settings.provider
            preferences[API_KEY] = settings.apiKey
            preferences[MODEL] = settings.model
            preferences[TRANSLATION_MODE] = settings.translationMode
            preferences[SYSTEM_PROMPT] = settings.systemPrompt
            preferences[TARGET_LANGUAGE] = settings.targetLanguage
            preferences[CUSTOM_BASE_URL] = settings.customBaseUrl
        }
    }
}
