package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import org.json.JSONObject

// Settings on the TV: the few things worth changing from the sofa. Everything else (playlist editing, the TMDB key,
// players) stays in the full settings on the phone / computer, which share the same server.

@Composable
fun SettingsScreen(act: TvActivity) {
    var englishOnly by remember { mutableStateOf(Repo.englishOnly) }
    var fourK by remember { mutableStateOf(Repo.fourKFirst) }
    var cleared by remember { mutableStateOf(false) }
    val sources = remember(Repo.version) {
        val s = Repo.syncData.optJSONObject("sources") ?: JSONObject()
        s.keys().asSequence().mapNotNull { s.optJSONObject(it) }.filter { !it.optBoolean("deleted") }.toList()
    }
    val version = remember { runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: "" }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 56.dp)) {
        item { PageTitle("Settings", "Shared with your phone and computer through your server") }
        item { Section("Playlists") }
        if (sources.isEmpty()) item { Row(Icons.Default.PlaylistAdd, "No playlists yet", "Add one in the full settings") { act.openWeb("#/settings") } }
        for (src in sources) item {
            val x = src.optJSONObject("xtream")
            val what = when (src.optString("content")) { "live" -> "Live TV"; "vod" -> "Movies & series"; else -> "Live TV, movies & series" }
            Row(if (x != null) Icons.Default.Cloud else Icons.Default.Link, src.optString("name").ifBlank { x?.optString("server") ?: "Playlist" }, what) {}
        }
        item { Section("Movies & series") }
        item { Toggle(Icons.Default.Language, "English groups only", "Hide other-language groups from the rows", englishOnly) { englishOnly = it; Repo.englishOnly = it; Repo.version++ } }
        item { Section("Live TV") }
        item { Toggle(Icons.Default.HighQuality, "Prefer 4K channels", "Start the 4K copy of a channel when there is one", fourK) { fourK = it; Repo.fourKFirst = it } }
        item { Section("This TV") }
        item { Row(Icons.Default.Dns, "Server", Repo.server.removePrefix("https://")) {} }
        item { Row(Icons.Default.Refresh, if (cleared) "Saved data cleared" else "Clear saved data", "Channel lists and catalogues download again next time") { Repo.clearCache(); cleared = true } }
        item { Row(Icons.Default.OpenInNew, "Full settings", "Playlists, TMDB, player options and more") { act.openWeb("#/settings") } }
        item { Spacer(Modifier.height(24.dp)); Text("M3U Player for Android TV $version", Modifier.padding(start = Tv.margin), fontSize = Tv.label, color = Tv.muted) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), Modifier.padding(start = Tv.margin, top = 22.dp, bottom = 8.dp), fontSize = Tv.label, color = Tv.muted, fontWeight = FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp))
}

@Composable
private fun Row(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = Tv.margin - 12.dp).fillMaxWidth(.7f)) {
        ListItem(selected = false, onClick = onClick, leadingContent = { Icon(icon, null, Modifier.size(22.dp)) },
            headlineContent = { Text(title, fontSize = Tv.body, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(sub, fontSize = Tv.caption, color = Tv.muted) })
    }
}

@Composable
private fun Toggle(icon: ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(Modifier.padding(horizontal = Tv.margin - 12.dp).fillMaxWidth(.7f)) {
        ListItem(selected = false, onClick = { onChange(!on) }, leadingContent = { Icon(icon, null, Modifier.size(22.dp)) },
            headlineContent = { Text(title, fontSize = Tv.body, fontWeight = FontWeight.Medium) },
            supportingContent = { Text(sub, fontSize = Tv.caption, color = Tv.muted) },
            trailingContent = {
                Box(Modifier.width(44.dp).height(24.dp).background(if (on) Tv.accent else Tv.surface3, RoundedCornerShape(12.dp)), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(Modifier.padding(3.dp).size(18.dp).background(Color.White, RoundedCornerShape(9.dp)))
                }
            })
    }
}

