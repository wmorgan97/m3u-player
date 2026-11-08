package com.wmorgan97.m3uplayer.tv

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import kotlinx.coroutines.launch

// Settings rows for the update intervals (Settings > Playlists and Settings > TV Guide). Kept out of Settings.kt.

private fun lastUpdated(at: Long): String {
    if (at <= 0L) return "Not updated yet"
    val m = (System.currentTimeMillis() - at) / 60_000
    return "Last updated " + when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
}

internal fun refreshPlaylistRows(
    sources: List<Pair<String, String>>, say: (String) -> Unit,
    chooser: (String, List<Pair<String, () -> Unit>>, String?) -> Unit, changed: () -> Unit,
): List<SettingRow> = buildList {
    add(SettingRow.Header("Updates"))
    add(SettingRow.Action(Icons.Default.Refresh, "Update playlists now", lastUpdated(sources.maxOfOrNull { RefreshPrefs.playlistAt(it.first) } ?: 0L)) {
        RefreshHook.updatePlaylistsNow(); say("Updating playlists in the background")
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { Repo.liveChannels(refresh = true) }; kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { changed() } }
    })
    add(SettingRow.Toggle(Icons.Default.PowerSettingsNew, "Update on app start", "Check for new lists and guide when the app opens", RefreshPrefs.onStart) { RefreshPrefs.onStart = it; changed() })
    sources.forEach { (key, name) ->
        val cur = RefreshPrefs.playlist(key)
        add(SettingRow.Action(Icons.Default.Schedule, "Update “$name” every", lastUpdated(RefreshPrefs.playlistAt(key)), RefreshPolicy.label(cur), opens = true) {
            chooser("Update “$name” every", RefreshPolicy.PLAYLIST_CHOICES.map { h ->
                RefreshPolicy.label(h) to { RefreshPrefs.setPlaylist(key, h); say(if (h == RefreshPolicy.MANUAL) "Updates only when you ask" else "Updating: ${RefreshPolicy.label(h).lowercase()}"); changed() }
            }, RefreshPolicy.label(cur))
        })
    }
}

internal fun refreshGuideRows(say: (String) -> Unit, chooser: (String, List<Pair<String, () -> Unit>>, String?) -> Unit, changed: () -> Unit): List<SettingRow> = listOf(
    SettingRow.Header("Updates"),
    SettingRow.Action(Icons.Default.Refresh, "Update TV guide now", lastUpdated(RefreshPrefs.guideAt)) {
        RefreshHook.updateGuideNow(); say("Updating the guide in the background")
        kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) { Repo.live?.let { Repo.requestGuide(it.channels.take(200)) }; kotlinx.coroutines.delay(8_000); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { changed() } }
    },
    SettingRow.Action(Icons.Default.Schedule, "Update TV guide every", "Longer is gentler on your provider", RefreshPolicy.label(RefreshPrefs.guide), opens = true) {
        chooser("Update TV guide every", RefreshPolicy.GUIDE_CHOICES.map { h ->
            RefreshPolicy.label(h) to { RefreshPrefs.guide = h; say(if (h == RefreshPolicy.MANUAL) "Guide updates only when you ask" else "Updating the guide: ${RefreshPolicy.label(h).lowercase()}"); changed() }
        }, RefreshPolicy.label(RefreshPrefs.guide))
    },
    SettingRow.Toggle(Icons.Default.PowerSettingsNew, "Update on app start", "Check for new lists and guide when the app opens", RefreshPrefs.onStart) { RefreshPrefs.onStart = it; changed() },
)
