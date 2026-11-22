package com.zeeai.companion.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ZeeAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        @Volatile
        @JvmField
        var instance: ZeeAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        fun getInstance(): ZeeAccessibilityService? = instance

        fun isConnected(): Boolean = _isConnected.value || instance != null

        /**
         * Xiaomi / HyperOS-এ স্ট্রিং ম্যাচিংয়ের বদলে প্যাকেজ নাম দিয়ে নির্ভরযোগ্য স্ট্যাটাস চেক
         */
        fun isServiceEnabled(context: Context): Boolean {
            if (_isConnected.value && instance != null) return true
            return runCatching {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
                val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                enabled.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            }.getOrDefault(false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-driven screen queries are pulled on demand
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _isConnected.value = false
        super.onDestroy()
    }

    fun readActiveScreenText(maxChars: Int = 3500): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        collectVisibleText(root, lines, maxChars)
        return lines.joinToString("\n").take(maxChars)
    }

    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable && current.isEnabled) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }

    fun clickNodeContainingText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(query)
        for (node in matches) {
            if (clickNode(node)) return true
        }
        return false
    }

    fun clickFirstMatching(vararg targets: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return searchAndClick(root, targets.map { it.lowercase().trim() })
    }

    private fun searchAndClick(node: AccessibilityNodeInfo?, targets: List<String>): Boolean {
        if (node == null || !node.isVisibleToUser) return false

        val txt = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        for (t in targets) {
            if (txt.contains(t) || desc.contains(t)) {
                if (clickNode(node)) return true
            }
        }

        for (i in 0 until node.childCount) {
            if (searchAndClick(node.getChild(i), targets)) return true
        }
        return false
    }

    fun scrollCurrentScreen(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        fun findAndScroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.isScrollable && node.performAction(action)) return true
            for (i in 0 until node.childCount) {
                if (findAndScroll(node.getChild(i))) return true
            }
            return false
        }
        return findAndScroll(root)
    }

    fun scrollActiveContainer(forward: Boolean = true): Boolean {
        return scrollCurrentScreen(down = forward)
    }

    fun typeTextIntoFocusedField(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun findFirstEditable(node: AccessibilityNodeInfo? = rootInActiveWindow): AccessibilityNodeInfo? {
        if (node == null || !node.isVisibleToUser) return null
        if (node.isEditable) return node
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true)) return node

        for (i in 0 until node.childCount) {
            val found = findFirstEditable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    fun setTextOnNode(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Finds active message input box on screen, types message, and clicks Send button.
     */
    fun autoTypeAndSend(messageText: String): Boolean {
        val editable = findFirstEditable() ?: return false
        val textSet = setTextOnNode(editable, messageText)
        if (!textSet) return false

        mainHandler.postDelayed({
            clickFirstMatching("send", "পাঠান", "পাঠাও")
        }, 350L)
        return true
    }

    /**
     * Polls and clicks the Send button after launching a chat URL or app.
     */
    fun autoClickSendAfterDelay(delayMs: Long = 900L) {
        mainHandler.postDelayed({
            val clicked = clickFirstMatching("send", "পাঠান", "পাঠাও")
            if (!clicked) {
                mainHandler.postDelayed({
                    clickFirstMatching("send", "পাঠান", "পাঠাও")
                }, 600L)
            }
        }, delayMs)
    }

    /**
     * Polls and clicks the Call/Dial button after opening dialer.
     */
    fun autoClickCallAfterDelay(delayMs: Long = 500L) {
        mainHandler.postDelayed({
            clickFirstMatching("call", "dial", "কল", "ডায়াল")
        }, delayMs)
    }

    /**
     * Automates opening story creator in social apps (Facebook, Instagram).
     */
    fun openStoryCreator(): Boolean {
        return clickFirstMatching(
            "create story",
            "add to story",
            "your story",
            "স্টোরি",
            "camera",
            "ক্যামেরা",
            "story"
        )
    }

    fun navigate(actionName: String): Boolean {
        return when (actionName.lowercase().trim()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings", "quicksettings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            "lock" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } else false
            "screenshot" -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            } else false
            else -> false
        }
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo?,
        output: LinkedHashSet<String>,
        maxChars: Int
    ) {
        if (node == null || !node.isVisibleToUser) return
        if (node.isPassword) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            output.add(text)
        } else {
            val desc = node.contentDescription?.toString()?.trim()
            if (!desc.isNullOrEmpty()) output.add(desc)
        }

        if (output.sumOf { it.length } >= maxChars) return

        for (i in 0 until node.childCount) {
            collectVisibleText(node.getChild(i), output, maxChars)
        }
    }
}
