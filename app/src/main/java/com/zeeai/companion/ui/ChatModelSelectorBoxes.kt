package com.zeeai.companion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ai.ActiveEngineRoute
import com.zeeai.companion.offline.DiscoveredOfflineModel

@Composable
fun ChatDualModelSelectorBoxes(
    activeRoute: ActiveEngineRoute,
    isInternetConnected: Boolean,
    onlineModels: List<String>,
    selectedOnlineModel: String,
    offlineModels: List<DiscoveredOfflineModel>,
    selectedOfflineModelPath: String?,
    onSelectOnlineModel: (String) -> Unit,
    onSelectOfflineModel: (DiscoveredOfflineModel) -> Unit,
    onOpenModelsTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // BOX 1: 🌐 ONLINE AVAILABLE MODELS
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0C1826),
            border = BorderStroke(
                width = if (activeRoute == ActiveEngineRoute.ONLINE) 1.5.dp else 1.dp,
                color = if (activeRoute == ActiveEngineRoute.ONLINE) Color(0xFF00E5FF) else Color(0xFF1F2D3D)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🌐 Online Available Models",
                        color = Color(0xFF67E8F9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isInternetConnected) "● Connected" else "○ No Internet",
                        color = if (isInternetConnected) Color(0xFF34D399) else Color(0xFFF87171),
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val cleanOnline = onlineModels.ifEmpty {
                        listOf("gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")
                    }
                    cleanOnline.forEach { modelId ->
                        val isSelected = activeRoute == ActiveEngineRoute.ONLINE && selectedOnlineModel == modelId
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectOnlineModel(modelId) },
                            label = { Text(modelId, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0284C7),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF132233),
                                labelColor = Color(0xFFCBD5E1)
                            )
                        )
                    }
                }
            }
        }

        // BOX 2: 📴 OFFLINE DOWNLOADED MODELS (Zero "Model Not Found")
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF151324),
            border = BorderStroke(
                width = if (activeRoute == ActiveEngineRoute.OFFLINE) 1.5.dp else 1.dp,
                color = if (activeRoute == ActiveEngineRoute.OFFLINE) Color(0xFFF59E0B) else Color(0xFF2B2640)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📴 Offline Downloaded Models",
                        color = Color(0xFFFBBF24),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${offlineModels.size} Ready",
                        color = if (offlineModels.isNotEmpty()) Color(0xFF34D399) else Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (offlineModels.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "কোনো অফলাইন মডেল ডাউনলোড করা নেই",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        TextButton(onClick = onOpenModelsTab) {
                            Text("Download Gemma →", color = Color(0xFFFBBF24), fontSize = 11.sp)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        offlineModels.forEach { model ->
                            val isSelected = activeRoute == ActiveEngineRoute.OFFLINE &&
                                selectedOfflineModelPath == model.filePath
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectOfflineModel(model) },
                                label = { Text("${model.displayName} (${model.sizeMb}MB)", fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFD97706),
                                    selectedLabelColor = Color.White,
                                    containerColor = Color(0xFF211C35),
                                    labelColor = Color(0xFFE2E8F0)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
