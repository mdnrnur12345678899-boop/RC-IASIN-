package com.example.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val hasLauncher: Boolean,
)

object InstalledAppsRepository {

    suspend fun loadInstalledApps(context: Context): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val launcherPackages = mutableSetOf<String>()

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvedLaunchers = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(launcherIntent, 0)
            }
        }.getOrDefault(emptyList())

        resolvedLaunchers.forEach { info ->
            info.activityInfo?.packageName?.let { launcherPackages.add(it) }
        }

        val appsByPackage = linkedMapOf<String, InstalledAppInfo>()

        val installedList = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())

        for (appInfo in installedList) {
            val pkg = appInfo.packageName ?: continue
            if (pkg == selfPkg) continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            appsByPackage[pkg] = InstalledAppInfo(
                packageName = pkg,
                label = label,
                isSystemApp = isSystem,
                hasLauncher = pkg in launcherPackages
            )
        }

        // Also include any launcher package not returned in getInstalledApplications
        for (resolveInfo in resolvedLaunchers) {
            val pkg = resolveInfo.activityInfo?.packageName ?: continue
            if (pkg == selfPkg || appsByPackage.containsKey(pkg)) continue
            val label = runCatching { resolveInfo.loadLabel(pm).toString() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: pkg
            appsByPackage[pkg] = InstalledAppInfo(
                packageName = pkg,
                label = label,
                isSystemApp = false,
                hasLauncher = true
            )
        }

        appsByPackage.values.sortedWith(
            compareByDescending<InstalledAppInfo> { it.hasLauncher }
                .thenBy { it.isSystemApp }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
    }

    suspend fun loadAppIcon(context: Context, packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val targetPx = 72
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val bmp = drawable.bitmap
                val scaled = if (bmp.width > targetPx || bmp.height > targetPx) {
                    Bitmap.createScaledBitmap(bmp, targetPx, targetPx, true)
                } else {
                    bmp
                }
                return@runCatching scaled.asImageBitmap()
            }
            val bitmap = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}
