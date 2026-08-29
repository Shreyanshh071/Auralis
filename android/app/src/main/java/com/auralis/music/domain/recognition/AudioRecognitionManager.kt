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
import com.auralis.music.data.datastore.MusicRecognitionHistoryDataStore
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    private val historyDataStore = MusicRecognitionHistoryDataStore(context.applicationContext)
    val historyFlow: Flow<List<RecognitionHistoryItem>> = historyDataStore.historyFlow

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

    fun clearHistory() {
        scope.launch(Dispatchers.IO) {
            historyDataStore.clearHistory()
        }
    }

    fun removeHistoryItem(trackId: String) {
        scope.launch(Dispatchers.IO) {
            historyDataStore.removeRecognition(trackId)
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

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _state.update {
                        it.copy(
                            status = RecognitionStatus.LISTENING,
                            statusMessage = "Listening... Speak song or artist name",
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
                    if (_state.value.mode != RecognitionMode.VOICE_SEARCH) return

                    val elapsed = System.currentTimeMillis() - listeningStartTime
                    if (elapsed < 5000L) {
                        mainHandler.postDelayed({
                            if (isListening && _state.value.status == RecognitionStatus.LISTENING) {
                                startVoiceSearch()
                            }
                        }, 300)
                        return
                    }

                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Tap to speak."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out. Tap to speak."
                        SpeechRecognizer.ERROR_NETWORK -> "Network connection required."
                        SpeechRecognizer.ERROR_AUDIO -> "Microphone recording error."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
                        else -> "Could not recognize speech. Tap to try again."
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
                    val recognized = matches?.firstOrNull().orEmpty()

                    if (recognized.isNotBlank()) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.SUCCESS,
                                recognizedText = recognized,
                                statusMessage = "\"$recognized\""
                            )
                        }
                    } else {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.ERROR,
                                statusMessage = "No speech detected. Tap to try again."
                            )
                        }
                    }
                    isListening = false
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) {
                        _state.update {
                            it.copy(
                                recognizedText = partial,
                                statusMessage = "\"$partial\""
                            )
                        }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (e: Exception) {
            android.util.Log.e("AudioRecognition", "SpeechRecognizer init failed", e)
        }
    }

    fun setMode(mode: RecognitionMode) {
        stopListening()
        _state.update {
            it.copy(
                mode = mode,
                status = RecognitionStatus.IDLE,
                recognizedText = "",
                identifiedTrack = null,
                audioLevel = 0f,
                statusMessage = if (mode == RecognitionMode.VOICE_SEARCH) "Tap to speak" else "Tap to listen",
                errorMessage = null
            )
        }
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        listeningStartTime = System.currentTimeMillis()

        _state.update {
            it.copy(
                status = RecognitionStatus.LISTENING,
                recognizedText = "",
                identifiedTrack = null,
                audioLevel = 0f,
                statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH) "Listening..." else "Listening for music around you...",
                errorMessage = null
            )
        }

        if (_state.value.mode == RecognitionMode.VOICE_SEARCH) {
            startVoiceSearch()
        } else {
            startMusicIdentification()
        }
    }

    private fun startVoiceSearch() {
        mainHandler.post {
            try {
                if (speechRecognizer == null) {
                    initSpeechRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = RecognitionStatus.ERROR,
                        statusMessage = "Could not access speech recognizer: ${e.localizedMessage}"
                    )
                }
                isListening = false
            }
        }
    }

    private fun startMusicIdentification() {
        identifyJob?.cancel()

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

        identifyJob = scope.launch(Dispatchers.IO) {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(4096)

            val totalSamplesToRecord = (6.0 * sampleRate).toInt() // 6.0 seconds for robust recognition
            val output = ShortArray(totalSamplesToRecord)
            val audioBuffer = ShortArray(minBufferSize / 2)

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    minBufferSize
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.startRecording()
                }

                var totalWritten = 0
                val startTime = System.currentTimeMillis()

                // Record for 6.0 seconds
                while (isActive && totalWritten < output.size && (System.currentTimeMillis() - startTime) < 6200) {
                    val toRead = minOf(audioBuffer.size, output.size - totalWritten)
                    val read = audioRecord?.read(audioBuffer, 0, toRead) ?: 0
                    if (read > 0) {
                        System.arraycopy(audioBuffer, 0, output, totalWritten, read)
                        totalWritten += read

                        var sum = 0.0
                        for (i in 0 until read) {
                            val sample = audioBuffer[i].toInt()
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / read)
                        val normalized = (rms / 3000.0).coerceIn(0.15, 1.0).toFloat()

                        _state.update {
                            it.copy(
                                audioLevel = normalized,
                                statusMessage = "Listening for music around you..."
                            )
                        }
                    }
                    delay(40)
                }

                // Stop audio recording
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (e: Exception) {}
                audioRecord = null

                // Processing phase
                _state.update {
                    it.copy(
                        status = RecognitionStatus.PROCESSING,
                        statusMessage = "Analyzing audio with Shazam..."
                    )
                }

                if (totalWritten < sampleRate) {
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.ERROR,
                                statusMessage = "Audio recording too short. Please try again."
                            )
                        }
                        isListening = false
                    }
                    return@launch
                }

                val recordedShorts = output.copyOf(totalWritten)

                // 1. Generate Shazam acoustic signature
                val signature = withContext(Dispatchers.Default) {
                    ShazamSignatureGenerator(maxTimeSeconds = 6.0).apply {
                        feedPcm16Mono(recordedShorts)
                    }.nextSignatureOrNull()
                }

                if (signature == null) {
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.ERROR,
                                statusMessage = "Could not generate acoustic fingerprint."
                            )
                        }
                        isListening = false
                    }
                    return@launch
                }

                // 2. Query Shazam Discovery API
                val recognitionResult = Shazam.recognize(signature.uri, signature.sampleDurationMs).getOrNull()

                if (recognitionResult != null) {
                    // Save to persistent recognition history
                    historyDataStore.addRecognition(recognitionResult)

                    // 1. Fetch official songs and general songs from YouTube Music
                    val query = "${recognitionResult.title} ${recognitionResult.artist}".trim()
                    val officialSongs = try {
                        searchRepository.searchSongs(query)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    val generalResults = try {
                        searchRepository.search(query).songs
                    } catch (e: Exception) {
                        emptyList()
                    }

                    val allCandidates = (officialSongs + generalResults).distinctBy { it.id }

                    // 2. Select the authentic original studio version (filtering out remixes, sped up, live, etc.)
                    val matchedSong = findBestOfficialTrackMatch(recognitionResult, allCandidates) ?: Track(
                        id = recognitionResult.youtubeVideoId ?: "shazam_${recognitionResult.trackId}",
                        title = recognitionResult.title,
                        artist = recognitionResult.artist,
                        album = recognitionResult.album.orEmpty(),
                        thumbnail = recognitionResult.coverArtHqUrl ?: recognitionResult.coverArtUrl.orEmpty(),
                        duration = 0
                    )

                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.SUCCESS,
                                identifiedTrack = matchedSong,
                                recognizedText = "${recognitionResult.title} • ${recognitionResult.artist}",
                                statusMessage = "Identified: ${recognitionResult.title} • ${recognitionResult.artist}"
                            )
                        }
                        isListening = false
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                status = RecognitionStatus.ERROR,
                                statusMessage = "No match found. Play the music closer to the mic or tap Google Sound Search below."
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
                            statusMessage = "Recognition error: ${e.localizedMessage ?: "Could not capture audio"}"
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

    private fun findBestOfficialTrackMatch(
        recognitionResult: RecognitionResult,
        candidates: List<Track>
    ): Track? {
        if (candidates.isEmpty()) return null

        // Priority 1: Exact YouTube video ID match from Shazam metadata
        if (!recognitionResult.youtubeVideoId.isNullOrBlank()) {
            candidates.firstOrNull { it.id == recognitionResult.youtubeVideoId }?.let { return it }
        }

        val recTitle = cleanTitle(recognitionResult.title)
        val recArtist = cleanArtist(recognitionResult.artist)
        val recAlbum = recognitionResult.album?.trim().orEmpty()

        val nonMainModifiers = listOf(
            "speed up", "sped up", "slowed", "reverb", "remix", "live", "acoustic",
            "instrumental", "karaoke", "cover", "8d", "edit", "nightcore", "bass boosted",
            "tribute", "mashup", "parody", "reaction", "guitar", "piano"
        )

        fun score(track: Track): Int {
            var score = 0
            val tTitle = track.title.lowercase()
            val tArtist = track.artist.lowercase()
            val rTitle = recTitle.lowercase()
            val rArtist = recArtist.lowercase()

            // Severe penalty for unwanted modifiers if not part of the recognized title
            for (mod in nonMainModifiers) {
                if (tTitle.contains(mod) && !rTitle.contains(mod)) {
                    score -= 250
                }
            }

            // Title matching
            val cleanCandidateTitle = cleanTitle(track.title).lowercase()
            when {
                cleanCandidateTitle == rTitle -> score += 400
                cleanCandidateTitle.startsWith(rTitle) || rTitle.startsWith(cleanCandidateTitle) -> score += 250
                tTitle.contains(rTitle) -> score += 120
            }

            // Artist matching
            val cleanCandidateArtist = cleanArtist(track.artist).lowercase()
            when {
                cleanCandidateArtist == rArtist -> score += 350
                cleanCandidateArtist.contains(rArtist) || rArtist.contains(cleanCandidateArtist) -> score += 220
                rArtist.split(" ", ",", "&", "feat").any { part -> part.length > 2 && tArtist.contains(part) } -> score += 100
            }

            // Album matching
            if (recAlbum.isNotBlank()) {
                val rAlbum = recAlbum.lowercase()
                val tAlbum = track.album?.lowercase().orEmpty()
                if (tAlbum.contains(rAlbum) || (tAlbum.isNotBlank() && rAlbum.contains(tAlbum))) {
                    score += 100
                }
            }

            // Standard song duration bonus (1:30 to 6:00)
            if (track.duration in 90..420) {
                score += 50
            }

            // Penalty for user-uploaded video indicator titles
            if (tTitle.contains("full video") || tTitle.contains("mv") || tTitle.contains("lyrics video")) {
                score -= 40
            }

            return score
        }

        // Return candidate with highest match score
        return candidates.maxByOrNull { score(it) }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\s*[\\[(](?:Official|Audio|Video|HD|HQ|Visualizer|Lyric Video|Lyrics)[^\\])]*[\\])]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(?:Official|Audio|Video|HD|HQ|Visualizer|Lyric Video|Lyrics).*", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cleanArtist(artist: String): String {
        return artist
            .replace(Regex("\\s*-\\s*Topic", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*VEVO", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun stopListening() {
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

        _state.update {
            if (it.status == RecognitionStatus.LISTENING) {
                it.copy(
                    status = RecognitionStatus.IDLE,
                    audioLevel = 0f,
                    statusMessage = if (it.mode == RecognitionMode.VOICE_SEARCH) "Tap to speak" else "Tap to listen"
                )
            } else it
        }
    }

    fun release() {
        stopListening()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {}
            speechRecognizer = null
        }
    }
}
