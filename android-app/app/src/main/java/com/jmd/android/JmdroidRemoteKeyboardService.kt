package com.jmd.android

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

internal class JmdroidRemoteKeyboardService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onCreateInputView(): View = TextView(this).apply {
        text = "Teclado remoto do JMDroid ativo"
        textSize = 14f
        gravity = android.view.Gravity.CENTER
        setPadding(12, 8, 12, 8)
        setBackgroundColor(android.graphics.Color.rgb(34, 37, 45))
        setTextColor(android.graphics.Color.WHITE)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun sendRemoteKey(keyCode: Int, action: Int, metaState: Int): Boolean {
        val connection = currentInputConnection ?: return false
        val now = SystemClock.uptimeMillis()
        return connection.sendKeyEvent(KeyEvent(
            now,
            now,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        ))
    }

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: JmdroidRemoteKeyboardService? = null

        fun dispatchRemoteKey(keyCode: Int, action: Int, metaState: Int, result: (Boolean) -> Unit) {
            mainHandler.post {
                result(instance?.sendRemoteKey(keyCode, action, metaState) == true)
            }
        }
    }
}
