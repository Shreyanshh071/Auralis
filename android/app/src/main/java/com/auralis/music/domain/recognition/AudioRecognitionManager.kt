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

    init {
        mainHandler.post {
            initSpeechRecognizer()
        }
    }

    private fun initSpeechRecognizer() {
        try {
            if (speechRecognizer != null) {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }

            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            _state.update {
                                it.copy(
                                    status = RecognitionStatus.LISTENING,
                                    statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH)
                                        "Speak song name or artist..."
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
                                    statusMessage = "Finding song in music catalog..."
                                )
                            }
                        }

                        override fun onError(error: Int) {
                            if (!isListening) return

                            // If timed out or no speech detected during ambient music listen, auto-retry smoothly
                            if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && retryCount < maxRetries) {
                                retryCount++
                                mainHandler.postDelayed({
                                    if (isListening && _state.value.status != RecognitionStatus.SUCCESS) {
                                        restartListeningInternal()
                                    }
                                }, 300)
                                return
                            }

                            val message = when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH -> "No music or voice detected. Tap to retry."
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Tap to retry."
                                SpeechRecognizer.ERROR_NETWORK -> "Network connection required."
                                SpeechRecognizer.ERROR_AUDIO -> "Microphone recording error."
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                                else -> "Could not identify. Play music closer to mic."
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
                                            statusMessage = "Could not identify track. Tap to retry."
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
            }
        } catch (_: Exception) {}
    }

    private fun handleRecognizedQuery(query: String) {
        _state.update {
            it.copy(
                status = RecognitionStatus.PROCESSING,
                recognizedText = query,
                statusMessage = "Searching for \"$query\"..."
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
                statusMessage = if (mode == RecognitionMode.VOICE_SEARCH) "Tap to speak song name" else "Play music near your phone..."
            )
        }
    }

    fun startVoiceSearch() {
        setMode(RecognitionMode.VOICE_SEARCH)
        startListeningInternal()
    }

    fun startMusicIdentification() {
        setMode(RecognitionMode.MUSIC_IDENTIFY)
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

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
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
            } catch (_: Exception) {}
        }
    }
}
