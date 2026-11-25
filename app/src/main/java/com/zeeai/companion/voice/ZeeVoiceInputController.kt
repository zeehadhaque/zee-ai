package com.zeeai.companion.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceInputUiState(
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val rmsLevel: Float = 0f,
    val statusHint: String = "Mic Ready",
    val lastError: String? = null
)

class ZeeVoiceInputController(
    private val context: Context,
    private val onPauseBackgroundWakeWord: () -> Unit = {},
    private val onResumeBackgroundWakeWord: () -> Unit = {},
    private val onFinalTranscript: (String) -> Unit
) {
    companion object {
        private const val TAG = "ZeeVoiceInput"
        private const val GOOGLE_RECOGNITION_PKG = "com.google.android.googlequicksearchbox"
        private const val GOOGLE_TTS_ASR_PKG = "com.google.android.as"
        private const val GOOGLE_SPEECH_PKG = "com.google.android.tts"
        private const val WATCHDOG_TIMEOUT_MS = 5000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null

    private val _uiState = MutableStateFlow(VoiceInputUiState())
    val uiState: StateFlow<VoiceInputUiState> = _uiState.asStateFlow()

    @Volatile
    private var continuousMode = false
    @Volatile
    private var isPausedForTts = false
    @Volatile
    private var preferOffline = false
    @Volatile
    private var preferSystemFallbackService = false
    @Volatile
    private var consecutiveErrorCount = 0
    @Volatile
    private var preferredLocale = "bn-BD"

    var onFallbackToSystemDialog: (() -> Unit)? = null

    private val watchdogRunnable = Runnable {
        if (!_uiState.value.isListening) {
            Log.w(TAG, "SpeechRecognizer watchdog fired (600ms): onReadyForSpeech never called. Invoking official system dialog fallback.")
            destroyRecognizerOnMain()
            _uiState.value = _uiState.value.copy(
                isListening = false,
                statusHint = "Google Voice ডায়ালগ ওপেন হচ্ছে..."
            )
            onFallbackToSystemDialog?.invoke()
        }
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setLanguage(localeTag: String) {
        preferredLocale = localeTag
    }

    fun setPreferOffline(offline: Boolean) {
        preferOffline = offline
    }

    fun startListening(continuous: Boolean = false) {
        continuousMode = continuous
        isPausedForTts = false
        consecutiveErrorCount = 0
        preferSystemFallbackService = false
        mainHandler.post {
            if (!hasMicPermission()) {
                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    statusHint = "Microphone permission প্রয়োজন",
                    lastError = "Settings থেকে Microphone permission Allow করো।"
                )
                return@post
            }

            // 24/7 Background WakeWord সার্ভিসের মাইক আগে রিলিজ করো
            onPauseBackgroundWakeWord()
            startInternalOnMain()
        }
    }

    fun stopListening(resumeWakeWord: Boolean = true) {
        continuousMode = false
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizerOnMain()
            _uiState.value = _uiState.value.copy(
                isListening = false,
                rmsLevel = 0f,
                partialTranscript = "",
                statusHint = "Mic Ready"
            )
            if (resumeWakeWord) onResumeBackgroundWakeWord()
        }
    }

    fun pauseForAssistantSpeech() {
        isPausedForTts = true
        mainHandler.post {
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizerOnMain()
            _uiState.value = _uiState.value.copy(
                isListening = false,
                rmsLevel = 0f,
                statusHint = "ZEE কথা বলছে..."
            )
        }
    }

    fun resumeAfterAssistantSpeech() {
        isPausedForTts = false
        if (continuousMode) {
            mainHandler.postDelayed({
                if (continuousMode && !isPausedForTts) {
                    startInternalOnMain()
                }
            }, 350L)
        } else {
            onResumeBackgroundWakeWord()
        }
    }

    private fun startInternalOnMain() {
        if (isPausedForTts) return

        destroyRecognizerOnMain()

        _uiState.value = _uiState.value.copy(
            isListening = true,
            statusHint = "শুনছি... বলুন বস",
            lastError = null
        )

        val recognizer = createBestRecognizer()
        if (recognizer == null) {
            Log.w(TAG, "No SpeechRecognizer could be created. Calling fallback system dialog.")
            _uiState.value = _uiState.value.copy(
                isListening = false,
                statusHint = "System Speech Dialog ওপেন হচ্ছে...",
                lastError = null
            )
            onFallbackToSystemDialog?.invoke()
            return
        }

        speechRecognizer = recognizer

        // Start 600ms watchdog for Xiaomi/HyperOS IPC stalls
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_TIMEOUT_MS)

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                mainHandler.removeCallbacks(watchdogRunnable)
                _uiState.value = _uiState.value.copy(
                    isListening = true,
                    lastError = null,
                    statusHint = "শুনছি... বলুন বস"
                )
            }

            override fun onBeginningOfSpeech() {
                mainHandler.removeCallbacks(watchdogRunnable)
                _uiState.value = _uiState.value.copy(statusHint = "কথা ধরা হচ্ছে...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _uiState.value = _uiState.value.copy(rmsLevel = normalized)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    rmsLevel = 0f,
                    statusHint = "প্রসেস হচ্ছে..."
                )
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val partial = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                if (partial.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(partialTranscript = partial)
                }
            }

            override fun onResults(results: Bundle?) {
                mainHandler.removeCallbacks(watchdogRunnable)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val bestText = matches.firstOrNull { it.isNotBlank() }?.trim().orEmpty()

                _uiState.value = _uiState.value.copy(
                    isListening = false,
                    rmsLevel = 0f,
                    partialTranscript = "",
                    statusHint = if (bestText.isNotEmpty()) "শোনা হয়েছে" else "Mic Ready"
                )

                if (bestText.isNotEmpty()) {
                    onFinalTranscript(bestText)
                } else if (continuousMode && !isPausedForTts) {
                    scheduleRestart(350L)
                }
            }

            override fun onError(error: Int) {
                mainHandler.removeCallbacks(watchdogRunnable)
                val msg = mapErrorMessage(error)
                Log.w(TAG, "SpeechRecognizer error=$error ($msg)")

                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    consecutiveErrorCount = 0
                    if (continuousMode && !isPausedForTts) {
                        scheduleRestart(300L)
                        return
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            rmsLevel = 0f,
                            lastError = null,
                            statusHint = "Mic Ready"
                        )
                        onResumeBackgroundWakeWord()
                    }
                } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    destroyRecognizerOnMain()
                    if (continuousMode && !isPausedForTts) {
                        scheduleRestart(550L)
                        return
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            rmsLevel = 0f,
                            lastError = null,
                            statusHint = "Mic Ready"
                        )
                        onResumeBackgroundWakeWord()
                    }
                } else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    preferredLocale = "en-US"
                    scheduleRestart(280L)
                    return
                } else if (consecutiveErrorCount == 0 && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    consecutiveErrorCount++
                    preferSystemFallbackService = !preferSystemFallbackService
                    _uiState.value = _uiState.value.copy(lastError = null, statusHint = "মাইক রিকানেক্ট হচ্ছে...")
                    scheduleRestart(350L)
                    return
                } else {
                    consecutiveErrorCount = 0
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && onFallbackToSystemDialog != null) {
                        _uiState.value = _uiState.value.copy(
                            isListening = false,
                            rmsLevel = 0f,
                            statusHint = "Google Voice ডায়ালগ ওপেন হচ্ছে...",
                            lastError = null
                        )
                        onFallbackToSystemDialog?.invoke()
                        return
                    }
                    _uiState.value = _uiState.value.copy(
                        isListening = false,
                        rmsLevel = 0f,
                        statusHint = msg,
                        lastError = msg
                    )
                    if (continuousMode && !isPausedForTts && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        scheduleRestart(900L)
                    } else if (!continuousMode) {
                        onResumeBackgroundWakeWord()
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val targetLocale = preferredLocale.ifBlank { "bn-BD" }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        runCatching {
            recognizer.startListening(intent)
        }.onFailure { err ->
            Log.e(TAG, "startListening crashed: ${err.message}", err)
            mainHandler.removeCallbacks(watchdogRunnable)
            onFallbackToSystemDialog?.invoke()
        }
    }

    private fun createBestRecognizer(): SpeechRecognizer? {
        if (!preferSystemFallbackService) {
            val preferredPackages = listOf(GOOGLE_RECOGNITION_PKG, GOOGLE_TTS_ASR_PKG, GOOGLE_SPEECH_PKG)
            val serviceIntent = Intent("android.speech.RecognitionService")
            val services = runCatching {
                context.packageManager.queryIntentServices(serviceIntent, 0)
            }.getOrDefault(emptyList())

            val googleService = preferredPackages.firstNotNullOfOrNull { pkg ->
                services.firstOrNull { it.serviceInfo?.packageName == pkg }
            }

            if (googleService != null) {
                val comp = ComponentName(googleService.serviceInfo.packageName, googleService.serviceInfo.name)
                val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context, comp) }.getOrNull()
                    ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext, comp) }.getOrNull()
                if (rec != null) return rec
            }
        }

        val defaultRec = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        if (defaultRec != null) return defaultRec

        return null
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (continuousMode && !isPausedForTts) {
                startInternalOnMain()
            }
        }, delayMs)
    }

    private fun destroyRecognizerOnMain() {
        mainHandler.removeCallbacks(watchdogRunnable)
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun mapErrorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "অডিও রেকর্ড সমস্যা — আবার চেষ্টা করো"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission বন্ধ আছে"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "নেট কানেকশন চেক করো"
        SpeechRecognizer.ERROR_NO_MATCH -> "কথা বোঝা যায়নি, আরেকবার বলো"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "মাইক রিসেট হচ্ছে..."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "কোনো কথা শোনা যায়নি"
        else -> "ভয়েস এরর ($code)"
    }

    fun release() {
        stopListening(resumeWakeWord = false)
    }
}
