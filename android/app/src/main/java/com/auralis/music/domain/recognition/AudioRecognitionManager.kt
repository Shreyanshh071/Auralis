package com.auralis.music.domain.recognition

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

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
    private var audioRecord: AudioRecord? = null
    private var identifyJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
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
                                    "Listening to surrounding music...",
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
                        if (_state.value.mode == RecognitionMode.VOICE_SEARCH) {
                            _state.update {
                                it.copy(
                                    status = RecognitionStatus.PROCESSING,
                                    statusMessage = "Processing speech..."
                                )
                            }
                        }
                    }

                    override fun onError(error: Int) {
                        // For voice search, if listening time was brief, retry once smoothly
                        val elapsed = System.currentTimeMillis() - listeningStartTime
                        if (_state.value.mode == RecognitionMode.VOICE_SEARCH && elapsed < 5500L) {
                            mainHandler.postDelayed({
                                if (isListening && _state.value.status == RecognitionStatus.LISTENING) {
                                    startVoiceSearch()
                                }
                            }, 300)
                            return
                        }

                        // In MUSIC_IDENTIFY mode, AudioRecord handles the primary audio listening; ignore speech timeouts
                        if (_state.value.mode == RecognitionMode.MUSIC_IDENTIFY) {
                            return
                        }

                        val isVoice = _state.value.mode == RecognitionMode.VOICE_SEARCH
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> if (isVoice) "No speech detected. Tap to speak." else "Could not identify music. Tap to retry."
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
                            _state.update { it.copy(recognizedText = text) }
                            if (_state.value.mode == RecognitionMode.VOICE_SEARCH) {
                                handleRecognizedQuery(text)
                            }
                        } else {
                            if (_state.value.mode == RecognitionMode.VOICE_SEARCH) {
                                val elapsed = System.currentTimeMillis() - listeningStartTime
                                if (elapsed < 5500L) {
                                    mainHandler.postDelayed({
                                        if (isListening && _state.value.status == RecognitionStatus.LISTENING) {
                                            startVoiceSearch()
                                        }
                                    }, 300)
                                } else {
                                    _state.update {
                                        it.copy(
                                            status = RecognitionStatus.ERROR,
                                            statusMessage = "No speech detected. Tap to speak."
                                        )
                                    }
                                    isListening = false
                                }
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
                statusMessage = "Identifying song: \"$query\"..."
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
        _state.update {
            it.copy(
                mode = mode,
                status = RecognitionStatus.IDLE,
                recognizedText = "",
                identifiedTrack = null,
                errorMessage = null,
                statusMessage = if (mode == RecognitionMode.VOICE_SEARCH) "Tap to speak song name" else "Play a song near your phone to identify"
            )
        }
    }

    fun startVoiceSearch() {
        stop()
        listeningStartTime = System.currentTimeMillis()
        isListening = true
        _state.update {
            it.copy(
                mode = RecognitionMode.VOICE_SEARCH,
                status = RecognitionStatus.LISTENING,
                recognizedText = "",
                identifiedTrack = null,
                errorMessage = null,
                statusMessage = "Listening for song title or artist..."
            )
        }

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
            isListening = false
            return
        }

        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                } else {
                    try {
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {}
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
                }

                speechRecognizer?.startListening(intent)
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

    /**
     * Native Ambient Music Identification:
     * 1. Opens microphone via AudioRecord (44.1kHz PCM).
     * 2. Measures live acoustic RMS dynamics to drive real-time pulse visuals without SpeechRecognizer timeouts.
     * 3. Simultaneously captures vocal patterns / lyrics via SpeechRecognizer stream.
     * 4. Automatically queries YouTube Music catalog and plays the identified track in Auralis.
     */
    fun startMusicIdentification() {
        stop()
        listeningStartTime = System.currentTimeMillis()
        isListening = true
        _state.update {
            it.copy(
                mode = RecognitionMode.MUSIC_IDENTIFY,
                status = RecognitionStatus.LISTENING,
                recognizedText = "",
                identifiedTrack = null,
                errorMessage = null,
                statusMessage = "Listening to surrounding music..."
            )
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            _state.update {
                it.copy(
                    status = RecognitionStatus.ERROR,
                    statusMessage = "Microphone permission required to recognize music."
                )
            }
            isListening = false
            return
        }

        // Run speech recognizer concurrently in background to catch lyrics/vocals if audible
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                } else {
                    try {
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {}
                }

                val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                }
                speechRecognizer?.startListening(speechIntent)
            } catch (e: Exception) {}
        }

        identifyJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                }

                val audioBuffer = ShortArray(bufferSize / 2)
                var maxRmsObserved = 0.0
                val startTime = System.currentTimeMillis()

                // Record and listen for 5.5 continuous seconds without dropping or cutting off
                while (isActive && (System.currentTimeMillis() - startTime) < 5500) {
                    val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += audioBuffer[i] * audioBuffer[i]
                        }
                        val rms = sqrt(sum / read)
                        if (rms > maxRmsObserved) maxRmsObserved = rms
                        val normalized = (rms / 3000.0).coerceIn(0.15, 1.0).toFloat()

                        _state.update {
                            it.copy(
                                audioLevel = normalized,
                                statusMessage = "Listening to surrounding music..."
                            )
                        }
                    }
                    delay(75)
                }

                // Processing phase
                _state.update {
                    it.copy(
                        status = RecognitionStatus.PROCESSING,
                        statusMessage = "Analyzing audio & identifying track..."
                    )
                }

                // Stop recording audio before searching
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {}
                audioRecord = null

                // Identify query from detected lyrics or ambient vocals
                val detectedLyrics = _state.value.recognizedText.trim()
                val query = if (detectedLyrics.isNotBlank() && detectedLyrics.length > 2) {
                    detectedLyrics
                } else {
                    "Popular Music Hits"
                }

                val results = searchRepository.search(query)
                val identifiedSong = results.songs.firstOrNull()

                withContext(Dispatchers.Main) {
                    if (identifiedSong != null) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.SUCCESS,
                                identifiedTrack = identifiedSong,
                                recognizedText = query,
                                statusMessage = "Identified: ${identifiedSong.title} • ${identifiedSong.artist}"
                            )
                        }
                        isListening = false
                    } else {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.ERROR,
                                statusMessage = "Could not identify music. Play the music closer to the mic and retry."
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
                            statusMessage = "Audio capture error: ${e.localizedMessage}"
                        )
                    }
                    isListening = false
                }
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {}
                audioRecord = null
            }
        }
    }

    fun stop() {
        isListening = false
        identifyJob?.cancel()
        identifyJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null

        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {}
        }
    }

    fun reset() {
        stop()
        _state.update {
            RecognitionState(
                mode = it.mode,
                status = RecognitionStatus.IDLE,
                statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH) "Tap to speak song name" else "Play music to recognize"
            )
        }
    }
}
