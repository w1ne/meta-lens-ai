package com.metalens.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Auto-taps the "voice mode" button inside the ChatGPT Android app whenever
 * the app is foregrounded. This is what makes wake-word → Advanced Voice Mode
 * actually hands-free: we launch the app, the service clicks Voice, and
 * Android routes audio to whatever BT headset is active (glasses).
 *
 * OpenAI doesn't expose a deep-link for Advanced Voice Mode, so accessibility
 * is the only option without root. If OpenAI renames/redesigns the button,
 * the match heuristics below will need updating.
 */
class ChatGPTAccessibilityService : AccessibilityService() {

    private var lastTapAtMs: Long = 0

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 80
            packageNames = arrayOf(CHATGPT_PKG)
        }
        serviceInfo = info
        Log.i(TAG, "Service connected, watching $CHATGPT_PKG")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != CHATGPT_PKG) return

        val now = System.currentTimeMillis()
        if (now - lastTapAtMs < DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        val voiceNode = findVoiceButton(root) ?: return

        if (voiceNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastTapAtMs = now
            Log.i(TAG, "Tapped voice-mode button")
        } else {
            Log.w(TAG, "Voice-mode button found but ACTION_CLICK returned false")
        }
    }

    override fun onInterrupt() {}

    /**
     * Looks for the voice-mode button. ChatGPT's UI marks the button with a
     * content-description containing "voice" (English) or similar. We walk the
     * tree and match on contentDescription or viewId heuristics.
     */
    private fun findVoiceButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Primary: content-description match (case-insensitive contains "voice")
        for (keyword in VOICE_DESC_KEYWORDS) {
            val hits = root.findAccessibilityNodeInfosByText(keyword)
            for (node in hits) {
                if (node != null && node.isClickable && node.isVisibleToUser) return node
            }
        }

        // Fallback: walk all nodes and inspect contentDescription
        return walkForVoice(root)
    }

    private fun walkForVoice(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        node ?: return null
        val cd = node.contentDescription?.toString()?.lowercase() ?: ""
        if (cd.isNotEmpty() && VOICE_DESC_KEYWORDS.any { cd.contains(it.lowercase()) }) {
            if (node.isClickable && node.isVisibleToUser) return node
            // Bubble up to the first clickable ancestor
            var cur: AccessibilityNodeInfo? = node.parent
            while (cur != null) {
                if (cur.isClickable) return cur
                cur = cur.parent
            }
        }
        for (i in 0 until node.childCount) {
            val hit = walkForVoice(node.getChild(i))
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        private const val TAG = "ChatGPTAxe"
        private const val CHATGPT_PKG = "com.openai.chatgpt"
        private const val DEBOUNCE_MS = 3000L

        private val VOICE_DESC_KEYWORDS = listOf(
            "Voice mode",
            "Start voice",
            "voice mode",
            "Voice chat",
            "Voice",
        )
    }
}
