package com.zeeai.companion.jarvis

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.zeeai.companion.automation.ZeeAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ChargingTelemetrySnapshot(
    val isCharging: Boolean = false,
    val batteryLevel: Int = 85,
    val voltageVolts: Float = 4.2f,
    val currentMa: Int = 1800,
    val wattage: Float = 24.5f,
    val temperatureC: Float = 31.4f,
    val chargeType: String = "AC Turbo / Direct Flux"
)

data class SentryStatus(
    val isArmed: Boolean = false,
    val breachCount: Int = 0,
    val lastBreachTime: String? = null
)

/**
 * Hardware Sensors & System Telemetry Engine:
 * - Zero-Touch Air Gestures (Sensor.TYPE_PROXIMITY)
 * - Tactical Sentry Watchdog (Sensor.TYPE_ACCELEROMETER)
 * - Arc Reactor Charging Telemetry (ACTION_POWER_CONNECTED + BatteryManager)
 * - Direct Hardware Diagnostics, Top Apps & Storage File Creator
 */
class HardwareSensorsController(private val context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _airGesturesEnabled = MutableStateFlow(false)
    val airGesturesEnabled: StateFlow<Boolean> = _airGesturesEnabled.asStateFlow()

    private val _airGestureStatus = MutableStateFlow("SENSORS STANDBY // WAVE NEAR EARPIECE")
    val airGestureStatus: StateFlow<String> = _airGestureStatus.asStateFlow()

    private val _activeGestureBanner = MutableStateFlow<String?>(null)
    val activeGestureBanner: StateFlow<String?> = _activeGestureBanner.asStateFlow()

    private val _sentryStatus = MutableStateFlow(SentryStatus())
    val sentryStatus: StateFlow<SentryStatus> = _sentryStatus.asStateFlow()

    private val _chargingTelemetry = MutableStateFlow(readBatteryTelemetry())
    val chargingTelemetry: StateFlow<ChargingTelemetrySnapshot> = _chargingTelemetry.asStateFlow()

    var onGestureExecuted: ((gestureName: String, spokenFeedback: String) -> Unit)? = null
    var onSentryBreachDetected: ((spokenWarning: String) -> Unit)? = null
    var onPowerConnected: ((ChargingTelemetrySnapshot) -> Unit)? = null

    private var proximityNearTime = 0L
    private var lastWaveTime = 0L
    private var lastAccelMag = 0f
    private var sentryLastTrigger = 0L

    private val proximityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!_airGesturesEnabled.value || event == null) return
            val dist = event.values[0]
            val maxRange = event.sensor.maximumRange
            val isNear = (dist < maxRange && dist <= 4.0f) || dist == 0f
            val now = System.currentTimeMillis()
            if (isNear) {
                if (proximityNearTime == 0L) {
                    proximityNearTime = now
                    _airGestureStatus.value = "HAND DETECTED NEAR SENSOR..."
                }
            } else if (proximityNearTime > 0L) {
                val dur = now - proximityNearTime
                proximityNearTime = 0L
                if (dur in 25..800) {
                    val waveDelta = now - lastWaveTime
                    if (waveDelta in 80..1200) {
                        lastWaveTime = 0L
                        dispatchAirGesture("double_wave")
                    } else {
                        lastWaveTime = now
                        mainHandler.postDelayed({
                            if (lastWaveTime == now) dispatchAirGesture("wave")
                        }, 420L)
                    }
                } else if (dur in 801..3500) {
                    dispatchAirGesture("palm_hover")
                } else {
                    _airGestureStatus.value = "OPTICAL PROXIMITY ACTIVE"
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val sentryListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (!_sentryStatus.value.isArmed || event == null) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val mag = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (lastAccelMag == 0f) {
                lastAccelMag = mag
                return
            }
            val delta = abs(mag - lastAccelMag)
            lastAccelMag = mag
            if (delta > 2.2f) {
                val now = System.currentTimeMillis()
                if (now - sentryLastTrigger > 6000L) {
                    sentryLastTrigger = now
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val cur = _sentryStatus.value
                    _sentryStatus.value = cur.copy(
                        breachCount = cur.breachCount + 1,
                        lastBreachTime = timeStr
                    )
                    onSentryBreachDetected?.invoke(
                        "সতর্ক হোন! বসের ফোনে অনুমোদনহীন মুভমেন্ট ধরা পড়েছে। এখনই ফোন রেখে দিন!"
                    )
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private var powerReceiverRegistered = false
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val isConn = action == Intent.ACTION_POWER_CONNECTED
            val snap = readBatteryTelemetry(forceCharging = isConn)
            _chargingTelemetry.value = snap
            if (isConn) {
                onPowerConnected?.invoke(snap)
            }
        }
    }

    fun startPowerMonitor() {
        if (powerReceiverRegistered) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(appContext, powerReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            powerReceiverRegistered = true
        }
    }

    fun toggleAirGestures(): Boolean {
        val next = !_airGesturesEnabled.value
        setAirGesturesEnabled(next)
        return next
    }

    fun setAirGesturesEnabled(enable: Boolean) {
        _airGesturesEnabled.value = enable
        runCatching { sensorManager?.unregisterListener(proximityListener) }
        if (enable && proximitySensor != null) {
            sensorManager?.registerListener(proximityListener, proximitySensor, SensorManager.SENSOR_DELAY_UI)
            _airGestureStatus.value = "OPTICAL PROXIMITY ACTIVE"
        } else {
            _airGestureStatus.value = if (proximitySensor == null && enable) "SIMULATION READY" else "SENSORS STANDBY"
        }
    }

    fun simulateGesture(gesture: String) = dispatchAirGesture(gesture)

    private fun dispatchAirGesture(gesture: String) {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val (banner, reply) = when (gesture) {
            "wave" -> {
                runCatching {
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                }
                "👋 AIR WAVE // NEXT MEDIA TRACK" to "এয়ার ওয়েভ ডিটেক্ট হয়েছে বস! পরের ট্র্যাকে স্কিপ করেছি।"
            }
            "palm_hover" -> {
                runCatching {
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                }
                "✋ PALM HOVER // PLAY-PAUSE TOGGLED" to "পাম হোভার ডিটেক্ট হয়েছে বস! প্লে-পজ টগল করেছি।"
            }
            "double_wave" -> {
                ZeeAccessibilityService.sendControlCommand(appContext, "NAV", "SCREENSHOT")
                "✌️ DOUBLE WAVE // SCREENSHOT CAPTURED" to "ডাবল ওয়েভে স্ক্রিনশট নিয়ে নিয়েছি বস!"
            }
            else -> "✋ AIR GESTURE // EXECUTED" to "এয়ার জেসচার সম্পন্ন হয়েছে বস!"
        }
        _airGestureStatus.value = banner
        _activeGestureBanner.value = banner
        mainHandler.postDelayed({ _activeGestureBanner.value = null }, 3200L)
        onGestureExecuted?.invoke(gesture, reply)
    }

    fun setSentryArmed(armed: Boolean) {
        runCatching { sensorManager?.unregisterListener(sentryListener) }
        lastAccelMag = 0f
        if (armed && accelerometer != null) {
            sensorManager?.registerListener(sentryListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
        _sentryStatus.value = _sentryStatus.value.copy(isArmed = armed)
    }

    fun readBatteryTelemetry(forceCharging: Boolean? = null): ChargingTelemetrySnapshot {
        return runCatching {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it >= 0 }
                ?: intent?.let {
                    val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (l >= 0 && s > 0) (l * 100) / s else 80
                } ?: 80
            val isChg = forceCharging ?: (bm?.isCharging ?: false)
            val mv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4200) ?: 4200
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 315) ?: 315) / 10f
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            val currentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            val currentMa = abs(currentUa) / 1000
            val volts = if (mv > 0) mv / 1000f else 4.2f
            val amps = if (currentMa > 50) currentMa / 1000f else if (isChg) 3.0f else 0.45f
            val watts = ((volts * amps) * 10f).roundToInt() / 10f
            val type = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC Turbo Adapter"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB Bus Line"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless Flux"
                else -> if (isChg) "Direct Power" else "Internal Arc Core"
            }
            ChargingTelemetrySnapshot(isChg, level, volts, if (currentMa > 0) currentMa else 1500, watts, temp, type)
        }.getOrDefault(ChargingTelemetrySnapshot())
    }

    fun getDirectSystemDiagnosticsSpoken(): String {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val totalRamGb = (mem.totalMem / (1024.0 * 1024.0 * 1024.0))
        val freeRamGb = (mem.availMem / (1024.0 * 1024.0 * 1024.0))
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalRom = stat.totalBytes / (1024L * 1024L * 1024L)
        val freeRom = stat.availableBytes / (1024L * 1024L * 1024L)
        val batt = readBatteryTelemetry()
        return "ডিভাইস ডায়াগনস্টিকস সম্পন্ন বস: মডেল ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}। RAM ফ্রি আছে ${"%.1f".format(freeRamGb)} / ${"%.1f".format(totalRamGb)} GB, স্টোরেজ ফ্রি $freeRom / $totalRom GB, এবং ব্যাটারি ${batt.batteryLevel}% (${batt.temperatureC}°C)।"
    }

    fun getTopLauncherAppsSpoken(): String {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = runCatching {
            pm.queryIntentActivities(intent, 0)
                .map { it.loadLabel(pm).toString() }
                .distinct()
                .take(8)
        }.getOrDefault(emptyList())
        return if (apps.isEmpty()) "বস, লঞ্চার অ্যাপ লিস্ট পাওয়া যায়নি।"
        else "ফোনের প্রধান অ্যাপগুলো হলো বস: ${apps.joinToString(", ")}।"
    }

    fun hasUsageStatsPermission(): Boolean {
        return runCatching {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    fun getTopUsedAppsSpoken(): String {
        if (!hasUsageStatsPermission()) {
            return "স্ক্রিন টাইম দেখতে Settings -> Permissions Hub থেকে Usage Access চালু করুন বস।"
        }
        return runCatching {
            val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val cal = Calendar.getInstance()
            val end = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, cal.timeInMillis, end).orEmpty()
            val pm = appContext.packageManager
            val top = stats.filter { it.totalTimeInForeground > 60_000L }
                .sortedByDescending { it.totalTimeInForeground }
                .take(4)
                .map { s ->
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName)
                    val mins = s.totalTimeInForeground / 60_000L
                    "$label (${mins}m)"
                }
            if (top.isEmpty()) "আজকের উল্লেখযোগ্য অ্যাপ ইউজেজ ডেটা নেই বস।"
            else "আজকের সবচেয়ে বেশি ব্যবহৃত অ্যাপ বস: ${top.joinToString(", ")}।"
        }.getOrDefault("স্ক্রিন টাইম রিড করা যায়নি বস।")
    }

    fun writeQuickNoteFile(): String {
        return runCatching {
            val dir = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: appContext.filesDir, "zee_notes").apply { mkdirs() }
            val f = File(dir, "zee_note_${System.currentTimeMillis()}.txt")
            f.writeText("ZEE AI Executive Note for Boss\nGenerated cleanly on-device.")
            "ফাইল তৈরি হয়েছে বস: ${f.name}"
        }.getOrElse { "নোট ফাইল তৈরি ব্যর্থ: ${it.message}" }
    }

    fun release() {
        runCatching { sensorManager?.unregisterListener(proximityListener) }
        runCatching { sensorManager?.unregisterListener(sentryListener) }
        if (powerReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(powerReceiver) }
            powerReceiverRegistered = false
        }
    }
}
