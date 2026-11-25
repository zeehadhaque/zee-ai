package com.zeeai.companion.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zeeai.companion.MainActivity
import com.zeeai.companion.ai.ActiveEngineRoute
import com.zeeai.companion.ai.BestFriendPersona
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.ai.RouterConfig
import com.zeeai.companion.ai.UnifiedCommandAndChatRouter
import com.zeeai.companion.automation.JarvisScreenAgent
import com.zeeai.companion.automation.NativePhoneCommander
import com.zeeai.companion.automation.OfflineAutonomousPhoneController
import com.zeeai.companion.automation.PhoneCommandOutcome
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.jarvis.JarvisPreferences
import com.zeeai.companion.jarvis.ZeeFloatingOverlayService
import com.zeeai.companion.offline.OfflineConversationalBrain
import com.zeeai.companion.offline.PersonalOfflineMemoryVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

data class WakeWordDetection(
    val targetCharacterId: String, // "zee" or "zhee"
    val trailingCommand: String,
    val timestamp: Long = System.currentTimeMillis()
)

object WakeWordEventBus {
    private val _detections = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 4)
    val detections: SharedFlow<WakeWordDetection> = _detections.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isHandsFreeSessionDesired = MutableStateFlow(false)
    val isHandsFreeSessionDesired: StateFlow<Boolean> = _isHandsFreeSessionDesired.asStateFlow()

    @Volatile
    var isPausedForActiveVoice: Boolean = false
        private set

    internal var activeServiceRef: WeakReference<WakeWordForegroundService>? = null

    internal fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    internal fun setPaused(paused: Boolean) {
        isPausedForActiveVoice = paused
    }

    fun setPhoneMicSessionDesired(active: Boolean) {
        _isHandsFreeSessionDesired.value = active
    }

    internal fun emitDetection(detection: WakeWordDetection) {
        _detections.tryEmit(detection)
    }
}

class WakeWordForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isStopping = false
    private var lastTriggerTimeMs = 0L

    private var phoneCommander: NativePhoneCommander? = null
    private var offlinePhoneController: OfflineAutonomousPhoneController? = null
    private var geminiClient: GeminiNativeClient? = null
    private var router: UnifiedCommandAndChatRouter? = null
    private var keyVault: SecureKeyVault? = null
    private var jarvisPrefs: JarvisPreferences? = null
    private var onlineVoiceEngine: HumanVoiceEngine? = null
    private var jarvisAudioMicEngine: JarvisAudioMicEngine? = null
    private var screenAgent: JarvisScreenAgent? = null

    @Volatile
    private var isTtsSpeaking = false
    @Volatile
    private var handsFreeDirectMode = false
    @Volatile
    private var handsFreeFemalePersona = false
    @Volatile
    private var forceEnglishFallback = false
    @Volatile
    private var preferSystemFallback = false

    private val restartRunnable = Runnable {
        if (!isStopping && !WakeWordEventBus.isPausedForActiveVoice && !isTtsSpeaking) {
            startListeningCycle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        WakeWordEventBus.activeServiceRef = WeakReference(this)
        createNotificationChannel()
        val vault = runCatching { SecureKeyVault(applicationContext) }.getOrNull()
        val memVault = PersonalOfflineMemoryVault(applicationContext)
        val offlineCtrl = OfflineAutonomousPhoneController(applicationContext, memVault)
        val gemini = GeminiNativeClient()
        val commander = NativePhoneCommander(applicationContext)

        keyVault = vault
        jarvisPrefs = JarvisPreferences(applicationContext)
        phoneCommander = commander
        offlinePhoneController = offlineCtrl
        geminiClient = gemini
        router = UnifiedCommandAndChatRouter(applicationContext, offlineCtrl, gemini)
        jarvisAudioMicEngine = JarvisAudioMicEngine(gemini)
        screenAgent = JarvisScreenAgent(applicationContext, gemini, commander)

        onlineVoiceEngine = HumanVoiceEngine(
            context = applicationContext,
            onSpeechStart = {
                isTtsSpeaking = true
                jarvisAudioMicEngine?.setPaused(true)
                ZeeFloatingOverlayService.updateState(applicationContext, "SPEAKING", null)
                mainHandler.post { destroyRecognizer() }
            },
            onSpeechDone = {
                isTtsSpeaking = false
                jarvisAudioMicEngine?.setPaused(false)
                ZeeFloatingOverlayService.updateState(
                    applicationContext,
                    if (handsFreeDirectMode) "LISTENING" else "IDLE",
                    if (handsFreeDirectMode) "কমান্ড বলুন বস..." else null
                )
                scheduleRestart(350L)
            }
        )
    }

    internal fun pauseImmediately() {
        WakeWordEventBus.setPaused(true)
        jarvisAudioMicEngine?.setPaused(true)
        mainHandler.post { destroyRecognizer() }
    }

    internal fun resumeImmediately() {
        WakeWordEventBus.setPaused(false)
        jarvisAudioMicEngine?.setPaused(false)
        scheduleRestart(400L)
    }

    internal fun stopImmediately() {
        mainHandler.post { stopWakeWordService() }
    }

    private fun speakReply(text: String, isFemale: Boolean = handsFreeFemalePersona) {
        val clean = text.removePrefix("⚠️").trim()
        if (clean.isBlank()) return
        val vault = keyVault
        val geminiKey = vault?.getKey("gemini_api_key").orEmpty()
        val elevenKey = vault?.getKey("elevenlabs_api_key").orEmpty()
        vault?.let {
            onlineVoiceEngine?.updateVoiceConfig(OnlineHumanVoiceCatalog.loadConfig(it))
        }
        onlineVoiceEngine?.speakHuman(
            rawText = clean,
            persona = if (isFemale) VoicePersona.ZHEE_FEMALE else VoicePersona.ZEE_MALE,
            isOnline = isNetworkAvailable(),
            geminiApiKey = geminiKey,
            elevenLabsApiKey = elevenKey
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val isHandsFreeIntent = intent?.action == ACTION_START_HANDS_FREE
        if (isHandsFreeIntent) {
            handsFreeDirectMode = true
            handsFreeFemalePersona = intent?.getBooleanExtra(EXTRA_FEMALE_PERSONA, false) == true
        } else if (intent?.action == ACTION_START) {
            handsFreeDirectMode = false
        }

        val notifTitle = if (handsFreeDirectMode) {
            "ZEE Hands-Free Mic ON"
        } else {
            "ZEE AI Always-On Active"
        }
        val notifBody = if (handsFreeDirectMode) {
            "All voice commands live across YouTube, Google, WhatsApp, Scroll..."
        } else {
            "Listening 24/7 for \"Hey Zee\" or direct phone commands"
        }

        val notification = buildNotification(notifTitle, notifBody)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        val foregroundOk = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
            true
        }.getOrDefault(false)

        if (!foregroundOk || !hasMic) {
            stopWakeWordService()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                WakeWordEventBus.setPhoneMicSessionDesired(false)
                stopWakeWordService()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                WakeWordEventBus.setPaused(true)
                jarvisAudioMicEngine?.setPaused(true)
                destroyRecognizer()
                return START_STICKY
            }
            ACTION_RESUME -> {
                WakeWordEventBus.setPaused(false)
                jarvisAudioMicEngine?.setPaused(false)
                scheduleRestart(350L)
                return START_STICKY
            }
        }

        isStopping = false
        WakeWordEventBus.setPaused(false)
        WakeWordEventBus.setRunning(true)
        if (isHandsFreeIntent) {
            if (Settings.canDrawOverlays(this)) {
                ZeeFloatingOverlayService.start(this)
                ZeeFloatingOverlayService.updateState(this, "LISTENING", "হ্যান্ডস-ফ্রি JARVIS মাইক চালু হয়েছে বস")
            }
            // Brief delay lets confirmation speech from foreground Activity finish cleanly before across-app mic listens
            scheduleRestart(1200L)
        } else {
            jarvisAudioMicEngine?.stopCapture()
            mainHandler.post { startListeningCycle() }
        }
        return START_STICKY
    }

    private fun createBestRecognizer(): SpeechRecognizer? {
        if (!preferSystemFallback) {
            val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.as", "com.google.android.tts")
            val services = runCatching {
                packageManager.queryIntentServices(Intent("android.speech.RecognitionService"), 0)
            }.getOrDefault(emptyList())
            val googleService = preferred.firstNotNullOfOrNull { pkg ->
                services.firstOrNull { it.serviceInfo?.packageName == pkg }
            }
            if (googleService != null) {
                val comp = ComponentName(googleService.serviceInfo.packageName, googleService.serviceInfo.name)
                val rec = runCatching { SpeechRecognizer.createSpeechRecognizer(applicationContext, comp) }.getOrNull()
                if (rec != null) return rec
            }
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(applicationContext) }.getOrNull()
            ?: runCatching { SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
    }

    private fun startListeningCycle() {
        if (isStopping || WakeWordEventBus.isPausedForActiveVoice || isTtsSpeaking) return

        val geminiKey = keyVault?.getKey("gemini_api_key").orEmpty().trim()
        if (handsFreeDirectMode && geminiKey.isNotBlank() && isNetworkAvailable()) {
            destroyRecognizer()
            val engine = jarvisAudioMicEngine
            if (engine != null) {
                if (engine.isRunning()) {
                    engine.setPaused(false)
                    ZeeFloatingOverlayService.updateState(applicationContext, "LISTENING", "কমান্ড বলুন বস...")
                    return
                }
                ZeeFloatingOverlayService.updateState(applicationContext, "LISTENING", "পিওর JARVIS মাইক শুনছে বস...")
                engine.startContinuousCapture(
                    apiKeyProvider = { keyVault?.getKey("gemini_api_key").orEmpty().trim() },
                    onRmsLevel = { },
                    onStatus = { status ->
                        if (status.contains("Analyzing", ignoreCase = true) || status.contains("Transcribing", ignoreCase = true)) {
                            ZeeFloatingOverlayService.updateState(applicationContext, "THINKING", "বুঝতেছি বস...")
                        }
                    },
                    onHearingStart = {
                        ZeeFloatingOverlayService.updateState(applicationContext, "HEARING", "শুনছি বস...")
                    },
                    onTranscript = { spoken ->
                        val clean = spoken.trim()
                        if (clean.isNotBlank()) {
                            executeHandsFreeCommand(clean)
                        }
                    },
                    onError = { err ->
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification("ZEE Hands-Free Mic", err.take(90)))
                    }
                )
                return
            }
        }

        destroyRecognizer()

        val recognizer = createBestRecognizer()
        if (recognizer == null) {
            scheduleRestart(2000L)
            return
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (handsFreeDirectMode) return // Wait for complete utterance in direct hands-free mode
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) {
                    checkForWakeWordOrCommand(text, isFinal = false)
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (text.isNotBlank()) {
                    if (handsFreeDirectMode) {
                        executeHandsFreeCommand(text)
                    } else {
                        checkForWakeWordOrCommand(text, isFinal = true)
                    }
                }
                scheduleRestart(350L)
            }

            override fun onError(error: Int) {
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    stopWakeWordService()
                    return
                }
                if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    forceEnglishFallback = true
                } else if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER) {
                    preferSystemFallback = !preferSystemFallback
                }
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 280L
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 650L
                    else -> 800L
                }
                scheduleRestart(delayMs)
            }
        })

        val listenIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (forceEnglishFallback) "en-US" else "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }

        speechRecognizer = recognizer
        runCatching { recognizer.startListening(listenIntent) }
            .onFailure { scheduleRestart(900L) }
    }

    private fun executeHandsFreeCommand(spokenText: String) {
        val lower = spokenText.lowercase().trim()
        if (lower in listOf("stop mic", "mic off", "mic bondho", "mic bondho koro", "stop listening", "মাইক বন্ধ", "মাইক বন্ধ করো")) {
            handsFreeDirectMode = false
            WakeWordEventBus.setPhoneMicSessionDesired(false)
            ZeeFloatingOverlayService.updateState(applicationContext, "IDLE", "মাইক বন্ধ হয়েছে বস")
            speakReply("মাইক বন্ধ করেছি বস।")
            mainHandler.postDelayed({ stopWakeWordService() }, 1800L)
            return
        }

        val outcome = phoneCommander?.tryHandleCommand(spokenText)
        if (outcome is PhoneCommandOutcome.Executed) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification("ZEE Hands-Free Mic ON", outcome.replyText))
            ZeeFloatingOverlayService.updateState(applicationContext, "EXECUTING", outcome.replyText)
            speakReply(outcome.replyText)
            return
        }
        if (outcome is PhoneCommandOutcome.NeedsConfirmation) {
            val confirmReply = runCatching { outcome.confirmation.onConfirm() }
                .getOrElse { outcome.confirmation.details }
            ZeeFloatingOverlayService.updateState(applicationContext, "EXECUTING", confirmReply)
            speakReply(confirmReply)
            return
        }

        val r = router ?: return
        val vault = keyVault ?: return
        val prefsSnap = jarvisPrefs?.state?.value
        val activeModel = vault.getKey("gemini_flash_model").removePrefix("models/").trim()
            .ifBlank { GeminiNativeClient.DEFAULT_FLASH_MODEL }
        val geminiKey = vault.getKey("gemini_api_key").trim()

        serviceScope.launch {
            jarvisAudioMicEngine?.setPaused(true)
            var didSpeak = false
            try {
                if (geminiKey.isNotBlank() && isNetworkAvailable() && JarvisScreenAgent.looksLikeScreenOrMultiStepTask(spokenText)) {
                    ZeeFloatingOverlayService.updateState(applicationContext, "EXECUTING", "JARVIS অ্যাকশন চালাচ্ছে: ${spokenText.take(40)}")
                    val agentRes = screenAgent?.executeGoal(spokenText, geminiKey) { stepMsg ->
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification("JARVIS Screen Agent", stepMsg.take(90)))
                        ZeeFloatingOverlayService.updateState(applicationContext, "EXECUTING", stepMsg.take(80))
                    }
                    val finalReply = agentRes?.getOrNull()
                    if (!finalReply.isNullOrBlank()) {
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification("ZEE Hands-Free Mic ON", finalReply.take(90)))
                        ZeeFloatingOverlayService.updateState(applicationContext, "SPEAKING", finalReply.take(90))
                        didSpeak = true
                        speakReply(finalReply)
                        return@launch
                    }
                }

                ZeeFloatingOverlayService.updateState(applicationContext, "THINKING", "জবাব তৈরি হচ্ছে বস...")
                val config = RouterConfig(
                    route = ActiveEngineRoute.ONLINE,
                    selectedOnlineModel = activeModel,
                    persona = if (handsFreeFemalePersona) BestFriendPersona.ZHEE else BestFriendPersona.ZEE,
                    geminiApiKey = geminiKey,
                    groqApiKey = vault.getKey("groq_api_key"),
                    openRouterApiKey = vault.getKey("openrouter_api_key"),
                    customProviderPreset = prefsSnap?.aiProviderPreset ?: "gemini",
                    customBaseUrl = prefsSnap?.aiBaseUrl.orEmpty(),
                    customApiKey = vault.getKey("custom_provider_api_key")
                )
                val reply = r.handleUserInput(
                    userText = spokenText,
                    config = config,
                    history = emptyList(),
                    onStreamingText = {}
                )
                val spoken = reply.substringBefore("\n\n⚡").trim().ifBlank { reply }
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("ZEE Hands-Free Mic ON", spoken.take(90)))
                if (spoken.isNotBlank()) {
                    ZeeFloatingOverlayService.updateState(applicationContext, "SPEAKING", spoken.take(90))
                    didSpeak = true
                    speakReply(spoken)
                }
            } finally {
                if (!didSpeak && !isTtsSpeaking) {
                    jarvisAudioMicEngine?.setPaused(false)
                    ZeeFloatingOverlayService.updateState(
                        applicationContext,
                        if (handsFreeDirectMode) "LISTENING" else "IDLE",
                        if (handsFreeDirectMode) "কমান্ড বলুন বস..." else null
                    )
                }
            }
        }
    }

    private fun checkForWakeWordOrCommand(rawTranscript: String, isFinal: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTimeMs < WAKE_COOLDOWN_MS) return

        val normalized = rawTranscript.trim()
        val zheeRegex = Regex("""(?:^|[\s,।.!?])(?:hey\s+zhee|zhee|জি|ঝি)(?=$|[\s,।.!?])""", RegexOption.IGNORE_CASE)
        val zeeRegex = Regex("""(?:^|[\s,।.!?])(?:hey\s+zee|zee)(?=$|[\s,।.!?])""", RegexOption.IGNORE_CASE)

        val zheeMatch = zheeRegex.find(normalized)
        val zeeMatch = zeeRegex.find(normalized)

        if (zheeMatch != null || zeeMatch != null) {
            val (targetChar, match) = if (zheeMatch != null) "zhee" to zheeMatch else "zee" to zeeMatch!!
            val trailing = normalized.substring(match.range.last + 1).trim()
            if (!isFinal && trailing.isNotEmpty() && trailing.length < 3) return

            lastTriggerTimeMs = now

            WakeWordEventBus.emitDetection(
                WakeWordDetection(targetCharacterId = targetChar, trailingCommand = trailing)
            )

            if (trailing.isNotBlank()) {
                val outcome = phoneCommander?.tryHandleCommand(trailing)
                if (outcome is PhoneCommandOutcome.Executed) {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification("ZEE AI Always-On Active", "${targetChar.uppercase()}: ${outcome.replyText}"))
                    speakReply(outcome.replyText, isFemale = targetChar == "zhee")
                    return
                } else {
                    val offlineReply = OfflineConversationalBrain.respond(trailing, targetChar)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIFICATION_ID, buildNotification("ZEE AI Always-On Active", "${targetChar.uppercase()}: $offlineReply"))
                    speakReply(offlineReply, isFemale = targetChar == "zhee")
                    return
                }
            } else {
                val greeting = if (targetChar == "zhee") "হ্যাঁ বস, আমি শুনছি।" else "হ্যাঁ বস, বলুন কী করতে হবে?"
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("ZEE AI Always-On Active", "${targetChar.uppercase()}: $greeting"))
                speakReply(greeting, isFemale = targetChar == "zhee")
            }
        } else if (isFinal) {
            val outcome = phoneCommander?.tryHandleCommand(normalized)
            if (outcome is PhoneCommandOutcome.Executed) {
                lastTriggerTimeMs = now
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, buildNotification("ZEE AI Always-On Active", "Action: ${outcome.replyText}"))
                speakReply(outcome.replyText, isFemale = false)
            }
        }
    }

    private fun scheduleRestart(delayMs: Long) {
        mainHandler.removeCallbacks(restartRunnable)
        if (!isStopping && !WakeWordEventBus.isPausedForActiveVoice && !isTtsSpeaking) {
            mainHandler.postDelayed(restartRunnable, delayMs)
        }
    }

    private fun destroyRecognizer() {
        mainHandler.removeCallbacks(restartRunnable)
        speechRecognizer?.let { sr ->
            runCatching { sr.cancel() }
            runCatching { sr.destroy() }
        }
        speechRecognizer = null
    }

    private fun stopWakeWordService() {
        isStopping = true
        handsFreeDirectMode = false
        jarvisAudioMicEngine?.stopCapture()
        destroyRecognizer()
        WakeWordEventBus.setRunning(false)
        WakeWordEventBus.setPaused(false)
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        isStopping = true
        handsFreeDirectMode = false
        WakeWordEventBus.activeServiceRef = null
        jarvisAudioMicEngine?.stopCapture()
        destroyRecognizer()
        WakeWordEventBus.setRunning(false)
        WakeWordEventBus.setPaused(false)
        onlineVoiceEngine?.release()
        onlineVoiceEngine = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ZEE AI Background Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground voice control listener across all apps."
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, statusLine: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(statusLine)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop Mic", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "zee_ai_wake_word_channel"
        private const val NOTIFICATION_ID = 4701
        private const val WAKE_COOLDOWN_MS = 2000L

        const val ACTION_START = "com.zeeai.companion.WAKE_START"
        const val ACTION_START_HANDS_FREE = "com.zeeai.companion.HANDS_FREE_START"
        const val ACTION_STOP = "com.zeeai.companion.WAKE_STOP"
        const val ACTION_PAUSE = "com.zeeai.companion.WAKE_PAUSE"
        const val ACTION_RESUME = "com.zeeai.companion.WAKE_RESUME"
        const val EXTRA_FEMALE_PERSONA = "female_persona"

        fun start(context: Context) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun startHandsFreeControl(context: Context, isFemale: Boolean = false) {
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return
            WakeWordEventBus.setPaused(false)
            val intent = Intent(context, WakeWordForegroundService::class.java).apply {
                action = ACTION_START_HANDS_FREE
                putExtra(EXTRA_FEMALE_PERSONA, isFemale)
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            WakeWordEventBus.activeServiceRef?.get()?.let {
                it.stopImmediately()
                return
            }
            if (WakeWordEventBus.isRunning.value) {
                runCatching { context.stopService(Intent(context, WakeWordForegroundService::class.java)) }
            }
        }

        fun pause(context: Context) {
            if (!WakeWordEventBus.isRunning.value) return
            WakeWordEventBus.setPaused(true)
            WakeWordEventBus.activeServiceRef?.get()?.pauseImmediately()
        }

        fun resume(context: Context) {
            if (!WakeWordEventBus.isRunning.value) return
            WakeWordEventBus.setPaused(false)
            WakeWordEventBus.activeServiceRef?.get()?.resumeImmediately()
        }
    }
}
