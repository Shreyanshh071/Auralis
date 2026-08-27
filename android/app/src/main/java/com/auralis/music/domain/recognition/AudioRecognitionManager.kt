package com.auralis.music.domain.recognition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class RecognitionMode {
    VOICE_SEARCH,
    MUSIC_IDENTIFY
}

enum class RecognitionStatus {
    IDLE,
    LISTENING,
    PROCESSING,
    SUCCESS,
    ERROR
}

data class RecognitionState(
    val mode: RecognitionMode = RecognitionMode.VOICE_SEARCH,
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    val recognizedText: String = "",
    val identifiedTrack: Track? = null,
    val audioLevel: Float = 0.0f,
    val statusMessage: String = "Listening for song or artist...",
    val errorMessage: String? = null
)

class AudioRecognitionManager(
    private val context: Context,
    private val searchRepository: SearchRepository,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(RecognitionState())
    val state: StateFlow<RecognitionState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var retryCount = 0
    private var maxRetries = 5
    private var listeningStartTime = 0L

    init {
        mainHandler.post {
            initSpeechRecognizer()
        }
    }

    private fun initSpeechRecognizer() {
        try {
            if (speechRecognizer != null) {
                try {
                    speechRecognizer?.destroy()
                } catch (e: Exception) {}
                speechRecognizer = null
            }

            speechRecognizer = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
            } catch (e: Exception) {
                try {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } catch (e2: Exception) {
                    null
                }
            }

            speechRecognizer?.apply {
                setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            _state.update {
                                it.copy(
                                    status = RecognitionStatus.LISTENING,
                                    statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH)
                                        "Listening... Speak song or artist name"
                                    else
                                        "Listening to music near your phone...",
                                    errorMessage = null
                                )
                            }
                        }

                        override fun onBeginningOfSpeech() {
                            _state.update { it.copy(status = RecognitionStatus.LISTENING) }
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.15f, 1.0f)
                            _state.update { it.copy(audioLevel = normalized) }
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            _state.update {
                                it.copy(
                                    status = RecognitionStatus.PROCESSING,
                                    statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH)
                                        "Processing speech..."
                                    else
                                        "Finding song in music catalog..."
                                )
                            }
                        }

                        override fun onError(error: Int) {
                            // If error occurred during start/listen phase and we have retries remaining,
                            // smoothly retry in background without flashing the error screen to the user
                            if (retryCount < maxRetries) {
                                retryCount++
                                mainHandler.postDelayed({
                                    if (isListening && _state.value.status != RecognitionStatus.SUCCESS) {
                                        restartListeningInternal()
                                    }
                                }, 350)
                                return
                            }

                            val isVoice = _state.value.mode == RecognitionMode.VOICE_SEARCH
                            val message = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> if (isVoice) "No speech detected. Tap to speak." else "Could not detect music. Tap to retry."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (isVoice) "Listening timed out. Tap to speak." else "Listening timed out. Tap to retry."
                                SpeechRecognizer.ERROR_NETWORK -> "Network connection required."
                                SpeechRecognizer.ERROR_AUDIO -> "Microphone recording error."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                                else -> if (isVoice) "Could not recognize speech. Tap to try again." else "Could not identify. Play music closer to mic."
                            }
                            _state.update {
                                it.copy(
                                    status = RecognitionStatus.ERROR,
                                    statusMessage = message,
                                    errorMessage = message
                                )
                            }
                            isListening = false
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            if (text.isNotBlank()) {
                                handleRecognizedQuery(text)
                            } else {
                                if (retryCount < maxRetries) {
                                    retryCount++
                                    restartListeningInternal()
                                } else {
                                    _state.update {
                                        it.copy(
                                            status = RecognitionStatus.ERROR,
                                            statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH)
                                                "No speech detected. Tap to speak."
                                            else
                                                "Could not identify track. Tap to retry."
                                        )
                                    }
                                    isListening = false
                                }
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull() ?: ""
                            if (text.isNotBlank()) {
                                _state.update { it.copy(recognizedText = text) }
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            } catch (e: Exception) {}
    }

    private fun handleRecognizedQuery(query: String) {
        if (_state.value.mode == RecognitionMode.VOICE_SEARCH) {
            _state.update {
                it.copy(
                    status = RecognitionStatus.SUCCESS,
                    recognizedText = query,
                    statusMessage = "Searching for \"$query\"..."
                )
            }
            isListening = false
            return
        }

        // Ambient Music Identification Mode
        _state.update {
            it.copy(
                status = RecognitionStatus.PROCESSING,
                recognizedText = query,
                statusMessage = "Searching for song: \"$query\"..."
            )
        }

        scope.launch(Dispatchers.IO) {
            try {
                val results = searchRepository.search(query)
                val song = results.songs.firstOrNull()

                withContext(Dispatchers.Main) {
                    if (song != null) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.SUCCESS,
                                recognizedText = query,
                                identifiedTrack = song,
                                statusMessage = "Identified: ${song.title} • ${song.artist}"
                            )
                        }
                        isListening = false
                    } else {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.ERROR,
                                statusMessage = "No matching track found for \"$query\"."
                            )
                        }
                        isListening = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            status = RecognitionStatus.ERROR,
                            statusMessage = "Search error: ${e.localizedMessage}"
                        )
                    }
                    isListening = false
                }
            }
        }
    }

    fun setMode(mode: RecognitionMode) {
        stop()
        retryCount = 0
        _state.update {
            it.copy(
                mode = mode,
                status = RecognitionStatus.IDLE,
                recognizedText = "",
                identifiedTrack = null,
                errorMessage = null,
                statusMessage = if (mode == RecognitionMode.VOICE_SEARCH) "Tap to speak song or artist name" else "Play music near your phone..."
            )
        }
    }

    fun startVoiceSearch() {
        stop()
        retryCount = 0
        listeningStartTime = System.currentTimeMillis()
        _state.update {
            it.copy(
                mode = RecognitionMode.VOICE_SEARCH,
                status = RecognitionStatus.LISTENING,
                recognizedText = "",
                identifiedTrack = null,
                errorMessage = null,
                statusMessage = "Listening... Speak song or artist name"
            )
        }
        startListeningInternal()
    }

    fun startMusicIdentification() {
        stop()
        retryCount = 0
        listeningStartTime = System.currentTimeMillis()
        _state.update {
            it.copy(
                mode = RecognitionMode.MUSIC_IDENTIFY,
                status = RecognitionStatus.LISTENING,
                recognizedText = "",
                identifiedTrack = null,
                errorMessage = null,
                statusMessage = "Listening to music near your phone..."
            )
        }
        startListeningInternal()
    }

    private fun startListeningInternal() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            _state.update {
                it.copy(
                    status = RecognitionStatus.ERROR,
                    statusMessage = "Microphone permission required."
                )
            }
            return
        }

        retryCount = 0
        restartListeningInternal()
    }

    private fun restartListeningInternal() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                } else {
                    try {
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {}
                }

                if (speechRecognizer == null) {
                    _state.update {
                        it.copy(
                            status = RecognitionStatus.ERROR,
                            statusMessage = "Speech recognizer unavailable on this device."
                        )
                    }
                    isListening = false
                    return@post
                }

                _state.update {
                    it.copy(
                        status = RecognitionStatus.LISTENING,
                        statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH)
                            "Listening for song name or artist..."
                        else
                            "Listening to music near your phone...",
                        errorMessage = null
                    )
                }

                val isMusic = _state.value.mode == RecognitionMode.MUSIC_IDENTIFY
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (isMusic) 8000L else 4000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (isMusic) 6000L else 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2500L)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = RecognitionStatus.ERROR,
                        statusMessage = "Microphone error: ${e.localizedMessage}"
                    )
                }
                isListening = false
            }
        }
    }

    fun stop() {
        isListening = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {}
        }
    }
}
