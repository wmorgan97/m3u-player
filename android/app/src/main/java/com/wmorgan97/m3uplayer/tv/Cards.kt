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
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

// Cards and rows. One focus treatment everywhere: the picked card grows a little, gets a white frame and a
// soft glow (Android TV's own language); nothing else on the page moves.

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
fun cardBorder() = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Tv.focusRing), shape = Tv.shape))
@Composable
fun cardGlow() = CardDefaults.glow(focusedGlow = Glow(elevationColor = Color(0x99000000), elevation = 18.dp))
@Composable
fun cardScale(s: Float = 1.07f) = CardDefaults.scale(focusedScale = s)

/** A picture on a quiet grey until it arrives, then a short fade in; the fallback shows when there is none or it fails.
 *  (Plain AsyncImage with a colour placeholder: one layout node per picture and no animation while loading, which is
 *  what keeps a row of 30 posters smooth to scroll.) */
@Composable
fun Picture(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fallback: @Composable BoxScope.() -> Unit = {}) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.background(Tv.surface2)) {
        if (url.isNullOrBlank() || failed) fallback()
        else AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).crossfade(180).build(), null, contentScale = scale, modifier = Modifier.fillMaxSize(),
            placeholder = ColorPainter(Tv.surface2), error = ColorPainter(Color.Transparent), onError = { failed = true })
    }
}

@Composable
fun <T> CardRow(items: List<T>, key: ((T) -> Any)? = null, card: @Composable (T, Int) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
        itemsIndexed(items, key = key?.let { k -> { _, it -> k(it) } }) { i, it -> card(it, i) }
    }
}

/** Two lines under a card: title in white, a quiet second line. */
@Composable
fun Caption(title: String, sub: String?, width: Dp) {
    Column(Modifier.width(width).padding(top = 8.dp)) {
        Text(title, fontSize = Tv.caption, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(sub ?: " ", fontSize = Tv.label, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A poster (2:3) with its title and year underneath. */
@Composable
fun PosterCard(v: Vod, width: Dp = Tv.poster, rank: Int? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val key = "${v.kind}:${v.id}:${rank ?: ""}:${width.value}"
    Column(Modifier.width(width)) {
        Card(onClick = { Nav.pending = key; onClick() }, onLongClick = onLong, shape = CardDefaults.shape(Tv.shape), border = cardBorder(), glow = cardGlow(),
            scale = cardScale(), modifier = Modifier.width(width).aspectRatio(2f / 3f).restorable(key)) {
            Box(Modifier.fillMaxSize()) {
                Picture(Repo.img(v.poster, 300), Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                        Text(v.title, Modifier.padding(10.dp), fontSize = Tv.caption, color = Tv.text2, maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (v.rating != null) Text("★ %.1f".format(v.rating), Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color(0xB3000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 11.sp, color = Tv.star, fontWeight = FontWeight.Bold)
                if (rank != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(70.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                    Text("$rank", Modifier.align(Alignment.BottomStart).padding(start = 8.dp), fontSize = 44.sp, fontWeight = FontWeight.Black, color = Tv.text, lineHeight = 44.sp)
                }
            }
        }
        Caption(v.title, v.year?.toString(), width)
    }
}

/** Continue watching / episodes: a 16:9 picture with a progress line, title and detail beneath. */
@Composable
fun WideCard(image: String?, title: String, sub: String, progress: Float?, width: Dp = Tv.wide, badge: String? = null, restoreKey: String? = null,
             onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        Card(onClick = { Nav.pending = restoreKey; onClick() }, onLongClick = onLong, shape = CardDefaults.shape(Tv.shape), border = cardBorder(), glow = cardGlow(),
            scale = cardScale(1.05f), modifier = Modifier.width(width).aspectRatio(16f / 9f).let { if (restoreKey != null) it.restorable(restoreKey) else it }) {
            Box(Modifier.fillMaxSize()) {
                Picture(Repo.img(image, 500), Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                        Text(title, Modifier.padding(12.dp), fontSize = Tv.caption, color = Tv.text2, maxLines = 2, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (badge != null) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { Tag(badge, bg = Color(0xB3000000), color = Tv.text, size = 11.sp) }
                if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            }
        }
        Caption(title, sub, width)
    }
}

/** A channel: its logo on a dark tile, name and what's on. */
@Composable
fun ChannelCard(name: String, logo: String?, now: String, progress: Float? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(Tv.channel)) {
        Card(onClick = onClick, onLongClick = onLong, shape = CardDefaults.shape(Tv.shape), border = cardBorder(), glow = cardGlow(),
            scale = cardScale(), modifier = Modifier.width(Tv.channel).aspectRatio(16f / 9f)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                if (logo.isNullOrBlank()) Text(initials(name), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Tv.muted)
                else Picture(Repo.img(logo, 200), Modifier.fillMaxSize().padding(16.dp).background(Color.Transparent), ContentScale.Fit) {
                    Text(initials(name), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Tv.muted)
                }
                if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
            }
        }
        Caption(name, now, Tv.channel)
    }
}

/** Channel logo tile used in lists and the guide. */
@Composable
fun LogoTile(c: Channel, modifier: Modifier = Modifier) {
    Box(modifier.background(Tv.surface2, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
        if (c.logo.isBlank()) Text(initials(c.name), fontSize = Tv.caption, color = Tv.muted, fontWeight = FontWeight.Bold)
        else Picture(Repo.img(c.logo, 120), Modifier.fillMaxSize().padding(4.dp).background(Color.Transparent), ContentScale.Fit) {
            Text(initials(c.name), fontSize = Tv.caption, color = Tv.muted, fontWeight = FontWeight.Bold)
        }
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
