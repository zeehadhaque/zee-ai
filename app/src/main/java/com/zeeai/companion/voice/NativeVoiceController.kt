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
import com.zeeai.companion.ai.GeminiNativeClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    private val geminiClient: GeminiNativeClient = GeminiNativeClient(),
    private val onSpeakingStateChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "ZeeNativeVoice"
        private const val WATCHDOG_TIMEOUT_MS = 2800L
        private val PREFERRED_RECOGNITION_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
            "com.google.android.tts"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val directMicEngine = JarvisAudioMicEngine(geminiClient)

    private val _isMicLockedOn = MutableStateFlow(false)
    val isMicLockedOn: StateFlow<Boolean> = _isMicLockedOn.asStateFlow()

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _micRmsLevel = MutableStateFlow(0f)
    val micRmsLevel: StateFlow<Float> = _micRmsLevel.asStateFlow()

    private val _statusHint = MutableStateFlow("Tap Mic to start JARVIS voice control")
    val statusHint: StateFlow<String> = _statusHint.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _usingDirectJarvisMic = MutableStateFlow(false)
    val usingDirectJarvisMic: StateFlow<Boolean> = _usingDirectJarvisMic.asStateFlow()

    @Volatile
    private var isTtsSpeaking = false
    @Volatile
    private var isProcessingCommand = false
    @Volatile
    private var preferSystemFallbackService = false
    @Volatile
    private var forceEnglishLocaleFallback = false
    @Volatile
    var preferPureJarvisAudioMic: Boolean = true
    private var consecutiveFailureCount = 0

    private var activeLanguageTag: String = "bn-BD"
    private var activeOnPartial: ((String) -> Unit)? = null
    private var activeOnFinal: ((String) -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    var onBeforeManualMicStart: (() -> Unit)? = null

    fun clearError() {
        _lastError.value = null
    }

    private val restartListeningRunnable = Runnable {
        if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand && !directMicEngine.isRunning()) {
            startListeningInternal()
        }
    }

    private val readyWatchdogRunnable = Runnable {
        if (!_isMicLockedOn.value || isTtsSpeaking || isProcessingCommand || directMicEngine.isRunning()) return@Runnable
        Log.w(TAG, "In-process SpeechRecognizer watchdog fired — seamlessly switching to Pure JARVIS Direct Audio Mic")
        destroyRecognizerInternal()
        if (geminiApiKey.isNotBlank()) {
            startDirectJarvisAudioLoop()
        } else if (!preferSystemFallbackService) {
            preferSystemFallbackService = true
            _lastError.value = null
            _statusHint.value = "Switching in-app speech engine..."
            startListeningInternal()
        }
    }

    private var geminiApiKey: String = ""
    private var elevenLabsApiKey: String = ""
    private var isOnline: Boolean = true

    fun setApiKeys(gemini: String, eleven: String, online: Boolean) {
        this.geminiApiKey = gemini
        this.elevenLabsApiKey = eleven
        this.isOnline = online
    }

    fun updateOnlineVoiceConfig(config: OnlineVoiceConfig) {
        humanVoiceEngine.updateVoiceConfig(config)
    }

    val humanVoiceEngine = HumanVoiceEngine(
        context = context,
        onSpeechStart = {
            isTtsSpeaking = true
            isProcessingCommand = false
            directMicEngine.setPaused(true)
            _voiceState.value = VoiceState.SPEAKING
            _micRmsLevel.value = 0f
            _statusHint.value = "জী উত্তর বলছে..."
            onSpeakingStateChanged(true)
            mainHandler.post {
                mainHandler.removeCallbacks(restartListeningRunnable)
                mainHandler.removeCallbacks(readyWatchdogRunnable)
                destroyRecognizerInternal()
            }
        },
        onSpeechDone = {
            isTtsSpeaking = false
            onSpeakingStateChanged(false)
            if (_isMicLockedOn.value && !isProcessingCommand) {
                _voiceState.value = VoiceState.LISTENING
                _statusHint.value = if (_usingDirectJarvisMic.value) "JARVIS Direct Mic শুনছে... বলুন বস" else "শুনছি... কথা বলুন বস"
                if (directMicEngine.isRunning()) {
                    directMicEngine.setPaused(false)
                } else {
                    mainHandler.removeCallbacks(restartListeningRunnable)
                    mainHandler.postDelayed(restartListeningRunnable, 300L)
                }
            } else {
                _voiceState.value = VoiceState.IDLE
                _statusHint.value = "Tap Mic to start JARVIS voice control"
            }
        },
        onVoiceError = { msg ->
            _lastError.value = msg
            _statusHint.value = msg
        }
    )

    fun getVoiceStatusInfo(online: Boolean = isOnline): OnlineVoiceStatusInfo {
        return humanVoiceEngine.getVoiceStatusInfo(online, geminiApiKey, elevenLabsApiKey)
    }

    fun speak(
        text: String,
        profile: NativeVoiceProfile,
        online: Boolean = isOnline,
        geminiKey: String = geminiApiKey,
        elevenKey: String = elevenLabsApiKey
    ) {
        if (text.isBlank()) return
        isTtsSpeaking = true
        isProcessingCommand = false
        directMicEngine.setPaused(true)
        _voiceState.value = VoiceState.SPEAKING
        _micRmsLevel.value = 0f
        _statusHint.value = "জী উত্তর বলছে..."
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        mainHandler.post { destroyRecognizerInternal() }

        val persona = if (profile.isFemale) VoicePersona.ZHEE_FEMALE else VoicePersona.ZEE_MALE
        humanVoiceEngine.speakHuman(
            rawText = text,
            persona = persona,
            isOnline = online,
            geminiApiKey = geminiKey,
            elevenLabsApiKey = elevenKey
        )
    }

    fun stopSpeaking() {
        humanVoiceEngine.stopSpeaking()
        isTtsSpeaking = false
        onSpeakingStateChanged(false)
        if (_isMicLockedOn.value) {
            directMicEngine.setPaused(false)
            _voiceState.value = VoiceState.LISTENING
        } else {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun pauseListeningForProcessing() {
        isProcessingCommand = true
        directMicEngine.setPaused(true)
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        mainHandler.post { destroyRecognizerInternal() }
    }

    fun resumeListeningAfterProcessing() {
        isProcessingCommand = false
        if (_isMicLockedOn.value && !isTtsSpeaking) {
            if (directMicEngine.isRunning()) {
                directMicEngine.setPaused(false)
            } else {
                mainHandler.removeCallbacks(restartListeningRunnable)
                mainHandler.postDelayed(restartListeningRunnable, 300L)
            }
        }
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun startContinuousListening(
        languageTag: String,
        preferOffline: Boolean = false,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!hasMicPermission()) {
            _isMicLockedOn.value = false
            _voiceState.value = VoiceState.ERROR
            _lastError.value = "Microphone permission প্রয়োজন"
            _statusHint.value = "Microphone permission নেই"
            onError("Microphone permission required.")
            return
        }

        stopSpeaking()
        onBeforeManualMicStart?.invoke()

        isProcessingCommand = false
        consecutiveFailureCount = 0
        preferSystemFallbackService = false
        forceEnglishLocaleFallback = false

        _isMicLockedOn.value = true
        _voiceState.value = VoiceState.LISTENING
        _lastError.value = null
        _statusHint.value = "JARVIS মাইক চালু হচ্ছে..."

        activeLanguageTag = languageTag
        activeOnPartial = onPartial
        activeOnFinal = onFinal
        activeOnError = onError

        WakeWordForegroundService.pause(context)

        // When online + Gemini API key present + Pure JARVIS mode active: use direct AudioRecord VAD (Zero Google Popup, Zero Chime)
        if (preferPureJarvisAudioMic && isOnline && geminiApiKey.isNotBlank()) {
            startDirectJarvisAudioLoop()
            return
        }

        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed({
            if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                startListeningInternal()
            }
        }, 200L)
    }

    private fun startDirectJarvisAudioLoop() {
        destroyRecognizerInternal()
        _usingDirectJarvisMic.value = true
        _lastError.value = null
        _voiceState.value = VoiceState.LISTENING

        directMicEngine.startContinuousCapture(
            apiKeyProvider = { geminiApiKey },
            onRmsLevel = { lvl -> _micRmsLevel.value = lvl },
            onStatus = { hint ->
                if (_isMicLockedOn.value && !isTtsSpeaking) {
                    _statusHint.value = hint
                }
            },
            onHearingStart = {
                if (_isMicLockedOn.value && !isTtsSpeaking) {
                    _voiceState.value = VoiceState.HEARING
                }
            },
            onTranscript = { text ->
                if (_isMicLockedOn.value && text.isNotBlank()) {
                    _lastError.value = null
                    _statusHint.value = "শোনা হয়েছে: $text"
                    activeOnFinal?.invoke(text)
                }
            },
            onError = { err ->
                Log.w(TAG, "Direct Audio Mic fallback notice: $err")
                _usingDirectJarvisMic.value = false
                if (_isMicLockedOn.value && SpeechRecognizer.isRecognitionAvailable(context)) {
                    startListeningInternal()
                } else {
                    _lastError.value = err
                    _voiceState.value = VoiceState.ERROR
                    activeOnError?.invoke(err)
                }
            }
        )
    }

    private fun hasExplicitGoogleService(): Boolean {
        val services = runCatching {
            context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
        }.getOrDefault(emptyList())
        return PREFERRED_RECOGNITION_PACKAGES.any { pkg ->
            services.any { it.serviceInfo?.packageName == pkg }
        }
    }

    private fun createBestRecognizer(): SpeechRecognizer? {
        if (!preferSystemFallbackService) {
            val services = runCatching {
                context.packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
            }.getOrDefault(emptyList())
            val googleService = PREFERRED_RECOGNITION_PACKAGES.firstNotNullOfOrNull { pkg ->
                services.firstOrNull { it.serviceInfo?.packageName == pkg }
            }
            if (googleService != null) {
                val comp = ComponentName(googleService.serviceInfo.packageName, googleService.serviceInfo.name)
                val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(context, comp) }.getOrNull()
                    ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext, comp) }.getOrNull()
                if (rec != null) return rec
            }
        }

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
                ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
        }
        return null
    }

    private fun startListeningInternal() {
        if (!_isMicLockedOn.value || isTtsSpeaking || isProcessingCommand) return
        _usingDirectJarvisMic.value = false

        mainHandler.post {
            if (!_isMicLockedOn.value || isTtsSpeaking || isProcessingCommand) return@post
            destroyRecognizerInternal()

            val sr = createBestRecognizer()
            if (sr == null) {
                if (geminiApiKey.isNotBlank()) {
                    startDirectJarvisAudioLoop()
                } else {
                    _isMicLockedOn.value = false
                    _voiceState.value = VoiceState.ERROR
                    val msg = "Settings থেকে Google AI Studio API Key দিন — JARVIS অডিও মাইক চালু হবে।"
                    _lastError.value = msg
                    _statusHint.value = msg
                    activeOnError?.invoke(msg)
                }
                return@post
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    mainHandler.removeCallbacks(readyWatchdogRunnable)
                    consecutiveFailureCount = 0
                    if (_isMicLockedOn.value && !isTtsSpeaking) {
                        _voiceState.value = VoiceState.LISTENING
                        _lastError.value = null
                        _statusHint.value = if (forceEnglishLocaleFallback) "শুনছি (English)... বলুন বস" else "শুনছি... কথা বলুন বস"
                    }
                }

                override fun onBeginningOfSpeech() {
                    mainHandler.removeCallbacks(readyWatchdogRunnable)
                    if (_isMicLockedOn.value) {
                        _voiceState.value = VoiceState.HEARING
                        _statusHint.value = "কথা ধরা হচ্ছে..."
                    }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _micRmsLevel.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    _micRmsLevel.value = 0f
                    _statusHint.value = "প্রসেস হচ্ছে..."
                }

                override fun onError(error: Int) {
                    mainHandler.removeCallbacks(readyWatchdogRunnable)
                    _micRmsLevel.value = 0f

                    if (!_isMicLockedOn.value) {
                        _voiceState.value = VoiceState.IDLE
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        _isMicLockedOn.value = false
                        _voiceState.value = VoiceState.ERROR
                        val msg = "Microphone permission প্রয়োজন।"
                        _lastError.value = msg
                        _statusHint.value = msg
                        activeOnError?.invoke(msg)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        consecutiveFailureCount = 0
                        mainHandler.post {
                            if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                                _voiceState.value = VoiceState.LISTENING
                                mainHandler.removeCallbacks(restartListeningRunnable)
                                mainHandler.postDelayed(restartListeningRunnable, 280L)
                            }
                        }
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
                    ) {
                        forceEnglishLocaleFallback = true
                        _lastError.value = null
                        mainHandler.post {
                            destroyRecognizerInternal()
                            if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                                mainHandler.removeCallbacks(restartListeningRunnable)
                                mainHandler.postDelayed(restartListeningRunnable, 260L)
                            }
                        }
                        return
                    }

                    consecutiveFailureCount++
                    if (consecutiveFailureCount >= 2 && geminiApiKey.isNotBlank()) {
                        // Switch silently to Pure JARVIS Direct Audio Mic instead of EVER opening Google Popup Activity!
                        mainHandler.post {
                            destroyRecognizerInternal()
                            startDirectJarvisAudioLoop()
                        }
                        return
                    }

                    preferSystemFallbackService = !preferSystemFallbackService
                    _lastError.value = null
                    _statusHint.value = "মাইক রিকানেক্ট হচ্ছে..."
                    mainHandler.post {
                        destroyRecognizerInternal()
                        if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                            _voiceState.value = VoiceState.LISTENING
                            mainHandler.removeCallbacks(restartListeningRunnable)
                            mainHandler.postDelayed(restartListeningRunnable, 420L)
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    mainHandler.removeCallbacks(readyWatchdogRunnable)
                    _micRmsLevel.value = 0f
                    consecutiveFailureCount = 0

                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                        .orEmpty()
                        .trim()

                    if (text.isNotBlank()) {
                        _lastError.value = null
                        _statusHint.value = "শোনা হয়েছে: $text"
                        activeOnFinal?.invoke(text)
                    }

                    if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                        mainHandler.removeCallbacks(restartListeningRunnable)
                        mainHandler.postDelayed(restartListeningRunnable, 350L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull { it.isNotBlank() }
                        .orEmpty()
                    if (text.isNotBlank()) {
                        _voiceState.value = VoiceState.HEARING
                        activeOnPartial?.invoke(text)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val targetLocale = if (forceEnglishLocaleFallback) "en-US" else activeLanguageTag
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLocale)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }

            recognizer = sr
            mainHandler.removeCallbacks(readyWatchdogRunnable)
            mainHandler.postDelayed(readyWatchdogRunnable, WATCHDOG_TIMEOUT_MS)

            runCatching {
                sr.startListening(intent)
            }.onFailure { err ->
                mainHandler.removeCallbacks(readyWatchdogRunnable)
                Log.w(TAG, "sr.startListening threw: ${err.message}")
                if (geminiApiKey.isNotBlank()) {
                    startDirectJarvisAudioLoop()
                } else {
                    _lastError.value = "মাইক চালু সমস্যা: ${err.localizedMessage ?: "Retry"}"
                }
            }
        }
    }

    private fun destroyRecognizerInternal() {
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        recognizer?.let { sr ->
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
        recognizer = null
    }

    fun stopListening(resumeWakeWord: Boolean = true) {
        _isMicLockedOn.value = false
        _usingDirectJarvisMic.value = false
        isProcessingCommand = false
        directMicEngine.stopCapture()
        _micRmsLevel.value = 0f
        if (!isTtsSpeaking) {
            _voiceState.value = VoiceState.IDLE
            _statusHint.value = "Tap Mic to start JARVIS voice control"
        }
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        mainHandler.post {
            destroyRecognizerInternal()
            if (resumeWakeWord) WakeWordForegroundService.resume(context)
        }
    }

    fun suspendRecognizerForBackgroundHandoff() {
        _isMicLockedOn.value = false
        _usingDirectJarvisMic.value = false
        isProcessingCommand = false
        directMicEngine.stopCapture()
        _micRmsLevel.value = 0f
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        mainHandler.post {
            destroyRecognizerInternal()
        }
    }

    fun resumeAfterForegroundReturn() {
        if (activeOnFinal != null && activeOnPartial != null && hasMicPermission()) {
            _isMicLockedOn.value = true
            _voiceState.value = VoiceState.LISTENING
            _lastError.value = null
            _statusHint.value = "শুনছি... কথা বলুন বস"
            WakeWordForegroundService.pause(context)
            if (preferPureJarvisAudioMic && isOnline && geminiApiKey.isNotBlank()) {
                startDirectJarvisAudioLoop()
                return
            }
            mainHandler.removeCallbacks(restartListeningRunnable)
            mainHandler.postDelayed({
                if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                    startListeningInternal()
                }
            }, 300L)
        }
    }

    fun testVoiceSample(
        isFemale: Boolean,
        online: Boolean = isOnline,
        geminiKey: String = geminiApiKey,
        elevenKey: String = elevenLabsApiKey
    ) {
        val testPhrase = if (isFemale) {
            "হ্যালো বস! আমি ঝী। এখন আমাদের নিজস্ব জার্ভিস হিউম্যান ভয়েস সক্রিয় আছে। বলুন কী সাহায্য করতে পারি?"
        } else {
            "ইয়েস বস! আমি জী। এখন আমাদের নিজস্ব জার্ভিস স্টুডিও হিউম্যান ভয়েস সক্রিয় আছে। হুকুম করুন বস, কী করতে হবে?"
        }
        speak(
            text = testPhrase,
            profile = NativeVoiceProfile(languageTag = "bn-BD", pitch = 1.0f, rate = 1.0f, isFemale = isFemale),
            online = online,
            geminiKey = geminiKey,
            elevenKey = elevenKey
        )
    }

    fun emergencyStop() {
        stopSpeaking()
        stopListening()
    }

    fun shutdown() {
        emergencyStop()
        mainHandler.removeCallbacksAndMessages(null)
        humanVoiceEngine.release()
    }
}
