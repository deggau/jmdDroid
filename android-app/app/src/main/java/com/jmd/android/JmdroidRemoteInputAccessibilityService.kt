package com.jmd.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import java.util.ArrayDeque

internal class JmdroidRemoteInputAccessibilityService : AccessibilityService() {
    private data class PendingGesture(val path: Path, val duration: Long, val onRejected: () -> Unit)
    private val gestureQueue = ArrayDeque<PendingGesture>()
    private var gestureInProgress = false

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun enqueue(path: Path, duration: Long, onRejected: () -> Unit) {
        if (gestureQueue.size >= MAX_QUEUED_GESTURES) {
            onRejected()
            return
        }
        gestureQueue.addLast(PendingGesture(path, duration, onRejected))
        dispatchNextGesture()
    }

    private fun dispatchNextGesture() {
        if (gestureInProgress || gestureQueue.isEmpty()) return
        if (Build.VERSION.SDK_INT < 24) {
            gestureQueue.removeFirst().onRejected()
            dispatchNextGesture()
            return
        }
        val pending = gestureQueue.removeFirst()
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(pending.path, 0, pending.duration.coerceIn(1L, 1000L)))
            .build()
        gestureInProgress = true
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                gestureInProgress = false
                dispatchNextGesture()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureInProgress = false
                pending.onRejected()
                dispatchNextGesture()
            }
        }, null)
        if (!accepted) {
            gestureInProgress = false
            pending.onRejected()
            dispatchNextGesture()
        }
    }

    companion object {
        @Volatile private var instance: JmdroidRemoteInputAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, JmdroidRemoteInputAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabled.isNullOrBlank()) return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) if (splitter.next().equals(expected, ignoreCase = true)) return true
            return false
        }

        private val mainHandler = Handler(Looper.getMainLooper())
        private const val MAX_QUEUED_GESTURES = 20

        private fun perform(path: Path, duration: Long, onRejected: () -> Unit) {
            mainHandler.post {
                val service = instance
                if (service == null) onRejected() else service.enqueue(path, duration, onRejected)
            }
        }

        fun performTap(x: Float, y: Float, onRejected: () -> Unit) {
            val path = Path().apply { moveTo(x, y) }
            perform(path, 80L, onRejected)
        }

        fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, onRejected: () -> Unit) {
            val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
            perform(path, 250L, onRejected)
        }
    }
}
