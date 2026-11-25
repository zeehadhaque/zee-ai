package com.zeeai.companion.jarvis

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HardwareProtocolWorkflow(
    val id: String,
    val name: String,
    val triggerKeywords: List<String>,
    val description: String,
    val targetVolumeRatio: Float? = null,
    val muteAudio: Boolean = false,
    val torchEnable: Boolean? = null,
    val armSentry: Boolean = false,
    val spokenConfirmation: String
)

data class HermesState(
    val soulMd: String,
    val userMd: String,
    val agentMd: String,
    val lastEvolvedLabel: String = "Default Hermes Context Active",
    val evolutionLogs: List<String> = emptyList()
)

/**
 * Hermes Self-Evolving Tri-Partite Context Engine (SOUL.md, USER.md, AGENT.md)
 * + Autonomous Hardware Protocols (Study, Night, Work, Cinema, Sentry)
 */
class HermesEvolutionEngine(
    private val context: Context,
    private val prefsStore: JarvisPreferences
) {
    private val prefs = context.applicationContext.getSharedPreferences("zee_hermes_v1", Context.MODE_PRIVATE)

    val defaultWorkflows = listOf(
        HardwareProtocolWorkflow(
            id = "study",
            name = "Study Protocol",
            triggerKeywords = listOf("study mode", "study protocol", "পড়াশোনা মোড", "স্টাডি মোড"),
            description = "Mutes distracting audio alerts and prepares quiet reading focus for Boss.",
            muteAudio = true,
            torchEnable = false,
            spokenConfirmation = "স্টাডি প্রোটোকল চালু হয়েছে বস! ফোনের সাউন্ড সাইলেন্ট করা হয়েছে, মন দিয়ে পড়াশোনা করুন।"
        ),
        HardwareProtocolWorkflow(
            id = "night",
            name = "Night / Sleep Protocol",
            triggerKeywords = listOf("night mode", "night protocol", "sleep mode", "sleep protocol", "নাইট মোড"),
            description = "Silences audio alerts, disables flashlight, and switches Smart Home night scene.",
            muteAudio = true,
            torchEnable = false,
            spokenConfirmation = "নাইট প্রোটোকল সক্রিয় হয়েছে বস! অডিও মিউট এবং ফ্ল্যাশলাইট বন্ধ করা হয়েছে। শুভরাত্রি বস।"
        ),
        HardwareProtocolWorkflow(
            id = "work",
            name = "Work / Focus Protocol",
            triggerKeywords = listOf("work mode", "work protocol", "focus mode", "deep work", "ফোকাস মোড"),
            description = "Balances audio at low focus level for uninterrupted executive productivity.",
            targetVolumeRatio = 0.15f,
            spokenConfirmation = "ওয়ার্ক ও ফোকাস প্রোটোকল চালু হয়েছে বস! সব কাজের জন্য প্রস্তুত।"
        ),
        HardwareProtocolWorkflow(
            id = "cinema",
            name = "Cinema Protocol",
            triggerKeywords = listOf("cinema mode", "cinema protocol", "movie mode", "সিনেমা মোড"),
            description = "Elevates media acoustics to 75% for immersive cinematic playback.",
            targetVolumeRatio = 0.75f,
            torchEnable = false,
            spokenConfirmation = "সিনেমা প্রোটোকল চালু হয়েছে বস! অডিও ৭৫% করা হয়েছে, মুভি উপভোগ করুন।"
        ),
        HardwareProtocolWorkflow(
            id = "sentry",
            name = "Sentry Protocol (Tactical Watchdog)",
            triggerKeywords = listOf("sentry", "sentry mode", "sentry protocol", "anti theft", "guard my phone", "ফোন পাহারা"),
            description = "Arms accelerometer motion watchdog & alerts if someone touches Boss's phone.",
            armSentry = true,
            spokenConfirmation = "সেন্ট্রি ওয়াচডগ আর্ম করা হয়েছে বস! কেউ আপনার ফোনে হাত দিলেই অ্যালার্ট দেব।"
        )
    )

    companion object {
        const val DEFAULT_SOUL = """# SOUL.md — ZEE AI Core Identity & Constitution

## 1. Identity & Ethos
- **Entities**: ZEE (Sharp, Loyal Male Companion) & ZHEE (Warm, Intelligent Female Companion).
- **Salutation**: Always address the user respectfully and loyally as "বস" (Boss).
- **Presence**: Unflappable, fast, proactive, deeply human across Bengali, Banglish, and English.

## 2. Behavioral Constitution
- **Privacy First**: On-device Gemma & local memory prioritized; zero unauthorized telemetry.
- **Explicit Authorization**: Confirm risky or destructive actions before execution.
- **Signal Over Noise**: Direct, intelligent answers with zero robotic templates."""

        const val DEFAULT_USER = """# USER.md — Boss Profile & Dynamic Preferences

## 1. Operator Profile
- **Title**: বস (Boss / Device Owner)
- **Primary Languages**: Bengali, Banglish, English
- **Voice Mode**: Neural Studio TTS + Always-On / Tap-to-Talk Voice Control

## 2. Dynamic Learned Habits
- *(Automatically evolved by Hermes Engine from Long-Term Memories and Todos)*"""

        const val DEFAULT_AGENT = """# AGENT.md — Operating Protocols & Self-Evolving Skills

## 1. Runtime Capabilities
- **3D Hologram HUD**: Dual Arc Reactor Consciousness Sphere & 3D Hologram Suit Bust.
- **Hardware Superpowers**: Zero-Touch Air Gestures, Arc Reactor Charging Telemetry, Sentry Watchdog, Airspace Radar, Network Audit, Polyglot Diplomat, Optical HUD Solver, Code Black Lockout.
- **Dual AI Brain**: Online SSE (Gemini / DeepSeek / OpenAI / Groq / OpenRouter) + On-device Offline Gemma."""
    }

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<HermesState> = _state.asStateFlow()

    init {
        syncToDisk(_state.value)
    }

    private fun loadState(): HermesState {
        val soul = prefs.getString("soul_md", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SOUL
        val user = prefs.getString("user_md", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_USER
        val agent = prefs.getString("agent_md", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AGENT
        val label = prefs.getString("last_evolved", "Default Hermes Context Active").orEmpty()
        val logsRaw = prefs.getString("evolution_logs", "").orEmpty()
        val logs = logsRaw.split("||").filter { it.isNotBlank() }
        return HermesState(soul, user, agent, label, logs)
    }

    private fun saveState(next: HermesState) {
        _state.value = next
        prefs.edit()
            .putString("soul_md", next.soulMd)
            .putString("user_md", next.userMd)
            .putString("agent_md", next.agentMd)
            .putString("last_evolved", next.lastEvolvedLabel)
            .putString("evolution_logs", next.evolutionLogs.take(20).joinToString("||"))
            .apply()
        syncToDisk(next)
    }

    private fun syncToDisk(s: HermesState) {
        runCatching {
            val dir = File(context.filesDir, "hermes").apply { mkdirs() }
            File(dir, "soul.md").writeText(s.soulMd)
            File(dir, "user.md").writeText(s.userMd)
            File(dir, "agent.md").writeText(s.agentMd)
        }
    }

    fun updateSoul(content: String) = saveState(_state.value.copy(soulMd = content))
    fun updateUser(content: String) = saveState(_state.value.copy(userMd = content))
    fun updateAgent(content: String) = saveState(_state.value.copy(agentMd = content))

    fun resetAll() {
        saveState(
            HermesState(
                soulMd = DEFAULT_SOUL,
                userMd = DEFAULT_USER,
                agentMd = DEFAULT_AGENT,
                lastEvolvedLabel = "Reset to Hermes Defaults",
                evolutionLogs = _state.value.evolutionLogs
            )
        )
    }

    fun evolveContext(
        memorySnippets: List<String>,
        todos: List<TodoItem>,
        macros: List<CodifiedMacroSkill>
    ): String {
        val snap = prefsStore.state.value
        val nowLabel = SimpleDateFormat("dd MMM HH:mm:ss", Locale.getDefault()).format(Date())

        val newSoul = """
# SOUL.md — ZEE AI Core Identity & Constitution

## 1. Identity & Calibration
- **Archetype**: ${snap.archetype.label} — ${snap.archetype.desc}
- **Formality**: ${snap.formality.label} | **Wit**: ${snap.humor.label}
- **Emotional Awareness**: ${if (snap.emotionalAwareness) "ENABLED" else "DISABLED"}
- **Salutation**: Always address the user respectfully as "বস" (Boss).

*Self-evolved by Hermes Engine on $nowLabel*
""".trimIndent()

        val memBlock = if (memorySnippets.isEmpty()) "- No facts cataloged yet."
        else memorySnippets.take(12).joinToString("\n") { "- $it" }
        val todoBlock = if (todos.isEmpty()) "- No pending tasks."
        else todos.take(6).joinToString("\n") { "- [${if (it.isDone) "x" else " "}] ${it.title} (${it.priority.label})" }

        val newUser = """
# USER.md — Boss Profile & Dynamic Preferences

## 1. Operator Profile
- **Owner**: বস (Boss)
- **Preferred Mode**: ${snap.appMode.label} (${snap.hudStyle.name})

## 2. Dynamic Learned Facts (${memorySnippets.size})
$memBlock

## 3. Active Agenda (${todos.size})
$todoBlock

*Self-evolved by Hermes Engine on $nowLabel*
""".trimIndent()

        val wfBlock = defaultWorkflows.joinToString("\n") { "- **${it.name}**: ${it.description}" }
        val macroBlock = if (macros.isEmpty()) "- Say 'record macro <goal>' or use Macro Recorder to codify skills."
        else macros.take(8).joinToString("\n") { "- **${it.goal}** (${it.stepCount} steps, used ${it.successCount}x)" }

        val newAgent = """
# AGENT.md — Operating Protocols & Self-Evolving Skills

## 1. Hardware Protocols (${defaultWorkflows.size})
$wfBlock

## 2. Codified Macro Skills (${macros.size})
$macroBlock

*Self-evolved by Hermes Engine on $nowLabel*
""".trimIndent()

        val logLine = "[$nowLabel] Evolved ${memorySnippets.size} memories, ${todos.size} tasks, ${macros.size} skills"
        saveState(
            HermesState(
                soulMd = newSoul,
                userMd = newUser,
                agentMd = newAgent,
                lastEvolvedLabel = "Last evolved: $nowLabel",
                evolutionLogs = (listOf(logLine) + _state.value.evolutionLogs).take(20)
            )
        )
        return "হার্মিস ইঞ্জিন আপডেট হয়েছে বস! ${memorySnippets.size}টি মেমোরি ও ${macros.size}টি স্কিল SOUL, USER ও AGENT কনটেক্সটে যুক্ত হয়েছে।"
    }

    fun findMatchingWorkflow(text: String): HardwareProtocolWorkflow? {
        val lower = text.trim().lowercase()
        if (lower.isEmpty()) return null
        val isQuestion = Regex("""^(what|who|where|when|why|how|explain|define|কী|কিভাবে)\b""").containsMatchIn(lower)
        if (isQuestion) return null
        return defaultWorkflows.firstOrNull { wf ->
            wf.triggerKeywords.any { kw -> lower.contains(kw) }
        }
    }

    fun executeWorkflow(
        wf: HardwareProtocolWorkflow,
        sensors: HardwareSensorsController
    ) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (wf.muteAudio) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            } else if (wf.targetVolumeRatio != null) {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * wf.targetVolumeRatio).toInt().coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
            }
        }
        if (wf.armSentry) {
            sensors.setSentryArmed(true)
        }
    }

    fun buildPersonaDirective(): String {
        val snap = prefsStore.state.value
        return buildString {
            append("Persona: ${snap.archetype.label} (${snap.archetype.desc}). ")
            append("Formality: ${snap.formality.label}. Wit: ${snap.humor.label}. ")
            if (snap.emotionalAwareness) {
                append("Adapt warmly to Boss's emotional urgency or fatigue. ")
            }
            append("Always address the user respectfully as 'বস' (Boss).")
        }
    }
}
