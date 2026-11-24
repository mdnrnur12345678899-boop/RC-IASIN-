package com.example.vpn

import java.util.concurrent.atomic.AtomicInteger

object PacketUtils {
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
        writeU16(pkt, 10, computeIpChecksum(pkt, 0, ipHeaderLen))

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

        // IPv4 Header
        pkt[0] = 0x45.toByte()
        pkt[1] = 0x00
        writeU16(pkt, 2, totalLen)
        writeU16(pkt, 4, ipIdCounter.getAndIncrement() and 0xFFFF)
        writeU16(pkt, 6, 0x4000)
        pkt[8] = 64
        pkt[9] = PROTO_UDP.toByte()
        writeU32(pkt, 12, srcIp.toLong() and 0xFFFFFFFFL)
        writeU32(pkt, 16, dstIp.toLong() and 0xFFFFFFFFL)
        writeU16(pkt, 10, computeIpChecksum(pkt, 0, ipHeaderLen))

        // UDP Header
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

    private fun computeIpChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length) {
            sum += readU16(buf, i)
            i += 2
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
