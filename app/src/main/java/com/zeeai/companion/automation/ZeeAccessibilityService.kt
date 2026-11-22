package com.zeeai.companion.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow

class ZeeAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
        isConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event-driven screen queries are pulled on demand via readActiveScreenText()
        // to keep background CPU and battery consumption minimal.
    }

    override fun onInterrupt() {
        // No continuous foreground loop to interrupt.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        activeInstance = null
        isConnected.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        activeInstance = null
        isConnected.value = false
        super.onDestroy()
    }

    fun readActiveScreenText(maxChars: Int = 3500): String {
        val root = rootInActiveWindow ?: return ""
        val lines = LinkedHashSet<String>()
        collectVisibleText(root, lines, maxChars)
        return lines.joinToString("\n").take(maxChars)
    }

    fun clickNodeContainingText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(query)
        for (node in matches) {
            var current: AccessibilityNodeInfo? = node
            while (current != null) {
                if (current.isClickable && current.isEnabled) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                current = current.parent
            }
        }
        return false
    }

    fun scrollActiveContainer(forward: Boolean = true): Boolean {
        val root = rootInActiveWindow ?: return false
        val scrollable = findFirstScrollable(root) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return scrollable.performAction(action)
    }

    fun navigate(actionName: String): Boolean {
        return when (actionName.lowercase()) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            else -> false
        }
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo?,
        output: LinkedHashSet<String>,
        maxChars: Int
    ) {
        if (node == null || !node.isVisibleToUser) return
        // Privacy guardrail: never extract text from password inputs
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

    private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val found = findFirstScrollable(node.getChild(i))
            if (found != null) return found
        }
        return null
    }

    companion object {
        @Volatile
        private var activeInstance: ZeeAccessibilityService? = null

        val isConnected = MutableStateFlow(false)

        fun getInstance(): ZeeAccessibilityService? = activeInstance

        fun isConnected(): Boolean = isConnected.value || activeInstance != null

        fun isServiceEnabled(context: Context): Boolean {
            if (isConnected.value || activeInstance != null) return true
            return runCatching {
                val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                enabledServices.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName }
            }.getOrDefault(false)
        }
    }
}
