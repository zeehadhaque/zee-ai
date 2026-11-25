package com.zeeai.companion.jarvis

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.automation.OfflineAutonomousPhoneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class BrainDumpTaskItem(
    val type: String,
    val detail: String,
    val result: String,
    val isSuccess: Boolean = true
)

data class BrainDumpExecutionResult(
    val items: List<BrainDumpTaskItem>,
    val spokenSummary: String,
    val elapsedMs: Long
)

data class AirspaceTarget(
    val callsign: String,
    val origin: String,
    val altitudeM: Int,
    val velocityKmh: Int,
    val headingDeg: Int,
    val isIss: Boolean = false
)

data class AirspaceRadarSnapshot(
    val targets: List<AirspaceTarget>,
    val totalTracked: Int,
    val sourceLabel: String,
    val timestampStr: String
)

data class NetworkHostAudit(
    val ip: String,
    val mac: String,
    val openPorts: List<Int>,
    val riskLabel: String,
    val serviceHint: String
)

data class NetworkAuditSnapshot(
    val localIp: String,
    val gatewayIp: String,
    val securityScore: Int,
    val hosts: List<NetworkHostAudit>,
    val vulnerabilities: List<String>,
    val summary: String
)

data class OpticalSolveResult(
    val mode: String,
    val solution: String,
    val confidencePct: Int,
    val elapsedMs: Long,
    val usedVision: Boolean
)

data class PolyglotLanguage(
    val name: String,
    val nativeLabel: String,
    val ttsLocaleTag: String
)

data class PolyglotTranslationResult(
    val sourceLang: String,
    val targetLang: String,
    val translatedText: String,
    val ttsLocaleTag: String
)

data class ProactiveSuggestionEvent(
    val id: String,
    val category: String,
    val title: String,
    val message: String,
    val spokenPrompt: String,
    val actionCommand: String? = null
)

/**
 * Unified Stark Superpowers Engine:
 * 1. Brain Dump Parallel Multi-Tasker
 * 2. Real-Time Orbital & Airspace Radar (ISS + OpenSky ADS-B)
 * 3. Tactical Network Security Auditor (LAN + Socket Port Probe)
 * 4. Optical AR Problem Solver (Camera/Image/Math/Code/Circuits)
 * 5. Polyglot Diplomat Translator (15 Languages + TTS)
 * 6. Home Assistant REST Smart Home Controller
 * 7. Proactive Autonomous Agent Monitor
 */
class StarkSuperpowersEngine(
    private val context: Context,
    private val prefsStore: JarvisPreferences,
    private val store: ProductivityAndMemoryStore,
    private val phoneController: OfflineAutonomousPhoneController,
    private val geminiClient: GeminiNativeClient
) {
    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val supportedLanguages = listOf(
        PolyglotLanguage("Bengali", "বাংলা", "bn-BD"),
        PolyglotLanguage("English", "English", "en-US"),
        PolyglotLanguage("Hindi", "हिन्दी", "hi-IN"),
        PolyglotLanguage("Arabic", "العربية", "ar-SA"),
        PolyglotLanguage("Spanish", "Español", "es-ES"),
        PolyglotLanguage("French", "Français", "fr-FR"),
        PolyglotLanguage("Japanese", "日本語", "ja-JP"),
        PolyglotLanguage("German", "Deutsch", "de-DE"),
        PolyglotLanguage("Korean", "한국어", "ko-KR"),
        PolyglotLanguage("Italian", "Italiano", "it-IT"),
        PolyglotLanguage("Portuguese", "Português", "pt-BR"),
        PolyglotLanguage("Russian", "Русский", "ru-RU"),
        PolyglotLanguage("Chinese", "中文", "zh-CN"),
        PolyglotLanguage("Turkish", "Türkçe", "tr-TR"),
        PolyglotLanguage("Urdu", "اردو", "ur-PK")
    )

    // -------------------------------------------------------------------------
    // 1. MULTI-TASK BRAIN DUMP PARALLEL DECOUPLER
    // -------------------------------------------------------------------------
    suspend fun executeParallelBrainDump(
        rawDump: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        presetId: String
    ): BrainDumpExecutionResult = coroutineScope {
        val startMs = System.currentTimeMillis()
        val clean = rawDump.trim()
        if (clean.isEmpty()) {
            return@coroutineScope BrainDumpExecutionResult(
                items = emptyList(),
                spokenSummary = "বস, কোনো মাল্টি-টাস্ক কমান্ড পাওয়া যায়নি।",
                elapsedMs = 0L
            )
        }

        val plannedClauses: List<Pair<String, String>> = parseBrainDumpWithAiOrRules(
            rawDump = clean,
            apiKey = apiKey,
            baseUrl = baseUrl,
            model = model,
            presetId = presetId
        )

        val executed = plannedClauses.map { (type, detail) ->
            async(Dispatchers.IO) {
                executeSingleDecoupledTask(type, detail, apiKey, baseUrl, model, presetId)
            }
        }.awaitAll()

        val successCount = executed.count { it.isSuccess }
        val elapsed = System.currentTimeMillis() - startMs
        val summary = "ব্রেইন ডাম্প সম্পন্ন বস! $successCount/${executed.size}টি কাজ একসাথে ${elapsed}ms-এ শেষ করেছি।"

        store.recordSession(
            goal = "Multi-Task: ${clean.take(60)}",
            status = if (successCount > 0) SessionTaskStatus.SUCCESS else SessionTaskStatus.FAILED,
            summary = summary,
            steps = executed.map { "[${it.type}] ${it.detail} -> ${it.result}" }
        )

        BrainDumpExecutionResult(
            items = executed,
            spokenSummary = summary,
            elapsedMs = elapsed
        )
    }

    private suspend fun parseBrainDumpWithAiOrRules(
        rawDump: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        presetId: String
    ): List<Pair<String, String>> {
        if (apiKey.isNotBlank()) {
            val sysPrompt = """
                Decompose this chaotic brain-dump into atomic tasks as a pure JSON array of objects:
                [{"type":"todo|reminder|note|device|calc|draft","detail":"..."}]
                Use "device" for phone actions (wifi/torch/volume/open app/alarm), "reminder" for scheduled reminders, "note" for facts/knowledge, "todo" for action items, "calc" for math, "draft" for messages/emails.
                Output ONLY valid JSON array, no markdown fences.
            """.trimIndent()
            val aiRes = runProviderChat(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                presetId = presetId,
                systemInstruction = sysPrompt,
                userPrompt = rawDump
            )
            val parsed = runCatching {
                val rawJson = aiRes.getOrNull().orEmpty().trim()
                    .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val arr = JSONArray(rawJson)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val t = o.optString("type", "todo").lowercase()
                    val d = o.optString("detail").trim()
                    if (d.isEmpty()) null else t to d
                }
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }

        // Fast deterministic multilingual splitter (EN / Bengali / Banglish)
        val segments = rawDump
            .split(Regex("""(?i)\s*(?:,|;|\band then\b|\band\b|\bthen\b|\balso\b|এবং|আর|তারপর)\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(rawDump) }

        return segments.map { seg ->
            val lower = seg.lowercase()
            when {
                lower.contains("remind") || lower.contains("meeting") || lower.contains("calendar") || lower.contains("মনে কর") ->
                    "reminder" to seg
                lower.contains("note") || lower.contains("save") || lower.contains("remember") || lower.contains("লিখে রাখ") ->
                    "note" to seg
                lower.contains("flashlight") || lower.contains("torch") || lower.contains("wifi") || lower.contains("volume") ||
                    lower.contains("open ") || lower.contains("টর্চ") || lower.contains("খোল") || lower.contains("লাইট") ->
                    "device" to seg
                Regex("""\d+\s*[+\-*/%]\s*\d+""").containsMatchIn(lower) || lower.contains("calculate") || lower.contains("হিসাব") ->
                    "calc" to seg
                lower.contains("draft") || lower.contains("email") || lower.contains("write a") ->
                    "draft" to seg
                else -> "todo" to seg
            }
        }
    }

    private suspend fun executeSingleDecoupledTask(
        type: String,
        detail: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        presetId: String
    ): BrainDumpTaskItem {
        return when (type) {
            "todo" -> {
                val cleanTitle = detail.replace(Regex("(?i)^(add todo|todo|task|টুডু যোগ কর)\\s*[:\\-]?\\s*"), "").trim()
                store.addTodo(cleanTitle.ifEmpty { detail }, TodoPriority.HIGH)
                BrainDumpTaskItem("TODO", cleanTitle.ifEmpty { detail }, "Added to Tasks Matrix", true)
            }
            "reminder" -> {
                val cleanTitle = detail.replace(Regex("(?i)^(remind me to|remind me|reminder|মনে করিয়ে দাও)\\s*"), "").trim()
                store.addCalendarReminder(cleanTitle.ifEmpty { detail })
                BrainDumpTaskItem("REMINDER", cleanTitle.ifEmpty { detail }, "Scheduled in Calendar", true)
            }
            "note" -> {
                val cleanBody = detail.replace(Regex("(?i)^(note|remember|save note|নোট)\\s*[:\\-]?\\s*"), "").trim()
                store.addNote(cleanBody.take(28), cleanBody)
                BrainDumpTaskItem("NOTE", cleanBody.take(36), "Archived in Knowledge Vault", true)
            }
            "device" -> {
                val out = phoneController.tryInstantOfflineCommand(detail)
                if (out.handled) {
                    BrainDumpTaskItem("HARDWARE", detail, out.reply, true)
                } else {
                    BrainDumpTaskItem("HARDWARE", detail, "Command routed via controller", true)
                }
            }
            "calc" -> {
                val ans = evaluateSimpleMath(detail)
                BrainDumpTaskItem("MATH CORE", detail, ans ?: "Evaluated", ans != null)
            }
            "draft" -> {
                if (apiKey.isNotBlank()) {
                    val res = runProviderChat(
                        apiKey, baseUrl, model, presetId,
                        "Write a concise polished executive draft for Boss in 3-5 lines.",
                        detail
                    )
                    val text = res.getOrNull()?.take(180) ?: "Draft saved to Notes."
                    store.addNote("Draft: ${detail.take(22)}", res.getOrNull() ?: detail)
                    BrainDumpTaskItem("DRAFT", detail, text, true)
                } else {
                    store.addNote("Draft Idea", detail)
                    BrainDumpTaskItem("DRAFT", detail, "Saved outline to Notes Vault", true)
                }
            }
            else -> {
                store.addTodo(detail, TodoPriority.MEDIUM)
                BrainDumpTaskItem("TASK", detail, "Queued cleanly", true)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. REAL-TIME ORBITAL & AIRSPACE RADAR (ISS + OpenSky)
    // -------------------------------------------------------------------------
    suspend fun scanLocalAirspace(lat: Double = 23.8103, lon: Double = 90.4125): AirspaceRadarSnapshot =
        withContext(Dispatchers.IO) {
            val targets = mutableListOf<AirspaceTarget>()
            var sourceLabel = "OPENSKY + ISS LIVE"

            // Orbital ISS Telemetry
            runCatching {
                val req = Request.Builder().url("http://api.open-notify.org/iss-now.json").get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val pos = JSONObject(resp.body?.string().orEmpty()).optJSONObject("iss_position")
                        val iLat = pos?.optString("latitude")?.take(6) ?: "?"
                        val iLon = pos?.optString("longitude")?.take(6) ?: "?"
                        targets.add(
                            AirspaceTarget(
                                callsign = "ISS (ZARYA ORBITAL)",
                                origin = "LEO $iLat°, $iLon°",
                                altitudeM = 408_000,
                                velocityKmh = 27_600,
                                headingDeg = 51,
                                isIss = true
                            )
                        )
                    }
                }
            }

            // OpenSky ADS-B Aircraft Transponders within ~2.5 deg sector
            runCatching {
                val url = "https://opensky-network.org/api/states/all?lamin=${lat - 1.8}&lomin=${lon - 1.8}&lamax=${lat + 1.8}&lomax=${lon + 1.8}"
                val req = Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val states = JSONObject(resp.body?.string().orEmpty()).optJSONArray("states")
                        if (states != null) {
                            for (i in 0 until minOf(12, states.length())) {
                                val row = states.optJSONArray(i) ?: continue
                                val cs = row.optString(1).trim().ifEmpty { "UNKN-${100 + i}" }
                                val origin = row.optString(2).trim().ifEmpty { "INTL" }
                                val alt = row.optDouble(7, 9800.0).roundToInt().coerceAtLeast(300)
                                val vel = (row.optDouble(9, 220.0) * 3.6).roundToInt().coerceAtLeast(180)
                                val hdg = row.optDouble(10, 90.0).roundToInt()
                                targets.add(AirspaceTarget(cs, origin, alt, vel, hdg, false))
                            }
                        }
                    }
                }
            }

            if (targets.isEmpty()) {
                sourceLabel = "SIMULATED RADAR TELEMETRY (OFFLINE)"
                targets.addAll(
                    listOf(
                        AirspaceTarget("ISS (ORBITAL)", "LEO 23.7N 90.4E", 408_000, 27_600, 52, true),
                        AirspaceTarget("BG0084 (BIMAN)", "Bangladesh", 10_650, 864, 312, false),
                        AirspaceTarget("EK0585 (EMIRATES)", "UAE", 11_800, 912, 285, false),
                        AirspaceTarget("QR0639 (QATAR)", "Qatar", 11_200, 885, 290, false)
                    )
                )
            }

            val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            AirspaceRadarSnapshot(targets, targets.size, sourceLabel, ts)
        }

    // -------------------------------------------------------------------------
    // 3. TACTICAL NETWORK SECURITY AUDITOR
    // -------------------------------------------------------------------------
    suspend fun auditLocalNetwork(): NetworkAuditSnapshot = withContext(Dispatchers.IO) {
        val localIp = findLocalIpv4Address()
        val subnetPrefix = localIp.substringBeforeLast(".", "192.168.1")
        val gatewayIp = "$subnetPrefix.1"

        val arpMap = readProcNetArp()
        val probeIps = linkedSetOf(gatewayIp, "$subnetPrefix.2", "$subnetPrefix.10", "$subnetPrefix.100", "$subnetPrefix.254", localIp)
        arpMap.keys.take(10).forEach { probeIps.add(it) }

        val portsToCheck = listOf(21, 22, 23, 80, 443, 554, 8080, 8123)
        val hosts = probeIps.filter { it != "0.0.0.0" }.map { ip ->
            async {
                val open = mutableListOf<Int>()
                for (p in portsToCheck) {
                    if (isPortOpen(ip, p, 220)) open.add(p)
                }
                val mac = arpMap[ip] ?: if (ip == localIp) "LOCAL-NODE" else "DYNAMIC-HOST"
                val hasTelnet = 23 in open
                val hasFtp = 21 in open
                val hasRtsp = 554 in open
                val risk = when {
                    hasTelnet || hasFtp -> "HIGH RISK"
                    hasRtsp || (80 in open && 443 !in open) -> "MEDIUM"
                    else -> "SECURE"
                }
                val hint = when {
                    ip == gatewayIp -> "Gateway / Router"
                    ip == localIp -> "Boss Device (Self)"
                    8123 in open -> "Home Assistant Node"
                    554 in open -> "IP Camera / Stream"
                    22 in open -> "SSH Server / Workstation"
                    else -> "Network Endpoint"
                }
                if (open.isNotEmpty() || ip == gatewayIp || ip == localIp || arpMap.containsKey(ip)) {
                    NetworkHostAudit(ip, mac, open, risk, hint)
                } else null
            }
        }.awaitAll().filterNotNull()

        val vulns = mutableListOf<String>()
        var score = 98
        hosts.forEach { h ->
            if (23 in h.openPorts) {
                vulns.add("${h.ip}: Unencrypted Telnet (23) exposed")
                score -= 22
            }
            if (21 in h.openPorts) {
                vulns.add("${h.ip}: Legacy FTP (21) exposed")
                score -= 14
            }
            if (554 in h.openPorts) {
                vulns.add("${h.ip}: RTSP camera port (554) open on LAN")
                score -= 8
            }
            if (80 in h.openPorts && 443 !in h.openPorts) {
                vulns.add("${h.ip}: Plaintext HTTP (80) admin console without TLS")
                score -= 6
            }
        }
        val finalScore = score.coerceIn(25, 100)
        val summary = "নেটওয়ার্ক অডিট সম্পন্ন বস: ${hosts.size}টি নোড স্ক্যান হয়েছে, সিকিউরিটি স্কোর $finalScore/100।"
        NetworkAuditSnapshot(localIp, gatewayIp, finalScore, hosts, vulns, summary)
    }

    private fun findLocalIpv4Address(): String {
        runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        }
        return "192.168.1.10"
    }

    private fun readProcNetArp(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        runCatching {
            val f = File("/proc/net/arp")
            if (!f.exists() || !f.canRead()) return result
            f.readLines().drop(1).forEach { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size >= 4 && cols[3] != "00:00:00:00:00:00") {
                    result[cols[0]] = cols[3]
                }
            }
        }
        return result
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return runCatching {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------------------
    // 4. OPTICAL AR PROBLEM SOLVER
    // -------------------------------------------------------------------------
    suspend fun solveOpticalProblem(
        mode: String,
        query: String,
        imageUri: Uri?,
        apiKey: String,
        baseUrl: String,
        model: String,
        presetId: String
    ): OpticalSolveResult {
        val t0 = System.currentTimeMillis()
        val promptPrefix = when (mode) {
            "Math" -> "Solve step-by-step clearly as ZEE AI for Boss (বস). Show equations & final answer."
            "Code" -> "Audit, debug, and optimize this code for Boss (বস). Point out bugs cleanly."
            "Bills & Receipts" -> "Extract merchant, date, line items, taxes, total, and split summary for Boss."
            "Hardware & Circuits" -> "Identify components, pinouts, resistor/voltage math, or wiring risks for Boss."
            else -> "Analyze and solve this visual/technical query cleanly for Boss (বস)."
        }
        val finalPrompt = buildString {
            append(promptPrefix)
            if (query.isNotBlank()) append("\n\nBoss's note/question: $query")
        }

        if (imageUri != null && apiKey.isNotBlank() && presetId == "gemini") {
            val mime = appContext.contentResolver.getType(imageUri) ?: "image/jpeg"
            val sizeBytes = queryContentLength(imageUri)
            var streamed = ""
            val res = geminiClient.streamMultimodal(
                apiKey = apiKey,
                contentResolver = appContext.contentResolver,
                fileUri = imageUri,
                mimeType = mime,
                fileSizeBytes = sizeBytes,
                model = model.ifBlank { GeminiNativeClient.DEFAULT_FLASH_MODEL },
                systemInstruction = "You are ZEE AI Optical AR Solver. Always address the user respectfully as 'বস' (Boss).",
                prompt = finalPrompt,
                onChunk = { streamed = it }
            )
            if (res.isSuccess) {
                return OpticalSolveResult(
                    mode = mode,
                    solution = res.getOrNull().orEmpty().ifBlank { streamed },
                    confidencePct = 97,
                    elapsedMs = System.currentTimeMillis() - t0,
                    usedVision = true
                )
            }
        }

        if (apiKey.isNotBlank()) {
            val aiRes = runProviderChat(
                apiKey = apiKey,
                baseUrl = baseUrl,
                model = model,
                presetId = presetId,
                systemInstruction = "You are ZEE AI Optical Solver. Address user as 'বস' (Boss).",
                userPrompt = finalPrompt
            )
            if (aiRes.isSuccess) {
                return OpticalSolveResult(
                    mode = mode,
                    solution = aiRes.getOrNull().orEmpty(),
                    confidencePct = 94,
                    elapsedMs = System.currentTimeMillis() - t0,
                    usedVision = false
                )
            }
        }

        val mathFallback = evaluateSimpleMath(query)
        val fallbackText = mathFallback
            ?: "বস, ভিশন অ্যানালাইসিসের জন্য ইন্টারনেট বা Gemini API Key নিশ্চিত করুন। লোকাল ম্যাথ ইঞ্জিন শুধু সরাসরি সমীকরণ সমাধান করতে পারে।"
        return OpticalSolveResult(
            mode = mode,
            solution = fallbackText,
            confidencePct = if (mathFallback != null) 99 else 45,
            elapsedMs = System.currentTimeMillis() - t0,
            usedVision = false
        )
    }

    private fun queryContentLength(uri: Uri): Long {
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) return@use c.getLong(idx)
                }
                1024L * 256L
            } ?: (1024L * 256L)
        }.getOrDefault(1024L * 256L)
    }

    // -------------------------------------------------------------------------
    // 5. POLYGLOT DIPLOMAT TRANSLATOR
    // -------------------------------------------------------------------------
    suspend fun translatePolyglot(
        text: String,
        sourceLang: String,
        targetLang: String,
        apiKey: String,
        baseUrl: String,
        model: String,
        presetId: String
    ): PolyglotTranslationResult {
        val targetInfo = supportedLanguages.firstOrNull { it.name == targetLang } ?: supportedLanguages.first()
        if (text.isBlank()) {
            return PolyglotTranslationResult(sourceLang, targetLang, "", targetInfo.ttsLocaleTag)
        }
        if (apiKey.isBlank()) {
            return PolyglotTranslationResult(
                sourceLang,
                targetLang,
                "[$targetLang Offline Preview]: $text (Add an API key in Settings for neural live translation, Boss!)",
                targetInfo.ttsLocaleTag
            )
        }
        val sysPrompt = "Translate accurately and naturally from $sourceLang to $targetLang. Preserve tone & emotion. Output ONLY the translated text."
        val res = runProviderChat(apiKey, baseUrl, model, presetId, sysPrompt, text)
        return PolyglotTranslationResult(
            sourceLang = sourceLang,
            targetLang = targetLang,
            translatedText = res.getOrElse { "Translation unavailable offline: ${it.message}" },
            ttsLocaleTag = targetInfo.ttsLocaleTag
        )
    }

    // -------------------------------------------------------------------------
    // 6. SMART HOME (HOME ASSISTANT REST API)
    // -------------------------------------------------------------------------
    suspend fun executeSmartHomeCommand(rawCommand: String): String = withContext(Dispatchers.IO) {
        val snap = prefsStore.state.value
        val baseUrl = snap.homeAssistantUrl.trim().trimEnd('/')
        val token = snap.homeAssistantToken.trim()
        val entity = snap.smartHomeDefaultEntity.trim().ifEmpty { "light.living_room" }

        if (!snap.smartHomeEnabled) {
            return@withContext "স্মার্ট হোম কন্ট্রোল বন্ধ আছে বস। JARVIS Settings -> Smart Home থেকে চালু করুন।"
        }
        if (baseUrl.isBlank() || token.isBlank()) {
            return@withContext "বস, Home Assistant URL এবং Long-Lived Token সেট করা নেই।"
        }

        val lower = rawCommand.lowercase()
        val domain = entity.substringBefore(".", "light")
        val service = when {
            lower.contains("off") || lower.contains("বন্ধ") -> "turn_off"
            lower.contains("on") || lower.contains("চালু") || lower.contains("জ্বালা") -> "turn_on"
            else -> "toggle"
        }

        val endpoint = "$baseUrl/api/services/$domain/$service"
        val body = JSONObject().put("entity_id", entity).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    "স্মার্ট হোম আপডেট হয়েছে বস: $entity -> $service সম্পন্ন।"
                } else {
                    "Home Assistant রেসপন্স এরর (${resp.code}) বস।"
                }
            }
        }.getOrElse {
            "Home Assistant কানেকশন ব্যর্থ বস: ${it.message}"
        }
    }

    // -------------------------------------------------------------------------
    // 7. PROACTIVE AGENT MONITOR + TEST TRIGGERS
    // -------------------------------------------------------------------------
    private val _activeSuggestion = MutableStateFlow<ProactiveSuggestionEvent?>(null)
    val activeSuggestion: StateFlow<ProactiveSuggestionEvent?> = _activeSuggestion.asStateFlow()

    private var monitorJob: Job? = null
    private val firedKeys = mutableSetOf<String>()

    fun startProactiveMonitor(
        scope: CoroutineScope,
        sensors: HardwareSensorsController,
        onSpeakSuggestion: (ProactiveSuggestionEvent) -> Unit
    ) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(45_000L)
                evaluateProactiveConditions(sensors)?.let { evt ->
                    if (firedKeys.add(evt.id)) {
                        _activeSuggestion.value = evt
                        onSpeakSuggestion(evt)
                    }
                }
            }
        }
    }

    fun dismissSuggestion() {
        _activeSuggestion.value = null
    }

    fun triggerTestSuggestion(category: String, onEmit: (ProactiveSuggestionEvent) -> Unit) {
        val evt = when (category.lowercase()) {
            "night" -> ProactiveSuggestionEvent(
                id = "test_night_${System.currentTimeMillis()}",
                category = "Night Sleep",
                title = "Night Protocol Standby",
                message = "Late evening detected. Dim acoustics and arm Sentry Watchdog?",
                spokenPrompt = "বস, রাত হয়ে গেছে। আমি কি নাইট প্রোটোকল ও সেন্ট্রি ওয়াচডগ চালু করে দেব?",
                actionCommand = "night mode"
            )
            "study" -> ProactiveSuggestionEvent(
                id = "test_study_${System.currentTimeMillis()}",
                category = "Study Focus",
                title = "Deep Study Focus",
                message = "Activate Study Protocol (mute distractions & calm workspace)?",
                spokenPrompt = "বস, পড়াশোনা বা গভীর মনোযোগের জন্য স্টাডি প্রোটোকল চালু করব?",
                actionCommand = "study mode"
            )
            "meeting" -> ProactiveSuggestionEvent(
                id = "test_meeting_${System.currentTimeMillis()}",
                category = "Meeting Alert",
                title = "Upcoming Schedule Briefing",
                message = "Calendar window approaching. Want a quick executive briefing?",
                spokenPrompt = "বস, আপনার আসন্ন মিটিং বা রিমাইন্ডারের সময় কাছাকাছি! সংক্ষেপে ব্রিফ করব কি?",
                actionCommand = null
            )
            "tasks" -> {
                val pending = store.todos.value.count { !it.isDone }
                ProactiveSuggestionEvent(
                    id = "test_tasks_${System.currentTimeMillis()}",
                    category = "Tasks Prompt",
                    title = "$pending Pending Tasks",
                    message = "You have $pending open items in your Agenda. Shall we knock one out?",
                    spokenPrompt = "বস, আপনার টু-ডু লিস্টে $pending টি কাজ বাকি আছে। কোনটা দিয়ে শুরু করব?",
                    actionCommand = null
                )
            }
            "morning" -> ProactiveSuggestionEvent(
                id = "test_morning_${System.currentTimeMillis()}",
                category = "Morning Briefing",
                title = "Good Morning Briefing",
                message = "Ready to review today's calendar, weather, and hardware diagnostics?",
                spokenPrompt = "সুপ্রভাত বস! আজকের ক্যালেন্ডার, টাস্ক এবং সিস্টেম ব্রিফিং শোনাব কি?",
                actionCommand = null
            )
            "commute" -> ProactiveSuggestionEvent(
                id = "test_commute_${System.currentTimeMillis()}",
                category = "Commute Watch",
                title = "Transit & Traffic Watch",
                message = "Checking commute corridor from Home to Work.",
                spokenPrompt = "বস, যাতায়াতের জন্য রুট ট্রাফিক ব্রিফিং দেখতে চান?",
                actionCommand = null
            )
            else -> ProactiveSuggestionEvent(
                id = "test_battery_${System.currentTimeMillis()}",
                category = "Battery Alert",
                title = "Arc Reactor Power Advisory",
                message = "Reserve cells below threshold. Recommend connecting AC Flux.",
                spokenPrompt = "সতর্কতা বস, ফোনের ব্যাটারি কমে এসেছে! চার্জারে কানেক্ট করলে ভালো হবে।",
                actionCommand = null
            )
        }
        _activeSuggestion.value = evt
        onEmit(evt)
    }

    private fun evaluateProactiveConditions(sensors: HardwareSensorsController): ProactiveSuggestionEvent? {
        val snap = prefsStore.state.value
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
        val batt = sensors.readBatteryTelemetry()

        if (snap.proactiveBatteryAlert && !batt.isCharging && batt.batteryLevel <= 18) {
            return ProactiveSuggestionEvent(
                id = "batt_${dayKey}_${batt.batteryLevel / 5}",
                category = "Battery",
                title = "Low Reserve: ${batt.batteryLevel}%",
                message = "Core power at ${batt.batteryLevel}%. Plug into Arc Reactor flux.",
                spokenPrompt = "বস, ব্যাটারি মাত্র ${batt.batteryLevel} শতাংশ বাকি আছে। চার্জার লাগালে ভালো হবে।"
            )
        }
        if (snap.proactiveNightPrompt && (hour >= 23 || hour < 4)) {
            return ProactiveSuggestionEvent(
                id = "night_$dayKey",
                category = "Night",
                title = "Night Protocol Suggested",
                message = "Late hour detected. Switch on Night Protocol?",
                spokenPrompt = "বস, অনেক রাত হয়েছে। ঘুমের জন্য নাইট প্রোটোকল চালু করে দেব?",
                actionCommand = "night mode"
            )
        }
        if (snap.proactiveMorningPrompt && hour in 7..9) {
            return ProactiveSuggestionEvent(
                id = "morning_$dayKey",
                category = "Morning",
                title = "Morning Briefing Ready",
                message = "Pending tasks: ${store.todos.value.count { !it.isDone }}. Tap to start.",
                spokenPrompt = "সুপ্রভাত বস! আজকের কাজের তালিকা প্রস্তুত আছে।"
            )
        }
        return null
    }

    // -------------------------------------------------------------------------
    // SHARED AI PROVIDER ROUTER & MATH HELPER
    // -------------------------------------------------------------------------
    suspend fun runProviderChat(
        apiKey: String,
        baseUrl: String,
        model: String,
        presetId: String,
        systemInstruction: String,
        userPrompt: String
    ): Result<String> {
        val cleanKey = apiKey.trim()
        return if (presetId == "gemini" || baseUrl.contains("generativelanguage.googleapis.com")) {
            geminiClient.streamChat(
                apiKey = cleanKey,
                model = model.ifBlank { GeminiNativeClient.DEFAULT_FLASH_MODEL },
                systemInstruction = systemInstruction,
                history = emptyList(),
                userPrompt = userPrompt,
                onChunk = {}
            )
        } else {
            val base = baseUrl.trim().trimEnd('/')
            val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            geminiClient.executeOpenAiCompatibleChat(
                endpointUrl = endpoint,
                apiKey = cleanKey,
                model = model.ifBlank { "gpt-4o-mini" },
                systemInstruction = systemInstruction,
                history = emptyList(),
                userPrompt = userPrompt,
                onChunk = {}
            )
        }
    }

    private fun evaluateSimpleMath(text: String): String? {
        val m = Regex("""(-?\d+(?:\.\d+)?)\s*([+\-*/x×÷])\s*(-?\d+(?:\.\d+)?)""").find(text) ?: return null
        val a = m.groupValues[1].toDoubleOrNull() ?: return null
        val op = m.groupValues[2]
        val b = m.groupValues[3].toDoubleOrNull() ?: return null
        val res = when (op) {
            "+" -> a + b
            "-" -> a - b
            "*", "x", "×" -> a * b
            "/", "÷" -> if (b != 0.0) a / b else return "Cannot divide by zero"
            else -> return null
        }
        val formatted = if (res % 1.0 == 0.0) res.toLong().toString() else "%.2f".format(res)
        return "${m.value} = $formatted"
    }
}
