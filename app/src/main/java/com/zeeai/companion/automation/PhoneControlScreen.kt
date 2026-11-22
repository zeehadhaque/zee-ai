package com.zeeai.companion.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    onShowConfirmation: (PendingConfirmation) -> Unit
) {
    val isMicLockedOn by voiceController.isMicLockedOn.collectAsState()
    val voiceState by voiceController.voiceState.collectAsState()

    var liveVoiceText by remember { mutableStateOf("") }
    var textCommandInput by remember { mutableStateOf("") }
    var torchState by remember { mutableStateOf(phoneCommander.isTorchActive()) }
    val actionLogs = remember { mutableStateListOf<PhoneActionLog>() }

    fun executePhoneCommand(raw: String) {
        val clean = raw.trim()
        if (clean.isEmpty()) return

        when (val outcome = phoneCommander.tryHandleCommand(clean)) {
            is PhoneCommandOutcome.Executed -> {
                torchState = phoneCommander.isTorchActive()
                actionLogs.add(0, PhoneActionLog(command = clean, result = outcome.replyText))
                voiceController.speak(outcome.replyText, voiceProfile)
            }
            is PhoneCommandOutcome.NeedsConfirmation -> {
                onShowConfirmation(outcome.confirmation)
            }
            is PhoneCommandOutcome.NotAPhoneCommand -> {
                actionLogs.add(0, PhoneActionLog(command = clean, result = "Sent to AI Companion"))
                onGeneralPrompt(clean)
            }
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
                        if (!hasMic) {
                            onRequestMic()
                            return@IconButton
                        }
                        if (isMicLockedOn) {
                            voiceController.stopListening()
                            liveVoiceText = ""
                        } else {
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
                                onError = { liveVoiceText = it }
                            )
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

            Text(
                if (isMicLockedOn)
                    "Continuous Phone Mic Active — Say \"Torch jalao\", \"Volume up\", \"WhatsApp kholo\", \"Screen poro\" (Tap red button to stop)"
                else
                    "Tap to start Phone Control Mic (Stays locked on for continuous commands)",
                color = if (isMicLockedOn) Color(0xFFFCD34D) else Silver,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

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

        // Quick 1-Tap Action Chips Grid
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CardNavy, RoundedCornerShape(16.dp))
                .border(1.dp, Silver.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("One-Tap Phone Action Controls", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val ok = phoneCommander.toggleTorch()
                        torchState = phoneCommander.isTorchActive()
                        val msg = if (torchState) "Flashlight on korechi." else "Flashlight bondho korechi."
                        actionLogs.add(0, PhoneActionLog(command = "Torch Toggle", result = msg))
                        voiceController.speak(msg, voiceProfile)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (torchState) Color(0xFFF59E0B) else Color(0xFF1E293B)
                    )
                ) {
                    Text(if (torchState) "🔦 Torch: ON" else "🔦 Torch: OFF", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val reply = phoneCommander.adjustVolume("up")
                        actionLogs.add(0, PhoneActionLog(command = "Volume Up", result = reply))
                        voiceController.speak(reply, voiceProfile)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("🔊 Volume +", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val reply = phoneCommander.adjustVolume("down")
                        actionLogs.add(0, PhoneActionLog(command = "Volume Down", result = reply))
                        voiceController.speak(reply, voiceProfile)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("🔉 Volume -", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val reply = phoneCommander.adjustVolume("max")
                        actionLogs.add(0, PhoneActionLog(command = "Max Volume", result = reply))
                        voiceController.speak(reply, voiceProfile)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("💯 Volume Max", fontSize = 11.sp)
                }
            }

            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { executePhoneCommand("screenshot nao") }) {
                    Text("📸 Screenshot", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("lock screen") }) {
                    Text("🔒 Lock Screen", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("screen poro") }) {
                    Text("📖 Read Screen", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("home jao") }) {
                    Text("🏠 Home", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("back jao") }) {
                    Text("🔙 Back", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("recent apps") }) {
                    Text("📑 Recents", fontSize = 11.sp)
                }
            }

            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { executePhoneCommand("story post koro") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE1306C))
                ) {
                    Text("📸 Post Story", fontSize = 11.sp)
                }
                Button(
                    onClick = { executePhoneCommand("call dialer") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
                ) {
                    Text("📞 Call / Dialer", fontSize = 11.sp)
                }
                Button(
                    onClick = { executePhoneCommand("open WhatsApp") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))
                ) {
                    Text("💬 WhatsApp", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("open Messenger") }) {
                    Text("⚡ Messenger", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("open YouTube") }) {
                    Text("🎥 YouTube", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("open Chrome") }) {
                    Text("🌐 Chrome", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("open Camera") }) {
                    Text("📷 Camera", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { executePhoneCommand("open Calculator") }) {
                    Text("🔢 Calculator", fontSize = 11.sp)
                }
            }
        }

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
