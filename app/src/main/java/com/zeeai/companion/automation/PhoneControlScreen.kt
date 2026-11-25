package com.zeeai.companion.automation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.voice.NativeVoiceController
import com.zeeai.companion.voice.NativeVoiceProfile
import com.zeeai.companion.voice.VoiceState
import com.zeeai.companion.ui.PhoneControlDualDashboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgNavy = Color(0xFF060913)
private val CardNavy = Color(0xFF0B1120)
private val Silver = Color(0xFFC8D1E0)
private val ElectricBlue = Color(0xFF2F80FF)
private val NeonEmerald = Color(0xFF10B981)
private val CrimsonRed = Color(0xFFF87171)
private val Cyan = Color(0xFF00F0FF)

data class PhoneActionLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val result: String,
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

@Composable
fun PhoneControlScreen(
    context: Context,
    phoneCommander: NativePhoneCommander,
    offlinePhoneController: OfflineAutonomousPhoneController? = null,
    voiceController: NativeVoiceController,
    voiceProfile: NativeVoiceProfile,
    isNetworkAvailable: () -> Boolean,
    isAccessibilityActive: Boolean,
    isAccessibilityConnected: Boolean,
    hasMic: Boolean,
    onRequestMic: () -> Unit,
    activeCharId: String,
    onCharChanged: (String) -> Unit,
    onGeneralPrompt: (String) -> Unit,
    onGeneralPromptWithReply: ((String, (String) -> Unit) -> Unit)? = null,
    onShowConfirmation: (PendingConfirmation) -> Unit
) {
    val isMicLockedOn by voiceController.isMicLockedOn.collectAsState()
    val voiceState by voiceController.voiceState.collectAsState()
    val micRmsLevel by voiceController.micRmsLevel.collectAsState()
    val micStatusHint by voiceController.statusHint.collectAsState()
    val micLastError by voiceController.lastError.collectAsState()

    var liveVoiceText by remember { mutableStateOf("") }
    var textCommandInput by remember { mutableStateOf("") }
    var torchState by remember { mutableStateOf(phoneCommander.isTorchActive()) }
    var startAfterMicGrant by remember { mutableStateOf(false) }
    val actionLogs = remember { mutableStateListOf<PhoneActionLog>() }

    fun executePhoneCommand(raw: String) {
        val clean = raw.trim()
        if (clean.isEmpty()) return

        when (val outcome = phoneCommander.tryHandleCommand(clean)) {
            is PhoneCommandOutcome.Executed -> {
                torchState = phoneCommander.isTorchActive()
                actionLogs.add(0, PhoneActionLog(command = clean, result = outcome.replyText))
                voiceController.speak(outcome.replyText, voiceProfile)
                return
            }
            is PhoneCommandOutcome.NeedsConfirmation -> {
                onShowConfirmation(outcome.confirmation)
                return
            }
            is PhoneCommandOutcome.NotAPhoneCommand -> Unit
        }

        val offlineOutcome = offlinePhoneController?.tryInstantOfflineCommand(clean)
        if (offlineOutcome != null && offlineOutcome.handled) {
            actionLogs.add(0, PhoneActionLog(command = clean, result = offlineOutcome.reply))
            voiceController.speak(offlineOutcome.reply, voiceProfile)
            return
        }

        if (onGeneralPromptWithReply != null) {
            voiceController.pauseListeningForProcessing()
            actionLogs.add(0, PhoneActionLog(command = clean, result = "Processing AI command..."))
            onGeneralPromptWithReply(clean) { reply ->
                val idx = actionLogs.indexOfFirst { it.command == clean && it.result == "Processing AI command..." }
                if (idx >= 0) {
                    actionLogs[idx] = actionLogs[idx].copy(result = reply)
                } else {
                    actionLogs.add(0, PhoneActionLog(command = clean, result = reply))
                }
                voiceController.resumeListeningAfterProcessing()
            }
        } else {
            actionLogs.add(0, PhoneActionLog(command = clean, result = "Sent to AI Companion"))
            onGeneralPrompt(clean)
        }
    }

    val phoneSpeechDialogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull().orEmpty().trim()
            if (spoken.isNotBlank()) {
                liveVoiceText = ""
                executePhoneCommand(spoken)
            }
        }
    }

    fun launchSystemVoicePopup() {
        if (!voiceController.hasMicPermission() && !hasMic) {
            onRequestMic()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceProfile.languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceProfile.languageTag)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("bn-BD", "en-US", "bn-IN"))
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                if (activeCharId == "zhee") "ZHEE Phone Control — বলুন বস" else "ZEE Phone Control — কমান্ড বলুন বস"
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { phoneSpeechDialogLauncher.launch(intent) }
    }

    fun startPhoneControlMic() {
        liveVoiceText = ""
        voiceController.startContinuousListening(
            languageTag = voiceProfile.languageTag,
            preferOffline = !isNetworkAvailable(),
            onPartial = { liveVoiceText = it },
            onFinal = { spokenText ->
                liveVoiceText = ""
                if (spokenText.isNotBlank()) {
                    executePhoneCommand(spokenText)
                }
            },
            onError = { /* handled reactively by voiceController.lastError */ }
        )
    }

    val phoneMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onRequestMic()
            if (startAfterMicGrant) {
                startAfterMicGrant = false
                startPhoneControlMic()
            }
        } else {
            startAfterMicGrant = false
        }
    }

    DisposableEffect(voiceController) {
        voiceController.onFallbackToSystemDialog = { launchSystemVoicePopup() }
        onDispose {
            voiceController.onFallbackToSystemDialog = null
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "ZEE Phone Commander",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        "100% Native on-device voice control for your Android phone.",
                        color = Silver.copy(alpha = 0.75f),
                        fontSize = 11.sp
                    )
                }
                FilterChip(
                    selected = activeCharId == "zee",
                    onClick = { onCharChanged(if (activeCharId == "zee") "zhee" else "zee") },
                    label = {
                        Text(
                            if (activeCharId == "zee") "⚔️ ZEE (Male)" else "🌸 ZHEE (Female)",
                            fontSize = 11.sp
                        )
                    }
                )
            }
        }

        // Dedicated Phone Control Mic Card (Alada Mic)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            if (isMicLockedOn) Color(0xFF1E1B4B) else CardNavy,
                            CardNavy
                        )
                    ),
                    shape = RoundedCornerShape(18.dp)
                )
                .border(
                    width = if (isMicLockedOn) 2.dp else 1.dp,
                    color = if (isMicLockedOn) Cyan else Silver.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(18.dp)
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(if (isMicLockedOn) NeonEmerald else Silver, CircleShape)
                    )
                    Text(
                        if (isMicLockedOn) "PHONE MIC LOCKED ON" else "PHONE CONTROL MIC",
                        color = if (isMicLockedOn) NeonEmerald else Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Text(
                    when (voiceState) {
                        VoiceState.LISTENING -> "● Listening..."
                        VoiceState.HEARING -> "● Hearing speech..."
                        VoiceState.SPEAKING -> "● Speaking reply..."
                        else -> if (isMicLockedOn) "Active" else "Idle"
                    },
                    color = if (isMicLockedOn) Cyan else Silver,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Big Glowing Phone Control Mic Button
            Box(
                modifier = Modifier
                    .size(86.dp)
                    .scale(if (isMicLockedOn) pulseScale else 1f)
                    .background(
                        brush = Brush.radialGradient(
                            colors = if (isMicLockedOn)
                                listOf(Color(0xFFDC2626), Color(0xFF991B1B))
                            else
                                listOf(ElectricBlue, Color(0xFF1D4ED8))
                        ),
                        shape = CircleShape
                    )
                    .border(
                        width = 3.dp,
                        color = if (isMicLockedOn) Color(0xFFFCA5A5) else Cyan,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = {
                        if (!hasMic && !voiceController.hasMicPermission()) {
                            startAfterMicGrant = true
                            phoneMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@IconButton
                        }
                        if (isMicLockedOn) {
                            voiceController.stopListening()
                            liveVoiceText = ""
                        } else {
                            startPhoneControlMic()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        if (isMicLockedOn) "🛑" else "🎙️",
                        fontSize = 32.sp
                    )
                }
            }

            if (isMicLockedOn) {
                LinearProgressIndicator(
                    progress = { micRmsLevel.coerceIn(0.08f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth(0.75f)
                        .height(5.dp),
                    color = if (voiceState == VoiceState.HEARING) NeonEmerald else Cyan,
                    trackColor = Color(0xFF1E293B)
                )
            }

            Text(
                if (isMicLockedOn) micStatusHint
                else "Tap to start Phone Control Mic (Continuous voice commands + spoken replies)",
                color = if (isMicLockedOn) Color(0xFFFCD34D) else Silver,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launchSystemVoicePopup() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("🎤 Google Voice Popup", fontSize = 11.sp, color = Cyan)
                }
                if (voiceState == VoiceState.SPEAKING) {
                    OutlinedButton(
                        onClick = { voiceController.stopSpeaking() },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("🔇 Stop Voice", fontSize = 11.sp, color = CrimsonRed)
                    }
                }
            }

            if (!micLastError.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF450A0A), RoundedCornerShape(8.dp))
                        .border(1.dp, CrimsonRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("⚠️ $micLastError", color = Color(0xFFFCA5A5), fontSize = 11.sp)
                }
            }

            if (liveVoiceText.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF030712), RoundedCornerShape(10.dp))
                        .border(1.dp, Cyan.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Hearing: $liveVoiceText",
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Offline Voice Packs (ZEE Male & ZHEE Female) Card
        var voicePackInfo by remember { mutableStateOf(voiceController.getVoicePackInfo()) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(900L)
            voicePackInfo = voiceController.getVoicePackInfo()
        }
        var voiceStatusMsg by remember { mutableStateOf("") }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, ElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🗣️ Offline Voice Packs (ZEE & ZHEE)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    if (voicePackInfo.hasBengali) "✓ Offline Ready" else "⚠️ Pack Needed",
                    color = if (voicePackInfo.hasBengali) NeonEmerald else Color(0xFFFBBF24),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                "অফলাইনে কথা বলা ও ফোনের অ্যাকশন মুখে শোনার জন্য বাংলা ভয়েস প্যাক ডাউনলোড করুন:",
                color = Silver,
                fontSize = 11.sp
            )

            // Voice Pack Download & Setup Guide Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("💡 অফলাইন ভয়েস ডাউনলোড লিংক ও গাইড:", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Text(
                        "ZEE ও ZHEE অফলাইনে কথা বলার জন্য Android-এর Google Speech Services ব্যবহার করে:\n" +
                        "১. নিচের '📥 ইনস্টল বাংলা ভয়েস' অথবা '🛍️ Play Store TTS'-এ ট্যাপ করুন।\n" +
                        "২. Google TTS সেটিংসে গিয়ে 'Install voice data' ➔ 'Bengali (Bangladesh/India)' ডাউনলোড করুন।\n" +
                        "৩. ডাউনলোড শেষ হলে '🔊 Test' বাটনে চাপ দিয়ে কণ্ঠ নিশ্চিত করুন।",
                        color = Silver,
                        fontSize = 10.sp,
                        lineHeight = 15.sp
                    )
                }
            }

            if (voiceStatusMsg.isNotBlank()) {
                Text(voiceStatusMsg, color = Cyan, fontSize = 11.sp)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        voiceStatusMsg = "Google TTS সেটিংস খুলছে... 'Install voice data' থেকে Bengali প্যাক ডাউনলোড করুন।"
                        voiceController.launchVoiceDataInstall(context, isFemale = false)
                        voicePackInfo = voiceController.getVoicePackInfo()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricBlue)
                ) {
                    Text("📥 ইনস্টল বাংলা ভয়েস", fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        voiceStatusMsg = "Play Store খুলছে... Speech Services by Google ইনস্টল বা আপডেট করুন।"
                        voiceController.openGoogleSpeechServicesPlayStore(context)
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                ) {
                    Text("🛍️ Play Store TTS", fontSize = 11.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        voiceStatusMsg = "ZEE (Male) অফলাইন ভয়েস টেস্ট হচ্ছে..."
                        voiceController.testVoiceSample(isFemale = false)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🔊 Test ZEE Male", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        voiceStatusMsg = "ZHEE (Female) অফলাইন ভয়েস টেস্ট হচ্ছে..."
                        voiceController.testVoiceSample(isFemale = true)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🔊 Test ZHEE Female", fontSize = 11.sp)
                }
            }

            TextButton(
                onClick = {
                    voiceController.openTtsSettings(context)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("⚙️ Open System Text-to-Speech Settings", color = Silver.copy(alpha = 0.8f), fontSize = 11.sp)
            }
        }

        // System Services & Permissions Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("System Permissions & 24/7 Engine Status", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

            // Always Allow in Background 24/7 Card
            val wakeWordRunning by com.zeeai.companion.voice.WakeWordEventBus.isRunning.collectAsState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (wakeWordRunning) Color(0xFF064E3B) else Color(0xFF1E293B),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        1.dp,
                        if (wakeWordRunning) NeonEmerald.copy(alpha = 0.6f) else ElectricBlue.copy(alpha = 0.4f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Microphone: Always Allow (Background 24/7)",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                if (wakeWordRunning)
                                    "● Active • App বন্ধ থাকলেও সারাক্ষণ ভয়েস শুনে কাজ করবে ও মুখে উত্তর দেবে"
                                else
                                    "App কেটে দিলেও ব্যাকগ্রাউন্ডে ভয়েস কমান্ড শুনে কাজ করবে ও উত্তর দেবে",
                                color = if (wakeWordRunning) NeonEmerald else Silver,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!hasMic && !voiceController.hasMicPermission()) {
                                    onRequestMic()
                                    return@Button
                                }
                                com.zeeai.companion.voice.WakeWordForegroundService.start(context)
                                runCatching {
                                    val powerIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(powerIntent)
                                }
                                runCatching {
                                    val appSettingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(appSettingsIntent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (wakeWordRunning) NeonEmerald else ElectricBlue
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (wakeWordRunning) "✓ Always Allow (Running 24/7)" else "🎙️ Always Allow (Background ON)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (wakeWordRunning) {
                            OutlinedButton(onClick = {
                                com.zeeai.companion.voice.WakeWordForegroundService.stop(context)
                            }) {
                                Text("Stop", color = CrimsonRed, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            val isAccOk = isAccessibilityActive || isAccessibilityConnected
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Accessibility Service (Gestures & Screen)", color = Color.White, fontSize = 12.sp)
                    Text(
                        if (isAccOk) "Active • System navigation & screen reader ready" else "Disabled • Required for gestures & screen reading",
                        color = if (isAccOk) NeonEmerald else Color(0xFFFBBF24),
                        fontSize = 11.sp
                    )
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(if (isAccOk) "Settings" else "Enable", fontSize = 11.sp)
                }
            }

            HorizontalDivider(color = Color(0xFF1E293B))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Microphone Permission", color = Color.White, fontSize = 12.sp)
                    Text(
                        if (hasMic) "Granted • Audio hardware unlocked" else "Permission required to use mic",
                        color = if (hasMic) NeonEmerald else CrimsonRed,
                        fontSize = 11.sp
                    )
                }
                if (!hasMic) {
                    Button(
                        onClick = onRequestMic,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp)
                    }
                }
            }
        }

        // Dual Phone Controls Dashboard (Offline Instant + Online Smart)
        val installedVoiceCount = com.zeeai.companion.voice.OfflineVoiceCatalog.countInstalledPacks(context)
        PhoneControlDualDashboard(
            isAccessibilityActive = isAccessibilityActive || isAccessibilityConnected,
            isMicGranted = hasMic,
            isInternetConnected = isNetworkAvailable(),
            installedOfflineVoiceCount = installedVoiceCount,
            lastActionFeedback = actionLogs.firstOrNull()?.result.orEmpty(),
            onRunCommand = { cmd -> executePhoneCommand(cmd) },
            onOpenAccessibilitySettings = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            onRequestMicPermission = onRequestMic
        )

        // Text Command Input (Alternative to Voice)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textCommandInput,
                onValueChange = { textCommandInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Command likho (e.g. 'torch on', 'volume up')...", fontSize = 12.sp) },
                singleLine = true
            )
            Button(
                onClick = {
                    executePhoneCommand(textCommandInput)
                    textCommandInput = ""
                },
                enabled = textCommandInput.isNotBlank()
            ) {
                Text("Run", fontSize = 12.sp)
            }
        }

        // Live Action Log
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Phone Command Activity Log", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (actionLogs.isNotEmpty()) {
                    TextButton(onClick = { actionLogs.clear() }) {
                        Text("Clear", color = CrimsonRed, fontSize = 11.sp)
                    }
                }
            }

            if (actionLogs.isEmpty()) {
                Text(
                    "No commands executed yet. Tap the Phone Control Mic or use the quick buttons above.",
                    color = Silver.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            } else {
                actionLogs.take(8).forEach { log ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgNavy, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("🎤 \"${log.command}\"", color = Cyan, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                            Text(log.time, color = Silver.copy(alpha = 0.5f), fontSize = 10.sp)
                        }
                        Text("⚡ ${log.result}", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
