package com.example.ui.components

import java.util.Locale

object FormatUtils {
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bps: Long): String {
        if (bps < 1024L) return "$bps B/s"
        val kb = bps / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB/s", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB/s", mb)
    }

    fun formatDuration(totalSeconds: Long): String {
        val hrs = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    }
}
