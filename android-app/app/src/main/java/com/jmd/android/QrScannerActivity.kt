package com.jmd.android

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal const val QR_SCAN_RESULT = "com.jmd.android.QR_SCAN_RESULT"

class QrScannerActivity : ComponentActivity() {
    private val ink by lazy { getColor(R.color.jmd_ink) }
    private val surface by lazy { getColor(R.color.jmd_surface) }
    private val accent by lazy { getColor(R.color.jmd_accent) }
    private val muted by lazy { getColor(R.color.jmd_muted) }
    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var flashButton: Button
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var barcodeScanner: BarcodeScanner
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchEnabled = false
    private val scanDelivered = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        cameraExecutor = Executors.newSingleThreadExecutor()
        barcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
        setContentView(buildScannerScreen())
        ensureCameraPermission()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        barcodeScanner.close()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildScannerScreen(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(surface)
        setPadding(dp(18), dp(12), dp(18), dp(18))

        addView(LinearLayout(this@QrScannerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(this@QrScannerActivity).apply {
                text = "‹"
                textSize = 28f
                isAllCaps = false
                setTextColor(accent)
                background = null
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(this@QrScannerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, 0, 0)
                addView(TextView(this@QrScannerActivity).apply {
                    text = "Escanear QR do JMD"
                    textSize = 18f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(getColor(R.color.jmd_text))
                })
                addView(TextView(this@QrScannerActivity).apply {
                    text = "A câmera lê o código localmente"
                    textSize = 12f
                    setTextColor(muted)
                })
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))

        statusView = TextView(this@QrScannerActivity).apply {
            text = "Aponte a câmera para o QR exibido no JMD desktop."
            textSize = 14f
            setTextColor(getColor(R.color.jmd_text))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        addView(statusView)

        val cameraFrame = FrameLayout(this@QrScannerActivity).apply {
            clipToPadding = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.jmd_card))
                cornerRadius = dp(18).toFloat()
            }
        }
        previewView = PreviewView(this@QrScannerActivity).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        cameraFrame.addView(previewView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        cameraFrame.addView(ScannerFrameOverlay(this@QrScannerActivity), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        addView(cameraFrame, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        addView(TextView(this@QrScannerActivity).apply {
            text = "Mantenha o código inteiro dentro da moldura. Nenhuma imagem é salva ou enviada."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(6))
        })
        flashButton = Button(this@QrScannerActivity).apply {
            text = "Ligar lanterna"
            textSize = 14f
            isAllCaps = false
            setTextColor(accent)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.jmd_card))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), getColor(R.color.jmd_border))
            }
            minHeight = dp(46)
            setOnClickListener { toggleTorch() }
        }
        addView(flashButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            AlertDialog.Builder(this)
                .setTitle("Permitir uso da câmera")
                .setMessage("A câmera será usada somente para ler o QR code de pareamento exibido pelo JMD.")
                .setNegativeButton("Agora não") { _, _ -> finish() }
                .setPositiveButton("Continuar") { _, _ -> requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST) }
                .show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        }
    }

    @Deprecated("Use the activity result API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Câmera não autorizada")
                .setMessage("Para ler o QR code, permita que o JMDroid use a câmera.")
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
        }
    }

    private fun startCamera() {
        statusView.text = "Preparando a câmera…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase -> useCase.setAnalyzer(cameraExecutor, ::analyzeFrame) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                flashButton.isEnabled = camera?.cameraInfo?.hasFlashUnit() == true
                statusView.text = "Aponte a câmera para o QR exibido no JMD desktop."
            } catch (_: Exception) {
                statusView.text = "Não foi possível iniciar a câmera. Feche e tente novamente."
                flashButton.isEnabled = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || scanDelivered.get()) {
            imageProxy.close()
            return
        }
        try {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val qrText = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                    if (!qrText.isNullOrBlank() && scanDelivered.compareAndSet(false, true)) {
                        setResult(RESULT_OK, Intent().putExtra(QR_SCAN_RESULT, qrText))
                        finish()
                    }
                }
                .addOnFailureListener {
                    runOnUiThread { statusView.text = "Não consegui ler o QR. Ajuste a distância e tente novamente." }
                }
                .addOnCompleteListener { imageProxy.close() }
        } catch (_: Exception) {
            imageProxy.close()
        }
    }

    private fun toggleTorch() {
        val activeCamera = camera ?: return
        torchEnabled = !torchEnabled
        activeCamera.cameraControl.enableTorch(torchEnabled)
        flashButton.text = if (torchEnabled) "Desligar lanterna" else "Ligar lanterna"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val CAMERA_PERMISSION_REQUEST = 7103
    }
}

private class ScannerFrameOverlay(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.jmd_accent)
        style = Paint.Style.STROKE
        strokeWidth = 5f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width * 0.78f, 320f * resources.displayMetrics.density)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        val right = left + size
        val bottom = top + size
        val corner = size * 0.15f
        canvas.drawLine(left, top + corner, left, top, paint)
        canvas.drawLine(left, top, left + corner, top, paint)
        canvas.drawLine(right - corner, top, right, top, paint)
        canvas.drawLine(right, top, right, top + corner, paint)
        canvas.drawLine(left, bottom - corner, left, bottom, paint)
        canvas.drawLine(left, bottom, left + corner, bottom, paint)
        canvas.drawLine(right - corner, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - corner, right, bottom, paint)
    }
}
