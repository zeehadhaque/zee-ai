package com.zeeai.companion.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class ZeeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "ZeeControlService"
        const val ACTION_EXEC_CONTROL = "com.zeeai.companion.ACTION_EXEC_ACCESSIBILITY_CONTROL"
        const val ACTION_MACRO_CAPTURED = "com.zeeai.companion.ACTION_MACRO_CAPTURED"
        const val ACTION_SCREEN_SNAPSHOT = "com.zeeai.companion.ACTION_SCREEN_SNAPSHOT"
        const val EXTRA_CMD_TYPE = "cmd_type"
        const val EXTRA_CMD_ARG = "cmd_arg"
        const val EXTRA_PAYLOAD = "payload"

        @Volatile
        @JvmField
        var instance: ZeeAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun getInstance(): ZeeAccessibilityService? = instance

        /**
         * Isolated :control process-এ থাকা অবস্থাতেও মেইন অ্যাপ থেকে সঠিকভাবে Enabled স্ট্যাটাস চেক করে
         */
        fun isServiceEnabled(context: Context): Boolean {
            return runCatching {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabledList = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                enabledList.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            }.getOrDefault(false)
        }

        /**
         * মেইন অ্যাপ প্রসেস থেকে নিরাপদ package-locked অথবা ডাইরেক্ট ইন-প্রসেস কমান্ড পাঠায়
         */
        fun sendControlCommand(context: Context, type: String, arg: String = "") {
            val active = instance
            if (active != null) {
                active.mainHandler.post {
                    active.executeControlCommand(type, arg)
                }
                return
            }
            val intent = Intent(ACTION_EXEC_CONTROL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CMD_TYPE, type)
                putExtra(EXTRA_CMD_ARG, arg)
            }
            context.sendBroadcast(intent)
        }
    }

    private var isReceiverRegistered = false

    @Volatile
    private var isRecordingMacro = false
    private val macroEvents = mutableListOf<JSONObject>()

    private fun executeControlCommand(type: String, arg: String) {
        runCatching {
            when (type.uppercase()) {
                "NAV" -> handleGlobalNavigation(arg)
                "SCROLL" -> scrollCurrentScreen(down = arg.equals("DOWN", ignoreCase = true))
                "CLICK_TEXT" -> clickNodeContainingText(arg)
                "TYPE_TEXT" -> typeTextIntoFocusedField(arg)
                "OPEN_STORY" -> openStoryCreator()
                "CLICK_SEND" -> autoClickSendAfterDelay(arg.toLongOrNull() ?: 900L)
                "CLICK_CALL" -> autoClickCallAfterDelay(arg.toLongOrNull() ?: 500L)
                "CLICK_XY" -> {
                    val parts = arg.split("|")
                    val x = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
                    val y = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                    clickAtXY(x, y)
                }
                "SWIPE" -> {
                    val parts = arg.split("|")
                    val sx = parts.getOrNull(0)?.toFloatOrNull() ?: 500f
                    val sy = parts.getOrNull(1)?.toFloatOrNull() ?: 1200f
                    val ex = parts.getOrNull(2)?.toFloatOrNull() ?: 500f
                    val ey = parts.getOrNull(3)?.toFloatOrNull() ?: 400f
                    val dur = parts.getOrNull(4)?.toLongOrNull() ?: 350L
                    swipeCoords(sx, sy, ex, ey, dur)
                }
                "MACRO_START" -> startMacroRecording()
                "MACRO_STOP" -> {
                    val arr = stopMacroRecording()
                    val out = Intent(ACTION_MACRO_CAPTURED).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PAYLOAD, arr.toString())
                    }
                    sendBroadcast(out)
                }
                "SNAPSHOT_SCREEN" -> {
                    val textDump = readActiveScreenText(3000)
                    val out = Intent(ACTION_SCREEN_SNAPSHOT).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_PAYLOAD, textDump)
                    }
                    sendBroadcast(out)
                }
            }
        }.onFailure { err ->
            Log.e(TAG, "Control command failed ($type/$arg): ${err.message}")
        }
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_EXEC_CONTROL) return
            val type = intent.getStringExtra(EXTRA_CMD_TYPE) ?: return
            val arg = intent.getStringExtra(EXTRA_CMD_ARG).orEmpty()
            executeControlCommand(type, arg)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching {
            instance = this
            _isConnected.value = true
            if (!isReceiverRegistered) {
                val filter = IntentFilter(ACTION_EXEC_CONTROL)
                ContextCompat.registerReceiver(
                    this,
                    controlReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                isReceiverRegistered = true
            }
        }.onFailure { err ->
            Log.e(TAG, "onServiceConnected error: ${err.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Record only when Macro Recording is explicitly armed — zero CPU overhead otherwise
        if (!isRecordingMacro || event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg.isBlank()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                val rect = Rect()
                node?.getBoundsInScreen(rect)
                val label = event.text?.joinToString(" ")?.trim()?.ifBlank {
                    event.contentDescription?.toString()?.trim().orEmpty()
                }.orEmpty()
                val obj = JSONObject().apply {
                    put("type", "click")
                    put("label", label.ifBlank { "Tap(${rect.centerX()},${rect.centerY()})" })
                    put("x", rect.centerX())
                    put("y", rect.centerY())
                    put("package", pkg)
                    put("timestamp", System.currentTimeMillis())
                }
                synchronized(macroEvents) { macroEvents.add(obj) }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString("").orEmpty()
                if (text.isNotBlank()) {
                    val obj = JSONObject().apply {
                        put("type", "type")
                        put("text", text)
                        put("package", pkg)
                        put("timestamp", System.currentTimeMillis())
                    }
                    synchronized(macroEvents) {
                        if (macroEvents.isNotEmpty() && macroEvents.last().optString("type") == "type") {
                            macroEvents[macroEvents.size - 1] = obj
                        } else {
                            macroEvents.add(obj)
                        }
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                synchronized(macroEvents) {
                    val last = macroEvents.lastOrNull()
                    val lastTime = last?.optLong("timestamp", 0L) ?: 0L
                    if (last?.optString("type") != "scroll" || (System.currentTimeMillis() - lastTime > 700L)) {
                        macroEvents.add(
                            JSONObject().apply {
                                put("type", "scroll")
                                put("direction", "down")
                                put("package", pkg)
                                put("timestamp", System.currentTimeMillis())
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanupReceiver()
        instance = null
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanupReceiver()
        instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    private fun cleanupReceiver() {
        if (isReceiverRegistered) {
            runCatching { unregisterReceiver(controlReceiver) }
            isReceiverRegistered = false
        }
    }

    fun startMacroRecording() {
        synchronized(macroEvents) { macroEvents.clear() }
        isRecordingMacro = true
    }

    fun stopMacroRecording(): JSONArray {
        isRecordingMacro = false
        val arr = JSONArray()
        synchronized(macroEvents) {
            macroEvents.forEach { arr.put(it) }
        }
        return arr
    }

    fun handleGlobalNavigation(nav: String): Boolean {
        val actionId = when (nav.uppercase()) {
            "HOME" -> GLOBAL_ACTION_HOME
            "BACK" -> GLOBAL_ACTION_BACK
            "RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "POWER_DIALOG" -> GLOBAL_ACTION_POWER_DIALOG
            "LOCK" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else -1
            "SCREENSHOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        return if (actionId != -1) performGlobalAction(actionId) else false
    }

    fun navigate(nav: String): Boolean = handleGlobalNavigation(nav)

    fun clickAtXY(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, mainHandler)
    }

    fun swipeCoords(sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Long = 350L): Boolean {
        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 4000L))
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, mainHandler)
    }

    fun clickNodeContainingText(query: String): Boolean {
        val root = findBestForegroundWindowRoot() ?: return false
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isEmpty()) return false

        val targets = root.findAccessibilityNodeInfosByText(query).orEmpty()
        for (node in targets) {
            if (clickNearestClickable(node)) return true
        }

        // Fallback walk checking contentDescription as well as text
        fun walkAndClick(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 28) return false
            val txt = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            if (txt.contains(cleanQuery) || desc.contains(cleanQuery)) {
                if (clickNearestClickable(node)) return true
            }
            for (i in 0 until node.childCount) {
                if (walkAndClick(node.getChild(i), depth + 1)) return true
            }
            return false
        }
        return walkAndClick(root, 0)
    }

    private fun clickNearestClickable(start: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = start
        var depth = 0
        while (current != null && !current.isClickable && depth < 8) {
            current = current.parent
            depth++
        }
        return current != null && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun scrollCurrentScreen(down: Boolean): Boolean {
        val root = findBestForegroundWindowRoot()
        if (root != null) {
            val action = if (down) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            fun walk(node: AccessibilityNodeInfo?, depth: Int): Boolean {
                if (node == null || depth > 25) return false
                if (node.isScrollable && node.performAction(action)) return true
                for (i in 0 until node.childCount) {
                    if (walk(node.getChild(i), depth + 1)) return true
                }
                return false
            }
            if (walk(root, 0)) return true
        }

        // Fallback: dispatch physical swipe gesture for Compose/WebView/Reels UIs
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = if (down) metrics.heightPixels * 0.72f else metrics.heightPixels * 0.30f
        val endY = if (down) metrics.heightPixels * 0.28f else metrics.heightPixels * 0.75f
        return swipeCoords(x, startY, x, endY, 320L)
    }

    fun typeTextIntoFocusedField(text: String): Boolean {
        val root = findBestForegroundWindowRoot() ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root, 0)
            ?: return false

        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun autoTypeAndSend(text: String): Boolean {
        val ok = typeTextIntoFocusedField(text)
        if (ok) autoClickSendAfterDelay(350L)
        return ok
    }

    fun openStoryCreator(): Boolean {
        return clickNodeContainingText("story") || clickNodeContainingText("স্টোরি")
    }

    fun autoClickSendAfterDelay(delayMs: Long = 900L) {
        mainHandler.postDelayed({
            clickNodeContainingText("send") || clickNodeContainingText("পাঠান") || clickNodeContainingText("পাঠাও")
        }, delayMs)
    }

    fun autoClickCallAfterDelay(delayMs: Long = 500L) {
        mainHandler.postDelayed({
            clickNodeContainingText("call") || clickNodeContainingText("dial") || clickNodeContainingText("কল")
        }, delayMs)
    }

    private fun findBestForegroundWindowRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName != packageName) return active
        val wins = runCatching { windows?.toList().orEmpty() }.getOrDefault(emptyList())
        return wins.firstOrNull { (it.isActive || it.isFocused) && it.root?.packageName != packageName }?.root
            ?: wins.firstOrNull { it.root?.packageName != packageName }?.root
            ?: active
    }

    fun readActiveScreenText(maxChars: Int = 2500): String {
        val root = findBestForegroundWindowRoot() ?: return ""
        val sb = StringBuilder()
        fun extract(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30 || sb.length >= maxChars) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(text)
            } else if (!desc.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append(" | ")
                sb.append(desc)
            }
            for (i in 0 until node.childCount) {
                if (sb.length >= maxChars) break
                extract(node.getChild(i), depth + 1)
            }
        }
        extract(root, 0)
        return sb.toString().take(maxChars)
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        if (node.isEditable && node.isVisibleToUser && node.isEnabled) return node
        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }
}
