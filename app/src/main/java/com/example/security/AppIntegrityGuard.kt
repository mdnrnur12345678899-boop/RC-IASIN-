package com.example.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import java.io.File
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

    fun verify(context: Context): IntegrityResult {
        if (context.packageName != BuildConfig.APPLICATION_ID) {
            return IntegrityResult(false, "Package identity mismatch.")
        }

        val expectedHashes = BuildConfig.EXPECTED_CERT_SHA256
            .split(',')
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .toSet()

        val pmHashes = readPackageManagerCertHashes(context)
        if (pmHashes.isEmpty()) {
            return IntegrityResult(false, "Missing application signature.")
        }

        if (expectedHashes.isNotEmpty() && pmHashes.none { it in expectedHashes }) {
            return IntegrityResult(false, "Application signature verification failed (APK modified or re-signed).")
        }

        val apkPath = context.applicationInfo.sourceDir
        if (!apkPath.isNullOrBlank()) {
            val apkFile = File(apkPath)
            if (!apkFile.exists()) {
                return IntegrityResult(false, "APK archive missing.")
            }

            // Cross-check PackageArchiveInfo directly against the APK on disk to catch simple PM hooks
            val archiveHashes = readArchiveCertHashes(context, apkPath)
            if (archiveHashes.isNotEmpty()) {
                if (expectedHashes.isNotEmpty() && archiveHashes.none { it in expectedHashes }) {
                    return IntegrityResult(false, "On-disk APK signature mismatch.")
                }
                if (pmHashes.none { it in archiveHashes }) {
                    return IntegrityResult(false, "Signature consistency check failed.")
                }
            }

            // Verify core dex & manifest structure and v1 signature if present
            val structureOk = runCatching {
                ZipFile(apkFile).use { zip ->
                    zip.getEntry("classes.dex") != null && zip.getEntry("AndroidManifest.xml") != null
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
