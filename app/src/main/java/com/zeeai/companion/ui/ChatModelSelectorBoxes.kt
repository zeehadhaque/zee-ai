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

@Composable
fun AutoOnlineEngineBanner(
    isInternetConnected: Boolean,
    activeModelName: String,
    hasApiKey: Boolean,
    onOpenKeysSettings: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0C1826),
        border = BorderStroke(
            width = 1.dp,
            color = if (isInternetConnected && hasApiKey) Color(0xFF00E5FF).copy(alpha = 0.55f) else Color(0xFFF59E0B).copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (hasApiKey) "✨ Auto Engine: $activeModelName" else "🔑 API Key প্রয়োজন",
                    color = Color(0xFF67E8F9),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        !hasApiKey -> "Settings থেকে Google AI Studio Key দিন"
                        isInternetConnected -> "Live auto-failover active • Zero manual setup"
                        else -> "No internet (Phone controls still active)"
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp
                )
            }
            if (!hasApiKey) {
                TextButton(
                    onClick = onOpenKeysSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Add Key →", color = Color(0xFF38BDF8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text(
                    text = if (isInternetConnected) "● Online" else "○ Offline",
                    color = if (isInternetConnected) Color(0xFF34D399) else Color(0xFFF87171),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
