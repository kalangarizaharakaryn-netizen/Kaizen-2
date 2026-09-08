package com.kaizen.assistant

import android.content.Context
import android.content.Intent

/**
 * Maps a spoken app name to a package and launches it. Fully offline —
 * this is just Android's own PackageManager, no network involved.
 * Add more entries as you need them; the package name has to match
 * exactly what's installed (check Settings > Apps if one doesn't launch).
 */
object AppLauncher {
    private val aliases = mapOf(
        "spotify" to "com.spotify.music",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "chrome" to "com.android.chrome",
        "settings" to "com.android.settings",
        "camera" to "com.android.camera2"
    )

    /** Returns true if it found and launched something. */
    fun tryLaunch(context: Context, spokenName: String): Boolean {
        val key = spokenName.trim().lowercase()
        val pkg = aliases[key] ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
