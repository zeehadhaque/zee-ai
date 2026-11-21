package com.zeeai.companion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.zeeai.companion.ai.GeminiNativeClient
import com.zeeai.companion.automation.NativePhoneCommander
import com.zeeai.companion.automation.PendingConfirmation
import com.zeeai.companion.automation.PhoneCommandOutcome
import com.zeeai.companion.automation.ZeeAccessibilityService
import com.zeeai.companion.data.local.ChatMessageEntity
import com.zeeai.companion.data.local.MemoryEntity
import com.zeeai.companion.data.local.SecureKeyVault
import com.zeeai.companion.data.local.ZeeDatabase
import com.zeeai.companion.offline.LocalLlmInferenceEngine
import com.zeeai.companion.offline.OfflineModelStoreManager
import com.zeeai.companion.voice.NativeVoiceController
import com.zeeai.companion.voice.NativeVoiceProfile
import com.zeeai.companion.voice.WakeWordForegroundService
import com.zeeai.companion.voice.WakeWordEventBus
import com.zeeai.companion.voice.WakeWordDetection
import com.zeeai.companion.studios.filmmaker.FilmmakerStudioScreen
import com.zeeai.companion.studios.reader.UniversalReaderScreen
import com.zeeai.companion.studios.youtube.YouTubeStudioScreen
import com.zeeai.companion.studios.research.ResearchAndTrendsScreen
import com.zeeai.companion.studios.council.AICouncilScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val BgBlackNavy = Color(0xFF060913)
private val CardSurface = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)

@Composable
fun ZeeNativeApp(
    database: ZeeDatabase,
    keyVault: SecureKeyVault,
    modelStore: OfflineModelStoreManager,
    localLlm: LocalLlmInferenceEngine
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { database.dao() }
    val geminiClient = remember { GeminiNativeClient() }
    val phoneCommander = remember { NativePhoneCommander(context) }

    var activeTab by remember { mutableStateOf("companion") } // companion | memory | models | settings
    var activeCharId by remember { mutableStateOf("zee") }    // zee | zhee
    var isListening by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var autoSpeak by remember { mutableStateOf(true) }
    var livePartialVoice by remember { mutableStateOf("") }
    var streamingReply by remember { mutableStateOf("") }
    var textInput by remember { mutableStateOf("") }
    var pendingConfirm by remember { mutableStateOf<PendingConfirmation?>(null) }

    val messages by dao.observeMessages().collectAsState(initial = emptyList())
    val memories by dao.observeAllMemories().collectAsState(initial = emptyList())
    val models by modelStore.modelsState.collectAsState()
    val wakeWordRunning by WakeWordEventBus.isRunning.collectAsState()

    val voiceController = remember {
        NativeVoiceController(context) { speaking -> isSpeaking = speaking }
    }

    DisposableEffect(Unit) {
        onDispose {
            geminiClient.cancelActive()
            localLlm.cancelActiveGeneration()
            voiceController.shutdown()
        }
    }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    val voiceProfile = remember(activeCharId) {
        if (activeCharId == "zhee") {
            NativeVoiceProfile(languageTag = "bn-BD", pitch = 1.08f, rate = 0.98f)
        } else {
            NativeVoiceProfile(languageTag = "bn-BD", pitch = 0.92f, rate = 1.02f)
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun sendUserTurn(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty() || isGenerating) return
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

            // Step 1: Check native Android Phone Control commands first
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
                    if (autoSpeak) voiceController.speak(outcome.replyText, voiceProfile)
                    return@launch
                }
                is PhoneCommandOutcome.NeedsConfirmation -> {
                    pendingConfirm = outcome.confirmation
                    return@launch
                }
                PhoneCommandOutcome.NotAPhoneCommand -> Unit
            }

            // Step 2: Build Persona + Long-Term Memory System Prompt
            val activeMems = dao.getActiveMemories(activeCharId, includeShared = true)
            val personaName = if (activeCharId == "zhee") "ZHEE (warm, caring, intelligent female AI companion)"
            else "ZEE (witty, sharp, friendly male AI companion)"

            val sysPrompt = buildString {
                append("You are $personaName running natively on the user's Android phone. ")
                append("Speak naturally like a human friend across Bangla, Banglish, English, Hindi, and Urdu. ")
                append("Never repeat templated replies or claim fake capabilities.\n")
                if (activeMems.isNotEmpty()) {
                    append("Saved Memories:\n")
                    activeMems.forEach { append("- [${it.category}] ${it.content}\n") }
                }
            }

            isGenerating = true
            streamingReply = ""

            val online = isNetworkAvailable()
            val geminiKey = keyVault.getKey("gemini_api_key")
            val savedFlash = keyVault.getKey("gemini_flash_model").ifBlank { "gemini-2.5-flash" }

            if (online && geminiKey.isNotBlank()) {
                val historyPairs = messages.takeLast(10).map {
                    (if (it.role == "user") "user" else "model") to it.text
                }
                val res = geminiClient.streamChat(
                    apiKey = geminiKey,
                    model = savedFlash,
                    systemInstruction = sysPrompt,
                    history = historyPairs,
                    userPrompt = prompt,
                    onChunk = { partial -> streamingReply = partial }
                )
                val finalReply = res.getOrElse { "Gemini error: ${it.message}" }
                dao.insertMessage(
                    ChatMessageEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = activeCharId,
                        role = "assistant",
                        text = finalReply,
                        source = "gemini-online"
                    )
                )
                streamingReply = ""
                isGenerating = false
                if (autoSpeak) voiceController.speak(finalReply, voiceProfile)
            } else {
                // Offline Path: Real On-Device MediaPipe LLM -> Fallback Local Creative Engine
                val installedModelId = localLlm.findFirstInstalledMediaPipeModelId()
                if (installedModelId != null) {
                    val acc = StringBuilder()
                    localLlm.streamResponse(
                        modelId = installedModelId,
                        systemInstruction = sysPrompt,
                        userPrompt = prompt,
                        onToken = { token, done ->
                            acc.append(token)
                            scope.launch(Dispatchers.Main) {
                                streamingReply = acc.toString()
                                if (done) {
                                    val finalText = acc.toString().trim()
                                    dao.insertMessage(
                                        ChatMessageEntity(
                                            id = UUID.randomUUID().toString(),
                                            characterId = activeCharId,
                                            role = "assistant",
                                            text = finalText,
                                            source = "local-llm"
                                        )
                                    )
                                    streamingReply = ""
                                    isGenerating = false
                                    if (autoSpeak) voiceController.speak(finalText, voiceProfile)
                                }
                            }
                        },
                        onError = { err ->
                            scope.launch(Dispatchers.Main) {
                                dao.insertMessage(
                                    ChatMessageEntity(
                                        id = UUID.randomUUID().toString(),
                                        characterId = activeCharId,
                                        role = "assistant",
                                        text = "Offline LLM error: $err",
                                        source = "offline-creative"
                                    )
                                )
                                streamingReply = ""
                                isGenerating = false
                            }
                        }
                    )
                } else {
                    val offlineReply = withContext(Dispatchers.Default) {
                        offlineCreativeFallback(prompt, activeCharId)
                    }
                    dao.insertMessage(
                        ChatMessageEntity(
                            id = UUID.randomUUID().toString(),
                            characterId = activeCharId,
                            role = "assistant",
                            text = offlineReply,
                            source = "offline-creative"
                        )
                    )
                    streamingReply = ""
                    isGenerating = false
                    if (autoSpeak) voiceController.speak(offlineReply, voiceProfile)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        WakeWordEventBus.detections.collect { event ->
            activeCharId = event.targetCharacterId
            activeTab = "companion"
            if (event.trailingCommand.isNotBlank()) {
                sendUserTurn(event.trailingCommand)
            } else {
                val greeting = if (event.targetCharacterId == "zhee") "হ্যাঁ বলো, আমি শুনছি।" else "হ্যাঁ, বলো কী করব?"
                voiceController.speak(greeting, voiceProfile)
            }
        }
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BgBlackNavy,
            surface = CardSurface,
            primary = ElectricBlue,
            onBackground = Color.White,
            onSurface = Silver
        )
    ) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = CardSurface,
                    drawerContentColor = Color.White
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "ZEE AI ECOSYSTEM",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            "100% Pure Native Android • Active: ${activeCharId.uppercase()}",
                            color = Silver.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(8.dp))

                        listOf(
                            "companion" to "ZEE / ZHEE Companion",
                            "council" to "AI Council (Multi-Model)",
                            "youtube" to "YouTube Automation Studio",
                            "filmmaker" to "ZEE Cinema & Filmmaker",
                            "research" to "Research & Trend Radar",
                            "reader" to "Universal File Reader (SAF)",
                            "memory" to "Long-Term Room Memory",
                            "models" to "Offline Model Store & Control",
                            "settings" to "API Keys & Keystore Vault"
                        ).forEach { (id, title) ->
                            NavigationDrawerItem(
                                label = { Text(title, fontSize = 13.sp) },
                                selected = activeTab == id,
                                onClick = {
                                    activeTab = id
                                    scope.launch { drawerState.close() }
                                },
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = ElectricBlue.copy(alpha = 0.2f),
                                    unselectedContainerColor = Color.Transparent,
                                    selectedTextColor = Color.White,
                                    unselectedTextColor = Silver
                                )
                            )
                        }
                    }
                }
            }
        ) {
            Scaffold(
                containerColor = BgBlackNavy,
                bottomBar = {
                    NavigationBar(containerColor = CardSurface) {
                        listOf(
                            "companion" to "ZEE/ZHEE",
                            "youtube" to "YouTube",
                            "filmmaker" to "Filmmaker",
                            "research" to "Research",
                            "models" to "Models"
                        ).forEach { (id, label) ->
                            NavigationBarItem(
                                selected = activeTab == id,
                                onClick = { activeTab = id },
                                label = { Text(label, fontSize = 11.sp) },
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(
                                                if (activeTab == id) ElectricBlue else Silver.copy(alpha = 0.3f),
                                                CircleShape
                                            )
                                    )
                                }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Drawer Toggle + Emergency Stop + Online/Offline Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CardSurface, RoundedCornerShape(14.dp))
                            .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { scope.launch { drawerState.open() } },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Menu", fontSize = 11.sp)
                            }
                            Column {
                                Text("ZEE AI NATIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    if (isNetworkAvailable()) "Online • Gemini Ready" else "Offline • Local Android Mode",
                                    color = if (isNetworkAvailable()) Color(0xFF34D399) else Color(0xFFFBBF24),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Button(
                            onClick = {
                                geminiClient.cancelActive()
                                localLlm.cancelActiveGeneration()
                                voiceController.emergencyStop()
                                WakeWordForegroundService.stop(context)
                                isListening = false
                                isSpeaking = false
                                isGenerating = false
                                streamingReply = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("STOP ALL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    when (activeTab) {
                        "companion" -> {
                            // ZEE / ZHEE Switcher + Wake Word & Voice Mic
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = activeCharId == "zee",
                                    onClick = { activeCharId = "zee" },
                                    label = { Text("ZEE (Male)") }
                                )
                                FilterChip(
                                    selected = activeCharId == "zhee",
                                    onClick = { activeCharId = "zhee" },
                                    label = { Text("ZHEE (Female)") }
                                )
                                Spacer(Modifier.weight(1f))
                                FilterChip(
                                    selected = wakeWordRunning,
                                    onClick = {
                                        if (wakeWordRunning) {
                                            WakeWordForegroundService.stop(context)
                                        } else if (hasMic) {
                                            WakeWordForegroundService.start(context)
                                        } else {
                                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    },
                                    label = { Text(if (wakeWordRunning) "Wake: ON" else "Wake: OFF", fontSize = 11.sp) }
                                )
                                FilterChip(
                                    selected = autoSpeak,
                                    onClick = { autoSpeak = !autoSpeak },
                                    label = { Text(if (autoSpeak) "Voice ON" else "Muted", fontSize = 11.sp) }
                                )
                            }

                            if (livePartialVoice.isNotBlank()) {
                                Text(
                                    "Hearing: $livePartialVoice",
                                    color = ElectricBlue,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }

                            // Chat Stream
                            LazyColumn(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(messages, key = { it.id }) { msg ->
                                    val isUser = msg.role == "user"
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .widthIn(max = 310.dp)
                                                .background(
                                                    if (isUser) ElectricBlue else CardSurface,
                                                    RoundedCornerShape(16.dp)
                                                )
                                                .padding(12.dp)
                                        ) {
                                            Text(msg.text, color = Color.White, fontSize = 14.sp)
                                        }
                                        Text(
                                            "${if (isUser) "You" else msg.characterId.uppercase()} • ${msg.source}",
                                            color = Silver.copy(alpha = 0.55f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                                if (streamingReply.isNotBlank()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(0.9f)
                                                .background(CardSurface, RoundedCornerShape(16.dp))
                                                .border(1.dp, ElectricBlue.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                                                .padding(12.dp)
                                        ) {
                                            Text(streamingReply, color = Color.White, fontSize = 14.sp)
                                        }
                                    }
                                }
                            }

                            // Voice + Text Composer
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (!hasMic) {
                                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@Button
                                        }
                                        if (isListening) {
                                            voiceController.stopListening()
                                            isListening = false
                                            WakeWordForegroundService.resume(context)
                                        } else {
                                            WakeWordForegroundService.pause(context)
                                            isListening = true
                                            livePartialVoice = ""
                                            voiceController.startListening(
                                                languageTag = voiceProfile.languageTag,
                                                preferOffline = !isNetworkAvailable(),
                                                onPartial = { livePartialVoice = it },
                                                onFinal = { finalText ->
                                                    isListening = false
                                                    livePartialVoice = ""
                                                    WakeWordForegroundService.resume(context)
                                                    if (finalText.isNotBlank()) sendUserTurn(finalText)
                                                },
                                                onError = {
                                                    isListening = false
                                                    livePartialVoice = it
                                                    WakeWordForegroundService.resume(context)
                                                }
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isListening) Color(0xFFDC2626) else ElectricBlue
                                    )
                                ) {
                                    Text(if (isListening) "Stop" else "Mic", fontSize = 12.sp)
                                }

                                OutlinedTextField(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Bolo ba likho...", fontSize = 13.sp) },
                                    singleLine = true
                                )

                                Button(
                                    onClick = { sendUserTurn(textInput) },
                                    enabled = textInput.isNotBlank() && !isGenerating
                                ) {
                                    Text("Send", fontSize = 12.sp)
                                }
                            }
                        }

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
                            localLlm = localLlm,
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
                            localLlm = localLlm,
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

                    "models" -> {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CardSurface, RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Phone Control (Accessibility)", color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            if (ZeeAccessibilityService.isConnected()) "Active" else "Not Enabled",
                                            color = if (ZeeAccessibilityService.isConnected()) Color(0xFF34D399) else Color(0xFFFBBF24),
                                            fontSize = 12.sp
                                        )
                                    }
                                    OutlinedButton(onClick = {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }) { Text("Enable") }
                                }
                            }
                            items(models, key = { it.spec.id }) { item ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(CardSurface, RoundedCornerShape(14.dp))
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(item.spec.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("${item.spec.type} • ~${item.spec.approxSizeMb} MB • ${item.spec.language}", color = Silver, fontSize = 11.sp)
                                    if (item.isDownloading) {
                                        LinearProgressIndicator(progress = { item.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (item.installed) {
                                            OutlinedButton(onClick = { modelStore.deleteModel(item.spec.id) }) {
                                                Text("Delete", color = Color(0xFFF87171))
                                            }
                                        } else {
                                            Button(onClick = {
                                                val job = scope.launch { modelStore.downloadModel(item.spec.id) }
                                                modelStore.registerJob(item.spec.id, job)
                                            }) { Text(if (item.isDownloading) "${item.progressPercent}%" else "Download") }
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

                        val storedModels = keyVault.getKey("gemini_discovered_models").split(",").filter { it.isNotBlank() }
                        var discoveredModels by remember { mutableStateOf(storedModels) }
                        var selectedFlash by remember {
                            mutableStateOf(keyVault.getKey("gemini_flash_model").ifBlank { "gemini-2.5-flash" })
                        }
                        var selectedPro by remember {
                            mutableStateOf(keyVault.getKey("gemini_pro_model").ifBlank { "gemini-2.5-pro" })
                        }
                        var flashDropdownOpen by remember { mutableStateOf(false) }
                        var proDropdownOpen by remember { mutableStateOf(false) }

                        val runLiveValidation: (String) -> Unit = { keyToValidate ->
                            val clean = keyToValidate.trim()
                            if (clean.isNotBlank()) {
                                isValidating = true
                                statusMsg = "Connecting to Google AI Studio..."
                                scope.launch {
                                    val res = geminiClient.discoverModels(clean)
                                    isValidating = false
                                    res.onSuccess { disc ->
                                        isVerified = true
                                        statusMsg = "API Key Verified • ${disc.models.size} models detected"
                                        discoveredModels = disc.models.map { it.id }
                                        selectedFlash = disc.bestFlashModel
                                        selectedPro = disc.bestProModel

                                        keyVault.saveKey("gemini_api_key", clean)
                                        keyVault.saveKey("gemini_api_status", "verified")
                                        keyVault.saveKey("gemini_status_msg", statusMsg)
                                        keyVault.saveKey("gemini_discovered_models", discoveredModels.joinToString(","))
                                        keyVault.saveKey("gemini_flash_model", selectedFlash)
                                        keyVault.saveKey("gemini_pro_model", selectedPro)
                                    }.onFailure { err ->
                                        isVerified = false
                                        statusMsg = "Validation failed: ${err.message?.take(120)}"
                                        keyVault.saveKey("gemini_api_status", "invalid")
                                        keyVault.saveKey("gemini_status_msg", statusMsg)
                                    }
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
                                "Google AI Studio API Key & Live Model Discovery",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                            Text(
                                "Paste your Google AI Studio API key (AIza...). The key is validated live and modern models are auto-detected without deprecated IDs.",
                                color = Silver,
                                fontSize = 12.sp
                            )

                            OutlinedTextField(
                                value = geminiInput,
                                onValueChange = { newVal ->
                                    geminiInput = newVal
                                    if (newVal.trim().startsWith("AIza") && newVal.trim().length >= 39) {
                                        runLiveValidation(newVal)
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
                                        Text("Validating...")
                                    } else {
                                        Text("Validate & Discover")
                                    }
                                }

                                if (isVerified) {
                                    TextButton(onClick = {
                                        geminiInput = ""
                                        isVerified = false
                                        statusMsg = ""
                                        discoveredModels = emptyList()
                                        keyVault.removeKey("gemini_api_key")
                                        keyVault.removeKey("gemini_api_status")
                                        keyVault.removeKey("gemini_status_msg")
                                        keyVault.removeKey("gemini_discovered_models")
                                    }) {
                                        Text("Clear Key", color = Color(0xFFEF4444))
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

                            if (discoveredModels.isNotEmpty()) {
                                HorizontalDivider(color = Color(0xFF1E293B))

                                Text("Active Model Selectors", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { flashDropdownOpen = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                            Text("Fast Model (Voice & Companion Chat)", fontSize = 11.sp, color = Silver)
                                            Text(selectedFlash, fontSize = 14.sp, color = ElectricBlue, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = flashDropdownOpen,
                                        onDismissRequest = { flashDropdownOpen = false }
                                    ) {
                                        discoveredModels.filter { it.contains("flash", ignoreCase = true) || !it.contains("pro", ignoreCase = true) }.forEach { modelId ->
                                            DropdownMenuItem(
                                                text = { Text(modelId) },
                                                onClick = {
                                                    selectedFlash = modelId
                                                    keyVault.saveKey("gemini_flash_model", modelId)
                                                    flashDropdownOpen = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Box(modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { proDropdownOpen = true },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                            Text("Pro Model (Filmmaker, Research & Council)", fontSize = 11.sp, color = Silver)
                                            Text(selectedPro, fontSize = 14.sp, color = Color(0xFFA78BFA), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = proDropdownOpen,
                                        onDismissRequest = { proDropdownOpen = false }
                                    ) {
                                        discoveredModels.filter { it.contains("pro", ignoreCase = true) || !it.contains("flash", ignoreCase = true) }.forEach { modelId ->
                                            DropdownMenuItem(
                                                text = { Text(modelId) },
                                                onClick = {
                                                    selectedPro = modelId
                                                    keyVault.saveKey("gemini_pro_model", modelId)
                                                    proDropdownOpen = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            HorizontalDivider(color = Color(0xFF1E293B))
                            Text("Hardware Keystore Security", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                            Text(
                                "All API keys and OAuth tokens are AES-256 GCM encrypted inside the Android hardware Keystore. Zero telemetry or unencrypted network egress.",
                                color = Silver,
                                fontSize = 11.sp
                            )
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
    }
}

private fun offlineCreativeFallback(prompt: String, charId: String): String {
    val lower = prompt.lowercase()
    return when {
        Regex("(kobita|কবিতা|poem)").containsMatchIn(lower) ->
            "রাতের নীরবতা জানে সব না-বলা গল্প,\nজানালার ওপাশে জমে থাকে কুয়াশার অল্প স্বর।\nসব আলো নিভে গেলে বুঝে নিও—\nনিজের ভেতরেই জ্বলে থাকে আসল ভোর।"
        Regex("(shayari|শায়েরি|शायरी|urdu|hindi)").containsMatchIn(lower) ->
            "ख़ामोशियों में भी एक दास्तान छुपी होती है,\nहर टूटे हुए ख़्वाब में नई उड़ान छुपी होती है।"
        Regex("(joke|হাসাও|funny)").containsMatchIn(lower) ->
            "প্রোগ্রামার বলল: লাস্ট বাগটা ফিক্স করেই ঘুমাবো — সেই থেকে ৩ রাত কেটে গেছে!"
        else ->
            if (charId == "zhee") "এখন ইন্টারনেট নেই, আমি অফলাইনে আছি। Models ট্যাব থেকে Gemma মডেল ডাউনলোড করলে অফলাইনেও পুরো AI চ্যাট চলবে — এখন চাইলে কবিতা, শায়েরি বা ফোনের কমান্ড দিতে পারো।"
            else "অফলাইন মোডে আছি। ফোন কন্ট্রোল (WhatsApp/YouTube/Alarm/Screen Read) আর কবিতা-শায়েরি এখনই চলবে। পুরো অফলাইন AI চ্যাটের জন্য Models ট্যাব থেকে লোকাল LLM ডাউনলোড করে নাও।"
    }
}
