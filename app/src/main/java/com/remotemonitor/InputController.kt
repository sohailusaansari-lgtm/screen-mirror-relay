package com.remotemonitor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class InputController : AccessibilityService() {

    companion object {
        @Volatile
        var instance: InputController? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun injectTap(x: Int, y: Int) {
        if (x < 0 || y < 0) return
        handler.post {
            try {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                    .build()
                dispatchGesture(gesture, null, null)
            } catch (_: Exception) {}
        }
    }

    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return
        val dur = if (durationMs > 0) durationMs else 300L
        handler.post {
            try {
                val path = Path().apply {
                    moveTo(x1.toFloat(), y1.toFloat())
                    lineTo(x2.toFloat(), y2.toFloat())
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
                    .build()
                dispatchGesture(gesture, null, null)
            } catch (_: Exception) {}
        }
    }

    fun injectKey(key: String) {
        handler.post {
            try {
                when (key.lowercase()) {
                    "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "recent" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "power_dialog" -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                    "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                }
            } catch (_: Exception) {}
        }
    }

    fun injectText(text: String) {
        if (text.isEmpty()) return
        handler.post {
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("remote_text", text)
                clipboard.setPrimaryClip(clip)

                val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    focused.recycle()
                    return@post
                }

                val root = rootInActiveWindow
                if (root != null) {
                    findTextFieldAndPaste(root)
                    root.recycle()
                }
            } catch (_: Exception) {}
        }
    }

    private fun findTextFieldAndPaste(node: AccessibilityNodeInfo): Boolean {
        try {
            if (node.isEditable && node.isFocused) {
                return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    if (findTextFieldAndPaste(child)) {
                        child.recycle()
                        return true
                    }
                    child.recycle()
                }
            }
        } catch (_: Exception) {}
        return false
    }
}
