package com.example.vpn

import android.os.ParcelFileDescriptor
import com.example.data.ProxyConfig
import com.example.network.ProxyException
import com.example.network.ProxyHandshake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

class TunPacketEngine(
    private val tunFd: ParcelFileDescriptor,
    private val config: ProxyConfig,
    private val protectSocket: (Socket) -> Boolean,
    private val onFatalAuthFailure: (String) -> Unit,
) {
    private data class SessionKey(
        val srcIp: Int,
        val srcPort: Int,
        val dstIp: Int,
        val dstPort: Int,
    )

    private enum class TcpState {
        CONNECTING,
        ESTABLISHED,
        FIN_WAIT,
        CLOSED
    }

    private class TcpSession(
        val key: SessionKey,
        @Volatile var clientNextSeq: Long,
        @Volatile var serverSeq: Long,
    ) {
        @Volatile var state: TcpState = TcpState.CONNECTING
        @Volatile var socket: Socket? = null
        @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        @Volatile var clientFinReceived: Boolean = false
        val writeChannel = Channel<ByteArray>(512)
        val outOfOrder = TreeMap<Long, ByteArray>()
        val seqLock = Any()
    }

    private data class CachedDns(
        val responseWithoutTxId: ByteArray,
        val expiresAtMs: Long,
    )

    private val running = AtomicBoolean(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<SessionKey, TcpSession>()
    private val dnsCache = ConcurrentHashMap<String, CachedDns>()
    private val writeLock = Any()

    private val tunInput = FileInputStream(tunFd.fileDescriptor)
    private val tunOutput = FileOutputStream(tunFd.fileDescriptor)

    private val bytesIn = AtomicLong(0L)
    private val bytesOut = AtomicLong(0L)
    private val authFailureReported = AtomicBoolean(false)

    fun start() {
        // Packet reader loop
        scope.launch {
            val buffer = ByteArray(32767)
            while (running.get() && isActive) {
                val len = try {
                    tunInput.read(buffer)
                } catch (_: Exception) {
                    break
                }
                if (len <= 0) {
                    if (len < 0) break
                    continue
                }
                runCatching {
                    handleIpPacket(buffer, len)
                }
            }
        }

        // 1-second telemetry & stale session reaper
        scope.launch {
            var prevIn = 0L
            var prevOut = 0L
            while (running.get() && isActive) {
                delay(1000L)
                val currIn = bytesIn.get()
                val currOut = bytesOut.get()
                val dlSpeed = (currIn - prevIn).coerceAtLeast(0L)
                val ulSpeed = (currOut - prevOut).coerceAtLeast(0L)
                prevIn = currIn
                prevOut = currOut

                val now = System.currentTimeMillis()
                val iterator = sessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastActivityMs > 120_000L) {
                        closeSession(entry.key, entry.value, sendRst = false)
                    }
                }

                if (dnsCache.size > 256) {
                    dnsCache.entries.removeIf { it.value.expiresAtMs < now }
                }

                TunnelStateHolder.updateTraffic(
                    bytesDownloaded = currIn,
                    bytesUploaded = currOut,
                    downloadSpeedBps = dlSpeed,
                    uploadSpeedBps = ulSpeed,
                    activeSockets = sessions.size
                )
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        sessions.forEach { (key, session) ->
            closeSession(key, session, sendRst = false)
        }
        sessions.clear()
        dnsCache.clear()
        runCatching { scope.cancel() }
        runCatching { tunInput.close() }
        runCatching { tunOutput.close() }
    }

    private fun handleIpPacket(pkt: ByteArray, len: Int) {
        if (len < 20) return
        val version = (pkt[0].toInt() ushr 4) and 0x0F
        if (version == 6) {
            PacketUtils.buildIpv6TcpRst(pkt, len)?.let { rst -> writeToTun(rst) }
            return
        }
        if (version != 4) return

        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl) return

        val totalLen = PacketUtils.readU16(pkt, 2).coerceAtMost(len)
        val proto = pkt[9].toInt() and 0xFF
        val srcIp = PacketUtils.readU32(pkt, 12).toInt()
        val dstIp = PacketUtils.readU32(pkt, 16).toInt()

        when (proto) {
            PacketUtils.PROTO_TCP -> handleTcpPacket(pkt, ihl, totalLen, srcIp, dstIp)
            PacketUtils.PROTO_UDP -> handleUdpPacket(pkt, ihl, totalLen, srcIp, dstIp)
        }
    }

    private fun handleTcpPacket(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 20) return
        val srcPort = PacketUtils.readU16(pkt, ihl)
        val dstPort = PacketUtils.readU16(pkt, ihl + 2)
        val seqNum = PacketUtils.readU32(pkt, ihl + 4)
        val dataOffset = ((pkt[ihl + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || totalLen < ihl + dataOffset) return

        val flags = pkt[ihl + 13].toInt() and 0x3F
        val payloadOffset = ihl + dataOffset
        val payloadLen = totalLen - payloadOffset

        val key = SessionKey(srcIp, srcPort, dstIp, dstPort)
        val isSyn = (flags and PacketUtils.TCP_SYN) != 0
        val isAck = (flags and PacketUtils.TCP_ACK) != 0
        val isFin = (flags and PacketUtils.TCP_FIN) != 0
        val isRst = (flags and PacketUtils.TCP_RST) != 0

        if (isSyn && !isAck) {
            sessions.remove(key)?.let { old ->
                old.state = TcpState.CLOSED
                old.writeChannel.close()
                runCatching { old.socket?.close() }
            }

            val session = TcpSession(
                key = key,
                clientNextSeq = (seqNum + 1L) and 0xFFFFFFFFL,
                serverSeq = Random.nextLong(10_000L, 0x3FFFFFFFL)
            )
            sessions[key] = session
            launchTcpTunnel(session)
            return
        }

        val session = sessions[key] ?: return
        session.lastActivityMs = System.currentTimeMillis()

        if (isRst) {
            closeSession(key, session, sendRst = false)
            return
        }

        if (session.state == TcpState.CLOSED) return

        synchronized(session.seqLock) {
            if (payloadLen > 0) {
                val payload = pkt.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                val diff = seqDiff(seqNum, session.clientNextSeq)

                when {
                    diff == 0L -> {
                        session.writeChannel.trySend(payload)
                        session.clientNextSeq = (session.clientNextSeq + payloadLen) and 0xFFFFFFFFL
                        drainOutOfOrder(session)
                        sendAck(session)
                    }
                    diff < 0L -> {
                        val overlap = (-diff).toInt()
                        if (overlap < payload.size) {
                            val tail = payload.copyOfRange(overlap, payload.size)
                            session.writeChannel.trySend(tail)
                            session.clientNextSeq = (session.clientNextSeq + tail.size) and 0xFFFFFFFFL
                            drainOutOfOrder(session)
                        }
                        sendAck(session)
                    }
                    diff in 1L..65536L -> {
                        if (session.outOfOrder.size < 32) {
                            session.outOfOrder[seqNum] = payload
                        }
                        sendAck(session)
                    }
                }
            }

            if (isFin && !session.clientFinReceived) {
                val finSeq = (seqNum + payloadLen.toLong()) and 0xFFFFFFFFL
                if (seqDiff(finSeq, session.clientNextSeq) <= 0L) {
                    session.clientFinReceived = true
                    session.clientNextSeq = (session.clientNextSeq + 1L) and 0xFFFFFFFFL
                    if (session.state == TcpState.ESTABLISHED) {
                        session.state = TcpState.FIN_WAIT
                    }
                    session.writeChannel.close()
                    sendAck(session)
                }
            }
        }
    }

    private fun drainOutOfOrder(session: TcpSession) {
        while (true) {
            val next = session.outOfOrder.remove(session.clientNextSeq) ?: break
            session.writeChannel.trySend(next)
            session.clientNextSeq = (session.clientNextSeq + next.size) and 0xFFFFFFFFL
        }
    }

    private fun sendAck(session: TcpSession) {
        val ackPkt = PacketUtils.buildTcpPacket(
            srcIp = session.key.dstIp,
            srcPort = session.key.dstPort,
            dstIp = session.key.srcIp,
            dstPort = session.key.srcPort,
            seqNum = session.serverSeq,
            ackNum = session.clientNextSeq,
            flags = PacketUtils.TCP_ACK
        )
        writeToTun(ackPkt)
    }

    private fun launchTcpTunnel(session: TcpSession) {
        val dstHost = PacketUtils.ipIntToString(session.key.dstIp)
        val dstPort = session.key.dstPort

        scope.launch {
            try {
                val socket = ProxyHandshake.connectViaProxy(
                    config = config,
                    targetHost = dstHost,
                    targetPort = dstPort,
                    connectTimeoutMs = 8000,
                    readTimeoutMs = 0,
                    protectSocket = protectSocket
                )
                runCatching { socket.keepAlive = true }

                if (!running.get() || session.state == TcpState.CLOSED) {
                    runCatching { socket.close() }
                    return@launch
                }

                session.socket = socket
                if (session.state == TcpState.CONNECTING) {
                    session.state = TcpState.ESTABLISHED
                }
                session.lastActivityMs = System.currentTimeMillis()

                TunnelStateHolder.log(
                    LogLevel.INFO,
                    "TCP -> $dstHost:$dstPort via ${config.protocol.displayName}"
                )

                // Send SYN-ACK with MSS=1400 to prevent MTU fragmentation
                val synAck = PacketUtils.buildTcpPacket(
                    srcIp = session.key.dstIp,
                    srcPort = session.key.dstPort,
                    dstIp = session.key.srcIp,
                    dstPort = session.key.srcPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientNextSeq,
                    flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
                    mss = 1400
                )
                session.serverSeq = (session.serverSeq + 1L) and 0xFFFFFFFFL
                writeToTun(synAck)

                // Dedicated sequential FIFO writer coroutine for this TCP socket
                val writerJob = launch {
                    val out = socket.getOutputStream()
                    try {
                        for (chunk in session.writeChannel) {
                            out.write(chunk)
                            out.flush()
                            bytesOut.addAndGet(chunk.size.toLong())
                            session.lastActivityMs = System.currentTimeMillis()
                        }
                        // Client closed write side cleanly (FIN)
                        runCatching { socket.shutdownOutput() }
                    } catch (_: Exception) {
                        closeSession(session.key, session, sendRst = true)
                    }
                }

                val input = socket.getInputStream()
                val readBuf = ByteArray(1400)
                while (running.get() && session.state != TcpState.CLOSED) {
                    val n = input.read(readBuf)
                    if (n <= 0) break
                    session.lastActivityMs = System.currentTimeMillis()
                    bytesIn.addAndGet(n.toLong())

                    val dataPkt = PacketUtils.buildTcpPacket(
                        srcIp = session.key.dstIp,
                        srcPort = session.key.dstPort,
                        dstIp = session.key.srcIp,
                        dstPort = session.key.srcPort,
                        seqNum = session.serverSeq,
                        ackNum = session.clientNextSeq,
                        flags = PacketUtils.TCP_PSH or PacketUtils.TCP_ACK,
                        payload = readBuf,
                        payloadLength = n
                    )
                    session.serverSeq = (session.serverSeq + n.toLong()) and 0xFFFFFFFFL
                    writeToTun(dataPkt)
                }

                writerJob.cancel()
                if (session.state != TcpState.CLOSED) {
                    val finPkt = PacketUtils.buildTcpPacket(
                        srcIp = session.key.dstIp,
                        srcPort = session.key.dstPort,
                        dstIp = session.key.srcIp,
                        dstPort = session.key.srcPort,
                        seqNum = session.serverSeq,
                        ackNum = session.clientNextSeq,
                        flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
                    )
                    session.serverSeq = (session.serverSeq + 1L) and 0xFFFFFFFFL
                    writeToTun(finPkt)
                }
                closeSession(session.key, session, sendRst = false)
            } catch (e: ProxyException.AuthFailed) {
                closeSession(session.key, session, sendRst = true)
                if (authFailureReported.compareAndSet(false, true)) {
                    onFatalAuthFailure(e.message ?: "Proxy authentication failed.")
                }
            } catch (_: Exception) {
                closeSession(session.key, session, sendRst = true)
            }
        }
    }

    private fun handleUdpPacket(pkt: ByteArray, ihl: Int, totalLen: Int, srcIp: Int, dstIp: Int) {
        if (totalLen < ihl + 8) return
        val srcPort = PacketUtils.readU16(pkt, ihl)
        val dstPort = PacketUtils.readU16(pkt, ihl + 2)
        val udpLen = PacketUtils.readU16(pkt, ihl + 4)
        if (udpLen < 8 || totalLen < ihl + udpLen) return

        if (dstPort == 53) {
            val dnsQuery = pkt.copyOfRange(ihl + 8, ihl + udpLen)
            if (dnsQuery.size < 12) return
            val txId0 = dnsQuery[0]
            val txId1 = dnsQuery[1]
            val cacheKey = dnsQuery.copyOfRange(2, dnsQuery.size).joinToString(",") { "%02x".format(it) }
            val now = System.currentTimeMillis()
            val cached = dnsCache[cacheKey]
            if (cached != null && cached.expiresAtMs > now) {
                val resp = ByteArray(2 + cached.responseWithoutTxId.size).apply {
                    this[0] = txId0
                    this[1] = txId1
                    System.arraycopy(cached.responseWithoutTxId, 0, this, 2, cached.responseWithoutTxId.size)
                }
                writeDnsUdpReply(dstIp, srcIp, srcPort, resp)
                return
            }

            val dnsServerIp = PacketUtils.ipIntToString(dstIp)
            scope.launch {
                try {
                    bytesOut.addAndGet(dnsQuery.size.toLong())
                    val dnsResp = resolveDnsThroughProxy(dnsQuery, dnsServerIp)
                    if (dnsResp != null && dnsResp.size >= 12) {
                        bytesIn.addAndGet(dnsResp.size.toLong())
                        dnsCache[cacheKey] = CachedDns(
                            responseWithoutTxId = dnsResp.copyOfRange(2, dnsResp.size),
                            expiresAtMs = System.currentTimeMillis() + 60_000L
                        )
                        writeDnsUdpReply(dstIp, srcIp, srcPort, dnsResp)
                    }
                } catch (e: ProxyException.AuthFailed) {
                    if (authFailureReported.compareAndSet(false, true)) {
                        onFatalAuthFailure(e.message ?: "Proxy authentication failed.")
                    }
                } catch (_: Exception) {
                    // Ignore transient DNS failure
                }
            }
        } else {
            // Fast-fail non-DNS UDP (e.g., QUIC on UDP:443) so apps switch to TCP immediately
            PacketUtils.buildIcmpPortUnreachable(pkt, ihl, totalLen)?.let { icmp ->
                writeToTun(icmp)
            }
        }
    }

    private fun writeDnsUdpReply(srcIp: Int, dstIp: Int, dstPort: Int, dnsResp: ByteArray) {
        val udpResp = PacketUtils.buildUdpPacket(
            srcIp = srcIp,
            srcPort = 53,
            dstIp = dstIp,
            dstPort = dstPort,
            payload = dnsResp
        )
        writeToTun(udpResp)
    }

    private fun resolveDnsThroughProxy(dnsQuery: ByteArray, fallbackDnsIp: String): ByteArray? {
        // 1. Primary: DNS-over-HTTPS via 1.1.1.1:443 (works on BOTH HTTP CONNECT and SOCKS5 proxies)
        queryDoH("1.1.1.1", "1.1.1.1", dnsQuery)?.let { return it }
        // 2. Secondary: DNS-over-HTTPS via dns.google:443
        queryDoH("dns.google", "dns.google", dnsQuery)?.let { return it }
        // 3. Fallback: DNS-over-TCP via port 53
        return queryDnsOverTcp(fallbackDnsIp, dnsQuery)
    }

    private fun queryDoH(host: String, sniHost: String, dnsQuery: ByteArray): ByteArray? {
        return runCatching {
            val rawSocket = ProxyHandshake.connectViaProxy(
                config = config,
                targetHost = host,
                targetPort = 443,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000,
                protectSocket = protectSocket
            )
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(rawSocket, sniHost, 443, true) as SSLSocket
            ssl.use { tls ->
                tls.soTimeout = 5000
                tls.startHandshake()
                val verified = HttpsURLConnection.getDefaultHostnameVerifier().verify(sniHost, tls.session)
                if (!verified) return@runCatching null

                val headers = ("POST /dns-query HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Content-Type: application/dns-message\r\n" +
                    "Accept: application/dns-message\r\n" +
                    "Content-Length: ${dnsQuery.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)

                val out = tls.getOutputStream()
                out.write(headers)
                out.write(dnsQuery)
                out.flush()

                val allBytes = tls.getInputStream().readBytes()
                val sepIndex = findHeaderEnd(allBytes) ?: return@runCatching null
                val headerText = String(allBytes, 0, sepIndex, StandardCharsets.ISO_8859_1)
                if (!headerText.lineSequence().firstOrNull().orEmpty().contains(" 200")) {
                    return@runCatching null
                }
                val body = allBytes.copyOfRange(sepIndex + 4, allBytes.size)
                if (headerText.contains("Transfer-Encoding: chunked", ignoreCase = true)) {
                    decodeChunkedBody(body)
                } else {
                    body
                }
            }
        }.getOrNull()
    }

    private fun queryDnsOverTcp(dnsServerIp: String, dnsQuery: ByteArray): ByteArray? {
        return runCatching {
            val socket = ProxyHandshake.connectViaProxy(
                config = config,
                targetHost = dnsServerIp,
                targetPort = 53,
                connectTimeoutMs = 5000,
                readTimeoutMs = 5000,
                protectSocket = protectSocket
            )
            socket.use { s ->
                val out = s.getOutputStream()
                val framedQuery = ByteArray(2 + dnsQuery.size)
                PacketUtils.writeU16(framedQuery, 0, dnsQuery.size)
                System.arraycopy(dnsQuery, 0, framedQuery, 2, dnsQuery.size)
                out.write(framedQuery)
                out.flush()

                val input = s.getInputStream()
                val lenHeader = readExactBytes(input, 2)
                val respLen = PacketUtils.readU16(lenHeader, 0)
                if (respLen in 12..4096) readExactBytes(input, respLen) else null
            }
        }.getOrNull()
    }

    private fun findHeaderEnd(data: ByteArray): Int? {
        for (i in 0 until data.size - 3) {
            if (data[i] == '\r'.code.toByte() &&
                data[i + 1] == '\n'.code.toByte() &&
                data[i + 2] == '\r'.code.toByte() &&
                data[i + 3] == '\n'.code.toByte()
            ) {
                return i
            }
        }
        return null
    }

    private fun decodeChunkedBody(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var pos = 0
        while (pos < raw.size) {
            var lineEnd = pos
            while (lineEnd < raw.size - 1 &&
                !(raw[lineEnd] == '\r'.code.toByte() && raw[lineEnd + 1] == '\n'.code.toByte())
            ) {
                lineEnd++
            }
            if (lineEnd >= raw.size - 1) break
            val sizeLine = String(raw, pos, lineEnd - pos, StandardCharsets.US_ASCII)
                .substringBefore(';').trim()
            val chunkSize = sizeLine.toIntOrNull(16) ?: break
            pos = lineEnd + 2
            if (chunkSize == 0 || pos + chunkSize > raw.size) break
            out.write(raw, pos, chunkSize)
            pos += chunkSize + 2
        }
        return out.toByteArray()
    }

    private fun closeSession(key: SessionKey, session: TcpSession, sendRst: Boolean) {
        sessions.remove(key, session)
        val prevState = session.state
        session.state = TcpState.CLOSED
        session.writeChannel.close()
        if (sendRst && prevState != TcpState.CLOSED) {
            val rstPkt = PacketUtils.buildTcpPacket(
                srcIp = key.dstIp,
                srcPort = key.dstPort,
                dstIp = key.srcIp,
                dstPort = key.srcPort,
                seqNum = session.serverSeq,
                ackNum = session.clientNextSeq,
                flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK
            )
            writeToTun(rstPkt)
        }
        runCatching { session.socket?.close() }
    }

    private fun writeToTun(packet: ByteArray) {
        if (!running.get()) return
        synchronized(writeLock) {
            runCatching {
                tunOutput.write(packet)
            }
        }
    }

    private fun seqDiff(a: Long, b: Long): Long {
        val diff = (a - b) and 0xFFFFFFFFL
        return if (diff > 0x7FFFFFFFL) diff - 0x100000000L else diff
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buf, offset, count - offset)
            if (read == -1) throw EOFException("Unexpected EOF during DNS-over-TCP read.")
            offset += read
        }
        return buf
    }
}
