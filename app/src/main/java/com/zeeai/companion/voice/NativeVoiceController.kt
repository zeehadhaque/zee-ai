package com.zeeai.companion.voice

import android.content.Context
import android.content.Intent
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
    val rate: Float
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
        tts?.language = Locale.forLanguageTag(profile.languageTag)
        tts?.setPitch(profile.pitch)
        tts?.setSpeechRate(profile.rate)
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "zee_utt_${System.currentTimeMillis()}")
    }

    fun stopSpeaking() {
        tts?.stop()
        onSpeakingStateChanged(false)
    }

    fun startListening(
        languageTag: String,
        preferOffline: Boolean,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopSpeaking() // Barge-in: stop AI voice when user starts speaking
        stopListening()

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
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
                onError("Voice recognition error code: $error")
            }

            override fun onResults(results: Bundle?) {
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        }

        recognizer = sr
        sr.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
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
