package com.jmd.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ScreenShareRequest(
    val shareId: String,
    val maxFps: Int,
    val maxDimension: Int,
    val maxFrameBytes: Int,
    val quality: Int
)

internal class JmdroidConnectionService : Service() {
    private var apiClient: JmdroidApiClient? = null
    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private var shareRequest: ScreenShareRequest? = null
    private var lastShareId: String? = null
    @Volatile private var desktopScreenShareId: String? = null
    @Volatile private var desktopScreenShareStopping = false
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameNanos = 0L
    private val isCapturing = AtomicBoolean(false)
    @Volatile private var screenWidth = 0
    @Volatile private var screenHeight = 0
    private val reconnectRunnable = Runnable { connectSocket() }
    private var stoppingService = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopScreenShare(sendStopped = true, stopProjection = false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stoppingService = true
                mainHandler.removeCallbacks(reconnectRunnable)
                stopScreenShare(sendStopped = true)
                webSocket?.close(1000, "Desconectado pelo usuário")
                webSocket = null
                apiClient?.close()
                apiClient = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP_SHARE -> stopScreenShare(sendStopped = true)
            ACTION_VIEWER_READY -> sendDesktopViewerReady(
                intent.getStringExtra(EXTRA_SHARE_ID).orEmpty(),
                intent.getIntExtra(EXTRA_VIEWER_WIDTH, 0),
                intent.getIntExtra(EXTRA_VIEWER_HEIGHT, 0)
            )
            ACTION_VIEWER_STOP -> requestStopDesktopScreenShare(intent.getStringExtra(EXTRA_SHARE_ID).orEmpty())
            ACTION_RECONNECT -> reconnect()
            ACTION_SCREEN_SHARE_GRANTED -> {
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0)
                val requestId = intent.getStringExtra(EXTRA_SHARE_ID)
                if (data != null && requestId != null) startScreenShare(requestId, resultCode, data)
                else failPendingShare(requestId, "consentimento ausente")
            }
            ACTION_SCREEN_SHARE_DENIED -> failPendingShare(intent.getStringExtra(EXTRA_SHARE_ID), "permissão negada")
            ACTION_SCREEN_SHARE_CANCEL_PROMPT -> cancelSharePrompt()
            else -> connectSocket()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stoppingService = true
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScreenShare(sendStopped = true)
        webSocket?.close(1000, "Serviço encerrado")
        apiClient?.close()
        super.onDestroy()
    }

    private fun connectSocket() {
        val paired = JmdroidCredentialStore(this).load()
        if (paired == null) {
            stopSelf()
            return
        }
        startForegroundCompat(buildServiceNotification())
        webSocket?.cancel()
        apiClient?.close()
        val client = try { JmdroidApiClient(paired) } catch (error: Exception) {
            broadcastState(false, error.message ?: "Não foi possível conectar ao JMD.")
            scheduleReconnect()
            return
        }
        apiClient = client
        try {
            webSocket = client.connectSession(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (this@JmdroidConnectionService.webSocket !== webSocket) return
                    broadcastState(true, "Sessão ativa no JMD.")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (this@JmdroidConnectionService.webSocket !== webSocket) return
                    val message = try { JSONObject(text) } catch (_: Exception) { return }
                    when (message.optString("type")) {
                        "session.ready" -> broadcastState(true, "Sessão segura ativa no JMD.")
                        "screen_share.start" -> receiveShareRequest(message)
                        "screen_share.stop" -> receiveShareStop(message.optString("shareId"))
                        "screen_share.input" -> receiveScreenShareInput(message)
                        "desktop_screen_share.start" -> receiveDesktopScreenShareRequest(message)
                        "desktop_screen_share.stop" -> receiveDesktopScreenShareStop(message.optString("shareId"))
                        "desktop_screen_share.stopped" -> finishDesktopScreenShare(message.optString("shareId"))
                        "desktop_screen_share.error" -> finishDesktopScreenShare(
                            message.optString("shareId"), message.optString("message").ifBlank { "O JMD não conseguiu capturar a tela." }
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (this@JmdroidConnectionService.webSocket !== webSocket) return
                    if (desktopScreenShareId == null || desktopScreenShareStopping || bytes.size > DESKTOP_FRAME_MAX_BYTES) return
                    if (bytes.size < 4 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte() ||
                        bytes[bytes.size - 2] != 0xff.toByte() || bytes[bytes.size - 1] != 0xd9.toByte()) return
                    desktopFrameListener?.invoke(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (this@JmdroidConnectionService.webSocket !== webSocket) return
                    this@JmdroidConnectionService.webSocket = null
                    stopScreenShare(sendStopped = false)
                    finishDesktopScreenShare(desktopScreenShareId, "Conexão com o JMD interrompida.")
                    broadcastState(false, "Sessão do JMD encerrada.")
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    if (this@JmdroidConnectionService.webSocket !== webSocket) return
                    this@JmdroidConnectionService.webSocket = null
                    stopScreenShare(sendStopped = false)
                    finishDesktopScreenShare(desktopScreenShareId, "Conexão com o JMD interrompida.")
                    if (response?.code == 401 || response?.code == 403) {
                        val store = JmdroidCredentialStore(this@JmdroidConnectionService)
                        store.load()?.let(store::remove)
                        if (store.active() != null) {
                            broadcastState(false, "Pareamento expirado neste computador. Alternando para outro computador salvo.")
                            scheduleReconnect()
                        } else {
                            broadcastState(false, "Pareamento expirado. Conecte novamente ao JMD.")
                            stopSelf()
                        }
                    } else {
                        broadcastState(false, error.message ?: "Conexão com o JMD interrompida.")
                        scheduleReconnect()
                    }
                }
            })
        } catch (error: Exception) {
            broadcastState(false, error.message ?: "Não foi possível conectar ao JMD.")
            scheduleReconnect()
        }
    }

    private fun reconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        stopScreenShare(sendStopped = true)
        finishDesktopScreenShare(desktopScreenShareId, "Computador ativo alterado.")
        webSocket?.close(1000, "Reconectando")
        webSocket = null
        apiClient?.close()
        apiClient = null
        connectSocket()
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS)
    }

    private fun receiveShareRequest(message: JSONObject) {
        val request = ScreenShareRequest(
            shareId = message.optString("shareId"),
            maxFps = message.optInt("maxFps", 12).coerceIn(12, 60),
            maxDimension = message.optInt("maxDimension", 2400).coerceIn(320, 2400),
            maxFrameBytes = message.optInt("maxFrameBytes", 2 * 1024 * 1024).coerceIn(64 * 1024, 2 * 1024 * 1024),
            quality = message.optInt("quality", 65).coerceIn(0, 100)
        )
        if (request.shareId.isBlank() || lastShareId == request.shareId) return
        val active = shareRequest
        if (active?.shareId == request.shareId) return
        if (active != null) {
            sendControl("screen_share.error", request.shareId, "outra transmissão já está ativa")
            return
        }
        shareRequest = request
        lastShareId = request.shareId
        broadcastShareRequest(request)
        showShareRequestNotification(request)
    }

    private fun receiveShareStop(shareId: String) {
        if (shareRequest?.shareId != shareId) return
        notificationManager().cancel(SHARE_NOTIFICATION_ID)
        stopScreenShare(sendStopped = true)
    }

    private fun receiveScreenShareInput(message: JSONObject) {
        val shareId = message.optString("shareId")
        if (shareId.isBlank() || shareRequest?.shareId != shareId || !isCapturing.get()) return
        val x = message.optDouble("x", Double.NaN)
        val y = message.optDouble("y", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return
        if (!JmdroidRemoteInputAccessibilityService.isEnabled(this)) {
            sendControl("screen_share.input_error", shareId, "Ative o controle remoto nas configurações de Acessibilidade do Android.")
            return
        }
        val width = screenWidth
        val height = screenHeight
        if (width <= 0 || height <= 0) return
        // The desktop sends the dimensions of the exact JPEG it displayed. Map its
        // rounded frame pixels to the physical MediaProjection surface, not dp values.
        val frameWidth = if (message.has("frameWidth")) message.optInt("frameWidth", 0) else width
        val frameHeight = if (message.has("frameHeight")) message.optInt("frameHeight", 0) else height
        if (frameWidth !in 1..2400 || frameHeight !in 1..2400) return
        val point = mapFramePoint(x, y, frameWidth, frameHeight, width, height) ?: return
        val pointX = point.first
        val pointY = point.second
        val onRejected = {
            if (shareRequest?.shareId == shareId && isCapturing.get()) {
                sendControl("screen_share.input_error", shareId, "Não foi possível executar o gesto. Verifique a Acessibilidade do Android.")
            }
        }
        when (message.optString("action")) {
            "tap" -> JmdroidRemoteInputAccessibilityService.performTap(pointX, pointY, onRejected)
            "drag" -> {
                val endX = message.optDouble("endX", Double.NaN)
                val endY = message.optDouble("endY", Double.NaN)
                if (!endX.isFinite() || !endY.isFinite() || endX !in 0.0..1.0 || endY !in 0.0..1.0) return
                val end = mapFramePoint(endX, endY, frameWidth, frameHeight, width, height) ?: return
                val duration = message.optInt("durationMilliseconds", 420).coerceIn(50, 5000)
                JmdroidRemoteInputAccessibilityService.performSwipe(pointX, pointY, end.first, end.second, duration.toLong(), onRejected)
            }
            "scroll" -> {
                val dx = message.optDouble("deltaX", 0.0)
                val dy = message.optDouble("deltaY", 0.0)
                if (!dx.isFinite() || !dy.isFinite() || (dx == 0.0 && dy == 0.0)) return
                val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                val dominant = if (horizontal) dx else dy
                val axisSize = if (horizontal) width.toFloat() else height.toFloat()
                if (axisSize <= 1f) return
                val wheelSteps = (kotlin.math.abs(dominant) / 120.0).coerceAtMost(5.0)
                val density = resources.displayMetrics.density
                val distance = (density * 90f * wheelSteps.toFloat()).coerceAtMost((axisSize - 1f) * 0.45f).coerceAtLeast(1f)
                val direction = if (horizontal) { if (dx > 0) -1f else 1f } else { if (dy > 0) 1f else -1f }
                var startX = pointX
                var startY = pointY
                var endX = pointX
                var endY = pointY
                if (horizontal) {
                    startX = if (direction > 0) (pointX - distance / 2f).coerceIn(0f, width - 1f - distance)
                    else (pointX + distance / 2f).coerceIn(distance, width - 1f)
                    endX = startX + direction * distance
                } else {
                    startY = if (direction > 0) (pointY - distance / 2f).coerceIn(0f, height - 1f - distance)
                    else (pointY + distance / 2f).coerceIn(distance, height - 1f)
                    endY = startY + direction * distance
                }
                val duration = (150.0 + (wheelSteps - 1.0).coerceAtLeast(0.0) * 70.0).toLong().coerceIn(120L, 500L)
                JmdroidRemoteInputAccessibilityService.performSwipe(startX, startY, endX, endY, duration, onRejected)
            }
            else -> return
        }
    }

    private fun mapFramePoint(
        x: Double,
        y: Double,
        frameWidth: Int,
        frameHeight: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Pair<Float, Float>? {
        if (frameWidth <= 0 || frameHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return null
        val frameX = (x * (frameWidth - 1)).roundToInt()
        val frameY = (y * (frameHeight - 1)).roundToInt()
        val screenX = if (frameWidth == 1) 0f else frameX.toFloat() * (screenWidth - 1f) / (frameWidth - 1f)
        val screenY = if (frameHeight == 1) 0f else frameY.toFloat() * (screenHeight - 1f) / (frameHeight - 1f)
        return screenX.coerceIn(0f, screenWidth - 1f) to screenY.coerceIn(0f, screenHeight - 1f)
    }

    private fun receiveDesktopScreenShareRequest(message: JSONObject) {
        val shareId = message.optString("shareId")
        if (shareId.isBlank()) return
        val activeId = desktopScreenShareId
        if (activeId != null && activeId != shareId) {
            sendControl("desktop_screen_share.error", shareId, "outra visualização está ativa")
            return
        }
        desktopScreenShareId = shareId
        desktopScreenShareStopping = false
        broadcastDesktopScreenShareRequest(shareId)
        showDesktopScreenShareNotification(shareId)
    }

    private fun receiveDesktopScreenShareStop(shareId: String) {
        if (desktopScreenShareId != shareId) return
        sendControl("desktop_screen_share.stopped", shareId)
        finishDesktopScreenShare(shareId)
    }

    private fun showDesktopScreenShareNotification(shareId: String) {
        val intent = Intent(this, DesktopScreenViewerActivity::class.java).apply {
            action = ACTION_OPEN_DESKTOP_SCREEN_VIEWER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHARE_ID, shareId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, DESKTOP_SHARE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jmd)
            .setContentTitle("Ver tela do computador")
            .setContentText("Toque para abrir o visualizador do JMD.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(this).notify(DESKTOP_SHARE_NOTIFICATION_ID, notification) }
        catch (_: SecurityException) { }
    }

    private fun broadcastDesktopScreenShareRequest(shareId: String) {
        sendBroadcast(Intent(ACTION_DESKTOP_SCREEN_SHARE_REQUEST).setPackage(packageName)
            .putExtra(EXTRA_SHARE_ID, shareId))
    }

    private fun broadcastDesktopScreenShareEnded(shareId: String?, message: String? = null) {
        if (shareId == null) return
        sendBroadcast(Intent(ACTION_DESKTOP_SCREEN_SHARE_ENDED).setPackage(packageName)
            .putExtra(EXTRA_SHARE_ID, shareId).putExtra(EXTRA_MESSAGE, message.orEmpty()))
    }

    private fun finishDesktopScreenShare(shareId: String?, message: String? = null) {
        if (shareId == null || desktopScreenShareId != shareId) return
        desktopScreenShareId = null
        desktopScreenShareStopping = false
        desktopFrameListener = null
        notificationManager().cancel(DESKTOP_SHARE_NOTIFICATION_ID)
        broadcastDesktopScreenShareEnded(shareId, message)
    }

    private fun sendDesktopViewerReady(shareId: String, width: Int, height: Int) {
        if (desktopScreenShareId != shareId || width <= 0 || height <= 0) return
        desktopScreenShareStopping = false
        notificationManager().cancel(DESKTOP_SHARE_NOTIFICATION_ID)
        sendControl("desktop_screen_share.ready", shareId, width = width, height = height)
    }

    private fun requestStopDesktopScreenShare(shareId: String) {
        if (desktopScreenShareId != shareId || desktopScreenShareStopping) return
        desktopScreenShareStopping = true
        sendControl("desktop_screen_share.stop", shareId)
        notificationManager().cancel(DESKTOP_SHARE_NOTIFICATION_ID)
    }

    private fun showShareRequestNotification(request: ScreenShareRequest) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SCREEN_SHARE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SHARE_ID, request.shareId)
            putExtra(EXTRA_MAX_FPS, request.maxFps)
            putExtra(EXTRA_MAX_DIMENSION, request.maxDimension)
            putExtra(EXTRA_MAX_FRAME_BYTES, request.maxFrameBytes)
            putExtra(EXTRA_QUALITY, request.quality)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, SHARE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SHARE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jmd)
            .setContentTitle("O JMD solicitou compartilhar a tela")
            .setContentText("Toque em Aceitar para abrir a autorização de captura do Android.")
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_stat_jmd, "Aceitar", pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try { NotificationManagerCompat.from(this).notify(SHARE_NOTIFICATION_ID, notification) }
        catch (_: SecurityException) { }
    }

    private fun startScreenShare(requestId: String, resultCode: Int, resultData: Intent) {
        val request = shareRequest?.takeIf { it.shareId == requestId } ?: return
        if (isCapturing.get()) return
        try {
            startForegroundCompat(buildServiceNotification(sharing = true), includeMediaProjection = true)
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection indisponível")
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)

            val metrics = resources.displayMetrics
            val captureWidth = metrics.widthPixels.coerceAtLeast(1)
            val captureHeight = metrics.heightPixels.coerceAtLeast(1)
            screenWidth = captureWidth
            screenHeight = captureHeight
            val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            val thread = HandlerThread("jmd-screen-capture").apply { start() }
            captureThread = thread
            val handler = Handler(thread.looper)
            captureHandler = handler
            lastFrameNanos = 0L
            isCapturing.set(true)
            reader.setOnImageAvailableListener({ source -> captureLatestFrame(source, request) }, handler)
            virtualDisplay = projection.createVirtualDisplay(
                "JMDroid Screen Share",
                captureWidth,
                captureHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            ) ?: throw IllegalStateException("Não foi possível criar a tela virtual")
            notificationManager().cancel(SHARE_NOTIFICATION_ID)
            val rotation = try {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                when (windowManager.defaultDisplay.rotation) {
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            } catch (_: Exception) { 0 }
            val dimensionRatio = request.maxDimension.toFloat() / max(captureWidth, captureHeight)
            val scale = dimensionRatio.coerceAtMost(1f)
            val frameWidth = (captureWidth * scale).toInt().coerceAtLeast(1)
            val frameHeight = (captureHeight * scale).toInt().coerceAtLeast(1)
            sendControl("screen_share.started", request.shareId, width = frameWidth, height = frameHeight, rotation = rotation)
        } catch (error: Exception) {
            isCapturing.set(false)
            sendControl("screen_share.error", request.shareId, error.message ?: "não foi possível iniciar")
            stopScreenShare(sendStopped = false)
        }
    }

    private fun captureLatestFrame(source: ImageReader, request: ScreenShareRequest) {
        val image = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        image.use { frame ->
            if (!isCapturing.get() || shareRequest?.shareId != request.shareId) return
            // ImageReader reflects the actual captured content rectangle and any
            // projection resize; input coordinates are mapped back to these pixels.
            screenWidth = frame.width
            screenHeight = frame.height
            val now = SystemClock.elapsedRealtimeNanos()
            val interval = 1_000_000_000L / request.maxFps
            if (now - lastFrameNanos < interval) return
            lastFrameNanos = now
            val plane = frame.planes.firstOrNull() ?: return
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * frame.width
            val paddedWidth = frame.width + rowPadding / pixelStride
            var bitmap: Bitmap? = null
            var cropped: Bitmap? = null
            try {
                bitmap = Bitmap.createBitmap(paddedWidth, frame.height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(plane.buffer)
                cropped = if (paddedWidth == frame.width) bitmap else Bitmap.createBitmap(bitmap, 0, 0, frame.width, frame.height)
                var outBitmap = cropped
                if (max(outBitmap.width, outBitmap.height) > request.maxDimension) {
                    val ratio = request.maxDimension.toFloat() / max(outBitmap.width, outBitmap.height)
                    outBitmap = Bitmap.createScaledBitmap(outBitmap,
                        (outBitmap.width * ratio).toInt().coerceAtLeast(1),
                        (outBitmap.height * ratio).toInt().coerceAtLeast(1), true)
                }
                val jpeg = encodeJpegWithinLimit(outBitmap, request)
                if (jpeg != null) {
                    val socket = webSocket
                    if (socket == null) {
                        stopScreenShare(sendStopped = false)
                    } else if (socket.queueSize() <= request.maxFrameBytes && !socket.send(ByteString.of(*jpeg))) {
                        stopScreenShare(sendStopped = false)
                    }
                }
                if (outBitmap !== cropped && outBitmap !== bitmap) outBitmap.recycle()
            } catch (_: Exception) {
                // Drop a bad frame; the next ImageReader callback will provide a newer one.
            } finally {
                if (cropped !== bitmap) cropped?.recycle()
                bitmap?.recycle()
            }
        }
    }

    private fun encodeJpegWithinLimit(source: Bitmap, request: ScreenShareRequest): ByteArray? {
        var bitmap = source
        var ownedBitmap: Bitmap? = null
        var quality = request.quality
        try {
            repeat(8) {
                val output = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                val data = output.toByteArray()
                if (data.size <= request.maxFrameBytes) return data
                if (quality > 35) quality = (quality - 10).coerceAtLeast(35)
                else {
                    val resized = Bitmap.createScaledBitmap(bitmap,
                        (bitmap.width * 0.85f).toInt().coerceAtLeast(1),
                        (bitmap.height * 0.85f).toInt().coerceAtLeast(1), true)
                    if (bitmap !== source) ownedBitmap?.recycle()
                    ownedBitmap = resized
                    bitmap = resized
                    quality = request.quality.coerceAtMost(55)
                }
            }
            return null
        } finally {
            ownedBitmap?.recycle()
        }
    }

    private fun failPendingShare(shareId: String?, reason: String) {
        val request = shareRequest ?: return
        if (shareId != null && request.shareId != shareId) return
        notificationManager().cancel(SHARE_NOTIFICATION_ID)
        sendControl("screen_share.error", request.shareId, reason)
        shareRequest = null
    }

    private fun cancelSharePrompt() {
        val request = shareRequest ?: return
        notificationManager().cancel(SHARE_NOTIFICATION_ID)
        sendControl("screen_share.error", request.shareId, "solicitação cancelada")
        shareRequest = null
    }

    private fun stopScreenShare(sendStopped: Boolean, stopProjection: Boolean = true) {
        val request = shareRequest
        shareRequest = null
        isCapturing.set(false)
        screenWidth = 0
        screenHeight = 0
        if (sendStopped && request != null) sendControl("screen_share.stopped", request.shareId)
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) { }
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        val projection = mediaProjection
        mediaProjection = null
        if (projection != null) {
            try { projection.unregisterCallback(projectionCallback) } catch (_: Exception) { }
            if (stopProjection) try { projection.stop() } catch (_: Exception) { }
        }
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        notificationManager().cancel(SHARE_NOTIFICATION_ID)
        if (!stoppingService) startForegroundCompat(buildServiceNotification())
    }

    private fun sendControl(type: String, shareId: String, message: String? = null, width: Int? = null, height: Int? = null, rotation: Int? = null) {
        val json = JSONObject().put("type", type).put("shareId", shareId)
        if (message != null) json.put("message", message.take(200))
        if (width != null) json.put("width", width)
        if (height != null) json.put("height", height)
        if (rotation != null) json.put("rotation", rotation)
        if (type == "screen_share.started") json.put("format", "jpeg")
        webSocket?.send(json.toString())
    }

    private fun broadcastState(connected: Boolean, message: String) {
        sendBroadcast(Intent(ACTION_CONNECTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_CONNECTED, connected).putExtra(EXTRA_MESSAGE, message))
    }

    private fun broadcastShareRequest(request: ScreenShareRequest) {
        sendBroadcast(Intent(ACTION_SCREEN_SHARE_REQUEST).setPackage(packageName)
            .putExtra(EXTRA_SHARE_ID, request.shareId)
            .putExtra(EXTRA_MAX_FPS, request.maxFps)
            .putExtra(EXTRA_MAX_DIMENSION, request.maxDimension)
            .putExtra(EXTRA_MAX_FRAME_BYTES, request.maxFrameBytes)
            .putExtra(EXTRA_QUALITY, request.quality))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = notificationManager()
        manager.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL_ID, "Conexão com o JMD", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(SHARE_CHANNEL_ID, "Pedidos de compartilhamento", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildServiceNotification(sharing: Boolean = false): Notification {
        val computerName = JmdroidCredentialStore(this).load()?.computerName?.takeIf { it.isNotBlank() } ?: "computador"
        val notification = NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jmd)
            .setContentTitle(if (sharing) "Compartilhando tela com o JMD" else "Conectado ao JMD")
            .setContentText(if (sharing) "A captura está ativa. Toque na notificação do Android para interrompê-la." else "Conectado a \"$computerName\"")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (sharing) notification.addAction(0, "Parar", servicePendingIntent(ACTION_STOP_SHARE))
        else notification.addAction(0, "Desconectar", servicePendingIntent(ACTION_STOP_SERVICE))
        return notification.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, JmdroidConnectionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun startForegroundCompat(notification: Notification, includeMediaProjection: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 29) {
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (includeMediaProjection) type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(SERVICE_NOTIFICATION_ID, notification, type)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ACTION_CONNECT = "com.jmd.android.action.CONNECT"
        const val ACTION_RECONNECT = "com.jmd.android.action.RECONNECT"
        const val ACTION_STOP_SERVICE = "com.jmd.android.action.STOP_SERVICE"
        const val ACTION_CONNECTION_STATE = "com.jmd.android.action.CONNECTION_STATE"
        const val ACTION_SCREEN_SHARE_REQUEST = "com.jmd.android.action.SCREEN_SHARE_REQUEST"
        const val ACTION_OPEN_SCREEN_SHARE = "com.jmd.android.action.OPEN_SCREEN_SHARE"
        const val ACTION_SCREEN_SHARE_GRANTED = "com.jmd.android.action.SCREEN_SHARE_GRANTED"
        const val ACTION_SCREEN_SHARE_DENIED = "com.jmd.android.action.SCREEN_SHARE_DENIED"
        const val ACTION_SCREEN_SHARE_CANCEL_PROMPT = "com.jmd.android.action.SCREEN_SHARE_CANCEL_PROMPT"
        const val ACTION_STOP_SHARE = "com.jmd.android.action.STOP_SHARE"
        const val ACTION_DESKTOP_SCREEN_SHARE_REQUEST = "com.jmd.android.action.DESKTOP_SCREEN_SHARE_REQUEST"
        const val ACTION_DESKTOP_SCREEN_SHARE_ENDED = "com.jmd.android.action.DESKTOP_SCREEN_SHARE_ENDED"
        const val ACTION_OPEN_DESKTOP_SCREEN_VIEWER = "com.jmd.android.action.OPEN_DESKTOP_SCREEN_VIEWER"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SHARE_ID = "shareId"
        const val EXTRA_MAX_FPS = "maxFps"
        const val EXTRA_MAX_DIMENSION = "maxDimension"
        const val EXTRA_MAX_FRAME_BYTES = "maxFrameBytes"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_PROJECTION_RESULT = "projectionResult"
        const val EXTRA_PROJECTION_DATA = "projectionData"
        const val ACTION_VIEWER_READY = "com.jmd.android.action.DESKTOP_VIEWER_READY"
        const val ACTION_VIEWER_STOP = "com.jmd.android.action.DESKTOP_VIEWER_STOP"
        private const val EXTRA_VIEWER_WIDTH = "viewerWidth"
        private const val EXTRA_VIEWER_HEIGHT = "viewerHeight"
        private const val SERVICE_CHANNEL_ID = "jmdroid_connection_service"
        private const val SHARE_CHANNEL_ID = "jmdroid_screen_share_request"
        private const val SERVICE_NOTIFICATION_ID = 4301
        private const val SHARE_NOTIFICATION_ID = 4302
        private const val DESKTOP_SHARE_NOTIFICATION_ID = 4303
        private const val DESKTOP_FRAME_MAX_BYTES = 512 * 1024
        private const val RECONNECT_DELAY_MS = 5_000L

        @Volatile private var desktopFrameListener: ((ByteArray) -> Unit)? = null

        fun setDesktopFrameListener(listener: ((ByteArray) -> Unit)?) {
            desktopFrameListener = listener
        }

        fun sendDesktopViewerReady(context: Context, shareId: String, width: Int, height: Int) {
            context.startService(Intent(context, JmdroidConnectionService::class.java)
                .setAction(ACTION_VIEWER_READY).putExtra(EXTRA_SHARE_ID, shareId)
                .putExtra(EXTRA_VIEWER_WIDTH, width).putExtra(EXTRA_VIEWER_HEIGHT, height))
        }

        fun stopDesktopScreenShare(context: Context, shareId: String) {
            context.startService(Intent(context, JmdroidConnectionService::class.java)
                .setAction(ACTION_VIEWER_STOP).putExtra(EXTRA_SHARE_ID, shareId))
        }

        fun connect(context: Context, reconnect: Boolean = false) {
            val intent = Intent(context, JmdroidConnectionService::class.java)
                .setAction(if (reconnect) ACTION_RECONNECT else ACTION_CONNECT)
            if (Build.VERSION.SDK_INT >= 26) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JmdroidConnectionService::class.java).setAction(ACTION_STOP_SERVICE))
        }

        fun grantProjection(context: Context, requestId: String, resultCode: Int, data: Intent) {
            context.startService(Intent(context, JmdroidConnectionService::class.java)
                .setAction(ACTION_SCREEN_SHARE_GRANTED)
                .putExtra(EXTRA_SHARE_ID, requestId)
                .putExtra(EXTRA_PROJECTION_RESULT, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data))
        }

        fun denyProjection(context: Context, requestId: String) {
            context.startService(Intent(context, JmdroidConnectionService::class.java)
                .setAction(ACTION_SCREEN_SHARE_DENIED).putExtra(EXTRA_SHARE_ID, requestId))
        }

        fun cancelPrompt(context: Context) {
            context.startService(Intent(context, JmdroidConnectionService::class.java)
                .setAction(ACTION_SCREEN_SHARE_CANCEL_PROMPT))
        }
    }
}
