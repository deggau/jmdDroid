package com.jmd.android

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ink by lazy { getColor(R.color.jmd_ink) }
    private val surface by lazy { getColor(R.color.jmd_surface) }
    private val card by lazy { getColor(R.color.jmd_card) }
    private val accent by lazy { getColor(R.color.jmd_accent) }
    private val textPrimary by lazy { getColor(R.color.jmd_text) }
    private val textMuted by lazy { getColor(R.color.jmd_muted) }
    private val border by lazy { getColor(R.color.jmd_border) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = 0

        val details = deviceDetails()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(28))
            setBackgroundColor(surface)
        }

        page.addView(buildHeader())
        page.addView(label("DIAGNÓSTICO LOCAL", accent).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(34), 0, dp(8))
        })
        page.addView(label("Seu aparelho", textPrimary, 30f, Typeface.create("sans-serif", Typeface.BOLD)))
        page.addView(label(
            "Identificação do dispositivo para preparar a conexão com o JMD no computador.",
            textMuted,
            15f
        ).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(22))
        })

        page.addView(buildDeviceCard())
        page.addView(buildPrivacyNote())
        page.addView(Button(this).apply {
            text = "Copiar dados do aparelho"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(accent, accent, 14f)
            minHeight = dp(54)
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico JMDroid", details))
                Toast.makeText(this@MainActivity, "Dados copiados", Toast.LENGTH_SHORT).show()
            }
        }, marginParams(top = 20))

        page.addView(label("JMDROID  ·  ${BuildConfig.VERSION_NAME}", textMuted, 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(surface)
            addView(page)
        })
    }

    private fun buildHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(TextView(this@MainActivity).apply {
            text = "J"
            textSize = 24f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(ink)
            gravity = Gravity.CENTER
            background = rounded(accent, accent, 12f)
        }, LinearLayout.LayoutParams(dp(46), dp(46)))

        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(label("JMDroid", textPrimary, 19f, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
            addView(label("JMD para Android", textMuted, 13f).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val localTag = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(getColor(R.color.jmd_accent_tint), getColor(R.color.jmd_accent_tint), 20f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            addView(View(this@MainActivity).apply {
                background = rounded(accent, accent, 8f)
            }, LinearLayout.LayoutParams(dp(7), dp(7)))
            addView(label("LOCAL", accent, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                letterSpacing = 0.08f
                setPadding(dp(6), 0, 0, 0)
            })
        }
        addView(localTag)
    }

    private fun buildDeviceCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(card, border, 16f)

        addView(label("MODELO", textMuted, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.12f
        })
        addView(label(Build.MODEL, textPrimary, 20f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            setPadding(0, dp(6), 0, 0)
        })
        addView(label(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, textMuted, 14f).apply {
            setPadding(0, dp(3), 0, dp(18))
        })

        addView(View(this@MainActivity).apply { setBackgroundColor(border) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

        val versionRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("ANDROID", textMuted, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                    letterSpacing = 0.12f
                })
                addView(label("Versão ${Build.VERSION.RELEASE ?: "desconhecida"}", textPrimary, 16f).apply {
                    setPadding(0, dp(5), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "API ${Build.VERSION.SDK_INT}"
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(accent)
                gravity = Gravity.CENTER
                background = rounded(getColor(R.color.jmd_accent_tint), getColor(R.color.jmd_accent_tint), 9f)
                setPadding(dp(10), dp(7), dp(10), dp(7))
            })
        }
        addView(versionRow)
    }

    private fun buildPrivacyNote(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(2), dp(20), dp(2), 0)

        addView(TextView(this@MainActivity).apply {
            text = "✓"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = rounded(getColor(R.color.jmd_accent_tint), getColor(R.color.jmd_accent_tint), 20f)
        }, LinearLayout.LayoutParams(dp(30), dp(30)))

        addView(label("Sem permissões especiais nesta versão.", textMuted, 13f).apply {
            setPadding(dp(10), 0, 0, 0)
        })
    }

    private fun label(
        value: String,
        color: Int,
        size: Float = 14f,
        face: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    ) = TextView(this).apply {
        text = value
        textSize = size
        typeface = face
        setTextColor(color)
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun marginParams(top: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun deviceDetails(): String = buildString {
        appendLine("Fabricante: ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }}")
        appendLine("Modelo: ${Build.MODEL}")
        append("Android: ${Build.VERSION.RELEASE ?: "desconhecida"} (API ${Build.VERSION.SDK_INT})")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
