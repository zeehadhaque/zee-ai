package com.zeeai.companion.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = onSpeakingStateChanged(true)
                override fun onDone(utteranceId: String?) = onSpeakingStateChanged(false)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = onSpeakingStateChanged(false)
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

        // Select distinct male or female voice matching the active persona
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
        onSpeakingStateChanged(false)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun startListening(
        languageTag: String,
        preferOffline: Boolean = false,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopSpeaking() // Barge-in: stop AI voice when user starts speaking
        stopListening()

        // Seamless mic handoff: pause wake word background detection while active listening is engaged
        WakeWordForegroundService.pause(context)

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            WakeWordForegroundService.resume(context)
            onError("Android Speech Recognition service not available on this device.")
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                WakeWordForegroundService.resume(context)
                onError("Voice recognition error code: $error")
            }

            override fun onResults(results: Bundle?) {
                WakeWordForegroundService.resume(context)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onFinal(text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val shouldPreferOffline = preferOffline || !isNetworkAvailable()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, shouldPreferOffline)
            putExtra("android.speech.extra.DICTATION_MODE", true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        recognizer = sr
        sr.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        WakeWordForegroundService.resume(context)
    }

    fun emergencyStop() {
        stopSpeaking()
        stopListening()
    }

    fun shutdown() {
        emergencyStop()
        tts?.shutdown()
        tts = null
    }
}
