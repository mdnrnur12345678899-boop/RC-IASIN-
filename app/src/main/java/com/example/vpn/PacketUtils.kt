package com.example.vpn

import java.util.concurrent.atomic.AtomicInteger

object PacketUtils {
    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    private val ipIdCounter = AtomicInteger(1)

    fun readU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    fun readU32(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)

    fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }

    fun ipIntToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    fun buildTcpPacket(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        payloadLength: Int = payload.size,
        windowSize: Int = 65535,
        mss: Int? = null,
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = if (mss != null) 24 else 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payloadLength
        val pkt = ByteArray(totalLen)

        // IPv4 Header
        pkt[0] = 0x45.toByte()
        pkt[1] = 0x00
        writeU16(pkt, 2, totalLen)
        writeU16(pkt, 4, ipIdCounter.getAndIncrement() and 0xFFFF)
        writeU16(pkt, 6, 0x4000) // Don't Fragment
        pkt[8] = 64 // TTL
        pkt[9] = PROTO_TCP.toByte()
        writeU32(pkt, 12, srcIp.toLong() and 0xFFFFFFFFL)
        writeU32(pkt, 16, dstIp.toLong() and 0xFFFFFFFFL)
        writeU16(pkt, 10, computeChecksum(pkt, 0, ipHeaderLen))

        // TCP Header
        writeU16(pkt, 20, srcPort)
        writeU16(pkt, 22, dstPort)
        writeU32(pkt, 24, seqNum and 0xFFFFFFFFL)
        writeU32(pkt, 28, ackNum and 0xFFFFFFFFL)
        pkt[32] = if (mss != null) 0x60.toByte() else 0x50.toByte()
        pkt[33] = (flags and 0xFF).toByte()
        writeU16(pkt, 34, windowSize)

        if (mss != null) {
            pkt[40] = 0x02 // Kind: MSS
            pkt[41] = 0x04 // Length: 4
            writeU16(pkt, 42, mss)
        }

        if (payloadLength > 0) {
            System.arraycopy(payload, 0, pkt, ipHeaderLen + tcpHeaderLen, payloadLength)
        }

        val tcpChecksum = computeTransportChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = PROTO_TCP,
            pkt = pkt,
            transportOffset = ipHeaderLen,
            transportLen = tcpHeaderLen + payloadLength
        )
        writeU16(pkt, 36, tcpChecksum)
        return pkt
    }

    fun buildUdpPacket(
        srcIp: Int,
        srcPort: Int,
        dstIp: Int,
        dstPort: Int,
        payload: ByteArray,
        payloadLength: Int = payload.size,
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLen = udpHeaderLen + payloadLength
        val totalLen = ipHeaderLen + udpLen
        val pkt = ByteArray(totalLen)

        pkt[0] = 0x45.toByte()
        pkt[1] = 0x00
        writeU16(pkt, 2, totalLen)
        writeU16(pkt, 4, ipIdCounter.getAndIncrement() and 0xFFFF)
        writeU16(pkt, 6, 0x4000)
        pkt[8] = 64
        pkt[9] = PROTO_UDP.toByte()
        writeU32(pkt, 12, srcIp.toLong() and 0xFFFFFFFFL)
        writeU32(pkt, 16, dstIp.toLong() and 0xFFFFFFFFL)
        writeU16(pkt, 10, computeChecksum(pkt, 0, ipHeaderLen))

        writeU16(pkt, 20, srcPort)
        writeU16(pkt, 22, dstPort)
        writeU16(pkt, 24, udpLen)

        if (payloadLength > 0) {
            System.arraycopy(payload, 0, pkt, ipHeaderLen + udpHeaderLen, payloadLength)
        }

        var udpChecksum = computeTransportChecksum(
            srcIp = srcIp,
            dstIp = dstIp,
            protocol = PROTO_UDP,
            pkt = pkt,
            transportOffset = ipHeaderLen,
            transportLen = udpLen
        )
        if (udpChecksum == 0) udpChecksum = 0xFFFF
        writeU16(pkt, 26, udpChecksum)
        return pkt
    }

    /**
     * Builds an ICMP Type 3 Code 3 (Port Unreachable) packet so apps attempting QUIC (UDP:443)
     * immediately fall back to HTTPS over TCP instead of stalling.
     */
    fun buildIcmpPortUnreachable(originalPkt: ByteArray, ihl: Int, originalTotalLen: Int): ByteArray? {
        val quotedLen = (ihl + 8).coerceAtMost(originalTotalLen)
        if (quotedLen < 28) return null

        val ipHeaderLen = 20
        val icmpHeaderLen = 8
        val totalLen = ipHeaderLen + icmpHeaderLen + quotedLen
        val pkt = ByteArray(totalLen)

        val origSrcIp = readU32(originalPkt, 12)
        val origDstIp = readU32(originalPkt, 16)

        pkt[0] = 0x45.toByte()
        pkt[1] = 0x00
        writeU16(pkt, 2, totalLen)
        writeU16(pkt, 4, ipIdCounter.getAndIncrement() and 0xFFFF)
        writeU16(pkt, 6, 0x4000)
        pkt[8] = 64
        pkt[9] = PROTO_ICMP.toByte()
        writeU32(pkt, 12, origDstIp) // Reply comes from destination
        writeU32(pkt, 16, origSrcIp)
        writeU16(pkt, 10, computeChecksum(pkt, 0, ipHeaderLen))

        pkt[20] = 3 // Destination Unreachable
        pkt[21] = 3 // Port Unreachable
        System.arraycopy(originalPkt, 0, pkt, ipHeaderLen + icmpHeaderLen, quotedLen)
        writeU16(pkt, 22, computeChecksum(pkt, ipHeaderLen, icmpHeaderLen + quotedLen))
        return pkt
    }

    /**
     * Builds an IPv6 TCP RST+ACK reply for incoming IPv6 SYN segments so dual-stack apps
     * fall back to IPv4 immediately without leaking or timing out.
     */
    fun buildIpv6TcpRst(ipv6Pkt: ByteArray, len: Int): ByteArray? {
        if (len < 60) return null // 40-byte IPv6 header + 20-byte TCP header
        val nextHeader = ipv6Pkt[6].toInt() and 0xFF
        if (nextHeader != PROTO_TCP) return null

        val tcpFlags = ipv6Pkt[53].toInt() and 0x3F
        if ((tcpFlags and TCP_SYN) == 0 || (tcpFlags and TCP_ACK) != 0) return null

        val srcPort = readU16(ipv6Pkt, 40)
        val dstPort = readU16(ipv6Pkt, 42)
        val seqNum = readU32(ipv6Pkt, 44)

        val pkt = ByteArray(60)
        pkt[0] = 0x60.toByte() // Version 6
        writeU16(pkt, 4, 20) // Payload length = 20 bytes TCP
        pkt[6] = PROTO_TCP.toByte()
        pkt[7] = 64 // Hop limit
        // Swap src (8..23) and dst (24..39)
        System.arraycopy(ipv6Pkt, 24, pkt, 8, 16)
        System.arraycopy(ipv6Pkt, 8, pkt, 24, 16)

        // TCP header at offset 40
        writeU16(pkt, 40, dstPort)
        writeU16(pkt, 42, srcPort)
        writeU32(pkt, 44, 0L)
        writeU32(pkt, 48, (seqNum + 1L) and 0xFFFFFFFFL)
        pkt[52] = 0x50.toByte()
        pkt[53] = (TCP_RST or TCP_ACK).toByte()
        writeU16(pkt, 54, 0)

        // IPv6 TCP pseudo-header checksum
        var sum = 0L
        for (i in 8 until 40 step 2) {
            sum += readU16(pkt, i)
        }
        sum += 20L // TCP length
        sum += PROTO_TCP.toLong()
        for (i in 40 until 60 step 2) {
            sum += readU16(pkt, i)
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        writeU16(pkt, 56, (sum.inv() and 0xFFFFL).toInt())
        return pkt
    }

    private fun computeChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += readU16(buf, i)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun computeTransportChecksum(
        srcIp: Int,
        dstIp: Int,
        protocol: Int,
        pkt: ByteArray,
        transportOffset: Int,
        transportLen: Int,
    ): Int {
        var sum = 0L
        sum += (srcIp ushr 16) and 0xFFFF
        sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF
        sum += dstIp and 0xFFFF
        sum += protocol and 0xFF
        sum += transportLen and 0xFFFF

        var i = transportOffset
        val end = transportOffset + transportLen
        while (i + 1 < end) {
            sum += readU16(pkt, i)
            i += 2
        }
        if (i < end) {
            sum += (pkt[i].toInt() and 0xFF) shl 8
        }

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
