package com.zeeai.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class VoicePersona { ZEE_MALE, ZHEE_FEMALE }

data class OnlineVoiceStatusInfo(
    val activeProviderName: String,
    val maleVoiceLabel: String,
    val femaleVoiceLabel: String,
    val isReady: Boolean
)

/**
 * 100% Online Human Neural Voice Engine for ZEE (Male) & ZHEE (Female).
 *
 * Cascade (Google TTS and all offline robotic voices permanently removed):
 * 1. ElevenLabs Multilingual v2 (Cinema-grade expressive human voice, when API key configured)
 * 2. Microsoft Edge Neural Studio (`bn-BD-PradeepNeural` / `bn-BD-NabanitaNeural` / `AndrewMultilingualNeural` / `AvaMultilingualNeural`, zero-key HD human neural audio)
 */
class HumanVoiceEngine(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit,
    private val onVoiceError: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "ZeeOnlineVoice"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val apiHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var voiceConfig: OnlineVoiceConfig = OnlineVoiceConfig()

    private var currentJob: Job? = null
    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    private val sessionCounter = AtomicLong(0L)
    private val sessionSpeakingActive = AtomicBoolean(false)
    private var activeFocusRequest: AudioFocusRequest? = null

    init {
        // Permanently purge any leftover offline voice downloads from earlier builds
        runCatching {
            File(appContext.filesDir, "offline_neural_voices").deleteRecursively()
        }
    }

    fun updateVoiceConfig(config: OnlineVoiceConfig) {
        this.voiceConfig = config
    }

    fun getVoiceStatusInfo(
        isOnline: Boolean,
        geminiApiKey: String,
        elevenLabsApiKey: String
    ): OnlineVoiceStatusInfo {
        val cfg = voiceConfig
        return when {
            !isOnline -> OnlineVoiceStatusInfo(
                activeProviderName = "Internet Required (Online Voice Only)",
                maleVoiceLabel = "ZEE (PradeepNeural / Andrew)",
                femaleVoiceLabel = "ZHEE (NabanitaNeural / Ava)",
                isReady = false
            )
            elevenLabsApiKey.isNotBlank() -> OnlineVoiceStatusInfo(
                activeProviderName = "ElevenLabs Cinema Studio v2 + Edge Neural HD",
                maleVoiceLabel = OnlineHumanVoiceCatalog.getVoiceDisplayName(cfg.elevenLabsMaleId, isFemale = false),
                femaleVoiceLabel = OnlineHumanVoiceCatalog.getVoiceDisplayName(cfg.elevenLabsFemaleId, isFemale = true),
                isReady = true
            )
            else -> OnlineVoiceStatusInfo(
                activeProviderName = "Edge Neural Studio HD (Zero-Key Human Voice)",
                maleVoiceLabel = "ZEE • PradeepNeural / Andrew",
                femaleVoiceLabel = "ZHEE • NabanitaNeural / Ava",
                isReady = true
            )
        }
    }

    /**
     * Fetches custom + library voices directly from user's ElevenLabs account.
     */
    suspend fun fetchElevenLabsAccountVoices(apiKey: String): Result<List<ElevenLabsVoicePreset>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("ElevenLabs API Key প্রয়োজন"))
        }
        runCatching {
            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/voices")
                .addHeader("xi-api-key", cleanKey)
                .addHeader("Accept", "application/json")
                .get()
                .build()
            apiHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val raw = resp.body?.string().orEmpty()
                val arr = JSONObject(raw).optJSONArray("voices") ?: JSONArray()
                val out = mutableListOf<ElevenLabsVoicePreset>()
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val id = v.optString("voice_id")
                    val name = v.optString("name")
                    val labels = v.optJSONObject("labels")
                    val gender = labels?.optString("gender").orEmpty().lowercase()
                    val desc = labels?.optString("description").orEmpty().ifBlank { v.optString("category", "ElevenLabs") }
                    if (id.isNotBlank() && name.isNotBlank()) {
                        out.add(ElevenLabsVoicePreset(id = id, name = name, tag = desc, isFemale = gender == "female"))
                    }
                }
                out
            }
        }
    }

    /**
     * Speaks text using 100% Online Human Neural Voices (no offline/robotic TTS fallback).
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

        stopPlaybackInternals()
        val mySessionId = sessionCounter.incrementAndGet()

        if (!isOnline) {
            sessionSpeakingActive.set(false)
            scope.launch(Dispatchers.Main) {
                onVoiceError("অনলাইন হিউম্যান ভয়েসের জন্য ইন্টারনেট সংযোগ প্রয়োজন বস।")
                onSpeechDone()
            }
            return
        }

        sessionSpeakingActive.set(true)

        currentJob = scope.launch {
            try {
                withContext(Dispatchers.Main) {
                    if (sessionCounter.get() == mySessionId) {
                        onSpeechStart()
                    }
                }

                ensureAudibleVolume()
                requestSpeechAudioFocus()

                var completed = false

                // 1. ElevenLabs Cinema-Grade Multilingual v2
                if (!completed && elevenLabsApiKey.isNotBlank()) {
                    completed = tryPlayElevenLabs(cleanText, persona, elevenLabsApiKey, mySessionId)
                }

                // 2. Microsoft Edge Neural Studio HD (100% Google-Free Natural Bengali & English Human Voices)
                if (!completed && isActive && sessionCounter.get() == mySessionId) {
                    completed = tryPlayEdgeNeuralStudio(cleanText, persona, mySessionId)
                }

                if (!completed && isActive && sessionCounter.get() == mySessionId) {
                    withContext(Dispatchers.Main) {
                        onVoiceError("অনলাইন ভয়েস সার্ভার থেকে অডিও পাওয়া যায়নি—ইন্টারনেট বা API Key চেক করুন।")
                    }
                }
            } catch (_: CancellationException) {
                // Interrupted or superseded
            } catch (t: Throwable) {
                Log.e(TAG, "speakHuman online failed: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    onVoiceError("ভয়েস এরর: ${t.localizedMessage ?: "Online TTS error"}")
                }
            } finally {
                withContext(NonCancellable) {
                    abandonSpeechAudioFocus()
                    if (sessionCounter.get() == mySessionId && sessionSpeakingActive.getAndSet(false)) {
                        withContext(Dispatchers.Main) {
                            onSpeechDone()
                        }
                    }
                }
            }
        }
    }

    private suspend fun tryPlayElevenLabs(
        text: String,
        persona: VoicePersona,
        apiKey: String,
        mySessionId: Long
    ): Boolean {
        return runCatching {
            val cfg = voiceConfig
            val voiceId = if (persona == VoicePersona.ZEE_MALE) cfg.elevenLabsMaleId else cfg.elevenLabsFemaleId
            val payload = JSONObject().apply {
                put("text", text.take(1500))
                put("model_id", "eleven_multilingual_v2")
                put(
                    "voice_settings",
                    JSONObject().apply {
                        put("stability", 0.42)
                        put("similarity_boost", 0.82)
                        put("style", 0.38)
                        put("use_speaker_boost", true)
                    }
                )
            }

            val req = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId.trim()}")
                .addHeader("xi-api-key", apiKey.trim())
                .addHeader("Accept", "audio/mpeg")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            apiHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "ElevenLabs HTTP ${resp.code}: ${resp.body?.string()?.take(160)}")
                    return false
                }
                val mp3Bytes = resp.body?.bytes()
                if (!isValidAudioPayload(resp.header("Content-Type"), mp3Bytes)) return false
                if (sessionCounter.get() != mySessionId) return false

                val tempFile = File(appContext.cacheDir, "zee_eleven_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(mp3Bytes!!)
                val played = playMp3File(tempFile, pitch = 1.0f, speed = 1.0f)
                runCatching { tempFile.delete() }
                played
            }
        }.getOrElse {
            Log.w(TAG, "ElevenLabs error: ${it.message}")
            false
        }
    }

    private suspend fun tryPlayEdgeNeuralStudio(
        text: String,
        persona: VoicePersona,
        mySessionId: Long
    ): Boolean {
        return runCatching {
            val isFemale = persona == VoicePersona.ZHEE_FEMALE
            val sentences = splitIntoSentences(text)
            if (sentences.isEmpty()) return false

            var anyPlayed = false
            for (chunk in sentences) {
                if (!scope.isActive || sessionCounter.get() != mySessionId) break
                val mp3Bytes = EdgeNeuralTtsClient.synthesizeMp3(apiHttpClient, chunk, isFemale)
                    ?: return anyPlayed
                if (sessionCounter.get() != mySessionId) return anyPlayed

                val tempFile = File(appContext.cacheDir, "zee_edge_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(mp3Bytes)
                val played = playMp3File(tempFile, pitch = 1.0f, speed = 1.0f)
                runCatching { tempFile.delete() }
                if (!played) return anyPlayed
                anyPlayed = true
            }
            anyPlayed
        }.getOrElse {
            Log.w(TAG, "Edge Neural Studio TTS error: ${it.message}")
            false
        }
    }

    private suspend fun playMp3File(file: File, pitch: Float, speed: Float): Boolean {
        val doneSignal = CompletableDeferred<Boolean>()
        var player: MediaPlayer? = null

        return withContext(Dispatchers.Main) {
            try {
                player = MediaPlayer()
                activeMediaPlayer = player
                player?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player?.setOnCompletionListener {
                    if (!doneSignal.isCompleted) doneSignal.complete(true)
                }
                player?.setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    if (!doneSignal.isCompleted) doneSignal.complete(false)
                    true
                }
                player?.setOnPreparedListener { mp ->
                    runCatching {
                        if (pitch != 1.0f || speed != 1.0f) {
                            mp.playbackParams = PlaybackParams().setPitch(pitch).setSpeed(speed)
                        }
                    }
                    runCatching { mp.start() }.onFailure {
                        if (!doneSignal.isCompleted) doneSignal.complete(false)
                    }
                }
                player?.setDataSource(file.absolutePath)
                player?.prepareAsync()

                withTimeoutOrNull(45_000L) {
                    doneSignal.await()
                } ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "MediaPlayer play failed: ${t.message}")
                false
            } finally {
                runCatching {
                    player?.let { mp ->
                        if (mp.isPlaying) mp.stop()
                        mp.release()
                    }
                }
                if (activeMediaPlayer === player) activeMediaPlayer = null
            }
        }
    }

    private fun ensureAudibleVolume() {
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (currentVol <= 1 && maxVol > 2) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.70f).toInt().coerceAtLeast(1), 0)
            }
        }
    }

    private fun requestSpeechAudioFocus() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
            activeFocusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonSpeechAudioFocus() {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            activeFocusRequest = null
        }
    }

    private fun isValidAudioPayload(contentType: String?, bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 256) return false
        val ct = contentType.orEmpty().lowercase()
        if (ct.contains("text/html") || ct.contains("application/json")) return false
        val head = bytes.take(16).toByteArray().decodeToString().trimStart()
        if (head.startsWith("<") || head.startsWith("{")) return false
        return true
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val rawChunks = text.split(Regex("(?<=[।?!.\n])\\s+"))
        for (raw in rawChunks) {
            val chunk = raw.trim()
            if (chunk.isNotBlank()) {
                if (chunk.length > 170) {
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

    private fun stopPlaybackInternals() {
        currentJob?.cancel()
        currentJob = null
        runCatching {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        activeMediaPlayer = null
        abandonSpeechAudioFocus()
    }

    fun stopSpeaking() {
        sessionCounter.incrementAndGet()
        stopPlaybackInternals()
        if (sessionSpeakingActive.getAndSet(false)) {
            onSpeechDone()
        }
    }

    fun release() {
        stopSpeaking()
    }
}
