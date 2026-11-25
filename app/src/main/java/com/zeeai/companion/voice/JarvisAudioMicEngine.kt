package com.zeeai.companion.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.zeeai.companion.ai.GeminiNativeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pure Hardware Microphone + Voice Activity Detection (VAD) + Gemini Audio Transcription Engine.
 * Runs 100% natively in-process using Android's AudioRecord without launching Google's Speech
 * Activity popup, Google assistant chimes, or OEM SpeechRecognizer IPC bindings.
 */
class JarvisAudioMicEngine(
    private val geminiClient: GeminiNativeClient
) {
    companion object {
        private const val TAG = "JarvisDirectMic"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SAMPLES = 640 // 40ms chunks at 16kHz
        private const val PRE_ROLL_FRAMES = 9 // ~360ms pre-roll before trigger
        private const val SILENCE_END_MS = 950L
        private const val MAX_UTTERANCE_MS = 12_000L
        private const val MIN_UTTERANCE_BYTES = 16000 * 2 / 3 // ~330ms minimum speech
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var running = false

    fun isRunning(): Boolean = running

    fun setPaused(pause: Boolean) {
        paused = pause
    }

    @SuppressLint("MissingPermission")
    fun startContinuousCapture(
        apiKeyProvider: () -> String,
        onRmsLevel: (Float) -> Unit,
        onStatus: (String) -> Unit,
        onHearingStart: () -> Unit,
        onTranscript: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stopCapture()
        running = true
        paused = false

        captureJob = scope.launch {
            val minBufBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 2 * 4)

            val recorder = createBestAudioRecord(minBufBytes)
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder?.release() }
                running = false
                withContext(Dispatchers.Main) {
                    onError("Microphone hardware busy.")
                }
                return@launch
            }

            try {
                recorder.startRecording()
                withContext(Dispatchers.Main) {
                    onStatus("JARVIS Direct Mic শুনছে... বলুন বস")
                }

                val readBuf = ShortArray(FRAME_SAMPLES)
                val preRoll = ArrayDeque<ByteArray>(PRE_ROLL_FRAMES + 2)
                var recordingStream: ByteArrayOutputStream? = null
                var noiseFloor = 180.0
                var calibrationFrames = 0
                var inSpeech = false
                var consecutiveVoiceFrames = 0
                var lastVoiceAtMs = 0L
                var speechStartMs = 0L

                while (isActive && running) {
                    if (paused) {
                        inSpeech = false
                        consecutiveVoiceFrames = 0
                        recordingStream = null
                        preRoll.clear()
                        withContext(Dispatchers.Main) { onRmsLevel(0f) }
                        delay(80L)
                        continue
                    }

                    val readCount = recorder.read(readBuf, 0, FRAME_SAMPLES)
                    if (readCount <= 0) {
                        delay(20L)
                        continue
                    }

                    var sumSq = 0.0
                    for (i in 0 until readCount) {
                        val s = readBuf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / readCount)
                    val normLevel = ((rms / 3500.0).toFloat()).coerceIn(0f, 1f)
                    onRmsLevel(normLevel)

                    val pcmChunk = shortsToLittleEndianBytes(readBuf, readCount)
                    val now = System.currentTimeMillis()

                    if (!inSpeech) {
                        if (calibrationFrames < 10) {
                            noiseFloor = (noiseFloor * calibrationFrames + rms) / (calibrationFrames + 1)
                            calibrationFrames++
                        } else if (rms < noiseFloor * 1.8) {
                            noiseFloor = noiseFloor * 0.94 + rms * 0.06
                        }

                        if (preRoll.size >= PRE_ROLL_FRAMES) preRoll.removeFirst()
                        preRoll.addLast(pcmChunk)

                        val threshold = maxOf(noiseFloor * 2.7, 340.0)
                        if (rms > threshold && calibrationFrames >= 5) {
                            consecutiveVoiceFrames++
                            if (consecutiveVoiceFrames >= 2) {
                                inSpeech = true
                                speechStartMs = now
                                lastVoiceAtMs = now
                                recordingStream = ByteArrayOutputStream(32768).apply {
                                    while (preRoll.isNotEmpty()) {
                                        write(preRoll.removeFirst())
                                    }
                                }
                                withContext(Dispatchers.Main) {
                                    onHearingStart()
                                    onStatus("কথা ধরা হচ্ছে...")
                                }
                            }
                        } else {
                            consecutiveVoiceFrames = 0
                        }
                    } else {
                        recordingStream?.write(pcmChunk)
                        val activeThresh = maxOf(noiseFloor * 2.0, 240.0)
                        if (rms > activeThresh) {
                            lastVoiceAtMs = now
                        }

                        val silenceElapsed = now - lastVoiceAtMs
                        val totalElapsed = now - speechStartMs
                        if (silenceElapsed >= SILENCE_END_MS || totalElapsed >= MAX_UTTERANCE_MS) {
                            val pcmBytes = recordingStream?.toByteArray() ?: ByteArray(0)
                            recordingStream = null
                            inSpeech = false
                            consecutiveVoiceFrames = 0
                            preRoll.clear()
                            onRmsLevel(0f)

                            if (pcmBytes.size >= MIN_UTTERANCE_BYTES) {
                                val key = apiKeyProvider().trim()
                                if (key.isNotBlank()) {
                                    withContext(Dispatchers.Main) { onStatus("বোঝার চেষ্টা করছি...") }
                                    val wav = wrapPcm16AsWav(pcmBytes, SAMPLE_RATE)
                                    val txRes = geminiClient.transcribeAudioWav(key, wav)
                                    val transcript = txRes.getOrNull().orEmpty().trim()
                                    if (transcript.isNotBlank() && !transcript.equals("[SILENCE]", ignoreCase = true)) {
                                        withContext(Dispatchers.Main) {
                                            onTranscript(transcript)
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            onStatus("JARVIS Direct Mic শুনছে... বলুন বস")
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        onError("Direct Audio Mic-এর জন্য Settings-এ Gemini API Key দিন।")
                                    }
                                    delay(1500L)
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    onStatus("JARVIS Direct Mic শুনছে... বলুন বস")
                                }
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                // Clean cancellation
            } catch (t: Throwable) {
                Log.w(TAG, "Direct Audio mic loop error: ${t.message}")
            } finally {
                running = false
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                onRmsLevel(0f)
            }
        }
    }

    fun stopCapture() {
        running = false
        paused = false
        val job = captureJob
        captureJob = null
        if (job != null) {
            scope.launch { runCatching { job.cancelAndJoin() } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createBestAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (src in sources) {
            val rec = runCatching {
                AudioRecord(
                    src,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull()
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec
            runCatching { rec?.release() }
        }
        return null
    }

    private fun shortsToLittleEndianBytes(shorts: ShortArray, count: Int): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = shorts[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun wrapPcm16AsWav(pcmBytes: ByteArray, sampleRate: Int): ByteArray {
        val totalAudioLen = pcmBytes.size
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(44 + totalAudioLen).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(totalDataLen)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(totalAudioLen)
        header.put(pcmBytes)
        return header.array()
    }
}
