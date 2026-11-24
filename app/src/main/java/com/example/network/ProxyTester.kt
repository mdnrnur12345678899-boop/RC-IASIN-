package com.example.network

import com.example.data.ProxyConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class ProxyTestResult(
    val exitIp: String,
    val latencyMs: Long,
    val handshakeMs: Long,
)

object ProxyTester {

    suspend fun verifyProxy(config: ProxyConfig): Result<ProxyTestResult> = withContext(Dispatchers.IO) {
        runCatching {
            val totalStart = System.currentTimeMillis()
            val tunnelSocket = ProxyHandshake.connectViaProxy(
                config = config,
                targetHost = "api.ipify.org",
                targetPort = 443,
                connectTimeoutMs = 8000,
                readTimeoutMs = 8000
            )
            val handshakeMs = System.currentTimeMillis() - totalStart

            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val sslSocket = sslFactory.createSocket(
                tunnelSocket,
                "api.ipify.org",
                443,
                true
            ) as SSLSocket

            sslSocket.use { tls ->
                tls.soTimeout = 8000
                tls.startHandshake()
                val verified = HttpsURLConnection.getDefaultHostnameVerifier()
                    .verify("api.ipify.org", tls.session)
                if (!verified) {
                    throw ProxyException.ProtocolError("TLS hostname verification failed for exit check.")
                }

                val request = "GET /?format=json HTTP/1.1\r\n" +
                    "Host: api.ipify.org\r\n" +
                    "User-Agent: NetRelay/1.0\r\n" +
                    "Accept: application/json\r\n" +
                    "Connection: close\r\n\r\n"
                tls.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.US_ASCII))
                    flush()
                }

                val reader = BufferedReader(InputStreamReader(tls.getInputStream(), StandardCharsets.UTF_8))
                val fullResponse = reader.readText()
                val body = fullResponse.substringAfter("\r\n\r\n", "").trim()
                val exitIp = extractIp(body)
                    ?: throw ProxyException.ProtocolError("Proxy connected, but exit IP check returned unexpected response.")

                val totalMs = System.currentTimeMillis() - totalStart
                ProxyTestResult(
                    exitIp = exitIp,
                    latencyMs = totalMs,
                    handshakeMs = handshakeMs
                )
            }
        }
    }

    private fun extractIp(body: String): String? {
        if (body.isEmpty()) return null
        val jsonStart = body.indexOf('{')
        val jsonEnd = body.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            val slice = body.substring(jsonStart, jsonEnd + 1)
            val ip = runCatching { JSONObject(slice).optString("ip") }.getOrNull()
            if (!ip.isNullOrBlank()) return ip.trim()
        }
        return null
    }
}
