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

/** The app's picture card: a plain focusable box with the shared card focus ([FocusStyle.Card]: spring scale, white
 *  frame and halo fading in), no ripple. Focus never recomposes the card (the treatment is drawn), so moving the
 *  highlight costs a redraw of two cards and nothing else. [focused], when given, is set as focus comes and goes (the
 *  caption beneath reads it at draw time to brighten). */
@Composable
fun FocusCard(modifier: Modifier, scale: Float = Tv.scaleCard, restoreKey: String? = null, label: String = "", onLongClick: (() -> Unit)? = null, onFocus: (() -> Unit)? = null,
              focused: MutableState<Boolean>? = null, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val style = remember(scale) { FocusStyle.card(scale) }
    Box(modifier.let { if (restoreKey != null) it.restorable(restoreKey) else it }
        .onFocusChanged { focused?.value = it.isFocused; if (it.isFocused) onFocus?.invoke() }
        .graphicsLayer { }   // (its own display list: the frame and halo animating redraw this card only, not the row)
        .tvFocus(style)
        .tvClickable({ Nav.pending = restoreKey; onClick() }, onLongClick, label = label)
    ) { content() }
}

private const val FADE_MS = 200

/** A picture on a quiet grey until it arrives, then a short fade in (pictures already in memory show at once); the fallback shows when there is none or it fails.
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
        else AsyncImage(remember(src) { ImageRequest.Builder(ctx).data(src).crossfade(if (fade) FADE_MS else 0).build() }, null, contentScale = scale, modifier = Modifier.fillMaxSize(),
            placeholder = ColorPainter(Tv.surface2), error = ColorPainter(Color.Transparent), onError = { tries = if (tries == 0 && Repo.imgOriginal(url) != null) 1 else 2 })
    }
}

@Composable
fun <T> CardRow(items: List<T>, key: ((T) -> Any)? = null, card: @Composable (T, Int) -> Unit) = traced("CardRow") {
    // Keyed rows show each key once: a duplicate key (the same stream listed twice, one match on two lists) crashes a LazyRow.
    val list = if (key == null) items else remember(items) { items.distinctBy(key) }
    WithScroll(rememberRowKeyline()) {
        LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
            itemsIndexed(list, key = key?.let { k -> { _, it -> k(it) } }) { i, it -> card(it, i) }
        }
    }
}

/** Two lines under a card: title in white, a quiet second line; at 80% until the card above is [focused]. (The alpha
 *  is applied to each line as it's drawn, never through an offscreen layer: ~30 captions on screen would each need one.) */
@Composable
fun Caption(title: String, sub: String?, width: Dp, label: String = title, focused: State<Boolean>? = null) = traced("Caption") {
    Column(Modifier.width(width).padding(top = 8.dp).clearAndSetSemantics { }.let { m -> if (focused == null) m else m.graphicsLayer { alpha = if (focused.value) 1f else .8f; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha } }) {
        Text(title, fontSize = Tv.caption, lineHeight = Tv.captionLine, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        Text(sub ?: " ", fontSize = Tv.label, lineHeight = Tv.captionLine, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A poster (2:3) with its title and year underneath. */
@Composable
fun PosterCard(v: Vod, width: Dp = Tv.poster, rank: Int? = null, restoreKey: String? = null, onLong: (() -> Unit)? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit) = traced("PosterCard") {
    val key = restoreKey ?: "${v.kind}:${v.id}:${rank ?: ""}:${width.value}"
    val focused = remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        FocusCard(Modifier.width(width).aspectRatio(2f / 3f), restoreKey = key, label = v.label, onLongClick = onLong, onFocus = onFocus, focused = focused, onClick = onClick) {
            Box(Modifier.fillMaxSize()) {
                Picture(Repo.img(v.poster, 300), Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                        Text(v.title, Modifier.padding(10.dp), fontSize = Tv.caption, color = Tv.text2, maxLines = 4, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    }
                }
                // Top 10: the number top-left, where it shows even when the row only peeks in at the bottom of the screen.
                if (rank != null) {
                    Box(Modifier.align(Alignment.TopStart).fillMaxWidth().height(64.dp).background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent))))
                    Text("$rank", Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp), fontSize = Tv.rank, fontWeight = FontWeight.Black, color = Tv.text, lineHeight = Tv.rank,
                        style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
                }
                if (v.rating != null) TvBadge("★ %.1f".format(v.rating), Modifier.align(Alignment.TopEnd).padding(6.dp), BadgeTone.Star)
            }
        }
        Caption(v.title, v.year?.toString(), width, label = v.label, focused = focused)
    }
}

/** Continue watching / episodes: a 16:9 picture with a progress line, title and detail beneath. */
@Composable
fun WideCard(image: String?, title: String, sub: String, progress: Float?, width: Dp = Tv.wide, badge: String? = null, restoreKey: String? = null,
             onLong: (() -> Unit)? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    val focused = remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        FocusCard(Modifier.width(width).aspectRatio(16f / 9f), scale = Tv.scaleWide, restoreKey = restoreKey, label = title, onLongClick = onLong, onFocus = onFocus, focused = focused, onClick = onClick) {
            Box(Modifier.fillMaxSize()) {
                Picture(Repo.img(image, 500), Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                        Text(title, Modifier.padding(12.dp), fontSize = Tv.caption, color = Tv.text2, maxLines = 2, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (badge != null) TvBadge(badge, Modifier.align(Alignment.TopStart).padding(6.dp))
                if (progress != null) TvProgress(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth())
            }
        }
        Caption(title, sub, width, focused = focused)
    }
}

/** A channel: its logo on a dark tile, name and what's on. */
@Composable
fun ChannelCard(name: String, logo: String?, now: String, progress: Float? = null, restoreKey: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val focused = remember { mutableStateOf(false) }
    Column(Modifier.width(Tv.channel)) {
        FocusCard(Modifier.width(Tv.channel).aspectRatio(16f / 9f), scale = Tv.scaleWide, restoreKey = restoreKey, label = name, onLongClick = onLong, focused = focused, onClick = onClick) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                if (logo.isNullOrBlank()) Text(initials(name), fontSize = Tv.numeral, fontWeight = FontWeight.Bold, color = Tv.muted)
                else Picture(Repo.logo(logo), Modifier.fillMaxSize().padding(16.dp).background(Color.Transparent), ContentScale.Fit) {
                    Text(initials(name), fontSize = Tv.numeral, fontWeight = FontWeight.Bold, color = Tv.muted)
                }
                if (progress != null) TvProgress(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp))
            }
        }
        Caption(name, now, Tv.channel, focused = focused)
    }
}

/** Channel logo tile used in lists and the guide. */
@Composable
fun LogoTile(c: Channel, modifier: Modifier = Modifier) {
    Box(modifier.background(Tv.surface2, Tv.shapeS), contentAlignment = Alignment.Center) {
        if (c.logo.isBlank()) Text(initials(c.name), fontSize = Tv.caption, color = Tv.muted, fontWeight = FontWeight.Bold)
        else Picture(Repo.logo(c.logo), Modifier.fillMaxSize().padding(4.dp).background(Color.Transparent), ContentScale.Fit, fade = false) {
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
        .put("show", show.title).put("epLabel", "S${e.season} E${e.num}").put("epTitle", e.title)   // for the player's next-episode card
        .put("sync", JSONObject().put("id", "e${e.id}").put("kind", "episode").put("name", name).put("url", url).put("logo", e.still.ifEmpty { show.poster })
            .put("group", "${show.title} · Season ${e.season}").put("season", e.season).put("num", e.num).put("series", show.name))
}
