package com.zeeai.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class VoicePersona { ZEE_MALE, ZHEE_FEMALE }

class HumanVoiceEngine(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit
) {
    companion object {
        private const val TAG = "ZeeHumanVoice"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val ELEVEN_VOICE_MALE = "pNInz6obpgDQGcFmaJgB"   // Adam (Warm Male)
        private const val ELEVEN_VOICE_FEMALE = "EXAVITQu4vr4xnSDxMaL" // Bella/Sarah (Warm Female)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val offlineNeuralEngine = OfflineNeuralTtsEngine(context, onSpeechStart, onSpeechDone)

    private var androidTts: TextToSpeech? = null
    private var isTtsReady = false
    private var currentJob: Job? = null
    private var activeMediaPlayer: MediaPlayer? = null

    init {
        initLocalTts()
    }

    private fun initLocalTts() {
        var localInstance: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                val instance = localInstance ?: androidTts
                instance?.let { configureTtsAttributes(it) }
            } else {
                Log.w(TAG, "Primary TTS failed init, retrying default system engine")
                runCatching {
                    androidTts = TextToSpeech(context.applicationContext) { fallbackStatus ->
                        isTtsReady = (fallbackStatus == TextToSpeech.SUCCESS)
                        if (isTtsReady) androidTts?.let { configureTtsAttributes(it) }
                    }
                }
            }
        }

        localInstance = runCatching {
            TextToSpeech(context.applicationContext, listener, "com.google.android.tts")
        }.getOrElse {
            TextToSpeech(context.applicationContext, listener)
        }
        androidTts = localInstance
        configureTtsAttributes(localInstance)
    }

    private fun configureTtsAttributes(tts: TextToSpeech?) {
        if (tts == null) return
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) { onSpeechStart() }
                }
                override fun onDone(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) { onSpeechDone() }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    scope.launch(Dispatchers.Main) { onSpeechDone() }
                }
            })
        }
    }

    /**
     * Best available human voice দিয়ে কথা বলবে:
     * 1. Online + ElevenLabs Key থাকলে -> Cinema-Grade ElevenLabs Voice
     * 2. Online থাকলে -> High-Definition Google Translate Neural Voice (Zero robotic tone, 100% natural Bangla & English)
     * 3. Offline Sherpa-ONNX Voice Pack ডাউনলোড করা থাকলে -> Sherpa-ONNX VITS
     * 4. 100% Offline Fallback -> System HD Voice (Bangla supported হলে বাংলা, নতুবা ফোনেটিক বাংলিশে স্পষ্ট কথা বলবে)
     */
    fun speakHuman(
        rawText: String,
        persona: VoicePersona,
        isOnline: Boolean,
        geminiApiKey: String = "",
        elevenLabsApiKey: String = ""
    ) {
        val cleanText = cleanTextForNaturalSpeech(rawText)
        if (cleanText.isBlank()) return

        stopSpeaking()

        currentJob = scope.launch {
            // Ensure media volume is audible
            ensureAudibleVolume()

            // ১. ElevenLabs (যদি API Key থাকে)
            if (isOnline && elevenLabsApiKey.isNotBlank()) {
                val played = tryPlayElevenLabs(cleanText, persona, elevenLabsApiKey)
                if (played) return@launch
            }

            // ২. Google High-Definition Neural TTS (Free, Ultra-fast, 100% Human-like)
            if (isOnline) {
                val played = tryPlayGoogleNeuralTts(cleanText)
                if (played) return@launch
            }

            // ৩. 100% Offline Sherpa-ONNX Neural Voice (VITS/Piper model downloaded)
            val gender = if (persona == VoicePersona.ZEE_MALE) VoiceGender.MALE_ZEE else VoiceGender.FEMALE_ZHEE
            val neuralPlayed = offlineNeuralEngine.speakOfflineNeural(cleanText, gender)
            if (neuralPlayed) return@launch

            // ৪. Guaranteed Local HD Offline Voice
            speakOfflineHdVoice(cleanText, persona)
        }
    }

    private fun ensureAudibleVolume() {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (currentVol == 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.70f).toInt().coerceAtLeast(1), 0)
                }
            }
        }
    }

    /**
     * Google High-Definition Translate Neural Voice.
     * বাংলা টেক্সটের জন্য 'bn', ইংরেজি টেক্সটের জন্য 'en' দিয়ে সরাসরি ক্রিস্টাল ক্লিয়ার হিউম্যান অডিও স্ট্রিম করে।
     * বড় টেক্সট হলে বাক্য অনুযায়ী দ্রুত চাঙ্ক করে প্লে করে।
     */
    private suspend fun tryPlayGoogleNeuralTts(text: String): Boolean {
        return runCatching {
            val isBengali = text.any { it in '\u0980'..'\u09FF' }
            val langCode = if (isBengali) "bn" else "en"

            val sentences = splitIntoSentences(text)
            if (sentences.isEmpty()) return false

            var anyPlayed = false
            for (sentence in sentences) {
                if (!scope.isActive) break
                val encoded = URLEncoder.encode(sentence, "UTF-8")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$langCode&q=$encoded"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .build()

                val bytes = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.bytes()
                }

                if (bytes != null && bytes.size > 100) {
                    val tempFile = File(context.cacheDir, "zee_neural_${System.currentTimeMillis()}.mp3")
                    tempFile.writeBytes(bytes)
                    val played = playMp3File(tempFile)
                    tempFile.delete()
                    if (played) anyPlayed = true
                }
            }
            anyPlayed
        }.getOrElse {
            Log.w(TAG, "Google Neural TTS error: ${it.message}")
            false
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val rawChunks = text.split(Regex("(?<=[।?!.\n])\\s+"))
        for (raw in rawChunks) {
            val chunk = raw.trim()
            if (chunk.isNotBlank()) {
                if (chunk.length > 170) {
                    // Split long sentence at commas or spaces
                    var remaining = chunk
                    while (remaining.length > 170) {
                        val splitIndex = remaining.substring(0, 170).lastIndexOfAny(charArrayOf(',', ';', ' ', '،'))
                        val cutAt = if (splitIndex > 50) splitIndex else 170
                        result.add(remaining.substring(0, cutAt).trim())
                        remaining = remaining.substring(cutAt).trim()
                    }
                    if (remaining.isNotBlank()) result.add(remaining)
                } else {
                    result.add(chunk)
                }
            }
        }
        return if (result.isEmpty()) listOf(text.take(170)) else result
    }

    private suspend fun tryPlayElevenLabs(
        text: String,
        persona: VoicePersona,
        apiKey: String
    ): Boolean {
        return runCatching {
            val voiceId = if (persona == VoicePersona.ZEE_MALE) ELEVEN_VOICE_MALE else ELEVEN_VOICE_FEMALE
            val payload = JSONObject().apply {
                put("text", text.take(1200))
                put("model_id", "eleven_multilingual_v2")
                put(
                    "voice_settings",
                    JSONObject().apply {
                        put("stability", 0.45)
                        put("similarity_boost", 0.80)
                        put("style", 0.35)
                        put("use_speaker_boost", true)
                    }
                )
            }

            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .addHeader("xi-api-key", apiKey.trim())
                .addHeader("Accept", "audio/mpeg")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val mp3Bytes = resp.body?.bytes() ?: return false
                val tempFile = File(context.cacheDir, "zee_eleven_voice.mp3")
                tempFile.writeBytes(mp3Bytes)
                val played = playMp3File(tempFile)
                tempFile.delete()
                played
            }
        }.getOrDefault(false)
    }

    private suspend fun playMp3File(file: File): Boolean {
        val player = MediaPlayer()
        activeMediaPlayer = player
        val doneSignal = kotlinx.coroutines.CompletableDeferred<Boolean>()

        return withContext(Dispatchers.Main) {
            try {
                runCatching {
                    onSpeechStart()
                    player.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    player.setDataSource(file.absolutePath)
                    player.setOnCompletionListener {
                        if (!doneSignal.isCompleted) doneSignal.complete(true)
                    }
                    player.setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        if (!doneSignal.isCompleted) doneSignal.complete(false)
                        true
                    }
                    player.prepare()
                    player.start()
                }.onFailure {
                    Log.w(TAG, "MediaPlayer start failed: ${it.message}")
                    if (!doneSignal.isCompleted) doneSignal.complete(false)
                }

                doneSignal.await()
            } finally {
                runCatching {
                    if (player.isPlaying) player.stop()
                    player.release()
                }
                if (activeMediaPlayer === player) activeMediaPlayer = null
                onSpeechDone()
            }
        }
    }

    /**
     * Offline Local HD Voice Fallback:
     * ডিভাইসে বাংলা ভয়েস থাকলে সরাসরি বাংলায় পড়ে।
     * বাংলা না থাকলে (শুধুমাত্র English TTS থাকলে) বাংলা টেক্সটকে ফোনেটিক বাংলিশে রূপান্তর করে ইংরেজি ভয়েস দিয়ে স্পষ্ট আওয়াজে পড়ে।
     * ফলে ফোন কখনো সাইলেন্ট থাকে না।
     */
    private suspend fun speakOfflineHdVoice(text: String, persona: VoicePersona) = withContext(Dispatchers.Main) {
        var waitCount = 0
        while (!isTtsReady && waitCount < 15) {
            delay(100)
            waitCount++
        }

        var tts = androidTts
        if (tts == null || !isTtsReady) {
            val fallbackCompletable = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val fallbackTts = TextToSpeech(context.applicationContext) { status ->
                fallbackCompletable.complete(status == TextToSpeech.SUCCESS)
            }
            if (fallbackCompletable.await()) {
                androidTts = fallbackTts
                isTtsReady = true
                tts = fallbackTts
                configureTtsAttributes(fallbackTts)
            } else {
                Log.e(TAG, "No TTS engine could be initialized on device.")
                onSpeechDone()
                return@withContext
            }
        }

        configureTtsAttributes(tts)

        val isBengali = text.any { it in '\u0980'..'\u09FF' }

        // Check if device TTS natively supports Bengali
        val availableBnLocale = if (isBengali) {
            listOf(Locale("bn", "BD"), Locale("bn", "IN"), Locale("bn")).firstOrNull { loc ->
                val res = runCatching { tts.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                res >= TextToSpeech.LANG_AVAILABLE
            }
        } else null

        val textToSpeak: String
        if (isBengali && availableBnLocale != null) {
            tts.language = availableBnLocale
            textToSpeak = text
        } else if (isBengali) {
            // Bengali data not installed in system TTS -> Transliterate to Phonetic Banglish
            textToSpeak = BengaliToBanglish.toBanglish(text)
            tts.language = Locale.US
        } else {
            textToSpeak = text
            tts.language = Locale.US
        }

        // Set natural voice attributes
        val bestVoice = pickBestLocalVoice(tts, if (availableBnLocale != null) "bn" else "en", persona)
        if (bestVoice != null) {
            tts.voice = bestVoice
        }

        tts.setPitch(if (persona == VoicePersona.ZEE_MALE) 0.96f else 1.05f)
        tts.setSpeechRate(if (persona == VoicePersona.ZEE_MALE) 0.98f else 0.96f)

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val status = tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts.speak returned error: $status")
            onSpeechDone()
        }
    }

    private fun pickBestLocalVoice(
        tts: TextToSpeech,
        langPrefix: String,
        persona: VoicePersona
    ): Voice? {
        val allVoices = runCatching { tts.voices }.getOrNull().orEmpty()
        val langVoices = allVoices.filter {
            it.locale.language.equals(langPrefix, ignoreCase = true)
        }.sortedWith(
            compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
        )

        if (langVoices.isEmpty()) return null

        val preferredHints = if (persona == VoicePersona.ZEE_MALE) {
            listOf("bnx", "bad", "male", "-d-", "-b-", "tpd", "iom")
        } else {
            listOf("ban", "bnf", "female", "-a-", "-c-", "tpf", "sfg")
        }

        return langVoices.firstOrNull { v ->
            val name = v.name.lowercase()
            preferredHints.any { name.contains(it) }
        } ?: langVoices.firstOrNull()
    }

    fun cleanTextForNaturalSpeech(raw: String): String {
        return raw
            .replace(Regex("""\[\[TOOL:[^\]]+]]"""), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""[*_`#~>|]"""), "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    fun stopSpeaking() {
        currentJob?.cancel()
        currentJob = null
        offlineNeuralEngine.stop()
        runCatching {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        activeMediaPlayer = null
        runCatching { androidTts?.stop() }
    }

    fun release() {
        stopSpeaking()
        offlineNeuralEngine.release()
        runCatching { androidTts?.shutdown() }
        androidTts = null
    }
}
