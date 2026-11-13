package com.jmd.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
    private lateinit var credentialStore: JmdroidCredentialStore
    private lateinit var connectionStatus: TextView
    private lateinit var pairingButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var commandContainer: LinearLayout
    private lateinit var commandSearch: EditText
    private var pairedJmd: PairedJmd? = null
    private var jmdApiClient: JmdroidApiClient? = null
    private var jmdWebSocket: WebSocket? = null
    private var jmdSessionReady = false
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var availableUpdate: GithubRelease? = null
    private var pendingApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        window.decorView.systemUiVisibility = 0

        val details = deviceDetails()
        credentialStore = JmdroidCredentialStore(this)
        pairedJmd = credentialStore.load()
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
        page.addView(buildRemoteCard(), marginParams(top = 16))
        page.addView(buildUpdateCard(), marginParams(top = 16))
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
        checkForUpdate(showCurrent = false)
        pairedJmd?.let { connectToJmd(it, loadCommands = true) }
    }

    override fun onDestroy() {
        backgroundExecutor.shutdownNow()
        jmdWebSocket?.close(1000, "App fechado")
        jmdApiClient?.close()
        super.onDestroy()
    }

    @Deprecated("Use the activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == QR_SCAN_REQUEST) {
            if (resultCode == RESULT_OK) {
                val rawQr = data?.getStringExtra(QR_SCAN_RESULT).orEmpty()
                try {
                    startPairing(JmdroidApiClient.parsePairingQr(rawQr))
                } catch (error: Exception) {
                    AlertDialog.Builder(this)
                        .setTitle("QR code inválido")
                        .setMessage(error.message ?: "O código lido não é um pareamento JMD válido.")
                        .setPositiveButton("Tentar novamente") { _, _ -> showPairingOptions() }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
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

    private fun buildUpdateCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(card, border, 16f)

        addView(label("ATUALIZAÇÕES", accent, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.12f
        })
        addView(label("Versão do GitHub", textPrimary, 18f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            setPadding(0, dp(6), 0, dp(3))
        })
        addView(label("O JMDroid verifica a release mais recente e baixa a atualização quando você solicitar.", textMuted, 13f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })

        updateButton = Button(this@MainActivity).apply {
            text = "Verificar atualizações"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(accent, accent, 12f)
            minHeight = dp(48)
            setOnClickListener {
                val update = availableUpdate
                if (update != null) showUpdateDialog(update) else checkForUpdate(showCurrent = true)
            }
        }
        addView(updateButton, marginParams())

        updateStatus = label("Verificação automática ao abrir o app.", textMuted, 12f).apply {
            setPadding(0, dp(10), 0, 0)
        }
        addView(updateStatus)
    }

    private fun buildRemoteCard(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(card, border, 16f)

        addView(label("CONTROLE PELO JMD", accent, 11f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            letterSpacing = 0.12f
        })
        addView(label("Conectar ao computador", textPrimary, 18f, Typeface.create("sans-serif-medium", Typeface.NORMAL)).apply {
            setPadding(0, dp(6), 0, dp(3))
        })
        addView(label("Pareie com aprovação no JMD desktop para consultar e executar comandos remotos disponíveis.", textMuted, 13f).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        connectionStatus = label("Sem computador pareado.", textMuted, 12f)
        addView(connectionStatus)
        pairingButton = Button(this@MainActivity).apply {
            text = if (pairedJmd == null) "Ler QR code do JMD" else "Parear outro computador"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isAllCaps = false
            setTextColor(ink)
            background = rounded(accent, accent, 12f)
            minHeight = dp(48)
            setOnClickListener { showPairingOptions() }
        }
        addView(pairingButton, marginParams(top = 10))
        reconnectButton = Button(this@MainActivity).apply {
            text = "Conectar novamente"
            textSize = 13f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(surface, border, 12f)
            minHeight = dp(42)
            visibility = if (pairedJmd == null) View.GONE else View.VISIBLE
            setOnClickListener {
                if (jmdSessionReady) loadRemoteCommands(commandSearch.text.toString())
                else pairedJmd?.let { connectToJmd(it, loadCommands = true) }
            }
        }
        addView(reconnectButton, marginParams(top = 6))

        commandSearch = EditText(this@MainActivity).apply {
            hint = "Filtrar comandos"
            setHintTextColor(textMuted)
            setTextColor(textPrimary)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(surface, border, 10f)
        }
        addView(commandSearch, marginParams(top = 16))
        addView(Button(this@MainActivity).apply {
            text = "Carregar comandos"
            textSize = 14f
            isAllCaps = false
            setTextColor(accent)
            background = rounded(surface, border, 12f)
            minHeight = dp(44)
            setOnClickListener { loadRemoteCommands(commandSearch.text.toString()) }
        }, marginParams(top = 8))
        commandContainer = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        addView(commandContainer)
        refreshConnectionStatus()
    }

    private fun showPairingOptions() {
        AlertDialog.Builder(this)
            .setTitle("Parear com o JMD")
            .setItems(arrayOf("Ler QR code com a câmera", "Colar o conteúdo do QR")) { _, choice ->
                if (choice == 0) {
                    startActivityForResult(Intent(this, QrScannerActivity::class.java), QR_SCAN_REQUEST)
                } else {
                    showManualPairingInput()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
                        startPairing(qr)
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
                    pairingButton.text = "Parear outro computador"
                    reconnectButton.visibility = View.VISIBLE
                    connectToJmd(paired, loadCommands = true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    pairingButton.isEnabled = true
                    connectionStatus.text = error.message ?: "Não foi possível parear com o JMD."
                }
            }
        }
    }

    private fun connectToJmd(paired: PairedJmd, loadCommands: Boolean) {
        jmdApiClient = JmdroidApiClient(paired)
        connectionStatus.text = "Conectando à sessão segura do JMD…"
        val socket = try {
            jmdApiClient!!.connectSession(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runOnUiThread {
                        jmdSessionReady = true
                        connectionStatus.text = "Conectado ao JMD · ${paired.baseUrl.substringAfter("//").substringBefore(":")}"
                        if (loadCommands) loadRemoteCommands("")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!text.contains("session.ready")) return
                    runOnUiThread { connectionStatus.text = "Sessão segura ativa no JMD." }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runOnUiThread {
                        jmdSessionReady = false
                        connectionStatus.text = "Sessão do JMD encerrada. Toque em ‘Conectar novamente’ para tentar outra vez."
                    }
                }

                override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                    runOnUiThread {
                        jmdSessionReady = false
                        if (response?.code == 401 || response?.code == 403) {
                            credentialStore.clear()
                            pairedJmd = null
                            pairingButton.text = "Ler QR code do JMD"
                            reconnectButton.visibility = View.GONE
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

    private fun loadRemoteCommands(query: String) {
        val client = jmdApiClient ?: run {
            connectionStatus.text = "Pareie primeiro com o JMD desktop."
            return
        }
        connectionStatus.text = "Carregando comandos…"
        backgroundExecutor.execute {
            try {
                val commands = client.getCommands(query)
                runOnUiThread {
                    commandContainer.removeAllViews()
                    if (commands.isEmpty()) {
                        commandContainer.addView(label("Nenhum comando disponível para essa busca.", textMuted, 13f))
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
                            isClickable = command.available
                            isEnabled = command.available
                            alpha = if (command.available) 1f else 0.55f
                            setOnClickListener { showCommandInput(command) }
                            if (!command.available && !command.unavailableReason.isNullOrBlank()) {
                                setOnLongClickListener {
                                    Toast.makeText(this@MainActivity, command.unavailableReason, Toast.LENGTH_LONG).show()
                                    true
                                }
                            }
                        }
                        commandContainer.addView(item, marginParams(top = 6))
                    }
                    connectionStatus.text = "${commands.size} comando(s) disponível(is). Toque em um para executar."
                }
            } catch (error: Exception) {
                runOnUiThread { connectionStatus.text = apiErrorMessage(error) }
            }
        }
    }

    private fun showCommandInput(command: RemoteCommand) {
        val input = EditText(this).apply {
            hint = "Texto para enviar ao computador"
            setTextColor(textPrimary)
            setHintTextColor(textMuted)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this)
            .setTitle(command.name)
            .setMessage("O texto será enviado ao JMD no computador para executar este comando.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Executar", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (input.text.isEmpty()) {
                            input.error = "Informe o texto para processar."
                            return@setOnClickListener
                        }
                        if (input.text.length > 1_000_000) {
                            input.error = "O limite é 1.000.000 de caracteres."
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        executeRemoteCommand(command, input.text.toString())
                    }
                }
                dialog.show()
            }
    }

    private fun executeRemoteCommand(command: RemoteCommand, text: String) {
        val client = jmdApiClient ?: return
        connectionStatus.text = "Executando ${command.name}…"
        backgroundExecutor.execute {
            try {
                val response = client.execute(command.id, text)
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
                runOnUiThread { connectionStatus.text = apiErrorMessage(error) }
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
                Toast.makeText(this, "Resultado copiado", Toast.LENGTH_SHORT).show()
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
                    if (::pairingButton.isInitialized) pairingButton.text = "Ler QR code do JMD"
                    if (::reconnectButton.isInitialized) reconnectButton.visibility = View.GONE
                }
                if (error.code == "command_unavailable") error.message ?: "Este comando não está disponível agora."
                else "O acesso foi recusado. O JMD pode ter revogado este aparelho; faça o pareamento novamente."
            }
            429 -> "O JMD limitou as solicitações. Aguarde um minuto e tente outra vez."
            else -> error.message ?: "Falha na API do JMD."
        }
        else -> error.message ?: "Não foi possível acessar o JMD. Confira se o computador está na mesma rede e com a tela de pareamento aberta."
    }

    private fun checkForUpdate(showCurrent: Boolean) {
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
                        updateButton.text = "Baixar ${release.tag}"
                        updateStatus.text = "Nova versão disponível: ${release.tag}"
                        showUpdateDialog(release)
                    } else {
                        availableUpdate = null
                        updateButton.text = "Verificar atualizações"
                        updateStatus.text = "Você está na versão mais recente (${BuildConfig.VERSION_NAME})."
                        if (showCurrent) Toast.makeText(this, "JMDroid já está atualizado", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateStatus.text = "Não foi possível consultar o GitHub. Verifique sua conexão."
                    if (showCurrent) Toast.makeText(this, "Falha ao verificar atualizações", Toast.LENGTH_SHORT).show()
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

    private fun showUpdateDialog(release: GithubRelease) {
        AlertDialog.Builder(this)
            .setTitle("JMDroid ${release.tag} disponível")
            .setMessage("Baixar a atualização? O Android exibirá a confirmação para instalar.")
            .setNegativeButton("Depois", null)
            .setPositiveButton("Baixar e instalar") { _, _ -> downloadAndInstall(release) }
            .show()
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
                    Toast.makeText(this, "Falha ao baixar a atualização", Toast.LENGTH_SHORT).show()
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
