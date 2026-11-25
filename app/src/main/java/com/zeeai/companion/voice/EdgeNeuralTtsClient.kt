package com.zeeai.companion.voice

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Pure-Kotlin client for Microsoft Edge Neural Studio TTS over WebSocket.
 * Delivers genuine human neural voices (`bn-BD-PradeepNeural` Male, `bn-BD-NabanitaNeural` Female,
 * `en-US-AndrewMultilingualNeural` Male, `en-US-AvaMultilingualNeural` Female) with zero API keys
 * and completely eliminates Google's robotic TTS.
 */
object EdgeNeuralTtsClient {

    private const val TAG = "EdgeNeuralTts"
    private const val WIN_EPOCH_SECONDS = 11644473600L
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR_VERSION = "143"
    private const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    private val secureRandom = SecureRandom()

    @Volatile
    private var clockSkewSeconds: Long = 0L

    private val SHA256_INITIAL = uintArrayOf(
        0x6A09E667u, 0xBB67AE85u, 0x3C6EF372u, 0xA54FF53Au,
        0x510E527Fu, 0x9B05688Cu, 0x1F83D9ABu, 0x5BE0CD19u
    )

    private val SHA256_STATE = uintArrayOf(
        0xFD9FBEA2u, 0x91243CE7u, 0xBE499094u, 0xCD2E1E0Au,
        0x6C006F49u, 0x04BD35ABu, 0xFEB0A7E4u, 0x03EFFBD2u
    )

    private val SHA256_SCHEDULE = uintArrayOf(
        0xEB24BF9Eu, 0x1558691Fu, 0xA0C09EE2u, 0x0CFA32BAu,
        0xA622EE8Cu, 0xD1DD9491u, 0xC0DD25A0u, 0xF9E70AB6u,
        0xF0C31573u, 0x7058CE4Eu, 0x7188E606u, 0x00E5AB33u,
        0xD258B060u, 0x72766564u
    )

    private val SHA256_ROUND_CONSTANTS = uintArrayOf(
        0x428A2F98u, 0x71374491u, 0xB5C0FBCFu, 0xE9B5DBA5u, 0x3956C25Bu, 0x59F111F1u, 0x923F82A4u, 0xAB1C5ED5u,
        0xD807AA98u, 0x12835B01u, 0x243185BEu, 0x550C7DC3u, 0x72BE5D74u, 0x80DEB1FEu, 0x9BDC06A7u, 0xC19BF174u,
        0xE49B69C1u, 0xEFBE4786u, 0x0FC19DC6u, 0x240CA1CCu, 0x2DE92C6Fu, 0x4A7484AAu, 0x5CB0A9DCu, 0x76F988DAu,
        0x983E5152u, 0xA831C66Du, 0xB00327C8u, 0xBF597FC7u, 0xC6E00BF3u, 0xD5A79147u, 0x06CA6351u, 0x14292967u,
        0x27B70A85u, 0x2E1B2138u, 0x4D2C6DFCu, 0x53380D13u, 0x650A7354u, 0x766A0ABBu, 0x81C2C92Eu, 0x92722C85u,
        0xA2BFE8A1u, 0xA81A664Bu, 0xC24B8B70u, 0xC76C51A3u, 0xD192E819u, 0xD6990624u, 0xF40E3585u, 0x106AA070u,
        0x19A4C116u, 0x1E376C08u, 0x2748774Cu, 0x34B0BCB5u, 0x391C0CB3u, 0x4ED8AA4Au, 0x5B9CCA4Fu, 0x682E6FF3u,
        0x748F82EEu, 0x78A5636Fu, 0x84C87814u, 0x8CC70208u, 0x90BEFFFAu, 0xA4506CEBu, 0xBEF9A3F7u, 0xC67178F2u
    )

    private const val SHA256_ADJUSTMENT = 0xC9DC06A7u
    private val SHA256_BLOCK_CONSTANTS = UIntArray(64) { i ->
        if (i == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[i]
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80u
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0u

    private val CS64_COEFFS = arrayOf(
        0xE82E91B1u to 0xB32700D0u,
        0x44DE24C6u to 0x88DFF51Du,
        0x3BEB65BCu to 0x29514038u,
        0xC89C5933u to 0xB328CBDFu,
        0x8D1EC211u to 0xD405B4D5u,
        0x314EF5D5u to 0x26E55F3Cu,
        0xE92491B1u to 0x607801C6u,
        0xF0A5B9D5u to 0x82B2C1FBu,
        0x06064CF3u to 0xFA51A3DAu,
        0xDA79FA60u to 0x207CC464u,
        0xBED20560u to 0x3C3C74F3u,
        0x89F9C9F8u to 0xD2D14B61u,
        0x35FA38C6u to 0xBB04A2C1u,
        0xCB49E3C6u to 0xC5626167u
    )
    private const val CS64_FACTOR = 0x82893933u
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1Au
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000u

    private fun rol16(v: UInt): UInt = (v shl 16) or (v shr 16)

    private fun sigma0(v: UInt): UInt =
        (v shr 7) xor (v shl 25) xor (v shr 18) xor (v shl 14) xor (v shr 3)

    private fun sigma1(v: UInt): UInt =
        (v shr 17) xor (v shl 15) xor (v shr 19) xor (v shl 13) xor (v shr 10)

    private fun rotRight(v: UInt, b1: Int, b2: Int, b3: Int): UInt {
        val rep = v.toULong() * 0x100000001uL
        return ((rep shr b1) xor (rep shr b2) xor (rep shr b3)).toUInt()
    }

    private fun expandSchedule(words: UIntArray, firstWord: Int = 16) {
        for (i in firstWord until 64) {
            words[i] = sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]
        }
    }

    private fun sha256Rounds(init: UIntArray, words: UIntArray, firstRound: Int = 0): UIntArray {
        var a = init[0]
        var b = init[1]
        var c = init[2]
        var d = init[3]
        var e = init[4]
        var f = init[5]
        var g = init[6]
        var h = init[7]
        val constants = if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
        for (i in firstRound until 64) {
            val s1 = rotRight(e, 6, 11, 25)
            val ch = g xor (e and (f xor g))
            val t1 = h + s1 + ch + constants[i] + words[i]
            val s0 = rotRight(a, 2, 13, 22)
            val maj = (a and b) or (c and (a or b))
            val t2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        return uintArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun sha256Init(head: ByteArray): UIntArray {
        val words = UIntArray(64)
        words[14] = ((head[0].toUInt() and 0xFFu) shl 16) or
            ((head[1].toUInt() and 0xFFu) shl 8) or
            (head[2].toUInt() and 0xFFu)
        words[15] = ((head[3].toUInt() and 0xFFu) shl 24) or
            ((head[4].toUInt() and 0xFFu) shl 16) or
            ((head[5].toUInt() and 0xFFu) shl 8) or
            (head[6].toUInt() and 0xFFu)

        for (i in 16 until 30) {
            var v = SHA256_SCHEDULE[i - 16]
            if (i - 2 >= 14) {
                v += if (i == 16) sigma1(words[i - 2]) xor SHA256_SIGMA1_ADJUSTMENT else sigma1(words[i - 2])
            }
            if (i - 7 >= 14) v += words[i - 7]
            if (i - 15 >= 14) {
                v += if (i == 29) sigma0(words[i - 15]) xor SHA256_SIGMA0_ADJUSTMENT else sigma0(words[i - 15])
            }
            words[i] = v
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, 14)
        return UIntArray(8) { idx -> SHA256_INITIAL[idx] + working[idx] }
    }

    private fun tokenSha256Digest(data: ByteArray): ByteArray {
        val totalLen = 57L + data.size
        val tailLen = data.size - 7
        val zeroPad = ((55 - totalLen.toInt()) % 64 + 64) % 64
        val padded = ByteArray(tailLen + 1 + zeroPad + 8)
        System.arraycopy(data, 7, padded, 0, tailLen)
        padded[tailLen] = 0x80.toByte()
        ByteBuffer.wrap(padded, padded.size - 8, 8)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(totalLen * 8L)

        val state = sha256Init(data.copyOfRange(0, 7))
        val words = UIntArray(64)
        for (offset in padded.indices step 64) {
            for (w in 0 until 16) {
                val base = offset + w * 4
                words[w] = ((padded[base].toUInt() and 0xFFu) shl 24) or
                    ((padded[base + 1].toUInt() and 0xFFu) shl 16) or
                    ((padded[base + 2].toUInt() and 0xFFu) shl 8) or
                    (padded[base + 3].toUInt() and 0xFFu)
            }
            expandSchedule(words, 16)
            val working = sha256Rounds(state, words, 0)
            for (k in 0 until 8) state[k] += working[k]
        }
        val out = ByteArray(32)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        for (s in state) bb.putInt(s.toInt())
        return out
    }

    private fun wordsLe4Even(src: ByteArray): List<UInt> {
        val rem = ((-src.size) % 4 + 4) % 4
        val padded = src + ByteArray(rem)
        val list = ArrayList<UInt>(padded.size / 4 + 1)
        val bb = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        while (bb.hasRemaining()) {
            list.add(bb.int.toUInt())
        }
        if (list.size % 2 != 0) list.add(0u)
        return list
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<UInt, UInt> {
        val bb = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = bb.int.toUInt() or 1u
        val keyB = bb.int.toUInt() or 1u
        val factor0 = keyA * CS64_FACTOR
        val factor1 = keyB * CS64_FACTOR

        val terms = ArrayList<UInt>(CS64_COEFFS.size + 32)
        val offsets = ArrayList<UInt>(CS64_COEFFS.size + 32)
        for ((idx, pair) in CS64_COEFFS.withIndex()) {
            terms.add(pair.first * if (idx % 2 == 0) factor0 else factor1)
            offsets.add(pair.second)
        }

        val inputWords = wordsLe4Even(byteArrayOf(0) + data)
        for ((idx, word) in inputWords.withIndex()) {
            var term = word * if (idx % 2 == 0) keyA else keyB
            var offset = rol16(word) * if (idx % 2 == 0) 0xEF0569FBu else 0xC31713DBu
            if (idx == 0) {
                term += CS64_ADJUSTMENT0 * factor0
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }

        var swapAcc = 0u
        var swapLast = 0u
        var state = 0u
        var acc = 0u

        for (i in terms.indices step 2) {
            val x = terms[i]
            val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34u
            swapAcc += rol16(q) * 0xC3EFEA97u + q

            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3Cu
            swapLast = rol16(q) * 0x35BD1EC9u + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111u
            state = rol16(state) * 0x87085B9Fu
            state = rol16(state) * 0x12CEB96Du
            state = rol16(state) * 0x257E1D83u
            acc += state

            t = state * keyB + y
            t = rol16(t) * 0xA27416F5u
            t = rol16(t) * 0xD38396FFu
            state = rol16(rol16(t) * 0x7C932B89u) * 0xBFA49F69u
            acc += state
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    fun generateSecMsGecV2(unixSecondsOverride: Long? = null): String {
        val baseUnix = unixSecondsOverride ?: ((System.currentTimeMillis() / 1000L) + clockSkewSeconds)
        var aligned = baseUnix + WIN_EPOCH_SECONDS
        aligned -= aligned % 300L
        val ticks = aligned * 10_000_000L
        val rawStr = "$ticks$TRUSTED_CLIENT_TOKEN"
        val data = rawStr.toByteArray(Charsets.US_ASCII)
        val digest = tokenSha256Digest(data)
        val (c0, c1) = cs64Digest(data, digest)

        val cs64Bytes = ByteArray(8)
        ByteBuffer.wrap(cs64Bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(c0.toInt())
            putInt(c1.toInt())
        }
        val cs64Hex = cs64Bytes.joinToString("") { "%02X".format(Locale.US, it) }
        val shaHex = digest.joinToString("") { "%02X".format(Locale.US, it) }
        return "$cs64Hex-$shaHex"
    }

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(Locale.US, it) }
    }

    private fun escapeXml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    data class SelectedNeuralVoice(
        val langTag: String,
        val voiceName: String,
        val rate: String = "+4%"
    )

    fun pickVoiceForText(text: String, isFemale: Boolean): SelectedNeuralVoice {
        return when {
            text.any { it in '\u0980'..'\u09FF' } -> SelectedNeuralVoice(
                langTag = "bn-BD",
                voiceName = if (isFemale) "bn-BD-NabanitaNeural" else "bn-BD-PradeepNeural",
                rate = "+3%"
            )
            text.any { it in '\u0900'..'\u097F' } -> SelectedNeuralVoice(
                langTag = "hi-IN",
                voiceName = if (isFemale) "hi-IN-SwaraNeural" else "hi-IN-MadhurNeural",
                rate = "+2%"
            )
            text.any { it in '\u0600'..'\u06FF' } -> SelectedNeuralVoice(
                langTag = "ur-PK",
                voiceName = if (isFemale) "ur-PK-UzmaNeural" else "ur-PK-AsadNeural",
                rate = "+2%"
            )
            else -> SelectedNeuralVoice(
                langTag = "en-US",
                voiceName = if (isFemale) "en-US-AvaMultilingualNeural" else "en-US-AndrewMultilingualNeural",
                rate = "+2%"
            )
        }
    }

    suspend fun synthesizeMp3(
        client: OkHttpClient,
        text: String,
        isFemale: Boolean
    ): ByteArray? = withContext(Dispatchers.IO) {
        val clean = text.trim().take(1200)
        if (clean.isBlank()) return@withContext null

        val firstAttempt = executeSynthesis(client, clean, isFemale)
        if (firstAttempt != null) return@withContext firstAttempt

        // Retry once cleanly in case clock skew was just adjusted from 403 Date header
        executeSynthesis(client, clean, isFemale)
    }

    private suspend fun executeSynthesis(
        client: OkHttpClient,
        text: String,
        isFemale: Boolean
    ): ByteArray? {
        val connId = UUID.randomUUID().toString().replace("-", "")
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("speech.platform.bing.com")
            .encodedPath("/consumer/speech/synthesize/readaloud/edge/v1")
            .addQueryParameter("TrustedClientToken", TRUSTED_CLIENT_TOKEN)
            .addQueryParameter("Sec-MS-GEC", generateSecMsGecV2())
            .addQueryParameter("Sec-MS-GEC-Version", SEC_MS_GEC_VERSION)
            .addQueryParameter("ConnectionId", connId)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cookie", "muid=${generateMuid()};")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 " +
                    "Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"
            )
            .build()

        val chosen = pickVoiceForText(text, isFemale)
        val audioOut = ByteArrayOutputStream(16384)
        val done = CompletableDeferred<Boolean>()

        val wsClient = client.newBuilder()
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        val ws = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val ts = jsUtcTimestamp()
                val cfg = buildString {
                    append("X-Timestamp:").append(ts).append("\r\n")
                    append("Content-Type:application/json; charset=utf-8\r\n")
                    append("Path:speech.config\r\n\r\n")
                    append(
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceboundaryenabled":"false","wordboundaryenabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""
                    )
                }
                webSocket.send(cfg)

                val ssml = buildString {
                    append("X-RequestId:").append(connId).append("\r\n")
                    append("Content-Type:application/ssml+xml\r\n")
                    append("X-Timestamp:").append(ts).append("Z\r\n")
                    append("Path:ssml\r\n\r\n")
                    append("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${chosen.langTag}'>")
                    append("<voice name='${chosen.voiceName}'>")
                    append("<prosody pitch='+0Hz' rate='${chosen.rate}' volume='+0%'>")
                    append(escapeXml(text))
                    append("</prosody></voice></speak>")
                }
                webSocket.send(ssml)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, "Done")
                    if (!done.isCompleted) done.complete(true)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                if (raw.size <= 2) return
                val headerLen = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
                val dataOffset = 2 + headerLen
                if (dataOffset < raw.size) {
                    val headerText = raw.copyOfRange(2, dataOffset).decodeToString()
                    if (headerText.contains("Path:audio")) {
                        synchronized(audioOut) {
                            audioOut.write(raw, dataOffset, raw.size - dataOffset)
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                if (!done.isCompleted) done.complete(audioOut.size() > 256)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    val serverDate = response.header("Date")
                    if (!serverDate.isNullOrBlank()) {
                        adjustClockSkewFromHttpDate(serverDate)
                    }
                }
                Log.w(TAG, "Edge Neural WSS error (${response?.code}): ${t.message}")
                if (!done.isCompleted) done.complete(false)
            }
        })

        val ok = withTimeoutOrNull(12_000L) { done.await() } ?: false
        if (!ok) {
            runCatching { ws.cancel() }
            return null
        }
        val resultBytes = synchronized(audioOut) { audioOut.toByteArray() }
        return resultBytes.takeIf { it.size > 256 }
    }

    private fun adjustClockSkewFromHttpDate(httpDate: String) {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val parsedMs = runCatching { fmt.parse(httpDate)?.time }.getOrNull() ?: return
        val serverSec = parsedMs / 1000L
        val localSec = System.currentTimeMillis() / 1000L
        clockSkewSeconds = serverSec - localSec
    }

    private fun jsUtcTimestamp(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date((System.currentTimeMillis() + clockSkewSeconds * 1000L)))
    }
}
