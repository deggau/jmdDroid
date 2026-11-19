package com.jmd.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DesktopScreenViewerActivity : Activity() {
    private lateinit var screenImage: ImageView
    private lateinit var statusText: TextView
    private var shareId: String? = null
    private var lastReadyWidth = 0
    private var lastReadyHeight = 0
    private var currentBitmap: Bitmap? = null
    private val pendingFrame = AtomicReference<ByteArray?>(null)
    private val decodingFrame = AtomicBoolean(false)
    private val decoder = Executors.newSingleThreadExecutor()
    private val frameLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val id = shareId ?: return@OnGlobalLayoutListener
        val width = screenImage.width
        val height = screenImage.height
        if (width > 0 && height > 0 && (width != lastReadyWidth || height != lastReadyHeight)) {
            lastReadyWidth = width
            lastReadyHeight = height
            JmdroidConnectionService.sendDesktopViewerReady(this, id, width, height)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != JmdroidConnectionService.ACTION_DESKTOP_SCREEN_SHARE_ENDED) return
            if (intent.getStringExtra(JmdroidConnectionService.EXTRA_SHARE_ID) != shareId) return
            val message = intent.getStringExtra(JmdroidConnectionService.EXTRA_MESSAGE).orEmpty()
            if (message.isNotBlank()) statusText.text = message
            shareId = null
            clearFrame()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(15, 16, 19)
        window.navigationBarColor = Color.rgb(15, 16, 19)
        shareId = intent.getStringExtra(JmdroidConnectionService.EXTRA_SHARE_ID)
        buildViewer()
        if (shareId.isNullOrBlank()) {
            statusText.text = "Nenhuma solicitação de compartilhamento ativa."
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(JmdroidConnectionService.ACTION_DESKTOP_SCREEN_SHARE_ENDED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        JmdroidConnectionService.setDesktopFrameListener(::acceptFrame)
        screenImage.viewTreeObserver.addOnGlobalLayoutListener(frameLayoutListener)
        screenImage.post { frameLayoutListener.onGlobalLayout() }
    }

    override fun onStop() {
        if (screenImage.viewTreeObserver.isAlive) {
            screenImage.viewTreeObserver.removeOnGlobalLayoutListener(frameLayoutListener)
        }
        JmdroidConnectionService.setDesktopFrameListener(null)
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shareId = intent.getStringExtra(JmdroidConnectionService.EXTRA_SHARE_ID)
        lastReadyWidth = 0
        lastReadyHeight = 0
        screenImage.post { frameLayoutListener.onGlobalLayout() }
    }

    @Deprecated("Use the system back dispatcher")
    override fun onBackPressed() {
        stopShareAndClose()
    }

    override fun onDestroy() {
        pendingFrame.set(null)
        decoder.shutdownNow()
        clearFrame()
        super.onDestroy()
    }

    private fun buildViewer() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 16, 19))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(12), dp(8))
        }
        val title = TextView(this).apply {
            text = "Tela do computador"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply {
            text = "Parar"
            isAllCaps = false
            setTextColor(Color.rgb(180, 243, 106))
            setOnClickListener { stopShareAndClose() }
        })
        root.addView(header)

        screenImage = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Tela compartilhada do computador"
            adjustViewBounds = false
        }
        val imageArea = FrameLayout(this).apply {
            addView(screenImage, FrameLayout.LayoutParams(-1, -1))
            statusText = TextView(this@DesktopScreenViewerActivity).apply {
                text = "Conectando ao computador…"
                textSize = 14f
                setTextColor(Color.rgb(220, 222, 228))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(12), dp(24), dp(12))
            }
            addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        root.addView(imageArea, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun acceptFrame(bytes: ByteArray) {
        pendingFrame.set(bytes)
        startFrameDecoder()
    }

    private fun startFrameDecoder() {
        if (!decodingFrame.compareAndSet(false, true)) return
        decoder.execute {
            try {
                while (true) {
                    val latest = pendingFrame.getAndSet(null) ?: break
                    val bitmap = BitmapFactory.decodeByteArray(latest, 0, latest.size) ?: continue
                    runOnUiThread {
                        if (!isFinishing && shareId != null) {
                            val old = currentBitmap
                            currentBitmap = bitmap
                            screenImage.setImageBitmap(bitmap)
                            statusText.visibility = View.GONE
                            old?.recycle()
                        } else bitmap.recycle()
                    }
                }
            } finally {
                decodingFrame.set(false)
                if (pendingFrame.get() != null) startFrameDecoder()
            }
        }
    }

    private fun stopShareAndClose() {
        val id = shareId
        shareId = null
        if (id != null) JmdroidConnectionService.stopDesktopScreenShare(this, id)
        clearFrame()
        finish()
    }

    private fun clearFrame() {
        screenImage.setImageDrawable(null)
        currentBitmap?.recycle()
        currentBitmap = null
        pendingFrame.set(null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
