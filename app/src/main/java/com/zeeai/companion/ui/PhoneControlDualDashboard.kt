package com.zeeai.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneControlDualDashboard(
    modifier: Modifier = Modifier,
    isAccessibilityActive: Boolean,
    isMicGranted: Boolean,
    isInternetConnected: Boolean,
    activeOnlineVoiceLabel: String = "100% Online Human Voice Ready",
    lastActionFeedback: String,
    onRunCommand: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestMicPermission: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (lastActionFeedback.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF0F2922),
                border = BorderStroke(1.dp, Color(0xFF10B981)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚡ $lastActionFeedback",
                    color = Color(0xFFA7F3D0),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // BOX 1: 📴 100% OFFLINE AVAILABLE PHONE CONTROLS (নেট ছাড়াই <50ms কাজ করে)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF121826),
            border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📴 Offline Phone Controls (Instant)",
                        color = Color(0xFFFBBF24),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text("100% Ready", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }

                // Live Permission & Voice Status
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = onOpenAccessibilitySettings,
                        label = {
                            Text(
                                if (isAccessibilityActive) "✓ Accessibility ON" else "⚠ Enable Accessibility",
                                fontSize = 11.sp
                            )
                        }
                    )
                    AssistChip(
                        onClick = onRequestMicPermission,
                        label = {
                            Text(
                                if (isMicGranted) "✓ Mic Ready" else "⚠ Allow Mic",
                                fontSize = 11.sp
                            )
                        }
                    )
                }

                Text(
                    text = if (isInternetConnected) "🎙️ Voice Output: $activeOnlineVoiceLabel" else "🌐 Connect Internet for 100% Human Voice",
                    color = if (isInternetConnected) Color(0xFF34D399) else Color(0xFFFBBF24),
                    fontSize = 11.sp
                )

                val offlineActions = listOf(
                    "🔦 Torch ON" to "torch on",
                    "🔦 Torch OFF" to "torch off",
                    "🔊 Volume UP" to "volume up",
                    "🔉 Volume DOWN" to "volume down",
                    "🔇 Mute" to "volume mute",
                    "📸 Screenshot" to "screenshot",
                    "🏠 Home" to "go home",
                    "⬅ Back" to "go back",
                    "📶 Wi-Fi Settings" to "wifi settings",
                    "🔵 Bluetooth" to "bluetooth settings",
                    "⏰ 5m Timer" to "timer 5 min",
                    "🔋 Battery" to "battery",
                    "🕒 Time" to "koyta baje",
                    "📷 Camera" to "open camera"
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    offlineActions.forEach { (label, cmd) ->
                        FilledTonalButton(
                            onClick = { onRunCommand(cmd) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(label, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // BOX 2: 🌐 ONLINE AVAILABLE SMART CONTROLS (ইন্টারনেট থাকলেই চালু, নইলে ক্লিন নোটিশ)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0C1A2A),
            border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🌐 Online Smart Controls",
                        color = Color(0xFF67E8F9),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isInternetConnected) "● Online Ready" else "○ Needs Internet",
                        color = if (isInternetConnected) Color(0xFF34D399) else Color(0xFFF87171),
                        fontSize = 11.sp
                    )
                }

                val onlineActions = listOf(
                    "▶ Open YouTube" to "open youtube",
                    "💬 Open WhatsApp" to "open whatsapp",
                    "🌐 Open Chrome" to "open chrome",
                    "🗺 Open Maps" to "open maps",
                    "🤖 AI Smart Command" to "আমার ব্যাটারি কত আর এখন কয়টা বাজে বলো"
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    onlineActions.forEach { (label, cmd) ->
                        OutlinedButton(
                            onClick = { onRunCommand(cmd) },
                            enabled = isInternetConnected || cmd.startsWith("open "),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(label, fontSize = 12.sp, color = Color(0xFFE0F2FE))
                        }
                    }
                }
            }
        }
    }
}
