package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import org.json.JSONObject

// Shared building blocks: posters, wide cards, channel tiles and the rows that hold them. Focus is the
// Android TV standard: the picked card grows slightly and gets a white frame.

private val CardShape = RoundedCornerShape(10.dp)

/** The card you opened something from: Back puts the highlight on it again. */
object Nav { var restoreKey: String? by mutableStateOf(null); var pending: String? = null }

@Composable
fun Modifier.restorable(key: String): Modifier {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Nav.restoreKey) {
        if (Nav.restoreKey == key) { kotlinx.coroutines.delay(60); runCatching { fr.requestFocus() }; Nav.restoreKey = null }
    }
    return this.focusRequester(fr)
}

@Composable
fun cardBorder() = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape))

@Composable
fun RowTitle(text: String, extra: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        if (extra != null) { Spacer(Modifier.width(12.dp)); Text(extra, fontSize = 13.sp, color = Muted) }
    }
}

@Composable
fun <T> CardRow(items: List<T>, card: @Composable (T, Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        itemsIndexed(items) { i, it -> card(it, i) }
    }
}

/** A poster (2:3) with its title and year underneath. */
@Composable
fun PosterCard(v: Vod, width: Dp = 132.dp, rank: Int? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val key = "${v.kind}:${v.id}:${rank ?: ""}:${width.value}"
    Column(Modifier.width(width)) {
        Card(onClick = { Nav.pending = key; onClick() }, onLongClick = onLong, shape = CardDefaults.shape(CardShape), border = cardBorder(),
            scale = CardDefaults.scale(focusedScale = 1.06f), modifier = Modifier.width(width).aspectRatio(2f / 3f).restorable(key)) {
            Box(Modifier.fillMaxSize().background(Surface2)) {
                Text(v.title, Modifier.align(Alignment.Center).padding(8.dp), fontSize = 13.sp, color = Muted, maxLines = 3)
                AsyncImage(Repo.img(v.poster, 300), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (v.rating != null) Text("★ %.1f".format(v.rating), Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 11.sp, color = Color(0xFFFFD166), fontWeight = FontWeight.Bold)
                if (rank != null) Text("$rank", Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 2.dp), fontSize = 46.sp, fontWeight = FontWeight.Black, color = Color.White.copy(alpha = .92f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(v.title, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(v.year?.toString() ?: "", fontSize = 11.sp, color = Muted)
    }
}

/** Continue watching: a 16:9 picture, progress bar, title and time left. */
@Composable
fun WideCard(image: String?, title: String, sub: String, progress: Float?, width: Dp = 240.dp, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = onClick, onLongClick = onLong, shape = CardDefaults.shape(CardShape), border = cardBorder(),
            scale = CardDefaults.scale(focusedScale = 1.05f), modifier = Modifier.width(width).aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(Surface2)) {
                AsyncImage(Repo.img(image, 500), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Accent))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(sub, fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A channel: its logo on a dark tile, name and what's on. */
@Composable
fun ChannelCard(name: String, logo: String?, now: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(180.dp)) {
        Card(onClick = onClick, onLongClick = onLong, shape = CardDefaults.shape(CardShape), border = cardBorder(),
            scale = CardDefaults.scale(focusedScale = 1.06f), modifier = Modifier.width(180.dp).aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Surface2, Surface1))), contentAlignment = Alignment.Center) {
                if (logo.isNullOrBlank()) Text(initials(name), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Muted)
                AsyncImage(Repo.img(logo, 200), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(name, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(now, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun initials(name: String) = name.split(Regex("\\s+")).filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2).joinToString("") { it.take(1).uppercase() }

// ---------- what the player gets ----------

fun channelItem(c: Channel): JSONObject {
    val (now, next) = Repo.nowNext(c)
    val sub = when { now != null -> "${now.title} · ends ${hhmm(now.end)}"; else -> prettyGroup(c.group) }
    return JSONObject().put("url", c.url).put("title", c.name).put("subtitle", sub).put("info", now?.desc ?: "").put("live", true)
        .put("headers", JSONObject(c.headers)).put("art", c.logo).put("listTitle", c.name).put("listSub", now?.title ?: next?.title ?: "")
        .put("sync", JSONObject().put("kind", "live").put("name", c.name).put("url", c.url).put("logo", c.logo).put("group", c.group).put("tvgId", c.tvgId))
        .put("listTitleOverride", "Channels")
}

fun vodSync(v: Vod, url: String) = JSONObject().put("id", (if (v.kind == "series") "s" else "m") + v.id).put("kind", v.kind).put("name", v.name).put("url", url)
    .put("logo", v.poster).put("group", v.group).put("year", v.year?.toString() ?: "").put("rating", v.rating ?: 0)

fun movieItem(v: Vod, url: String, start: Double): JSONObject = JSONObject().put("url", url).put("title", v.label)
    .put("subtitle", listOfNotNull(v.rating?.let { "★ %.1f".format(it) }).joinToString(" · ")).put("info", v.overview).put("live", false).put("start", start)
    .put("art", v.poster).put("listTitle", v.label).put("sync", vodSync(v, url))

fun episodeItem(show: Vod, e: Episode, url: String, start: Double): JSONObject {
    val name = "${show.title} S${e.season}E${e.num} - ${e.title}"
    return JSONObject().put("url", url).put("title", "${show.title} · S${e.season} E${e.num}").put("subtitle", e.title).put("info", e.plot).put("live", false).put("start", start)
        .put("art", e.still.ifEmpty { show.poster }).put("listTitle", "${e.num}. ${e.title}").put("listSub", e.duration)
        .put("listTitleOverride", "Episodes")
        .put("sync", JSONObject().put("id", "e${e.id}").put("kind", "episode").put("name", name).put("url", url).put("logo", e.still.ifEmpty { show.poster })
            .put("group", "${show.title} · Season ${e.season}").put("season", e.season).put("num", e.num).put("series", show.name))
}
