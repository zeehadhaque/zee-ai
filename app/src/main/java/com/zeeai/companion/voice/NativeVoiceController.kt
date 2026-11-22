package com.zeeai.companion.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class NativeVoiceProfile(
    val languageTag: String, // "bn-BD", "en-US", "hi-IN", "ur-PK"
    val pitch: Float,
    val rate: Float,
    val isFemale: Boolean = false
)

class NativeVoiceController(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private val _isMicLockedOn = MutableStateFlow(false)
    val isMicLockedOn: StateFlow<Boolean> = _isMicLockedOn.asStateFlow()

    private var isTtsSpeaking = false
    private var activeLanguageTag: String = "bn-BD"
    private var activePreferOffline: Boolean = false
    private var activeOnPartial: ((String) -> Unit)? = null
    private var activeOnFinal: ((String) -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    private val restartListeningRunnable = Runnable {
        if (_isMicLockedOn.value && !isTtsSpeaking) {
            startListeningInternal()
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    isTtsSpeaking = true
                    onSpeakingStateChanged(true)
                    // Pause active speech recognition so AI doesn't hear its own voice
                    mainHandler.post {
                        recognizer?.cancel()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    onSpeakingStateChanged(false)
                    // Resume listening automatically if Mic is locked on
                    if (_isMicLockedOn.value) {
                        mainHandler.postDelayed(restartListeningRunnable, 250L)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    onSpeakingStateChanged(false)
                    if (_isMicLockedOn.value) {
                        mainHandler.postDelayed(restartListeningRunnable, 250L)
                    }
                }
            })
        }
    }

    fun speak(text: String, profile: NativeVoiceProfile) {
        if (!ttsReady || text.isBlank()) return
        stopSpeaking() // Instant interruption
        val clean = text.replace(Regex("\\[.*?\\]"), "").trim()
        val loc = Locale.forLanguageTag(profile.languageTag)
        tts?.language = loc
        tts?.setPitch(profile.pitch)
        tts?.setSpeechRate(profile.rate)

        try {
            tts?.voices?.let { voices ->
                val langVoices = voices.filter { it.locale.language == loc.language }
                val targetVoice = if (profile.isFemale) {
                    langVoices.firstOrNull { it.name.contains("female", ignoreCase = true) || it.name.contains("-f-", ignoreCase = true) }
                        ?: voices.firstOrNull { it.name.contains("female", ignoreCase = true) }
                } else {
                    langVoices.firstOrNull { it.name.contains("male", ignoreCase = true) || it.name.contains("-m-", ignoreCase = true) }
                        ?: voices.firstOrNull { it.name.contains("male", ignoreCase = true) }
                }
                if (targetVoice != null) {
                    tts?.voice = targetVoice
                }
            }
        } catch (_: Exception) {}

        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "zee_utt_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        isTtsSpeaking = false
        onSpeakingStateChanged(false)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Starts continuous always-on mic listening. Tapping Mic once locks it ON.
     */
    fun startContinuousListening(
        languageTag: String,
        preferOffline: Boolean = false,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopSpeaking() // Barge-in immediately
        _isMicLockedOn.value = true

        activeLanguageTag = languageTag
        activePreferOffline = preferOffline
        activeOnPartial = onPartial
        activeOnFinal = onFinal
        activeOnError = onError

        // Eliminate wake-word vs manual mic clash: pause wake word service first
        WakeWordForegroundService.pause(context)

        // Wait 250ms on main looper so background service recognizer finishes releasing the audio hardware
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed({
            if (_isMicLockedOn.value) {
                startListeningInternal()
            }
        }, 250L)
    }

    private fun startListeningInternal() {
        if (!_isMicLockedOn.value || isTtsSpeaking) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _isMicLockedOn.value = false
            WakeWordForegroundService.resume(context)
            activeOnError?.invoke("Android Speech Recognition service is not available on this device.")
            return
        }

        destroyRecognizerInternal()

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                if (!_isMicLockedOn.value) {
                    WakeWordForegroundService.resume(context)
                    activeOnError?.invoke("Voice recognition error code: $error")
                    return
                }

                // If mic is locked ON, never cancel the lock on silence or busy
                val retryDelay = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 400L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 800L
                    SpeechRecognizer.ERROR_CLIENT -> 450L
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        _isMicLockedOn.value = false
                        WakeWordForegroundService.resume(context)
                        activeOnError?.invoke("Microphone permission required.")
                        return
                    }
                    else -> 1000L
                }

                mainHandler.post {
                    destroyRecognizerInternal()
                    mainHandler.removeCallbacks(restartListeningRunnable)
                    mainHandler.postDelayed(restartListeningRunnable, retryDelay)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()

                if (text.isNotBlank()) {
                    activeOnFinal?.invoke(text)
                } else if (_isMicLockedOn.value) {
                    // Empty speech, keep listening open
                    mainHandler.removeCallbacks(restartListeningRunnable)
                    mainHandler.postDelayed(restartListeningRunnable, 400L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    activeOnPartial?.invoke(text)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val shouldPreferOffline = activePreferOffline || !isNetworkAvailable()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, shouldPreferOffline)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer = sr
        try {
            sr.startListening(intent)
        } catch (_: Exception) {
            mainHandler.postDelayed(restartListeningRunnable, 800L)
        }
    }

    private fun destroyRecognizerInternal() {
        recognizer?.let { sr ->
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
        recognizer = null
    }

    fun stopListening() {
        _isMicLockedOn.value = false
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.post {
            destroyRecognizerInternal()
            WakeWordForegroundService.resume(context)
        }
    }

    fun emergencyStop() {
        stopSpeaking()
        stopListening()
    }

    fun shutdown() {
        emergencyStop()
        mainHandler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
    }
}
