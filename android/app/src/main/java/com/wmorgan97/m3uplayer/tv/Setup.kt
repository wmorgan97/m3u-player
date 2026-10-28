package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.json.JSONObject

// First run on the TV, native (it used to hand over to the phone-style web app): connect the server (address + key),
// then, if the server has no playlist yet, add one. Done: the app starts as normal.

@Composable
fun SetupScreen(onDone: () -> Unit, onQuit: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }          // 0 server, 1 choose playlist kind, 2 playlist form
    var form by remember { mutableStateOf<FormAsk?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun hasPlaylist() = Repo.syncData.optJSONObject("sources")?.let { s -> s.keys().asSequence().any { s.optJSONObject(it)?.optBoolean("deleted") == false } } == true

    Box(Modifier.fillMaxSize().background(Tv.bg), contentAlignment = Alignment.TopStart) {
        Column(Modifier.padding(start = Tv.margin, top = Tv.topMargin + 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LiveTv, null, tint = Tv.accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("M3U Player", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
            }
            Text(if (step == 0) "Step 1 of 2 · Connect your server" else "Step 2 of 2 · Add your playlist", fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(top = 4.dp))
        }
    }
    key(step) { when (step) {
        0 -> FormDialog("Connect your server", "The address and key shown at the end of the server install. Your server adds the TV guide, films and series, and keeps this TV in step with your phone.",
            listOf(FormField("Server address", Repo.server, "https://your-server.ts.net:8443", url = true), FormField("Server key", "", "Server key", secret = true)),
            "Connect", busy, error, onSubmit = { (addr, k) ->
                if (k.isEmpty()) { error = "Enter the server key"; return@FormDialog }
                busy = true; error = null
                scope.launch {
                    Repo.saveConfig(k, addr.trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" })
                    val ok = runCatching { Repo.sync() }.isSuccess
                    busy = false
                    if (!ok) { Repo.saveConfig("", null); error = "Couldn't connect with that address and key" }
                    else if (hasPlaylist()) onDone() else step = 1
                }
            }, onDismiss = onQuit)
        1 -> Options("Add your playlist", listOf(
            "Xtream login (server, username, password)" to { form = playlistForm(null, JSONObject().put("kind", "xtream")); step = 2 },
            "M3U playlist link" to { form = playlistForm(null, JSONObject().put("kind", "url")); step = 2 },
            "Skip for now" to onDone)) { onDone() }
        else -> form?.let { f ->
            FormDialog(f.title, f.intro, f.fields, f.submit, busy, error, onSubmit = { vals ->
                busy = true; error = null
                scope.launch {
                    val err = runCatching { f.save(vals) }.getOrElse { it.message ?: "That didn't work" }
                    busy = false
                    if (err == null) onDone() else error = err
                }
            }) { error = null; step = 1 }
        }
    } }
}
