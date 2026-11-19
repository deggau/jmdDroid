package com.jmd.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    private var zoomFactor = 1f
    private var currentBitmap: Bitmap? = null
    private val imageMatrix = Matrix()
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
            updateImageMatrix(resetZoom = false)
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
    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (currentBitmap == null) return false
                val nextZoom = (zoomFactor * detector.scaleFactor).coerceIn(1f, MAX_ZOOM)
                val appliedScale = nextZoom / zoomFactor
                zoomFactor = nextZoom
                if (zoomFactor <= 1.001f) {
                    zoomFactor = 1f
                    updateImageMatrix(resetZoom = true)
                } else {
                    imageMatrix.postScale(appliedScale, appliedScale, detector.focusX, detector.focusY)
                    constrainImageMatrix()
                }
                screenImage.imageMatrix = imageMatrix
                return true
            }
        })
    }
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (currentBitmap == null || zoomFactor <= 1f) return false
                imageMatrix.postTranslate(-distanceX, -distanceY)
                constrainImageMatrix()
                screenImage.imageMatrix = imageMatrix
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetZoom()
                return true
            }
        })
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
        header.addView(zoomControl("−", "Diminuir zoom") { zoomBy(1f / ZOOM_STEP) })
        header.addView(zoomControl("+", "Aumentar zoom") { zoomBy(ZOOM_STEP) })
        header.addView(zoomControl("Fit", "Ajustar à tela") { resetZoom() }, LinearLayout.LayoutParams(dp(42), dp(38)))
        header.addView(Button(this).apply {
            text = "Parar"
            isAllCaps = false
            setTextColor(Color.rgb(180, 243, 106))
            setOnClickListener { stopShareAndClose() }
        }, LinearLayout.LayoutParams(-2, dp(42)))
        root.addView(header)

        screenImage = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.MATRIX
            contentDescription = "Tela compartilhada do computador"
            adjustViewBounds = false
            setOnTouchListener { _, event ->
                scaleDetector.onTouchEvent(event)
                gestureDetector.onTouchEvent(event)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    constrainImageMatrix()
                    screenImage.imageMatrix = imageMatrix
                }
                true
            }
        }
        val imageArea = FrameLayout(this).apply {
            addView(screenImage, FrameLayout.LayoutParams(-1, -1))
            statusText = TextView(this@DesktopScreenViewerActivity).apply {
                text = "Conectando ao computador…"
                textSize = 14f
                setTextColor(Color.rgb(220, 222, 228))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(12), dp(24), dp(12))
                isClickable = false
                isFocusable = false
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
                            val keepViewport = old != null && old.width == bitmap.width && old.height == bitmap.height
                            currentBitmap = bitmap
                            screenImage.setImageBitmap(bitmap)
                            statusText.visibility = View.GONE
                            if (!keepViewport) updateImageMatrix(resetZoom = false)
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
        imageMatrix.reset()
        screenImage.imageMatrix = imageMatrix
        zoomFactor = 1f
        currentBitmap?.recycle()
        currentBitmap = null
        pendingFrame.set(null)
    }

    private fun zoomControl(
        symbol: String,
        description: String,
        params: LinearLayout.LayoutParams = LinearLayout.LayoutParams(dp(36), dp(38)).apply { marginEnd = dp(3) },
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = symbol
        contentDescription = description
        textSize = if (symbol == "Fit") 12f else 22f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        layoutParams = params
    }

    private fun zoomBy(factor: Float) {
        if (currentBitmap == null || screenImage.width == 0 || screenImage.height == 0) return
        val nextZoom = (zoomFactor * factor).coerceIn(1f, MAX_ZOOM)
        if (nextZoom <= 1.001f) {
            resetZoom()
            return
        }
        val scale = nextZoom / zoomFactor
        zoomFactor = nextZoom
        imageMatrix.postScale(scale, scale, screenImage.width / 2f, screenImage.height / 2f)
        constrainImageMatrix()
        screenImage.imageMatrix = imageMatrix
    }

    private fun resetZoom() {
        zoomFactor = 1f
        updateImageMatrix(resetZoom = true)
    }

    private fun updateImageMatrix(resetZoom: Boolean) {
        val bitmap = currentBitmap ?: return
        val viewWidth = screenImage.width
        val viewHeight = screenImage.height
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (resetZoom) zoomFactor = 1f
        val baseScale = minOf(viewWidth.toFloat() / bitmap.width, viewHeight.toFloat() / bitmap.height)
        val drawnWidth = bitmap.width * baseScale
        val drawnHeight = bitmap.height * baseScale
        imageMatrix.reset()
        imageMatrix.setScale(baseScale, baseScale)
        imageMatrix.postTranslate((viewWidth - drawnWidth) / 2f, (viewHeight - drawnHeight) / 2f)
        if (zoomFactor > 1f) imageMatrix.postScale(zoomFactor, zoomFactor, viewWidth / 2f, viewHeight / 2f)
        constrainImageMatrix()
        screenImage.imageMatrix = imageMatrix
    }

    private fun constrainImageMatrix() {
        val bitmap = currentBitmap ?: return
        val viewWidth = screenImage.width.toFloat()
        val viewHeight = screenImage.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val values = FloatArray(9)
        imageMatrix.getValues(values)
        val drawnWidth = bitmap.width * values[Matrix.MSCALE_X]
        val drawnHeight = bitmap.height * values[Matrix.MSCALE_Y]
        val translateX = values[Matrix.MTRANS_X]
        val translateY = values[Matrix.MTRANS_Y]
        val correctionX = when {
            drawnWidth <= viewWidth -> (viewWidth - drawnWidth) / 2f - translateX
            translateX > 0f -> -translateX
            translateX + drawnWidth < viewWidth -> viewWidth - (translateX + drawnWidth)
            else -> 0f
        }
        val correctionY = when {
            drawnHeight <= viewHeight -> (viewHeight - drawnHeight) / 2f - translateY
            translateY > 0f -> -translateY
            translateY + drawnHeight < viewHeight -> viewHeight - (translateY + drawnHeight)
            else -> 0f
        }
        imageMatrix.postTranslate(correctionX, correctionY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ZOOM_STEP = 1.25f
        const val MAX_ZOOM = 5f
    }
}
