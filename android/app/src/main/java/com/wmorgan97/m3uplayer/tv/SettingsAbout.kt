package com.wmorgan97.m3uplayer.tv

import android.app.Activity
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

// "Server & about": read-only facts for when something isn't working (what to read out to whoever is helping).
// Everything here is local and cheap: no network calls, nothing that could tune the provider.

internal object SettingsAbout {
    fun diagnostics(act: Activity, syncError: String?): List<SettingRow> = buildList {
        add(SettingRow.Header("Diagnostics"))
        add(SettingRow.Info(Icons.Default.Info, "App version", appVersion(act)))
        if (syncError != null) add(SettingRow.Info(Icons.Default.ErrorOutline, "Last sync", syncError, warn = true))
        Repo.live?.let { l -> add(SettingRow.Info(Icons.Default.LiveTv, "Channels loaded", "${count(l.channels.size, "channel")} in ${count(l.groups.size, "group")}")) }
        add(SettingRow.Info(Icons.Default.Tv, "Display", display(act)))
        add(SettingRow.Info(Icons.Default.Memory, "Device", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"))
    }

    fun appVersion(act: Activity): String = runCatching {
        val p = act.packageManager.getPackageInfo(act.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
        "${p.versionName ?: "?"} (build $code)"
    }.getOrDefault("Unknown")

    /** The output mode right now (it changes while a film plays when frame-rate matching is on). */
    @Suppress("DEPRECATION")
    private fun display(act: Activity): String = runCatching {
        val m = (if (Build.VERSION.SDK_INT >= 30) act.display else act.windowManager.defaultDisplay)!!.mode
        "${m.physicalWidth} × ${m.physicalHeight} · ${"%.2f".format(java.util.Locale.UK, m.refreshRate).trimEnd('0').trimEnd('.')} Hz"
    }.getOrDefault("Unknown")
}
