package com.zeeai.companion.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
            _voiceState.value = VoiceState.SPEAKING
            onSpeakingStateChanged(true)
            mainHandler.post { recognizer?.cancel() }
        },
        onSpeechDone = {
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
    )

    init {
        // HumanVoiceEngine handles all TTS playback reliably
    }

    fun speak(
        text: String,
        profile: NativeVoiceProfile,
        online: Boolean = isOnline,
        geminiKey: String = geminiApiKey,
        elevenKey: String = elevenLabsApiKey
    ) {
        if (text.isBlank()) return
        stopSpeaking()
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, activeLanguageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, activeLanguageTag)
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("bn-BD", "en-US", "bn-IN"))
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

    fun getVoicePackInfo(): TtsVoicePackInfo {
        val allVoices = runCatching { tts?.voices }.getOrNull() ?: emptySet()
        val total = allVoices.size
        val offline = allVoices.count { !it.isNetworkConnectionRequired }
        val hasBn = allVoices.any { it.locale.language.equals("bn", ignoreCase = true) } ||
                    (tts?.isLanguageAvailable(Locale.forLanguageTag("bn-BD")) ?: -1) >= 0
        val hasEn = allVoices.any { it.locale.language.equals("en", ignoreCase = true) } ||
                    (tts?.isLanguageAvailable(Locale.US) ?: -1) >= 0
        val engine = tts?.defaultEngine ?: "Default"
        return TtsVoicePackInfo(total, offline, hasBn, hasEn, engine)
    }

    fun openGoogleSpeechServicesPlayStore(context: Context) {
        val marketUri = Uri.parse("market://details?id=com.google.android.tts")
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, marketUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Exception) {}
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
            "হ্যালো! আমি ঝী। তোমার নতুন আল্ট্রা-ন্যাচারাল হিউম্যান ভয়েস সক্রিয় আছে। আমি সবসময় তোমার পাশে আছি।"
        } else {
            "নমস্কার! আমি জী। তোমার নতুন আল্ট্রা-ন্যাচারাল হিউম্যান ভয়েস সক্রিয় আছে। বলো, কীভাবে সাহায্য করতে পারি?"
        }
        humanVoiceEngine.speakHuman(
            rawText = testPhrase,
            persona = persona,
            isOnline = online,
            geminiApiKey = geminiKey,
            elevenLabsApiKey = elevenKey
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
