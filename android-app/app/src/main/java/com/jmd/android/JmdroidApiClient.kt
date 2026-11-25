package com.jmd.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HttpsURLConnection

internal fun validateLocalBaseUrl(baseUrl: String): java.net.URI {
    val url = java.net.URI(baseUrl)
    require((url.scheme.equals("http", ignoreCase = true) || url.scheme.equals("https", ignoreCase = true)) &&
        url.host != null && url.port in 1..65535 && url.userInfo == null &&
        url.rawPath.isNullOrEmpty() && url.rawQuery == null && url.rawFragment == null) {
        "O endereço HTTP ou HTTPS do QR code é inválido."
    }
    val address = url.host.split('.').map { it.toIntOrNull() ?: -1 }
    require(address.size == 4 && address.all { it in 0..255 } && url.host == address.joinToString(".") &&
        address[0] in 1..223 &&
        (address[0] == 10 || (address[0] == 172 && address[1] in 16..31) ||
            (address[0] == 192 && address[1] == 168))) {
        "O JMD precisa estar em um endereço IPv4 privado da rede local."
    }
    return url
}

internal data class PairingQr(
    val baseUrl: String,
    val certificateSha256: String
)

internal data class PairedJmd(
    val baseUrl: String,
    val certificateSha256: String,
    val deviceId: String,
    val accessToken: String,
    val computerName: String? = null,
    val serverPeerId: String? = null,
    val serverPublicKey: String? = null
)
internal data class RemoteCommand(
    val id: String,
    val name: String,
    val description: String,
    val shortcut: String,
    val available: Boolean,
    val unavailableReason: String?,
    val isWorkflow: Boolean = false,
    val inputVariables: List<String> = emptyList(),
    val iconDataUrl: String? = null,
    val voicePhrases: List<String> = emptyList()
)

internal data class JmdroidTaskChecklistEntry(
    val title: String,
    val weight: Double,
    val isCompleted: Boolean
)

internal data class JmdroidTask(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: String,
    val completedAt: String?,
    val taskNumber: Int,
    val reminderIntervalMinutes: Int?,
    val categoryColor: String,
    val lastReminderAt: String?,
    val urls: String,
    val attachments: String,
    val pausedAt: String?,
    val pauseReason: String,
    val pausedBy: String,
    val checklist: List<JmdroidTaskChecklistEntry>
)

internal data class JmdroidTaskHistoryEntry(
    val id: String,
    val occurredAt: String,
    val commandName: String,
    val beforeValue: String,
    val afterValue: String,
    val source: String,
    val sourceDeviceName: String?
)

internal data class JmdroidTaskHistoryPage(
    val items: List<JmdroidTaskHistoryEntry>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int
)

internal data class JmdroidTaskInput(
    val title: String,
    val description: String = "",
    val reminderIntervalMinutes: Int? = null,
    val categoryColor: String = "Verde",
    val urls: String = "",
    val attachments: String = "",
    val checklist: List<JmdroidTaskChecklistEntry> = emptyList()
)

internal data class JmdroidAttachmentUpload(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long?
)

private fun JSONObject.nullableString(key: String): String? {
    if (isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}

internal class JmdroidApiClient(private val paired: PairedJmd) {
    init {
        validateLocalBaseUrl(paired.baseUrl)
    }

    private val trustManager = if (paired.baseUrl.startsWith("https://", ignoreCase = true)) {
        PinnedCertificateTrustManager(paired.certificateSha256)
    } else null
    private val sslContext = trustManager?.let { manager ->
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(manager), null) }
    }
    private val httpClient = OkHttpClient.Builder().apply {
        if (trustManager != null && sslContext != null) {
            sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        connectTimeout(10, TimeUnit.SECONDS)
        readTimeout(30, TimeUnit.SECONDS)
        followRedirects(false)
        followSslRedirects(false)
    }.build()

    fun connectSession(listener: WebSocketListener): WebSocket {
        val request = Request.Builder()
            .url("${paired.baseUrl.trimEnd('/')}/api/v1/session")
            .header("Authorization", "Bearer ${paired.accessToken}")
            .build()
        return httpClient.newWebSocket(request, listener)
    }

    fun getCommands(query: String = ""): List<RemoteCommand> {
        val params = mutableListOf("includeUnavailable=true")
        if (query.isNotBlank()) params += "q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val suffix = "?${params.joinToString("&")}"
        val response = request("GET", "/api/v1/commands$suffix")
        val items = response.getJSONArray("items")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val schema = item.optJSONObject("inputSchema")
            val variableProperties = schema?.optJSONObject("properties")?.optJSONObject("variables")
                ?.optJSONObject("properties")
            val variables = variableProperties?.keys()?.asSequence()?.toList().orEmpty()
            val workflow = variableProperties != null || item.optString("unavailableReason")
                .contains("execução remota", ignoreCase = true)
            RemoteCommand(
                item.getString("id"), item.getString("name"), item.optString("description"),
                item.optString("shortcut"), item.optBoolean("available"), item.optString("unavailableReason").takeIf { it.isNotBlank() },
                workflow, variables,
                item.optString("icon").takeIf { it.startsWith("data:image/png;base64,") },
                item.optJSONArray("voicePhrases")?.let { phrases ->
                    (0 until phrases.length()).mapNotNull { phrases.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
            )
        }
    }

    fun healthCheck() {
        request("GET", "/health")
    }

    fun getTasks(status: String = "open"): List<JmdroidTask> {
        val response = request("GET", "/api/v1/tasks?status=${java.net.URLEncoder.encode(status, "UTF-8")}")
        val items = response.getJSONArray("items")
        return (0 until items.length()).map { parseTask(items.getJSONObject(it)) }
    }

    fun getTask(taskId: String): JmdroidTask = parseTask(request("GET", "/api/v1/tasks/${encodePath(taskId)}"))

    fun createTask(input: JmdroidTaskInput): JmdroidTask = parseTask(
        request("POST", "/api/v1/tasks", taskInputJson(input))
    )

    fun updateTask(taskId: String, input: JmdroidTaskInput): JmdroidTask = parseTask(
        request("PUT", "/api/v1/tasks/${encodePath(taskId)}", taskInputJson(input))
    )

    fun setTaskChecklistStep(taskId: String, index: Int, isCompleted: Boolean): JmdroidTask = parseTask(
        request("PUT", "/api/v1/tasks/${encodePath(taskId)}/checklist/$index", JSONObject().put("isCompleted", isCompleted))
    )

    fun setTaskCompleted(taskId: String, completed: Boolean): JmdroidTask = parseTask(
        request("POST", "/api/v1/tasks/${encodePath(taskId)}/${if (completed) "complete" else "reopen"}")
    )

    fun pauseTask(taskId: String, reason: String, responsible: String): JmdroidTask = parseTask(
        request("POST", "/api/v1/tasks/${encodePath(taskId)}/pause", JSONObject().put("reason", reason).put("responsible", responsible))
    )

    fun unpauseTask(taskId: String): JmdroidTask = parseTask(
        request("POST", "/api/v1/tasks/${encodePath(taskId)}/unpause", JSONObject())
    )

    fun deleteTask(taskId: String) {
        request("DELETE", "/api/v1/tasks/${encodePath(taskId)}")
    }

    fun uploadTaskAttachments(
        taskId: String,
        contentResolver: ContentResolver,
        files: List<JmdroidAttachmentUpload>
    ): JmdroidTask {
        require(files.isNotEmpty() && files.size <= MAX_ATTACHMENT_FILES) { "Selecione de 1 a $MAX_ATTACHMENT_FILES arquivos." }
        files.forEach { file ->
            require(file.sizeBytes == null || file.sizeBytes in 1..MAX_SINGLE_ATTACHMENT_BYTES) {
                "Cada arquivo deve ter até 20 MiB."
            }
        }
        require(files.mapNotNull { it.sizeBytes }.sum() <= MAX_TOTAL_ATTACHMENT_BYTES) {
            "O tamanho total dos anexos deve ser de até 24 MiB."
        }

        val boundary = "JMDroid-${java.util.UUID.randomUUID()}"
        val connection = (URL("${paired.baseUrl.trimEnd('/')}/api/v1/tasks/${encodePath(taskId)}/attachments").openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = requireNotNull(sslContext) { "A configuração HTTPS do JMD está incompleta." }.socketFactory
            }
            connectTimeout = 10_000
            readTimeout = 120_000
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${paired.accessToken}")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            instanceFollowRedirects = false
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
        }
        try {
            connection.outputStream.use { output ->
                var totalBytes = 0L
                files.forEach { file ->
                    val safeName = file.fileName.substringAfterLast('/').substringAfterLast('\\')
                        .replace("\"", "_").replace("\r", "_").replace("\n", "_").ifBlank { "anexo" }
                    val mime = file.mimeType.takeIf { it.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")) }
                        ?: "application/octet-stream"
                    output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\n".toByteArray(Charsets.UTF_8))
                    output.write("Content-Type: $mime\r\n\r\n".toByteArray(Charsets.UTF_8))
                    val input = contentResolver.openInputStream(file.uri)
                        ?: throw IllegalArgumentException("Não foi possível abrir ${file.fileName}.")
                    input.use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        var fileBytes = 0L
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            totalBytes += count
                            require(fileBytes <= MAX_SINGLE_ATTACHMENT_BYTES) { "${file.fileName} excede o limite de 20 MiB." }
                            require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) { "O tamanho total dos anexos excede 24 MiB." }
                            output.write(buffer, 0, count)
                        }
                        require(fileBytes > 0) { "${file.fileName} está vazio." }
                    }
                    output.write("\r\n".toByteArray(Charsets.UTF_8))
                }
                output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.let(::JSONObject) ?: JSONObject()
            if (status !in 200..299) {
                val error = json.optJSONObject("error")
                throw ApiException(status, error?.optString("code").orEmpty(), error?.optString("message")?.takeIf { it.isNotBlank() } ?: "A API do JMD respondeu HTTP $status.")
            }
            return parseTask(json.getJSONObject("task"))
        } finally {
            connection.disconnect()
        }
    }

    fun getTaskHistory(query: String, page: Int = 0, pageSize: Int = 30): JmdroidTaskHistoryPage {
        val suffix = "?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page&pageSize=$pageSize"
        val response = request("GET", "/api/v1/tasks/history$suffix")
        val items = response.getJSONArray("items").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                JmdroidTaskHistoryEntry(
                    id = item.optString("id"),
                    occurredAt = item.optString("occurredAt"),
                    commandName = item.optString("commandName"),
                    beforeValue = item.optString("beforeValue"),
                    afterValue = item.optString("afterValue"),
                    source = item.optString("source"),
                    sourceDeviceName = item.nullableString("sourceDeviceName")
                )
            }
        }
        return JmdroidTaskHistoryPage(items, response.optInt("totalCount"), response.optInt("page"), response.optInt("pageSize"))
    }

    fun execute(commandId: String, input: JSONObject): JSONObject {
        require(RemoteCommandIdPolicy.isValid(commandId)) { "ID de comando inválido." }
        val body = JSONObject().put("requestId", java.util.UUID.randomUUID().toString()).put("input", input)
        return request("POST", RemoteCommandIdPolicy.executePath(commandId), body)
    }

    fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("${paired.baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = requireNotNull(sslContext) { "A configuração HTTPS do JMD está incompleta." }.socketFactory
            }
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = method
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${paired.accessToken}")
            instanceFollowRedirects = false
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val json = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.let(::JSONObject) ?: JSONObject()
            if (status !in 200..299) {
                val error = json.optJSONObject("error")
                throw ApiException(status, error?.optString("code").orEmpty(), error?.optString("message")?.takeIf { it.isNotBlank() } ?: "A API do JMD respondeu HTTP $status.")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun taskInputJson(input: JmdroidTaskInput): JSONObject = JSONObject()
        .put("title", input.title)
        .put("description", input.description)
        .put("reminderIntervalMinutes", input.reminderIntervalMinutes ?: JSONObject.NULL)
        .put("categoryColor", input.categoryColor)
        .put("urls", input.urls)
        .put("attachments", input.attachments)
        .put("checklist", org.json.JSONArray().apply {
            input.checklist.forEach { step ->
                put(JSONObject().put("title", step.title).put("weight", step.weight).put("isCompleted", step.isCompleted))
            }
        })

    private fun parseTask(json: JSONObject): JmdroidTask {
        val checklistJson = json.optJSONArray("checklist")
        val checklist = if (checklistJson == null) emptyList() else (0 until checklistJson.length()).map { index ->
            val item = checklistJson.getJSONObject(index)
            JmdroidTaskChecklistEntry(item.optString("title"), item.optDouble("weight"), item.optBoolean("isCompleted"))
        }
        return JmdroidTask(
            id = json.optString("id"),
            title = json.optString("title"),
            description = json.optString("description"),
            createdAt = json.optString("createdAt"),
            completedAt = json.nullableString("completedAt"),
            taskNumber = json.optInt("taskNumber"),
            reminderIntervalMinutes = if (json.isNull("reminderIntervalMinutes")) null else json.optInt("reminderIntervalMinutes"),
            categoryColor = json.optString("categoryColor", "Verde"),
            lastReminderAt = json.nullableString("lastReminderAt"),
            urls = json.optString("urls"),
            attachments = json.optString("attachments"),
            pausedAt = json.nullableString("pausedAt"),
            pauseReason = json.optString("pauseReason"),
            pausedBy = json.optString("pausedBy"),
            checklist = checklist
        )
    }

    private fun encodePath(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val MAX_ATTACHMENT_FILES = 10
        private const val MAX_SINGLE_ATTACHMENT_BYTES = 20L * 1024L * 1024L
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 24L * 1024L * 1024L

        fun parsePairingQr(raw: String): PairingQr {
            val qr = JSONObject(raw.trim())
            require(qr.optString("type") == "jmd-pairing" && qr.optInt("version") == 1) { "Este QR code não é um pareamento JMD compatível." }
            val baseUrl = qr.getString("baseUrl").trimEnd('/')
            val url = validateLocalBaseUrl(baseUrl)
            val isHttps = url.scheme.equals("https", ignoreCase = true)
            val fingerprint = if (isHttps) qr.optString("certificateSha256").replace(":", "").uppercase() else ""
            if (isHttps) {
                require(fingerprint.matches(Regex("[0-9A-F]{64}"))) { "A impressão digital do certificado no QR code é inválida." }
            }
            return PairingQr(baseUrl, fingerprint)
        }

    }
}

internal class ApiException(val status: Int, val code: String, message: String) : IllegalStateException(message)

internal class PinnedCertificateTrustManager(private val expectedSha256: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate = chain.firstOrNull() ?: throw CertificateException("O JMD não apresentou certificado HTTPS.")
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), expectedSha256.toByteArray(Charsets.US_ASCII))) {
            throw CertificateException("A impressão digital HTTPS não corresponde ao QR code do JMD.")
        }
    }
}
