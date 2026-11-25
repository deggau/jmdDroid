package com.jmd.android

import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal data class JmdEndpoint(
    val baseUrl: String,
    val certificateSha256: String,
    val computerName: String,
    val appVersion: String,
    val peerId: String,
    val publicKey: String
)

internal class IdentityApprovalRequiredException(message: String) : IllegalStateException(message)
internal class JmdIdentityChangedException(message: String) : IllegalStateException(message)

internal object JmdroidIdentityPairing {
    private const val DISCOVERY_PORT = 47832
    private const val DISCOVERY_WINDOW_MS = 1800L
    private const val KEY_ALIAS = "jmdroid_mobile_identity_v1"
    private const val SIGNATURE_ALGORITHM = "SHA256withRSA/PSS"
    private val random = SecureRandom()

    fun discover(): List<JmdEndpoint> {
        val nonce = randomHex(32)
        val request = JSONObject()
            .put("service", "jmdroid-discovery")
            .put("apiVersion", 1)
            .put("nonce", nonce)
            .toString().toByteArray(Charsets.UTF_8)
        require(request.size <= 512)

        val candidates = linkedMapOf<String, JmdEndpoint>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 150
            val destinations = linkedSetOf<InetAddress>()
            Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { network ->
                if (!runCatching { network.isUp && !network.isLoopback }.getOrDefault(false)) return@forEach
                network.interfaceAddresses.forEach { address ->
                    val ip = address.address as? Inet4Address ?: return@forEach
                    if (ip.isSiteLocalAddress && !ip.isLoopbackAddress) address.broadcast?.let(destinations::add)
                }
            }
            destinations += InetAddress.getByName("255.255.255.255")
            destinations.forEach { destination ->
                runCatching { socket.send(DatagramPacket(request, request.size, destination, DISCOVERY_PORT)) }
            }

            val deadline = SystemClock.elapsedRealtime() + DISCOVERY_WINDOW_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val buffer = ByteArray(8192)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (packet.length <= 0 || packet.length > buffer.size) continue
                val source = packet.address as? Inet4Address ?: continue
                if (!source.isSiteLocalAddress || source.isLoopbackAddress) continue
                runCatching {
                    val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                    if (json.optString("service") != "jmd" || json.optInt("apiVersion") != 1 || json.optString("nonce") != nonce) return@runCatching
                    val port = json.optInt("port")
                    require(port in 1..65535)
                    val scheme = json.getString("scheme").lowercase(Locale.ROOT)
                    require(scheme == "http" || scheme == "https")
                    val publicKey = json.getString("publicKey")
                    val peerId = json.getString("peerId")
                    require(publicKeyPeerId(publicKey) == peerId)
                    val certificateSha256 = json.optString("certificateSha256").replace(":", "").uppercase(Locale.ROOT)
                    if (scheme == "https") require(certificateSha256.matches(Regex("[0-9A-F]{64}")))
                    val signed = listOf(
                        "discovery", nonce, peerId, json.getString("computerName"),
                        port.toString(), scheme, certificateSha256
                    ).joinToString("\n")
                    require(verify(publicKey, signed, json.getString("signature")))
                    val baseUrl = "${scheme}://${source.hostAddress}:${port}"
                    validateLocalBaseUrl(baseUrl)
                    candidates[peerId] = JmdEndpoint(
                        baseUrl, certificateSha256, json.optString("computerName").ifBlank { source.hostAddress },
                        json.optString("appVersion"), peerId, publicKey
                    )
                }
            }
        }
        return candidates.values.sortedBy { it.computerName.lowercase(Locale.ROOT) }
    }

    @Synchronized
    fun pair(
        endpoint: JmdEndpoint,
        deviceName: String,
        appVersion: String,
        allowApproval: Boolean = true,
        status: (String) -> Unit = {}
    ): PairedJmd {
        val url = validateLocalBaseUrl(endpoint.baseUrl)
        val isHttps = url.scheme.equals("https", true)
        val certificate = endpoint.certificateSha256.replace(":", "").uppercase(Locale.ROOT)
        if (isHttps) require(certificate.matches(Regex("[0-9A-F]{64}"))) {
            "A impressão digital do certificado HTTPS do JMD é inválida."
        }

        val mobileIdentity = mobileIdentity()
        val challengeNonce = randomHex(32)
        val identityResponse = request(endpoint.baseUrl, certificate, "GET", "/api/v1/identity/${challengeNonce}")
        val serverPeerId = identityResponse.getString("peerId")
        val serverPublicKey = identityResponse.getString("publicKey")
        require(publicKeyPeerId(serverPublicKey) == serverPeerId && verify(
            serverPublicKey, "identity\n${challengeNonce}\n${serverPeerId}", identityResponse.getString("signature")
        )) { "A identidade RSA do JMD não pôde ser confirmada." }
        if (endpoint.peerId.isNotBlank()) {
            if (serverPeerId != endpoint.peerId || serverPublicKey != endpoint.publicKey) {
                throw JmdIdentityChangedException("A identidade deste JMD mudou desde a descoberta. Faça uma nova busca e confira o computador.")
            }
        }

        val requestId = randomHex(24)
        val timestamp = System.currentTimeMillis() / 1000L
        val safeDeviceName = deviceName.trim().take(80).ifBlank { "Android" }
        val safeAppVersion = appVersion.take(32)
        val signedClaim = "mobile-connect\n${requestId}\n${safeDeviceName}\n${safeAppVersion}\n${mobileIdentity.peerId}\n${timestamp}"
        val claim = JSONObject()
            .put("requestId", requestId)
            .put("deviceName", safeDeviceName)
            .put("appVersion", safeAppVersion)
            .put("peerId", mobileIdentity.peerId)
            .put("publicKey", mobileIdentity.publicKeyPem)
            .put("timestamp", timestamp)
            .put("signature", sign(mobileIdentity.privateKey, signedClaim))
        var response = requestWithStatus(endpoint.baseUrl, certificate, "POST", "/api/v1/pairing/identity-requests", claim)
        if (response.first != 200 && response.first != 202) throw apiError(response.first, response.second)

        var state = response.second
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(5)
        while (true) {
            val approved = validatePairingState(
                state, requestId, mobileIdentity.peerId, serverPeerId, serverPublicKey, certificate, isHttps
            )
            when (approved.state) {
                "approved" -> {
                    require(!approved.deviceId.isNullOrBlank() && approved.deviceId == mobileIdentity.peerId && !approved.accessToken.isNullOrBlank()) {
                        "A resposta de aprovação do JMD não contém as credenciais esperadas."
                    }
                    return PairedJmd(
                        endpoint.baseUrl, certificate, approved.deviceId, approved.accessToken,
                        endpoint.computerName.ifBlank { URL(endpoint.baseUrl).host }, serverPeerId, serverPublicKey
                    )
                }
                "denied" -> throw IllegalStateException("O pedido de pareamento foi negado no JMD.")
                "expired" -> throw IllegalStateException("O pedido de pareamento expirou. Inicie uma nova conexão no JMD.")
                "pending" -> {
                    if (!allowApproval) throw IdentityApprovalRequiredException("Este computador precisa aprovar o JMDroid novamente.")
                    if (SystemClock.elapsedRealtime() >= deadline) throw IllegalStateException("O pedido expirou antes da aprovação. Tente novamente.")
                    status("Aguardando aprovação no JMD desktop…")
                    Thread.sleep(state.optLong("pollAfterMs", 1500).coerceIn(500, 5000))
                    response = requestWithStatus(
                        endpoint.baseUrl, certificate, "GET",
                        "/api/v1/pairing/identity-requests/${java.net.URLEncoder.encode(requestId, "UTF-8")}", null
                    )
                    if (response.first != 200 && response.first != 202) throw apiError(response.first, response.second)
                    state = response.second
                }
                else -> throw IllegalStateException("O JMD retornou um estado de pareamento desconhecido.")
            }
        }
    }

    fun refresh(paired: PairedJmd): PairedJmd {
        val peerId = paired.serverPeerId?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Este pareamento antigo precisa ser refeito para criar uma identidade segura.")
        val publicKey = paired.serverPublicKey?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("A chave pública do JMD não está salva. Faça o pareamento novamente.")
        val endpoint = discover().firstOrNull { it.peerId == peerId } ?: JmdEndpoint(
            paired.baseUrl, paired.certificateSha256, paired.computerName.orEmpty(), "", peerId, publicKey
        )
        if (endpoint.peerId != peerId || endpoint.publicKey != publicKey) {
            throw JmdIdentityChangedException("A identidade RSA salva para este computador mudou. Faça novo pareamento manual.")
        }
        return pair(endpoint, android.os.Build.MODEL.ifBlank { "Android" }, BuildConfig.VERSION_NAME, allowApproval = false)
    }

    private data class PairingState(val state: String, val deviceId: String?, val accessToken: String?)

    private fun validatePairingState(
        json: JSONObject, requestId: String, mobilePeerId: String, expectedServerPeerId: String,
        expectedServerPublicKey: String, expectedCertificate: String, isHttps: Boolean
    ): PairingState {
        val state = json.getString("state")
        require(json.getString("requestId") == requestId && json.getString("devicePeerId") == mobilePeerId) {
            "A resposta de pareamento não corresponde a este aparelho."
        }
        val serverPeerId = json.getString("serverPeerId")
        val serverPublicKey = json.getString("serverPublicKey")
        require(serverPeerId == expectedServerPeerId && serverPublicKey == expectedServerPublicKey &&
            publicKeyPeerId(serverPublicKey) == serverPeerId) {
            "A identidade do JMD na resposta de pareamento não corresponde ao desafio assinado."
        }
        val responseCertificate = json.optString("certificateSha256").replace(":", "").uppercase(Locale.ROOT)
        require(if (isHttps) responseCertificate == expectedCertificate else responseCertificate.isEmpty()) {
            "O certificado TLS retornado pelo JMD mudou durante o pareamento."
        }
        val token = json.optString("accessToken").takeIf { it.isNotBlank() && it != "null" }
        val tokenHash = token?.let { sha256Hex(it.toByteArray(Charsets.UTF_8)).uppercase(Locale.ROOT) }.orEmpty()
        val signed = "mobile-pairing-state\n${requestId}\n${state}\n${mobilePeerId}\n${serverPeerId}\n${tokenHash}"
        require(verify(serverPublicKey, signed, json.getString("signature"))) {
            "A assinatura da resposta de pareamento do JMD é inválida."
        }
        return PairingState(
            state, json.optString("deviceId").takeIf { it.isNotBlank() && it != "null" }, token
        )
    }

    private fun request(baseUrl: String, certificate: String, method: String, path: String): JSONObject {
        val response = requestWithStatus(baseUrl, certificate, method, path, null)
        if (response.first !in 200..299) throw apiError(response.first, response.second)
        return response.second
    }

    private fun requestWithStatus(
        baseUrl: String, certificate: String, method: String, path: String, body: JSONObject?
    ): Pair<Int, JSONObject> {
        val connection = (URL("${baseUrl.trimEnd('/')}${path}").openConnection() as java.net.HttpURLConnection).apply {
            if (this is HttpsURLConnection) sslSocketFactory = pinnedSslContext(certificate).socketFactory
            connectTimeout = 5000
            readTimeout = 10_000
            requestMethod = method
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(status: Int, json: JSONObject): IllegalStateException {
        val error = json.optJSONObject("error")
        val code = error?.optString("code").orEmpty()
        val message = error?.optString("message")?.takeIf(String::isNotBlank)
        return IllegalStateException(message ?: when (code) {
            "identity_key_changed" -> "A identidade salva deste JMD mudou. Confirme o computador e faça novo pareamento."
            "private_network_only" -> "O pareamento só é permitido na rede privada local."
            "rate_limited" -> "Há muitos pedidos aguardando no JMD. Tente novamente em instantes."
            else -> "Falha no pareamento por identidade (HTTP $status)."
        })
    }

    private fun mobileIdentity(): MobileIdentity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .build()
            )
            generator.generateKeyPair()
        }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
            ?: throw IllegalStateException("A chave privada do JMDroid não está disponível no Android Keystore.")
        val publicKey = keyStore.getCertificate(KEY_ALIAS)?.publicKey
            ?: throw IllegalStateException("A chave pública do JMDroid não está disponível no Android Keystore.")
        val encoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
        val pem = "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n"
        return MobileIdentity(publicKeyPeerId(pem), pem, privateKey)
    }

    private data class MobileIdentity(val peerId: String, val publicKeyPem: String, val privateKey: PrivateKey)

    private fun publicKeyPeerId(pem: String): String = sha256Hex(pem.toByteArray(Charsets.UTF_8))

    private fun parsePublicKey(pem: String): PublicKey {
        val base64 = pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("") { it.trim() }
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.decode(base64, Base64.DEFAULT)))
    }

    private fun sign(privateKey: PrivateKey, message: String): String =
        Base64.encodeToString(Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(message.toByteArray(Charsets.UTF_8))
            sign()
        }, Base64.NO_WRAP)

    private fun verify(publicKeyPem: String, message: String, signatureBase64: String): Boolean = runCatching {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(parsePublicKey(publicKeyPem))
            update(message.toByteArray(Charsets.UTF_8))
            verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        }
    }.getOrDefault(false)

    private fun pinnedSslContext(certificate: String): javax.net.ssl.SSLContext {
        require(certificate.matches(Regex("[0-9A-F]{64}"))) {
            "A impressão digital TLS do JMD está ausente ou inválida."
        }
        val manager = PinnedCertificateTrustManager(certificate)
        return javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
