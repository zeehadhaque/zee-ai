package com.zeeai.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OfflineNeuralTtsEngine(
    private val context: Context,
    private val onSpeechStart: () -> Unit,
    private val onSpeechDone: () -> Unit
) {
    companion object {
        private const val TAG = "ZeeOfflineNeuralTTS"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engineMutex = Mutex()

    private var activeTts: OfflineTts? = null
    private var loadedPackId: String? = null
    private var speakJob: Job? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    /**
     * টেক্সট বাংলা না ইংরেজি তা বুঝে ZEE (Male) বা ZHEE (Female)-এর উপযুক্ত ইনস্টল্ড প্যাক বাছাই করে
     */
    fun findBestInstalledPack(text: String, gender: VoiceGender): OfflineNeuralVoicePack? {
        val isBengali = text.any { it in '\u0980'..'\u09FF' }
        val preferredLang = if (isBengali) VoiceLang.BENGALI else VoiceLang.ENGLISH

        // ১. Exact Gender + Exact Language
        OfflineVoiceCatalog.packs.firstOrNull {
            it.gender == gender && it.language == preferredLang && OfflineVoiceCatalog.isInstalled(context, it.id)
        }?.let { return it }

        // ২. Same Language (অন্য জেন্ডার যদি ওটাই ডাউনলোড করা থাকে)
        OfflineVoiceCatalog.packs.firstOrNull {
            it.language == preferredLang && OfflineVoiceCatalog.isInstalled(context, it.id)
        }?.let { return it }

        // ৩. যেকোনো ইনস্টল্ড প্যাক (ইংরেজি/বাংলিশের জন্য)
        return OfflineVoiceCatalog.packs.firstOrNull {
            it.gender == gender && OfflineVoiceCatalog.isInstalled(context, it.id)
        } ?: OfflineVoiceCatalog.packs.firstOrNull {
            OfflineVoiceCatalog.isInstalled(context, it.id)
        }
    }

    /**
     * 100% Offline Human Voice দিয়ে বাক্য-বাই-বাক্য স্ট্রিম করে কথা বলবে।
     * কোনো প্যাক ইনস্টল না থাকলে false রিটার্ন করবে যাতে HD Local TTS ফলব্যাক নিতে পারে।
     */
    fun speakOfflineNeural(
        rawText: String,
        gender: VoiceGender
    ): Boolean {
        val cleanText = sanitizeForHumanSpeech(rawText)
        if (cleanText.isBlank()) return false

        val pack = findBestInstalledPack(cleanText, gender) ?: return false
        val files = OfflineVoiceCatalog.resolveInstalledFiles(context, pack.id) ?: return false

        stop()

        speakJob = scope.launch {
            var notifiedStart = false
            try {
                val tts = ensureEngineLoaded(pack, files)
                val sentences = splitIntoNaturalSentences(cleanText)
                if (sentences.isEmpty()) return@launch

                withContext(Dispatchers.Main) {
                    notifiedStart = true
                    onSpeechStart()
                }

                for ((index, sentence) in sentences.withIndex()) {
                    if (!isActive) break
                    val generated = tts.generate(
                        text = sentence,
                        sid = pack.defaultSpeakerId,
                        speed = pack.defaultSpeed
                    )
                    if (!isActive) break
                    val samples = generated.samples
                    if (samples != null && samples.isNotEmpty()) {
                        playFloatSamples(samples, generated.sampleRate)
                    }
                    // কবিতা/শায়েরি বা বাক্যের মাঝে মানুষের মতো স্বাভাবিক নিঃশ্বাসের বিরতি
                    if (index < sentences.lastIndex && isActive) {
                        delay(170L)
                    }
                }
            } catch (_: CancellationException) {
                // ইউজার STOP চেপেছে
            } catch (t: Throwable) {
                Log.e(TAG, "Offline neural synthesis error: ${t.message}", t)
            } finally {
                if (notifiedStart) {
                    withContext(Dispatchers.Main) { onSpeechDone() }
                }
            }
        }
        return true
    }

    private suspend fun ensureEngineLoaded(
        pack: OfflineNeuralVoicePack,
        files: InstalledVoiceFiles
    ): OfflineTts = engineMutex.withLock {
        if (activeTts != null && loadedPackId == pack.id) {
            return@withLock activeTts!!
        }
        releaseEngineInternal()

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = files.modelPath,
            lexicon = files.lexiconPath,
            tokens = files.tokensPath,
            dataDir = files.dataDirPath,
            dictDir = "",
            noiseScale = 0.667f,     // স্বাভাবিক মানুষের কণ্ঠের ভ্যারিয়েশন
            noiseScaleW = 0.8f,      // প্রাকৃতিক রিদম ও টোন
            lengthScale = 1.02f      // রোবটের মতো তাড়াহুড়ো না করে স্পষ্ট বলা
        )

        val modelConfig = OfflineTtsModelConfig().apply {
            vits = vitsConfig
            numThreads = 4          // 12GB ফোনে সুপার-ফাস্ট জেনারেশন
            debug = false
            provider = "cpu"
        }

        val config = OfflineTtsConfig().apply {
            model = modelConfig
            maxNumSentences = 1
        }

        val created = OfflineTts(assetManager = null, config = config)
        activeTts = created
        loadedPackId = pack.id
        created
    }

    /**
     * Zero-distortion 32-bit Float PCM playback (Android API 26+ Native)
     */
    private fun playFloatSamples(samples: FloatArray, sampleRate: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
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
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf)
            .build()

        activeTrack = track
        try {
            track.play()
            var offset = 0
            val chunk = 2048
            while (offset < samples.size && activeTrack === track) {
                val count = minOf(chunk, samples.size - offset)
                val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (activeTrack === track) {
                track.stop()
            }
        } finally {
            runCatching { track.release() }
            if (activeTrack === track) activeTrack = null
        }
    }

    private fun splitIntoNaturalSentences(text: String): List<String> {
        return text
            .split(Regex("""(?<=[।.!?])\s+|\n+"""))
            .map { it.trim() }
            .filter { it.length >= 2 }
    }

    private fun sanitizeForHumanSpeech(raw: String): String {
        return raw
            .replace(Regex("""\[\[TOOL:[^\]]+]]"""), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""[*_`#~>|]"""), "")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
            .replace(Regex("""[ \t]+"""), " ")
            .trim()
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        val track = activeTrack
        activeTrack = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
    }

    fun release() {
        stop()
        releaseEngineInternal()
    }

    private fun releaseEngineInternal() {
        runCatching { activeTts?.release() }
        activeTts = null
        loadedPackId = null
    }
}
