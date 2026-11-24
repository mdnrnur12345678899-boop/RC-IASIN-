package com.example.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.example.BuildConfig
import com.example.R
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.Locale
import java.util.jar.JarFile
import java.util.zip.ZipFile

data class IntegrityResult(
    val isValid: Boolean,
    val reason: String? = null,
)

object AppIntegrityGuard {

    private val MASK_KEY = intArrayOf(0x5A, 0x3C, 0x71, 0x19, 0x4F, 0x2D, 0x63, 0x0B)

    // XOR-encoded protected constants (with key 0x5A, 0x3C, 0x71, 0x19, 0x4F, 0x2D, 0x63, 0x0B)
    // "https://t.me/rciasin"
    private val ENC_TELEGRAM_URL = intArrayOf(
        0x32, 0x48, 0x05, 0x69, 0x3C, 0x17, 0x4C, 0x24,
        0x2E, 0x12, 0x1C, 0x7C, 0x60, 0x5F, 0x00, 0x62,
        0x3B, 0x4F, 0x18, 0x77
    )

    // "RC Proxy"
    private val ENC_BRAND_TITLE = intArrayOf(
        0x08, 0x7F, 0x51, 0x49, 0x3D, 0x42, 0x1B, 0x72
    )

    // "RC PROXY"
    private val ENC_BRAND_UPPER = intArrayOf(
        0x08, 0x7F, 0x51, 0x49, 0x1D, 0x62, 0x3B, 0x52
    )

    // "com.aistudio.netrelay.vprtqw"
    private val ENC_PACKAGE_ID = intArrayOf(
        0x39, 0x53, 0x1C, 0x37, 0x2E, 0x44, 0x10, 0x7F,
        0x2F, 0x58, 0x18, 0x76, 0x61, 0x43, 0x06, 0x7F,
        0x28, 0x59, 0x1D, 0x78, 0x36, 0x03, 0x15, 0x7B,
        0x28, 0x48, 0x00, 0x6E
    )

    fun telegramUrl(): String = decodeXorBytes(ENC_TELEGRAM_URL)
    fun brandTitle(): String = decodeXorBytes(ENC_BRAND_TITLE)
    fun brandUpper(): String = decodeXorBytes(ENC_BRAND_UPPER)

    fun enforceOrCrash(context: Context) {
        val result = verify(context)
        if (!result.isValid) {
            runCatching { Process.killProcess(Process.myPid()) }
            runCatching { Runtime.getRuntime().halt(1) }
            throw SecurityException(result.reason ?: "Integrity check failed.")
        }
    }

    fun verify(context: Context): IntegrityResult {
        val expectedPkg = decodeXorBytes(ENC_PACKAGE_ID)
        if (context.packageName != expectedPkg || BuildConfig.APPLICATION_ID != expectedPkg) {
            return IntegrityResult(false, "Package identity modified.")
        }

        // Reject debuggable repackaging (android:debuggable="true")
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return IntegrityResult(false, "Debuggable tampering detected.")
        }

        // Verify protected brand & Telegram URL in Android resources (catches MT Manager ARSC/strings.xml edits)
        val appName = runCatching { context.getString(R.string.app_name) }.getOrNull()
        val tgMsg = runCatching { context.getString(R.string.telegram_message) }.getOrNull()
        val hintProfile = runCatching { context.getString(R.string.hint_profile_name) }.getOrNull()
        val notifTitle = runCatching { context.getString(R.string.notification_title_connected) }.getOrNull()

        if (appName != brandTitle() ||
            tgMsg == null || !tgMsg.contains(telegramUrl()) ||
            hintProfile != brandUpper() ||
            notifTitle == null || !notifTitle.contains(brandUpper())
        ) {
            return IntegrityResult(false, "Protected brand or Telegram link modified.")
        }

        val expectedHashes = decodeExpectedCertHashes()

        val pmHashes = readPackageManagerCertHashes(context)
        if (pmHashes.isEmpty()) {
            return IntegrityResult(false, "Missing application signature.")
        }

        if (expectedHashes.isNotEmpty() && pmHashes.none { it in expectedHashes }) {
            return IntegrityResult(false, "Application signature verification failed.")
        }

        val apkPath = context.applicationInfo.sourceDir
        if (!apkPath.isNullOrBlank()) {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                return IntegrityResult(false, "APK archive missing.")
            }

            // 1. Direct raw disk APK Signature Block v2/v3 parser (bypasses PackageManager hooks)
            val rawBlockHashes = readRawApkSigBlockV2V3Hashes(apkFile)
            if (rawBlockHashes.isNotEmpty()) {
                if (expectedHashes.isNotEmpty() && rawBlockHashes.none { it in expectedHashes }) {
                    return IntegrityResult(false, "APK v2/v3 signature block mismatch.")
                }
                if (pmHashes.none { it in rawBlockHashes }) {
                    return IntegrityResult(false, "Signature hook tampering detected.")
                }
            }

            // 2. Cross-check PackageArchiveInfo against on-disk APK
            val archiveHashes = readArchiveCertHashes(context, apkPath)
            if (archiveHashes.isNotEmpty()) {
                if (expectedHashes.isNotEmpty() && archiveHashes.none { it in expectedHashes }) {
                    return IntegrityResult(false, "On-disk APK signature mismatch.")
                }
                if (pmHashes.none { it in archiveHashes }) {
                    return IntegrityResult(false, "Signature consistency check failed.")
                }
            }

            // 3. Verify core APK entries exist and v1 META-INF signature (if present) matches
            val structureOk = runCatching {
                ZipFile(apkFile).use { zip ->
                    zip.getEntry("classes.dex") != null &&
                        zip.getEntry("AndroidManifest.xml") != null &&
                        zip.getEntry("resources.arsc") != null
                }
            }.getOrDefault(false)
            if (!structureOk) {
                return IntegrityResult(false, "APK structure integrity check failed.")
            }

            if (expectedHashes.isNotEmpty()) {
                val v1Hashes = readV1MetaInfHashes(apkFile)
                if (v1Hashes.isNotEmpty() && v1Hashes.none { it in expectedHashes }) {
                    return IntegrityResult(false, "JAR signature mismatch.")
                }
            }
        }

        return IntegrityResult(true)
    }

    internal fun decodeXorBytes(encoded: IntArray): String {
        val out = ByteArray(encoded.size)
        for (i in encoded.indices) {
            out[i] = (encoded[i] xor MASK_KEY[i % MASK_KEY.size]).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    private fun decodeExpectedCertHashes(): Set<String> {
        val maskedHex = BuildConfig.EXPECTED_CERT_MASKED.trim()
        if (maskedHex.isEmpty() || maskedHex.length % 2 != 0) return emptySet()
        val byteLen = maskedHex.length / 2
        val out = ByteArray(byteLen)
        for (i in 0 until byteLen) {
            val v = maskedHex.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return emptySet()
            out[i] = (v xor MASK_KEY[i % MASK_KEY.size]).toByte()
        }
        val decoded = String(out, StandardCharsets.UTF_8)
        return decoded
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    @Suppress("DEPRECATION")
    private fun readPackageManagerCertHashes(context: Context): Set<String> {
        return runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo ?: return emptySet()
                val certs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    @Suppress("DEPRECATION")
    private fun readArchiveCertHashes(context: Context, apkPath: String): Set<String> {
        return runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info?.signingInfo ?: return emptySet()
                val certs = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
                certs.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            } else {
                val info = pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES)
                info?.signatures.orEmpty().map { sha256Hex(it.toByteArray()) }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    /**
     * Parses APK Signature Scheme v2/v3 signing block ("APK Sig Block 42") directly from the APK
     * file on disk so Java PackageManager signature hooks cannot forge certificate hashes.
     */
    internal fun readRawApkSigBlockV2V3Hashes(apkFile: File): Set<String> {
        return runCatching {
            RandomAccessFile(apkFile, "r").use { raf ->
                val fileLen = raf.length()
                if (fileLen < 22) return emptySet()

                val searchSize = minOf(fileLen, 65_557L).toInt()
                val tail = ByteArray(searchSize)
                raf.seek(fileLen - searchSize)
                raf.readFully(tail)

                var eocdPos = -1
                for (i in searchSize - 22 downTo 0) {
                    if (tail[i] == 0x50.toByte() &&
                        tail[i + 1] == 0x4B.toByte() &&
                        tail[i + 2] == 0x05.toByte() &&
                        tail[i + 3] == 0x06.toByte()
                    ) {
                        val commentLen = ((tail[i + 21].toInt() and 0xFF) shl 8) or
                            (tail[i + 20].toInt() and 0xFF)
                        if (i + 22 + commentLen == searchSize) {
                            eocdPos = i
                            break
                        }
                    }
                }
                if (eocdPos == -1) return emptySet()

                val cdOffset = ByteBuffer.wrap(tail, eocdPos + 16, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                if (cdOffset < 32 || cdOffset >= fileLen) return emptySet()

                val footer = ByteArray(24)
                raf.seek(cdOffset - 24)
                raf.readFully(footer)
                val magic = String(footer, 8, 16, StandardCharsets.US_ASCII)
                if (magic != "APK Sig Block 42") return emptySet()

                val blockSizeExcludingFirst8 = ByteBuffer.wrap(footer, 0, 8)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .long
                if (blockSizeExcludingFirst8 < 24 || blockSizeExcludingFirst8 > cdOffset - 8) {
                    return emptySet()
                }
                val totalBlockSize = blockSizeExcludingFirst8 + 8
                if (totalBlockSize > 10 * 1024 * 1024) return emptySet()

                val blockStart = cdOffset - totalBlockSize
                val blockBytes = ByteArray((totalBlockSize - 24).toInt())
                raf.seek(blockStart + 8)
                raf.readFully(blockBytes)

                val buf = ByteBuffer.wrap(blockBytes).order(ByteOrder.LITTLE_ENDIAN)
                val hashes = mutableSetOf<String>()
                while (buf.remaining() >= 12) {
                    val pairLen = buf.long
                    if (pairLen < 4 || pairLen > buf.remaining()) break
                    val id = buf.int
                    val valLen = (pairLen - 4).toInt()
                    val valueStart = buf.position()
                    if (id == 0x7109871a || id == -262969152) { // v2 (0x7109871a) or v3 (0xf05368c0)
                        extractCertHashesFromV2V3Value(buf.slice().apply {
                            order(ByteOrder.LITTLE_ENDIAN)
                            limit(valLen)
                        }, hashes)
                    }
                    buf.position(valueStart + valLen)
                }
                hashes
            }
        }.getOrDefault(emptySet())
    }

    private fun extractCertHashesFromV2V3Value(valueBuf: ByteBuffer, outHashes: MutableSet<String>) {
        if (valueBuf.remaining() < 4) return
        val signersLen = valueBuf.int
        if (signersLen <= 0 || signersLen > valueBuf.remaining()) return
        val signersEnd = valueBuf.position() + signersLen
        while (valueBuf.position() + 4 <= signersEnd) {
            val signerLen = valueBuf.int
            if (signerLen <= 0 || valueBuf.position() + signerLen > signersEnd) break
            val signerEnd = valueBuf.position() + signerLen
            if (valueBuf.position() + 4 <= signerEnd) {
                val signedDataLen = valueBuf.int
                if (signedDataLen > 0 && valueBuf.position() + signedDataLen <= signerEnd) {
                    val sdEnd = valueBuf.position() + signedDataLen
                    // 1. digests (skip)
                    if (valueBuf.position() + 4 <= sdEnd) {
                        val digestsLen = valueBuf.int
                        if (digestsLen >= 0 && valueBuf.position() + digestsLen <= sdEnd) {
                            valueBuf.position(valueBuf.position() + digestsLen)
                            // 2. certificates
                            if (valueBuf.position() + 4 <= sdEnd) {
                                val certsLen = valueBuf.int
                                if (certsLen > 0 && valueBuf.position() + certsLen <= sdEnd) {
                                    val certsEnd = valueBuf.position() + certsLen
                                    while (valueBuf.position() + 4 <= certsEnd) {
                                        val certLen = valueBuf.int
                                        if (certLen <= 0 || valueBuf.position() + certLen > certsEnd) break
                                        val certBytes = ByteArray(certLen)
                                        valueBuf.get(certBytes)
                                        outHashes.add(sha256Hex(certBytes))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            valueBuf.position(signerEnd)
        }
    }

    private fun readV1MetaInfHashes(apkFile: File): Set<String> {
        return runCatching {
            val hashes = mutableSetOf<String>()
            JarFile(apkFile).use { jar ->
                val cf = CertificateFactory.getInstance("X.509")
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.uppercase(Locale.US)
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))
                    ) {
                        jar.getInputStream(entry).use { input ->
                            cf.generateCertificates(input).forEach { cert ->
                                hashes.add(sha256Hex(cert.encoded))
                            }
                        }
                    }
                }
            }
            hashes
        }.getOrDefault(emptySet())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02X".format(Locale.US, it) }
    }
}
