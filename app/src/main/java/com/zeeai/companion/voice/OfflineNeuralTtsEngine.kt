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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class OfflineNeuralTtsEngine(
    private val context: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechDone: () -> Unit = {}
) {
    companion object {
        private const val TAG = "ZeeOfflineNeuralTTS"
    }

    private val engineMutex = Mutex()

    private var activeTts: OfflineTts? = null
    private var loadedPackId: String? = null

    @Volatile
    private var activeTrack: AudioTrack? = null

    @Volatile
    private var isStopped = false

    fun findBestInstalledPack(text: String, gender: VoiceGender): OfflineNeuralVoicePack? {
        val isBengali = text.any { it in '\u0980'..'\u09FF' }
        val preferredLang = if (isBengali) VoiceLang.BENGALI else VoiceLang.ENGLISH

        OfflineVoiceCatalog.packs.firstOrNull {
            it.gender == gender && it.language == preferredLang && OfflineVoiceCatalog.isInstalled(context, it.id)
        }?.let { return it }

        OfflineVoiceCatalog.packs.firstOrNull {
            it.language == preferredLang && OfflineVoiceCatalog.isInstalled(context, it.id)
        }?.let { return it }

        return OfflineVoiceCatalog.packs.firstOrNull {
            it.gender == gender && OfflineVoiceCatalog.isInstalled(context, it.id)
        } ?: OfflineVoiceCatalog.packs.firstOrNull {
            OfflineVoiceCatalog.isInstalled(context, it.id)
        }
    }

    /**
     * Suspends through actual offline neural synthesis & PCM playback.
     * Returns true ONLY if neural samples were actually synthesized and played,
     * allowing callers to cleanly fall back to System HD TTS if false.
     */
    suspend fun speakOfflineNeural(
        rawText: String,
        gender: VoiceGender,
        notifyLifecycle: Boolean = false
    ): Boolean = withContext(Dispatchers.Default) {
        val cleanText = sanitizeForHumanSpeech(rawText)
        if (cleanText.isBlank()) return@withContext false

        val pack = findBestInstalledPack(cleanText, gender) ?: return@withContext false
        val files = OfflineVoiceCatalog.resolveInstalledFiles(context, pack.id) ?: return@withContext false

        stopCurrentTrack()
        isStopped = false

        var notifiedStart = false
        var anyPlayed = false

        try {
            val tts = ensureEngineLoaded(pack, files)
            val sentences = splitIntoNaturalSentences(cleanText)
            if (sentences.isEmpty()) return@withContext false

            for ((index, sentence) in sentences.withIndex()) {
                if (!currentCoroutineContext().isActive || isStopped) break
                val generated = engineMutex.withLock {
                    if (activeTts !== tts || isStopped) null
                    else tts.generate(
                        text = sentence,
                        sid = pack.defaultSpeakerId,
                        speed = pack.defaultSpeed
                    )
                } ?: break

                if (!currentCoroutineContext().isActive || isStopped) break
                val samples = generated.samples
                if (samples != null && samples.isNotEmpty()) {
                    if (notifyLifecycle && !notifiedStart) {
                        notifiedStart = true
                        withContext(Dispatchers.Main) { onSpeechStart() }
                    }
                    playFloatSamples(samples, generated.sampleRate)
                    anyPlayed = true
                }

                if (index < sentences.lastIndex && currentCoroutineContext().isActive && !isStopped) {
                    delay(150L)
                }
            }
            anyPlayed
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Offline neural synthesis error: ${t.message}", t)
            false
        } finally {
            if (notifyLifecycle && notifiedStart) {
                withContext(Dispatchers.Main) { onSpeechDone() }
            }
        }
    }

    private suspend fun ensureEngineLoaded(
        pack: OfflineNeuralVoicePack,
        files: InstalledVoiceFiles
    ): OfflineTts = engineMutex.withLock {
        if (activeTts != null && loadedPackId == pack.id) {
            return@withLock activeTts!!
        }
        releaseEngineLocked()

        val vitsConfig = OfflineTtsVitsModelConfig(
            model = files.modelPath,
            lexicon = files.lexiconPath,
            tokens = files.tokensPath,
            dataDir = files.dataDirPath,
            dictDir = "",
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.02f
        )

        val modelConfig = OfflineTtsModelConfig().apply {
            vits = vitsConfig
            numThreads = 4
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
            while (offset < samples.size && activeTrack === track && !isStopped) {
                val count = minOf(chunk, samples.size - offset)
                val written = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            if (activeTrack === track) {
                runCatching { track.stop() }
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
            .filter { it.isNotEmpty() }
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

    private fun stopCurrentTrack() {
        val track = activeTrack
        activeTrack = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
    }

    fun stop() {
        isStopped = true
        stopCurrentTrack()
    }

    fun release() {
        stop()
        if (engineMutex.tryLock()) {
            try {
                releaseEngineLocked()
            } finally {
                engineMutex.unlock()
            }
        }
    }

    private fun releaseEngineLocked() {
        runCatching { activeTts?.release() }
        activeTts = null
        loadedPackId = null
    }
}
