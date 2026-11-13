package com.jmd.android

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.net.ssl.HttpsURLConnection

internal data class PairingQr(
    val baseUrl: String,
    val certificateSha256: String,
    val sessionId: String,
    val pairingSecret: String
)

internal data class PairedJmd(val baseUrl: String, val certificateSha256: String, val deviceId: String, val accessToken: String)
internal data class RemoteCommand(
    val id: String,
    val name: String,
    val description: String,
    val shortcut: String,
    val available: Boolean,
    val unavailableReason: String?
)

internal class JmdroidApiClient(private val paired: PairedJmd) {
    private val trustManager = PinnedCertificateTrustManager(paired.certificateSha256)
    private val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun connectSession(listener: WebSocketListener): WebSocket {
        val request = Request.Builder()
            .url("${paired.baseUrl.trimEnd('/')}/api/v1/session")
            .header("Authorization", "Bearer ${paired.accessToken}")
            .build()
        return httpClient.newWebSocket(request, listener)
    }

    fun getCommands(query: String = ""): List<RemoteCommand> {
        val suffix = if (query.isBlank()) "" else "?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val response = request("GET", "/api/v1/commands$suffix")
        val items = response.getJSONArray("items")
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            RemoteCommand(
                item.getString("id"), item.getString("name"), item.optString("description"),
                item.optString("shortcut"), item.optBoolean("available"), item.optString("unavailableReason").takeIf { it.isNotBlank() }
            )
        }
    }

    fun execute(commandId: String, text: String): JSONObject {
        require(commandId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "ID de comando inválido." }
        val input = JSONObject().put("text", text)
        val body = JSONObject().put("requestId", java.util.UUID.randomUUID().toString()).put("input", input)
        return request("POST", "/api/v1/commands/$commandId/execute", body)
    }

    fun close() {
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("${paired.baseUrl.trimEnd('/')}$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = sslContext.socketFactory
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

    companion object {
        fun parsePairingQr(raw: String): PairingQr {
            val qr = JSONObject(raw.trim())
            require(qr.optString("type") == "jmd-pairing" && qr.optInt("version") == 1) { "Este QR code não é um pareamento JMD compatível." }
            val baseUrl = qr.getString("baseUrl").trimEnd('/')
            val url = java.net.URI(baseUrl)
            require(url.scheme == "https" && url.host != null && url.port in 1..65535 && url.userInfo == null &&
                url.rawPath.isNullOrEmpty() && url.rawQuery == null && url.rawFragment == null) {
                "O endereço HTTPS do QR code é inválido."
            }
            val address = url.host.split('.').mapNotNull { it.toIntOrNull() }
            require(address.size == 4 && address.all { it in 0..255 } && address[0] in 1..223 &&
                (address[0] == 10 || (address[0] == 172 && address[1] in 16..31) || (address[0] == 192 && address[1] == 168))) {
                "O JMD precisa estar em um endereço IPv4 privado da rede local."
            }
            val fingerprint = qr.getString("certificateSha256").replace(":", "").uppercase()
            require(fingerprint.matches(Regex("[0-9A-F]{64}"))) { "A impressão digital do certificado no QR code é inválida." }
            return PairingQr(baseUrl, fingerprint, qr.getString("sessionId"), qr.getString("pairingSecret"))
        }

        fun pair(qr: PairingQr, deviceName: String, appVersion: String): PairedJmd {
            val trustManager = PinnedCertificateTrustManager(qr.certificateSha256)
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
            val claim = JSONObject()
                .put("sessionId", qr.sessionId)
                .put("pairingSecret", qr.pairingSecret)
                .put("deviceName", deviceName.take(80))
                .put("appVersion", appVersion.take(32))
            val accepted = pairingRequest(qr, sslContext, "POST", "/api/v1/pairing/requests", claim)
            val requestId = accepted.getString("requestId")
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(accepted.optLong("pollAfterMs", 1500).coerceIn(500, 5000))
                val state = pairingRequest(qr, sslContext, "GET", "/api/v1/pairing/requests/${java.net.URLEncoder.encode(requestId, "UTF-8")}", null)
                when (state.optString("state")) {
                    "approved" -> return PairedJmd(qr.baseUrl, qr.certificateSha256, state.getString("deviceId"), state.getString("accessToken"))
                    "denied" -> throw IllegalStateException("O pedido de pareamento foi negado no JMD.")
                    "expired" -> throw IllegalStateException("O QR code expirou. Gere outro no JMD desktop.")
                    "pending" -> Unit
                    else -> throw IllegalStateException("Resposta de pareamento inesperada.")
                }
            }
            throw IllegalStateException("O pedido expirou sem aprovação. Gere outro QR code no JMD desktop.")
        }

        private fun pairingRequest(qr: PairingQr, sslContext: SSLContext, method: String, path: String, body: JSONObject?): JSONObject {
            val connection = (URL("${qr.baseUrl}$path").openConnection() as HttpsURLConnection).apply {
                this.sslSocketFactory = sslContext.socketFactory
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = method
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-JMD-Pairing-Secret", qr.pairingSecret)
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
                    throw IllegalStateException(error?.optString("message")?.takeIf { it.isNotBlank() } ?: "Falha no pareamento (HTTP $status).")
                }
                return json
            } finally { connection.disconnect() }
        }
    }
}

internal class ApiException(val status: Int, val code: String, message: String) : IllegalStateException(message)

private class PinnedCertificateTrustManager(private val expectedSha256: String) : X509TrustManager {
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
