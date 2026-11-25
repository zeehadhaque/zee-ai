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

        fun currentForegroundPackage(): String =
            instance?.getForegroundPackageName().orEmpty()

        fun captureScreenMap(): InteractiveScreenMap? =
            instance?.captureInteractiveScreenMap()

        fun performClickNodeIndex(index: Int): Boolean =
            instance?.clickCapturedNode(index) ?: false

        fun performTypeNodeIndex(index: Int, text: String, submitAfter: Boolean): Boolean =
            instance?.typeIntoCapturedNode(index, text, submitAfter) ?: false

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
                "SCROLL" -> swipeDirection(arg.uppercase())
                "CLICK_TEXT" -> clickNodeContainingText(arg)
                "TYPE_TEXT" -> typeTextIntoFocusedField(arg, preferBottomChatBox = true)
                "TYPE_AND_SEND" -> autoTypeAndSend(arg, isPostOrComment = false)
                "TYPE_AND_POST" -> autoTypeAndSend(arg, isPostOrComment = true)
                "SEARCH_IN_APP" -> searchInActiveApp(arg)
                "FIND_OR_SEARCH" -> findOrSearchOnScreen(arg)
                "FB_POST" -> prepareFacebookPost(arg)
                "YT_PLAY_FIRST" -> autoClickFirstYouTubeResult(arg.toLongOrNull() ?: 1800L)
                "SKIP_AD" -> skipAdIfPresent()
                "OPEN_STORY" -> openStoryCreator()
                "CLICK_SEND" -> autoClickSendAfterDelay(arg.toLongOrNull() ?: 750L)
                "CLICK_POST" -> autoClickPostAfterDelay(arg.toLongOrNull() ?: 750L)
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

    fun scrollCurrentScreen(down: Boolean): Boolean =
        swipeDirection(if (down) "DOWN" else "UP")

    fun swipeDirection(direction: String): Boolean {
        val dir = direction.uppercase()
        if (dir == "LEFT" || dir == "RIGHT") {
            val metrics = resources.displayMetrics
            val y = metrics.heightPixels * 0.5f
            val sx = if (dir == "LEFT") metrics.widthPixels * 0.80f else metrics.widthPixels * 0.20f
            val ex = if (dir == "LEFT") metrics.widthPixels * 0.20f else metrics.widthPixels * 0.80f
            return swipeCoords(sx, y, ex, y, 280L)
        }

        val down = dir != "UP"
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

        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = if (down) metrics.heightPixels * 0.74f else metrics.heightPixels * 0.28f
        val endY = if (down) metrics.heightPixels * 0.26f else metrics.heightPixels * 0.74f
        return swipeCoords(x, startY, x, endY, 300L)
    }

    fun getForegroundPackageName(): String {
        return findBestForegroundWindowRoot()?.packageName?.toString().orEmpty()
    }

    private fun collectEditableNodes(
        node: AccessibilityNodeInfo?,
        depth: Int,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null || depth > 28) return
        val cls = node.className?.toString().orEmpty()
        val isEdit = node.isEditable || cls.contains("EditText", ignoreCase = true) || cls.contains("AutoComplete", ignoreCase = true)
        if (isEdit && node.isVisibleToUser && node.isEnabled) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditableNodes(node.getChild(i), depth + 1, out)
        }
    }

    private fun findBestEditableNode(
        root: AccessibilityNodeInfo,
        preferBottomChatBox: Boolean
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(root, 0, candidates)
        if (candidates.isEmpty()) return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isVisibleToUser && focused.isEnabled) {
            val r = Rect()
            focused.getBoundsInScreen(r)
            if (candidates.size == 1 || (preferBottomChatBox && r.bottom > resources.displayMetrics.heightPixels / 2)) {
                return focused
            }
        }

        val rectA = Rect()
        val rectB = Rect()
        val sorted = candidates.sortedWith { a, b ->
            a.getBoundsInScreen(rectA)
            b.getBoundsInScreen(rectB)
            if (preferBottomChatBox) rectB.bottom.compareTo(rectA.bottom) else rectA.top.compareTo(rectB.top)
        }
        return sorted.firstOrNull() ?: focused
    }

    private fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun performImeEnter(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    fun typeTextIntoFocusedField(text: String, preferBottomChatBox: Boolean = true): Boolean {
        val root = findBestForegroundWindowRoot() ?: return false
        val target = findBestEditableNode(root, preferBottomChatBox) ?: return false
        return setTextOnNode(target, text)
    }

    private fun clickActionButton(keywords: List<String>, idHints: List<String>): Boolean {
        val root = findBestForegroundWindowRoot() ?: return false
        val kwLower = keywords.map { it.lowercase() }
        val idLower = idHints.map { it.lowercase() }
        val matches = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28) return
            if (node.isVisibleToUser && node.isEnabled) {
                val txt = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                val vid = node.viewIdResourceName?.lowercase().orEmpty()
                val shortId = vid.substringAfterLast("/")

                var score = 0
                if (txt.isNotEmpty() && txt.length <= 28) {
                    if (kwLower.any { txt == it }) score = maxOf(score, 100)
                    else if (kwLower.any { txt.startsWith(it) || txt.contains(it) }) score = maxOf(score, 70)
                }
                if (desc.isNotEmpty() && desc.length <= 36) {
                    if (kwLower.any { desc == it }) score = maxOf(score, 95)
                    else if (kwLower.any { desc.startsWith(it) || desc.contains(it) }) score = maxOf(score, 65)
                }
                if (shortId.isNotEmpty() && idLower.any { shortId == it || shortId.contains(it) }) {
                    score = maxOf(score, 80)
                }

                if (score > 0) {
                    matches.add(score to node)
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
            }
        }
        walk(root, 0)

        for ((_, node) in matches.sortedByDescending { it.first }) {
            if (clickNearestClickable(node)) return true
        }
        return false
    }

    fun autoTypeAndSend(text: String, isPostOrComment: Boolean = false): Boolean {
        val root = findBestForegroundWindowRoot() ?: return false
        val target = findBestEditableNode(root, preferBottomChatBox = !isPostOrComment) ?: return false
        if (!setTextOnNode(target, text)) return false

        if (isPostOrComment) {
            autoClickPostAfterDelay(450L)
        } else {
            mainHandler.postDelayed({
                val clicked = clickActionButton(
                    keywords = listOf("send", "send message", "পাঠান", "পাঠাও", "reply", "send now"),
                    idHints = listOf("send", "send_btn", "send_button", "composer_send", "conversation_send")
                )
                if (!clicked) {
                    val freshRoot = findBestForegroundWindowRoot()
                    val freshFocus = freshRoot?.let { findBestEditableNode(it, true) }
                    performImeEnter(freshFocus)
                }
            }, 420L)
        }
        return true
    }

    fun searchInActiveApp(query: String): Boolean {
        val clean = query.trim()
        if (clean.isEmpty()) return false
        val root = findBestForegroundWindowRoot() ?: return false

        // 1. Check if an editable search bar is already visible on screen
        val existingInput = findBestEditableNode(root, preferBottomChatBox = false)
        if (existingInput != null) {
            val vid = existingInput.viewIdResourceName?.lowercase().orEmpty()
            val desc = existingInput.contentDescription?.toString()?.lowercase().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                existingInput.hintText?.toString()?.lowercase().orEmpty()
            } else ""
            val looksSearch = vid.contains("search") || vid.contains("url") || vid.contains("omnibox") ||
                desc.contains("search") || hint.contains("search") || hint.contains("khuj") ||
                hint.contains("সার্চ") || hint.contains("type web address")
            if (looksSearch) {
                if (!setTextOnNode(existingInput, clean)) return false
                mainHandler.postDelayed({
                    performImeEnter(findBestForegroundWindowRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: existingInput)
                    clickActionButton(
                        keywords = listOf("search", "go", "submit", "সার্চ", "খুঁজুন"),
                        idHints = listOf("search_go_btn", "search_button", "go", "submit")
                    )
                }, 280L)
                return true
            }
        }

        // 2. Click the active app's Search icon/bar first, then enter the query
        val openedSearch = clickActionButton(
            keywords = listOf("search", "search youtube", "search whatsapp", "search or type web address", "ask meta ai or search", "সার্চ", "খুঁজুন"),
            idHints = listOf("menu_search", "search", "search_button", "search_bar", "url_bar", "search_mag_icon")
        )
        if (!openedSearch) return false

        mainHandler.postDelayed({
            val afterRoot = findBestForegroundWindowRoot() ?: return@postDelayed
            val input = findBestEditableNode(afterRoot, preferBottomChatBox = false) ?: return@postDelayed
            if (setTextOnNode(input, clean)) {
                mainHandler.postDelayed({
                    performImeEnter(findBestForegroundWindowRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: input)
                    clickActionButton(
                        keywords = listOf("search", "go", "সার্চ", "খুঁজুন"),
                        idHints = listOf("search_go_btn", "search_button")
                    )
                }, 300L)
            }
        }, 500L)
        return true
    }

    fun findOrSearchOnScreen(query: String): Boolean {
        val clean = query.trim()
        if (clean.isEmpty()) return false
        // Prefer searching inside the active app if it looks like a multi-word search; or click visible item directly first
        if (clean.split(" ").size <= 2 && clickNodeContainingText(clean)) {
            return true
        }
        return searchInActiveApp(clean)
    }

    fun prepareFacebookPost(text: String) {
        val clean = text.trim()
        mainHandler.postDelayed({
            clickActionButton(
                keywords = listOf("what's on your mind", "write something", "create post", "মনে কী চলছে", "post"),
                idHints = listOf("feed_composer", "composer", "create_post")
            )
            if (clean.isNotBlank()) {
                mainHandler.postDelayed({
                    val r = findBestForegroundWindowRoot() ?: return@postDelayed
                    val box = findBestEditableNode(r, preferBottomChatBox = false) ?: return@postDelayed
                    setTextOnNode(box, clean)
                    mainHandler.postDelayed({
                        clickActionButton(
                            keywords = listOf("next", "post", "publish", "share", "পোস্ট"),
                            idHints = listOf("composer_post_button", "post_button", "submit")
                        )
                    }, 550L)
                }, 950L)
            }
        }, 900L)
    }

    fun autoClickFirstYouTubeResult(delayMs: Long = 1800L) {
        mainHandler.postDelayed({
            val root = findBestForegroundWindowRoot() ?: return@postDelayed
            val minY = (resources.displayMetrics.heightPixels * 0.16f).toInt()
            val maxY = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            val rect = Rect()

            fun walkVideo(node: AccessibilityNodeInfo?, depth: Int): Boolean {
                if (node == null || depth > 25) return false
                if (node.isVisibleToUser && node.isClickable) {
                    node.getBoundsInScreen(rect)
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    val txt = node.text?.toString()?.lowercase().orEmpty()
                    val combined = "$desc $txt"
                    val isTopChrome = rect.top < minY || rect.bottom > maxY
                    val isVideoCard = !isTopChrome &&
                        rect.height() > 140 &&
                        rect.width() > (resources.displayMetrics.widthPixels * 0.45f).toInt() &&
                        !combined.contains("filter") &&
                        !combined.contains("sponsored") &&
                        !combined.contains("subscribe") &&
                        (combined.contains("minute") || combined.contains("second") || combined.contains("hour") ||
                            combined.contains("views") || combined.contains("মিনিট") || combined.length > 18)
                    if (isVideoCard && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                for (i in 0 until node.childCount) {
                    if (walkVideo(node.getChild(i), depth + 1)) return true
                }
                return false
            }
            walkVideo(root, 0)
        }, delayMs)
    }

    fun skipAdIfPresent(): Boolean {
        return clickActionButton(
            keywords = listOf("skip ad", "skip ads", "skip", "স্কিপ"),
            idHints = listOf("skip_ad_button", "skip_button")
        )
    }

    fun openStoryCreator(): Boolean {
        return clickActionButton(
            keywords = listOf("create story", "add to story", "your story", "story", "স্টোরি"),
            idHints = listOf("create_story", "story")
        )
    }

    fun autoClickSendAfterDelay(delayMs: Long = 750L) {
        mainHandler.postDelayed({
            clickActionButton(
                keywords = listOf("send", "send message", "পাঠান", "পাঠাও"),
                idHints = listOf("send", "send_btn", "send_button", "composer_send")
            )
        }, delayMs)
    }

    fun autoClickPostAfterDelay(delayMs: Long = 750L) {
        mainHandler.postDelayed({
            clickActionButton(
                keywords = listOf("post", "publish", "share", "reply", "comment", "পোস্ট", "শেয়ার"),
                idHints = listOf("post", "post_button", "submit", "publish")
            )
        }, delayMs)
    }

    fun autoClickCallAfterDelay(delayMs: Long = 500L) {
        mainHandler.postDelayed({
            clickActionButton(
                keywords = listOf("call", "dial", "কল"),
                idHints = listOf("dialpad_floating_action_button", "call_button", "call")
            )
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

    data class UiNodeSnapshot(
        val index: Int,
        val label: String,
        val resourceId: String,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val centerX: Int,
        val centerY: Int
    )

    data class InteractiveScreenMap(
        val packageName: String,
        val summaryText: String,
        val nodes: List<UiNodeSnapshot>,
        val formattedForAi: String
    )

    @Volatile
    private var lastCapturedNodes: List<AccessibilityNodeInfo> = emptyList()

    fun captureInteractiveScreenMap(): InteractiveScreenMap {
        val root = findBestForegroundWindowRoot()
            ?: return InteractiveScreenMap("", "", emptyList(), "NO_FOREGROUND_WINDOW")
        val pkg = root.packageName?.toString().orEmpty()
        val snapshots = mutableListOf<UiNodeSnapshot>()
        val rawNodes = mutableListOf<AccessibilityNodeInfo>()
        val textSnippets = mutableListOf<String>()
        val rect = Rect()

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 28 || snapshots.size >= 55) return
            if (node.isVisibleToUser) {
                val txt = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val cls = node.className?.toString().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString()?.trim().orEmpty() else ""
                val vid = node.viewIdResourceName?.substringAfterLast("/")?.trim().orEmpty()
                val isEdit = node.isEditable || cls.contains("EditText", true) || cls.contains("AutoComplete", true)
                val isClickable = node.isClickable || node.isCheckable

                val combinedLabel = listOf(txt, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()
                if (combinedLabel.isNotBlank() && textSnippets.size < 35) {
                    textSnippets.add(combinedLabel.take(80))
                }

                if (isClickable || isEdit) {
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 8 && rect.height() > 8) {
                        val idx = snapshots.size
                        snapshots.add(
                            UiNodeSnapshot(
                                index = idx,
                                label = combinedLabel.ifBlank { vid.ifBlank { cls.substringAfterLast(".") } }.take(64),
                                resourceId = vid.take(36),
                                isClickable = isClickable,
                                isEditable = isEdit,
                                centerX = rect.centerX(),
                                centerY = rect.centerY()
                            )
                        )
                        rawNodes.add(node)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                if (snapshots.size >= 55) break
                traverse(node.getChild(i), depth + 1)
            }
        }
        traverse(root, 0)
        lastCapturedNodes = rawNodes

        val formatted = buildString {
            appendLine("FOREGROUND_APP: $pkg")
            appendLine("INTERACTIVE_NODES:")
            snapshots.forEach { n ->
                val kind = if (n.isEditable) "INPUT" else "BTN"
                appendLine("[${n.index}] $kind label=\"${n.label}\" id=\"${n.resourceId}\" xy=(${n.centerX},${n.centerY})")
            }
            appendLine("SCREEN_TEXT: ${textSnippets.joinToString(" | ").take(1400)}")
        }
        return InteractiveScreenMap(pkg, textSnippets.joinToString(" | "), snapshots, formatted)
    }

    fun clickCapturedNode(index: Int): Boolean {
        val node = lastCapturedNodes.getOrNull(index) ?: return false
        if (clickNearestClickable(node)) return true
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.centerX() > 0 && rect.centerY() > 0) {
            return clickAtXY(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    fun typeIntoCapturedNode(index: Int, text: String, submitAfter: Boolean): Boolean {
        val node = lastCapturedNodes.getOrNull(index) ?: return false
        val ok = setTextOnNode(node, text)
        if (!ok) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.centerX() > 0 && rect.centerY() > 0) {
                clickAtXY(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }
        if (ok && submitAfter) {
            mainHandler.postDelayed({
                val clicked = clickActionButton(
                    keywords = listOf("send", "search", "post", "publish", "go", "submit", "পাঠান", "পোস্ট", "সার্চ"),
                    idHints = listOf("send", "send_btn", "search_go_btn", "search_button", "post", "submit")
                )
                if (!clicked) {
                    performImeEnter(findBestForegroundWindowRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node)
                }
            }, 380L)
        }
        return ok
    }
}
