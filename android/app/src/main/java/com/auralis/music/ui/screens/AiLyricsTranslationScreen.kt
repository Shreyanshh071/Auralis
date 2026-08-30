package com.auralis.music.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.AiTranslationDataStore
import com.auralis.music.domain.model.AiTranslationSettings
import kotlinx.coroutines.launch

private enum class AiTranslationDialog {
    PROVIDER,
    API_KEY,
    MODEL,
    MODE,
    PROMPT,
    LANGUAGE,
    PROVIDER_INFO,
    MODE_INFO
}

@Composable
fun AiLyricsTranslationScreen(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { AiTranslationDataStore(context) }
    val settings by dataStore.settingsFlow.collectAsState(initial = AiTranslationSettings())

    var activeDialog by remember { mutableStateOf<AiTranslationDialog?>(null) }

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val onBackground = MaterialTheme.colorScheme.onBackground

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // ── TOP APP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "AI lyrics translation",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 21.sp
                )
            }

            // ── SECTIONS LIST ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ════ 1. PROVIDER ════
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Provider",
                            color = primaryColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AiSettingRow(
                                icon = Icons.Default.Explore,
                                title = "Provider",
                                subtitle = settings.provider,
                                onInfoClick = { activeDialog = AiTranslationDialog.PROVIDER_INFO },
                                onClick = { activeDialog = AiTranslationDialog.PROVIDER }
                            )
                        }
                    }
                }

                // ════ 2. API CREDENTIALS ════
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "API credentials",
                            color = primaryColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                AiSettingRow(
                                    icon = Icons.Default.VpnKey,
                                    title = "API key",
                                    subtitle = if (settings.apiKey.isBlank()) "Not set" else "••••••••••••••••",
                                    onClick = { activeDialog = AiTranslationDialog.API_KEY }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )

                                AiSettingRow(
                                    icon = Icons.Default.Tune,
                                    title = "Model",
                                    subtitle = settings.model,
                                    onClick = { activeDialog = AiTranslationDialog.MODEL }
                                )
                            }
                        }
                    }
                }

                // ════ 3. TRANSLATION MODE ════
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Translation mode",
                            color = primaryColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp,
                            modifier = Modifier.padding(start = 4.dp)
                        )

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = surfaceColor,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                AiSettingRow(
                                    icon = Icons.Default.Translate,
                                    title = "Translation mode",
                                    subtitle = settings.translationMode,
                                    onInfoClick = { activeDialog = AiTranslationDialog.MODE_INFO },
                                    onClick = { activeDialog = AiTranslationDialog.MODE }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )

                                AiSettingRow(
                                    icon = Icons.Default.Edit,
                                    title = "System prompt",
                                    subtitle = if (settings.systemPrompt.equals(AiTranslationSettings.DEFAULT_SYSTEM_PROMPT, ignoreCase = true) || settings.systemPrompt.isBlank()) "Default" else "Custom",
                                    onClick = { activeDialog = AiTranslationDialog.PROMPT }
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                )

                                AiSettingRow(
                                    icon = Icons.Default.Language,
                                    title = "Target language",
                                    subtitle = settings.targetLanguage,
                                    onClick = { activeDialog = AiTranslationDialog.LANGUAGE }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── DIALOGS ──
    when (activeDialog) {
        AiTranslationDialog.PROVIDER -> {
            val providers = listOf(
                "OpenRouter",
                "OpenAI",
                "Perplexity",
                "Claude",
                "Gemini",
                "XAi",
                "Mistral",
                "DeepL",
                "Custom"
            )
            var showCustomUrl by remember { mutableStateOf(settings.provider == "Custom") }
            var customUrlText by remember { mutableStateOf(settings.customBaseUrl) }

            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(24.dp),
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(providers.size) { idx ->
                            val p = providers[idx]
                            val isSel = settings.provider == p
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (p == "Custom") {
                                            showCustomUrl = true
                                            scope.launch { dataStore.updateSettings(settings.copy(provider = p)) }
                                        } else {
                                            showCustomUrl = false
                                            val defaultModelForP = when (p) {
                                                "OpenRouter" -> "google/gemini-2.5-flash-lite"
                                                "OpenAI" -> "openai/gpt-4o-mini"
                                                "Perplexity" -> "sonar"
                                                "Claude" -> "claude-3-5-haiku-20241022"
                                                "Gemini" -> "google/gemini-2.5-flash"
                                                "XAi" -> "x-ai/grok-4.1-fast"
                                                "Mistral" -> "mistral-small-latest"
                                                "DeepL" -> "DeepL v2"
                                                else -> settings.model
                                            }
                                            scope.launch {
                                                dataStore.updateSettings(
                                                    settings.copy(
                                                        provider = p,
                                                        model = defaultModelForP
                                                    )
                                                )
                                            }
                                            activeDialog = null
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = primaryColor,
                                        unselectedColor = onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = p,
                                    color = if (isSel) primaryColor else onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }

                        if (showCustomUrl || settings.provider == "Custom") {
                            item {
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedTextField(
                                    value = customUrlText,
                                    onValueChange = { customUrlText = it },
                                    label = { Text("Custom API Base URL", fontSize = 12.sp) },
                                    placeholder = { Text("https://api.openai.com/v1", fontSize = 12.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(onClick = { activeDialog = null }) {
                                        Text("Cancel", color = onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                dataStore.updateSettings(
                                                    settings.copy(
                                                        provider = "Custom",
                                                        customBaseUrl = customUrlText.trim()
                                                    )
                                                )
                                            }
                                            activeDialog = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                                    ) {
                                        Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }

        AiTranslationDialog.API_KEY -> {
            var keyInput by remember { mutableStateOf(settings.apiKey) }
            var isPasswordVisible by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                title = { Text("API Key", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Enter your ${settings.provider} API key. Keys are stored locally on your device only.",
                            fontSize = 12.5.sp,
                            color = onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            placeholder = { Text("sk-or-v1-...", color = onSurfaceVariant.copy(alpha = 0.5f)) },
                            singleLine = true,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = "Toggle visibility",
                                        tint = onSurfaceVariant
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (settings.provider == "OpenRouter") {
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/keys")))
                                    } catch (_: Exception) {}
                                },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Get free OpenRouter API key ↗", color = primaryColor, fontSize = 12.5.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch { dataStore.updateSettings(settings.copy(apiKey = keyInput.trim())) }
                            Toast.makeText(context, "API Key saved", Toast.LENGTH_SHORT).show()
                            activeDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                    ) {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) { Text("Cancel", color = onSurfaceVariant) }
                }
            )
        }

        AiTranslationDialog.MODEL -> {
            val presets = listOf(
                "google/gemini-2.5-flash-lite",
                "google/gemini-2.5-flash",
                "x-ai/grok-4.1-fast",
                "deepseek/deepseek-v3.1-terminus:exacto",
                "openai/gpt-4o-mini",
                "meta-llama/llama-4-scout",
                "openai/gpt-5-nano",
                "openai/gpt-oss-120b",
                "google/gemini-3-flash-preview"
            )
            var showCustomInput by remember { mutableStateOf(settings.model !in presets) }
            var customModelText by remember { mutableStateOf(if (settings.model !in presets) settings.model else "") }

            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                shape = RoundedCornerShape(24.dp),
                text = {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(presets.size) { idx ->
                            val m = presets[idx]
                            val isSel = settings.model == m && !showCustomInput
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        showCustomInput = false
                                        scope.launch { dataStore.updateSettings(settings.copy(model = m)) }
                                        activeDialog = null
                                    }
                                    .padding(vertical = 8.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = primaryColor,
                                        unselectedColor = onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = m,
                                    color = if (isSel) primaryColor else onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }

                        item {
                            val isCustomSel = showCustomInput || settings.model !in presets
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            showCustomInput = true
                                        }
                                        .padding(vertical = 8.dp, horizontal = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isCustomSel,
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = primaryColor,
                                            unselectedColor = onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Custom",
                                        color = if (isCustomSel) primaryColor else onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = if (isCustomSel) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                }

                                if (showCustomInput) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedTextField(
                                        value = customModelText,
                                        onValueChange = { customModelText = it },
                                        placeholder = { Text("Enter custom model ID", fontSize = 13.sp) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(onClick = { activeDialog = null }) {
                                            Text("Cancel", color = onSurfaceVariant)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Button(
                                            onClick = {
                                                if (customModelText.isNotBlank()) {
                                                    scope.launch { dataStore.updateSettings(settings.copy(model = customModelText.trim())) }
                                                }
                                                activeDialog = null
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                                        ) {
                                            Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }

        AiTranslationDialog.MODE -> {
            val modes = listOf(
                "Translation" to "Translate meaning into target language",
                "Transcription" to "Phonetic script / Romanization (Romaji/Pinyin/Hinglish)"
            )
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                title = { Text("Translation Mode", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        modes.forEach { (m, desc) ->
                            val isSel = settings.translationMode.equals(m, ignoreCase = true) ||
                                (m == "Transcription" && (settings.translationMode.contains("Romanization", ignoreCase = true) || settings.translationMode.contains("Transliteration", ignoreCase = true)))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSel) primaryColor.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable {
                                        scope.launch { dataStore.updateSettings(settings.copy(translationMode = m)) }
                                        activeDialog = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = isSel, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = primaryColor))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(m, color = if (isSel) primaryColor else onSurface, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                    Text(desc, color = onSurfaceVariant, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = null }) { Text("Cancel", color = onSurfaceVariant) }
                }
            )
        }

        AiTranslationDialog.PROMPT -> {
            var promptInput by remember { mutableStateOf(if (settings.systemPrompt == "Default") AiTranslationSettings.STANDARD_SYSTEM_PROMPT else settings.systemPrompt) }

            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                title = { Text("System Prompt", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            minLines = 4,
                            maxLines = 8,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextButton(
                            onClick = { promptInput = AiTranslationSettings.STANDARD_SYSTEM_PROMPT },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("Reset to Default", color = primaryColor, fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val toSave = if (promptInput.trim() == AiTranslationSettings.STANDARD_SYSTEM_PROMPT.trim()) "Default" else promptInput.trim()
                            scope.launch { dataStore.updateSettings(settings.copy(systemPrompt = toSave)) }
                            activeDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                    ) {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) { Text("Cancel", color = onSurfaceVariant) }
                }
            )
        }

        AiTranslationDialog.LANGUAGE -> {
            val languages = listOf(
                "English (US)", "English (UK)", "Spanish", "French", "German",
                "Japanese", "Korean", "Hindi", "Chinese (Simplified)", "Chinese (Traditional)",
                "Russian", "Italian", "Portuguese", "Arabic", "Bengali", "Marathi",
                "Telugu", "Tamil", "Urdu", "Indonesian", "Turkish", "Vietnamese"
            )
            var query by remember { mutableStateOf("") }
            val filtered = languages.filter { it.contains(query, ignoreCase = true) }

            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                title = { Text("Target Language", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search language...", color = onSurfaceVariant.copy(alpha = 0.5f)) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = onSurfaceVariant) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filtered.size) { i ->
                                val l = filtered[i]
                                val isSel = settings.targetLanguage == l
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) primaryColor.copy(alpha = 0.12f) else Color.Transparent)
                                    .clickable {
                                        scope.launch { dataStore.updateSettings(settings.copy(targetLanguage = l)) }
                                        activeDialog = null
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = isSel, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = primaryColor))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(l, color = if (isSel) primaryColor else onSurface, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = null }) { Text("Cancel", color = onSurfaceVariant) }
                }
            )
        }

        AiTranslationDialog.PROVIDER_INFO -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                title = { Text("About Providers", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Text(
                        "OpenRouter provides access to over 100+ AI models including free tiers from Google (Gemini Flash Lite), Meta (Llama 3), and DeepSeek. You can get an API key with free monthly allowances at openrouter.ai.",
                        color = onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = null }) { Text("Got it", color = primaryColor) }
                }
            )
        }

        AiTranslationDialog.MODE_INFO -> {
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = surfaceColor,
                title = { Text("Translation Modes", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("• Translation: Converts lyrics meaning into your target language with 1:1 time synchronization.", color = onSurfaceVariant, fontSize = 12.5.sp)
                        Text("• Transcription: Converts non-Latin scripts (Japanese, Korean, Hindi, Chinese) into Latin phonetic alphabet (Romaji, Pinyin, Hinglish) for singing along.", color = onSurfaceVariant, fontSize = 12.5.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { activeDialog = null }) { Text("Got it", color = primaryColor) }
                }
            )
        }

        null -> {}
    }
}

@Composable
private fun AiSettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onInfoClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(primaryColor.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = onSurfaceVariant,
                fontSize = 12.5.sp
            )
        }

        if (onInfoClick != null) {
            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info",
                    tint = onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
