package com.jmd.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class MainActivity : Activity() {
    private data class GithubRelease(val tag: String, val downloadUrl: String, val sha256: String)

    private val ink by lazy { getColor(R.color.jmd_ink) }
    private val surface by lazy { getColor(R.color.jmd_surface) }
    private val card by lazy { getColor(R.color.jmd_card) }
    private val accent by lazy { getColor(R.color.jmd_accent) }
    private val textPrimary by lazy { getColor(R.color.jmd_text) }
    private val textMuted by lazy { getColor(R.color.jmd_muted) }
    private val border by lazy { getColor(R.color.jmd_border) }
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private val healthExecutor = Executors.newSingleThreadExecutor()
    private val messageHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkJmdHealth()
            if (!isFinishing && pairedJmd != null) messageHandler.postDelayed(this, 10_000)
        }
    }
    private lateinit var credentialStore: JmdroidCredentialStore
    private lateinit var connectionStatus: TextView
    private lateinit var connectionDot: View
    private lateinit var connectionLabel: TextView
    private lateinit var pairingButton: Button
    private lateinit var commandContainer: LinearLayout
    private lateinit var commandSearch: EditText
    private lateinit var commandPanel: View
    private lateinit var tasksPanel: LinearLayout
    private lateinit var tasksContainer: LinearLayout
    private lateinit var taskSearch: EditText
    private lateinit var taskStatusText: TextView
    private lateinit var commandsTab: Button
    private lateinit var tasksTab: Button
    private var allCommands: List<RemoteCommand> = emptyList()
    private var allTasks: List<JmdroidTask> = emptyList()
    private var taskStatus = "open"
    private var showingTaskHistory = false
    private var taskHistoryPage = 0
    private var taskHistoryItems: List<JmdroidTaskHistoryEntry> = emptyList()
    private var taskHistoryTotal = 0
    private var pairedJmd: PairedJmd? = null
    private var jmdApiClient: JmdroidApiClient? = null
    private var jmdWebSocket: WebSocket? = null
    private var jmdSessionReady = false
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var mainScreen: View
    private var updateScreenVisible = false
    private var availableUpdate: GithubRelease? = null
    private var pendingApk: File? = null
    private lateinit var appMessageBanner: LinearLayout
    private lateinit var appMessageText: TextView
    private val hideAppMessage = Runnable {
        if (::appMessageBanner.isInitialized) appMessageBanner.visibility = View.GONE
    }
    private val taskHistorySearchRunnable = Runnable {
        if (showingTaskHistory && ::taskSearch.isInitialized) loadTaskHistory(0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = 0

        credentialStore = JmdroidCredentialStore(this)
        pairedJmd = credentialStore.load()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(28))
            setBackgroundColor(surface)
        }

        page.addView(buildHeader())
        page.addView(label("CONTROLE REMOTO", accent).apply {
            letterSpacing = 0.14f
            setPadding(0, dp(28), 0, dp(8))
        })
        page.addView(label("JMD no celular", textPrimary, 28f, Typeface.create("sans-serif", Typeface.BOLD)))
        page.addView(label(
            "Acesse os comandos e acompanhe suas tarefas do desktop.",
            textMuted,
            15f
        ).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(22))
        })

        page.addView(buildPrimaryTabs(), marginParams(top = 18))
        commandPanel = buildRemoteCard()
        page.addView(commandPanel, marginParams(top = 14))
        tasksPanel = buildTasksScreen()
        tasksPanel.visibility = View.GONE
        page.addView(tasksPanel, marginParams(top = 14))

        page.addView(label("JMDROID  ·  ${BuildConfig.VERSION_NAME}", textMuted, 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
        })

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(surface)
            addView(page)
        }
        val screen = FrameLayout(this)
        screen.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        appMessageText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(textPrimary)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
        }
        appMessageBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(18), dp(12))
            background = rounded(card, border, 14f)
            elevation = dp(8).toFloat()
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(appMessageText, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply { marginStart = dp(12) })
        }
        screen.addView(appMessageBanner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            setMargins(dp(20), 0, dp(20), dp(48))
        })
        mainScreen = screen
        setContentView(mainScreen)
        pairedJmd?.let {
            connectToJmd(it, loadCommands = true)
            scheduleHealthChecks()
        }
    }

    override fun onDestroy() {
        messageHandler.removeCallbacks(hideAppMessage)
        messageHandler.removeCallbacks(healthCheckRunnable)
        messageHandler.removeCallbacks(taskHistorySearchRunnable)
        backgroundExecutor.shutdownNow()
        healthExecutor.shutdownNow()
        jmdWebSocket?.close(1000, "App fechado")
        jmdApiClient?.close()
        super.onDestroy()
    }

    @Deprecated("Use the system back dispatcher")
    override fun onBackPressed() {
        if (updateScreenVisible) closeUpdateScreen() else super.onBackPressed()
    }

    private fun showBrandedMessage(message: String, durationMs: Long = 2_500L) {
        if (!::appMessageBanner.isInitialized || isFinishing) return
        appMessageText.text = message
        appMessageBanner.visibility = View.VISIBLE
        messageHandler.removeCallbacks(hideAppMessage)
        messageHandler.postDelayed(hideAppMessage, durationMs)
    }

    @Deprecated("Use the activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == QR_SCAN_REQUEST) {
            if (resultCode == RESULT_OK) {
                val rawQr = data?.getStringExtra(QR_SCAN_RESULT).orEmpty()
                try {
                    confirmPairingTransport(JmdroidApiClient.parsePairingQr(rawQr))
                } catch (error: Exception) {
                    AlertDialog.Builder(this)
                        .setTitle("QR code inválido")
                        .setMessage(error.message ?: "O código lido não é um pareamento JMD válido.")
                        .setPositiveButton("Tentar novamente") { _, _ -> startPairingScanner() }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
            } else if (data?.getBooleanExtra(QR_SCAN_MANUAL, false) == true) {
                showManualPairingInput()
            }
            return
        }
        if (requestCode != INSTALL_SOURCE_REQUEST) return

        val apk = pendingApk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls() && apk != null) {
            launchPackageInstaller(apk)
        } else {
            updateStatus.text = "A permissão de instalação não foi ativada."
            updateButton.isEnabled = true
        }
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

        val statusTag = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card, border, 20f)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            connectionDot = View(this@MainActivity).apply { background = rounded(Color.RED, Color.RED, 8f) }
            addView(connectionDot, LinearLayout.LayoutParams(dp(8), dp(8)))
            connectionLabel = label("OFFLINE", textMuted, 10f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                letterSpacing = 0.05f
                setPadding(dp(6), 0, 0, 0)
            }
            addView(connectionLabel)
        }
        addView(statusTag)
        addView(TextView(this@MainActivity).apply {
            text = "⋮"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
            contentDescription = "Mais opções"
            setOnClickListener { showSecondaryMenu(this) }
        }, LinearLayout.LayoutParams(dp(44), dp(48)).apply { marginStart = dp(4) })
        refreshConnectionIndicator(false)
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

    private fun buildUpdateScreen(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(28))
            setBackgroundColor(surface)
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "‹"
                textSize = 34f
                gravity = Gravity.CENTER
                setTextColor(textPrimary)
                contentDescription = "Voltar"
                setOnClickListener { closeUpdateScreen() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(label("Atualizações", textPrimary, 21f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                setPadding(dp(8), 0, 0, 0)
            })
        }
        content.addView(toolbar)
        content.addView(label("JMDROID", accent, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.14f
            setPadding(dp(2), dp(36), 0, dp(8))
        })
        content.addView(label("Atualização do aplicativo", textPrimary, 27f, Typeface.create("sans-serif", Typeface.BOLD)))
        content.addView(label("Veja se há uma versão nova do JMDroid e instale quando estiver pronto.", textMuted, 15f).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(24))
        })

        val versionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(card, border, 16f)
            addView(label("VERSÃO INSTALADA", textMuted, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                letterSpacing = 0.1f
            })
            addView(label("${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}", textPrimary, 20f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }
        content.addView(versionCard)

        updateStatus = label("Toque no botão para consultar a versão mais recente.", textMuted, 14f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(card, border, 14f)
        }
        content.addView(updateStatus, marginParams(top = 12))
        updateButton = Button(this).apply {
            text = "Verificar atualizações"
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(accent, accent, 12f)
            minHeight = dp(52)
            setOnClickListener {
                val release = availableUpdate
                if (release != null) downloadAndInstall(release) else checkForUpdate()
            }
        }
        content.addView(updateButton, marginParams(top = 16))
        content.addView(label("O download é conferido por SHA-256. O Android pedirá confirmação antes da instalação.", textMuted, 12f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(2), dp(12), dp(2), 0)
        })
        return ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(surface)
            addView(content)
        }
    }

    private fun openUpdateScreen() {
        messageHandler.removeCallbacks(hideAppMessage)
        if (::appMessageBanner.isInitialized) appMessageBanner.visibility = View.GONE
        updateScreenVisible = true
        setContentView(buildUpdateScreen())
        checkForUpdate()
    }

    private fun closeUpdateScreen() {
        updateScreenVisible = false
        setContentView(mainScreen)
    }

    private fun buildRemoteCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(card, border, 16f)

        addView(label("JMD DESKTOP", accent, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.12f
        })
        addView(label(if (pairedJmd == null) "Conecte seu computador" else "Comandos disponíveis", textPrimary, 18f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            setPadding(0, dp(6), 0, dp(3))
        })
        addView(label(if (pairedJmd == null) "Conecte ao JMD desktop para consultar e executar comandos." else "Toque em um comando para informar o texto e executá-lo no computador.", textMuted, 13f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        connectionStatus = label(if (pairedJmd == null) "Nenhum JMD conectado." else "Verificando conexão com o computador…", textMuted, 12f)
        addView(connectionStatus)
        pairingButton = Button(this@MainActivity).apply {
            text = if (pairedJmd == null) "Conectar ao JMD" else "Reconectar ao JMD"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(accent, accent, 12f)
            minHeight = dp(48)
            setOnClickListener {
                val paired = pairedJmd
                if (paired == null) startPairingScanner() else connectToJmd(paired, loadCommands = true)
            }
        }
        addView(pairingButton, marginParams(top = 10))
        pairingButton.visibility = View.VISIBLE

        commandSearch = EditText(this@MainActivity).apply {
            hint = "Filtrar comandos"
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 16f
            setSingleLine(true)
            minHeight = dp(58)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(surface, border, 10f)
        }
        addView(commandSearch, marginParams(top = 14))
        commandSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderCommands(s?.toString().orEmpty())
        })
        commandContainer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addView(commandContainer)
        commandSearch.visibility = if (pairedJmd == null) View.GONE else View.VISIBLE
        commandContainer.visibility = if (pairedJmd == null) View.GONE else View.VISIBLE
        refreshConnectionStatus()
    }

    private fun buildPrimaryTabs(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = rounded(card, border, 14f)
        commandsTab = Button(this@MainActivity).apply {
            text = "Comandos"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { selectMainTab(showTasks = false) }
        }
        tasksTab = Button(this@MainActivity).apply {
            text = "Tarefas"
            textSize = 14f
            isAllCaps = false
            setOnClickListener { selectMainTab(showTasks = true) }
        }
        addView(commandsTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(tasksTab, LinearLayout.LayoutParams(0, dp(46), 1f))
        updateTabStyles(showTasks = false)
    }

    private fun selectMainTab(showTasks: Boolean) {
        if (!::commandPanel.isInitialized || !::tasksPanel.isInitialized) return
        commandPanel.visibility = if (showTasks) View.GONE else View.VISIBLE
        tasksPanel.visibility = if (showTasks) View.VISIBLE else View.GONE
        updateTabStyles(showTasks)
        if (showTasks) {
            if (pairedJmd == null) {
                taskStatusText.text = "Conecte um JMD na tela Comandos para acessar as tarefas."
                renderTasks()
            } else if (showingTaskHistory) loadTaskHistory(0) else loadTasks()
        }
    }

    private fun updateTabStyles(showTasks: Boolean) {
        if (!::commandsTab.isInitialized) return
        listOf(commandsTab to !showTasks, tasksTab to showTasks).forEach { (button, selected) ->
            button.setTextColor(if (selected) ink else textMuted)
            button.background = rounded(if (selected) accent else card, if (selected) accent else card, 10f)
        }
    }

    private fun buildTasksScreen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(card, border, 16f)

        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("Tarefas", textPrimary, 20f, Typeface.create("sans-serif-medium", Typeface.NORMAL)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "+ Nova tarefa"
                textSize = 13f
                isAllCaps = false
                setTextColor(ink)
                background = rounded(accent, accent, 10f)
                setOnClickListener { showTaskEditor() }
            })
        }
        addView(header)

        val filters = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        listOf("Abertas" to "open", "Concluídas" to "completed", "Todas" to "all").forEach { (title, status) ->
            filters.addView(Button(this@MainActivity).apply {
                text = title
                textSize = 11f
                isAllCaps = false
                setTextColor(textMuted)
                background = rounded(surface, surface, 9f)
                setOnClickListener {
                    showingTaskHistory = false
                    taskStatus = status
                    messageHandler.removeCallbacks(taskHistorySearchRunnable)
                    taskSearch.hint = "Filtrar tarefas"
                    taskSearch.setText("")
                    taskStatusText.visibility = View.VISIBLE
                    loadTasks()
                }
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
        }
        filters.addView(Button(this@MainActivity).apply {
            text = "Histórico"
            textSize = 11f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(surface, surface, 9f)
            setOnClickListener {
                showingTaskHistory = true
                taskStatusText.visibility = View.VISIBLE
                taskSearch.hint = "Buscar no histórico"
                taskSearch.setText("")
                loadTaskHistory(0)
            }
        }, LinearLayout.LayoutParams(0, dp(40), 0.9f))
        addView(filters)

        taskSearch = EditText(this@MainActivity).apply {
            hint = "Filtrar tarefas"
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 16f
            setSingleLine(true)
            minHeight = dp(54)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(surface, border, 10f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (showingTaskHistory) {
                        messageHandler.removeCallbacks(taskHistorySearchRunnable)
                        messageHandler.postDelayed(taskHistorySearchRunnable, 450)
                    } else renderTasks()
                }
            })
        }
        addView(taskSearch, marginParams(top = 12))
        taskStatusText = label("Conecte um JMD para carregar tarefas.", textMuted, 12f).apply {
            setPadding(0, dp(10), 0, dp(2))
        }
        addView(taskStatusText)
        tasksContainer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        addView(tasksContainer)
        renderTasks()
    }

    private fun loadTasks() {
        val client = jmdApiClient
        if (client == null) {
            taskStatusText.text = "Conecte um JMD na tela Comandos para acessar as tarefas."
            renderTasks()
            return
        }
        taskStatusText.text = "Carregando tarefas…"
        backgroundExecutor.execute {
            try {
                val tasks = client.getTasks(taskStatus)
                runOnUiThread {
                    allTasks = tasks
                    taskStatusText.text = "${tasks.size} tarefa(s)"
                    renderTasks()
                }
            } catch (error: Exception) {
                runOnUiThread { taskStatusText.text = apiErrorMessage(error); renderTasks() }
            }
        }
    }

    private fun renderTasks() {
        if (!::tasksContainer.isInitialized) return
        tasksContainer.removeAllViews()
        if (showingTaskHistory) {
            taskHistoryItems.forEach { item ->
                val row = label("${item.commandName}\n${item.afterValue}\n${item.occurredAt} · ${item.sourceDeviceName ?: item.source}", textPrimary, 14f)
                row.setPadding(dp(12), dp(12), dp(12), dp(12))
                row.background = rounded(surface, border, 10f)
                tasksContainer.addView(row, marginParams(top = 8))
            }
            if (taskHistoryItems.size < taskHistoryTotal) tasksContainer.addView(Button(this).apply {
                text = "Carregar mais histórico"; isAllCaps = false
                setOnClickListener { loadTaskHistory(taskHistoryPage + 1) }
            })
            return
        }
        val query = if (::taskSearch.isInitialized) taskSearch.text.toString().trim() else ""
        val visible = allTasks.filter { query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true) || it.checklist.any { s -> s.title.contains(query, true) } }
        if (visible.isEmpty()) {
            tasksContainer.addView(label(if (pairedJmd == null) "Conecte um JMD para ver as tarefas." else "Nenhuma tarefa encontrada.", textMuted, 14f).apply { setPadding(0, dp(16), 0, dp(16)) })
        }
        visible.forEach { task ->
            val done = task.checklist.count { it.isCompleted }
            val cardView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(surface, border, 12f)
            }
            val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            heading.addView(label("#${task.taskNumber}  ${task.title}", textPrimary, 16f, Typeface.create("sans-serif-medium", Typeface.NORMAL)), LinearLayout.LayoutParams(0, -2, 1f))
            cardView.addView(heading)
            if (task.description.isNotBlank()) cardView.addView(label(task.description, textMuted, 13f).apply { setPadding(0, dp(4), 0, dp(4)) })
            val percent = if (task.checklist.isEmpty()) if (task.completedAt != null) 100 else 0 else task.checklist.filter { it.isCompleted }.sumOf { it.weight }.toInt()
            cardView.addView(label("${task.categoryColor} · $percent% · ${if (task.pausedAt != null) "Pausada" else if (task.completedAt != null) "Concluída" else "$done/${task.checklist.size} etapas"}", textMuted, 12f).apply { setPadding(0, dp(5), 0, dp(3)) })
            if (task.checklist.isNotEmpty()) {
                cardView.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = percent }, LinearLayout.LayoutParams(-1, dp(5)))
                task.checklist.forEachIndexed { index, step ->
                    cardView.addView(CheckBox(this).apply {
                        text = step.title; textSize = 13f; setTextColor(textPrimary); isChecked = step.isCompleted; isEnabled = task.completedAt == null
                        setOnCheckedChangeListener { _, checked -> updateTaskStep(task.id, index, checked) }
                    })
                }
            }
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(taskActionIcon(if (task.completedAt == null) "✓" else "↻", if (task.completedAt == null) "Concluir tarefa" else "Reabrir tarefa", Color.rgb(46, 125, 50)) {
                mutateTask { setTaskCompleted(task.id, task.completedAt == null) }
            })
            actions.addView(taskActionIcon("✎", "Editar tarefa", accent) { showTaskEditor(task) })
            actions.addView(taskActionIcon(if (task.pausedAt == null) "Ⅱ" else "▶", if (task.pausedAt == null) "Pausar tarefa" else "Retomar tarefa", Color.rgb(230, 126, 34)) {
                if (task.pausedAt == null) showPauseDialog(task) else mutateTask { unpauseTask(task.id) }
            })
            actions.addView(taskActionIcon("×", "Excluir tarefa", Color.rgb(198, 40, 40)) {
                AlertDialog.Builder(this@MainActivity).setMessage("Excluir esta tarefa?")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Excluir") { _, _ -> mutateTask { deleteTask(task.id); null } }
                    .show()
            })
            cardView.addView(actions)
            cardView.setOnClickListener { showTaskDetails(task) }
            tasksContainer.addView(cardView, marginParams(top = 10))
        }
    }

    private fun loadTaskHistory(page: Int) {
        val client = jmdApiClient ?: return
        taskStatusText.text = "Carregando histórico…"
        backgroundExecutor.execute {
            try {
                val result = client.getTaskHistory(taskSearch.text.toString(), page)
                runOnUiThread {
                    taskHistoryPage = result.page; taskHistoryTotal = result.totalCount
                    taskHistoryItems = if (page == 0) result.items else taskHistoryItems + result.items
                    taskStatusText.text = "${result.totalCount} registro(s) no histórico"
                    renderTasks()
                }
            } catch (error: Exception) { runOnUiThread { taskStatusText.text = apiErrorMessage(error) } }
        }
    }

    private fun updateTaskStep(id: String, index: Int, checked: Boolean) {
        val client = jmdApiClient ?: return
        backgroundExecutor.execute {
            try { client.setTaskChecklistStep(id, index, checked); runOnUiThread { loadTasks() } }
            catch (error: Exception) { runOnUiThread { showBrandedMessage(apiErrorMessage(error)); loadTasks() } }
        }
    }

    private fun showTaskDetails(task: JmdroidTask) {
        val detail = buildString {
            append(task.description.ifBlank { "Sem descrição" }); append("\n\nCategoria: ${task.categoryColor}")
            task.reminderIntervalMinutes?.let { append("\nLembrete: a cada $it minutos") }
            if (task.urls.isNotBlank()) append("\n\nLinks:\n${task.urls}")
            if (task.attachments.isNotBlank()) append("\n\nAnexos:\n${task.attachments}")
            task.pausedAt?.let { append("\n\nPausada: ${task.pauseReason} · ${task.pausedBy}") }
        }
        AlertDialog.Builder(this).setTitle("#${task.taskNumber} · ${task.title}").setMessage(detail)
            .setPositiveButton("Editar") { _, _ -> showTaskEditor(task) }
            .setNeutralButton(if (task.completedAt == null) "Concluir" else "Reabrir") { _, _ -> mutateTask { setTaskCompleted(task.id, task.completedAt == null) } }
            .setNegativeButton("Fechar", null).show()
    }

    private fun taskActionIcon(symbol: String, description: String, tint: Int, action: () -> Unit): TextView = TextView(this).apply {
        text = symbol
        contentDescription = description
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(tint)
        background = rounded(card, border, 10f)
        isFocusable = true
        isClickable = true
        setOnClickListener { action() }
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) }
    }

    private fun showPauseDialog(task: JmdroidTask) {
        val reason = EditText(this).apply { hint = "Motivo da pausa" }
        AlertDialog.Builder(this).setTitle("Pausar tarefa").setView(reason).setNegativeButton("Cancelar", null)
            .setPositiveButton("Pausar") { _, _ -> mutateTask { pauseTask(task.id, reason.text.toString(), Build.MODEL ?: "Android") } }.show()
    }

    private fun mutateTask(action: JmdroidApiClient.() -> Any?) {
        val client = jmdApiClient ?: return
        backgroundExecutor.execute { try { client.action(); runOnUiThread { loadTasks(); showBrandedMessage("Tarefa atualizada") } }
            catch (error: Exception) { runOnUiThread { showBrandedMessage(apiErrorMessage(error)) } } }
    }

    private fun showTaskEditor(task: JmdroidTask? = null) {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun field(hintText: String, value: String = "", multiline: Boolean = false) = EditText(this).apply {
            hint = hintText; setText(value); setTextColor(textPrimary); textSize = 15f
            if (multiline) minLines = 2 else setSingleLine(true)
            form.addView(this, marginParams(top = 6))
        }
        val title = field("Título", task?.title.orEmpty())
        val description = field("Descrição", task?.description.orEmpty(), true)
        val checklist = field("Etapas (uma por linha)", task?.checklist?.joinToString("\n") { it.title }.orEmpty(), true)
        val urls = field("Links (um por linha)", task?.urls.orEmpty(), true)
        val attachments = field("Anexos (um por linha)", task?.attachments.orEmpty(), true)
        val reminder = field("Lembrete em minutos (opcional)", task?.reminderIntervalMinutes?.toString().orEmpty())
        val categories = arrayOf("Vermelho", "Laranja", "Amarelo", "Verde")
        val spinner = Spinner(this).apply { adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories); setSelection(categories.indexOf(task?.categoryColor).coerceAtLeast(0)); form.addView(this) }
        val scroll = ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle(if (task == null) "Nova tarefa" else "Editar tarefa").setView(scroll)
            .setNegativeButton("Cancelar", null).setPositiveButton("Salvar", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = title.text.toString().trim()
                        val steps = checklist.text.toString().lines().map { it.trim() }.filter { it.isNotBlank() }
                        val interval = reminder.text.toString().trim().toIntOrNull()
                        if (name.isBlank() || name.length > 300 || (reminder.text.isNotBlank() && (interval == null || interval !in 1..10080))) {
                            showBrandedMessage("Informe título e intervalo válidos."); return@setOnClickListener
                        }
                        val old = task?.checklist.orEmpty()
                        val baseWeight = if (steps.isEmpty()) 0 else 100 / steps.size
                        val entries = steps.mapIndexed { i, value ->
                            val weight = if (i == steps.lastIndex) 100 - baseWeight * (steps.size - 1) else baseWeight
                            JmdroidTaskChecklistEntry(value, weight.toDouble(), old.getOrNull(i)?.isCompleted ?: false)
                        }
                        val input = JmdroidTaskInput(name, description.text.toString(), interval, spinner.selectedItem.toString(), urls.text.toString(), attachments.text.toString(), entries)
                        val client = jmdApiClient ?: return@setOnClickListener
                        backgroundExecutor.execute { try { if (task == null) client.createTask(input) else client.updateTask(task.id, input); runOnUiThread { dialog.dismiss(); loadTasks(); showBrandedMessage("Tarefa salva") } } catch (error: Exception) { runOnUiThread { showBrandedMessage(apiErrorMessage(error)) } } }
                    }
                }
            }.show()
    }

    private fun startPairingScanner() {
        startActivityForResult(Intent(this, QrScannerActivity::class.java), QR_SCAN_REQUEST)
    }

    private fun showSecondaryMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Informações do aparelho").setOnMenuItemClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Informações do aparelho")
                    .setMessage(deviceDetails())
                    .setNegativeButton("Fechar", null)
                    .setPositiveButton("Copiar dados") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico JMDroid", deviceDetails()))
                        showBrandedMessage("Dados copiados")
                    }.show()
                true
            }
            menu.add("Verificar atualizações").setOnMenuItemClickListener {
                openUpdateScreen()
                true
            }
            if (pairedJmd == null) {
                menu.add("Conectar a um JMD").setOnMenuItemClickListener {
                    startPairingScanner()
                    true
                }
            } else {
                menu.add("Conectar novamente").setOnMenuItemClickListener {
                    pairedJmd?.let { connectToJmd(it, loadCommands = true) }
                    true
                }
                menu.add("Conectar a outro JMD").setOnMenuItemClickListener {
                    startPairingScanner()
                    true
                }
                menu.add("Desconectar").setOnMenuItemClickListener {
                    disconnectFromJmd()
                    true
                }
            }
            show()
        }
    }

    private fun disconnectFromJmd() {
        messageHandler.removeCallbacks(healthCheckRunnable)
        jmdWebSocket?.close(1000, "Desconectado pelo usuário")
        jmdWebSocket = null
        jmdApiClient?.close()
        jmdApiClient = null
        jmdSessionReady = false
        pairedJmd = null
        allCommands = emptyList()
        allTasks = emptyList()
        taskHistoryItems = emptyList()
        credentialStore.clear()
        pairingButton.visibility = View.VISIBLE
        commandSearch.visibility = View.GONE
        commandContainer.visibility = View.GONE
        connectionStatus.text = "Nenhum JMD conectado."
        refreshConnectionIndicator(false)
        showBrandedMessage("Desconectado do JMD")
    }

    private fun refreshConnectionIndicator(connected: Boolean) {
        if (!::connectionDot.isInitialized) return
        val color = if (connected) Color.rgb(76, 175, 80) else Color.rgb(229, 83, 83)
        connectionDot.background = rounded(color, color, 8f)
        connectionLabel.text = if (connected) "CONECTADO" else "OFFLINE"
        connectionLabel.setTextColor(if (connected) color else textMuted)
        if (::pairingButton.isInitialized) {
            pairingButton.text = if (pairedJmd == null) "Conectar ao JMD" else "Reconectar ao JMD"
            pairingButton.visibility = if (pairedJmd == null || !connected) View.VISIBLE else View.GONE
        }
        if (::connectionStatus.isInitialized && pairedJmd != null) {
            connectionStatus.visibility = if (connected) View.GONE else View.VISIBLE
        }
        if (::connectionStatus.isInitialized && pairedJmd == null) connectionStatus.text = "Nenhum JMD conectado."
    }

    private fun scheduleHealthChecks() {
        messageHandler.removeCallbacks(healthCheckRunnable)
        healthCheckRunnable.run()
    }

    private fun checkJmdHealth() {
        val paired = pairedJmd ?: run {
            refreshConnectionIndicator(false)
            return
        }
        val client = jmdApiClient ?: JmdroidApiClient(paired).also { jmdApiClient = it }
        healthExecutor.execute {
            var failure: Exception? = null
            val connected = try {
                client.healthCheck()
                true
            } catch (error: Exception) {
                failure = error
                false
            }
            runOnUiThread {
                if (pairedJmd?.deviceId == paired.deviceId) {
                    val error = failure
                    if (error is ApiException && error.status in setOf(401, 403)) {
                        connectionStatus.text = apiErrorMessage(error)
                        return@runOnUiThread
                    }
                    refreshConnectionIndicator(connected)
                    if (connected) connectionStatus.text = "API do JMD disponível · toque em um comando para executar."
                    else connectionStatus.text = "Sem resposta do JMD. Verifique a rede ou tente novamente no menu."
                }
            }
        }
    }

    private fun showManualPairingInput() {
        val input = EditText(this).apply {
            hint = "Cole aqui o JSON do QR code do JMD"
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
            minLines = 4
            maxLines = 8
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText((getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString().orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Parear com o JMD")
            .setMessage("Como alternativa à câmera, cole o conteúdo JSON do QR code exibido em Configurações > Android > Conectar no JMD desktop.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Solicitar aprovação", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val qr = try { JmdroidApiClient.parsePairingQr(input.text.toString()) }
                        catch (error: Exception) {
                            input.error = error.message ?: "QR code inválido"
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        confirmPairingTransport(qr)
                    }
                }
                dialog.show()
            }
    }

    private fun startPairing(qr: PairingQr) {
        pairingButton.isEnabled = false
        connectionStatus.text = "Solicitando aprovação no computador…"
        backgroundExecutor.execute {
            try {
                val paired = JmdroidApiClient.pair(qr, Build.MODEL.ifBlank { "Android" }, BuildConfig.VERSION_NAME)
                credentialStore.save(paired)
                runOnUiThread {
                    pairedJmd = paired
                    pairingButton.isEnabled = true
                    pairingButton.visibility = View.GONE
                    commandSearch.visibility = View.VISIBLE
                    commandContainer.visibility = View.VISIBLE
                    connectToJmd(paired, loadCommands = true)
                    scheduleHealthChecks()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pairingButton.isEnabled = true
                    connectionStatus.text = error.message ?: "Não foi possível parear com o JMD."
                }
            }
        }
    }

    private fun confirmPairingTransport(qr: PairingQr) {
        if (!qr.baseUrl.startsWith("http://", ignoreCase = true)) {
            startPairing(qr)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Usar conexão HTTP?")
            .setMessage("O segredo de pareamento e o token serão enviados sem criptografia. Continue somente em uma rede interna confiável.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> startPairing(qr) }
            .show()
    }

    private fun connectToJmd(paired: PairedJmd, loadCommands: Boolean) {
        jmdWebSocket?.close(1000, "Reconectando")
        jmdApiClient?.close()
        jmdApiClient = JmdroidApiClient(paired)
        connectionStatus.text = "Conectando à sessão segura do JMD…"
        val socket = try {
            jmdApiClient!!.connectSession(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnUiThread {
                        jmdSessionReady = true
                        connectionStatus.text = "Sessão ativa no JMD."
                        if (loadCommands) loadRemoteCommands()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!text.contains("session.ready")) return
                    runOnUiThread { connectionStatus.text = "Sessão segura ativa no JMD." }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runOnUiThread {
                        if (jmdWebSocket !== webSocket) return@runOnUiThread
                        jmdSessionReady = false
                        connectionStatus.text = "Sessão do JMD encerrada."
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    runOnUiThread {
                        if (jmdWebSocket !== webSocket) return@runOnUiThread
                        jmdSessionReady = false
                        if (response?.code == 401 || response?.code == 403) {
                            credentialStore.clear()
                            pairedJmd = null
                            pairingButton.visibility = View.VISIBLE
                            commandSearch.visibility = View.GONE
                            commandContainer.visibility = View.GONE
                            messageHandler.removeCallbacks(healthCheckRunnable)
                            refreshConnectionIndicator(false)
                        }
                        connectionStatus.text = error.message ?: "Conexão com o JMD interrompida."
                    }
                }
            })
        } catch (error: Exception) {
            connectionStatus.text = error.message ?: "Não foi possível abrir a sessão do JMD."
            return
        }
        jmdWebSocket?.close(1000, "Nova conexão")
        jmdWebSocket = socket
    }

    private fun loadRemoteCommands() {
        val client = jmdApiClient ?: run {
            connectionStatus.text = "Pareie primeiro com o JMD desktop."
            return
        }
        connectionStatus.text = "Carregando comandos…"
        backgroundExecutor.execute {
            try {
                val commands = client.getCommands()
                runOnUiThread {
                    allCommands = commands
                    renderCommands(commandSearch.text.toString())
                    connectionStatus.text = if (commands.isEmpty()) "Nenhum comando disponível no JMD." else "${commands.size} comando(s) carregado(s)."
                }
            } catch (error: Exception) {
                runOnUiThread { connectionStatus.text = apiErrorMessage(error) }
            }
        }
    }

    private fun renderCommands(query: String) {
        if (!::commandContainer.isInitialized) return
        val normalized = query.trim().lowercase()
        val commands = allCommands.filter { command ->
            normalized.isBlank() || listOf(command.name, command.description, command.shortcut)
                .any { it.lowercase().contains(normalized) }
        }
        commandContainer.removeAllViews()
        if (commands.isEmpty()) {
            commandContainer.addView(label(
                if (allCommands.isEmpty()) "Os comandos aparecerão aqui quando o JMD estiver conectado." else "Nenhum comando encontrado para essa busca.",
                textMuted, 13f
            ))
        }
        commands.forEach { command ->
                        val item = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(11), dp(12), dp(11))
                            background = rounded(surface, border, 10f)
                            addView(label(command.name, textPrimary, 15f, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
                            if (command.description.isNotBlank()) {
                                addView(label(command.description, textMuted, 12f).apply { setPadding(0, dp(3), 0, 0) })
                            }
                            if (command.shortcut.isNotBlank()) {
                                addView(label(command.shortcut, accent, 11f).apply { setPadding(0, dp(4), 0, 0) })
                            }
                            if (!command.available) {
                                addView(label(
                                    command.unavailableReason ?: "A execução será solicitada ao JMD desktop.",
                                    textMuted,
                                    11f
                                ).apply { setPadding(0, dp(5), 0, 0) })
                            }
                            isClickable = command.available
                            isEnabled = command.available
                            alpha = if (command.available) 1f else 0.55f
                            if (command.available) setOnClickListener { showCommandInput(command) }
                        }
                        commandContainer.addView(item, marginParams(top = 6))
        }
        if (pairedJmd != null && allCommands.isNotEmpty()) {
            connectionStatus.text = if (normalized.isBlank()) "${commands.size} comando(s) disponível(is)." else "${commands.size} resultado(s) para ‘$query’."
        }
    }

    private fun showCommandInput(command: RemoteCommand) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val workflowFields = linkedMapOf<String, EditText>()
        val textInput = if (!command.isWorkflow) EditText(this).apply {
            hint = "Texto para enviar ao computador"
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            form.addView(this)
        } else null
        if (command.isWorkflow) {
            form.addView(label("O workflow será executado no JMD desktop.", textMuted, 13f).apply {
                setPadding(0, 0, 0, dp(8))
            })
            command.inputVariables.forEach { variable ->
                form.addView(label(variable, textPrimary, 13f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                    setPadding(0, dp(8), 0, dp(4))
                })
                workflowFields[variable] = EditText(this).apply {
                    hint = "Valor para $variable"
                    setTextColor(textPrimary)
                    setHintTextColor(textMuted)
                    setSingleLine(true)
                    form.addView(this)
                }
            }
            if (command.inputVariables.isEmpty()) {
                form.addView(label("Este workflow não solicita valores adicionais.", textMuted, 13f))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(command.name)
            .setMessage(if (command.isWorkflow) "Os dados serão enviados à API para execução no desktop." else "O texto será enviado à API para execução no desktop.")
            .setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Executar", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val payload = if (command.isWorkflow) {
                            val variables = JSONObject()
                            workflowFields.forEach { (name, field) ->
                                if (field.text.length > 10_000) {
                                    field.error = "O limite é 10.000 caracteres."
                                    return@setOnClickListener
                                }
                                if (field.text.isNotEmpty()) variables.put(name, field.text.toString())
                            }
                            JSONObject().put("variables", variables)
                        } else {
                            val text = textInput?.text?.toString().orEmpty()
                            if (text.isEmpty()) {
                                textInput?.error = "Informe o texto para processar."
                                return@setOnClickListener
                            }
                            if (text.length > 1_000_000) {
                                textInput?.error = "O limite é 1.000.000 de caracteres."
                                return@setOnClickListener
                            }
                            JSONObject().put("text", text)
                        }
                        dialog.dismiss()
                        executeRemoteCommand(command, payload)
                    }
                }
                dialog.show()
            }
    }

    private fun executeRemoteCommand(command: RemoteCommand, input: JSONObject) {
        val client = jmdApiClient ?: return
        connectionStatus.text = "Executando ${command.name}…"
        backgroundExecutor.execute {
            try {
                val response = client.execute(command.id, input)
                val succeeded = response.optString("status") == "succeeded"
                val output = response.optJSONObject("result")?.optString("text").orEmpty()
                val error = response.optJSONObject("error")?.optString("message").orEmpty()
                runOnUiThread {
                    connectionStatus.text = if (succeeded) "${command.name} concluído." else "${command.name} falhou."
                    if (succeeded) showResultDialog(command.name, output)
                    else AlertDialog.Builder(this).setTitle("Não foi possível executar")
                        .setMessage(error.ifBlank { "O JMD não conseguiu executar o comando." }).setPositiveButton("OK", null).show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    val message = apiErrorMessage(error)
                    connectionStatus.text = message
                    showBrandedMessage(message, 4_000L)
                }
            }
        }
    }

    private fun showResultDialog(commandName: String, output: String) {
        AlertDialog.Builder(this)
            .setTitle("Resultado · $commandName")
            .setMessage(output.ifBlank { "Comando concluído sem texto de saída." })
            .setNegativeButton("Fechar", null)
            .setPositiveButton("Copiar") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Resultado JMD", output))
                showBrandedMessage("Resultado copiado")
            }
            .show()
    }

    private fun refreshConnectionStatus() {
        if (::connectionStatus.isInitialized && pairedJmd != null) connectionStatus.text = "Computador pareado · aguardando conexão segura."
    }

    private fun apiErrorMessage(error: Exception): String = when (error) {
        is ApiException -> when (error.status) {
            401, 403 -> {
                if (error.code in setOf("unauthorized", "device_revoked")) {
                    credentialStore.clear()
                    pairedJmd = null
                    allCommands = emptyList()
                    messageHandler.removeCallbacks(healthCheckRunnable)
                    if (::pairingButton.isInitialized) pairingButton.visibility = View.VISIBLE
                    if (::commandSearch.isInitialized) commandSearch.visibility = View.GONE
                    if (::commandContainer.isInitialized) commandContainer.visibility = View.GONE
                    refreshConnectionIndicator(false)
                }
                if (error.code == "command_unavailable") error.message ?: "Este comando não está disponível agora."
                else "O acesso foi recusado. O JMD pode ter revogado este aparelho; faça o pareamento novamente."
            }
            429 -> "O JMD limitou as solicitações. Aguarde um minuto e tente outra vez."
            else -> error.message ?: "Falha na API do JMD."
        }
        else -> error.message ?: "Não foi possível acessar o JMD. Confira se o computador está na mesma rede e com a tela de pareamento aberta."
    }

    private fun checkForUpdate() {
        updateButton.isEnabled = false
        updateStatus.text = "Consultando a release no GitHub…"
        backgroundExecutor.execute {
            try {
                val release = fetchLatestRelease()
                val isNewer = compareVersions(release.tag, BuildConfig.VERSION_NAME) > 0
                runOnUiThread {
                    updateButton.isEnabled = true
                    if (isNewer) {
                        availableUpdate = release
                        updateButton.text = "Baixar e instalar ${release.tag}"
                        updateStatus.text = "A versão ${release.tag} está disponível. Toque abaixo para baixar e iniciar a instalação."
                    } else {
                        availableUpdate = null
                        updateButton.text = "Verificar novamente"
                        updateStatus.text = "Você está na versão mais recente (${BuildConfig.VERSION_NAME})."
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateStatus.text = "Não foi possível consultar o GitHub. Verifique sua conexão."
                }
            }
        }
    }

    private fun fetchLatestRelease(): GithubRelease {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "JMDroid-Android")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("GitHub respondeu ${connection.responseCode}")
            }
            val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.getString("tag_name")
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name") == APK_ASSET_NAME }
                ?: throw IllegalStateException("A release não contém $APK_ASSET_NAME")
            val downloadUrl = asset.getString("browser_download_url")
            if (!downloadUrl.startsWith(RELEASE_DOWNLOAD_PREFIX)) {
                throw IllegalStateException("URL de download inesperada")
            }
            val digest = asset.optString("digest")
                .takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
                ?: throw IllegalStateException("A release não publicou o SHA-256 do APK")
            return GithubRelease(tag, downloadUrl, digest)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAndInstall(release: GithubRelease) {
        updateButton.isEnabled = false
        updateStatus.text = "Baixando ${release.tag}…"
        backgroundExecutor.execute {
            try {
                val apk = downloadApk(release)
                runOnUiThread {
                    pendingApk = apk
                    updateStatus.text = "Download concluído. Preparando instalação…"
                    requestInstallPermissionOrInstall(apk)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateStatus.text = "Não foi possível baixar a atualização. Tente novamente."
                }
            }
        }
    }

    private fun downloadApk(release: GithubRelease): File {
        val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JMDroid-Android")
        }
        val updateDirectory = File(cacheDir, "updates").apply { mkdirs() }
        val apkFile = File(updateDirectory, "jmdroid-${release.tag}.apk")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("Download respondeu ${connection.responseCode}")
            }
            if (connection.contentLength.toLong() > MAX_APK_BYTES) throw IllegalStateException("APK grande demais")

            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) throw IllegalStateException("APK grande demais")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (apkFile.length() == 0L) throw IllegalStateException("APK vazio")
            val actualDigest = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (!actualDigest.equals(release.sha256, ignoreCase = true)) {
                throw SecurityException("O checksum do APK não corresponde à release")
            }
            return apkFile
        } catch (error: Exception) {
            apkFile.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun requestInstallPermissionOrInstall(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Permitir instalação do JMDroid")
                .setMessage("Ative ‘Permitir desta fonte’ nas configurações para instalar atualizações baixadas do GitHub.")
                .setNegativeButton("Cancelar") { _, _ ->
                    updateStatus.text = "A atualização foi baixada e pode ser instalada depois."
                    updateButton.isEnabled = true
                }
                .setPositiveButton("Abrir configurações") { _, _ ->
                    val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                    startActivityForResult(settings, INSTALL_SOURCE_REQUEST)
                }
                .show()
            return
        }
        launchPackageInstaller(apk)
    }

    private fun launchPackageInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.fileprovider", apk)
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        startActivity(install)
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = versionParts(left)
        val rightParts = versionParts(right)
        for (index in 0 until maxOf(leftParts.size, rightParts.size)) {
            val comparison = (leftParts.getOrElse(index) { 0 }).compareTo(rightParts.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun versionParts(version: String): List<Int> = version
        .trim()
        .removePrefix("v")
        .split(".")
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

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

        addView(label("Só o texto informado por você é enviado ao JMD pareado.", textMuted, 13f).apply {
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

    private companion object {
        const val LATEST_RELEASE_API = "https://api.github.com/repos/deggau/jmdDroid/releases/latest"
        const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/deggau/jmdDroid/releases/download/"
        const val APK_ASSET_NAME = "jmdroid.apk"
        const val MAX_APK_BYTES = 100L * 1024L * 1024L
        const val INSTALL_SOURCE_REQUEST = 7101
        const val QR_SCAN_REQUEST = 7102
    }
}
