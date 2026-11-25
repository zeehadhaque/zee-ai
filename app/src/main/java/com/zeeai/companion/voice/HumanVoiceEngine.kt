package com.zeeai.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Fast timeout for Google Translate TTS so slow/blocked networks instantly cascade to Offline TTS
    private val googleTtsHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    private val offlineNeuralEngine = OfflineNeuralTtsEngine(appContext)

    @Volatile
    private var androidTts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false
    private var currentJob: Job? = null
    @Volatile
    private var activeMediaPlayer: MediaPlayer? = null

    private val sessionCounter = AtomicLong(0L)
    private val sessionSpeakingActive = AtomicBoolean(false)
    private var activeUtteranceId: String? = null
    private var activeUtteranceSignal: CompletableDeferred<Boolean>? = null
    private var activeFocusRequest: AudioFocusRequest? = null

    init {
        initLocalTts()
    }

    private fun initLocalTts() {
        runCatching {
            TextToSpeech(appContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    androidTts?.let { configureUtteranceListener(it) }
                } else {
                    Log.w(TAG, "Google TTS engine init failed ($status), falling back to device default TTS")
                    runCatching { androidTts?.shutdown() }
                    val fallback = TextToSpeech(appContext) { fbStatus ->
                        isTtsReady = (fbStatus == TextToSpeech.SUCCESS)
                        if (isTtsReady) androidTts?.let { configureUtteranceListener(it) }
                    }
                    androidTts = fallback
                }
            }, "com.google.android.tts").also { instance ->
                androidTts = instance
            }
        }.onFailure {
            runCatching {
                androidTts = TextToSpeech(appContext) { status ->
                    isTtsReady = (status == TextToSpeech.SUCCESS)
                    if (isTtsReady) androidTts?.let { configureUtteranceListener(it) }
                }
            }
        }
    }

    private fun configureUtteranceListener(tts: TextToSpeech) {
        runCatching {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // Start already notified for session
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == activeUtteranceId) {
                        activeUtteranceSignal?.complete(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == null || utteranceId == activeUtteranceId) {
                        activeUtteranceSignal?.complete(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "Android TTS onError utterance=$utteranceId code=$errorCode")
                    if (utteranceId == null || utteranceId == activeUtteranceId) {
                        activeUtteranceSignal?.complete(false)
                    }
                }
            })
        }
    }

    fun getVoicePackInfo(): TtsVoicePackInfo {
        val tts = androidTts
        val allVoices = runCatching { tts?.voices }.getOrNull().orEmpty()
        val installedLocal = allVoices.filter { v ->
            !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) && !v.isNetworkConnectionRequired
        }
        val hasBn = installedLocal.any { it.locale.language.equals("bn", ignoreCase = true) } ||
            OfflineVoiceCatalog.packs.any { it.language == VoiceLang.BENGALI && OfflineVoiceCatalog.isInstalled(appContext, it.id) }
        val hasEn = installedLocal.any { it.locale.language.equals("en", ignoreCase = true) } ||
            (runCatching { tts?.isLanguageAvailable(Locale.US) }.getOrDefault(-1) ?: -1) >= TextToSpeech.LANG_AVAILABLE
        val engine = runCatching { tts?.defaultEngine }.getOrNull() ?: "Android TTS"
        return TtsVoicePackInfo(
            totalVoices = allVoices.size,
            offlineVoices = installedLocal.size,
            hasBengali = hasBn,
            hasEnglish = hasEn,
            defaultEngine = engine
        )
    }

    /**
     * Best available human voice দিয়ে কথা বলবে:
     * 1. Online + ElevenLabs Key থাকলে -> Cinema-Grade ElevenLabs Voice
     * 2. Online থাকলে -> High-Definition Google Translate Neural Voice
     * 3. Offline Sherpa-ONNX Voice Pack ডাউনলোড করা থাকলে -> Sherpa-ONNX VITS
     * 4. Guaranteed Local HD Offline Voice (Bangla supported থাকলে বাংলা, নতুবা ফোনেটিক বাংলিশে স্পষ্ট কথা বলবে)
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

                // 1. ElevenLabs (if online & API Key present)
                if (!completed && isOnline && elevenLabsApiKey.isNotBlank()) {
                    completed = tryPlayElevenLabs(cleanText, persona, elevenLabsApiKey, mySessionId)
                }

                // 2. Google High-Definition Neural TTS (if online)
                if (!completed && isOnline) {
                    completed = tryPlayGoogleNeuralTts(cleanText, mySessionId)
                }

                // 3. 100% Offline Sherpa-ONNX Neural Voice (suspends until playback completes)
                if (!completed && isActive && sessionCounter.get() == mySessionId) {
                    val gender = if (persona == VoicePersona.ZEE_MALE) VoiceGender.MALE_ZEE else VoiceGender.FEMALE_ZHEE
                    completed = offlineNeuralEngine.speakOfflineNeural(cleanText, gender, notifyLifecycle = false)
                }

                // 4. Guaranteed Local HD Offline Voice (awaits utterance completion + retry fallback)
                if (!completed && isActive && sessionCounter.get() == mySessionId) {
                    speakOfflineHdVoice(cleanText, persona, isOnline, mySessionId)
                }
            } catch (_: CancellationException) {
                // Superseded by new speak() or stopped by user
            } catch (t: Throwable) {
                Log.e(TAG, "speakHuman failed: ${t.message}", t)
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

    private fun ensureAudibleVolume() {
        runCatching {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                if (currentVol <= 1 && maxVol > 2) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.70f).toInt().coerceAtLeast(1), 0)
                }
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
        // Reject HTML or JSON masquerading with wrong MIME
        val head = bytes.take(16).toByteArray().decodeToString().trimStart()
        if (head.startsWith("<") || head.startsWith("{")) return false
        return true
    }

    private suspend fun tryPlayGoogleNeuralTts(text: String, mySessionId: Long): Boolean {
        return runCatching {
            val isBengali = text.any { it in '\u0980'..'\u09FF' }
            val langCode = if (isBengali) "bn" else "en"

            val sentences = splitIntoSentences(text)
            if (sentences.isEmpty()) return false

            var anyPlayed = false
            for (sentence in sentences) {
                if (!scope.isActive || sessionCounter.get() != mySessionId) break
                val encoded = URLEncoder.encode(sentence, "UTF-8")
                val url = "https://translate.google.com/translate_tts?ie=UTF-8&client=tw-ob&tl=$langCode&q=$encoded"

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36")
                    .build()

                val bytes = googleTtsHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val ct = resp.header("Content-Type")
                        val raw = resp.body?.bytes()
                        if (isValidAudioPayload(ct, raw)) raw else null
                    }
                } ?: return false

                if (sessionCounter.get() != mySessionId) return false

                val tempFile = File(appContext.cacheDir, "zee_neural_${System.currentTimeMillis()}.mp3")
                tempFile.writeBytes(bytes)
                val played = playMp3File(tempFile)
                runCatching { tempFile.delete() }
                if (!played) return anyPlayed
                anyPlayed = true
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
        apiKey: String,
        mySessionId: Long
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
                if (!resp.isSuccessful) {
                    Log.w(TAG, "ElevenLabs HTTP ${resp.code}")
                    return false
                }
                val mp3Bytes = resp.body?.bytes()
                if (!isValidAudioPayload(resp.header("Content-Type"), mp3Bytes)) return false
                if (sessionCounter.get() != mySessionId) return false
                val tempFile = File(appContext.cacheDir, "zee_eleven_voice.mp3")
                tempFile.writeBytes(mp3Bytes!!)
                val played = playMp3File(tempFile)
                runCatching { tempFile.delete() }
                played
            }
        }.getOrDefault(false)
    }

    private suspend fun playMp3File(file: File): Boolean {
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
                    runCatching { mp.start() }.onFailure {
                        if (!doneSignal.isCompleted) doneSignal.complete(false)
                    }
                }
                player?.setDataSource(file.absolutePath)
                player?.prepareAsync()

                withTimeoutOrNull(35_000L) {
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

    /**
     * Offline Local HD Voice Fallback:
     * ডিভাইসে অ্যাকচুয়াল ইনস্টল্ড বাংলা ভয়েস থাকলে বাংলায় পড়ে,
     * নতুবা ফোনেটিক বাংলিশে রূপান্তর করে ইনস্টল্ড ইংরেজি ভয়েসে স্পষ্ট আওয়াজে পড়ে।
     */
    private suspend fun speakOfflineHdVoice(
        text: String,
        persona: VoicePersona,
        isOnline: Boolean,
        mySessionId: Long
    ) = withContext(Dispatchers.Main) {
        var waitCount = 0
        while (!isTtsReady && waitCount < 20) {
            if (sessionCounter.get() != mySessionId) return@withContext
            delay(100)
            waitCount++
        }

        var tts = androidTts
        if (tts == null || !isTtsReady) {
            val fallbackCompletable = CompletableDeferred<Boolean>()
            val fallbackTts = TextToSpeech(appContext) { status ->
                if (!fallbackCompletable.isCompleted) {
                    fallbackCompletable.complete(status == TextToSpeech.SUCCESS)
                }
            }
            val ready = withTimeoutOrNull(2500L) { fallbackCompletable.await() } == true
            if (ready) {
                runCatching { androidTts?.shutdown() }
                androidTts = fallbackTts
                isTtsReady = true
                tts = fallbackTts
            } else {
                runCatching { fallbackTts.shutdown() }
                Log.e(TAG, "No TTS engine could be initialized on device.")
                return@withContext
            }
        }

        if (sessionCounter.get() != mySessionId) return@withContext
        configureUtteranceListener(tts)

        val isBengali = text.any { it in '\u0980'..'\u09FF' }
        val allVoices = runCatching { tts.voices }.getOrNull().orEmpty()

        // Verify whether device truly has a working installed Bengali voice (never pick KEY_FEATURE_NOT_INSTALLED!)
        val installedBnVoiceExists = allVoices.any { v ->
            v.locale.language.equals("bn", ignoreCase = true) &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                (isOnline || !v.isNetworkConnectionRequired)
        }

        val availableBnLocale = if (isBengali && (installedBnVoiceExists || allVoices.isEmpty())) {
            listOf(Locale("bn", "BD"), Locale("bn", "IN"), Locale("bn")).firstOrNull { loc ->
                val res = runCatching { tts.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                res >= TextToSpeech.LANG_AVAILABLE
            }
        } else null

        val primaryAttemptOk: Boolean
        if (isBengali && availableBnLocale != null) {
            tts.language = availableBnLocale
            pickBestInstalledVoice(tts, "bn", persona, isOnline)?.let { tts.voice = it }
            primaryAttemptOk = speakAndAwaitTts(tts, text, persona, mySessionId)
        } else {
            val spokenText = if (isBengali) BengaliToBanglish.toBanglish(text) else text
            val enLocale = findBestEnglishOrDefaultLocale(tts)
            tts.language = enLocale
            pickBestInstalledVoice(tts, enLocale.language, persona, isOnline)?.let { tts.voice = it }
            primaryAttemptOk = speakAndAwaitTts(tts, spokenText, persona, mySessionId)
        }

        // Guaranteed emergency retry with Phonetic Banglish + device default locale & offline voice
        if (!primaryAttemptOk && sessionCounter.get() == mySessionId) {
            Log.w(TAG, "Primary local TTS failed, retrying clean default locale + Banglish fallback")
            val emergencyText = if (isBengali) BengaliToBanglish.toBanglish(text) else text
            val safeLocale = findBestEnglishOrDefaultLocale(tts)
            runCatching {
                tts.language = safeLocale
                val offlineEn = pickBestInstalledVoice(tts, safeLocale.language, persona, isOnline = false)
                if (offlineEn != null) tts.voice = offlineEn
                else tts.defaultVoice?.let { tts.voice = it }
            }
            speakAndAwaitTts(tts, emergencyText, persona, mySessionId)
        }
    }

    private fun findBestEnglishOrDefaultLocale(tts: TextToSpeech): Locale {
        val candidates = listOf(Locale.US, Locale("en", "IN"), Locale.UK, Locale("en"), Locale.getDefault())
        return candidates.firstOrNull { loc ->
            runCatching { tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        } ?: Locale.getDefault()
    }

    private suspend fun speakAndAwaitTts(
        tts: TextToSpeech,
        textToSpeak: String,
        persona: VoicePersona,
        mySessionId: Long
    ): Boolean {
        if (textToSpeak.isBlank() || sessionCounter.get() != mySessionId) return false
        tts.setPitch(if (persona == VoicePersona.ZEE_MALE) 0.94f else 1.06f)
        tts.setSpeechRate(if (persona == VoicePersona.ZEE_MALE) 0.98f else 0.96f)

        val utteranceId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        activeUtteranceId = utteranceId
        activeUtteranceSignal = deferred

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val status = runCatching {
            tts.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }.getOrDefault(TextToSpeech.ERROR)

        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "tts.speak returned error: $status")
            activeUtteranceId = null
            activeUtteranceSignal = null
            return false
        }

        val timeoutMs = (textToSpeak.length * 140L + 6000L).coerceIn(8_000L, 45_000L)
        return withTimeoutOrNull(timeoutMs) {
            deferred.await()
        } ?: false
    }

    private fun pickBestInstalledVoice(
        tts: TextToSpeech,
        langPrefix: String,
        persona: VoicePersona,
        isOnline: Boolean
    ): Voice? {
        val allVoices = runCatching { tts.voices }.getOrNull().orEmpty()
        val installedVoices = allVoices.filter { v ->
            v.locale.language.equals(langPrefix, ignoreCase = true) &&
                !v.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) &&
                (isOnline || !v.isNetworkConnectionRequired)
        }.sortedWith(
            compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                .thenByDescending { it.quality }
        )

        if (installedVoices.isEmpty()) return null

        val preferredHints = if (persona == VoicePersona.ZEE_MALE) {
            listOf("bnx", "bad", "male", "-d-", "-b-", "tpd", "iom")
        } else {
            listOf("ban", "bnf", "female", "-a-", "-c-", "tpf", "sfg")
        }

        return installedVoices.firstOrNull { v ->
            val name = v.name.lowercase()
            preferredHints.any { name.contains(it) }
        } ?: installedVoices.firstOrNull()
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
        activeUtteranceSignal?.let { if (!it.isCompleted) it.complete(false) }
        activeUtteranceId = null
        activeUtteranceSignal = null
        offlineNeuralEngine.stop()
        runCatching {
            activeMediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        activeMediaPlayer = null
        runCatching { androidTts?.stop() }
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
        offlineNeuralEngine.release()
        runCatching { androidTts?.shutdown() }
        androidTts = null
    }
}
