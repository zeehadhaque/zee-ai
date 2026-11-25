package com.zeeai.companion.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class TtsVoicePackInfo(
    val totalVoices: Int,
    val offlineVoices: Int,
    val hasBengali: Boolean,
    val hasEnglish: Boolean,
    val defaultEngine: String
)

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
    companion object {
        private const val TAG = "ZeeNativeVoice"
        private const val WATCHDOG_TIMEOUT_MS = 4500L
        private val PREFERRED_RECOGNITION_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.as",
            "com.google.android.tts"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _isMicLockedOn = MutableStateFlow(false)
    val isMicLockedOn: StateFlow<Boolean> = _isMicLockedOn.asStateFlow()

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _micRmsLevel = MutableStateFlow(0f)
    val micRmsLevel: StateFlow<Float> = _micRmsLevel.asStateFlow()

    private val _statusHint = MutableStateFlow("Tap Mic to start voice phone control")
    val statusHint: StateFlow<String> = _statusHint.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var isTtsSpeaking = false
    @Volatile
    private var isProcessingCommand = false
    @Volatile
    private var useExplicitGoogleService = false
    @Volatile
    private var forceEnglishLocaleFallback = false
    private var consecutiveFailureCount = 0
    private var watchdogStallCount = 0

    private var activeLanguageTag: String = "bn-BD"
    private var activePreferOffline: Boolean = false
    private var activeOnPartial: ((String) -> Unit)? = null
    private var activeOnFinal: ((String) -> Unit)? = null
    private var activeOnError: ((String) -> Unit)? = null

    var onBeforeManualMicStart: (() -> Unit)? = null
    var onFallbackToSystemDialog: (() -> Unit)? = null

    private val restartListeningRunnable = Runnable {
        if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
            startListeningInternal()
        }
    }

    private val readyWatchdogRunnable = Runnable {
        if (!_isMicLockedOn.value || isTtsSpeaking || isProcessingCommand) return@Runnable
        watchdogStallCount++
        Log.w(TAG, "SpeechRecognizer watchdog fired (#$watchdogStallCount): onReadyForSpeech stalled")

        destroyRecognizerInternal()

        if (!useExplicitGoogleService && hasExplicitGoogleService()) {
            useExplicitGoogleService = true
            _statusHint.value = "Switching to Google Speech Engine..."
            startListeningInternal()
        } else if (watchdogStallCount >= 2 && onFallbackToSystemDialog != null) {
            _statusHint.value = "Opening Google Voice Popup..."
            _isMicLockedOn.value = false
            _voiceState.value = VoiceState.IDLE
            _micRmsLevel.value = 0f
            onFallbackToSystemDialog?.invoke()
        } else {
            _lastError.value = "Speech service response slow — retrying mic..."
            _statusHint.value = "Retrying mic connection..."
            mainHandler.postDelayed(restartListeningRunnable, 500L)
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

    val humanVoiceEngine = HumanVoiceEngine(
        context = context,
        onSpeechStart = {
            isTtsSpeaking = true
            isProcessingCommand = false
            _voiceState.value = VoiceState.SPEAKING
            _micRmsLevel.value = 0f
            _statusHint.value = "ZEE কথা বলছে..."
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
                _statusHint.value = "শুনছি... কথা বলুন বস"
                mainHandler.removeCallbacks(restartListeningRunnable)
                mainHandler.postDelayed(restartListeningRunnable, 300L)
            } else {
                _voiceState.value = VoiceState.IDLE
                _statusHint.value = "Tap Mic to start voice phone control"
            }
        }
    )

    fun speak(
        text: String,
        profile: NativeVoiceProfile,
        online: Boolean = isOnline,
        geminiKey: String = geminiApiKey,
        elevenKey: String = elevenLabsApiKey
    ) {
        if (text.isBlank()) return
        // Immediately lock mic restart BEFORE async TTS synthesis starts so 400ms timer can't steal mic!
        isTtsSpeaking = true
        isProcessingCommand = false
        _voiceState.value = VoiceState.SPEAKING
        _micRmsLevel.value = 0f
        _statusHint.value = "ZEE উত্তর বলছে..."
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
            _voiceState.value = VoiceState.LISTENING
        } else {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun pauseListeningForProcessing() {
        isProcessingCommand = true
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        mainHandler.post { destroyRecognizerInternal() }
    }

    fun resumeListeningAfterProcessing() {
        isProcessingCommand = false
        if (_isMicLockedOn.value && !isTtsSpeaking) {
            mainHandler.removeCallbacks(restartListeningRunnable)
            mainHandler.postDelayed(restartListeningRunnable, 300L)
        }
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts continuous always-on mic listening for Phone Control. Tapping Mic once locks it ON.
     */
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
        watchdogStallCount = 0
        useExplicitGoogleService = false
        forceEnglishLocaleFallback = false

        _isMicLockedOn.value = true
        _voiceState.value = VoiceState.LISTENING
        _lastError.value = null
        _statusHint.value = "মাইক্রোফোন চালু হচ্ছে..."

        activeLanguageTag = languageTag
        activePreferOffline = preferOffline
        activeOnPartial = onPartial
        activeOnFinal = onFinal
        activeOnError = onError

        // Pause 24/7 wake word synchronously via EventBus + Service so hardware mic is free immediately
        WakeWordForegroundService.pause(context)

        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.postDelayed({
            if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                startListeningInternal()
            }
        }, 300L)
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
        if (useExplicitGoogleService) {
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

        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
            ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context.applicationContext) }.getOrNull()
    }

    private fun startListeningInternal() {
        if (!_isMicLockedOn.value || isTtsSpeaking || isProcessingCommand) return

        mainHandler.post {
            if (!_isMicLockedOn.value || isTtsSpeaking || isProcessingCommand) return@post
            destroyRecognizerInternal()

            val sr = createBestRecognizer()
            if (sr == null) {
                _isMicLockedOn.value = false
                _voiceState.value = VoiceState.ERROR
                val msg = "Android Speech Recognition পাওয়া যায়নি (Google Popup ব্যবহার করুন)।"
                _lastError.value = msg
                _statusHint.value = msg
                WakeWordForegroundService.resume(context)
                activeOnError?.invoke(msg)
                onFallbackToSystemDialog?.invoke()
                return@post
            }

            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    mainHandler.removeCallbacks(readyWatchdogRunnable)
                    consecutiveFailureCount = 0
                    watchdogStallCount = 0
                    if (_isMicLockedOn.value && !isTtsSpeaking) {
                        _voiceState.value = VoiceState.LISTENING
                        _lastError.value = null
                        _statusHint.value = if (forceEnglishLocaleFallback) "শুনছি (English Mode)... বলুন বস" else "শুনছি... কথা বলুন বস"
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
                        WakeWordForegroundService.resume(context)
                        return
                    }

                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        _isMicLockedOn.value = false
                        _voiceState.value = VoiceState.ERROR
                        val msg = "Microphone permission প্রয়োজন।"
                        _lastError.value = msg
                        _statusHint.value = msg
                        WakeWordForegroundService.resume(context)
                        activeOnError?.invoke(msg)
                        return
                    }

                    // Transient silence / no match -> normal continuous restart
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        consecutiveFailureCount = 0
                        mainHandler.post {
                            destroyRecognizerInternal()
                            if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                                _voiceState.value = VoiceState.LISTENING
                                _statusHint.value = "শুনছি... কমান্ড বলুন বস"
                                mainHandler.removeCallbacks(restartListeningRunnable)
                                mainHandler.postDelayed(restartListeningRunnable, 350L)
                            }
                        }
                        return
                    }

                    // Language unsupported -> fall back to en-US immediately
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                        error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                        error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT
                    ) {
                        forceEnglishLocaleFallback = true
                        _lastError.value = "বাংলা স্পিচ প্যাক নেই — English voice mode-এ সুইচ হয়েছে"
                        mainHandler.post {
                            destroyRecognizerInternal()
                            if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                                mainHandler.removeCallbacks(restartListeningRunnable)
                                mainHandler.postDelayed(restartListeningRunnable, 350L)
                            }
                        }
                        return
                    }

                    consecutiveFailureCount++
                    val errText = mapErrorMessage(error)
                    Log.w(TAG, "SpeechRecognizer error=$error ($errText), consecutive=$consecutiveFailureCount")
                    _lastError.value = errText
                    _statusHint.value = errText
                    activeOnError?.invoke(errText)

                    // On repeated hard failures (ERROR_CLIENT, ERROR_AUDIO, ERROR_NETWORK, etc.), escalate cleanly
                    if (consecutiveFailureCount == 1 && !useExplicitGoogleService && hasExplicitGoogleService()) {
                        useExplicitGoogleService = true
                    } else if (consecutiveFailureCount >= 3) {
                        mainHandler.post {
                            destroyRecognizerInternal()
                            _isMicLockedOn.value = false
                            _voiceState.value = VoiceState.ERROR
                            WakeWordForegroundService.resume(context)
                            onFallbackToSystemDialog?.invoke()
                        }
                        return
                    }

                    val retryDelay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 700L
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> {
                            // Offline network error on bn-BD -> try en-US fallback
                            forceEnglishLocaleFallback = true
                            800L
                        }
                        else -> 650L
                    }

                    mainHandler.post {
                        destroyRecognizerInternal()
                        if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                            _voiceState.value = VoiceState.LISTENING
                            mainHandler.removeCallbacks(restartListeningRunnable)
                            mainHandler.postDelayed(restartListeningRunnable, retryDelay)
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

                    // Restart ONLY if TTS & async AI generation did not take over mic
                    if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
                        mainHandler.removeCallbacks(restartListeningRunnable)
                        mainHandler.postDelayed(restartListeningRunnable, 450L)
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLocale)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("bn-BD", "en-US", "bn-IN"))
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                if (activePreferOffline) {
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
            }

            recognizer = sr
            mainHandler.removeCallbacks(readyWatchdogRunnable)
            mainHandler.postDelayed(readyWatchdogRunnable, WATCHDOG_TIMEOUT_MS)

            runCatching {
                sr.startListening(intent)
            }.onFailure { err ->
                mainHandler.removeCallbacks(readyWatchdogRunnable)
                Log.w(TAG, "sr.startListening threw: ${err.message}")
                _lastError.value = "মাইক চালু সমস্যা: ${err.localizedMessage ?: "Retry"}"
                mainHandler.postDelayed(restartListeningRunnable, 600L)
            }
        }
    }

    private fun mapErrorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "অডিও রেকর্ড সমস্যা — রিসেট হচ্ছে"
        SpeechRecognizer.ERROR_CLIENT -> "স্পিচ ক্লায়েন্ট এরর — রিকানেক্ট হচ্ছে"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission বন্ধ"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ইন্টারনেট নেই — English mode ট্রাই হচ্ছে"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "মাইক রিসেট হচ্ছে..."
        SpeechRecognizer.ERROR_SERVER, 11 -> "Google Speech সার্ভার সংযোগ বিচ্ছিন্ন"
        else -> "ভয়েস এরর ($code) — Popup Mic ব্যবহার করতে পারেন"
    }

    fun requestRestartListening(delayMs: Long = 200L) {
        if (_isMicLockedOn.value && !isTtsSpeaking && !isProcessingCommand) {
            mainHandler.removeCallbacks(restartListeningRunnable)
            mainHandler.postDelayed(restartListeningRunnable, delayMs)
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
        isProcessingCommand = false
        _micRmsLevel.value = 0f
        if (!isTtsSpeaking) {
            _voiceState.value = VoiceState.IDLE
            _statusHint.value = "Tap Mic to start voice phone control"
        }
        mainHandler.removeCallbacks(restartListeningRunnable)
        mainHandler.removeCallbacks(readyWatchdogRunnable)
        mainHandler.post {
            destroyRecognizerInternal()
            if (resumeWakeWord) WakeWordForegroundService.resume(context)
        }
    }

    fun getVoicePackInfo(): TtsVoicePackInfo = humanVoiceEngine.getVoicePackInfo()

    fun openGoogleSpeechServicesPlayStore(context: Context) {
        val marketUri = Uri.parse("market://details?id=com.google.android.tts")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, marketUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    fun launchVoiceDataInstall(context: Context, isFemale: Boolean = false) {
        val intents = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                // Continue to next fallback
            }
        }
    }

    fun openTtsSettings(context: Context) {
        val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallback)
            } catch (_: Exception) {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }

    fun testVoiceSample(
        isFemale: Boolean,
        online: Boolean = isOnline,
        geminiKey: String = geminiApiKey,
        elevenKey: String = elevenLabsApiKey
    ) {
        val persona = if (isFemale) VoicePersona.ZHEE_FEMALE else VoicePersona.ZEE_MALE
        val testPhrase = if (isFemale) {
            "হ্যালো বস! আমি ঝী। তোমার নতুন আল্ট্রা-ন্যাচারাল হিউম্যান ভয়েস সক্রিয় আছে। সবসময় তোমার পাশে আছি।"
        } else {
            "ইয়েস বস! আমি জী। তোমার নতুন আল্ট্রা-ন্যাচারাল হিউম্যান ভয়েস সক্রিয় আছে। বলো, কীভাবে সাহায্য করতে পারি?"
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
