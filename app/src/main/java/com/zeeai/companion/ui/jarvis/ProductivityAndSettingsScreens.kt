package com.zeeai.companion.ui.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zeeai.companion.automation.ZeeAccessibilityService
import com.zeeai.companion.jarvis.*
import com.zeeai.companion.ui.components.*
import com.zeeai.companion.ui.theme.LuxuryAccentPreset
import com.zeeai.companion.ui.theme.LuxuryPalette
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =============================================================================
// 1. PRODUCTIVITY SUITE SHEETS (TODOS / CALENDAR / NOTES / SESSION DETAIL)
// =============================================================================

@Composable
fun TodosSheetDialog(
    store: ProductivityAndMemoryStore,
    onDismiss: () -> Unit
) {
    val todos by store.todos.collectAsState()
    var newTask by remember { mutableStateOf("") }
    var prio by remember { mutableStateOf(TodoPriority.MEDIUM) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.94f),
            glowColor = LuxuryPalette.ElectricCyan,
            borderColor = LuxuryPalette.ElectricCyan.copy(alpha = 0.4f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("TASKS & AGENDA MATRIX", color = LuxuryPalette.ElectricCyan, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    LuxuryGlassTextField(
                        value = newTask,
                        onValueChange = { newTask = it },
                        label = "Add New Mission Item",
                        placeholder = "e.g. Deploy build, review finance..."
                    )
                }
                IconButton(
                    onClick = {
                        if (newTask.isNotBlank()) {
                            store.addTodo(newTask, prio)
                            newTask = ""
                        }
                    },
                    modifier = Modifier
                        .padding(top = 18.dp)
                        .background(LuxuryPalette.ElectricCyan.copy(alpha = 0.22f), CircleShape)
                ) {
                    Icon(Icons.Rounded.Add, null, tint = LuxuryPalette.ElectricCyan)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TodoPriority.entries.forEach { p ->
                    val col = when (p) {
                        TodoPriority.HIGH -> LuxuryPalette.CrimsonAlert
                        TodoPriority.MEDIUM -> LuxuryPalette.SolarGold
                        TodoPriority.LOW -> LuxuryPalette.EmeraldNeon
                    }
                    LuxuryGlassPill(p.label, col, isSelected = prio == p) { prio = p }
                }
            }

            Spacer(Modifier.height(10.dp))
            if (todos.isEmpty()) {
                Text("No active tasks yet, Boss. Add one above or via Brain Dump.", color = LuxuryPalette.MutedSlate, fontSize = 12.sp)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 290.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(todos, key = { it.id }) { t ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.04f),
                            border = BorderStroke(1.dp, if (t.isDone) LuxuryPalette.EmeraldNeon.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { store.toggleTodo(t.id) }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (t.isDone) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (t.isDone) LuxuryPalette.EmeraldNeon else LuxuryPalette.MutedSlate,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t.title, color = if (t.isDone) LuxuryPalette.MutedSlate else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(t.priority.label, color = LuxuryPalette.ChampagneGold, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                                }
                                IconButton(onClick = { store.deleteTodo(t.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarSheetDialog(
    store: ProductivityAndMemoryStore,
    onDismiss: () -> Unit
) {
    val events by store.calendar.collectAsState()
    var title by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.94f),
            glowColor = LuxuryPalette.IndigoLight,
            borderColor = LuxuryPalette.IndigoLight.copy(alpha = 0.4f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("CALENDAR & REMINDERS", color = LuxuryPalette.IndigoLight, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            LuxuryGlassTextField(value = title, onValueChange = { title = it }, label = "Reminder Title", placeholder = "e.g. Executive call at 5 PM")
            Spacer(Modifier.height(6.dp))
            LuxuryGlassTextField(value = location, onValueChange = { location = it }, label = "Location (Optional)", placeholder = "e.g. Dhaka HQ / Zoom")
            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (title.isNotBlank()) {
                            store.addCalendarReminder(title, System.currentTimeMillis() + 3600_000L, location)
                            title = ""; location = ""
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.RoyalIndigo),
                    modifier = Modifier.weight(1f)
                ) { Text("Save In-App") }
                OutlinedButton(
                    onClick = { store.openNativeCalendarInsert(title.ifBlank { "ZEE AI Reminder" }) },
                    modifier = Modifier.weight(1f)
                ) { Text("Phone Calendar") }
            }

            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(events, key = { it.id }) { ev ->
                    val ts = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ev.dateTimeMs))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, LuxuryPalette.IndigoLight.copy(alpha = 0.25f))
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Event, null, tint = LuxuryPalette.IndigoLight, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(ev.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("$ts ${if (ev.location.isNotBlank()) "• ${ev.location}" else ""}", color = LuxuryPalette.MutedSlate, fontSize = 10.5.sp)
                            }
                            IconButton(onClick = { store.deleteCalendarItem(ev.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotesSheetDialog(
    store: ProductivityAndMemoryStore,
    onDismiss: () -> Unit
) {
    val notes by store.notes.collectAsState()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LuxuryGlassCard(
            modifier = Modifier.fillMaxWidth(0.94f),
            glowColor = LuxuryPalette.QuantumViolet,
            borderColor = LuxuryPalette.QuantumViolet.copy(alpha = 0.4f)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("KNOWLEDGE BASE VAULT", color = LuxuryPalette.QuantumViolet, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }

            LuxuryGlassTextField(value = title, onValueChange = { title = it }, label = "Note Header", placeholder = "e.g. Server IP / Strategy")
            Spacer(Modifier.height(6.dp))
            LuxuryGlassTextField(value = body, onValueChange = { body = it }, label = "Details", placeholder = "Write knowledge note...", singleLine = false, maxLines = 3)
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    if (body.isNotBlank()) {
                        store.addNote(title, body)
                        title = ""; body = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.QuantumViolet),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Archive Note for Boss", color = Color.White, fontWeight = FontWeight.Bold) }

            Spacer(Modifier.height(10.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(notes, key = { it.id }) { n ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.dp, LuxuryPalette.QuantumViolet.copy(alpha = 0.25f))
                    ) {
                        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(n.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(n.content, color = LuxuryPalette.SilverText, fontSize = 11.5.sp)
                            }
                            IconButton(onClick = { store.deleteNote(n.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionDetailDialog(
    session: TaskSessionEntry,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        LuxuryGlassCard(glowColor = LuxuryPalette.ElectricCyan) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("MISSION TELEMETRY", color = LuxuryPalette.ElectricCyan, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
            }
            Text(session.goal, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "${session.status.name} • ${SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(session.startedAtMs))}",
                color = LuxuryPalette.EmeraldNeon,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
            if (session.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(session.summary, color = LuxuryPalette.SilverText, fontSize = 12.sp)
            }
            if (session.steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    session.steps.forEachIndexed { idx, step ->
                        Text("${idx + 1}. $step", color = LuxuryPalette.MutedSlate, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onDelete(); onDismiss() }, colors = ButtonDefaults.outlinedButtonColors(contentColor = LuxuryPalette.CrimsonAlert), modifier = Modifier.fillMaxWidth()) {
                Text("Delete Session Log")
            }
        }
    }
}

// =============================================================================
// 2. SYSTEM PERMISSIONS HUB SCREEN
// =============================================================================

@Composable
fun PermissionsHubScreen(
    jarvisCore: JarvisCoreController,
    onRequestCoreRuntimePerms: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun checkAll(): Map<String, Boolean> {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        val notifs = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val acc = ZeeAccessibilityService.isServiceEnabled(context)
        val overlay = Settings.canDrawOverlays(context)
        val notifListener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName)
        val usageOk = jarvisCore.sensors.hasUsageStatsPermission()
        return mapOf(
            "mic" to mic,
            "acc" to acc,
            "overlay" to overlay,
            "notifListener" to notifListener,
            "notifs" to notifs,
            "contacts" to contacts,
            "battery" to batteryOk,
            "usage" to usageOk
        )
    }

    var perms by remember { mutableStateOf(checkAll()) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) perms = checkAll()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val activeCount = perms.values.count { it }
    val totalCount = perms.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Column(modifier = Modifier.weight(1f)) {
                Text("Permissions Hub", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Text("Enable ZEE AI Autonomous Hardware Bridges", color = LuxuryPalette.MutedSlate, fontSize = 11.5.sp)
            }
            IconButton(onClick = { perms = checkAll() }) {
                Icon(Icons.Rounded.Refresh, null, tint = LuxuryPalette.ElectricCyan)
            }
        }

        // Progress Overview Glass Card
        LuxuryGlassCard(glowColor = if (activeCount == totalCount) LuxuryPalette.EmeraldNeon else LuxuryPalette.ElectricCyan) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$activeCount of $totalCount Permissions Active", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                LuxuryGlassPill(
                    label = if (activeCount == totalCount) "ALL READY" else "SETUP NEEDED",
                    color = if (activeCount == totalCount) LuxuryPalette.EmeraldNeon else LuxuryPalette.SolarGold
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { activeCount.toFloat() / totalCount.toFloat() },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                color = if (activeCount == totalCount) LuxuryPalette.EmeraldNeon else LuxuryPalette.ElectricCyan,
                trackColor = Color.White.copy(alpha = 0.12f)
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    onRequestCoreRuntimePerms()
                    perms = checkAll()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.RoyalIndigo),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Grant Core Runtime Permissions (Mic, Contacts, Alerts)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        PermissionRowCard(
            title = "Microphone (Voice Control)",
            desc = "Speak live to ZEE / ZHEE via Tap-to-Talk or Always-On HUD.",
            granted = perms["mic"] == true,
            actionLabel = "Grant"
        ) { onRequestCoreRuntimePerms() }

        PermissionRowCard(
            title = "Accessibility Service (Full Phone Control)",
            desc = "Required to tap, scroll, automate UI flows, record Macros & read screen.",
            granted = perms["acc"] == true,
            actionLabel = "Enable"
        ) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        PermissionRowCard(
            title = "Floating Arc-Reactor Overlay",
            desc = "Draws ZEE floating glass orb over other apps for instant screen summary.",
            granted = perms["overlay"] == true,
            actionLabel = "Enable"
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        PermissionRowCard(
            title = "Inbox Notification Listener",
            desc = "Reads incoming WhatsApp, SMS & urgent OTP alerts cleanly (no risky SMS permission).",
            granted = perms["notifListener"] == true,
            actionLabel = "Enable"
        ) {
            runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        PermissionRowCard(
            title = "Notifications & Proactive Alerts",
            desc = "Required for Wake Word, Overlay foreground badge, and Boss reminders.",
            granted = perms["notifs"] == true,
            actionLabel = "Grant"
        ) { onRequestCoreRuntimePerms() }

        PermissionRowCard(
            title = "Contacts Access",
            desc = "Voice dial & direct WhatsApp messaging by contact name.",
            granted = perms["contacts"] == true,
            actionLabel = "Grant"
        ) { onRequestCoreRuntimePerms() }

        PermissionRowCard(
            title = "Unrestricted Battery (Background)",
            desc = "Prevents MIUI / HyperOS / OneUI from sleeping ZEE voice & wake engines.",
            granted = perms["battery"] == true,
            actionLabel = "Allow"
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }

        PermissionRowCard(
            title = "Usage Access (Screen Time Diagnostics)",
            desc = "Lets ZEE tell Boss your top-used apps and daily screen time.",
            granted = perms["usage"] == true,
            actionLabel = "Open"
        ) {
            runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Android App Info (Autostart / Restrict)", color = LuxuryPalette.SilverText)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRowCard(
    title: String,
    desc: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    LuxuryGlassCard(
        borderColor = if (granted) LuxuryPalette.EmeraldNeon.copy(alpha = 0.38f) else LuxuryPalette.WarningAmber.copy(alpha = 0.35f)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PulsingServiceDot(enabled = granted)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = LuxuryPalette.MutedSlate, fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                LuxuryGlassPill("ACTIVE", LuxuryPalette.EmeraldNeon)
            } else {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.ElectricCyan),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(actionLabel, color = LuxuryPalette.ObsidianVoid, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// =============================================================================
// 3. JARVIS AUTONOMOUS CORE & AI ENGINE SETTINGS SCREEN
// =============================================================================

@Composable
fun AutonomousCoreSettingsScreen(
    jarvisCore: JarvisCoreController,
    customApiKey: String,
    onSaveCustomApiKey: (String) -> Unit,
    memorySnippets: List<String>,
    onTestVoice: () -> Unit,
    onSpeakFeedback: (String) -> Unit,
    onOpenPermissionsHub: () -> Unit,
    onOpenLegacyKeyVault: () -> Unit,
    onOpenBrainDump: () -> Unit,
    onOpenRadar: () -> Unit,
    onOpenNetworkAudit: () -> Unit,
    onOpenOpticalSolver: () -> Unit,
    onOpenPolyglot: () -> Unit,
    onOpenChargingHud: () -> Unit,
    onTriggerCodeBlack: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap by jarvisCore.prefs.state.collectAsState()
    val hermesState by jarvisCore.hermes.state.collectAsState()
    val sentry by jarvisCore.sensors.sentryStatus.collectAsState()
    val macros by jarvisCore.store.macros.collectAsState()

    var keyDraft by remember(customApiKey) { mutableStateOf(customApiKey) }
    var telemetryResult by remember { mutableStateOf<String?>(null) }
    var editingDocKey by remember { mutableStateOf<String?>(null) }
    var smartHomeOutput by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
            Column(modifier = Modifier.weight(1f)) {
                Text("ZEE / JARVIS Autonomous Core", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("Themes, 3D HUD, Hermes Brain, AI Providers & Stark Hardware", color = LuxuryPalette.MutedSlate, fontSize = 11.sp)
            }
        }

        // 1. HUD VISUAL STYLE & LUXURY ACCENT THEME
        SectionTitle("HOLOGRAM HUD & LUXURY THEME", LuxuryPalette.ElectricCyan)
        LuxuryGlassCard(glowColor = snap.luxuryAccent.primaryHex) {
            Text("Select HUD Visual Stage", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudVisualStyle.entries.forEach { style ->
                    val selected = snap.hudStyle == style
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { jarvisCore.prefs.update { it.copy(hudStyle = style) } },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) snap.luxuryAccent.primaryHex.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.04f),
                        border = BorderStroke(1.2.dp, if (selected) snap.luxuryAccent.primaryHex else Color.White.copy(alpha = 0.12f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                if (style == HudVisualStyle.ARC_REACTOR) Icons.Rounded.MotionPhotosOn else Icons.Rounded.AccessibilityNew,
                                null,
                                tint = if (selected) snap.luxuryAccent.primaryHex else LuxuryPalette.MutedSlate
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (style == HudVisualStyle.ARC_REACTOR) "Arc Reactor Sphere" else "3D Hologram Suit",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Obsidian Luxury Aura Palette", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LuxuryAccentPreset.entries.forEach { preset ->
                    LuxuryGlassPill(
                        label = preset.label,
                        color = preset.primaryHex,
                        isSelected = snap.luxuryAccent == preset,
                        onClick = { jarvisCore.prefs.update { it.copy(luxuryAccent = preset) } }
                    )
                }
            }
        }

        // 2. AI PROVIDER ENGINE & PARAMETERS (GEMINI / DEEPSEEK / OPENAI / GROQ / OLLAMA)
        SectionTitle("AI PROVIDER PRESETS & ENGINE PARAMETERS", LuxuryPalette.IndigoLight)
        LuxuryGlassCard(glowColor = LuxuryPalette.IndigoLight) {
            Text("Provider Preset", color = LuxuryPalette.SilverText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JarvisPreferences.PROVIDER_PRESETS.forEach { p ->
                    LuxuryGlassPill(
                        label = p.label,
                        color = LuxuryPalette.IndigoLight,
                        isSelected = snap.aiProviderPreset == p.id,
                        onClick = { jarvisCore.prefs.selectProviderPreset(p.id) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LuxuryGlassTextField(
                value = snap.aiBaseUrl,
                onValueChange = { url -> jarvisCore.prefs.update { it.copy(aiBaseUrl = url) } },
                label = "Endpoint Base URL",
                placeholder = "https://api.openai.com/v1"
            )
            Spacer(Modifier.height(8.dp))
            LuxuryGlassTextField(
                value = snap.aiCustomModel,
                onValueChange = { m -> jarvisCore.prefs.update { it.copy(aiCustomModel = m) } },
                label = "Active Model ID",
                placeholder = "gemini-2.0-flash / deepseek-chat / gpt-4o-mini"
            )
            Spacer(Modifier.height(8.dp))
            LuxuryGlassTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it; onSaveCustomApiKey(it) },
                label = "Provider API Key (or configure Gemini/Groq in ZEE Key Vault)",
                placeholder = "Paste API key...",
                obscureText = true
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenLegacyKeyVault) {
                Text("Open ZEE AI Key Vault (Gemini, Groq, OpenRouter, ElevenLabs, HF) ->", color = LuxuryPalette.ElectricCyan, fontSize = 12.sp)
            }
            LuxuryGlassSliderRow(
                label = "Creativity / Temperature",
                value = snap.temperature,
                valueRange = 0f..1.5f,
                displayValue = "%.2f".format(snap.temperature),
                accentColor = LuxuryPalette.IndigoLight,
                onValueChange = { v -> jarvisCore.prefs.update { it.copy(temperature = v) } }
            )
            LuxuryGlassSliderRow(
                label = "Max Response Tokens",
                value = snap.maxTokens.toFloat(),
                valueRange = 512f..8192f,
                displayValue = "${snap.maxTokens}",
                accentColor = LuxuryPalette.ElectricCyan,
                onValueChange = { v -> jarvisCore.prefs.update { it.copy(maxTokens = v.toInt()) } }
            )
            LuxuryGlassSliderRow(
                label = "Autonomous Agent Max Steps",
                value = snap.maxSteps.toFloat(),
                valueRange = 5f..35f,
                displayValue = "${snap.maxSteps} steps",
                accentColor = LuxuryPalette.SolarGold,
                onValueChange = { v -> jarvisCore.prefs.update { it.copy(maxSteps = v.toInt()) } }
            )
        }

        // 3. VOICE, AUDIO & ALWAYS-ON ENGINE
        SectionTitle("VOICE, AUDIO & ALWAYS-ON ENGINE", LuxuryPalette.EmeraldNeon)
        LuxuryGlassCard {
            LuxuryGlassSwitchTile(
                title = "Always-On Voice Trigger",
                subtitle = "Continuous hands-free listening / auto wake in HUD & Agent modes",
                checked = snap.alwaysOnListening,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(alwaysOnListening = b) } },
                accentColor = LuxuryPalette.EmeraldNeon,
                icon = Icons.Rounded.Hearing
            )
            LuxuryGlassSwitchTile(
                title = "Neural Studio Human Voice",
                subtitle = "100% Online Human Voices via ElevenLabs, Gemini 2.5 Studio & Cloud Neural for ZEE / ZHEE",
                checked = snap.useNeuralVoice,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(useNeuralVoice = b) } },
                accentColor = LuxuryPalette.ElectricCyan,
                icon = Icons.Rounded.RecordVoiceOver
            )
            LuxuryGlassSwitchTile(
                title = "Spoken Step Feedback",
                subtitle = "Speak confirmations aloud as Boss loyalty replies",
                checked = snap.voiceFeedback,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(voiceFeedback = b) } },
                accentColor = LuxuryPalette.SkyAzure,
                icon = Icons.Rounded.VolumeUp
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = onTestVoice, modifier = Modifier.fillMaxWidth()) {
                Text("🔊 Test ZEE / ZHEE Neural Voice Calibration", color = Color.White)
            }
        }

        // 4. PERSONALITY & EMOTIONAL INTELLIGENCE
        SectionTitle("PERSONALITY & EMOTIONAL INTELLIGENCE", LuxuryPalette.QuantumViolet)
        LuxuryGlassCard(glowColor = LuxuryPalette.QuantumViolet) {
            Text("Companion Archetype", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PersonalityArchetype.entries.forEach { a ->
                    LuxuryGlassPill(a.label, LuxuryPalette.QuantumViolet, isSelected = snap.archetype == a) {
                        jarvisCore.prefs.update { it.copy(archetype = a) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(snap.archetype.desc, color = LuxuryPalette.MutedSlate, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Formality", color = LuxuryPalette.SilverText, fontSize = 11.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FormalityLevel.entries.forEach { f ->
                            LuxuryGlassPill(f.label, LuxuryPalette.ElectricCyan, isSelected = snap.formality == f) {
                                jarvisCore.prefs.update { it.copy(formality = f) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Wit & Humor Level", color = LuxuryPalette.SilverText, fontSize = 11.5.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HumorLevel.entries.forEach { h ->
                    LuxuryGlassPill(h.label, LuxuryPalette.SolarGold, isSelected = snap.humor == h) {
                        jarvisCore.prefs.update { it.copy(humor = h) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LuxuryGlassSwitchTile(
                title = "Emotional Intelligence Awareness",
                subtitle = "Matches Boss's urgency, celebration or tiredness naturally",
                checked = snap.emotionalAwareness,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(emotionalAwareness = b) } },
                accentColor = LuxuryPalette.QuantumViolet
            )
        }

        // 5. HERMES SELF-EVOLVING WORKFLOWS & HARDWARE PROTOCOLS
        SectionTitle("HERMES HARDWARE PROTOCOLS & SENTRY", LuxuryPalette.SolarGold)
        LuxuryGlassCard(glowColor = LuxuryPalette.SolarGold) {
            LuxuryGlassSwitchTile(
                title = "Hermes Multi-Step Protocol Detector",
                subtitle = "Say 'Study Mode', 'Night Mode', 'Work Mode', 'Cinema Mode', or 'Sentry'",
                checked = snap.workflowsEnabled,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(workflowsEnabled = b) } },
                accentColor = LuxuryPalette.SolarGold
            )
            jarvisCore.hermes.defaultWorkflows.forEach { wf ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(wf.name, color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Text(wf.description, color = LuxuryPalette.MutedSlate, fontSize = 10.5.sp)
                    }
                    LuxuryGlassPill("Test", LuxuryPalette.SolarGold) {
                        jarvisCore.hermes.executeWorkflow(wf, jarvisCore.sensors)
                        onSpeakFeedback(wf.spokenConfirmation)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            LuxuryGlassSwitchTile(
                title = "Tactical Sentry Motion Watchdog",
                subtitle = "Armed breaches: ${sentry.breachCount} ${sentry.lastBreachTime?.let { "• Last: $it" } ?: ""}",
                checked = sentry.isArmed,
                onCheckedChange = { b ->
                    jarvisCore.sensors.setSentryArmed(b)
                    onSpeakFeedback(if (b) "সেন্ট্রি ওয়াচডগ আর্ম করা হয়েছে বস!" else "সেন্ট্রি ওয়াচডগ বন্ধ করেছি বস।")
                },
                accentColor = LuxuryPalette.CrimsonAlert,
                icon = Icons.Rounded.Security
            )
        }

        // 6. FLOATING OVERLAY, TELEMETRY & STARK SUPERPOWERS SUITE
        SectionTitle("FLOATING HUD, TELEMETRY & SUPERPOWERS", LuxuryPalette.ElectricCyan)
        LuxuryGlassCard {
            LuxuryGlassSwitchTile(
                title = "Floating Arc-Reactor Overlay Bubble",
                subtitle = "Show floating orb over all apps to summarize screens or launch voice HUD",
                checked = snap.overlayBubbleActive && ZeeFloatingOverlayService.isRunning,
                onCheckedChange = { b ->
                    if (!jarvisCore.setOverlayActive(b)) {
                        onOpenPermissionsHub()
                    }
                },
                accentColor = LuxuryPalette.ElectricCyan,
                icon = Icons.Rounded.PictureInPictureAlt
            )
            Spacer(Modifier.height(8.dp))
            Text("Direct Hardware Telemetry", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LuxuryGlassPill("Check Specs", LuxuryPalette.ElectricCyan) {
                    val out = jarvisCore.sensors.getDirectSystemDiagnosticsSpoken()
                    telemetryResult = out; onSpeakFeedback(out)
                }
                LuxuryGlassPill("Top Apps", LuxuryPalette.IndigoLight) {
                    val out = jarvisCore.sensors.getTopLauncherAppsSpoken()
                    telemetryResult = out; onSpeakFeedback(out)
                }
                LuxuryGlassPill("Screen Time", LuxuryPalette.SolarGold) {
                    val out = jarvisCore.sensors.getTopUsedAppsSpoken()
                    telemetryResult = out; onSpeakFeedback(out)
                }
                LuxuryGlassPill("Write File", LuxuryPalette.EmeraldNeon) {
                    val out = jarvisCore.sensors.writeQuickNoteFile()
                    telemetryResult = out; onSpeakFeedback(out)
                }
            }
            telemetryResult?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(msg, color = LuxuryPalette.ChampagneGold, fontSize = 11.5.sp, lineHeight = 16.sp)
            }

            Spacer(Modifier.height(10.dp))
            Text("Stark Superpowers Quick Launch", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LuxuryGlassPill("🧠 Brain Dump", LuxuryPalette.IndigoLight, onClick = onOpenBrainDump)
                LuxuryGlassPill("🛰️ Radar", LuxuryPalette.EmeraldNeon, onClick = onOpenRadar)
                LuxuryGlassPill("🛡️ LAN Audit", LuxuryPalette.ElectricCyan, onClick = onOpenNetworkAudit)
                LuxuryGlassPill("📸 Optical AR", LuxuryPalette.SolarGold, onClick = onOpenOpticalSolver)
                LuxuryGlassPill("🌍 Polyglot", LuxuryPalette.SkyAzure, onClick = onOpenPolyglot)
                LuxuryGlassPill("⚡ Reactor", LuxuryPalette.SolarGold, onClick = onOpenChargingHud)
                LuxuryGlassPill("🚨 Code Black", LuxuryPalette.CrimsonAlert, onClick = onTriggerCodeBlack)
            }
        }

        // 7. MACRO RECORDER & CODIFIED SKILLS
        SectionTitle("MACRO RECORDER & CODIFIED SKILLS (${macros.size})", LuxuryPalette.CrimsonAlert)
        LuxuryGlassCard {
            LuxuryGlassSwitchTile(
                title = "Enable Skill & Macro Codifier",
                subtitle = "Observe Accessibility taps & scrolls to teach ZEE repeatable skills",
                checked = snap.macroRecorderEnabled,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(macroRecorderEnabled = b) } },
                accentColor = LuxuryPalette.CrimsonAlert
            )
            if (macros.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                macros.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.goal, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("${m.stepCount} actions recorded • Used ${m.successCount}x", color = LuxuryPalette.MutedSlate, fontSize = 10.5.sp)
                        }
                        IconButton(onClick = { jarvisCore.store.deleteMacro(m.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, null, tint = Color(0xFFF87171), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }

        // 8. SMART HOME (HOME ASSISTANT REST API)
        SectionTitle("SMART HOME (HOME ASSISTANT)", LuxuryPalette.SkyAzure)
        LuxuryGlassCard {
            LuxuryGlassSwitchTile(
                title = "Enable Home Assistant Bridge",
                subtitle = "Control smart lights, switches & scenes from ZEE voice or text",
                checked = snap.smartHomeEnabled,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(smartHomeEnabled = b) } },
                accentColor = LuxuryPalette.SkyAzure
            )
            Spacer(Modifier.height(6.dp))
            LuxuryGlassTextField(
                value = snap.homeAssistantUrl,
                onValueChange = { u -> jarvisCore.prefs.update { it.copy(homeAssistantUrl = u) } },
                label = "Home Assistant URL",
                placeholder = "http://homeassistant.local:8123"
            )
            Spacer(Modifier.height(6.dp))
            LuxuryGlassTextField(
                value = snap.homeAssistantToken,
                onValueChange = { t -> jarvisCore.prefs.update { it.copy(homeAssistantToken = t) } },
                label = "Long-Lived Access Token",
                obscureText = true
            )
            Spacer(Modifier.height(6.dp))
            LuxuryGlassTextField(
                value = snap.smartHomeDefaultEntity,
                onValueChange = { e -> jarvisCore.prefs.update { it.copy(smartHomeDefaultEntity = e) } },
                label = "Default Entity ID",
                placeholder = "light.living_room"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val res = jarvisCore.superpowers.executeSmartHomeCommand("toggle")
                        smartHomeOutput = res
                        onSpeakFeedback(res)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Toggle Smart Home Device", color = LuxuryPalette.SkyAzure) }
            smartHomeOutput?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = LuxuryPalette.SilverText, fontSize = 11.sp)
            }
        }

        // 9. PROACTIVE COMMUTE & AUTONOMOUS PROMPTS
        SectionTitle("PROACTIVE COMMUTE & AUTONOMOUS PROMPTS", LuxuryPalette.EmeraldNeon)
        LuxuryGlassCard {
            LuxuryGlassSwitchTile(
                title = "Commute & Traffic Watch",
                checked = snap.proactiveCommuteEnabled,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(proactiveCommuteEnabled = b) } },
                accentColor = LuxuryPalette.EmeraldNeon
            )
            LuxuryGlassTextField(
                value = snap.commuteHomeAddress,
                onValueChange = { s -> jarvisCore.prefs.update { it.copy(commuteHomeAddress = s) } },
                label = "Home Sector Address"
            )
            Spacer(Modifier.height(6.dp))
            LuxuryGlassTextField(
                value = snap.commuteWorkAddress,
                onValueChange = { s -> jarvisCore.prefs.update { it.copy(commuteWorkAddress = s) } },
                label = "Office / Campus Destination"
            )
            Spacer(Modifier.height(10.dp))
            Text("Autonomous Voice Prompt Triggers (Tap Test to Fire)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))

            ProactiveTriggerRow("Night Sleep Protocol Prompt", snap.proactiveNightPrompt, { b -> jarvisCore.prefs.update { it.copy(proactiveNightPrompt = b) } }) {
                jarvisCore.superpowers.triggerTestSuggestion("night") { onSpeakFeedback(it.spokenPrompt) }
            }
            ProactiveTriggerRow("Study / Deep Focus Prompt", snap.proactiveStudyPrompt, { b -> jarvisCore.prefs.update { it.copy(proactiveStudyPrompt = b) } }) {
                jarvisCore.superpowers.triggerTestSuggestion("study") { onSpeakFeedback(it.spokenPrompt) }
            }
            ProactiveTriggerRow("Upcoming Meeting Alert", snap.proactiveMeetingReminders, { b -> jarvisCore.prefs.update { it.copy(proactiveMeetingReminders = b) } }) {
                jarvisCore.superpowers.triggerTestSuggestion("meeting") { onSpeakFeedback(it.spokenPrompt) }
            }
            ProactiveTriggerRow("Pending Tasks Check-In", snap.proactiveTaskPrompt, { b -> jarvisCore.prefs.update { it.copy(proactiveTaskPrompt = b) } }) {
                jarvisCore.superpowers.triggerTestSuggestion("tasks") { onSpeakFeedback(it.spokenPrompt) }
            }
            ProactiveTriggerRow("Good Morning Briefing", snap.proactiveMorningPrompt, { b -> jarvisCore.prefs.update { it.copy(proactiveMorningPrompt = b) } }) {
                jarvisCore.superpowers.triggerTestSuggestion("morning") { onSpeakFeedback(it.spokenPrompt) }
            }
            ProactiveTriggerRow("Low Battery Reserve Advisory", snap.proactiveBatteryAlert, { b -> jarvisCore.prefs.update { it.copy(proactiveBatteryAlert = b) } }) {
                jarvisCore.superpowers.triggerTestSuggestion("battery") { onSpeakFeedback(it.spokenPrompt) }
            }
        }

        // 10. PRIVACY GOVERNANCE & PERMISSIONS HUB
        SectionTitle("PRIVACY GOVERNANCE & PERMISSIONS", LuxuryPalette.ElectricCyan)
        LuxuryGlassCard {
            LuxuryGlassSwitchTile(
                title = "Confirm Sensitive Device Actions",
                subtitle = "Ask Boss before critical calls, purchases or irreversible steps",
                checked = snap.confirmRiskyActions,
                onCheckedChange = { b -> jarvisCore.prefs.update { it.copy(confirmRiskyActions = b) } },
                accentColor = LuxuryPalette.SolarGold
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenPermissionsHub,
                colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.RoyalIndigo),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Open Permissions Hub (All 8 Bridges)", fontWeight = FontWeight.Bold)
            }
        }

        // 11. HERMES SELF-EVOLVING CONTEXT FILES (SOUL.md / USER.md / AGENT.md)
        SectionTitle("HERMES MARKDOWN MEMORY ENGINE", LuxuryPalette.QuantumViolet)
        LuxuryGlassCard(glowColor = LuxuryPalette.QuantumViolet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(hermesState.lastEvolvedLabel, color = LuxuryPalette.SilverText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                LuxuryGlassPill("⚡ Evolve Now", LuxuryPalette.QuantumViolet) {
                    val res = jarvisCore.runHermesEvolution(memorySnippets)
                    onSpeakFeedback(res)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LuxuryGlassPill("SOUL.md", LuxuryPalette.ElectricCyan, modifier = Modifier.weight(1f)) { editingDocKey = "SOUL" }
                LuxuryGlassPill("USER.md", LuxuryPalette.IndigoLight, modifier = Modifier.weight(1f)) { editingDocKey = "USER" }
                LuxuryGlassPill("AGENT.md", LuxuryPalette.SolarGold, modifier = Modifier.weight(1f)) { editingDocKey = "AGENT" }
            }
        }

        Spacer(Modifier.height(28.dp))
    }

    // Markdown Context Viewer & Editor Modal
    editingDocKey?.let { docKey ->
        val initial = when (docKey) {
            "SOUL" -> hermesState.soulMd
            "USER" -> hermesState.userMd
            else -> hermesState.agentMd
        }
        var draft by remember(docKey, initial) { mutableStateOf(initial) }
        Dialog(onDismissRequest = { editingDocKey = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LuxuryGlassCard(modifier = Modifier.fillMaxWidth(0.94f), glowColor = LuxuryPalette.QuantumViolet) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("HERMES CORE // $docKey.md", color = LuxuryPalette.ElectricCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    IconButton(onClick = { editingDocKey = null }) { Icon(Icons.Rounded.Close, null, tint = LuxuryPalette.MutedSlate) }
                }
                LuxuryGlassTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = "Markdown Configuration",
                    singleLine = false,
                    maxLines = 14
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { jarvisCore.hermes.resetAll(); editingDocKey = null },
                        modifier = Modifier.weight(1f)
                    ) { Text("Reset Defaults") }
                    Button(
                        onClick = {
                            when (docKey) {
                                "SOUL" -> jarvisCore.hermes.updateSoul(draft)
                                "USER" -> jarvisCore.hermes.updateUser(draft)
                                else -> jarvisCore.hermes.updateAgent(draft)
                            }
                            editingDocKey = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LuxuryPalette.RoyalIndigo),
                        modifier = Modifier.weight(1f)
                    ) { Text("Save $docKey.md") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, accent: Color) {
    Text(
        text = text,
        color = accent,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
    )
}

@Composable
private fun ProactiveTriggerRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onTestClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LuxuryPalette.SilverText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        LuxuryGlassPill("Test", LuxuryPalette.EmeraldNeon, onClick = onTestClick)
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = LuxuryPalette.EmeraldNeon.copy(alpha = 0.7f))
        )
    }
}

// =============================================================================
// 4. PREMIUM 4-STEP GUIDED ONBOARDING OVERLAY
// =============================================================================

@Composable
fun LuxuryOnboardingOverlay(
    prefsStore: JarvisPreferences,
    onRequestCorePermissions: () -> Unit,
    onDismiss: () -> Unit
) {
    val snap by prefsStore.state.collectAsState()
    var step by remember { mutableIntStateOf(0) }

    val titles = listOf(
        "Welcome to ZEE / JARVIS",
        "Dual Holographic Experience",
        "Hermes Brain & Local Freedom",
        "Grant Hardware Superpowers"
    )
    val bodies = listOf(
        "Your luxury Obsidian Glass AI companion — engineered to listen cleanly, control every corner of your phone, and address you loyally as 'বস' (Boss).",
        "Switch effortlessly between Autonomous Agent, Fast Dual-Engine Chat, and the interactive 3D Arc Reactor / Hologram Suit HUD.",
        "Run offline AI chat with on-device Gemma models, or connect Gemini, DeepSeek, OpenAI, Groq & ElevenLabs Studio Voices.",
        "Enable Microphone, Accessibility, Overlay & Notification bridges so ZEE can read screens, detect Air Gestures, and guard with Sentry."
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF02040A).copy(alpha = 0.96f))
            .padding(22.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Animated Step Progress Bars + Skip
            Column {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (i in 0..3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (i <= step) snap.luxuryAccent.primaryHex
                                    else Color.White.copy(alpha = 0.14f)
                                )
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        prefsStore.update { it.copy(onboardingComplete = true) }
                        onDismiss()
                    }) {
                        Text("Skip Tour", color = LuxuryPalette.MutedSlate, fontSize = 12.sp)
                    }
                }
            }

            // Center Feature Card
            LuxuryGlassCard(glowColor = snap.luxuryAccent.primaryHex) {
                Icon(
                    imageVector = when (step) {
                        0 -> Icons.Rounded.AutoAwesome
                        1 -> Icons.Rounded.ViewInAr
                        2 -> Icons.Rounded.Memory
                        else -> Icons.Rounded.Security
                    },
                    contentDescription = null,
                    tint = snap.luxuryAccent.primaryHex,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.height(14.dp))
                Text(titles[step], color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text(bodies[step], color = LuxuryPalette.SilverText, fontSize = 13.5.sp, lineHeight = 20.sp)

                if (step == 1) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LuxuryGlassPill(
                            "Arc Reactor Sphere",
                            LuxuryPalette.ElectricCyan,
                            isSelected = snap.hudStyle == HudVisualStyle.ARC_REACTOR
                        ) { prefsStore.update { it.copy(hudStyle = HudVisualStyle.ARC_REACTOR) } }
                        LuxuryGlassPill(
                            "3D Hologram Suit",
                            LuxuryPalette.QuantumViolet,
                            isSelected = snap.hudStyle == HudVisualStyle.HOLOGRAM_SUIT
                        ) { prefsStore.update { it.copy(hudStyle = HudVisualStyle.HOLOGRAM_SUIT) } }
                    }
                }
                if (step == 3) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = onRequestCorePermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("Request Core Permissions Now", color = LuxuryPalette.EmeraldNeon, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Bottom Next / Finish Navigation
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, modifier = Modifier.weight(1f)) {
                        Text("Back", color = LuxuryPalette.SilverText)
                    }
                }
                Button(
                    onClick = {
                        if (step < 3) {
                            step++
                        } else {
                            prefsStore.update { it.copy(onboardingComplete = true) }
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = snap.luxuryAccent.primaryHex),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1.4f)
                ) {
                    Text(
                        if (step < 3) "Continue" else "Enter ZEE HUD",
                        color = LuxuryPalette.ObsidianVoid,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
