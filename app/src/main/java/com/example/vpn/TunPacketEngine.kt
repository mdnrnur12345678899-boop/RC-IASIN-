package com.example.vpn

import android.os.ParcelFileDescriptor
import com.example.data.ProxyConfig
import com.example.network.ProxyException
import com.example.network.ProxyHandshake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
        CLOSING,
        CLOSED
    }

    private class TcpSession(
        val key: SessionKey,
        @Volatile var clientNextSeq: Long,
        @Volatile var serverSeq: Long,
    ) {
        @Volatile var state: TcpState = TcpState.CONNECTING
        @Volatile var socket: Socket? = null
        @Volatile var output: OutputStream? = null
        @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        val writeLock = Any()
    }

    private val running = AtomicBoolean(true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<SessionKey, TcpSession>()
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
                handleIpPacket(buffer, len)
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
        runCatching { scope.cancel() }
        runCatching { tunInput.close() }
        runCatching { tunOutput.close() }
    }

    private fun handleIpPacket(pkt: ByteArray, len: Int) {
        if (len < 20) return
        val version = (pkt[0].toInt() ushr 4) and 0x0F
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
            sessions.remove(key)?.let { old -> runCatching { old.socket?.close() } }

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

        if (payloadLen > 0 && session.state == TcpState.ESTABLISHED) {
            val diff = seqDiff(seqNum, session.clientNextSeq)
            if (diff == 0L) {
                val payload = pkt.copyOfRange(payloadOffset, payloadOffset + payloadLen)
                session.clientNextSeq = (session.clientNextSeq + payloadLen) and 0xFFFFFFFFL
                val ackPkt = PacketUtils.buildTcpPacket(
                    srcIp = dstIp,
                    srcPort = dstPort,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientNextSeq,
                    flags = PacketUtils.TCP_ACK
                )
                writeToTun(ackPkt)

                scope.launch {
                    try {
                        synchronized(session.writeLock) {
                            session.output?.write(payload)
                            session.output?.flush()
                        }
                        bytesOut.addAndGet(payloadLen.toLong())
                    } catch (_: Exception) {
                        closeSession(key, session, sendRst = true)
                    }
                }
            } else if (diff < 0L) {
                // Retransmission: re-ACK expected seq
                val ackPkt = PacketUtils.buildTcpPacket(
                    srcIp = dstIp,
                    srcPort = dstPort,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientNextSeq,
                    flags = PacketUtils.TCP_ACK
                )
                writeToTun(ackPkt)
            }
        }

        if (isFin) {
            session.clientNextSeq = (session.clientNextSeq + 1L) and 0xFFFFFFFFL
            if (session.state != TcpState.CLOSED) {
                session.state = TcpState.CLOSING
                val finAck = PacketUtils.buildTcpPacket(
                    srcIp = dstIp,
                    srcPort = dstPort,
                    dstIp = srcIp,
                    dstPort = srcPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientNextSeq,
                    flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK
                )
                session.serverSeq = (session.serverSeq + 1L) and 0xFFFFFFFFL
                writeToTun(finAck)
                closeSession(key, session, sendRst = false)
            }
        }
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
                if (!running.get() || session.state == TcpState.CLOSED) {
                    runCatching { socket.close() }
                    return@launch
                }

                session.socket = socket
                session.output = socket.getOutputStream()
                session.state = TcpState.ESTABLISHED
                session.lastActivityMs = System.currentTimeMillis()

                TunnelStateHolder.log(LogLevel.INFO, "TCP -> $dstHost:$dstPort via ${config.protocol.displayName}")

                // Send SYN-ACK to TUN
                val synAck = PacketUtils.buildTcpPacket(
                    srcIp = session.key.dstIp,
                    srcPort = session.key.dstPort,
                    dstIp = session.key.srcIp,
                    dstPort = session.key.srcPort,
                    seqNum = session.serverSeq,
                    ackNum = session.clientNextSeq,
                    flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK
                )
                session.serverSeq = (session.serverSeq + 1L) and 0xFFFFFFFFL
                writeToTun(synAck)

                val input = socket.getInputStream()
                val readBuf = ByteArray(1400)
                while (running.get() && session.state == TcpState.ESTABLISHED) {
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

                if (session.state == TcpState.ESTABLISHED) {
                    session.state = TcpState.CLOSING
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

        // Tunnel DNS queries (UDP port 53) as DNS-over-TCP through the configured proxy
        if (dstPort == 53) {
            val dnsQuery = pkt.copyOfRange(ihl + 8, ihl + udpLen)
            val dnsServerIp = PacketUtils.ipIntToString(dstIp)
            scope.launch {
                try {
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
                        bytesOut.addAndGet(dnsQuery.size.toLong())

                        val input = s.getInputStream()
                        val lenHeader = readExactBytes(input, 2)
                        val respLen = PacketUtils.readU16(lenHeader, 0)
                        if (respLen in 1..4096) {
                            val dnsResp = readExactBytes(input, respLen)
                            bytesIn.addAndGet(respLen.toLong())
                            val udpResp = PacketUtils.buildUdpPacket(
                                srcIp = dstIp,
                                srcPort = 53,
                                dstIp = srcIp,
                                dstPort = srcPort,
                                payload = dnsResp
                            )
                            writeToTun(udpResp)
                        }
                    }
                } catch (e: ProxyException.AuthFailed) {
                    if (authFailureReported.compareAndSet(false, true)) {
                        onFatalAuthFailure(e.message ?: "Proxy authentication failed.")
                    }
                } catch (_: Exception) {
                    // Ignore transient DNS lookup failures
                }
            }
        }
    }

    private fun closeSession(key: SessionKey, session: TcpSession, sendRst: Boolean) {
        sessions.remove(key, session)
        val prevState = session.state
        session.state = TcpState.CLOSED
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
