package com.jmd.android

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.media.projection.MediaProjectionManager
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
import android.widget.TextView
import androidx.core.content.FileProvider
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.util.Base64
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
            if (!isFinishing && ::credentialStore.isInitialized && credentialStore.loadAll().isNotEmpty()) {
                messageHandler.postDelayed(this, 10_000)
            }
        }
    }
    private lateinit var credentialStore: JmdroidCredentialStore
    private lateinit var connectionStatus: TextView
    private lateinit var computerList: LinearLayout
    private lateinit var pairingButton: Button
    private lateinit var commandContainer: LinearLayout
    private lateinit var commandSearch: EditText
    private lateinit var commandPanel: View
    private lateinit var tasksPanel: LinearLayout
    private lateinit var tasksContainer: LinearLayout
    private lateinit var taskSearch: EditText
    private lateinit var taskStatusText: TextView
    private val taskFilterButtons = linkedMapOf<String, Button>()
    private var taskHistoryFilterButton: Button? = null
    private lateinit var commandsTab: Button
    private lateinit var tasksTab: Button
    private var allCommands: List<RemoteCommand> = emptyList()
    private var allTasks: List<JmdroidTask> = emptyList()
    private var taskStatus = "open"
    private var showingTaskHistory = false
    private var taskHistoryPage = 0
    private var taskHistoryItems: List<JmdroidTaskHistoryEntry> = emptyList()
    private var taskHistoryTotal = 0
    private var taskAttachmentPickerCallback: ((List<JmdroidAttachmentUpload>) -> Unit)? = null
    private var pairedJmd: PairedJmd? = null
    private val computerOnline = mutableMapOf<String, Boolean>()
    private var jmdApiClient: JmdroidApiClient? = null
    private var jmdWebSocket: WebSocket? = null
    private var jmdSessionReady = false
    private var pendingScreenShare: ScreenShareRequest? = null
    private var serviceReceiverRegistered = false
    private val connectionServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                JmdroidConnectionService.ACTION_CONNECTION_STATE -> {
                    val connected = intent.getBooleanExtra(JmdroidConnectionService.EXTRA_CONNECTED, false)
                    jmdSessionReady = connected
                    val message = intent.getStringExtra(JmdroidConnectionService.EXTRA_MESSAGE).orEmpty()
                    if (!connected && credentialStore.load() == null) {
                        pairedJmd = null
                        refreshConnectionIndicator(false)
                        renderComputerList()
                        return
                    }
                    refreshConnectionIndicator(connected)
                    connectionStatus.text = message
                    if (connected && allCommands.isEmpty()) loadRemoteCommands()
                }
                JmdroidConnectionService.ACTION_SCREEN_SHARE_REQUEST -> {
                    requestScreenShareConsent(intent.toScreenShareRequest())
                }
                JmdroidConnectionService.ACTION_DESKTOP_SCREEN_SHARE_REQUEST -> {
                    val shareId = intent.getStringExtra(JmdroidConnectionService.EXTRA_SHARE_ID).orEmpty()
                    if (shareId.isNotBlank()) {
                        startActivity(Intent(this@MainActivity, DesktopScreenViewerActivity::class.java)
                            .putExtra(JmdroidConnectionService.EXTRA_SHARE_ID, shareId))
                    }
                }
            }
        }
    }
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
            requestScreenShareNotificationPermission()
        }
        if (pairedJmd == null && credentialStore.loadAll().isNotEmpty()) scheduleHealthChecks()
        handleScreenShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(JmdroidConnectionService.ACTION_CONNECTION_STATE)
            addAction(JmdroidConnectionService.ACTION_SCREEN_SHARE_REQUEST)
            addAction(JmdroidConnectionService.ACTION_DESKTOP_SCREEN_SHARE_REQUEST)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(connectionServiceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(connectionServiceReceiver, filter)
        }
        serviceReceiverRegistered = true
    }

    override fun onStop() {
        if (serviceReceiverRegistered) {
            unregisterReceiver(connectionServiceReceiver)
            serviceReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScreenShareIntent(intent)
    }

    override fun onDestroy() {
        messageHandler.removeCallbacks(hideAppMessage)
        messageHandler.removeCallbacks(healthCheckRunnable)
        messageHandler.removeCallbacks(taskHistorySearchRunnable)
        backgroundExecutor.shutdownNow()
        healthExecutor.shutdownNow()
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
        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            val request = pendingScreenShare
            pendingScreenShare = null
            if (request != null) {
                if (resultCode == RESULT_OK && data != null) {
                    JmdroidConnectionService.grantProjection(this, request.shareId, resultCode, data)
                    if (Build.VERSION.SDK_INT >= 24 && !JmdroidRemoteInputAccessibilityService.isEnabled(this)) {
                        showRemoteInputAccessibilitySettings()
                    }
                } else {
                    JmdroidConnectionService.denyProjection(this, request.shareId)
                }
            }
            return
        }
        if (requestCode == TASK_ATTACHMENT_PICKER_REQUEST) {
            val callback = taskAttachmentPickerCallback
            taskAttachmentPickerCallback = null
            if (resultCode == RESULT_OK && callback != null) {
                try {
                    val uris = buildList {
                        data?.clipData?.let { clip ->
                            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
                        }
                        if (isEmpty()) data?.data?.let(::add)
                    }.distinct()
                    callback(uris.map(::describeTaskAttachment))
                } catch (error: Exception) {
                    showBrandedMessage(error.message ?: "Não foi possível ler os arquivos selecionados.", 4_000L)
                }
            }
            return
        }
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

        addView(TextView(this@MainActivity).apply {
            text = "⋮"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(textPrimary)
            contentDescription = "Mais opções"
            setOnClickListener { showSecondaryMenu(this) }
        }, LinearLayout.LayoutParams(dp(44), dp(48)).apply { marginStart = dp(4) })
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
        addView(label("Toque em um computador para alternar. Mantenha pressionado para remover o pareamento.", textMuted, 12f).apply {
            setPadding(0, 0, 0, dp(4))
        })
        computerList = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        addView(computerList, marginParams(top = 0))
        val addComputerButton = Button(this@MainActivity).apply {
            text = "＋  Conectar outro computador"
            textSize = 14f
            isAllCaps = false
            setTextColor(textPrimary)
            background = rounded(surface, border, 12f)
            minHeight = dp(46)
            setOnClickListener { startPairingScanner() }
        }
        addView(addComputerButton)
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
        renderComputerList()
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
            val filterButton = Button(this@MainActivity).apply {
                text = title
                textSize = 11f
                isAllCaps = false
                setOnClickListener {
                    showingTaskHistory = false
                    taskStatus = status
                    updateTaskFilterSelection()
                    messageHandler.removeCallbacks(taskHistorySearchRunnable)
                    taskSearch.hint = "Filtrar tarefas"
                    taskSearch.setText("")
                    taskStatusText.visibility = View.VISIBLE
                    loadTasks()
                }
            }
            taskFilterButtons[status] = filterButton
            filters.addView(filterButton, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
        }
        taskHistoryFilterButton = Button(this@MainActivity).apply {
            text = "Histórico"
            textSize = 11f
            isAllCaps = false
            setOnClickListener {
                showingTaskHistory = true
                updateTaskFilterSelection()
                taskStatusText.visibility = View.VISIBLE
                taskSearch.hint = "Buscar no histórico"
                taskSearch.setText("")
                loadTaskHistory(0)
            }
        }
        filters.addView(taskHistoryFilterButton, LinearLayout.LayoutParams(0, dp(40), 0.9f))
        addView(filters)
        updateTaskFilterSelection()

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
                    taskStatusText.text = "${taskFilterTitle(taskStatus)} · ${tasks.size} tarefa(s)"
                    renderTasks()
                }
            } catch (error: Exception) {
                runOnUiThread { taskStatusText.text = apiErrorMessage(error); renderTasks() }
            }
        }
    }

    private fun updateTaskFilterSelection() {
        taskFilterButtons.forEach { (status, button) ->
            val selected = !showingTaskHistory && taskStatus == status
            button.setTextColor(if (selected) ink else textMuted)
            button.background = rounded(if (selected) accent else surface, if (selected) accent else border, 9f)
        }
        taskHistoryFilterButton?.let { button ->
            button.setTextColor(if (showingTaskHistory) ink else textMuted)
            button.background = rounded(if (showingTaskHistory) accent else surface, if (showingTaskHistory) accent else border, 9f)
        }
    }

    private fun taskFilterTitle(status: String): String = when (status) {
        "completed" -> "Concluídas"
        "all" -> "Todas"
        else -> "Abertas"
    }

    private fun buildTaskHistoryCard(item: JmdroidTaskHistoryEntry): View {
        val isPhone = item.source.equals("JMDroid", ignoreCase = true) || !item.sourceDeviceName.isNullOrBlank()
        val sourceText = if (isPhone) {
            "CELULAR · ${item.sourceDeviceName?.takeIf { it.isNotBlank() } ?: "JMDroid"}"
        } else if (item.source.equals("APP", ignoreCase = true)) {
            "DESKTOP · JMD"
        } else {
            item.source.ifBlank { "ORIGEM DESCONHECIDA" }.uppercase(Locale("pt", "BR"))
        }
        val sourceColor = if (isPhone) Color.rgb(115, 190, 65) else Color.rgb(89, 154, 224)
        val sourceTint = if (isPhone) Color.rgb(44, 67, 34) else Color.rgb(34, 52, 72)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = rounded(surface, border, 11f)

            val heading = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(item.commandName.ifBlank { "Alteração de tarefa" }, textPrimary, 14f,
                    Typeface.create("sans-serif-medium", Typeface.NORMAL)),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = sourceText
                    textSize = 10f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(sourceColor)
                    setPadding(dp(8), dp(5), dp(8), dp(5))
                    background = rounded(sourceTint, sourceTint, 12f)
                    maxLines = 1
                    maxWidth = dp(160)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            addView(heading)
            addView(label(formatTaskHistoryDate(item.occurredAt), textMuted, 11f).apply {
                setPadding(0, dp(5), 0, dp(7))
            })
            if (item.beforeValue.isNotBlank()) {
                addView(label("Antes · ${item.beforeValue}", textMuted, 12f).apply {
                    maxLines = 3
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
            if (item.afterValue.isNotBlank()) {
                addView(label("Depois · ${item.afterValue}", textPrimary, 13f).apply {
                    setPadding(0, if (item.beforeValue.isNotBlank()) dp(4) else 0, 0, 0)
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
    }

    private fun formatTaskHistoryDate(value: String): String {
        val instant = try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: Exception) {
            try {
                Instant.parse(value)
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant()
                } catch (_: Exception) {
                    return value
                }
            }
        }
        return TASK_HISTORY_DATE_FORMAT.format(instant.atZone(ZoneId.systemDefault()))
    }

    private fun renderTasks() {
        if (!::tasksContainer.isInitialized) return
        tasksContainer.removeAllViews()
        if (showingTaskHistory) {
            taskHistoryItems.forEach { item ->
                tasksContainer.addView(buildTaskHistoryCard(item), marginParams(top = 8))
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
                    taskStatusText.text = "Histórico · ${result.totalCount} registro(s)"
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
        val selectedAttachments = mutableListOf<JmdroidAttachmentUpload>()
        form.addView(label("Anexos", textMuted, 12f).apply { setPadding(0, dp(10), 0, dp(2)) })
        val existingAttachments = task?.attachments.orEmpty().lines().map { it.trim() }.filter { it.isNotBlank() }
        if (existingAttachments.isNotEmpty()) {
            val existingNames = existingAttachments.joinToString("\n") { it.substringAfterLast('\\').substringAfterLast('/') }
            form.addView(label("Já enviados:\n$existingNames", textMuted, 12f).apply {
                setLineSpacing(dp(2).toFloat(), 1f)
                setPadding(0, dp(4), 0, dp(6))
            })
        }
        val selectedAttachmentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun renderSelectedAttachments() {
            selectedAttachmentList.removeAllViews()
            if (selectedAttachments.isEmpty()) {
                selectedAttachmentList.addView(label("Nenhum arquivo novo selecionado.", textMuted, 12f))
                return
            }
            selectedAttachments.forEach { attachment ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(attachment.fileName, textPrimary, 13f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "×"
                        textSize = 22f
                        gravity = Gravity.CENTER
                        contentDescription = "Remover ${attachment.fileName}"
                        setTextColor(textMuted)
                        setOnClickListener {
                            selectedAttachments.removeAll { it.uri == attachment.uri }
                            renderSelectedAttachments()
                        }
                    }, LinearLayout.LayoutParams(dp(38), dp(38)))
                }
                selectedAttachmentList.addView(row)
            }
        }
        val chooseAttachmentsButton = Button(this).apply {
            text = "Selecionar arquivos do celular"
            textSize = 13f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(surface, border, 10f)
            minHeight = dp(44)
            setOnClickListener {
                taskAttachmentPickerCallback = { picked ->
                    picked.forEach { attachment ->
                        if (selectedAttachments.any { it.uri == attachment.uri }) return@forEach
                        if (selectedAttachments.size >= MAX_TASK_ATTACHMENT_FILES) {
                            showBrandedMessage("A API aceita até 10 arquivos por envio.")
                            return@forEach
                        }
                        if (attachment.sizeBytes != null && attachment.sizeBytes > MAX_TASK_ATTACHMENT_BYTES) {
                            showBrandedMessage("${attachment.fileName} excede o limite de 20 MiB.", 4_000L)
                            return@forEach
                        }
                        val totalKnownBytes = selectedAttachments.mapNotNull { it.sizeBytes }.sum() + (attachment.sizeBytes ?: 0L)
                        if (totalKnownBytes > MAX_TASK_ATTACHMENT_TOTAL_BYTES) {
                            showBrandedMessage("O total dos anexos deve ser de até 24 MiB.", 4_000L)
                            return@forEach
                        }
                        selectedAttachments += attachment
                    }
                    renderSelectedAttachments()
                }
                val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(picker, TASK_ATTACHMENT_PICKER_REQUEST)
            }
        }
        form.addView(chooseAttachmentsButton, marginParams(top = 4))
        form.addView(selectedAttachmentList, marginParams(top = 4))
        form.addView(label("Até 10 arquivos, 20 MiB por arquivo e 24 MiB por envio.", textMuted, 11f).apply {
            setPadding(0, dp(4), 0, 0)
        })
        renderSelectedAttachments()
        val reminder = field("Lembrete em minutos (opcional)", task?.reminderIntervalMinutes?.toString().orEmpty())
        val categories = listOf("Vermelho", "Laranja", "Amarelo", "Verde")
        var selectedCategory = task?.categoryColor?.takeIf { it in categories } ?: "Verde"
        val categoryChoices = linkedMapOf<String, View>()
        form.addView(label("Prioridade", textMuted, 12f).apply { setPadding(0, dp(10), 0, dp(2)) })
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(8))
        }
        fun colorForCategory(category: String): Int = when (category) {
            "Vermelho" -> Color.rgb(232, 72, 86)
            "Laranja" -> Color.rgb(255, 140, 0)
            "Amarelo" -> Color.rgb(255, 204, 0)
            else -> Color.rgb(76, 175, 80)
        }
        fun refreshCategoryChoices() {
            categoryChoices.forEach { (category, square) ->
                square.background = GradientDrawable().apply {
                    setColor(colorForCategory(category))
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(if (category == selectedCategory) 2 else 1), if (category == selectedCategory) Color.WHITE else border)
                }
                square.isSelected = category == selectedCategory
            }
        }
        categories.forEach { category ->
            val square = View(this).apply {
                contentDescription = "Prioridade $category"
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    selectedCategory = category
                    refreshCategoryChoices()
                }
            }
            categoryChoices[category] = square
            categoryRow.addView(square, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(9) })
        }
        refreshCategoryChoices()
        form.addView(categoryRow)
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
                        val input = JmdroidTaskInput(name, description.text.toString(), interval, selectedCategory, urls.text.toString(), task?.attachments.orEmpty(), entries)
                        val client = jmdApiClient ?: return@setOnClickListener
                        dialog.dismiss()
                        showBrandedMessage(if (selectedAttachments.isEmpty()) "Salvando tarefa…" else "Salvando tarefa e enviando anexos…", 60_000L)
                        backgroundExecutor.execute {
                            try {
                                val savedTask = if (task == null) client.createTask(input) else client.updateTask(task.id, input)
                                val resultMessage = if (selectedAttachments.isEmpty()) {
                                    "Tarefa salva"
                                } else {
                                    try {
                                        client.uploadTaskAttachments(savedTask.id, contentResolver, selectedAttachments.toList())
                                        "Tarefa salva e anexos enviados"
                                    } catch (uploadError: Exception) {
                                        "Tarefa salva, mas o envio dos anexos falhou: ${apiErrorMessage(uploadError)}"
                                    }
                                }
                                runOnUiThread { loadTasks(); showBrandedMessage(resultMessage, 5_000L) }
                            } catch (error: Exception) {
                                runOnUiThread { showBrandedMessage(apiErrorMessage(error), 5_000L) }
                            }
                        }
                    }
                }
            }.show()
    }

    private fun startPairingScanner() {
        startActivityForResult(Intent(this, QrScannerActivity::class.java), QR_SCAN_REQUEST)
    }

    private fun showTaskNotificationSettings() {
        val initial = TaskNotificationSettings.load(this)
        var startMinute = initial.startMinute
        var endMinute = initial.endMinute
        val selectedDays = initial.activeDays.toMutableSet()
        fun formatTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
        fun settingCheckBox(text: String, checked: Boolean) = CheckBox(this).apply {
            this.text = text
            isChecked = checked
            setTextColor(textPrimary)
            textSize = 14f
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            setPadding(0, dp(4), 0, dp(4))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(8))
        }
        val enabled = settingCheckBox("Receber notificações de tarefas novas", initial.enabled)
        content.addView(enabled)
        content.addView(label("Escolha quais cores podem gerar notificações:", textMuted, 13f).apply {
            setPadding(0, dp(8), 0, dp(4))
        })
        val priorityChecks = TaskNotificationSettings.categories.associateWith { category ->
            settingCheckBox(category, category in initial.priorities).also { checkBox ->
                val color = when (category) {
                    "Vermelho" -> Color.rgb(232, 72, 86)
                    "Laranja" -> Color.rgb(255, 140, 0)
                    "Amarelo" -> Color.rgb(255, 204, 0)
                    else -> Color.rgb(76, 175, 80)
                }
                checkBox.buttonTintList = android.content.res.ColorStateList.valueOf(color)
                content.addView(checkBox)
            }
        }
        content.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        })
        val paused = settingCheckBox("Pausar notificações agora", initial.paused)
        content.addView(paused)
        val limitHours = settingCheckBox("Notificar somente dentro de um horário", initial.limitToHours)
        content.addView(limitHours)
        content.addView(label("Dias em que as notificações podem aparecer:", textMuted, 13f).apply {
            setPadding(0, dp(4), 0, dp(2))
        })
        val dayOptions = listOf(
            java.util.Calendar.SUNDAY to "Domingo",
            java.util.Calendar.SATURDAY to "Sábado",
            null to "Dias de semana"
        )
        val dayChecks = dayOptions.associate { (day, title) ->
            title to settingCheckBox(title, if (day == null) {
                selectedDays.containsAll(setOf(
                    java.util.Calendar.MONDAY,
                    java.util.Calendar.TUESDAY,
                    java.util.Calendar.WEDNESDAY,
                    java.util.Calendar.THURSDAY,
                    java.util.Calendar.FRIDAY
                ))
            } else day in selectedDays).also(content::addView)
        }
        dayChecks.getValue("Dias de semana").setOnCheckedChangeListener { _, checked ->
            val weekdays = setOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY, java.util.Calendar.FRIDAY)
            if (checked) selectedDays.addAll(weekdays) else selectedDays.removeAll(weekdays)
        }
        listOf(java.util.Calendar.SUNDAY to "Domingo", java.util.Calendar.SATURDAY to "Sábado").forEach { (day, title) ->
            dayChecks.getValue(title).setOnCheckedChangeListener { _, checked ->
                if (checked) selectedDays.add(day) else selectedDays.remove(day)
            }
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val startTime = Button(this).apply { isAllCaps = false; setTextColor(accent); text = "Início ${formatTime(startMinute)}" }
        val endTime = Button(this).apply { isAllCaps = false; setTextColor(accent); text = "Fim ${formatTime(endMinute)}" }
        startTime.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                startMinute = hour * 60 + minute
                startTime.text = "Início ${formatTime(startMinute)}"
            }, startMinute / 60, startMinute % 60, true).show()
        }
        endTime.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                endMinute = hour * 60 + minute
                endTime.text = "Fim ${formatTime(endMinute)}"
            }, endMinute / 60, endMinute % 60, true).show()
        }
        timeRow.addView(startTime, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(endTime, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(timeRow)
        limitHours.setOnCheckedChangeListener { _, checked -> timeRow.visibility = if (checked) View.VISIBLE else View.GONE }
        timeRow.visibility = if (initial.limitToHours) View.VISIBLE else View.GONE
        content.addView(label("O Android consulta a API em segundo plano aproximadamente a cada 15 minutos. Na primeira consulta, as tarefas existentes são registradas sem gerar notificações.", textMuted, 12f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        })

        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Notificações de tarefas")
            .setView(scroll)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Salvar", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selectedPriorities = priorityChecks.filterValues { it.isChecked }.keys
                        if (enabled.isChecked && selectedPriorities.isEmpty()) {
                            showBrandedMessage("Selecione pelo menos uma prioridade.")
                            return@setOnClickListener
                        }
                        if (enabled.isChecked && limitHours.isChecked && selectedDays.isEmpty()) {
                            showBrandedMessage("Selecione pelo menos um dia da semana.")
                            return@setOnClickListener
                        }
                        val config = TaskNotificationConfig(
                            enabled.isChecked,
                            selectedPriorities,
                            paused.isChecked,
                            limitHours.isChecked,
                            startMinute,
                            endMinute,
                            selectedDays.toSet()
                        )
                        TaskNotificationSettings.save(this, config)
                        if (config.enabled) {
                            TaskNotificationWorker.schedule(this)
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), TASK_NOTIFICATIONS_PERMISSION_REQUEST)
                            } else {
                                showBrandedMessage("Notificações de tarefas ativadas.")
                            }
                        } else {
                            TaskNotificationWorker.cancel(this)
                            showBrandedMessage("Notificações de tarefas desativadas.")
                        }
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    @Deprecated("Use the Activity Result API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SCREEN_SHARE_NOTIFICATIONS_PERMISSION_REQUEST) {
            showBrandedMessage(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "Notificações ativadas para pedidos de compartilhamento."
            } else {
                "Sem notificações, pedidos de compartilhamento recebidos em segundo plano podem não aparecer."
            }, 4_000L)
        }
        if (requestCode == TASK_NOTIFICATIONS_PERMISSION_REQUEST) {
            showBrandedMessage(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                "Permissão de notificações concedida."
            } else {
                "Permita notificações do JMDroid nas configurações do Android."
            }, 4_000L)
        }
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
            menu.add("Notificações de tarefas").setOnMenuItemClickListener {
                showTaskNotificationSettings()
                true
            }
            menu.add("Controle remoto de toques").setOnMenuItemClickListener {
                showRemoteInputAccessibilitySettings()
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
                menu.add("Conectar outro computador").setOnMenuItemClickListener {
                    startPairingScanner()
                    true
                }
                menu.add("Desconectar computador ativo").setOnMenuItemClickListener {
                    disconnectFromJmd()
                    true
                }
            }
            show()
        }
    }

    private fun showRemoteInputAccessibilitySettings() {
        val enabled = JmdroidRemoteInputAccessibilityService.isEnabled(this)
        AlertDialog.Builder(this)
            .setTitle("Controle remoto de toques")
            .setMessage(if (enabled) {
                "Acessibilidade está habilitada. Durante o compartilhamento da tela, o JMD poderá enviar toques e rolagens para este celular. Você pode desativar o serviço a qualquer momento nas configurações do Android."
            } else {
                "O Android não permite conceder Acessibilidade durante a instalação; é necessário ativar manualmente. Para reproduzir cliques e rolagens do computador, abra Acessibilidade e habilite Controle remoto do JMDroid. O compartilhamento da imagem pode continuar sem isso."
            })
            .setNegativeButton("Fechar", null)
            .setPositiveButton(if (enabled) "Abrir Acessibilidade" else "Liberar cliques e rolagem") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { showBrandedMessage("Não foi possível abrir as configurações de Acessibilidade") }
            }
            .show()
    }

    private fun disconnectFromJmd() {
        messageHandler.removeCallbacks(healthCheckRunnable)
        credentialStore.select(null)
        disconnectCurrentSession()
        showBrandedMessage("Computador desconectado")
    }

    private fun disconnectCurrentSession() {
        JmdroidConnectionService.stop(this)
        jmdWebSocket = null
        jmdApiClient?.close()
        jmdApiClient = null
        jmdSessionReady = false
        pairedJmd = null
        allCommands = emptyList()
        allTasks = emptyList()
        taskHistoryItems = emptyList()
        refreshConnectionIndicator(false)
        if (::connectionStatus.isInitialized) connectionStatus.text = "Selecione um computador ou conecte um novo."
        if (::pairingButton.isInitialized) pairingButton.visibility = View.VISIBLE
        if (::commandSearch.isInitialized) commandSearch.visibility = View.GONE
        if (::commandContainer.isInitialized) commandContainer.visibility = View.GONE
        renderComputerList()
    }

    private fun refreshConnectionIndicator(connected: Boolean) {
        pairedJmd?.let { computerOnline[computerId(it)] = connected }
        renderComputerList()
        if (::pairingButton.isInitialized) {
            pairingButton.text = if (pairedJmd == null) "Conectar ao JMD" else "Reconectar ao JMD"
            pairingButton.visibility = if (pairedJmd == null || !connected) View.VISIBLE else View.GONE
        }
        if (::connectionStatus.isInitialized && pairedJmd != null) {
            connectionStatus.visibility = if (connected) View.GONE else View.VISIBLE
        }
        if (::connectionStatus.isInitialized && pairedJmd == null) {
            connectionStatus.text = if (credentialStore.loadAll().isEmpty()) "Nenhum computador conectado."
            else "Selecione um computador para conectar."
        }
    }

    private fun scheduleHealthChecks() {
        messageHandler.removeCallbacks(healthCheckRunnable)
        healthCheckRunnable.run()
    }

    private fun checkJmdHealth() {
        val computers = credentialStore.loadAll()
        if (computers.isEmpty()) return
        healthExecutor.execute {
            val results = computers.map { computer ->
                val client = if (computer == pairedJmd) jmdApiClient ?: JmdroidApiClient(computer) else JmdroidApiClient(computer)
                try {
                    client.healthCheck()
                    computer to true
                } catch (_: Exception) {
                    computer to false
                } finally {
                    if (client !== jmdApiClient) client.close()
                }
            }
            runOnUiThread {
                results.forEach { (computer, online) -> computerOnline[computerId(computer)] = online }
                renderComputerList()
                val activeOnline = pairedJmd?.let { computerOnline[computerId(it)] } == true
                if (activeOnline) connectionStatus.text = "API do computador disponível · toque em um comando para executar."
                else if (pairedJmd != null) connectionStatus.text = "Sem resposta deste computador. Verifique a rede ou tente novamente."
            }
        }
    }

    private fun computerId(paired: PairedJmd) = "${paired.baseUrl.trimEnd('/')}|${paired.certificateSha256.lowercase()}"

    private fun computerAddress(paired: PairedJmd): String = runCatching {
        java.net.URL(paired.baseUrl).host.ifBlank { paired.baseUrl }
    }.getOrDefault(paired.baseUrl)

    private fun computerName(paired: PairedJmd): String = paired.computerName?.takeIf(String::isNotBlank)
        ?: computerAddress(paired)

    private fun renderComputerList() {
        if (!::computerList.isInitialized || !::credentialStore.isInitialized) return
        computerList.removeAllViews()
        val computers = credentialStore.loadAll()
        computers.forEach { computer ->
            val selected = computerId(computer) == pairedJmd?.let(::computerId)
            val onlineState = computerOnline[computerId(computer)]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(if (selected) surface else card, if (selected) accent else border, 12f)
                isClickable = true
                setOnClickListener {
                    if (!selected) selectComputer(computer)
                }
                setOnLongClickListener {
                    confirmRemoveComputer(computer)
                    true
                }
            }
            val dotColor = when (onlineState) {
                true -> Color.rgb(76, 175, 80)
                false -> Color.rgb(229, 83, 83)
                null -> Color.rgb(245, 166, 35)
            }
            row.addView(View(this).apply { background = rounded(dotColor, dotColor, 8f) }, LinearLayout.LayoutParams(dp(9), dp(9)))
            val computerLabel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                addView(label(computerName(computer), textPrimary, 14f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                val address = computerAddress(computer)
                if (address != computerName(computer)) {
                    addView(label(address, textMuted, 11f).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            }
            row.addView(computerLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(when (onlineState) { true -> "Online"; false -> "Offline"; null -> "Verificando" }, if (onlineState == true) dotColor else textMuted, 12f))
            if (selected) row.addView(label("  ATIVO", accent, 10f, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
            computerList.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
        }
    }

    private fun selectComputer(computer: PairedJmd) {
        val current = pairedJmd
        if (current == computer) return
        credentialStore.select(computer)
        pairedJmd = computer
        allCommands = emptyList()
        allTasks = emptyList()
        showingTaskHistory = false
        taskHistoryItems = emptyList()
        taskHistoryTotal = 0
        commandSearch.setText("")
        renderComputerList()
        connectToJmd(computer, loadCommands = true)
        scheduleHealthChecks()
        if (tasksPanel.visibility == View.VISIBLE) loadTasks()
        showBrandedMessage("Conectando a ${computerName(computer)}")
    }

    private fun confirmRemoveComputer(computer: PairedJmd) {
        AlertDialog.Builder(this)
            .setTitle("Remover computador?")
            .setMessage("O pareamento com ${computerName(computer)} será apagado deste celular.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                val wasActive = pairedJmd == computer
                credentialStore.remove(computer)
                computerOnline.remove(computerId(computer))
                if (wasActive) {
                    val next = credentialStore.active()
                    pairedJmd = next
                    if (next == null) disconnectCurrentSession() else connectToJmd(next, loadCommands = true)
                }
                renderComputerList()
            }
            .show()
    }

    private fun describeTaskAttachment(uri: Uri): JmdroidAttachmentUpload {
        var fileName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "anexo"
        var sizeBytes: Long? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0) cursor.getString(nameColumn)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) sizeBytes = cursor.getLong(sizeColumn)
            }
        }
        return JmdroidAttachmentUpload(
            uri = uri,
            fileName = fileName,
            mimeType = contentResolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = sizeBytes
        )
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
                    allCommands = emptyList()
                    allTasks = emptyList()
                    taskHistoryItems = emptyList()
                    taskHistoryTotal = 0
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
        jmdWebSocket = null
        jmdApiClient?.close()
        jmdApiClient = JmdroidApiClient(paired)
        jmdSessionReady = false
        connectionStatus.text = "Conectando à sessão segura do JMD…"
        refreshConnectionIndicator(false)
        JmdroidConnectionService.connect(this, reconnect = true)
        if (loadCommands) loadRemoteCommands()
    }

    private fun handleScreenShareIntent(intent: Intent?) {
        if (intent?.action != JmdroidConnectionService.ACTION_OPEN_SCREEN_SHARE) return
        val request = intent.toScreenShareRequest()
        intent.action = null
        intent.removeExtra(JmdroidConnectionService.EXTRA_SHARE_ID)
        if (request.shareId.isNotBlank()) requestScreenShareConsent(request)
    }

    private fun Intent.toScreenShareRequest() = ScreenShareRequest(
        shareId = getStringExtra(JmdroidConnectionService.EXTRA_SHARE_ID).orEmpty(),
        maxFps = getIntExtra(JmdroidConnectionService.EXTRA_MAX_FPS, 12).coerceIn(1, 60),
        maxDimension = getIntExtra(JmdroidConnectionService.EXTRA_MAX_DIMENSION, 2400).coerceIn(320, 2400),
        maxFrameBytes = getIntExtra(JmdroidConnectionService.EXTRA_MAX_FRAME_BYTES, 2 * 1024 * 1024).coerceIn(64 * 1024, 2 * 1024 * 1024),
        quality = getIntExtra(JmdroidConnectionService.EXTRA_QUALITY, 65).coerceIn(0, 100)
    )

    private fun requestScreenShareConsent(request: ScreenShareRequest) {
        if (pendingScreenShare?.shareId == request.shareId) return
        if (pendingScreenShare != null) {
            JmdroidConnectionService.denyProjection(this, request.shareId)
            return
        }
        pendingScreenShare = request
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(manager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
        } catch (error: Exception) {
            pendingScreenShare = null
            JmdroidConnectionService.denyProjection(this, request.shareId)
            showBrandedMessage(error.message ?: "Não foi possível abrir a autorização de captura.", 4_000L)
        }
    }

    private fun requestScreenShareNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), SCREEN_SHARE_NOTIFICATIONS_PERMISSION_REQUEST)
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
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                background = rounded(surface, border, 10f)
                val icon = ImageView(this@MainActivity).apply {
                    setImageDrawable(commandIcon(command.iconDataUrl))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(card, border, 9f)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
                val details = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(command.name, textPrimary, 15f, Typeface.create("sans-serif-medium", Typeface.NORMAL)))
                    if (command.description.isNotBlank()) {
                        addView(label(command.description, textMuted, 12f).apply { setPadding(0, dp(3), 0, 0) })
                    }
                    if (command.shortcut.isNotBlank()) {
                        addView(label(command.shortcut, accent, 11f).apply { setPadding(0, dp(4), 0, 0) })
                    }
                    if (command.voicePhrases.isNotEmpty()) {
                        addView(label("Voz: ${command.voicePhrases.joinToString(" · ")}", textMuted, 11f).apply { setPadding(0, dp(4), 0, 0) })
                    }
                    if (!command.available) {
                        addView(label(command.unavailableReason ?: "A execução será solicitada ao JMD desktop.", textMuted, 11f)
                            .apply { setPadding(0, dp(5), 0, 0) })
                    }
                }
                addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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

    private fun commandIcon(dataUrl: String?): Drawable {
        val prefix = "data:image/png;base64,"
        if (dataUrl != null && dataUrl.startsWith(prefix) && dataUrl.length <= 400_000) {
            try {
                val bytes = Base64.decode(dataUrl.substring(prefix.length), Base64.DEFAULT)
                if (bytes.size <= 256 * 1024) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return BitmapDrawable(resources, it) }
            } catch (_: Exception) { }
        }
        return getDrawable(R.mipmap.ic_launcher)!!
    }

    private fun showCommandInput(command: RemoteCommand) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val workflowFields = linkedMapOf<String, EditText>()
        val isScreenShare = command.id == "screenShare"
        val isDesktopScreenShare = command.id == "desktopScreenShare"
        val isScreenShareCommand = isScreenShare || isDesktopScreenShare
        val textInput = if (!command.isWorkflow && !isScreenShareCommand) EditText(this).apply {
            hint = "Texto para enviar ao computador"
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            form.addView(this)
        } else null
        if (isScreenShare) {
            form.addView(label("O Android solicitará autorização do sistema antes de compartilhar a tela. Para receber toques e rolagens do computador, habilite Controle remoto de toques no menu secundário.", textMuted, 13f))
        } else if (isDesktopScreenShare) {
            form.addView(label("O JMD enviará a tela principal do computador para este celular. A visualização pode ser interrompida a qualquer momento.", textMuted, 13f))
        } else if (command.isWorkflow) {
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
            .setMessage(when {
                isScreenShare -> "O pedido será enviado ao computador."
                isDesktopScreenShare -> "O pedido será enviado ao computador pareado."
                command.isWorkflow -> "Os dados serão enviados à API para execução no desktop."
                else -> "O texto será enviado à API para execução no desktop."
            })
            .setView(form)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Executar", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val payload = if (isScreenShareCommand) {
                            JSONObject()
                        } else if (command.isWorkflow) {
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
                    if (command.id == "screenShare" && succeeded) {
                        connectionStatus.text = "Pedido enviado. Aguardando autorização do Android…"
                        showBrandedMessage("Pedido enviado ao JMD. Autorize o compartilhamento na tela do Android.", 5_000L)
                    } else if (command.id == "desktopScreenShare" && succeeded) {
                        connectionStatus.text = "Pedido enviado. Abrindo a tela do computador…"
                        showBrandedMessage("Pedido enviado ao JMD. Abrindo o visualizador…", 4_000L)
                    } else {
                        connectionStatus.text = if (succeeded) "${command.name} concluído." else "${command.name} falhou."
                        if (succeeded && output.isNotBlank()) showResultDialog(command.name, output)
                        else if (succeeded) showBrandedMessage("${command.name} concluído.")
                        else AlertDialog.Builder(this).setTitle("Não foi possível executar")
                            .setMessage(error.ifBlank { "O JMD não conseguiu executar o comando." }).setPositiveButton("OK", null).show()
                    }
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
                    pairedJmd?.let(credentialStore::remove)
                    pairedJmd = credentialStore.active()
                    allCommands = emptyList()
                    messageHandler.removeCallbacks(healthCheckRunnable)
                    if (pairedJmd == null) JmdroidConnectionService.stop(this)
                    else {
                        connectToJmd(pairedJmd!!, loadCommands = true)
                        scheduleHealthChecks()
                    }
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
        val TASK_HISTORY_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR"))
        const val LATEST_RELEASE_API = "https://api.github.com/repos/deggau/jmdDroid/releases/latest"
        const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/deggau/jmdDroid/releases/download/"
        const val APK_ASSET_NAME = "jmdroid.apk"
        const val MAX_APK_BYTES = 100L * 1024L * 1024L
        const val INSTALL_SOURCE_REQUEST = 7101
        const val QR_SCAN_REQUEST = 7102
        const val TASK_NOTIFICATIONS_PERMISSION_REQUEST = 7105
        const val SCREEN_CAPTURE_REQUEST = 7106
        const val SCREEN_SHARE_NOTIFICATIONS_PERMISSION_REQUEST = 7107
        const val TASK_ATTACHMENT_PICKER_REQUEST = 7104
        const val MAX_TASK_ATTACHMENT_FILES = 10
        const val MAX_TASK_ATTACHMENT_BYTES = 20L * 1024L * 1024L
        const val MAX_TASK_ATTACHMENT_TOTAL_BYTES = 24L * 1024L * 1024L
    }
}
