package com.zeeai.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeeai.companion.ui.theme.LuxuryPalette

enum class ZeeAppMode(val label: String) {
    AGENT("Agent"),
    CHAT("Chat"),
    HUD("ZEE HUD")
}

/**
 * Deeper obsidian backdrop with luminous liquid refraction orbs inspired by Jarvis LiquidGlassScaffold
 * upgraded with richer depth and jewel-toned sapphire/cyan/violet ambient aura.
 */
@Composable
fun LuxuryLiquidGlassBackdrop(
    isHudMode: Boolean,
    accentColor: Color = LuxuryPalette.ElectricCyan,
    secondaryColor: Color = LuxuryPalette.RoyalIndigo,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val bgBase by animateColorAsState(
        targetValue = if (isHudMode) Color(0xFF010308) else LuxuryPalette.ObsidianVoid,
        animationSpec = tween(450),
        label = "bgBase"
    )

    val orbTransition = rememberInfiniteTransition(label = "ambientOrbs")
    val drift by orbTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(9000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orbDrift"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgBase)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Top-right luminous liquid sphere
            val topRightCenter = Offset(
                x = size.width * (0.94f - drift * 0.06f),
                y = size.height * (0.06f + drift * 0.03f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        (if (isHudMode) accentColor else secondaryColor).copy(alpha = 0.16f),
                        Color.Transparent
                    ),
                    center = topRightCenter,
                    radius = size.width * 0.68f
                ),
                center = topRightCenter,
                radius = size.width * 0.68f
            )

            // Bottom-left deep crystal refraction sphere
            val bottomLeftCenter = Offset(
                x = size.width * (0.08f + drift * 0.05f),
                y = size.height * (0.86f - drift * 0.04f)
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        (if (isHudMode) LuxuryPalette.QuantumViolet else LuxuryPalette.SkyAzure)
                            .copy(alpha = 0.11f),
                        Color.Transparent
                    ),
                    center = bottomLeftCenter,
                    radius = size.width * 0.72f
                ),
                center = bottomLeftCenter,
                radius = size.width * 0.72f
            )
        }
        content()
    }
}

/**
 * Smoked Crystal Liquid Glass Card with specular bevel rim and deeper obsidian fill.
 */
@Composable
fun LuxuryGlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    glowColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val rimBrush = if (borderColor != null) {
        Brush.linearGradient(
            colors = listOf(
                borderColor.copy(alpha = 0.70f),
                borderColor.copy(alpha = 0.28f),
                borderColor.copy(alpha = 0.12f)
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.20f),
                LuxuryPalette.ElectricCyan.copy(alpha = 0.12f),
                Color.White.copy(alpha = 0.05f)
            )
        )
    }

    val fillBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFF131B2E).copy(alpha = 0.80f),
            Color(0xFF070C17).copy(alpha = 0.92f)
        )
    )

    val baseMod = modifier
        .then(
            if (glowColor != null) {
                Modifier.shadow(
                    elevation = 14.dp,
                    shape = shape,
                    ambientColor = glowColor.copy(alpha = 0.35f),
                    spotColor = glowColor.copy(alpha = 0.35f)
                )
            } else {
                Modifier.shadow(
                    elevation = 10.dp,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.6f),
                    spotColor = Color.Black.copy(alpha = 0.6f)
                )
            }
        )
        .clip(shape)
        .background(fillBrush, shape)
        .border(borderWidth, rimBrush, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(contentPadding)

    Column(modifier = baseMod, content = content)
}

/**
 * Compact Glowing Glass Pill Tag / Chip
 */
@Composable
fun LuxuryGlassPill(
    label: String,
    color: Color = LuxuryPalette.ElectricCyan,
    icon: ImageVector? = null,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(30.dp)
    val bgColor = if (isSelected) color.copy(alpha = 0.22f) else color.copy(alpha = 0.08f)
    val rimColor = if (isSelected) color.copy(alpha = 0.68f) else color.copy(alpha = 0.24f)

    Surface(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = bgColor,
        border = BorderStroke(if (isSelected) 1.2.dp else 0.8.dp, rimColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp)
                )
            }
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) color else LuxuryPalette.PlatinumText,
                letterSpacing = 0.2.sp
            )
        }
    }
}

/**
 * Pulsing Service Status Indicator Dot (exact match to Jarvis ServiceStatusDot)
 */
@Composable
fun PulsingServiceDot(enabled: Boolean, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "dotPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    val dotColor = if (enabled) LuxuryPalette.EmeraldNeon else LuxuryPalette.CrimsonAlert

    Box(
        modifier = modifier.size(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(dotColor.copy(alpha = 0.26f * pulse), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape)
        )
    }
}

/**
 * Next-Gen Liquid Glass Segmented Mode Switcher: Agent | Chat | ZEE HUD
 */
@Composable
fun LiquidGlassModeSwitcher(
    selectedMode: ZeeAppMode,
    onModeChanged: (ZeeAppMode) -> Unit,
    isBusy: Boolean = false,
    accentColor: Color = LuxuryPalette.ElectricCyan,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val isHud = selectedMode == ZeeAppMode.HUD
    val shape = RoundedCornerShape(30.dp)

    val containerBg = if (isHud) {
        Color(0xFF040D1A).copy(alpha = 0.85f)
    } else {
        Color(0xFF0D1526).copy(alpha = 0.78f)
    }

    val containerBorder = if (isHud) {
        accentColor.copy(alpha = 0.40f)
    } else {
        Color.White.copy(alpha = 0.14f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .shadow(
                    elevation = 16.dp,
                    shape = shape,
                    ambientColor = if (isHud) accentColor.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.55f),
                    spotColor = if (isHud) accentColor.copy(alpha = 0.28f) else Color.Black.copy(alpha = 0.55f)
                )
                .clip(shape)
                .background(containerBg, shape)
                .border(1.dp, containerBorder, shape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZeeAppMode.entries.forEach { mode ->
                val isSelected = selectedMode == mode
                val itemColor = when (mode) {
                    ZeeAppMode.AGENT -> LuxuryPalette.IndigoLight
                    ZeeAppMode.CHAT -> LuxuryPalette.SkyAzure
                    ZeeAppMode.HUD -> accentColor
                }
                val glowColor = when (mode) {
                    ZeeAppMode.AGENT -> LuxuryPalette.RoyalIndigo
                    ZeeAppMode.CHAT -> Color(0xFF0EA5E9)
                    ZeeAppMode.HUD -> accentColor
                }

                val bgAlpha by animateColorAsState(
                    targetValue = if (isSelected) glowColor.copy(alpha = 0.30f) else Color.Transparent,
                    animationSpec = tween(220),
                    label = "pillBg"
                )
                val borderAlpha by animateColorAsState(
                    targetValue = if (isSelected) itemColor.copy(alpha = 0.70f) else Color.Transparent,
                    animationSpec = tween(220),
                    label = "pillBorder"
                )

                val itemShape = RoundedCornerShape(24.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(itemShape)
                        .background(
                            brush = if (isSelected) {
                                Brush.linearGradient(
                                    colors = listOf(
                                        glowColor.copy(alpha = 0.34f),
                                        itemColor.copy(alpha = 0.14f)
                                    )
                                )
                            } else {
                                SolidColor(bgAlpha)
                            },
                            shape = itemShape
                        )
                        .border(1.2.dp, borderAlpha, itemShape)
                        .clickable(
                            enabled = !isBusy,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (selectedMode != mode) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onModeChanged(mode)
                            }
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when (mode) {
                                ZeeAppMode.AGENT -> "🤖"
                                ZeeAppMode.CHAT -> "💬"
                                ZeeAppMode.HUD -> "⚡"
                            },
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = mode.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            fontFamily = if (mode == ZeeAppMode.HUD) FontFamily.Monospace else FontFamily.Default,
                            letterSpacing = if (mode == ZeeAppMode.HUD) 1.2.sp else 0.2.sp,
                            color = if (isSelected) Color.White else LuxuryPalette.MutedSlate
                        )
                    }
                }
            }
        }
    }
}

/**
 * Luxury Smoked-Crystal Transcript Bubble for User, Assistant, System & Error messages
 */
@Composable
fun LuxuryChatBubble(
    text: String,
    isUser: Boolean,
    characterTag: String,
    sourceTag: String,
    timestampLabel: String = "",
    stepBadge: String? = null,
    accentColor: Color = LuxuryPalette.ElectricCyan,
    onSpeakClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isError = text.startsWith("Error:") || text.startsWith("⚠️") || text.startsWith("Online AI error")
    val isSystem = sourceTag == "system-guide"

    if (isSystem) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF0F172A).copy(alpha = 0.85f),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.25f))
            ) {
                Text(
                    text = text,
                    color = LuxuryPalette.SilverText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        return
    }

    val shape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomStart = if (isUser) 20.dp else 6.dp,
        bottomEnd = if (isUser) 6.dp else 20.dp
    )

    val bgBrush = when {
        isUser -> Brush.linearGradient(
            colors = listOf(
                LuxuryPalette.RoyalIndigo.copy(alpha = 0.65f),
                LuxuryPalette.DeepIndigo.copy(alpha = 0.45f)
            )
        )
        isError -> Brush.linearGradient(
            colors = listOf(
                LuxuryPalette.DangerRed.copy(alpha = 0.25f),
                LuxuryPalette.DangerRed.copy(alpha = 0.10f)
            )
        )
        else -> Brush.linearGradient(
            colors = listOf(
                Color(0xFF131B2E).copy(alpha = 0.86f),
                Color(0xFF090E1B).copy(alpha = 0.94f)
            )
        )
    }

    val borderBrush = when {
        isUser -> Brush.linearGradient(
            colors = listOf(LuxuryPalette.IndigoLight.copy(alpha = 0.65f), LuxuryPalette.RoyalIndigo.copy(alpha = 0.25f))
        )
        isError -> Brush.linearGradient(
            colors = listOf(LuxuryPalette.DangerRed.copy(alpha = 0.6f), LuxuryPalette.DangerRed.copy(alpha = 0.2f))
        )
        else -> Brush.linearGradient(
            colors = listOf(Color.White.copy(alpha = 0.18f), accentColor.copy(alpha = 0.14f))
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 330.dp)
                .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = 0.45f))
                .clip(shape)
                .background(bgBrush, shape)
                .border(1.dp, borderBrush, shape)
                .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
            if (!stepBadge.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = 0.16f),
                    border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.45f)),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = stepBadge,
                        color = accentColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = text,
                color = if (isError) Color(0xFFFCA5A5) else Color.White,
                fontSize = 13.8.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(5.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isUser) "Boss" else "$characterTag • $sourceTag",
                    color = Color.White.copy(alpha = 0.50f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isUser && onSpeakClick != null) {
                        Text(
                            text = "🔊",
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { onSpeakClick() }
                        )
                    }
                    if (timestampLabel.isNotBlank()) {
                        Text(
                            text = timestampLabel,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 9.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LuxuryGlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    obscureText: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = LuxuryPalette.MutedSlate,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 5.dp, start = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(15.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(15.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = LuxuryPalette.MutedSlate.copy(alpha = 0.5f),
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    textStyle = TextStyle(color = Color.White, fontSize = 13.5.sp),
                    cursorBrush = SolidColor(LuxuryPalette.ElectricCyan),
                    visualTransformation = if (obscureText) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
fun LuxuryGlassSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    steps: Int = 0,
    accentColor: Color = LuxuryPalette.ElectricCyan,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = accentColor.copy(alpha = 0.15f),
                border = BorderStroke(0.8.dp, accentColor.copy(alpha = 0.45f))
            ) {
                Text(
                    text = displayValue,
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
            )
        )
    }
}

@Composable
fun LuxuryGlassSwitchTile(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color = LuxuryPalette.ElectricCyan,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(
                1.dp,
                if (checked) accentColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(14.dp)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) accentColor else LuxuryPalette.MutedSlate,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = LuxuryPalette.MutedSlate,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accentColor.copy(alpha = 0.75f),
                uncheckedThumbColor = LuxuryPalette.MutedSlate,
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f)
            )
        )
    }
}
