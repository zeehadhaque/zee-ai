package com.zeeai.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Base64
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
import java.net.URLEncoder
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
 * Cascade:
 * 1. ElevenLabs Multilingual v2 (Cinema-grade expressive human voice, custom Male/Female voice IDs)
 * 2. Google Gemini 2.5 Studio Native Audio (`gemini-2.5-flash-preview-tts`, real 24kHz PCM Male/Female studio voices)
 * 3. Google Cloud Neural Translate Audio (Zero-API-Key online human voice in Bengali/English)
 *
 * Offline robotic TTS / Sherpa-ONNX packs are permanently removed.
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

    private val cloudFreeHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var voiceConfig: OnlineVoiceConfig = OnlineVoiceConfig()

    private var currentJob: Job? = null
    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null
    @Volatile
    private var activeAudioTrack: AudioTrack? = null

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
                maleVoiceLabel = "ZEE (${cfg.geminiMaleVoice})",
                femaleVoiceLabel = "ZHEE (${cfg.geminiFemaleVoice})",
                isReady = false
            )
            elevenLabsApiKey.isNotBlank() -> OnlineVoiceStatusInfo(
                activeProviderName = "ElevenLabs Cinema Studio v2",
                maleVoiceLabel = OnlineHumanVoiceCatalog.getVoiceDisplayName(cfg.elevenLabsMaleId, isFemale = false),
                femaleVoiceLabel = OnlineHumanVoiceCatalog.getVoiceDisplayName(cfg.elevenLabsFemaleId, isFemale = true),
                isReady = true
            )
            geminiApiKey.isNotBlank() -> OnlineVoiceStatusInfo(
                activeProviderName = "Gemini 2.5 Studio Neural Voice",
                maleVoiceLabel = "ZEE • ${cfg.geminiMaleVoice}",
                femaleVoiceLabel = "ZHEE • ${cfg.geminiFemaleVoice}",
                isReady = true
            )
            else -> OnlineVoiceStatusInfo(
                activeProviderName = "Cloud Neural Voice (Free Online)",
                maleVoiceLabel = "ZEE • Cloud Male Tone",
                femaleVoiceLabel = "ZHEE • Cloud Female Tone",
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

                // 2. Google Gemini 2.5 Native Studio TTS (Charon/Fenrir Male, Kore/Aoede Female)
                if (!completed && isActive && sessionCounter.get() == mySessionId && geminiApiKey.isNotBlank()) {
                    completed = tryPlayGeminiStudioTts(cleanText, persona, geminiApiKey, mySessionId)
                }

                // 3. Free Cloud Neural Human Voice (Zero-Key Online Bengali & English)
                if (!completed && isActive && sessionCounter.get() == mySessionId) {
                    completed = tryPlayCloudNeuralTts(cleanText, persona, mySessionId)
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

    /**
     * Calls Google Gemini 2.5 Studio TTS (`gemini-2.5-flash-preview-tts` with Pro fallback)
     * and plays the studio 24kHz PCM human audio cleanly through AudioTrack.
     */
    private suspend fun tryPlayGeminiStudioTts(
        text: String,
        persona: VoicePersona,
        apiKey: String,
        mySessionId: Long
    ): Boolean {
        val cfg = voiceConfig
        val voiceName = if (persona == VoicePersona.ZEE_MALE) cfg.geminiMaleVoice else cfg.geminiFemaleVoice
        val models = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")

        for (modelId in models) {
            if (!scope.isActive || sessionCounter.get() != mySessionId) return false
            val played = runCatching {
                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", text.take(1500)))
                            )
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                            put(
                                "speechConfig",
                                JSONObject().put(
                                    "voiceConfig",
                                    JSONObject().put(
                                        "prebuiltVoiceConfig",
                                        JSONObject().put("voiceName", voiceName)
                                    )
                                )
                            )
                        }
                    )
                }

                val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=${apiKey.trim()}"
                val req = Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", apiKey.trim())
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                apiHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "Gemini TTS ($modelId) HTTP ${resp.code}")
                        return@runCatching false
                    }
                    val bodyStr = resp.body?.string().orEmpty()
                    val root = JSONObject(bodyStr)
                    val parts = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?: return@runCatching false

                    var inlineData: JSONObject? = null
                    for (i in 0 until parts.length()) {
                        val candidateData = parts.optJSONObject(i)?.optJSONObject("inlineData")
                        if (candidateData != null && candidateData.optString("data").isNotBlank()) {
                            inlineData = candidateData
                            break
                        }
                    }
                    if (inlineData == null) return@runCatching false

                    val mimeType = inlineData.optString("mimeType", "audio/L16;codec=pcm;rate=24000")
                    val b64 = inlineData.optString("data")
                    val audioBytes = Base64.decode(b64, Base64.DEFAULT)
                    if (audioBytes.size < 256 || sessionCounter.get() != mySessionId) {
                        return@runCatching false
                    }

                    val sampleRate = Regex("""rate=(\d+)""").find(mimeType)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                    if (mimeType.contains("mp3", ignoreCase = true) || mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("wav", ignoreCase = true)) {
                        val ext = if (mimeType.contains("wav", ignoreCase = true)) "wav" else "mp3"
                        val temp = File(appContext.cacheDir, "zee_gem_${System.currentTimeMillis()}.$ext")
                        temp.writeBytes(audioBytes)
                        val ok = playMp3File(temp, pitch = 1.0f, speed = 1.0f)
                        runCatching { temp.delete() }
                        ok
                    } else {
                        playPcm16Bytes(audioBytes, sampleRate, mySessionId)
                    }
                }
            }.getOrElse {
                Log.w(TAG, "Gemini TTS ($modelId) error: ${it.message}")
                false
            }
            if (played) return true
        }
        return false
    }

    private suspend fun tryPlayCloudNeuralTts(
        text: String,
        persona: VoicePersona,
        mySessionId: Long
    ): Boolean {
        return runCatching {
            val isBengali = text.any { it in '\u0980'..'\u09FF' }
            val langCode = if (isBengali) "bn" else "en"
            val sentences = splitIntoSentences(text)
            if (sentences.isEmpty()) return false

            val pitch = if (persona == VoicePersona.ZEE_MALE) 0.90f else 1.05f
            val speed = if (persona == VoicePersona.ZEE_MALE) 1.02f else 0.98f

            var anyPlayed = false
            for (sentence in sentences) {
                if (!scope.isActive || sessionCounter.get() != mySessionId) break
                val encoded = URLEncoder.encode(sentence, "UTF-8")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$langCode&q=$encoded"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .build()

                val bytes = cloudFreeHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val ct = resp.header("Content-Type")
                        val raw = resp.body?.bytes()
                        if (isValidAudioPayload(ct, raw)) raw else null
                    }
                } ?: return anyPlayed

                if (sessionCounter.get() != mySessionId) return anyPlayed

                val tempFile = File(appContext.cacheDir, "zee_cloud_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(bytes)
                val played = playMp3File(tempFile, pitch = pitch, speed = speed)
                runCatching { tempFile.delete() }
                if (!played) return anyPlayed
                anyPlayed = true
            }
            anyPlayed
        }.getOrElse {
            Log.w(TAG, "Cloud Neural TTS error: ${it.message}")
            false
        }
    }

    private suspend fun playPcm16Bytes(
        pcmBytes: ByteArray,
        sampleRate: Int,
        mySessionId: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val alignedSize = pcmBytes.size and 1.inv()
        if (alignedSize <= 0) return@withContext false

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()

        activeAudioTrack = track
        try {
            track.play()
            var offset = 0
            val chunk = 4096
            while (offset < alignedSize && activeAudioTrack === track && sessionCounter.get() == mySessionId) {
                val count = minOf(chunk, alignedSize - offset)
                val written = track.write(pcmBytes, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (activeAudioTrack === track) {
                runCatching { track.stop() }
            }
            offset > 0
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack PCM playback failed: ${t.message}")
            false
        } finally {
            runCatching { track.release() }
            if (activeAudioTrack === track) activeAudioTrack = null
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
        val track = activeAudioTrack
        activeAudioTrack = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
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
