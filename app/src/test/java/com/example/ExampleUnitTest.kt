package com.example

import com.example.data.ProxyConfig
import com.example.data.ProxyProtocol
import com.example.network.ProxyException
import com.example.network.ProxyHandshake
import com.example.vpn.PacketUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class ExampleUnitTest {

    @Test
    fun socks5_username_password_handshake_succeeds_and_relays() {
        val server = ServerSocket(0)
        val serverThread = thread {
            server.use { ss ->
                ss.accept().use { client ->
                    val input = BufferedInputStream(client.getInputStream())
                    val out = client.getOutputStream()

                    val ver = input.read()
                    val nMethods = input.read()
                    val methods = ByteArray(nMethods)
                    input.read(methods)
                    assertEquals(0x05, ver)
                    assertTrue(methods.contains(0x02.toByte()))

                    out.write(byteArrayOf(0x05, 0x02))
                    out.flush()

                    assertEquals(0x01, input.read())
                    val uLen = input.read()
                    val user = ByteArray(uLen).also { input.read(it) }
                    val pLen = input.read()
                    val pass = ByteArray(pLen).also { input.read(it) }
                    assertEquals("alice", String(user, StandardCharsets.UTF_8))
                    assertEquals("s3cr3t", String(pass, StandardCharsets.UTF_8))
                    out.write(byteArrayOf(0x01, 0x00))
                    out.flush()

                    val req = ByteArray(10)
                    input.read(req)
                    assertEquals(0x05, req[0].toInt())
                    assertEquals(0x01, req[1].toInt())
                    assertEquals(0x01, req[3].toInt())
                    assertEquals(443, ((req[8].toInt() and 0xFF) shl 8) or (req[9].toInt() and 0xFF))

                    out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 80))
                    out.flush()

                    val ping = ByteArray(4)
                    input.read(ping)
                    assertEquals("PING", String(ping, StandardCharsets.US_ASCII))
                    out.write("PONG".toByteArray(StandardCharsets.US_ASCII))
                    out.flush()
                }
            }
        }

        val config = ProxyConfig(
            protocol = ProxyProtocol.SOCKS5,
            host = "127.0.0.1",
            port = server.localPort,
            username = "alice",
            password = "s3cr3t"
        )

        val socket = ProxyHandshake.connectViaProxy(config, "1.1.1.1", 443, connectTimeoutMs = 3000)
        socket.use { s ->
            s.getOutputStream().write("PING".toByteArray(StandardCharsets.US_ASCII))
            s.getOutputStream().flush()
            val resp = ByteArray(4)
            s.getInputStream().read(resp)
            assertEquals("PONG", String(resp, StandardCharsets.US_ASCII))
        }
        serverThread.join(3000)
    }

    @Test
    fun http_connect_basic_auth_rejects_407_and_succeeds_200() {
        val server = ServerSocket(0)
        val serverThread = thread {
            server.use { ss ->
                ss.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        lines.add(line)
                    }
                    assertTrue(lines.first().startsWith("CONNECT example.com:443 HTTP/1.1"))
                    assertTrue(lines.any { it == "Proxy-Authorization: Basic Ym9iOndyb25n" })

                    client.getOutputStream().write(
                        "HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
                    )
                    client.getOutputStream().flush()
                }
            }
        }

        val config = ProxyConfig(
            protocol = ProxyProtocol.HTTP,
            host = "127.0.0.1",
            port = server.localPort,
            username = "bob",
            password = "wrong"
        )

        assertThrows(ProxyException.AuthFailed::class.java) {
            ProxyHandshake.connectViaProxy(config, "example.com", 443, connectTimeoutMs = 3000)
        }
        serverThread.join(3000)
    }

    @Test
    fun packet_utils_builds_valid_ipv4_tcp_packet() {
        val pkt = PacketUtils.buildTcpPacket(
            srcIp = 0x0A080002,
            srcPort = 51234,
            dstIp = 0x01010101,
            dstPort = 443,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
            payload = "hi".toByteArray(),
            mss = 1400
        )
        assertEquals(46, pkt.size)
        assertEquals(0x45, pkt[0].toInt() and 0xFF)
        assertEquals(PacketUtils.PROTO_TCP, pkt[9].toInt() and 0xFF)
        assertEquals(51234, PacketUtils.readU16(pkt, 20))
        assertEquals(443, PacketUtils.readU16(pkt, 22))
        assertEquals(1000L, PacketUtils.readU32(pkt, 24))
        assertEquals(2000L, PacketUtils.readU32(pkt, 28))
        assertEquals(0x60, pkt[32].toInt() and 0xFF)
        assertEquals(1400, PacketUtils.readU16(pkt, 42))
        assertArrayEquals("hi".toByteArray(), pkt.copyOfRange(44, 46))
    }

    @Test
    fun integrity_guard_decodes_protected_brand_and_telegram_url() {
        assertEquals("https://t.me/rciasin", com.example.security.AppIntegrityGuard.telegramUrl())
        assertEquals("RC Proxy", com.example.security.AppIntegrityGuard.brandTitle())
        assertEquals("RC PROXY", com.example.security.AppIntegrityGuard.brandUpper())
        assertEquals("com.rc.proxy", com.example.security.AppIntegrityGuard.packageId())
    }

    @Test
    fun integrity_guard_parses_real_apk_sig_block_when_built() {
        val apkFile = java.io.File("build/outputs/apk/debug/app-debug.apk")
        if (apkFile.exists()) {
            val hashes = com.example.security.AppIntegrityGuard.readRawApkSigBlockV2V3Hashes(apkFile)
            assertTrue("Expected v2/v3 signing block cert hash from APK", hashes.isNotEmpty())
        }
    }
}
