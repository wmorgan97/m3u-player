package com.wmorgan97.m3uplayer.tv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** One Server-Sent Event: [name] ("changed"), [data] (its JSON, never user data). */
data class SseEvent(val name: String, val data: String) {
    /** The writer's client id (`from`), or null. */
    val from: String? get() = FROM.find(data)?.groupValues?.get(1)
    private companion object { val FROM = Regex("\"from\"\\s*:\\s*\"([^\"]*)\"") }
}

/** Line-by-line SSE parser: comments (": hb") and `retry:` are skipped; a blank line ends an event. */
class SseParser {
    private var name = ""
    private val data = StringBuilder()
    fun feed(line: String): SseEvent? {
        if (line.isEmpty()) {
            val e = if (name.isNotEmpty() || data.isNotEmpty()) SseEvent(name.ifEmpty { "message" }, data.toString()) else null
            name = ""; data.setLength(0)
            return e
        }
        if (line.startsWith(":")) return null
        val i = line.indexOf(':')
        val field = if (i < 0) line else line.substring(0, i)
        val value = if (i < 0) "" else line.substring(i + 1).removePrefix(" ")
        when (field) {
            "event" -> name = value
            "data" -> { if (data.isNotEmpty()) data.append('\n'); data.append(value) }
        }
        return null
    }
}

/**
 * Live sync (server/sync.js GET /sync/stream): while the app is in front, the server tells us the instant another device
 * changes synced state, and we pull at once instead of waiting for the next poll. The event carries no data. The poll
 * stays as the fallback. [url] is read at every (re)connect and checked on every heartbeat (a profile switch reconnects);
 * it holds the server key, so it is never logged.
 */
class SyncStream(
    private val url: () -> String?,
    private val myCid: String,
    private val onChanged: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        /** 3 s, 6 s, 12 s … capped at 60 s. */
        fun backoffMs(attempt: Int): Long = minOf(60_000L, 3_000L shl minOf(attempt.coerceAtLeast(0), 5))
        private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()   // server beats every 20 s
    }
    @Volatile private var job: Job? = null
    @Volatile private var call: okhttp3.Call? = null

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }
    @Synchronized fun stop() { job?.cancel(); job = null; call?.cancel(); call = null }

    private suspend fun run() {
        var attempt = 0
        var pending: Job? = null
        while (scope.isActive && kotlin.coroutines.coroutineContext[Job]?.isActive == true) {
            val u = url()
            var gotSomething = false
            if (u != null) try {
                val c = client.newCall(Request.Builder().url(u).header("Accept", "text/event-stream").header("User-Agent", "M3UPlayer TV").build())
                call = c
                c.execute().use { r ->
                    if (!r.isSuccessful) return@use
                    gotSomething = true; attempt = 0
                    val p = SseParser()
                    val src = r.body!!.source()
                    while (true) {
                        val line = src.readUtf8Line() ?: break
                        if (url() != u) break   // profile switched: reconnect for the new one
                        val e = p.feed(line) ?: continue
                        if (e.name == "changed" && e.from != myCid) {
                            pending?.cancel()
                            pending = scope.launch { delay(300); runCatching { onChanged() } }   // a burst of changes = one pull
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* offline / server restarting: back off */ }
            if (gotSomething && url() != u) continue   // reconnect now for the new profile
            delay(backoffMs(attempt++))
        }
    }
}
