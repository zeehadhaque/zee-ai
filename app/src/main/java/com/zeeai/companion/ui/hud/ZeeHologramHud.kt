package com.zeeai.companion.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.R
import com.zeeai.companion.jarvis.HudOperationalPhase
import com.zeeai.companion.jarvis.HudVisualStyle
import com.zeeai.companion.jarvis.ProactiveSuggestionEvent
import com.zeeai.companion.ui.theme.LuxuryPalette
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full Interactive 3D Iron Man / JARVIS Hologram Stage ported A-to-Z
 * with Dual Visual Modes (Arc Reactor Sphere vs 3D Suit Bust),
 * 5-State Aura Morphing, 3D Gyroscope Drag/Tilt, Vocal Spectrum Equalizer,
 * Tap Ripple Mic Activation, Corner Telemetry & Bottom Luxury Action Pods.
 */
@Composable
fun ZeeHologramHud(
    phase: HudOperationalPhase,
    hudStyle: HudVisualStyle,
    accentPrimary: Color,
    statusMessage: String,
    activeModelLabel: String,
    activeRouteLabel: String,
    micRms: Float,
    alwaysOnEnabled: Boolean,
    airGesturesEnabled: Boolean,
    isRecordingMacro: Boolean,
    overlayActive: Boolean,
    sentryArmed: Boolean,
    activeGestureBanner: String?,
    proactiveBanner: ProactiveSuggestionEvent?,
    onToggleListening: () -> Unit,
    onStopAll: () -> Unit,
    onToggleAlwaysOn: () -> Unit,
    onOpenBrainDump: () -> Unit,
    onTriggerHermesEvolve: () -> Unit,
    onOpenAirGestures: () -> Unit,
    onToggleHudStyle: () -> Unit,
    onToggleMacro: () -> Unit,
    onOpenAirspaceRadar: () -> Unit,
    onOpenNetworkAudit: () -> Unit,
    onOpenOpticalSolver: () -> Unit,
    onOpenPolyglot: () -> Unit,
    onOpenChargingHud: () -> Unit,
    onToggleOverlay: () -> Unit,
    onTriggerCodeBlack: () -> Unit,
    onAcceptProactive: (ProactiveSuggestionEvent) -> Unit,
    onDismissProactive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current.density

    val isSpeaking = phase == HudOperationalPhase.SPEAKING
    val isThinking = phase == HudOperationalPhase.THINKING || phase == HudOperationalPhase.EXECUTING
    val isListening = phase == HudOperationalPhase.LISTENING
    val isAlert = phase == HudOperationalPhase.ALERT

    // Continuous Core Motion Clocks
    val infinite = rememberInfiniteTransition(label = "hudClocks")
    val spinFast by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing)),
        label = "spinFast"
    )
    val spinSlow by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(15000, easing = LinearEasing)),
        label = "spinSlow"
    )
    val pulseVal by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseVal"
    )
    val vocalWave by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(680, easing = LinearEasing)),
        label = "vocalWave"
    )
    val auraDrift by infinite.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6200, easing = LinearEasing)),
        label = "auraDrift"
    )

    // Interactive 3D Touch Spin / Tilt + Tap Ripple
    var dragYaw by remember { mutableFloatStateOf(0f) }
    var dragPitch by remember { mutableFloatStateOf(0f) }
    var tapRippleProgress by remember { mutableFloatStateOf(0f) }
    var rippleActive by remember { mutableStateOf(false) }

    LaunchedEffect(rippleActive) {
        if (rippleActive) {
            tapRippleProgress = 0f
            val steps = 22
            for (i in 1..steps) {
                tapRippleProgress = i / steps.toFloat()
                delay(22L)
            }
            rippleActive = false
            tapRippleProgress = 0f
        }
    }

    val smoothLevel by animateFloatAsState(
        targetValue = if (isListening) (0.28f + (micRms.coerceIn(0f, 12f) / 12f) * 0.72f)
        else if (isSpeaking) 0.55f
        else 0.14f,
        animationSpec = tween(160),
        label = "smoothLevel"
    )

    // Dynamic 5-State Aura Color (matching Jarvis Aura Morphing)
    val targetAura = when {
        isAlert -> LuxuryPalette.CrimsonAlert
        isListening -> LuxuryPalette.EmeraldNeon
        isThinking -> LuxuryPalette.SolarGold
        isSpeaking -> {
            val t = (sin(auraDrift * 2.0 * PI) + 1.0).toFloat() / 2f
            if (t < 0.5f) Color(0xFF818CF8) else LuxuryPalette.QuantumViolet
        }
        else -> accentPrimary
    }
    val activeAura by animateColorAsState(
        targetValue = targetAura,
        animationSpec = tween(420),
        label = "activeAura"
    )

    // Conversational Pacing Kinematics for Suit Bust
    val speakSwayY = if (isSpeaking) (sin(vocalWave * 2 * PI) * 0.045f + sin(auraDrift * 2 * PI) * 0.025f).toFloat()
    else (sin(auraDrift * 2 * PI) * 0.015f).toFloat()
    val speakTiltX = if (isSpeaking) (cos(vocalWave * 2 * PI) * 0.02f).toFloat() else 0f
    val speechAuraScale = 1.0f +
        (if (isSpeaking) sin(vocalWave * 2 * PI).toFloat() * 0.032f else 0f) +
        pulseVal * 0.014f +
        smoothLevel * 0.06f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF010308))
    ) {
        // LAYER 1: Ambient Cyber Perspective Grid & Radial Glow
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAura.copy(alpha = 0.18f),
                        LuxuryPalette.DeepSapphire.copy(alpha = 0.45f),
                        Color(0xFF000000)
                    ),
                    center = Offset(size.width / 2f, size.height * 0.46f),
                    radius = size.maxDimension * 0.65f
                )
            )
            // Subtle perspective cyber grid
            val step = 36.dp.toPx()
            val gridAlpha = 0.06f
            var x = 0f
            while (x < size.width) {
                drawLine(activeAura.copy(alpha = gridAlpha), Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.8f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(activeAura.copy(alpha = gridAlpha), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.8f)
                y += step
            }

            // Corner targeting L-brackets
            val pad = 16.dp.toPx()
            val arm = 24.dp.toPx()
            val bColor = activeAura.copy(alpha = 0.50f)
            // Top-Left
            drawLine(bColor, Offset(pad, pad), Offset(pad + arm, pad), 2.2f)
            drawLine(bColor, Offset(pad, pad), Offset(pad, pad + arm), 2.2f)
            // Top-Right
            drawLine(bColor, Offset(size.width - pad - arm, pad), Offset(size.width - pad, pad), 2.2f)
            drawLine(bColor, Offset(size.width - pad, pad), Offset(size.width - pad, pad + arm), 2.2f)
            // Bottom-Left
            drawLine(bColor, Offset(pad, size.height - pad - arm), Offset(pad, size.height - pad), 2.2f)
            drawLine(bColor, Offset(pad, size.height - pad), Offset(pad + arm, size.height - pad), 2.2f)
            // Bottom-Right
            drawLine(bColor, Offset(size.width - pad - arm, size.height - pad), Offset(size.width - pad, size.height - pad), 2.2f)
            drawLine(bColor, Offset(size.width - pad, size.height - pad - arm), Offset(size.width - pad, size.height - pad), 2.2f)
        }

        // LAYER 2: Top Corner Diagnostic Telemetry & Dynamic Status Pill
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = if (hudStyle == HudVisualStyle.HOLOGRAM_SUIT) "SUIT MARK-LXXXV // LINKED" else "ARC CORE // ONLINE",
                        color = activeAura.copy(alpha = 0.78f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "$activeRouteLabel • $activeModelLabel",
                        color = LuxuryPalette.SilverText.copy(alpha = 0.72f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 175.dp)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (sentryArmed) "SENTRY: ARMED" else "HERMES V3.5",
                        color = if (sentryArmed) LuxuryPalette.CrimsonAlert else activeAura.copy(alpha = 0.78f),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = if (isListening) "MIC GAIN ${(smoothLevel * 100).toInt()}%" else "LATENCY <50MS",
                        color = LuxuryPalette.MutedSlate,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Dynamic Status Capsule + Stop Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = activeAura.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, activeAura.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(activeAura, CircleShape)
                        )
                        Text(
                            text = statusMessage,
                            color = Color.White,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 220.dp)
                        )
                    }
                }
                if (isListening || isSpeaking || isThinking) {
                    Surface(
                        shape = CircleShape,
                        color = LuxuryPalette.CrimsonAlert.copy(alpha = 0.22f),
                        border = BorderStroke(1.dp, LuxuryPalette.CrimsonAlert.copy(alpha = 0.65f)),
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { onStopAll() }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = "Stop",
                                tint = Color(0xFFFF8A80),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Proactive Banner / Air Gesture Live Feedback
            AnimatedVisibility(
                visible = activeGestureBanner != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                activeGestureBanner?.let { banner ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = LuxuryPalette.SolarGold.copy(alpha = 0.18f),
                        border = BorderStroke(1.dp, LuxuryPalette.SolarGold.copy(alpha = 0.55f)),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = banner,
                            color = LuxuryPalette.ChampagneGold,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = proactiveBanner != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                proactiveBanner?.let { evt ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0D172B).copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, activeAura.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = activeAura, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(evt.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(evt.message, color = LuxuryPalette.MutedSlate, fontSize = 10.5.sp, maxLines = 2)
                            }
                            Text(
                                text = "RUN",
                                color = LuxuryPalette.EmeraldNeon,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(LuxuryPalette.EmeraldNeon.copy(alpha = 0.16f))
                                    .clickable { onAcceptProactive(evt) }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Dismiss",
                                tint = LuxuryPalette.MutedSlate,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDismissProactive() }
                            )
                        }
                    }
                }
            }
        }

        // LAYER 3: Interactive 3D Centerpiece Hologram Stage
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 84.dp, bottom = 132.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            rippleActive = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onToggleListening()
                        },
                        onDoubleTap = {
                            dragYaw = 0f
                            dragPitch = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        dragYaw += dragAmount.x * 0.25f
                        dragPitch = (dragPitch - dragAmount.y * 0.18f).coerceIn(-38f, 38f)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (hudStyle == HudVisualStyle.ARC_REACTOR) {
                ArcReactorStage(
                    activeAura = activeAura,
                    spinFast = spinFast,
                    spinSlow = spinSlow,
                    pulseVal = pulseVal,
                    vocalWave = vocalWave,
                    smoothLevel = smoothLevel,
                    speechScale = speechAuraScale,
                    isSpeaking = isSpeaking,
                    isThinking = isThinking,
                    isListening = isListening,
                    dragYaw = dragYaw,
                    dragPitch = dragPitch,
                    density = density
                )
            } else {
                HologramSuitBustStage(
                    activeAura = activeAura,
                    spinFast = spinFast,
                    spinSlow = spinSlow,
                    pulseVal = pulseVal,
                    vocalWave = vocalWave,
                    auraDrift = auraDrift,
                    smoothLevel = smoothLevel,
                    speechScale = speechAuraScale,
                    speakSwayY = speakSwayY,
                    speakTiltX = speakTiltX,
                    isSpeaking = isSpeaking,
                    isThinking = isThinking,
                    isListening = isListening,
                    dragYaw = dragYaw,
                    dragPitch = dragPitch,
                    density = density
                )
            }

            // Tap Shockwave Ripple
            if (rippleActive) {
                Canvas(modifier = Modifier.size(310.dp)) {
                    val maxR = size.minDimension / 2f
                    val curR = maxR * (0.25f + tapRippleProgress * 0.75f)
                    drawCircle(
                        color = activeAura.copy(alpha = (1f - tapRippleProgress) * 0.72f),
                        radius = curR,
                        style = Stroke(width = 4f * (1f - tapRippleProgress * 0.6f))
                    )
                }
            }
        }

        // LAYER 4: Bottom Action Pods Dock + Activator Cue
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xFF010308).copy(alpha = 0.92f))
                    )
                )
                .padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Scrollable Action Pods Row (All Jarvis Pods + Stark Tools)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HudActionPod(
                    label = "ALWAYS-ON",
                    icon = Icons.Rounded.Hearing,
                    activeColor = LuxuryPalette.EmeraldNeon,
                    isActive = alwaysOnEnabled,
                    onClick = onToggleAlwaysOn
                )
                HudActionPod(
                    label = "MULTITASK",
                    icon = Icons.Rounded.Hub,
                    activeColor = LuxuryPalette.IndigoLight,
                    isActive = false,
                    onClick = onOpenBrainDump
                )
                HudActionPod(
                    label = "SELF EVOLVE",
                    icon = Icons.Rounded.AutoAwesome,
                    activeColor = LuxuryPalette.QuantumViolet,
                    isActive = false,
                    onClick = onTriggerHermesEvolve
                )
                HudActionPod(
                    label = "AIR GESTURES",
                    icon = Icons.Rounded.Sensors,
                    activeColor = LuxuryPalette.ElectricCyan,
                    isActive = airGesturesEnabled,
                    onClick = onOpenAirGestures
                )
                HudActionPod(
                    label = if (hudStyle == HudVisualStyle.ARC_REACTOR) "SUIT BUST" else "ARC CORE",
                    icon = Icons.Rounded.ViewInAr,
                    activeColor = activeAura,
                    isActive = true,
                    onClick = onToggleHudStyle
                )
                HudActionPod(
                    label = if (isRecordingMacro) "SAVE MACRO" else "REC MACRO",
                    icon = if (isRecordingMacro) Icons.Rounded.Save else Icons.Rounded.FiberManualRecord,
                    activeColor = LuxuryPalette.CrimsonAlert,
                    isActive = isRecordingMacro,
                    onClick = onToggleMacro
                )
                HudActionPod(
                    label = "RADAR",
                    icon = Icons.Rounded.Radar,
                    activeColor = LuxuryPalette.EmeraldNeon,
                    isActive = false,
                    onClick = onOpenAirspaceRadar
                )
                HudActionPod(
                    label = "LAN AUDIT",
                    icon = Icons.Rounded.Security,
                    activeColor = LuxuryPalette.ElectricCyan,
                    isActive = false,
                    onClick = onOpenNetworkAudit
                )
                HudActionPod(
                    label = "OPTICAL AR",
                    icon = Icons.Rounded.CenterFocusStrong,
                    activeColor = LuxuryPalette.SolarGold,
                    isActive = false,
                    onClick = onOpenOpticalSolver
                )
                HudActionPod(
                    label = "TRANSLATE",
                    icon = Icons.Rounded.Translate,
                    activeColor = LuxuryPalette.SkyAzure,
                    isActive = false,
                    onClick = onOpenPolyglot
                )
                HudActionPod(
                    label = "CHARGING",
                    icon = Icons.Rounded.Bolt,
                    activeColor = LuxuryPalette.SolarGold,
                    isActive = false,
                    onClick = onOpenChargingHud
                )
                HudActionPod(
                    label = "OVERLAY",
                    icon = Icons.Rounded.PictureInPictureAlt,
                    activeColor = LuxuryPalette.ElectricCyan,
                    isActive = overlayActive,
                    onClick = onToggleOverlay
                )
                HudActionPod(
                    label = "CODE BLACK",
                    icon = Icons.Rounded.Lock,
                    activeColor = LuxuryPalette.CrimsonAlert,
                    isActive = false,
                    onClick = onTriggerCodeBlack
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    isListening -> "LISTENING TO BOSS... TAP ORB TO SEND"
                    isThinking -> "NEURAL CORTEX SYNTHESIZING..."
                    isSpeaking -> "ZEE AUDIO STREAMING // TAP TO INTERRUPT"
                    else -> "TAP HOLOGRAM TO TALK • DRAG TO ROTATE 3D • DOUBLE-TAP RESET"
                },
                color = activeAura.copy(alpha = 0.72f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun ArcReactorStage(
    activeAura: Color,
    spinFast: Float,
    spinSlow: Float,
    pulseVal: Float,
    vocalWave: Float,
    smoothLevel: Float,
    speechScale: Float,
    isSpeaking: Boolean,
    isThinking: Boolean,
    isListening: Boolean,
    dragYaw: Float,
    dragPitch: Float,
    density: Float
) {
    Box(
        modifier = Modifier
            .size(310.dp)
            .graphicsLayer {
                cameraDistance = 14f * density
                rotationY = dragYaw + sin(spinSlow * 2 * PI).toFloat() * 4f
                rotationX = dragPitch + cos(spinSlow * 2 * PI).toFloat() * 3f
                scaleX = speechScale
                scaleY = speechScale
            },
        contentAlignment = Alignment.Center
    ) {
        // 42-Bar 360 Vocal Spectrum Equalizer + Quantum Sparks
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val baseR = size.minDimension * 0.34f

            // Outer ambient halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(activeAura.copy(alpha = 0.24f), Color.Transparent),
                    center = c,
                    radius = size.minDimension * 0.50f
                ),
                center = c,
                radius = size.minDimension * 0.50f
            )

            // 42-bar circular acoustic equalizer
            val bars = 42
            for (i in 0 until bars) {
                val theta = (i.toFloat() / bars) * 2.0 * PI + spinSlow * 2.0 * PI * 0.25
                val ampWave = abs(sin(vocalWave * 4.0 * PI + i * 0.55)).toFloat()
                val activeBoost = when {
                    isSpeaking -> 0.55f + ampWave * 0.65f
                    isListening -> smoothLevel * (0.4f + ampWave * 0.8f)
                    isThinking -> 0.35f + ampWave * 0.35f
                    else -> 0.14f + pulseVal * 0.12f
                }
                val barLen = (8.dp.toPx()) + activeBoost * 24.dp.toPx()
                val startPt = Offset(
                    c.x + (baseR * cos(theta)).toFloat(),
                    c.y + (baseR * sin(theta)).toFloat()
                )
                val endPt = Offset(
                    c.x + ((baseR + barLen) * cos(theta)).toFloat(),
                    c.y + ((baseR + barLen) * sin(theta)).toFloat()
                )
                drawLine(
                    color = activeAura.copy(alpha = 0.35f + activeBoost * 0.55f),
                    start = startPt,
                    end = endPt,
                    strokeWidth = 2.6f,
                    cap = StrokeCap.Round
                )
                if (activeBoost > 0.45f) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.75f),
                        radius = 2f,
                        center = endPt
                    )
                }
            }
        }

        // Cross-faded Rotating Holographic Core Texture Sphere
        val coreSize = 186.dp
        Box(
            modifier = Modifier
                .size(coreSize)
                .clip(CircleShape)
                .border(1.4.dp, activeAura.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val goldMix = if (isThinking || isSpeaking) 0.55f else 0f
            Image(
                painter = painterResource(id = R.drawable.jarvis_core_cyan),
                contentDescription = "Arc Reactor Cyan Core",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = spinFast * 360f }
                    .alpha(1f - goldMix)
            )
            Image(
                painter = painterResource(id = R.drawable.jarvis_core_gold),
                contentDescription = "Arc Reactor Gold Core",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = -spinFast * 240f }
                    .alpha(goldMix)
            )
            // Subtle color wash overlay for 5-state aura harmony
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Transparent, activeAura.copy(alpha = 0.25f))
                        )
                    )
            )
        }

        // Triple-Axis Armillary Gyroscope Orbital Rings + Pulsar Core
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringAxes = listOf(28f to spinFast * 360f, -44f to -spinFast * 260f, 72f to spinSlow * 360f)
            val radii = listOf(size.minDimension * 0.42f, size.minDimension * 0.46f, size.minDimension * 0.37f)

            ringAxes.forEachIndexed { idx, (tiltDeg, sweepDeg) ->
                val rx = radii[idx]
                val ry = rx * (0.42f + idx * 0.14f)
                rotate(degrees = tiltDeg + sweepDeg * 0.15f, pivot = c) {
                    drawOval(
                        color = activeAura.copy(alpha = 0.52f - idx * 0.10f),
                        topLeft = Offset(c.x - rx, c.y - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(
                            width = 2.2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f), sweepDeg)
                        )
                    )
                    // Orbital satellite node
                    val rad = (sweepDeg * PI / 180.0)
                    val node = Offset(c.x + (rx * cos(rad)).toFloat(), c.y + (ry * sin(rad)).toFloat())
                    drawCircle(color = Color.White, radius = 3.5f, center = node)
                    drawCircle(color = activeAura.copy(alpha = 0.55f), radius = 7.5f, center = node)
                }
            }

            // Pulsar acoustic waves from core center
            for (k in 0..2) {
                val p = (pulseVal + k * 0.33f) % 1f
                drawCircle(
                    color = activeAura.copy(alpha = (1f - p) * 0.42f),
                    radius = (18.dp.toPx() + p * 74.dp.toPx()),
                    center = c,
                    style = Stroke(width = 2f)
                )
            }
        }
    }
}

@Composable
private fun HologramSuitBustStage(
    activeAura: Color,
    spinFast: Float,
    spinSlow: Float,
    pulseVal: Float,
    vocalWave: Float,
    auraDrift: Float,
    smoothLevel: Float,
    speechScale: Float,
    speakSwayY: Float,
    speakTiltX: Float,
    isSpeaking: Boolean,
    isThinking: Boolean,
    isListening: Boolean,
    dragYaw: Float,
    dragPitch: Float,
    density: Float
) {
    Box(
        modifier = Modifier
            .size(width = 290.dp, height = 340.dp)
            .graphicsLayer {
                cameraDistance = 14f * density
                rotationY = dragYaw + speakSwayY * 180f
                rotationX = dragPitch + speakTiltX * 140f
                scaleX = speechScale
                scaleY = speechScale
            },
        contentAlignment = Alignment.Center
    ) {
        // Volumetric Emitter Light Cone & Rising Pedestal at bottom
        Canvas(modifier = Modifier.fillMaxSize()) {
            val baseY = size.height * 0.90f
            val cx = size.width / 2f

            // Volumetric holographic beam cone
            val conePath = Path().apply {
                moveTo(cx - 72.dp.toPx(), baseY)
                lineTo(cx - 120.dp.toPx(), size.height * 0.14f)
                lineTo(cx + 120.dp.toPx(), size.height * 0.14f)
                lineTo(cx + 72.dp.toPx(), baseY)
                close()
            }
            drawPath(
                path = conePath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, activeAura.copy(alpha = 0.14f), activeAura.copy(alpha = 0.28f))
                )
            )

            // Emitter pedestal ellipses
            drawOval(
                color = activeAura.copy(alpha = 0.65f),
                topLeft = Offset(cx - 96.dp.toPx(), baseY - 14.dp.toPx()),
                size = Size(192.dp.toPx(), 28.dp.toPx()),
                style = Stroke(width = 2.4f)
            )
            drawOval(
                color = Color.White.copy(alpha = 0.45f),
                topLeft = Offset(cx - 56.dp.toPx(), baseY - 7.dp.toPx()),
                size = Size(112.dp.toPx(), 14.dp.toPx()),
                style = Stroke(width = 1.6f)
            )
        }

        // Cross-faded 3D Suit Hologram with Soft Radial Edge Feathering (no harsh box edges)
        val goldAlpha = if (isThinking) 0.85f else 0f
        val purpleAlpha = if (isSpeaking) 0.78f else 0f
        val cyanAlpha = (1f - maxOf(goldAlpha, purpleAlpha) * 0.7f).coerceIn(0.15f, 1f)

        Box(
            modifier = Modifier
                .size(255.dp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.Black, Color.Black, Color.Transparent),
                            center = Offset(size.width / 2f, size.height * 0.48f),
                            radius = size.minDimension * 0.52f
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.jarvis_male_hologram),
                contentDescription = "Jarvis Cyan Hologram Bust",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(cyanAlpha)
            )
            Image(
                painter = painterResource(id = R.drawable.jarvis_male_hologram_purple),
                contentDescription = "Jarvis Violet Speaking Hologram",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(purpleAlpha)
            )
            Image(
                painter = painterResource(id = R.drawable.jarvis_male_hologram_gold),
                contentDescription = "Jarvis Gold Thinking Hologram",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(goldAlpha)
            )
        }

        // Torso Orbital Rings, Chest Sonic Ripple & Vertical Digital Scanline Sweep
        Canvas(modifier = Modifier.fillMaxSize()) {
            val chestCenter = Offset(size.width / 2f, size.height * 0.56f)

            // Dual orbital torso rings
            drawOval(
                color = activeAura.copy(alpha = 0.42f),
                topLeft = Offset(chestCenter.x - 108.dp.toPx(), chestCenter.y - 26.dp.toPx()),
                size = Size(216.dp.toPx(), 52.dp.toPx()),
                style = Stroke(
                    width = 1.8f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f), spinFast * 220f)
                )
            )

            // Chest Arc-Reactor sonic pulse
            val sonicR = 14.dp.toPx() + ((if (isSpeaking || isListening) vocalWave else pulseVal) * 42.dp.toPx() * (0.4f + smoothLevel))
            drawCircle(
                color = activeAura.copy(alpha = 0.65f),
                radius = sonicR,
                center = chestCenter,
                style = Stroke(width = 2.2f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.85f),
                radius = 5.dp.toPx(),
                center = chestCenter
            )

            // Vertical holographic scanline sweep
            val scanY = size.height * (0.14f + auraDrift * 0.72f)
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, activeAura.copy(alpha = 0.85f), Color.Transparent)
                ),
                start = Offset(size.width * 0.14f, scanY),
                end = Offset(size.width * 0.86f, scanY),
                strokeWidth = 2.4f
            )
        }
    }
}

@Composable
private fun HudActionPod(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    activeColor: Color,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isActive) activeColor.copy(alpha = 0.24f) else Color(0xFF0A1120).copy(alpha = 0.86f)
    val rim = if (isActive) activeColor.copy(alpha = 0.80f) else Color.White.copy(alpha = 0.14f)

    Surface(
        shape = RoundedCornerShape(15.dp),
        color = bg,
        border = BorderStroke(1.dp, rim),
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else LuxuryPalette.SilverText,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = label,
                color = if (isActive) activeColor else LuxuryPalette.SilverText,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.6.sp
            )
        }
    }
}
