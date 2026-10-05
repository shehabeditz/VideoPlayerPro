package com.shehab360.videoplayerpro

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.DecimalFormat
import java.util.Locale

object Fmt {
    fun duration(ms: Long): String {
        val totalSeconds = (if (ms < 0) 0 else ms) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun size(bytes: Long): String {
        val df = DecimalFormat("#.##")
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb > 1024) "${df.format(mb / 1024)} GB" else "${df.format(mb)} MB"
    }
}

/** মিডিয়া পারমিশন সহায়ক */
object Perms {
    private const val VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun video(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO, VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun audio(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_AUDIO)
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** পূর্ণ অথবা (Android 14+) আংশিক "Selected videos only" অ্যাক্সেস */
    fun hasVideoAccess(ctx: Context): Boolean = video().any { granted(ctx, it) }

    fun hasAudioAccess(ctx: Context): Boolean = audio().any { granted(ctx, it) }
}
