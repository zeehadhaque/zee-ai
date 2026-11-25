package com.zeeai.companion.jarvis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.zeeai.companion.automation.ZeeAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HudOperationalPhase {
    IDLE, LISTENING, THINKING, EXECUTING, SPEAKING, ALERT
}

/**
 * Central Coordinator uniting Jarvis Preferences, Productivity Vault,
 * Hermes Evolution Engine, Hardware Sensors, Code Black Security,
 * Macro Recording, Overlay Broadcasts, and Screen Snapshots.
 */
class JarvisCoreController(
    private val context: Context,
    val prefs: JarvisPreferences,
    val store: ProductivityAndMemoryStore,
    val hermes: HermesEvolutionEngine,
    val sensors: HardwareSensorsController,
    val superpowers: StarkSuperpowersEngine
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _hudPhase = MutableStateFlow(HudOperationalPhase.IDLE)
    val hudPhase: StateFlow<HudOperationalPhase> = _hudPhase.asStateFlow()

    private val _hudStatusLabel = MutableStateFlow("ZEE AI // READY FOR BOSS")
    val hudStatusLabel: StateFlow<String> = _hudStatusLabel.asStateFlow()

    private val _codeBlackActive = MutableStateFlow(false)
    val codeBlackActive: StateFlow<Boolean> = _codeBlackActive.asStateFlow()

    private val _tamperCount = MutableStateFlow(0)
    val tamperCount: StateFlow<Int> = _tamperCount.asStateFlow()

    private val _codeBlackAlertNote = MutableStateFlow<String?>(null)
    val codeBlackAlertNote: StateFlow<String?> = _codeBlackAlertNote.asStateFlow()

    private val _isRecordingMacro = MutableStateFlow(false)
    val isRecordingMacro: StateFlow<Boolean> = _isRecordingMacro.asStateFlow()

    private val _macroGoalPending = MutableStateFlow("Custom Skill")
    val macroGoalPending: StateFlow<String> = _macroGoalPending.asStateFlow()

    private val _capturedScreenSummary = MutableStateFlow<String?>(null)
    val capturedScreenSummary: StateFlow<String?> = _capturedScreenSummary.asStateFlow()

    private val _showChargingHud = MutableStateFlow(false)
    val showChargingHud: StateFlow<Boolean> = _showChargingHud.asStateFlow()

    private val _toastBanner = MutableStateFlow<String?>(null)
    val toastBanner: StateFlow<String?> = _toastBanner.asStateFlow()

    var onSpeakBanner: ((String) -> Unit)? = null
    private var receiverRegistered = false

    private val macroAndSnapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ZeeAccessibilityService.ACTION_MACRO_CAPTURED -> {
                    val payload = intent.getStringExtra(ZeeAccessibilityService.EXTRA_PAYLOAD) ?: "[]"
                    val goal = _macroGoalPending.value
                    val count = runCatching { JSONArray(payload).length() }.getOrDefault(0)
                    _isRecordingMacro.value = false
                    if (count > 0) {
                        store.saveMacro(goal, payload)
                        hermes.evolveContext(emptyList(), store.todos.value, store.macros.value)
                        emitBanner("ম্যাক্রো '$goal' ($count ধাপ) সেভ হয়েছে বস!")
                    } else {
                        emitBanner("কোনো অ্যাকশন রেকর্ড হয়নি বস।")
                    }
                }
                ZeeAccessibilityService.ACTION_SCREEN_SNAPSHOT -> {
                    val dump = intent.getStringExtra(ZeeAccessibilityService.EXTRA_PAYLOAD).orEmpty().trim()
                    val condensed = if (dump.isEmpty()) {
                        "No readable foreground UI elements detected."
                    } else {
                        dump.lines().take(12).joinToString("\n")
                    }
                    _capturedScreenSummary.value = condensed
                    ZeeFloatingOverlayService.updateState(appContext, _hudPhase.value.name, condensed)
                }
            }
        }
    }

    fun initialize() {
        sensors.startPowerMonitor()
        registerReceivers()
        sensors.onPowerConnected = {
            _showChargingHud.value = true
        }
        sensors.onSentryBreachDetected = { warn ->
            onSpeakBanner?.invoke(warn)
            emitBanner("⚠️ SENTRY BREACH: $warn")
        }
        sensors.onGestureExecuted = { _, spoken ->
            onSpeakBanner?.invoke(spoken)
        }
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(ZeeAccessibilityService.ACTION_MACRO_CAPTURED)
                addAction(ZeeAccessibilityService.ACTION_SCREEN_SNAPSHOT)
            }
            ContextCompat.registerReceiver(appContext, macroAndSnapshotReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    fun updateHudPhase(phase: HudOperationalPhase, statusText: String) {
        _hudPhase.value = phase
        _hudStatusLabel.value = statusText
        ZeeFloatingOverlayService.updateState(appContext, phase.name)
    }

    fun toggleChargingHud(show: Boolean) {
        _showChargingHud.value = show
    }

    fun clearScreenSummary() {
        _capturedScreenSummary.value = null
    }

    fun emitBanner(msg: String) {
        _toastBanner.value = msg
        mainHandler.postDelayed({
            if (_toastBanner.value == msg) _toastBanner.value = null
        }, 4200L)
    }

    // -------------------------------------------------------------------------
    // MACRO RECORDER
    // -------------------------------------------------------------------------
    fun toggleMacroRecorder(goalName: String = "Routine ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}") {
        if (!_isRecordingMacro.value) {
            if (!ZeeAccessibilityService.isServiceEnabled(appContext)) {
                emitBanner("ম্যাক্রো রেকর্ড করতে Accessibility সার্ভিস চালু করুন বস!")
                return
            }
            _macroGoalPending.value = goalName.trim().ifEmpty { "Custom Routine" }
            _isRecordingMacro.value = true
            ZeeAccessibilityService.sendControlCommand(appContext, "MACRO_START")
            emitBanner("ম্যাক্রো রেকর্ডিং চালু! আপনি যা করবেন আমি শিখে নেব বস।")
            onSpeakBanner?.invoke("ম্যাক্রো রেকর্ডিং চালু হয়েছে বস! আপনি কাজটি করে SAVE বাটনে চাপ দিন।")
        } else {
            ZeeAccessibilityService.sendControlCommand(appContext, "MACRO_STOP")
        }
    }

    // -------------------------------------------------------------------------
    // CODE BLACK SECURITY LOCKDOWN
    // -------------------------------------------------------------------------
    fun triggerCodeBlack(speakAlert: Boolean = true) {
        _tamperCount.value = 0
        _codeBlackAlertNote.value = null
        _codeBlackActive.value = true
        updateHudPhase(HudOperationalPhase.ALERT, "CODE BLACK // SYSTEM LOCKDOWN")
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.85f).toInt().coerceAtLeast(1), 0)
        }
        vibratePulse(450L)
        if (speakAlert) {
            onSpeakBanner?.invoke("কোড ব্ল্যাক লকডাউন সক্রিয় হয়েছে বস! সব সিস্টেম সিকিউরড।")
        }
    }

    fun recordCodeBlackTamper() {
        val count = _tamperCount.value + 1
        _tamperCount.value = count
        _codeBlackAlertNote.value = "🚨 UNAUTHORIZED INTERACTION LOGGED (#$count) AT ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
        vibratePulse(280L)
        onSpeakBanner?.invoke("সতর্ক হোন! বসের অনুমতি ছাড়া সিস্টেম আনলকের চেষ্টা ধরা পড়েছে!")
    }

    fun unlockCodeBlack() {
        _codeBlackActive.value = false
        _tamperCount.value = 0
        _codeBlackAlertNote.value = null
        updateHudPhase(HudOperationalPhase.IDLE, "ZEE AI // PROTOCOL VERIFIED")
        onSpeakBanner?.invoke("ভেরিফায়েড বস! কোড ব্ল্যাক লকডাউন তুলে নেওয়া হয়েছে।")
    }

    // -------------------------------------------------------------------------
    // FLOATING OVERLAY CONTROL
    // -------------------------------------------------------------------------
    fun setOverlayActive(enable: Boolean): Boolean {
        if (enable && !Settings.canDrawOverlays(appContext)) {
            emitBanner("ফ্লোটিং ওভারলে পারমিশন দিন বস (Display over other apps)")
            return false
        }
        if (enable) {
            ZeeFloatingOverlayService.start(appContext)
            prefs.update { it.copy(overlayBubbleActive = true) }
            emitBanner("ফ্লোটিং হোলোগ্রাম ওভারলে চালু হয়েছে বস!")
        } else {
            ZeeFloatingOverlayService.stop(appContext)
            prefs.update { it.copy(overlayBubbleActive = false) }
            emitBanner("ফ্লোটিং ওভারলে বন্ধ করা হয়েছে বস।")
        }
        return true
    }

    // -------------------------------------------------------------------------
    // SELF-EVOLVING HERMES EVOLUTION + PROTOCOL CHECK
    // -------------------------------------------------------------------------
    fun runHermesEvolution(memorySnippets: List<String>): String {
        val msg = hermes.evolveContext(memorySnippets, store.todos.value, store.macros.value)
        emitBanner(msg)
        return msg
    }

    fun tryHandleProtocolCommand(text: String): String? {
        val lower = text.trim().lowercase()
        if (lower.contains("code black") || lower.contains("লকডাউন") || lower.contains("lockdown")) {
            triggerCodeBlack(true)
            return "কোড ব্ল্যাক সক্রিয় হয়েছে বস!"
        }
        if (lower.contains("read my screen") || lower.contains("what is on screen") || lower.contains("স্ক্রিন পড়")) {
            ZeeAccessibilityService.sendControlCommand(appContext, "SNAPSHOT_SCREEN")
            return "স্ক্রিনের কনটেন্ট পড়ছি বস..."
        }
        if (prefs.state.value.workflowsEnabled) {
            val wf = hermes.findMatchingWorkflow(text)
            if (wf != null) {
                hermes.executeWorkflow(wf, sensors)
                return wf.spokenConfirmation
            }
        }
        return null
    }

    private fun vibratePulse(ms: Long) {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    fun release() {
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(macroAndSnapshotReceiver) }
            receiverRegistered = false
        }
        sensors.release()
    }
}
