package com.zeeai.companion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zeeai.companion.R
import com.zeeai.companion.ai.ActiveEngineRoute
import com.zeeai.companion.ai.BestFriendPersona
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.ai.OfflineChatTurn
import com.zeeai.companion.ai.RouterConfig
import com.zeeai.companion.ai.UnifiedCommandAndChatRouter
import com.zeeai.companion.automation.NativePhoneCommander
import com.zeeai.companion.automation.OfflineAutonomousPhoneController
import com.zeeai.companion.automation.PendingConfirmation
import com.zeeai.companion.automation.PhoneCommandOutcome
import com.zeeai.companion.automation.PhoneControlScreen
import com.zeeai.companion.automation.ZeeAccessibilityService
import com.zeeai.companion.data.local.ChatMessageEntity
import com.zeeai.companion.data.local.MemoryEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.jarvis.*
import com.zeeai.companion.offline.PersonalOfflineMemoryVault
import com.zeeai.companion.studios.council.AICouncilScreen
import com.zeeai.companion.studios.filmmaker.FilmmakerStudioScreen
import com.zeeai.companion.studios.reader.UniversalReaderScreen
import com.zeeai.companion.studios.research.ResearchAndTrendsScreen
import com.zeeai.companion.studios.youtube.YouTubeStudioScreen
import com.zeeai.companion.ui.components.*
import com.zeeai.companion.ui.hud.ZeeHologramHud
import com.zeeai.companion.ui.jarvis.*
import com.zeeai.companion.ui.theme.LuxuryPalette
import com.zeeai.companion.ui.theme.ZeeLuxuryTheme
import com.zeeai.companion.voice.NativeVoiceController
import com.zeeai.companion.voice.NativeVoiceProfile
import com.zeeai.companion.voice.OnlineHumanVoiceCatalog
import com.zeeai.companion.voice.WakeWordEventBus
import com.zeeai.companion.voice.WakeWordForegroundService
import com.zeeai.companion.voice.ZeeVoiceInputController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val BgBlackNavy = LuxuryPalette.ObsidianVoid
private val CardSurface = LuxuryPalette.SmokedCrystal
private val Silver = LuxuryPalette.SilverText
private val ElectricBlue = LuxuryPalette.RoyalIndigo
private val Cyan = LuxuryPalette.ElectricCyan

@Composable
fun ZeeNativeApp(
    database: ZeeDatabase,
    keyVault: SecureKeyVault,
    openMicOnLaunch: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { database.dao() }
    val geminiClient = remember { GeminiNativeClient() }
    val phoneCommander = remember { NativePhoneCommander(context) }
    val offlineMemoryVault = remember { PersonalOfflineMemoryVault(context) }
    val offlinePhoneController = remember { OfflineAutonomousPhoneController(context, offlineMemoryVault) }

    // --- Jarvis Core Subsystem Initialization ---
    val jarvisPrefs = remember { JarvisPreferences(context) }
    val productivityStore = remember { ProductivityAndMemoryStore(context) }
    val hermesEngine = remember { HermesEvolutionEngine(context, jarvisPrefs) }
    val hardwareSensors = remember { HardwareSensorsController(context) }
    val starkSuperpowers = remember {
        StarkSuperpowersEngine(
            context = context,
            prefsStore = jarvisPrefs,
            store = productivityStore,
            phoneController = offlinePhoneController,
            geminiClient = geminiClient
        )
    }
    val jarvisCore = remember {
        JarvisCoreController(
            context = context,
            prefs = jarvisPrefs,
            store = productivityStore,
            hermes = hermesEngine,
            sensors = hardwareSensors,
            superpowers = starkSuperpowers
        )
    }

    val jarvisSettings by jarvisPrefs.state.collectAsState()
    val hudPhase by jarvisCore.hudPhase.collectAsState()
    val hudStatusLabel by jarvisCore.hudStatusLabel.collectAsState()
    val codeBlackActive by jarvisCore.codeBlackActive.collectAsState()
    val tamperCount by jarvisCore.tamperCount.collectAsState()
    val codeBlackAlertNote by jarvisCore.codeBlackAlertNote.collectAsState()
    val isRecordingMacro by jarvisCore.isRecordingMacro.collectAsState()
    val capturedScreenSummary by jarvisCore.capturedScreenSummary.collectAsState()
    val showChargingHud by jarvisCore.showChargingHud.collectAsState()
    val toastBanner by jarvisCore.toastBanner.collectAsState()
    val chargingTelemetry by hardwareSensors.chargingTelemetry.collectAsState()
    val airGesturesEnabled by hardwareSensors.airGesturesEnabled.collectAsState()
    val activeGestureBanner by hardwareSensors.activeGestureBanner.collectAsState()
    val sentryStatus by hardwareSensors.sentryStatus.collectAsState()
    val proactiveBanner by starkSuperpowers.activeSuggestion.collectAsState()
    val sessionEntries by productivityStore.sessions.collectAsState()

    // Dialog Visibility States
    var showTodosDialog by remember { mutableStateOf(false) }
    var showCalendarDialog by remember { mutableStateOf(false) }
    var showNotesDialog by remember { mutableStateOf(false) }
    var selectedSessionDetail by remember { mutableStateOf<TaskSessionEntry?>(null) }
    var showBrainDumpDialog by remember { mutableStateOf(false) }
    var showAirGesturesDialog by remember { mutableStateOf(false) }
    var showRadarDialog by remember { mutableStateOf(false) }
    var showNetworkAuditDialog by remember { mutableStateOf(false) }
    var showOpticalSolverDialog by remember { mutableStateOf(false) }
    var showPolyglotDialog by remember { mutableStateOf(false) }

    // Optical AR Media Capture State (No CAMERA permission needed -> uses FileProvider + ACTION_IMAGE_CAPTURE)
    var opticalImageUri by remember { mutableStateOf<Uri?>(null) }
    var opticalImageLabel by remember { mutableStateOf<String?>(null) }
    var pendingCameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var pendingCameraFileName by remember { mutableStateOf("") }

    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            opticalImageUri = uri
            opticalImageLabel = "Selected: ${uri.lastPathSegment?.substringAfterLast("/") ?: "Image"}"
        }
    }
    val cameraCaptureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && pendingCameraTempUri != null) {
            opticalImageUri = pendingCameraTempUri
            opticalImageLabel = "Captured: $pendingCameraFileName"
        }
    }

    var customProviderKeyDraft by remember { mutableStateOf(keyVault.getKey("custom_provider_api_key")) }

    var activeTab by remember { mutableStateOf("companion") } // companion | phone_control | jarvis_settings | permissions_hub | settings
    var activeCharId by remember { mutableStateOf("zee") }    // zee | zhee
    var isSpeaking by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var autoSpeak by remember { mutableStateOf(true) }
    var livePartialVoice by remember { mutableStateOf("") }
    var streamingReply by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var pendingConfirm by remember { mutableStateOf<PendingConfirmation?>(null) }

    var activeGeminiFlash by remember {
        val initial = keyVault.getKey("gemini_flash_model").removePrefix("models/").trim()
        mutableStateOf(if (GeminiNativeClient.isValidChatModel(initial)) initial else GeminiNativeClient.DEFAULT_FLASH_MODEL)
    }
    var activeGeminiPro by remember {
        val initial = keyVault.getKey("gemini_pro_model").removePrefix("models/").trim()
        mutableStateOf(if (GeminiNativeClient.isValidChatModel(initial)) initial else GeminiNativeClient.DEFAULT_PRO_MODEL)
    }
    var autoDiscoveredGeminiModels by remember {
        mutableStateOf(
            keyVault.getKey("gemini_discovered_models")
                .split(",")
                .map { it.removePrefix("models/").trim() }
                .filter { GeminiNativeClient.isValidChatModel(it) }
                .distinct()
        )
    }

    LaunchedEffect(Unit) {
        val savedKey = keyVault.getKey("gemini_api_key").trim()
        if (!GeminiNativeClient.isValidChatModel(activeGeminiFlash)) {
            activeGeminiFlash = GeminiNativeClient.DEFAULT_FLASH_MODEL
            keyVault.saveKey("gemini_flash_model", activeGeminiFlash)
        }
        if (!GeminiNativeClient.isValidChatModel(activeGeminiPro)) {
            activeGeminiPro = GeminiNativeClient.DEFAULT_PRO_MODEL
            keyVault.saveKey("gemini_pro_model", activeGeminiPro)
        }
        if (savedKey.isNotBlank() && autoDiscoveredGeminiModels.isNotEmpty()) {
            geminiClient.primeDiscoveredModels(savedKey, autoDiscoveredGeminiModels, activeGeminiFlash, activeGeminiPro)
        }
        jarvisCore.initialize()

        // Auto-sync live latest models whenever a Google AI Studio key is configured
        if (savedKey.isNotBlank()) {
            geminiClient.discoverModels(savedKey).onSuccess { disc ->
                val bestFlash = disc.bestFlashModel.removePrefix("models/").trim()
                val bestPro = disc.bestProModel.removePrefix("models/").trim()
                val liveIds = disc.models.map { it.id.removePrefix("models/").trim() }.distinct()
                activeGeminiFlash = bestFlash
                activeGeminiPro = bestPro
                autoDiscoveredGeminiModels = liveIds
                keyVault.saveKey("gemini_api_status", "verified")
                keyVault.saveKey("gemini_status_msg", "Auto-Synced • ${liveIds.size} live models active")
                keyVault.saveKey("gemini_discovered_models", liveIds.joinToString(","))
                keyVault.saveKey("gemini_flash_model", bestFlash)
                keyVault.saveKey("gemini_pro_model", bestPro)
                if (jarvisPrefs.state.value.aiProviderPreset == "gemini") {
                    jarvisPrefs.update { it.copy(aiCustomModel = bestFlash) }
                }
            }
        }
    }

    val messages by dao.observeMessages().collectAsState(initial = emptyList())
    val memories by dao.observeAllMemories().collectAsState(initial = emptyList())
    val wakeWordRunning by WakeWordEventBus.isRunning.collectAsState()

    var sendTurnAction by remember { mutableStateOf<(String) -> Unit>({}) }

    val voiceInputController = remember {
        ZeeVoiceInputController(
            context = context,
            onPauseBackgroundWakeWord = { WakeWordForegroundService.pause(context) },
            onResumeBackgroundWakeWord = { WakeWordForegroundService.resume(context) },
            onFinalTranscript = { transcript ->
                if (transcript.isNotBlank()) {
                    sendTurnAction(transcript)
                }
            }
        )
    }
    DisposableEffect(voiceInputController) {
        onDispose {
            voiceInputController.stopListening(resumeWakeWord = true)
        }
    }
    val voiceInputState by voiceInputController.uiState.collectAsState()

    val voiceController = remember {
        NativeVoiceController(context) { speaking ->
            isSpeaking = speaking
            if (speaking) {
                voiceInputController.pauseForAssistantSpeech()
            } else {
                voiceInputController.resumeAfterAssistantSpeech()
            }
        }
    }
    DisposableEffect(voiceController) {
        onDispose {
            voiceController.shutdown()
        }
    }
    val isMicLockedOn by voiceController.isMicLockedOn.collectAsState()
    val voiceState by voiceController.voiceState.collectAsState()
    val isAccessibilityConnected by ZeeAccessibilityService.isConnected.collectAsState()
    var isAccessibilityActive by remember {
        mutableStateOf(ZeeAccessibilityService.isServiceEnabled(context))
    }

    var launchSpeechInputAction by remember { mutableStateOf<() -> Unit>({}) }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMic = granted
        if (granted && activeTab == "companion") {
            launchSpeechInputAction()
        }
    }

    val corePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasMic = results[Manifest.permission.RECORD_AUDIO] ?: hasMic
    }

    fun requestAllRuntimePermissions() {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        corePermissionsLauncher.launch(perms)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activeCharId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isAccessibilityActive = ZeeAccessibilityService.isServiceEnabled(context)
                    hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (WakeWordEventBus.isHandsFreeSessionDesired.value && hasMic && !voiceController.isMicLockedOn.value) {
                        voiceController.resumeAfterForegroundReturn()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    val keepHandsFree = WakeWordEventBus.isHandsFreeSessionDesired.value || voiceController.isMicLockedOn.value
                    if (keepHandsFree && hasMic) {
                        WakeWordEventBus.setPhoneMicSessionDesired(true)
                        voiceController.suspendRecognizerForBackgroundHandoff()
                        WakeWordForegroundService.startHandsFreeControl(context, isFemale = activeCharId == "zhee")
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            geminiClient.cancelActive()
            voiceController.shutdown()
            voiceInputController.stopListening(resumeWakeWord = true)
            jarvisCore.release()
        }
    }

    val voiceProfile = remember(activeCharId) {
        if (activeCharId == "zhee") {
            NativeVoiceProfile(languageTag = "bn-BD", pitch = 1.18f, rate = 0.96f, isFemale = true)
        } else {
            NativeVoiceProfile(languageTag = "bn-BD", pitch = 0.85f, rate = 1.02f, isFemale = false)
        }
    }

    LaunchedEffect(jarvisCore) {
        jarvisCore.onSpeakBanner = { line ->
            if (autoSpeak && jarvisSettings.voiceFeedback) {
                voiceController.speak(line, voiceProfile)
            }
        }
        starkSuperpowers.startProactiveMonitor(scope, hardwareSensors) { evt ->
            if (jarvisSettings.proactiveAutoSpeak && autoSpeak) {
                voiceController.speak(evt.spokenPrompt, voiceProfile)
            }
        }
    }

    LaunchedEffect(voiceInputState.isListening, voiceInputState.partialTranscript, isGenerating, isSpeaking, codeBlackActive) {
        when {
            codeBlackActive -> jarvisCore.updateHudPhase(HudOperationalPhase.ALERT, "CODE BLACK // SYSTEM LOCKDOWN")
            voiceInputState.isListening -> jarvisCore.updateHudPhase(
                HudOperationalPhase.LISTENING,
                voiceInputState.partialTranscript.ifBlank { "LISTENING TO BOSS..." }
            )
            isGenerating -> jarvisCore.updateHudPhase(HudOperationalPhase.THINKING, "SYNTHESIZING RESPONSE...")
            isSpeaking -> jarvisCore.updateHudPhase(HudOperationalPhase.SPEAKING, "ZEE AUDIO STREAMING...")
            else -> jarvisCore.updateHudPhase(HudOperationalPhase.IDLE, "ZEE AI // READY FOR BOSS")
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    val activeGeminiKey = keyVault.getKey("gemini_api_key")
    val activeElevenLabsKey = keyVault.getKey("elevenlabs_api_key")
    val isVoiceOnline = isNetworkAvailable()
    LaunchedEffect(activeGeminiKey, activeElevenLabsKey, isVoiceOnline) {
        voiceController.setApiKeys(activeGeminiKey, activeElevenLabsKey, isVoiceOnline)
        voiceController.updateOnlineVoiceConfig(OnlineHumanVoiceCatalog.loadConfig(keyVault))
    }

    LaunchedEffect(voiceInputController, voiceController) {
        voiceController.onBeforeManualMicStart = {
            voiceInputController.stopListening(resumeWakeWord = false)
        }
    }

    val unifiedRouter = remember {
        UnifiedCommandAndChatRouter(
            context = context,
            phoneController = offlinePhoneController,
            geminiClient = geminiClient
        )
    }

    val effectiveOnlineModel = if (jarvisSettings.aiProviderPreset == "gemini") {
        activeGeminiFlash
    } else {
        jarvisSettings.aiCustomModel.ifBlank { activeGeminiFlash }
    }
    val effectiveProviderApiKey = when (jarvisSettings.aiProviderPreset) {
        "gemini" -> keyVault.getKey("gemini_api_key")
        "groq" -> customProviderKeyDraft.ifBlank { keyVault.getKey("groq_api_key") }
        "openrouter" -> customProviderKeyDraft.ifBlank { keyVault.getKey("openrouter_api_key") }
        else -> customProviderKeyDraft.ifBlank { keyVault.getKey("gemini_api_key") }
    }

    fun sendUserTurn(
        rawPrompt: String,
        forceSpeak: Boolean = false,
        onReplyCompleted: ((String) -> Unit)? = null
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) {
            onReplyCompleted?.invoke("")
            return
        }

        if (codeBlackActive) {
            val l = prompt.lowercase()
            if (l.contains("unlock") || l.contains("disarm") || l.contains("alpha") || l.contains("খোল")) {
                jarvisCore.unlockCodeBlack()
                onReplyCompleted?.invoke("Unlocked")
                return
            }
        }

        if (isGenerating) {
            geminiClient.cancelActive()
            isGenerating = false
        }
        textInput = ""
        voiceController.stopSpeaking()

        scope.launch {
            dao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = activeCharId,
                    role = "user",
                    text = prompt,
                    source = if (isNetworkAvailable()) "online" else "offline"
                )
            )

            // Step 0: Check Jarvis Protocol / Smart Home / Code Black commands
            val protocolReply = jarvisCore.tryHandleProtocolCommand(prompt)
            if (protocolReply != null) {
                dao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = activeCharId,
                        role = "assistant",
                        text = protocolReply,
                        source = "hermes-protocol"
                    )
                )
                jarvisCore.emitBanner(protocolReply)
                onReplyCompleted?.invoke(protocolReply)
                if (autoSpeak || forceSpeak) voiceController.speak(protocolReply, voiceProfile)
                return@launch
            }
            if (jarvisSettings.smartHomeEnabled && (prompt.contains("smart", ignoreCase = true) || prompt.contains("home assistant", ignoreCase = true) || prompt.contains("স্মার্ট লাইট"))) {
                val shReply = starkSuperpowers.executeSmartHomeCommand(prompt)
                dao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = activeCharId,
                        role = "assistant",
                        text = shReply,
                        source = "smart-home"
                    )
                )
                jarvisCore.emitBanner(shReply)
                onReplyCompleted?.invoke(shReply)
                if (autoSpeak || forceSpeak) voiceController.speak(shReply, voiceProfile)
                return@launch
            }

            // Step 1: Instant offline phone control (<50ms)
            val instantPhoneOutcome = offlinePhoneController.tryInstantOfflineCommand(prompt)
            if (instantPhoneOutcome.handled) {
                dao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = activeCharId,
                        role = "assistant",
                        text = instantPhoneOutcome.reply,
                        source = "phone-action"
                    )
                )
                jarvisCore.emitBanner(instantPhoneOutcome.reply)
                onReplyCompleted?.invoke(instantPhoneOutcome.reply)
                if (autoSpeak || forceSpeak) voiceController.speak(instantPhoneOutcome.reply, voiceProfile)
                return@launch
            }

            // Step 1b: Native Android Phone Control commands
            when (val outcome = phoneCommander.tryHandleCommand(prompt)) {
                is PhoneCommandOutcome.Executed -> {
                    dao.insertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            characterId = activeCharId,
                            role = "assistant",
                            text = outcome.replyText,
                            source = "phone-action"
                        )
                    )
                    jarvisCore.emitBanner(outcome.replyText)
                    onReplyCompleted?.invoke(outcome.replyText)
                    if (autoSpeak || forceSpeak) voiceController.speak(outcome.replyText, voiceProfile)
                    return@launch
                }
                is PhoneCommandOutcome.NeedsConfirmation -> {
                    pendingConfirm = outcome.confirmation
                    onReplyCompleted?.invoke(outcome.confirmation.title)
                    return@launch
                }
                PhoneCommandOutcome.NotAPhoneCommand -> Unit
            }

            // Step 2: Route through UnifiedCommandAndChatRouter with selected engine & Hermes persona
            isGenerating = true
            streamingReply = ""

            val history = messages.takeLast(10)
                .filter { it.text.isNotBlank() && !it.text.startsWith("Error:") }
                .map { OfflineChatTurn(isUser = it.role == "user", text = it.text) }

            val config = RouterConfig(
                route = ActiveEngineRoute.ONLINE,
                selectedOnlineModel = effectiveOnlineModel,
                persona = if (activeCharId == "zhee") BestFriendPersona.ZHEE else BestFriendPersona.ZEE,
                geminiApiKey = keyVault.getKey("gemini_api_key"),
                groqApiKey = keyVault.getKey("groq_api_key"),
                openRouterApiKey = keyVault.getKey("openrouter_api_key"),
                customProviderPreset = jarvisSettings.aiProviderPreset,
                customBaseUrl = jarvisSettings.aiBaseUrl,
                customApiKey = customProviderKeyDraft,
                hermesPersonaDirective = hermesEngine.buildPersonaDirective()
            )

            val reply = withContext(Dispatchers.IO) {
                unifiedRouter.handleUserInput(
                    userText = prompt,
                    config = config,
                    history = history,
                    onStreamingText = { partial ->
                        scope.launch(Dispatchers.Main) {
                            streamingReply = partial
                        }
                    }
                )
            }

            dao.insertMessage(
                ChatMessageEntity(
                    id = UUID.randomUUID().toString(),
                    characterId = activeCharId,
                    role = "assistant",
                    text = reply,
                    source = "online-ai"
                )
            )
            productivityStore.recordSession(
                goal = prompt.take(64),
                status = SessionTaskStatus.SUCCESS,
                summary = reply.take(160)
            )
            streamingReply = ""
            isGenerating = false
            if (jarvisSettings.appMode == ZeeAppMode.HUD) {
                jarvisCore.emitBanner(reply.take(130))
            }
            val cleanSpeak = reply.substringBefore("\n\n⚡").substringBefore("\n\n[Action")
                .removePrefix("⚠️").trim()
            onReplyCompleted?.invoke(reply)
            if ((autoSpeak || forceSpeak) && reply.isNotBlank()) {
                voiceController.speak(cleanSpeak.ifBlank { reply }, voiceProfile)
            }
        }
    }

    sendTurnAction = { prompt -> sendUserTurn(prompt) }

    val speechDialogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull().orEmpty().trim()
            if (spoken.isNotBlank()) {
                sendUserTurn(spoken)
            }
        }
    }

    fun launchSpeechInput() {
        if (!hasMic) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("bn-BD", "en-US", "bn-IN"))
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                if (activeCharId == "zhee") "ZHEE শুনছে... বলুন বস" else "ZEE শুনছে... বলুন বস"
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching {
            speechDialogLauncher.launch(intent)
        }.onFailure {
            voiceInputController.setLanguage(voiceProfile.languageTag)
            voiceInputController.startListening(continuous = jarvisSettings.alwaysOnListening)
        }
    }

    fun toggleInteractiveMic() {
        voiceController.stopSpeaking()
        if (voiceController.isMicLockedOn.value) {
            voiceController.stopListening(resumeWakeWord = false)
        }
        if (voiceInputState.isListening) {
            voiceInputController.stopListening(resumeWakeWord = true)
        } else if (!hasMic) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
            voiceInputController.setLanguage(voiceProfile.languageTag)
            voiceInputController.startListening(continuous = jarvisSettings.alwaysOnListening)
        } else {
            launchSpeechInput()
        }
    }

    fun stopAllSystems() {
        geminiClient.cancelActive()
        voiceController.emergencyStop()
        voiceInputController.stopListening(resumeWakeWord = true)
        WakeWordForegroundService.stop(context)
        isSpeaking = false
        isGenerating = false
        streamingReply = ""
        livePartialVoice = ""
    }

    launchSpeechInputAction = { launchSpeechInput() }
    voiceInputController.onFallbackToSystemDialog = { launchSpeechInput() }

    LaunchedEffect(openMicOnLaunch) {
        if (openMicOnLaunch) {
            activeTab = "companion"
            jarvisPrefs.update { it.copy(appMode = ZeeAppMode.HUD) }
            if (hasMic) toggleInteractiveMic() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) {
        WakeWordEventBus.detections.collect { event ->
            activeCharId = event.targetCharacterId
            activeTab = "companion"
            if (event.trailingCommand.isNotBlank()) {
                sendUserTurn(event.trailingCommand)
            } else {
                val greeting = if (event.targetCharacterId == "zhee") "হ্যাঁ বস, আমি শুনছি।" else "হ্যাঁ বস, বলুন কী করতে হবে?"
                voiceController.speak(greeting, voiceProfile)
            }
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val isHudScreenActive = activeTab == "companion" && jarvisSettings.appMode == ZeeAppMode.HUD
    val activeAccent = jarvisSettings.luxuryAccent.primaryHex

    ZeeLuxuryTheme(
        accent = jarvisSettings.luxuryAccent,
        isHudMode = isHudScreenActive
    ) {
        LuxuryLiquidGlassBackdrop(
            isHudMode = isHudScreenActive,
            accentColor = activeAccent,
            secondaryColor = jarvisSettings.luxuryAccent.secondaryHex
        ) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = Color(0xFF070C18),
                        drawerContentColor = Color.White
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Luxury Smoked Obsidian Header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(1.dp, activeAccent.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                                    .padding(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Color(0xFF030712), CircleShape)
                                        .border(1.4.dp, activeAccent.copy(alpha = 0.8f), CircleShape)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.zee_brand_logo),
                                        contentDescription = "ZEE AI Logo",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "ZEE // JARVIS LUXURY",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 13.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        "${activeCharId.uppercase()} • ${jarvisSettings.archetype.label} Mode",
                                        color = activeAccent,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))
                            LiquidGlassModeSwitcher(
                                selectedMode = jarvisSettings.appMode,
                                onModeChanged = { m ->
                                    jarvisPrefs.update { it.copy(appMode = m) }
                                    activeTab = "companion"
                                    scope.launch { drawerState.close() }
                                },
                                accentColor = activeAccent
                            )

                            DrawerSectionHeader("PRODUCTIVITY SUITE", activeAccent)
                            DrawerActionItem("Tasks & Todos Matrix", "☑️") {
                                showTodosDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Calendar & Reminders", "📅") {
                                showCalendarDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Knowledge Notes Vault", "📝") {
                                showNotesDialog = true; scope.launch { drawerState.close() }
                            }

                            DrawerSectionHeader("STARK SUPERPOWERS SUITE", LuxuryPalette.SolarGold)
                            DrawerActionItem("Multi-Task Brain Dump", "🧠") {
                                showBrainDumpDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Zero-Touch Air Gestures", "👋") {
                                showAirGesturesDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Airspace & Orbital Radar", "🛰️") {
                                showRadarDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("LAN Security Auditor", "🛡️") {
                                showNetworkAuditDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Optical AR Problem Solver", "📸") {
                                showOpticalSolverDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Polyglot Diplomat (15 Langs)", "🌍") {
                                showPolyglotDialog = true; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Arc Reactor Charging HUD", "⚡") {
                                jarvisCore.toggleChargingHud(true); scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Toggle Floating HUD Bubble", "🫧") {
                                if (!jarvisCore.setOverlayActive(!(jarvisSettings.overlayBubbleActive && ZeeFloatingOverlayService.isRunning))) {
                                    activeTab = "permissions_hub"
                                }
                                scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Code Black Security Lockdown", "🚨") {
                                jarvisCore.triggerCodeBlack(true); scope.launch { drawerState.close() }
                            }

                            DrawerSectionHeader("COMMANDER & STUDIOS", LuxuryPalette.IndigoLight)
                            listOf(
                                Triple("companion", "ZEE / ZHEE Main Stage", "🎙️"),
                                Triple("phone_control", "Phone Full Control", "📱"),
                                Triple("council", "AI Council (Multi-Model)", "⚖️"),
                                Triple("youtube", "YouTube Automation", "📺"),
                                Triple("filmmaker", "ZEE Cinema Studio", "🎬"),
                                Triple("research", "Research & Trend Radar", "📡"),
                                Triple("reader", "Universal File Reader", "📂"),
                                Triple("memory", "Long-Term Room Memory", "💾")
                            ).forEach { (id, title, emoji) ->
                                DrawerActionItem(title, emoji, selected = activeTab == id) {
                                    activeTab = id; scope.launch { drawerState.close() }
                                }
                            }

                            DrawerSectionHeader("AUTONOMOUS CORE & SYSTEM", LuxuryPalette.EmeraldNeon)
                            DrawerActionItem("JARVIS Core Settings & Themes", "🌌", selected = activeTab == "jarvis_settings") {
                                activeTab = "jarvis_settings"; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("Permissions Hub (8 Bridges)", "🔐", selected = activeTab == "permissions_hub") {
                                activeTab = "permissions_hub"; scope.launch { drawerState.close() }
                            }
                            DrawerActionItem("API Keys & Online Voice", "🔑", selected = activeTab == "settings") {
                                activeTab = "settings"; scope.launch { drawerState.close() }
                            }

                            if (sessionEntries.isNotEmpty()) {
                                DrawerSectionHeader("RECENT MISSION SESSIONS", LuxuryPalette.MutedSlate)
                                sessionEntries.take(6).forEach { s ->
                                    DrawerActionItem(s.goal.take(30), "📜") {
                                        selectedSessionDetail = s; scope.launch { drawerState.close() }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            ) {
                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (!isHudScreenActive) {
                            NavigationBar(
                                containerColor = Color(0xFF070C18).copy(alpha = 0.94f),
                                tonalElevation = 0.dp
                            ) {
                                listOf(
                                    Triple("companion", "ZEE HUD", "⚡"),
                                    Triple("phone_control", "Phone", "📱"),
                                    Triple("jarvis_settings", "Core", "🌌"),
                                    Triple("settings", "Keys", "🔑"),
                                    Triple("permissions_hub", "Perms", "🔐")
                                ).forEach { (id, label, iconEmoji) ->
                                    NavigationBarItem(
                                        selected = activeTab == id,
                                        onClick = { activeTab = id },
                                        label = {
                                            Text(
                                                label,
                                                fontSize = 10.5.sp,
                                                fontWeight = if (activeTab == id) FontWeight.Bold else FontWeight.Normal,
                                                color = if (activeTab == id) activeAccent else LuxuryPalette.MutedSlate
                                            )
                                        },
                                        icon = {
                                            Text(iconEmoji, fontSize = if (activeTab == id) 18.sp else 16.sp)
                                        }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = if (isHudScreenActive) 8.dp else 14.dp, vertical = 6.dp)
                    ) {
                        // Sleek Smoked Obsidian Top Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF091020).copy(alpha = 0.86f))
                                .border(1.dp, activeAccent.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = { scope.launch { drawerState.open() } },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.Menu, contentDescription = "Menu", tint = Color.White)
                                }
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(Color(0xFF030712), CircleShape)
                                        .border(1.2.dp, activeAccent.copy(alpha = 0.78f), CircleShape)
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.zee_brand_logo),
                                        contentDescription = "ZEE AI Emblem",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("ZEE AI", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, letterSpacing = 0.8.sp)
                                        PulsingServiceDot(enabled = isAccessibilityActive || isAccessibilityConnected)
                                    }
                                    Text(
                                        if (isNetworkAvailable()) "Online • $activeGeminiFlash"
                                        else "Offline (Phone Controls Ready)",
                                        color = if (isNetworkAvailable()) LuxuryPalette.EmeraldNeon else LuxuryPalette.SolarGold,
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                IconButton(
                                    onClick = { activeTab = if (activeTab == "jarvis_settings") "companion" else "jarvis_settings" },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Rounded.Tune, contentDescription = "Core Settings", tint = activeAccent, modifier = Modifier.size(19.dp))
                                }
                                Surface(
                                    onClick = { stopAllSystems() },
                                    shape = RoundedCornerShape(10.dp),
                                    color = LuxuryPalette.CrimsonAlert.copy(alpha = 0.88f)
                                ) {
                                    Text(
                                        "STOP",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // Floating Toast Banner (for HUD spoken feedback, Hermes, Macros, etc.)
                        AnimatedVisibility(visible = toastBanner != null) {
                            toastBanner?.let { msg ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF0E1A30).copy(alpha = 0.94f),
                                    border = BorderStroke(1.dp, activeAccent.copy(alpha = 0.55f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                ) {
                                    Text(
                                        text = msg,
                                        color = Color.White,
                                        fontSize = 11.5.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                    )
                                }
                            }
                        }

                        // Liquid Glass Segmented Mode Switcher when on Main Stage
                        if (activeTab == "companion") {
                            Spacer(Modifier.height(4.dp))
                            LiquidGlassModeSwitcher(
                                selectedMode = jarvisSettings.appMode,
                                onModeChanged = { m -> jarvisPrefs.update { it.copy(appMode = m) } },
                                isBusy = isGenerating,
                                accentColor = activeAccent
                            )
                        } else {
                            Spacer(Modifier.height(8.dp))
                        }

                        when (activeTab) {
                            "companion" -> {
                                when (jarvisSettings.appMode) {
                                    ZeeAppMode.HUD -> {
                                        ZeeHologramHud(
                                            phase = hudPhase,
                                            hudStyle = jarvisSettings.hudStyle,
                                            accentPrimary = activeAccent,
                                            statusMessage = hudStatusLabel,
                                            activeModelLabel = effectiveOnlineModel,
                                            activeRouteLabel = if (isNetworkAvailable()) "ONLINE" else "OFFLINE",
                                            micRms = voiceInputState.rmsLevel,
                                            alwaysOnEnabled = jarvisSettings.alwaysOnListening,
                                            airGesturesEnabled = airGesturesEnabled,
                                            isRecordingMacro = isRecordingMacro,
                                            overlayActive = jarvisSettings.overlayBubbleActive && ZeeFloatingOverlayService.isRunning,
                                            sentryArmed = sentryStatus.isArmed,
                                            activeGestureBanner = activeGestureBanner,
                                            proactiveBanner = proactiveBanner,
                                            onToggleListening = { toggleInteractiveMic() },
                                            onStopAll = { stopAllSystems() },
                                            onToggleAlwaysOn = {
                                                val next = !jarvisSettings.alwaysOnListening
                                                jarvisPrefs.update { it.copy(alwaysOnListening = next) }
                                                jarvisCore.emitBanner(if (next) "Always-On Listening Armed for Boss" else "Always-On Paused")
                                            },
                                            onOpenBrainDump = { showBrainDumpDialog = true },
                                            onTriggerHermesEvolve = {
                                                val memTexts = memories.map { it.content }
                                                val msg = jarvisCore.runHermesEvolution(memTexts)
                                                if (autoSpeak) voiceController.speak(msg, voiceProfile)
                                            },
                                            onOpenAirGestures = { showAirGesturesDialog = true },
                                            onToggleHudStyle = {
                                                val nextStyle = if (jarvisSettings.hudStyle == HudVisualStyle.ARC_REACTOR) {
                                                    HudVisualStyle.HOLOGRAM_SUIT
                                                } else {
                                                    HudVisualStyle.ARC_REACTOR
                                                }
                                                jarvisPrefs.update { it.copy(hudStyle = nextStyle) }
                                            },
                                            onToggleMacro = { jarvisCore.toggleMacroRecorder() },
                                            onOpenAirspaceRadar = { showRadarDialog = true },
                                            onOpenNetworkAudit = { showNetworkAuditDialog = true },
                                            onOpenOpticalSolver = { showOpticalSolverDialog = true },
                                            onOpenPolyglot = { showPolyglotDialog = true },
                                            onOpenChargingHud = { jarvisCore.toggleChargingHud(true) },
                                            onToggleOverlay = {
                                                val wantOn = !(jarvisSettings.overlayBubbleActive && ZeeFloatingOverlayService.isRunning)
                                                if (!jarvisCore.setOverlayActive(wantOn)) {
                                                    activeTab = "permissions_hub"
                                                }
                                            },
                                            onTriggerCodeBlack = { jarvisCore.triggerCodeBlack(true) },
                                            onAcceptProactive = { evt ->
                                                starkSuperpowers.dismissSuggestion()
                                                evt.actionCommand?.let { cmd -> sendUserTurn(cmd) } ?: launchSpeechInput()
                                            },
                                            onDismissProactive = { starkSuperpowers.dismissSuggestion() },
                                            modifier = Modifier.weight(1f).fillMaxWidth()
                                        )
                                    }

                                    ZeeAppMode.AGENT, ZeeAppMode.CHAT -> {
                                        // ZEE / ZHEE Persona & Voice Controls
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            LuxuryGlassPill("⚔️ ZEE (Male)", activeAccent, isSelected = activeCharId == "zee") { activeCharId = "zee" }
                                            LuxuryGlassPill("🌸 ZHEE (Female)", LuxuryPalette.QuantumViolet, isSelected = activeCharId == "zhee") { activeCharId = "zhee" }
                                            LuxuryGlassPill(
                                                if (wakeWordRunning) "🎙️ Wake: ON" else "🎙️ Wake: OFF",
                                                LuxuryPalette.EmeraldNeon,
                                                isSelected = wakeWordRunning
                                            ) {
                                                if (wakeWordRunning) WakeWordForegroundService.stop(context)
                                                else if (hasMic) WakeWordForegroundService.start(context)
                                                else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                            LuxuryGlassPill(
                                                if (autoSpeak) "🔊 TTS: ON" else "🔇 Muted",
                                                LuxuryPalette.SkyAzure,
                                                isSelected = autoSpeak
                                            ) {
                                                autoSpeak = !autoSpeak
                                                if (autoSpeak) {
                                                    voiceController.speak(
                                                        if (activeCharId == "zhee") "হ্যাঁ বস, আমি শুনছি।" else "ভয়েস চালু হয়েছে বস, হুকুম করুন কী করব?",
                                                        voiceProfile
                                                    )
                                                }
                                            }
                                        }

                                        if (jarvisSettings.appMode == ZeeAppMode.CHAT) {
                                            Spacer(Modifier.height(4.dp))
                                            AutoOnlineEngineBanner(
                                                isInternetConnected = isNetworkAvailable(),
                                                activeModelName = effectiveOnlineModel,
                                                hasApiKey = effectiveProviderApiKey.isNotBlank(),
                                                onOpenKeysSettings = { activeTab = "settings" }
                                            )
                                        } else {
                                            // Agent Mode Accessibility Alert + Suggestion Chips
                                            if (!isAccessibilityActive && !isAccessibilityConnected) {
                                                Spacer(Modifier.height(4.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = LuxuryPalette.SolarGold.copy(alpha = 0.14f),
                                                    border = BorderStroke(1.dp, LuxuryPalette.SolarGold.copy(alpha = 0.5f)),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { activeTab = "permissions_hub" }
                                                ) {
                                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Rounded.WarningAmber, null, tint = LuxuryPalette.SolarGold, modifier = Modifier.size(18.dp))
                                                        Spacer(Modifier.width(8.dp))
                                                        Text(
                                                            "Enable Accessibility in Permissions Hub for full device automation, Boss.",
                                                            color = LuxuryPalette.ChampagneGold,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                }
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(
                                                    "Turn on flashlight" to "🔦 Torch",
                                                    "Study mode" to "📚 Study Protocol",
                                                    "Open YouTube" to "📺 YouTube",
                                                    "Read my screen" to "👁️ Read Screen"
                                                ).forEach { (cmd, chip) ->
                                                    LuxuryGlassPill(chip, activeAccent) { sendUserTurn(cmd) }
                                                }
                                                LuxuryGlassPill("🧠 Brain Dump", LuxuryPalette.IndigoLight) { showBrainDumpDialog = true }
                                                LuxuryGlassPill("🛡️ LAN Audit", LuxuryPalette.ElectricCyan) { showNetworkAuditDialog = true }
                                            }
                                        }

                                        if (livePartialVoice.isNotBlank()) {
                                            Text(
                                                "Hearing: $livePartialVoice",
                                                color = activeAccent,
                                                fontSize = 12.sp,
                                                modifier = Modifier.padding(vertical = 4.dp)
                                            )
                                        }

                                        Spacer(Modifier.height(4.dp))
                                        // Luxury Smoked-Crystal Transcript Stream
                                        LazyColumn(
                                            modifier = Modifier.weight(1f).fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            items(messages, key = { it.id }) { msg ->
                                                val isUser = msg.role == "user"
                                                LuxuryChatBubble(
                                                    text = msg.text,
                                                    isUser = isUser,
                                                    characterTag = msg.characterId.uppercase(),
                                                    sourceTag = msg.source,
                                                    accentColor = activeAccent,
                                                    onSpeakClick = if (!isUser) {
                                                        { voiceController.speak(msg.text, voiceProfile) }
                                                    } else null
                                                )
                                            }
                                            if (streamingReply.isNotBlank()) {
                                                item {
                                                    LuxuryChatBubble(
                                                        text = streamingReply,
                                                        isUser = false,
                                                        characterTag = activeCharId.uppercase(),
                                                        sourceTag = "streaming",
                                                        stepBadge = "NEURAL STREAMING...",
                                                        accentColor = activeAccent
                                                    )
                                                }
                                            }
                                        }

                                        if (voiceInputState.isListening || voiceInputState.partialTranscript.isNotBlank() || isMicLockedOn) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF030712), RoundedCornerShape(10.dp))
                                                    .border(
                                                        1.dp,
                                                        if (voiceInputState.isListening) LuxuryPalette.EmeraldNeon else LuxuryPalette.CrimsonAlert.copy(alpha = 0.5f),
                                                        RoundedCornerShape(10.dp)
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                                                    PulsingServiceDot(enabled = voiceInputState.isListening)
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = if (voiceInputState.isListening) voiceInputState.statusHint else {
                                                            when (voiceState) {
                                                                com.zeeai.companion.voice.VoiceState.HEARING -> "MIC: Hearing speech..."
                                                                com.zeeai.companion.voice.VoiceState.SPEAKING -> "MIC: ZEE is replying..."
                                                                else -> "MIC LOCKED ON (Listening...)"
                                                            }
                                                        },
                                                        color = if (voiceInputState.isListening) LuxuryPalette.EmeraldNeon else Color(0xFFF87171),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                val transcriptToShow = voiceInputState.partialTranscript.ifBlank { livePartialVoice }
                                                if (transcriptToShow.isNotBlank()) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(transcriptToShow.takeLast(32), color = activeAccent, fontSize = 11.sp, maxLines = 1)
                                                }
                                            }
                                        }

                                        voiceInputState.lastError?.let { err ->
                                            Text("⚠️ $err", color = Color(0xFFFCA5A5), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                                        }

                                        // Frosted Luxury Input Composer
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(Color(0xFF0A1120).copy(alpha = 0.92f))
                                                .border(1.dp, activeAccent.copy(alpha = 0.30f), RoundedCornerShape(24.dp))
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            IconButton(
                                                onClick = { toggleInteractiveMic() },
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .background(
                                                        if (voiceInputState.isListening) LuxuryPalette.CrimsonAlert else activeAccent.copy(alpha = 0.22f),
                                                        CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = if (voiceInputState.isListening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                                    contentDescription = "Mic",
                                                    tint = if (voiceInputState.isListening) Color.White else activeAccent,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            OutlinedTextField(
                                                value = textInput,
                                                onValueChange = { textInput = it },
                                                modifier = Modifier.weight(1f),
                                                placeholder = { Text("Speak or command Boss...", fontSize = 12.5.sp, color = LuxuryPalette.MutedSlate) },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color.Transparent,
                                                    unfocusedBorderColor = Color.Transparent,
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )

                                            IconButton(
                                                onClick = { sendUserTurn(textInput) },
                                                enabled = textInput.isNotBlank() && !isGenerating,
                                                modifier = Modifier
                                                    .size(38.dp)
                                                    .background(
                                                        if (textInput.isNotBlank()) activeAccent else Color.White.copy(alpha = 0.08f),
                                                        CircleShape
                                                    )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.ArrowUpward,
                                                    contentDescription = "Send",
                                                    tint = if (textInput.isNotBlank()) LuxuryPalette.ObsidianVoid else LuxuryPalette.MutedSlate,
                                                    modifier = Modifier.size(19.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            "jarvis_settings" -> AutonomousCoreSettingsScreen(
                                jarvisCore = jarvisCore,
                                customApiKey = customProviderKeyDraft,
                                onSaveCustomApiKey = { newKey ->
                                    customProviderKeyDraft = newKey
                                    keyVault.saveKey("custom_provider_api_key", newKey.trim())
                                },
                                memorySnippets = memories.map { it.content },
                                onTestVoice = {
                                    voiceController.speak(
                                        if (activeCharId == "zhee") "হ্যালো বস! আমি জী, আপনার সব হুকুম শোনার জন্য প্রস্তুত।"
                                        else "ইয়েস বস! আমি জী এআই, সব হার্ডওয়্যার এবং নিউরাল ব্রেন অনলাইন আছে।",
                                        voiceProfile
                                    )
                                },
                                onSpeakFeedback = { line ->
                                    if (autoSpeak) voiceController.speak(line, voiceProfile)
                                },
                                onOpenPermissionsHub = { activeTab = "permissions_hub" },
                                onOpenLegacyKeyVault = { activeTab = "settings" },
                                onOpenBrainDump = { showBrainDumpDialog = true },
                                onOpenRadar = { showRadarDialog = true },
                                onOpenNetworkAudit = { showNetworkAuditDialog = true },
                                onOpenOpticalSolver = { showOpticalSolverDialog = true },
                                onOpenPolyglot = { showPolyglotDialog = true },
                                onOpenChargingHud = { jarvisCore.toggleChargingHud(true) },
                                onTriggerCodeBlack = { jarvisCore.triggerCodeBlack(true) },
                                onBack = { activeTab = "companion" }
                            )

                            "permissions_hub" -> PermissionsHubScreen(
                                jarvisCore = jarvisCore,
                                onRequestCoreRuntimePerms = { requestAllRuntimePermissions() },
                                onBack = { activeTab = "companion" }
                            )


                        "phone_control" -> PhoneControlScreen(
                            context = context,
                            phoneCommander = phoneCommander,
                            offlinePhoneController = offlinePhoneController,
                            voiceController = voiceController,
                            voiceProfile = voiceProfile,
                            isNetworkAvailable = { isNetworkAvailable() },
                            isAccessibilityActive = isAccessibilityActive,
                            isAccessibilityConnected = isAccessibilityConnected,
                            hasMic = hasMic,
                            onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                            activeCharId = activeCharId,
                            onCharChanged = { activeCharId = it },
                            onGeneralPrompt = { sendUserTurn(it, forceSpeak = true) },
                            onGeneralPromptWithReply = { prompt, cb ->
                                sendUserTurn(prompt, forceSpeak = true, onReplyCompleted = cb)
                            },
                            onShowConfirmation = { pendingConfirm = it }
                        )

                        "youtube" -> YouTubeStudioScreen(
                            activeCharId = activeCharId,
                            database = database,
                            keyVault = keyVault,
                            geminiClient = geminiClient,
                            isOnline = { isNetworkAvailable() }
                        )

                        "filmmaker" -> FilmmakerStudioScreen(
                            activeCharId = activeCharId,
                            database = database,
                            keyVault = keyVault,
                            geminiClient = geminiClient,
                            isOnline = { isNetworkAvailable() }
                        )

                        "research" -> ResearchAndTrendsScreen(
                            activeCharId = activeCharId,
                            database = database,
                            keyVault = keyVault,
                            geminiClient = geminiClient,
                            isOnline = { isNetworkAvailable() }
                        )

                        "council" -> AICouncilScreen(
                            keyVault = keyVault,
                            geminiClient = geminiClient,
                            isOnline = { isNetworkAvailable() }
                        )

                        "reader" -> UniversalReaderScreen(
                            activeCharId = activeCharId,
                            keyVault = keyVault,
                            geminiClient = geminiClient,
                            isOnline = { isNetworkAvailable() }
                        )

                    "memory" -> {
                        var memInput by remember { mutableStateOf("") }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Room DB Long-Term Memory (Offline Ready)", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = memInput,
                                    onValueChange = { memInput = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Add memory for ZEE/ZHEE...") },
                                    singleLine = true
                                )
                                Button(onClick = {
                                    if (memInput.isBlank()) return@Button
                                    val txt = memInput.trim()
                                    memInput = ""
                                    scope.launch {
                                        dao.upsertMemory(
                                            MemoryEntity(
                                                id = UUID.randomUUID().toString(),
                                                owner = "shared",
                                                category = "preference",
                                                content = txt
                                            )
                                        )
                                    }
                                }) { Text("Save") }
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(memories, key = { it.id }) { m ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(CardSurface, RoundedCornerShape(12.dp))
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(m.content, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                        TextButton(onClick = { scope.launch { dao.deleteMemory(m.id) } }) {
                                            Text("Delete", color = Color(0xFFF87171), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    "settings" -> {
                        var geminiInput by remember { mutableStateOf(keyVault.getKey("gemini_api_key")) }
                        var statusMsg by remember { mutableStateOf(keyVault.getKey("gemini_status_msg")) }
                        var isVerified by remember { mutableStateOf(keyVault.getKey("gemini_api_status") == "verified") }
                        var isValidating by remember { mutableStateOf(false) }
                        var lastSyncedKey by remember { mutableStateOf(if (isVerified) geminiInput.trim() else "") }

                        val runLiveValidation: (String) -> Unit = { keyToValidate ->
                            val clean = keyToValidate.trim()
                            if (clean.isNotBlank()) {
                                keyVault.saveKey("gemini_api_key", clean)
                                isValidating = true
                                statusMsg = "Detecting latest Gemini models from your API key..."
                                scope.launch {
                                    val res = geminiClient.discoverModels(clean)
                                    isValidating = false
                                    res.onSuccess { disc ->
                                        val bestFlash = disc.bestFlashModel.removePrefix("models/").trim()
                                        val bestPro = disc.bestProModel.removePrefix("models/").trim()
                                        val liveIds = disc.models.map { it.id.removePrefix("models/").trim() }.distinct()

                                        isVerified = true
                                        lastSyncedKey = clean
                                        activeGeminiFlash = bestFlash
                                        activeGeminiPro = bestPro
                                        autoDiscoveredGeminiModels = liveIds
                                        statusMsg = "Auto-Synced • ${liveIds.size} latest models active ($bestFlash)"

                                        keyVault.saveKey("gemini_api_key", clean)
                                        keyVault.saveKey("gemini_api_status", "verified")
                                        keyVault.saveKey("gemini_status_msg", statusMsg)
                                        keyVault.saveKey("gemini_discovered_models", liveIds.joinToString(","))
                                        keyVault.saveKey("gemini_flash_model", bestFlash)
                                        keyVault.saveKey("gemini_pro_model", bestPro)
                                        if (jarvisPrefs.state.value.aiProviderPreset == "gemini") {
                                            jarvisPrefs.update { it.copy(aiCustomModel = bestFlash) }
                                        }
                                        voiceController.setApiKeys(clean, keyVault.getKey("elevenlabs_api_key"), isNetworkAvailable())
                                    }.onFailure { err ->
                                        val diag = geminiClient.testAiConnection(
                                            geminiKey = clean,
                                            selectedModel = ""
                                        )
                                        if (diag.contains("Connected") || diag.contains("Ready")) {
                                            val autoPicked = geminiClient.cachedBestFlash
                                            isVerified = true
                                            lastSyncedKey = clean
                                            activeGeminiFlash = autoPicked
                                            activeGeminiPro = geminiClient.cachedBestPro
                                            autoDiscoveredGeminiModels = geminiClient.cachedDiscoveredModels
                                                .ifEmpty { listOf(autoPicked) }
                                            statusMsg = diag
                                            keyVault.saveKey("gemini_api_status", "verified")
                                            keyVault.saveKey("gemini_status_msg", statusMsg)
                                            keyVault.saveKey("gemini_discovered_models", autoDiscoveredGeminiModels.joinToString(","))
                                            keyVault.saveKey("gemini_flash_model", activeGeminiFlash)
                                            keyVault.saveKey("gemini_pro_model", activeGeminiPro)
                                            if (jarvisPrefs.state.value.aiProviderPreset == "gemini") {
                                                jarvisPrefs.update { it.copy(aiCustomModel = activeGeminiFlash) }
                                            }
                                            voiceController.setApiKeys(clean, keyVault.getKey("elevenlabs_api_key"), isNetworkAvailable())
                                        } else {
                                            isVerified = false
                                            statusMsg = "Validation failed: ${err.message?.take(120) ?: diag}"
                                            keyVault.saveKey("gemini_api_status", "invalid")
                                            keyVault.saveKey("gemini_status_msg", statusMsg)
                                        }
                                    }
                                }
                            }
                        }

                        // Auto-detect & sync models automatically as soon as an AIza... API key is typed or pasted
                        LaunchedEffect(geminiInput) {
                            val clean = geminiInput.trim()
                            if (clean.startsWith("AIza") && clean.length >= 35 && clean != lastSyncedKey && !isValidating) {
                                kotlinx.coroutines.delay(450L)
                                val currentClean = geminiInput.trim()
                                if (currentClean == clean && currentClean != lastSyncedKey) {
                                    runLiveValidation(currentClean)
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CardSurface, RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                "Google AI Studio API Key (Auto Model Sync)",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Google AI Studio API key (AIza...) বসালেই অটোমেটিক লেটেস্ট কাজের মডেলগুলো যুক্ত হয়ে যাবে — ম্যানুয়াল সিলেক্ট করার দরকার নেই।",
                                color = Silver,
                                fontSize = 12.sp
                            )

                            OutlinedTextField(
                                value = geminiInput,
                                onValueChange = { newVal ->
                                    geminiInput = newVal
                                    val clean = newVal.trim()
                                    if (clean.isNotBlank()) {
                                        keyVault.saveKey("gemini_api_key", clean)
                                    } else {
                                        lastSyncedKey = ""
                                    }
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                placeholder = { Text("AIzaSy...") },
                                label = { Text("Google AI Studio API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = { runLiveValidation(geminiInput) },
                                    enabled = !isValidating && geminiInput.isNotBlank()
                                ) {
                                    if (isValidating) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Syncing...")
                                    } else {
                                        Text(if (isVerified) "Re-Sync Latest" else "Detect & Connect")
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            val clean = geminiInput.trim()
                                            if (clean.isNotBlank()) {
                                                keyVault.saveKey("gemini_api_key", clean)
                                            }
                                            statusMsg = "Testing live AI connection..."
                                            val diag = geminiClient.testAiConnection(
                                                geminiKey = clean,
                                                selectedModel = activeGeminiFlash,
                                                groqKey = keyVault.getKey("groq_api_key"),
                                                openRouterKey = keyVault.getKey("openrouter_api_key")
                                            )
                                            statusMsg = diag
                                            if (geminiClient.cachedBestFlash.isNotBlank()) {
                                                activeGeminiFlash = geminiClient.cachedBestFlash
                                                activeGeminiPro = geminiClient.cachedBestPro
                                                if (geminiClient.cachedDiscoveredModels.isNotEmpty()) {
                                                    autoDiscoveredGeminiModels = geminiClient.cachedDiscoveredModels
                                                    keyVault.saveKey("gemini_discovered_models", autoDiscoveredGeminiModels.joinToString(","))
                                                }
                                                keyVault.saveKey("gemini_flash_model", activeGeminiFlash)
                                                keyVault.saveKey("gemini_pro_model", activeGeminiPro)
                                            }
                                            if (diag.contains("Connected") || diag.contains("Ready")) {
                                                isVerified = true
                                                keyVault.saveKey("gemini_api_status", "verified")
                                            }
                                            keyVault.saveKey("gemini_status_msg", statusMsg)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                                ) {
                                    Text("Test")
                                }

                                if (geminiInput.isNotBlank() || isVerified) {
                                    TextButton(onClick = {
                                        geminiInput = ""
                                        lastSyncedKey = ""
                                        isVerified = false
                                        statusMsg = ""
                                        autoDiscoveredGeminiModels = emptyList()
                                        activeGeminiFlash = GeminiNativeClient.DEFAULT_FLASH_MODEL
                                        activeGeminiPro = GeminiNativeClient.DEFAULT_PRO_MODEL
                                        geminiClient.clearDiscoveryCache()
                                        keyVault.removeKey("gemini_api_key")
                                        keyVault.removeKey("gemini_api_status")
                                        keyVault.removeKey("gemini_status_msg")
                                        keyVault.removeKey("gemini_discovered_models")
                                        keyVault.removeKey("gemini_flash_model")
                                        keyVault.removeKey("gemini_pro_model")
                                    }) {
                                        Text("Clear", color = Color(0xFFEF4444))
                                    }
                                }
                            }

                            if (statusMsg.isNotBlank()) {
                                val badgeBg = if (isVerified) Color(0xFF064E3B) else Color(0xFF7F1D1D)
                                val badgeFg = if (isVerified) Color(0xFF34D399) else Color(0xFFFCA5A5)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(badgeBg, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = (if (isVerified) "● " else "▲ ") + statusMsg,
                                        color = badgeFg,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            if (isVerified || autoDiscoveredGeminiModels.isNotEmpty()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                                        .border(1.dp, Cyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "✨ Auto-Selected Latest Models (Zero Manual Setup)",
                                        color = Cyan,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text("⚡ Fast & Phone Control: $activeGeminiFlash", color = Color.White, fontSize = 11.5.sp)
                                    Text("🧠 Pro Studio & Research: $activeGeminiPro", color = Color(0xFFC4B5FD), fontSize = 11.5.sp)
                                    if (autoDiscoveredGeminiModels.isNotEmpty()) {
                                        Text(
                                            "Auto-failover pool: ${autoDiscoveredGeminiModels.take(6).joinToString(", ")}",
                                            color = Silver.copy(alpha = 0.8f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            val isWakeRunning by WakeWordEventBus.isRunning.collectAsState()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isWakeRunning) Color(0xFF064E3B) else Color(0xFF0F172A), RoundedCornerShape(10.dp))
                                    .border(1.dp, if (isWakeRunning) Color(0xFF34D399) else Color(0xFF334155), RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("24/7 Background Wake Word", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(
                                        if (isWakeRunning) "● Active • Hey ZEE / Hey ZHEE শোনা চলছে"
                                        else "○ Off • ব্যাকগ্রাউন্ডে ভয়েস চালু রাখতে Enable করুন",
                                        color = if (isWakeRunning) Color(0xFF34D399) else Silver,
                                        fontSize = 10.5.sp
                                    )
                                }
                                Button(
                                    onClick = {
                                        if (isWakeRunning) {
                                            WakeWordForegroundService.stop(context)
                                        } else if (!hasMic) {
                                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        } else {
                                            WakeWordForegroundService.start(context)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isWakeRunning) Color(0xFFDC2626) else ElectricBlue
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(if (isWakeRunning) "Stop" else "Enable", fontSize = 11.sp)
                                }
                            }

                            HorizontalDivider(color = Color(0xFF1E293B))

                            Text("Free Backup AI Providers (Groq & OpenRouter)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("If Gemini runs out of free quota (429), ZEE AI automatically fails over to Groq or OpenRouter seamlessly.", color = Silver, fontSize = 11.sp)

                            var groqInput by remember { mutableStateOf(keyVault.getKey("groq_api_key")) }
                            OutlinedTextField(
                                value = groqInput,
                                onValueChange = {
                                    groqInput = it
                                    keyVault.saveKey("groq_api_key", it.trim())
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                placeholder = { Text("gsk_...") },
                                label = { Text("Groq API Key (Free llama-3.3-70b-versatile)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            var openRouterInput by remember { mutableStateOf(keyVault.getKey("openrouter_api_key")) }
                            OutlinedTextField(
                                value = openRouterInput,
                                onValueChange = {
                                    openRouterInput = it
                                    keyVault.saveKey("openrouter_api_key", it.trim())
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                placeholder = { Text("sk-or-...") },
                                label = { Text("OpenRouter API Key (Free models)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            HorizontalDivider(color = Color(0xFF1E293B))

                            Text("100% Online Human Voice Studio (ElevenLabs & Gemini 2.5)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("Pure online studio human voices for ZEE (Male) & ZHEE (Female). Cascade: ElevenLabs -> Gemini 2.5 Studio Audio -> Cloud Neural Voice.", color = Silver, fontSize = 11.sp)

                            var elevenLabsInput by remember { mutableStateOf(keyVault.getKey("elevenlabs_api_key")) }
                            var selectedElevenMale by remember {
                                mutableStateOf(keyVault.getKey(OnlineHumanVoiceCatalog.KEY_ELEVEN_MALE_ID).ifBlank { OnlineHumanVoiceCatalog.DEFAULT_ELEVEN_MALE_ID })
                            }
                            var selectedElevenFemale by remember {
                                mutableStateOf(keyVault.getKey(OnlineHumanVoiceCatalog.KEY_ELEVEN_FEMALE_ID).ifBlank { OnlineHumanVoiceCatalog.DEFAULT_ELEVEN_FEMALE_ID })
                            }
                            var selectedGeminiMale by remember {
                                mutableStateOf(keyVault.getKey(OnlineHumanVoiceCatalog.KEY_GEMINI_MALE_VOICE).ifBlank { OnlineHumanVoiceCatalog.DEFAULT_GEMINI_MALE_VOICE })
                            }
                            var selectedGeminiFemale by remember {
                                mutableStateOf(keyVault.getKey(OnlineHumanVoiceCatalog.KEY_GEMINI_FEMALE_VOICE).ifBlank { OnlineHumanVoiceCatalog.DEFAULT_GEMINI_FEMALE_VOICE })
                            }
                            var syncedAccountVoices by remember { mutableStateOf<List<com.zeeai.companion.voice.ElevenLabsVoicePreset>>(emptyList()) }
                            var voiceStudioMsg by remember { mutableStateOf("") }

                            fun persistAndSyncVoiceConfig() {
                                val cfg = com.zeeai.companion.voice.OnlineVoiceConfig(
                                    elevenLabsMaleId = selectedElevenMale,
                                    elevenLabsFemaleId = selectedElevenFemale,
                                    geminiMaleVoice = selectedGeminiMale,
                                    geminiFemaleVoice = selectedGeminiFemale
                                )
                                keyVault.saveKey(OnlineHumanVoiceCatalog.KEY_ELEVEN_MALE_ID, selectedElevenMale)
                                keyVault.saveKey(OnlineHumanVoiceCatalog.KEY_ELEVEN_FEMALE_ID, selectedElevenFemale)
                                keyVault.saveKey(OnlineHumanVoiceCatalog.KEY_GEMINI_MALE_VOICE, selectedGeminiMale)
                                keyVault.saveKey(OnlineHumanVoiceCatalog.KEY_GEMINI_FEMALE_VOICE, selectedGeminiFemale)
                                voiceController.updateOnlineVoiceConfig(cfg)
                            }

                            OutlinedTextField(
                                value = elevenLabsInput,
                                onValueChange = {
                                    elevenLabsInput = it
                                    keyVault.saveKey("elevenlabs_api_key", it.trim())
                                    voiceController.setApiKeys(activeGeminiKey, it.trim(), isNetworkAvailable())
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                placeholder = { Text("xi-api-key...") },
                                label = { Text("ElevenLabs API Key (Cinema-Grade Multilingual v2)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Text("ZEE (Male) — ElevenLabs Cinema Voices", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            val malePool = (OnlineHumanVoiceCatalog.elevenLabsMaleVoices + syncedAccountVoices.filter { !it.isFemale }).distinctBy { it.id }
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                malePool.forEach { preset ->
                                    FilterChip(
                                        selected = selectedElevenMale == preset.id,
                                        onClick = {
                                            selectedElevenMale = preset.id
                                            persistAndSyncVoiceConfig()
                                        },
                                        label = { Text("${preset.name}", fontSize = 11.sp) }
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = selectedElevenMale,
                                onValueChange = {
                                    selectedElevenMale = it.trim()
                                    persistAndSyncVoiceConfig()
                                },
                                label = { Text("Custom ZEE Male ElevenLabs Voice ID") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Text("ZHEE (Female) — ElevenLabs Cinema Voices", color = LuxuryPalette.QuantumViolet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            val femalePool = (OnlineHumanVoiceCatalog.elevenLabsFemaleVoices + syncedAccountVoices.filter { it.isFemale }).distinctBy { it.id }
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                femalePool.forEach { preset ->
                                    FilterChip(
                                        selected = selectedElevenFemale == preset.id,
                                        onClick = {
                                            selectedElevenFemale = preset.id
                                            persistAndSyncVoiceConfig()
                                        },
                                        label = { Text("${preset.name}", fontSize = 11.sp) }
                                    )
                                }
                            }

                            if (elevenLabsInput.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        voiceStudioMsg = "Fetching your ElevenLabs account voices..."
                                        scope.launch {
                                            val res = voiceController.humanVoiceEngine.fetchElevenLabsAccountVoices(elevenLabsInput)
                                            res.onSuccess { list ->
                                                syncedAccountVoices = list
                                                voiceStudioMsg = "Synced ${list.size} voices from ElevenLabs account!"
                                            }.onFailure { err ->
                                                voiceStudioMsg = "Sync failed: ${err.message}"
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("🔄 Sync My ElevenLabs Library Voices", fontSize = 11.sp)
                                }
                            }

                            Text("Gemini 2.5 Studio Human Voices (Uses Gemini Key)", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OnlineHumanVoiceCatalog.geminiMaleVoices.forEach { gv ->
                                    FilterChip(
                                        selected = selectedGeminiMale == gv.name,
                                        onClick = {
                                            selectedGeminiMale = gv.name
                                            persistAndSyncVoiceConfig()
                                        },
                                        label = { Text("♂ ${gv.name} (${gv.subtitle})", fontSize = 10.5.sp) }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OnlineHumanVoiceCatalog.geminiFemaleVoices.forEach { gv ->
                                    FilterChip(
                                        selected = selectedGeminiFemale == gv.name,
                                        onClick = {
                                            selectedGeminiFemale = gv.name
                                            persistAndSyncVoiceConfig()
                                        },
                                        label = { Text("♀ ${gv.name} (${gv.subtitle})", fontSize = 10.5.sp) }
                                    )
                                }
                            }

                            val isVoiceOnline = isNetworkAvailable()
                            val voiceStatusText = when {
                                !isVoiceOnline -> "🌐 Connect Internet — 100% Online Human Voice Only"
                                elevenLabsInput.isNotBlank() -> "🎙️ ElevenLabs Studio v2 + Edge Neural Active (${OnlineHumanVoiceCatalog.getVoiceDisplayName(selectedElevenMale, false)} / ${OnlineHumanVoiceCatalog.getVoiceDisplayName(selectedElevenFemale, true)})"
                                else -> "✨ Edge Neural Studio HD Active (ZEE: PradeepNeural / ZHEE: NabanitaNeural)"
                            }
                            val voiceStatusColor = if (isVoiceOnline) Color(0xFF34D399) else Color(0xFFFBBF24)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF0F172A), RoundedCornerShape(8.dp))
                                    .border(1.dp, voiceStatusColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(voiceStatusText, color = voiceStatusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            if (voiceStudioMsg.isNotBlank()) {
                                Text(voiceStudioMsg, color = Cyan, fontSize = 11.sp)
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { voiceController.testVoiceSample(isFemale = false, online = isNetworkAvailable(), geminiKey = activeGeminiKey, elevenKey = elevenLabsInput) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("🔊 Test ZEE Male", fontSize = 11.sp) }
                                OutlinedButton(
                                    onClick = { voiceController.testVoiceSample(isFemale = true, online = isNetworkAvailable(), geminiKey = activeGeminiKey, elevenKey = elevenLabsInput) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("🔊 Test ZHEE Female", fontSize = 11.sp) }
                            }

                            HorizontalDivider(color = Color(0xFF1E293B))
                            Text("Hardware Keystore Security", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                "All API keys and OAuth tokens are AES-256 GCM encrypted inside the Android hardware Keystore. Zero telemetry or unencrypted network egress.",
                                color = Silver,
                                fontSize = 11.sp
                            )

                            HorizontalDivider(color = Color(0xFF1E293B))
                            Text("Private Offline Best-Friend Memory", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "100% offline & private on-device storage. ZEE/ZHEE automatically stores your feelings, dreams, preferences, and memories.",
                                color = Silver,
                                fontSize = 11.sp
                            )
                            var refreshMemoryCounter by remember { mutableStateOf(0) }
                            val offlineMemories = remember(refreshMemoryCounter) { offlineMemoryVault.getAllMemories() }
                            val userNick = offlineMemoryVault.getNickname()
                            if (!userNick.isNullOrBlank()) {
                                Text("Friend's Nickname: $userNick", color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            if (offlineMemories.isEmpty()) {
                                Text("No private memories stored yet. Talk with ZEE/ZHEE to start creating personal memories.", color = Silver.copy(alpha = 0.7f), fontSize = 11.sp)
                            } else {
                                Text("Saved Personal Notes (${offlineMemories.size}):", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                offlineMemories.take(12).forEach { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF0F172A), RoundedCornerShape(6.dp))
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.fact, color = Color.White, fontSize = 11.sp)
                                            Text("${item.category} • ${item.timestamp}", color = Silver.copy(alpha = 0.6f), fontSize = 10.sp)
                                        }
                                        IconButton(
                                            onClick = {
                                                offlineMemoryVault.deleteMemory(item.id)
                                                refreshMemoryCounter++
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                Button(
                                    onClick = {
                                        offlineMemoryVault.clearAll()
                                        refreshMemoryCounter++
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7F1D1D)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Clear All Private Offline Memories", color = Color(0xFFFCA5A5), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // Global Confirmation Gate
        pendingConfirm?.let { confirm ->
            AlertDialog(
                onDismissRequest = { pendingConfirm = null },
                containerColor = CardSurface,
                title = { Text(confirm.title, color = Color.White) },
                text = { Text(confirm.details, color = Silver, fontSize = 13.sp) },
                confirmButton = {
                    Button(onClick = {
                        val result = confirm.onConfirm()
                        pendingConfirm = null
                        scope.launch {
                            dao.insertMessage(
                                ChatMessageEntity(
                                    id = UUID.randomUUID().toString(),
                                    characterId = activeCharId,
                                    role = "assistant",
                                    text = result,
                                    source = "phone-action"
                                )
                            )
                            if (autoSpeak) voiceController.speak(result, voiceProfile)
                        }
                    }) { Text("Confirm") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingConfirm = null }) { Text("Cancel") }
                }
            )
        }

        // Productivity Suite Modals
        if (showTodosDialog) {
            TodosSheetDialog(store = productivityStore, onDismiss = { showTodosDialog = false })
        }
        if (showCalendarDialog) {
            CalendarSheetDialog(store = productivityStore, onDismiss = { showCalendarDialog = false })
        }
        if (showNotesDialog) {
            NotesSheetDialog(store = productivityStore, onDismiss = { showNotesDialog = false })
        }
        selectedSessionDetail?.let { sess ->
            SessionDetailDialog(
                session = sess,
                onDelete = { productivityStore.deleteSession(sess.id) },
                onDismiss = { selectedSessionDetail = null }
            )
        }

        // Stark Superpowers Modals
        if (showBrainDumpDialog) {
            BrainDumpDialog(
                superpowers = starkSuperpowers,
                apiKey = effectiveProviderApiKey,
                baseUrl = jarvisSettings.aiBaseUrl,
                model = effectiveOnlineModel,
                presetId = jarvisSettings.aiProviderPreset,
                onSpeakSummary = { s -> if (autoSpeak) voiceController.speak(s, voiceProfile) },
                onDismiss = { showBrainDumpDialog = false }
            )
        }
        if (showAirGesturesDialog) {
            AirGesturesHudDialog(
                sensors = hardwareSensors,
                onDismiss = { showAirGesturesDialog = false }
            )
        }
        if (showChargingHud) {
            ArcReactorChargingDialog(
                telemetry = chargingTelemetry,
                onDismiss = { jarvisCore.toggleChargingHud(false) }
            )
        }
        if (showRadarDialog) {
            AirspaceRadarDialog(
                superpowers = starkSuperpowers,
                onDismiss = { showRadarDialog = false }
            )
        }
        if (showNetworkAuditDialog) {
            NetworkAuditDialog(
                superpowers = starkSuperpowers,
                onSpeakSummary = { s -> if (autoSpeak) voiceController.speak(s, voiceProfile) },
                onDismiss = { showNetworkAuditDialog = false }
            )
        }
        if (showOpticalSolverDialog) {
            OpticalSolverDialog(
                superpowers = starkSuperpowers,
                selectedImageLabel = opticalImageLabel,
                selectedImageUri = opticalImageUri,
                apiKey = effectiveProviderApiKey,
                baseUrl = jarvisSettings.aiBaseUrl,
                model = effectiveOnlineModel,
                presetId = jarvisSettings.aiProviderPreset,
                onPickGalleryImage = { galleryPicker.launch("image/*") },
                onCaptureCameraPhoto = {
                    val file = File(context.cacheDir, "cameras/optical_${System.currentTimeMillis()}.jpg").apply {
                        parentFile?.mkdirs()
                    }
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    pendingCameraFileName = file.name
                    pendingCameraTempUri = uri
                    cameraCaptureLauncher.launch(uri)
                },
                onSpeakSolution = { s -> if (autoSpeak) voiceController.speak(s.take(360), voiceProfile) },
                onDismiss = { showOpticalSolverDialog = false }
            )
        }
        if (showPolyglotDialog) {
            PolyglotDiplomatDialog(
                superpowers = starkSuperpowers,
                apiKey = effectiveProviderApiKey,
                baseUrl = jarvisSettings.aiBaseUrl,
                model = effectiveOnlineModel,
                presetId = jarvisSettings.aiProviderPreset,
                onSpeakText = { txt, localeTag ->
                    val polyProfile = NativeVoiceProfile(
                        languageTag = localeTag,
                        pitch = voiceProfile.pitch,
                        rate = voiceProfile.rate,
                        isFemale = voiceProfile.isFemale
                    )
                    voiceController.speak(txt, polyProfile)
                },
                onDismiss = { showPolyglotDialog = false }
            )
        }

        // Screen Snapshot Reader Summary Modal
        capturedScreenSummary?.let { dump ->
            AlertDialog(
                onDismissRequest = { jarvisCore.clearScreenSummary() },
                containerColor = LuxuryPalette.SmokedCrystal,
                title = { Text("Active Screen Readout", color = activeAccent, fontFamily = FontFamily.Monospace) },
                text = { Text(dump, color = Color.White, fontSize = 12.sp) },
                confirmButton = {
                    Button(onClick = {
                        if (autoSpeak) voiceController.speak(dump.take(280), voiceProfile)
                        jarvisCore.clearScreenSummary()
                    }) { Text("Speak & Close") }
                },
                dismissButton = {
                    TextButton(onClick = { jarvisCore.clearScreenSummary() }) { Text("Close") }
                }
            )
        }

        // Full-Screen Code Black Security Lockdown Overlay
        if (codeBlackActive) {
            CodeBlackOverlayScreen(
                tamperCount = tamperCount,
                alertNote = codeBlackAlertNote,
                onRecordTamper = { jarvisCore.recordCodeBlackTamper() },
                onUnlock = { jarvisCore.unlockCodeBlack() },
                onVoiceUnlock = { toggleInteractiveMic() }
            )
        }

        // Guided 4-Step Luxury Onboarding Overlay (first launch)
        if (!jarvisSettings.onboardingComplete) {
            LuxuryOnboardingOverlay(
                prefsStore = jarvisPrefs,
                onRequestCorePermissions = { requestAllRuntimePermissions() },
                onDismiss = { }
            )
        }
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String, accent: Color) {
    Text(
        text = title,
        color = accent,
        fontSize = 10.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 10.dp, start = 8.dp, bottom = 2.dp)
    )
}

@Composable
private fun DrawerActionItem(
    title: String,
    emoji: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon = { Text(emoji, fontSize = 15.sp) },
        label = { Text(title, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = LuxuryPalette.ElectricCyan.copy(alpha = 0.16f),
            unselectedContainerColor = Color.Transparent,
            selectedTextColor = Color.White,
            unselectedTextColor = LuxuryPalette.SilverText
        )
    )
}

private fun offlineCreativeFallback(prompt: String, charId: String): String {
    return com.zeeai.companion.offline.OfflineConversationalBrain.respond(prompt, charId)
}
