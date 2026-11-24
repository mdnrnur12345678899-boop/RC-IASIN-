package com.example.network

import com.example.data.ProxyConfig
import com.example.data.ProxyProtocol
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

sealed class ProxyException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class AuthFailed(message: String) : ProxyException(message)
    class Unreachable(message: String, cause: Throwable? = null) : ProxyException(message, cause)
    class ProtocolError(message: String) : ProxyException(message)
}

object ProxyHandshake {

    fun connectViaProxy(
        config: ProxyConfig,
        targetHost: String,
        targetPort: Int,
        connectTimeoutMs: Int = 8000,
        readTimeoutMs: Int = 0,
        protectSocket: ((Socket) -> Boolean)? = null,
    ): Socket {
        val socket = Socket()
        try {
            protectSocket?.invoke(socket)
            socket.soTimeout = connectTimeoutMs
            socket.connect(InetSocketAddress(config.host.trim(), config.port), connectTimeoutMs)
            socket.tcpNoDelay = true

            when (config.protocol) {
                ProxyProtocol.SOCKS5 -> performSocks5Handshake(socket, config, targetHost, targetPort)
                ProxyProtocol.HTTP -> performHttpConnectHandshake(socket, config, targetHost, targetPort)
            }

            socket.soTimeout = readTimeoutMs
            return socket
        } catch (e: ProxyException) {
            runCatching { socket.close() }
            throw e
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw ProxyException.Unreachable(
                "Could not connect to ${config.host}:${config.port} (${e.message ?: "Network error"})",
                e
            )
        }
    }

    private fun performSocks5Handshake(
        socket: Socket,
        config: ProxyConfig,
        targetHost: String,
        targetPort: Int,
    ) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val hasAuth = config.username.isNotEmpty() || config.password.isNotEmpty()
        val greeting = if (hasAuth) {
            byteArrayOf(0x05, 0x02, 0x00, 0x02)
        } else {
            byteArrayOf(0x05, 0x01, 0x00)
        }
        out.write(greeting)
        out.flush()

        val methodReply = readExact(input, 2)
        if (methodReply[0] != 0x05.toByte()) {
            throw ProxyException.ProtocolError("Invalid SOCKS5 version byte: ${methodReply[0].toInt() and 0xFF}")
        }
        val selectedMethod = methodReply[1].toInt() and 0xFF
        when (selectedMethod) {
            0x00 -> Unit // No authentication required
            0x02 -> {
                if (!hasAuth) {
                    throw ProxyException.AuthFailed("Proxy requires username and password.")
                }
                val userBytes = config.username.toByteArray(StandardCharsets.UTF_8)
                val passBytes = config.password.toByteArray(StandardCharsets.UTF_8)
                if (userBytes.size > 255 || passBytes.size > 255) {
                    throw ProxyException.AuthFailed("Username or password exceeds 255 bytes.")
                }
                val authReq = ByteArray(3 + userBytes.size + passBytes.size)
                authReq[0] = 0x01
                authReq[1] = userBytes.size.toByte()
                System.arraycopy(userBytes, 0, authReq, 2, userBytes.size)
                authReq[2 + userBytes.size] = passBytes.size.toByte()
                System.arraycopy(passBytes, 0, authReq, 3 + userBytes.size, passBytes.size)
                out.write(authReq)
                out.flush()

                val authResp = readExact(input, 2)
                if (authResp[1] != 0x00.toByte()) {
                    throw ProxyException.AuthFailed("SOCKS5 authentication failed: invalid username or password.")
                }
            }
            0xFF -> throw ProxyException.AuthFailed("Proxy rejected authentication methods (requires valid credentials).")
            else -> throw ProxyException.ProtocolError("Unsupported SOCKS5 auth method: $selectedMethod")
        }

        val ipv4Bytes = parseIpv4Literal(targetHost)
        val connectPacket = if (ipv4Bytes != null) {
            ByteArray(10).apply {
                this[0] = 0x05
                this[1] = 0x01 // CONNECT
                this[2] = 0x00
                this[3] = 0x01 // IPv4
                System.arraycopy(ipv4Bytes, 0, this, 4, 4)
                this[8] = ((targetPort ushr 8) and 0xFF).toByte()
                this[9] = (targetPort and 0xFF).toByte()
            }
        } else {
            val hostBytes = targetHost.toByteArray(StandardCharsets.UTF_8)
            if (hostBytes.size > 255) {
                throw ProxyException.ProtocolError("Target hostname too long.")
            }
            ByteArray(7 + hostBytes.size).apply {
                this[0] = 0x05
                this[1] = 0x01 // CONNECT
                this[2] = 0x00
                this[3] = 0x03 // DOMAINNAME
                this[4] = hostBytes.size.toByte()
                System.arraycopy(hostBytes, 0, this, 5, hostBytes.size)
                this[5 + hostBytes.size] = ((targetPort ushr 8) and 0xFF).toByte()
                this[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
            }
        }
        out.write(connectPacket)
        out.flush()

        val replyHeader = readExact(input, 4)
        if (replyHeader[0] != 0x05.toByte()) {
            throw ProxyException.ProtocolError("Invalid SOCKS5 connect reply version.")
        }
        val repCode = replyHeader[1].toInt() and 0xFF
        if (repCode != 0x00) {
            throw ProxyException.ProtocolError("SOCKS5 tunnel refused target ($targetHost:$targetPort), code=$repCode")
        }

        when (val atyp = replyHeader[3].toInt() and 0xFF) {
            0x01 -> readExact(input, 6) // IPv4 (4) + port (2)
            0x03 -> {
                val len = readExact(input, 1)[0].toInt() and 0xFF
                readExact(input, len + 2)
            }
            0x04 -> readExact(input, 18) // IPv6 (16) + port (2)
            else -> throw ProxyException.ProtocolError("Unknown SOCKS5 address type: $atyp")
        }
    }

    private fun performHttpConnectHandshake(
        socket: Socket,
        config: ProxyConfig,
        targetHost: String,
        targetPort: Int,
    ) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        val req = StringBuilder().apply {
            append("CONNECT ").append(targetHost).append(':').append(targetPort).append(" HTTP/1.1\r\n")
            append("Host: ").append(targetHost).append(':').append(targetPort).append("\r\n")
            append("Proxy-Connection: Keep-Alive\r\n")
            if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
                val creds = "${config.username}:${config.password}".toByteArray(StandardCharsets.UTF_8)
                val encoded = encodeBase64(creds)
                append("Proxy-Authorization: Basic ").append(encoded).append("\r\n")
            }
            append("\r\n")
        }.toString()

        out.write(req.toByteArray(StandardCharsets.ISO_8859_1))
        out.flush()

        val headerBytes = ByteArray(8192)
        var pos = 0
        while (pos < headerBytes.size) {
            val b = input.read()
            if (b == -1) throw EOFException("Proxy closed connection during HTTP CONNECT handshake.")
            headerBytes[pos++] = b.toByte()
            if (pos >= 4 &&
                headerBytes[pos - 4] == '\r'.code.toByte() &&
                headerBytes[pos - 3] == '\n'.code.toByte() &&
                headerBytes[pos - 2] == '\r'.code.toByte() &&
                headerBytes[pos - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        val responseText = String(headerBytes, 0, pos, StandardCharsets.ISO_8859_1)
        val statusLine = responseText.lineSequence().firstOrNull()?.trim().orEmpty()
        val parts = statusLine.split(' ', limit = 3)
        val statusCode = parts.getOrNull(1)?.toIntOrNull()
            ?: throw ProxyException.ProtocolError("Malformed HTTP proxy response: $statusLine")

        when {
            statusCode == 407 -> throw ProxyException.AuthFailed("HTTP proxy authentication failed (407 Proxy Auth Required).")
            statusCode !in 200..299 -> throw ProxyException.ProtocolError("HTTP proxy error ($statusCode): $statusLine")
        }
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) throw EOFException("Unexpected end of stream from proxy server.")
            offset += read
        }
        return buf
    }

    private fun parseIpv4Literal(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val num = parts[i].toIntOrNull() ?: return null
            if (num !in 0..255) return null
            bytes[i] = num.toByte()
        }
        return runCatching {
            val addr = InetAddress.getByAddress(bytes)
            if (addr is Inet4Address) bytes else null
        }.getOrNull()
    }

    private fun encodeBase64(input: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder(((input.size + 2) / 3) * 4)
        var i = 0
        while (i < input.size) {
            val b0 = input[i++].toInt() and 0xFF
            val hasB1 = i < input.size
            val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
            val hasB2 = i < input.size
            val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            out.append(alphabet[(triple ushr 18) and 0x3F])
            out.append(alphabet[(triple ushr 12) and 0x3F])
            out.append(if (hasB1) alphabet[(triple ushr 6) and 0x3F] else '=')
            out.append(if (hasB2) alphabet[triple and 0x3F] else '=')
        }
        return out.toString()
    }
}
