package com.jmd.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = dp(24)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(0xFFF5F7FA.toInt())
        }

        content.addView(TextView(this).apply {
            text = "JMDroid"
            textSize = 30f
            setTextColor(0xFF172033.toInt())
        })
        content.addView(TextView(this).apply {
            text = "Diagnóstico do aparelho"
            textSize = 17f
            setTextColor(0xFF526078.toInt())
            setPadding(0, dp(6), 0, padding)
        })

        val details = deviceDetails()
        content.addView(TextView(this).apply {
            text = details
            textSize = 16f
            setTextColor(0xFF172033.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(0xFFFFFFFF.toInt())
            setLineSpacing(dp(5).toFloat(), 1f)
        })

        content.addView(Button(this).apply {
            text = "Copiar informações"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico JMDroid", details))
                Toast.makeText(this@MainActivity, "Informações copiadas", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        content.addView(TextView(this).apply {
            text = "Esta versão funciona sem permissões especiais. A conexão com o computador e os recursos de controle serão adicionados em etapas, com autorização visível no aparelho."
            textSize = 14f
            setTextColor(0xFF526078.toInt())
            setPadding(0, padding, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun deviceDetails(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE ?: "desconhecida"
        return "Fabricante: $manufacturer\nModelo: $model\nAndroid: $androidVersion (API ${Build.VERSION.SDK_INT})"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
