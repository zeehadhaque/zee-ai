package com.zeeai.companion.ui.jarvis

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zeeai.companion.R
import com.zeeai.companion.jarvis.*
import com.zeeai.companion.ui.components.LuxuryGlassCard
import com.zeeai.companion.ui.components.LuxuryGlassPill
import com.zeeai.companion.ui.components.LuxuryGlassTextField
import com.zeeai.companion.ui.theme.LuxuryPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// -----------------------------------------------------------------------------
// 1. CODE BLACK TACTICAL LOCKDOWN SCREEN
// -----------------------------------------------------------------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CodeBlackOverlayScreen(
    tamperCount: Int,
    alertNote: String?,
    onRecordTamper: () -> Unit,
    onUnlock: () -> Unit,
    onVoiceUnlock: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "codeBlack")
    val pulse by infinite.animateFloat(
        initialValue = 0.25f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cbPulse"
    )
    var cornerTapCount by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF030001))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onRecordTamper() }
    ) {
        // CRT Red Scanlines + Radial Threat Aura
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF450A0A).copy(alpha = 0.50f * pulse), Color.Transparent),
                    radius = size.maxDimension * 0.7f
                )
            )
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = Color.Red.copy(alpha = 0.045f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f
                )
                y += 4.dp.toPx()
            }
        }

        // Secret Top-Right 4-Tap Unlock Area
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(68.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    cornerTapCount++
                    if (cornerTapCount >= 4) {
                        cornerTapCount = 0
                        onUnlock()
                    }
                }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Terminal Banner
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "STARK SECURITY KERNEL // LOCKED",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.8.sp
                )
                Text(
                    text = "TIMESTAMP ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}",
                    color = Color(0xFFFF8A80).copy(alpha = 0.65f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Center Bio-Threat Crest
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .border(2.dp, Color(0xFFFF1744).copy(alpha = 0.65f * pulse), CircleShape)
                        .background(Color(0xFF280408).copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GppBad,
                        contentDescription = null,
                        tint = Color(0xFFFF1744).copy(alpha = 0.85f + 0.15f * pulse),
                        modifier = Modifier.size(64.dp)
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "CODE BLACK",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "UNAUTHORIZED ACCESS PROHIBITED • TAMPER EVENTS: $tamperCount",
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (!alertNote.isNullOrBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF3F070C),
                        border = BorderStroke(1.dp, Color(0xFFFF1744).copy(alpha = 0.65f))
                    ) {
                        Text(
                            text = alertNote,
                            color = Color(0xFFFFCDD2),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // Bottom Override Protocols
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1F060A),
                    border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onVoiceUnlock() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Mic, null, tint = Color(0xFFFF5252), modifier = Modifier.size(16.dp))
                        Text(
                            text = "VOICE OVERRIDE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF2D060B),
                    border = BorderStroke(1.2.dp, Color(0xFFFF1744).copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(
                            onClick = { onRecordTamper() },
                            onDoubleClick = { onUnlock() },
                            onLongClick = { onUnlock() }
                        )
                ) {
                    Text(
                        text = "BOSS OVERRIDE // DOUBLE-TAP OR LONG-PRESS TO DISARM",
                        color = Color(0xFFFFCDD2),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 14.dp, horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 2. BRAIN DUMP PARALLEL MULTI-TASKING DIALOG
// -----------------------------------------------------------------------------
@Composable
fun BrainDumpDialog(
    superpowers: StarkSuperpowersEngine,
    apiKey: String,
    baseUrl: String,
    model: String,
    presetId: String,
    onSpeakSummary: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<BrainDumpExecutionResult?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp),
            glowColor = LuxuryPalette.IndigoLight,
            borderColor = LuxuryPalette.IndigoLight.copy(alpha = 0.4f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Hub, null, tint = LuxuryPalette.IndigoLight)
                    Text("MULTI-TASK BRAIN DUMP", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }
            Text(
                text = "Give multiple mixed instructions at once — ZEE decomposes and executes every thread in parallel for Boss.",
                color = LuxuryPalette.MutedSlate,
                fontSize = 11.5.sp
            )
            Spacer(Modifier.height(10.dp))

            // Preset chips
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "Remind me to check emails, add buy coffee todo, turn on flashlight",
                    "Note Wi-Fi code is 884422, calculate 45 * 18, draft a crisp standup note"
                ).forEach { sample ->
                    LuxuryGlassPill(
                        label = sample.take(30) + "...",
                        color = LuxuryPalette.ElectricCyan,
                        onClick = { inputText = sample }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            LuxuryGlassTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = "Chaotic Brain Dump Input",
                placeholder = "e.g. Remind me 5pm call, note project X deadline, set volume 30%...",
                singleLine = false,
                maxLines = 4
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (inputText.isNotBlank() && !isRunning) {
                        scope.launch {
                            isRunning = true
                            val res = superpowers.executeParallelBrainDump(inputText, apiKey, baseUrl, model, presetId)
                            result = res
                            isRunning = false
                            onSpeakSummary(res.spokenSummary)
                        }
                    }
                },
                enabled = !isRunning && inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.RoyalIndigo),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isRunning) "EXECUTING PARALLEL THREADS..." else "LAUNCH PARALLEL EXECUTION")
            }

            result?.let { exec ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Completed ${exec.items.size} threads in ${exec.elapsedMs}ms",
                    color = LuxuryPalette.EmeraldNeon,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(exec.items) { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.04f),
                            border = BorderStroke(1.dp, LuxuryPalette.ElectricCyan.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LuxuryGlassPill(label = item.type, color = LuxuryPalette.ElectricCyan)
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.detail, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    Text(item.result, color = LuxuryPalette.SilverText, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 3. ZERO-TOUCH AIR GESTURES SONAR HUD DIALOG
// -----------------------------------------------------------------------------
@Composable
fun AirGesturesHudDialog(
    sensors: HardwareSensorsController,
    onDismiss: () -> Unit
) {
    val isEnabled by sensors.airGesturesEnabled.collectAsState()
    val statusText by sensors.airGestureStatus.collectAsState()
    val infinite = rememberInfiniteTransition(label = "sonar")
    val wave by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing)),
        label = "sonarWave"
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            glowColor = LuxuryPalette.ElectricCyan,
            borderColor = LuxuryPalette.ElectricCyan.copy(alpha = 0.45f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ZERO-TOUCH AIR GESTURES", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            // Concentric Sonar Field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(160.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.minDimension / 2f
                    val ringColor = if (isEnabled) LuxuryPalette.EmeraldNeon else LuxuryPalette.ElectricCyan
                    for (i in 0..2) {
                        val p = (wave + i * 0.33f) % 1f
                        drawCircle(
                            color = ringColor.copy(alpha = (1f - p) * 0.55f),
                            radius = maxR * p,
                            style = Stroke(width = 2.4f)
                        )
                    }
                    drawCircle(color = ringColor.copy(alpha = 0.25f), radius = maxR * 0.28f)
                }
                Icon(
                    imageVector = Icons.Rounded.Sensors,
                    contentDescription = null,
                    tint = if (isEnabled) LuxuryPalette.EmeraldNeon else LuxuryPalette.ElectricCyan,
                    modifier = Modifier.size(34.dp)
                )
            }

            Text(
                text = statusText,
                color = LuxuryPalette.ChampagneGold,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))

            Button(
                onClick = { sensors.toggleAirGestures() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isEnabled) LuxuryPalette.DangerRed else LuxuryPalette.EmeraldNeon
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isEnabled) "DISARM PROXIMITY SENSOR" else "ARM HARDWARE PROXIMITY SENSOR",
                    color = if (isEnabled) Color.White else LuxuryPalette.ObsidianVoid,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(10.dp))
            Text("Quick Simulation Tests:", color = LuxuryPalette.MutedSlate, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LuxuryGlassPill("👋 Wave (Next)", LuxuryPalette.ElectricCyan, modifier = Modifier.weight(1f)) {
                    sensors.simulateGesture("wave")
                }
                LuxuryGlassPill("✋ Hover (Play)", LuxuryPalette.SolarGold, modifier = Modifier.weight(1f)) {
                    sensors.simulateGesture("palm_hover")
                }
                LuxuryGlassPill("✌️ Shot", LuxuryPalette.QuantumViolet, modifier = Modifier.weight(1f)) {
                    sensors.simulateGesture("double_wave")
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 4. ARC REACTOR CHARGING HUD DIALOG
// -----------------------------------------------------------------------------
@Composable
fun ArcReactorChargingDialog(
    telemetry: ChargingTelemetrySnapshot,
    onDismiss: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "chargingSpin")
    val spin by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "chgAngle"
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            glowColor = LuxuryPalette.SolarGold,
            borderColor = LuxuryPalette.SolarGold.copy(alpha = 0.45f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ARC REACTOR POWER FLUX", color = LuxuryPalette.ChampagneGold, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(185.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = if (telemetry.isCharging) R.drawable.jarvis_core_gold else R.drawable.jarvis_core_cyan),
                    contentDescription = "Reactor Stator",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(145.dp)
                        .clip(CircleShape)
                        .graphicsLayer { rotationZ = spin }
                )
                Canvas(modifier = Modifier.size(175.dp)) {
                    val strokeColor = if (telemetry.isCharging) LuxuryPalette.SolarGold else LuxuryPalette.ElectricCyan
                    drawArc(
                        color = strokeColor,
                        startAngle = spin,
                        sweepAngle = 240f,
                        useCenter = false,
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.6f),
                        startAngle = -spin * 1.3f,
                        sweepAngle = 95f,
                        useCenter = false,
                        style = Stroke(width = 3f, cap = StrokeCap.Round)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${telemetry.batteryLevel}%", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                    Text(if (telemetry.isCharging) "CHARGING" else "DISCHARGING", color = LuxuryPalette.ChampagneGold, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox("POWER", "${telemetry.wattage} W", LuxuryPalette.SolarGold, Modifier.weight(1f))
                MetricBox("VOLTAGE", "${"%.2f".format(telemetry.voltageVolts)} V", LuxuryPalette.ElectricCyan, Modifier.weight(1f))
                MetricBox("TEMP", "${telemetry.temperatureC}°C", LuxuryPalette.EmeraldNeon, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Source: ${telemetry.chargeType} (${telemetry.currentMa} mA)", color = LuxuryPalette.MutedSlate, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun MetricBox(label: String, valStr: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = LuxuryPalette.MutedSlate, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(2.dp))
            Text(valStr, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
        }
    }
}

// -----------------------------------------------------------------------------
// 5. AIRSPACE & ORBITAL RADAR DIALOG
// -----------------------------------------------------------------------------
@Composable
fun AirspaceRadarDialog(
    superpowers: StarkSuperpowersEngine,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<AirspaceRadarSnapshot?>(null) }
    var scanning by remember { mutableStateOf(true) }

    val infinite = rememberInfiniteTransition(label = "radarSweep")
    val sweepAngle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "sweep"
    )

    LaunchedEffect(Unit) {
        scanning = true
        snapshot = superpowers.scanLocalAirspace()
        scanning = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.94f),
            glowColor = LuxuryPalette.EmeraldNeon,
            borderColor = LuxuryPalette.EmeraldNeon.copy(alpha = 0.45f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ORBITAL & AIRSPACE RADAR", color = LuxuryPalette.EmeraldNeon, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            // Sweeping Phosphor Radar Scope
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(162.dp)) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val maxR = size.minDimension / 2f
                    drawCircle(color = Color(0xFF04140B), radius = maxR)
                    listOf(0.33f, 0.66f, 1.0f).forEach { f ->
                        drawCircle(color = LuxuryPalette.EmeraldNeon.copy(alpha = 0.28f), radius = maxR * f, style = Stroke(1.3f))
                    }
                    drawLine(LuxuryPalette.EmeraldNeon.copy(alpha = 0.22f), Offset(c.x - maxR, c.y), Offset(c.x + maxR, c.y), 1f)
                    drawLine(LuxuryPalette.EmeraldNeon.copy(alpha = 0.22f), Offset(c.x, c.y - maxR), Offset(c.x, c.y + maxR), 1f)

                    drawArc(
                        brush = Brush.sweepGradient(
                            colors = listOf(Color.Transparent, LuxuryPalette.EmeraldNeon.copy(alpha = 0.45f)),
                            center = c
                        ),
                        startAngle = sweepAngle - 50f,
                        sweepAngle = 50f,
                        useCenter = true
                    )

                    // Target Blips
                    snapshot?.targets?.forEachIndexed { idx, tgt ->
                        val ang = (tgt.headingDeg + idx * 55) * PI / 180.0
                        val dist = maxR * (0.28f + ((idx * 19) % 62) / 100f)
                        val pt = Offset(c.x + (dist * cos(ang)).toFloat(), c.y + (dist * sin(ang)).toFloat())
                        drawCircle(
                            color = if (tgt.isIss) LuxuryPalette.SolarGold else LuxuryPalette.EmeraldNeon,
                            radius = if (tgt.isIss) 5.5f else 4f,
                            center = pt
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(snapshot?.sourceLabel ?: "ACQUIRING TELEMETRY...", color = LuxuryPalette.SilverText, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                LuxuryGlassPill(label = if (scanning) "SCANNING..." else "RESCAN", color = LuxuryPalette.EmeraldNeon) {
                    if (!scanning) {
                        scope.launch {
                            scanning = true
                            snapshot = superpowers.scanLocalAirspace()
                            scanning = false
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(snapshot?.targets.orEmpty()) { tgt ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, (if (tgt.isIss) LuxuryPalette.SolarGold else LuxuryPalette.EmeraldNeon).copy(alpha = 0.30f))
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(tgt.callsign, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                                Text(tgt.origin, color = LuxuryPalette.MutedSlate, fontSize = 10.5.sp)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("ALT ${tgt.altitudeM}m", color = LuxuryPalette.ElectricCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text("${tgt.velocityKmh} km/h • HDG ${tgt.headingDeg}°", color = LuxuryPalette.SilverText, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 6. TACTICAL NETWORK SECURITY AUDITOR DIALOG
// -----------------------------------------------------------------------------
@Composable
fun NetworkAuditDialog(
    superpowers: StarkSuperpowersEngine,
    onSpeakSummary: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var audit by remember { mutableStateOf<NetworkAuditSnapshot?>(null) }
    var scanning by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        scanning = true
        val res = superpowers.auditLocalNetwork()
        audit = res
        scanning = false
        onSpeakSummary(res.summary)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.94f),
            glowColor = LuxuryPalette.ElectricCyan,
            borderColor = LuxuryPalette.ElectricCyan.copy(alpha = 0.45f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("LAN SECURITY AUDITOR", color = LuxuryPalette.ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            if (scanning) {
                Row(modifier = Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = LuxuryPalette.ElectricCyan, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Probing local subnet & ports (21, 22, 23, 80, 443, 554, 8080, 8123)...", color = LuxuryPalette.SilverText, fontSize = 12.sp)
                }
            } else {
                audit?.let { res ->
                    val scoreColor = when {
                        res.securityScore >= 85 -> LuxuryPalette.EmeraldNeon
                        res.securityScore >= 60 -> LuxuryPalette.SolarGold
                        else -> LuxuryPalette.CrimsonAlert
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricBox("POSTURE", "${res.securityScore}/100", scoreColor, Modifier.weight(1f))
                        MetricBox("LOCAL IP", res.localIp, LuxuryPalette.ElectricCyan, Modifier.weight(1f))
                        MetricBox("GATEWAY", res.gatewayIp, LuxuryPalette.IndigoLight, Modifier.weight(1f))
                    }
                    if (res.vulnerabilities.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = LuxuryPalette.CrimsonAlert.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, LuxuryPalette.CrimsonAlert.copy(alpha = 0.45f))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("EXPOSED VULNERABILITIES", color = Color(0xFFFF8A80), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                res.vulnerabilities.forEach { v ->
                                    Text("• $v", color = Color(0xFFFFCDD2), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(res.hosts) { h ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White.copy(alpha = 0.04f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${h.ip} (${h.serviceHint})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        Text(
                                            h.riskLabel,
                                            color = if (h.riskLabel == "SECURE") LuxuryPalette.EmeraldNeon else LuxuryPalette.SolarGold,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                    Text(
                                        "MAC: ${h.mac} • Open Ports: ${if (h.openPorts.isEmpty()) "Stealth / None" else h.openPorts.joinToString(", ")}",
                                        color = LuxuryPalette.MutedSlate,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                scanning = true
                                audit = superpowers.auditLocalNetwork()
                                scanning = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.RoyalIndigo),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("RE-RUN TACTICAL AUDIT") }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 7. OPTICAL AR PROBLEM SOLVER DIALOG
// -----------------------------------------------------------------------------
@Composable
fun OpticalSolverDialog(
    superpowers: StarkSuperpowersEngine,
    selectedImageLabel: String?,
    selectedImageUri: android.net.Uri?,
    apiKey: String,
    baseUrl: String,
    model: String,
    presetId: String,
    onPickGalleryImage: () -> Unit,
    onCaptureCameraPhoto: () -> Unit,
    onSpeakSolution: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val modes = listOf("Auto", "Math", "Code", "Bills & Receipts", "Hardware & Circuits")
    var selectedMode by remember { mutableStateOf("Auto") }
    var queryText by remember { mutableStateOf("") }
    var solving by remember { mutableStateOf(false) }
    var solveResult by remember { mutableStateOf<OpticalSolveResult?>(null) }

    val infinite = rememberInfiniteTransition(label = "arLaser")
    val laserY by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse),
        label = "laser"
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .verticalScroll(rememberScrollState()),
            glowColor = LuxuryPalette.SolarGold,
            borderColor = LuxuryPalette.SolarGold.copy(alpha = 0.45f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("OPTICAL AR PROBLEM SOLVER", color = LuxuryPalette.ChampagneGold, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            // Mode Chips
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modes.forEach { m ->
                    LuxuryGlassPill(label = m, color = LuxuryPalette.SolarGold, isSelected = selectedMode == m) {
                        selectedMode = m
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // Sweeping Gold AR Viewfinder Stage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(105.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF060911))
                    .border(1.dp, LuxuryPalette.SolarGold.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val y = 12f + (size.height - 24f) * laserY
                    drawLine(
                        brush = Brush.horizontalGradient(listOf(Color.Transparent, LuxuryPalette.SolarGold, Color.Transparent)),
                        start = Offset(10f, y),
                        end = Offset(size.width - 10f, y),
                        strokeWidth = 3f
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CenterFocusStrong, null, tint = LuxuryPalette.SolarGold, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = selectedImageLabel ?: "Capture Camera Photo or Pick Gallery Image",
                        color = LuxuryPalette.SilverText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCaptureCameraPhoto, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CameraAlt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Camera", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onPickGalleryImage, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Gallery", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            LuxuryGlassTextField(
                value = queryText,
                onValueChange = { queryText = it },
                label = "Problem / Equation / Context Note",
                placeholder = "e.g. Solve equation or explain circuit schematic...",
                singleLine = false,
                maxLines = 3
            )
            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    scope.launch {
                        solving = true
                        solveResult = superpowers.solveOpticalProblem(selectedMode, queryText, selectedImageUri, apiKey, baseUrl, model, presetId)
                        solving = false
                    }
                },
                enabled = !solving && (selectedImageUri != null || queryText.isNotBlank()),
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.SolarGold),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (solving) "ANALYZING TARGET..." else "SOLVE WITH AR ENGINE",
                    color = LuxuryPalette.ObsidianVoid,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            solveResult?.let { sol ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, LuxuryPalette.SolarGold.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${sol.mode} • ${sol.confidencePct}% CONF • ${sol.elapsedMs}ms", color = LuxuryPalette.ChampagneGold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Text(
                                "🔊 SPEAK",
                                color = LuxuryPalette.ElectricCyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onSpeakSolution(sol.solution) }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(sol.solution, color = Color.White, fontSize = 12.5.sp, lineHeight = 19.sp)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// 8. POLYGLOT DIPLOMAT TRANSLATOR DIALOG (15 LANGUAGES)
// -----------------------------------------------------------------------------
@Composable
fun PolyglotDiplomatDialog(
    superpowers: StarkSuperpowersEngine,
    apiKey: String,
    baseUrl: String,
    model: String,
    presetId: String,
    onSpeakText: (text: String, localeTag: String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val langs = superpowers.supportedLanguages.map { it.name }
    var sourceLang by remember { mutableStateOf("English") }
    var targetLang by remember { mutableStateOf("Bengali") }
    var inputText by remember { mutableStateOf("") }
    var translating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PolyglotTranslationResult?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .verticalScroll(rememberScrollState()),
            glowColor = LuxuryPalette.SkyAzure,
            borderColor = LuxuryPalette.SkyAzure.copy(alpha = 0.45f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("POLYGLOT DIPLOMAT (15 LANGS)", color = LuxuryPalette.SkyAzure, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From:", color = LuxuryPalette.MutedSlate, fontSize = 11.sp)
                Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    langs.forEach { l ->
                        LuxuryGlassPill(l, LuxuryPalette.IndigoLight, isSelected = sourceLang == l) { sourceLang = l }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("To:    ", color = LuxuryPalette.MutedSlate, fontSize = 11.sp)
                Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    langs.forEach { l ->
                        LuxuryGlassPill(l, LuxuryPalette.SkyAzure, isSelected = targetLang == l) { targetLang = l }
                    }
                }
                IconButton(onClick = { val t = sourceLang; sourceLang = targetLang; targetLang = t }) {
                    Icon(Icons.Rounded.SwapHoriz, "Swap", tint = LuxuryPalette.ElectricCyan)
                }
            }

            Spacer(Modifier.height(8.dp))
            LuxuryGlassTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = "$sourceLang Phrase",
                placeholder = "Type sentence to translate for Boss...",
                singleLine = false,
                maxLines = 3
            )
            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    scope.launch {
                        translating = true
                        result = superpowers.translatePolyglot(inputText, sourceLang, targetLang, apiKey, baseUrl, model, presetId)
                        translating = false
                    }
                },
                enabled = !translating && inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.SkyAzure),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (translating) "TRANSLATING..." else "TRANSLATE & PREPARE VOICE", color = LuxuryPalette.ObsidianVoid, fontWeight = FontWeight.ExtraBold)
            }

            result?.let { tr ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = LuxuryPalette.SkyAzure.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, LuxuryPalette.SkyAzure.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(tr.targetLang.uppercase(), color = LuxuryPalette.SkyAzure, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            LuxuryGlassPill("🔊 Speak", LuxuryPalette.EmeraldNeon) {
                                onSpeakText(tr.translatedText, tr.ttsLocaleTag)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(tr.translatedText, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}
