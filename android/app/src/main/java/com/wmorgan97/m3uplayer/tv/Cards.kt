package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
    if (Nav.restoreKey != key) return this   // only the one card being returned to pays for a requester and a coroutine
    val fr = remember { FocusRequester() }
    LaunchedEffect(key) { kotlinx.coroutines.delay(60); runCatching { fr.requestFocus() }; Nav.restoreKey = null }
    return this.focusRequester(fr)
}

/** The app's card: a plain focusable box with a quick 110ms scale and a white frame when picked, no shadow or ripple.
 *  Focus never recomposes the card: the scale is a layer property and the frame is drawn, both read at draw time,
 *  so moving the highlight costs a redraw of two cards and nothing else. That is what makes it feel instant. */
@Composable
fun FocusCard(modifier: Modifier, scale: Float = 1.06f, restoreKey: String? = null, label: String = "", onLongClick: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val focused = remember { mutableStateOf(false) }
    val anim = remember { Animatable(1f) }
    LaunchedEffect(Unit) { snapshotFlow { focused.value }.collect { f -> anim.animateTo(if (f) scale else 1f, tween(110)) } }
    val stroke = with(LocalDensity.current) { 3.dp.toPx() }; val radius = with(LocalDensity.current) { Tv.radius.toPx() }
    Box(modifier.let { if (restoreKey != null) it.restorable(restoreKey) else it }.onFocusChanged { focused.value = it.isFocused }
        .let { if (onLongClick != null) it.holdOk(onLongClick) else it }
        .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { Nav.pending = restoreKey; onClick(); true } else false }
        .clearAndSetSemantics { contentDescription = label }   // one accessibility node per card (Button Mapper listens to every change)
        .focusable().graphicsLayer { scaleX = anim.value; scaleY = anim.value; shape = Tv.shape; clip = true }
        .drawWithContent { drawContent(); if (focused.value) drawRoundRect(Tv.focusRing, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), cornerRadius = CornerRadius(radius - stroke / 2), style = Stroke(stroke)) }
    ) { content() }
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
fun Picture(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fade: Boolean = true, fallback: @Composable BoxScope.() -> Unit = {}) = traced("Picture") {
    // If the server's resized copy fails (server away), the picture is fetched from where it really lives.
    var tries by remember(url) { mutableIntStateOf(0) }
    val src = remember(url, tries) { if (tries == 1) Repo.imgOriginal(url) else url }
    val failed = tries >= 2 || (tries == 1 && src == null)
    val ctx = LocalContext.current
    Box(modifier.background(Tv.surface2)) {
        if (src.isNullOrBlank() || failed) fallback()
        else AsyncImage(remember(src) { ImageRequest.Builder(ctx).data(src).crossfade(if (fade) 150 else 0).build() }, null, contentScale = scale, modifier = Modifier.fillMaxSize(),
            placeholder = ColorPainter(Tv.surface2), error = ColorPainter(Color.Transparent), onError = { tries = if (tries == 0 && Repo.imgOriginal(url) != null) 1 else 2 })
    }
}

@Composable
fun <T> CardRow(items: List<T>, key: ((T) -> Any)? = null, card: @Composable (T, Int) -> Unit) = traced("CardRow") {
    LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
        itemsIndexed(items, key = key?.let { k -> { _, it -> k(it) } }) { i, it -> card(it, i) }
    }
}

/** Two lines under a card: title in white, a quiet second line. */
@Composable
fun Caption(title: String, sub: String?, width: Dp, label: String = title) = traced("Caption") {
    Column(Modifier.width(width).padding(top = 8.dp).clearAndSetSemantics { }) {
        Text(title, fontSize = Tv.caption, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(sub ?: " ", fontSize = Tv.label, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A poster (2:3) with its title and year underneath. */
@Composable
fun PosterCard(v: Vod, width: Dp = Tv.poster, rank: Int? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) = traced("PosterCard") {
    val key = "${v.kind}:${v.id}:${rank ?: ""}:${width.value}"
    Column(Modifier.width(width)) {
        FocusCard(Modifier.width(width).aspectRatio(2f / 3f), restoreKey = key, label = v.label, onLongClick = onLong, onClick = onClick) {
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
        Caption(v.title, v.year?.toString(), width, label = v.label)
    }
}

/** Continue watching / episodes: a 16:9 picture with a progress line, title and detail beneath. */
@Composable
fun WideCard(image: String?, title: String, sub: String, progress: Float?, width: Dp = Tv.wide, badge: String? = null, restoreKey: String? = null,
             onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.width(width)) {
        FocusCard(Modifier.width(width).aspectRatio(16f / 9f), scale = 1.04f, restoreKey = restoreKey, label = title, onLongClick = onLong, onClick = onClick) {
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
        FocusCard(Modifier.width(Tv.channel).aspectRatio(16f / 9f), label = name, onLongClick = onLong, onClick = onClick) {
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
        else Picture(Repo.img(c.logo, 120), Modifier.fillMaxSize().padding(4.dp).background(Color.Transparent), ContentScale.Fit, fade = false) {
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
        .put("num", if (c.num > 0 && Repo.showNumbers) "${c.num}" else "").put("nowTitle", now?.title ?: "").put("nowStart", now?.start ?: 0).put("nowEnd", now?.end ?: 0).put("nextTitle", next?.title ?: "")
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
