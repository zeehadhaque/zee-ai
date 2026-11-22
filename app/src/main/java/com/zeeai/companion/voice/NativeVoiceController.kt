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

enum class VoiceState {
    IDLE,
    LISTENING,
    HEARING,
    SPEAKING,
    ERROR
}

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

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

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
                    _voiceState.value = VoiceState.SPEAKING
                    onSpeakingStateChanged(true)
                    // Pause active speech recognition so AI does not hear its own voice
                    mainHandler.post {
                        recognizer?.cancel()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    isTtsSpeaking = false
                    onSpeakingStateChanged(false)
                    // Resume listening automatically if Mic is locked on
                    if (_isMicLockedOn.value) {
                        _voiceState.value = VoiceState.LISTENING
                        mainHandler.removeCallbacks(restartListeningRunnable)
                        mainHandler.postDelayed(restartListeningRunnable, 250L)
                    } else {
                        _voiceState.value = VoiceState.IDLE
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    isTtsSpeaking = false
                    onSpeakingStateChanged(false)
                    if (_isMicLockedOn.value) {
                        _voiceState.value = VoiceState.LISTENING
                        mainHandler.removeCallbacks(restartListeningRunnable)
                        mainHandler.postDelayed(restartListeningRunnable, 250L)
                    } else {
                        _voiceState.value = VoiceState.IDLE
                    }
                }
            })
        }
    }

    fun speak(text: String, profile: NativeVoiceProfile) {
        if (text.isBlank()) return
        if (!ttsReady) {
            mainHandler.postDelayed({
                if (ttsReady) speak(text, profile)
            }, 350L)
            return
        }

        stopSpeaking() // Instant interruption
        val clean = text.replace(Regex("\\[.*?\\]"), "").trim()
        if (clean.isEmpty()) return

        // Script detection for offline synthesis
        val hasBengali = clean.any { it in '\u0980'..'\u09FF' }
        val hasHindi = clean.any { it in '\u0900'..'\u097F' }
        val targetLocale = when {
            hasBengali -> Locale.forLanguageTag("bn-BD")
            hasHindi -> Locale.forLanguageTag("hi-IN")
            else -> Locale.forLanguageTag(profile.languageTag)
        }

        var langStatus = tts?.setLanguage(targetLocale)
        if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            if (hasBengali) {
                langStatus = tts?.setLanguage(Locale.forLanguageTag("bn-IN"))
                if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langStatus = tts?.setLanguage(Locale.forLanguageTag("bn"))
                }
            }
            if (langStatus == TextToSpeech.LANG_MISSING_DATA || langStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
        }

        // Distinct acoustic vocal personas
        tts?.setPitch(profile.pitch)
        tts?.setSpeechRate(profile.rate)

        try {
            tts?.voices?.let { allVoices ->
                val activeLang = tts?.voice?.locale?.language ?: targetLocale.language
                val langVoices = allVoices.filter { it.locale.language.equals(activeLang, ignoreCase = true) }
                val pool = if (langVoices.isNotEmpty()) langVoices else allVoices.toList()

                // Prioritize offline / embedded voices
                val offlineVoices = pool.filter { !it.isNetworkConnectionRequired }
                val targetPool = if (offlineVoices.isNotEmpty()) offlineVoices else pool

                val targetVoice = if (profile.isFemale) {
                    targetPool.firstOrNull { v ->
                        v.name.contains("female", ignoreCase = true) ||
                        v.name.contains("-f-", ignoreCase = true) ||
                        v.name.contains("fem", ignoreCase = true)
                    } ?: targetPool.firstOrNull()
                } else {
                    targetPool.firstOrNull { v ->
                        v.name.contains("male", ignoreCase = true) ||
                        v.name.contains("-m-", ignoreCase = true) ||
                        v.name.contains("masc", ignoreCase = true)
                    } ?: targetPool.firstOrNull()
                }

                if (targetVoice != null) {
                    tts?.voice = targetVoice
                }
            }
        } catch (_: Exception) {}

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "zee_utt_${System.currentTimeMillis()}")
        }

        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, "zee_utt_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        isTtsSpeaking = false
        onSpeakingStateChanged(false)
        if (_isMicLockedOn.value) {
            _voiceState.value = VoiceState.LISTENING
        } else {
            _voiceState.value = VoiceState.IDLE
        }
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
        _voiceState.value = VoiceState.LISTENING

        activeLanguageTag = languageTag
        activePreferOffline = preferOffline
        activeOnPartial = onPartial
        activeOnFinal = onFinal
        activeOnError = onError

        // Eliminate wake-word vs manual mic clash: pause wake word service first
        WakeWordForegroundService.pause(context)

        // Wait 200ms on main looper so audio hardware finishes releasing
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed({
            if (_isMicLockedOn.value) {
                startListeningInternal()
            }
        }, 200L)
    }

    private fun startListeningInternal() {
        if (!_isMicLockedOn.value || isTtsSpeaking) return

        mainHandler.post {
            destroyRecognizerInternal()

            val sr = runCatching {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            }.getOrElse {
                runCatching {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }.getOrNull()
            }

            if (sr == null) {
                _isMicLockedOn.value = false
                _voiceState.value = VoiceState.ERROR
                WakeWordForegroundService.resume(context)
                activeOnError?.invoke("Android Speech Recognition service is not available.")
                return@post
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (_isMicLockedOn.value && !isTtsSpeaking) {
                        _voiceState.value = VoiceState.LISTENING
                    }
                }

                override fun onBeginningOfSpeech() {
                    if (_isMicLockedOn.value) {
                        _voiceState.value = VoiceState.HEARING
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (!_isMicLockedOn.value) {
                        _voiceState.value = VoiceState.IDLE
                        WakeWordForegroundService.resume(context)
                        return
                    }

                    // Keep mic locked ON on timeout/no-match/busy
                    val retryDelay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 350L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 650L
                        SpeechRecognizer.ERROR_CLIENT -> 400L
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            _isMicLockedOn.value = false
                            _voiceState.value = VoiceState.ERROR
                            WakeWordForegroundService.resume(context)
                            activeOnError?.invoke("Microphone permission required.")
                            return
                        }
                        else -> 800L
                    }

                    mainHandler.post {
                        destroyRecognizerInternal()
                        if (_isMicLockedOn.value && !isTtsSpeaking) {
                            _voiceState.value = VoiceState.LISTENING
                            mainHandler.removeCallbacks(restartListeningRunnable)
                            mainHandler.postDelayed(restartListeningRunnable, retryDelay)
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                        .trim()

                    if (text.isNotBlank()) {
                        activeOnFinal?.invoke(text)
                    }

                    // If Mic is locked ON, restart listening
                    if (_isMicLockedOn.value) {
                        mainHandler.removeCallbacks(restartListeningRunnable)
                        // If TTS has NOT started speaking (e.g. action or offline or delay), restart listening
                        mainHandler.postDelayed({
                            if (_isMicLockedOn.value && !isTtsSpeaking) {
                                startListeningInternal()
                            }
                        }, 400L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) {
                        _voiceState.value = VoiceState.HEARING
                        activeOnPartial?.invoke(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer = sr
            try {
                sr.startListening(intent)
            } catch (_: Exception) {
                mainHandler.postDelayed(restartListeningRunnable, 600L)
            }
        }
    }

    fun requestRestartListening(delayMs: Long = 200L) {
        if (_isMicLockedOn.value && !isTtsSpeaking) {
            mainHandler.removeCallbacks(restartListeningRunnable)
            mainHandler.postDelayed(restartListeningRunnable, delayMs)
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
        _voiceState.value = VoiceState.IDLE
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
