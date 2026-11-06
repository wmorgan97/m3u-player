package com.wmorgan97.m3uplayer.tv

import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** One viewer profile ("Who's watching?"). [named]: false for the default profile until someone names it.
 *  [avatar]: its picture's path on the server ("/avatar/<id>?v=…" a photo from the phone, "/avatar/preset/<id>" one of the
 *  owner's ready-made ones; server/avatar.js), or "" for the initial on its colour. */
data class Profile(val id: String, val name: String, val color: Int, val deleted: Boolean = false, val named: Boolean = true, val avatar: String = "")

/** Viewer profiles: the same rules as the web app (js/profiles.js) and the server (server/sync.js). */
object Profiles {
    const val MAIN = "main"
    const val MAX = 5
    /** The personal parts of /sync: per profile. Everything else (playlists, TVs, Play on TV) is shared. */
    val PERSONAL = listOf("favorites", "favAt", "recents", "recReset", "progress", "reminders", "follows", "watchlater", "prefs")
    /** Calm tile colours, the web app's PROFILE_COLORS in the same order (a profile's colour is an index). */
    val COLORS = listOf(0xFF3D7BE0, 0xFFD4813A, 0xFF2A9D8F, 0xFF8A6FD1, 0xFFC95B8A, 0xFF5B8C3A, 0xFFC0504D, 0xFF4F6D8F)
    fun color(p: Profile) = Color(COLORS[Math.floorMod(p.color, COLORS.size)])
    fun initial(name: String) = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    /** Main first (always there, "Main" until named), then the others in the order they were added (ids are time-based). */
    fun list(raw: List<Profile>): List<Profile> {
        val m = raw.firstOrNull { it.id == MAIN && !it.deleted }
        val main = Profile(MAIN, m?.name?.takeIf { it.isNotBlank() } ?: "Main", m?.color ?: 0, named = !m?.name.isNullOrBlank(), avatar = m?.avatar ?: "")
        return listOf(main) + raw.filter { it.id != MAIN && !it.deleted && it.name.isNotBlank() }.sortedBy { it.id }
    }
    fun shouldAsk(list: List<Profile>, current: String, remember: Boolean) = list.size > 1 && !(remember && list.any { it.id == current })
    fun nextColor(list: List<Profile>): Int { val used = list.map { it.color }.toSet(); return COLORS.indices.firstOrNull { it !in used } ?: (list.size % COLORS.size) }
    /** Switch timing (logcat "M3UProfile"): from the pick to the next frame drawn after [what]. */
    @Volatile var switchT0 = 0L
    fun logDrawn(what: String, t0: Long) {
        if (t0 == 0L) return
        val at = android.os.SystemClock.uptimeMillis() - t0
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.view.Choreographer.getInstance().postFrameCallback { android.view.Choreographer.getInstance().postFrameCallback {
                android.util.Log.i("M3UProfile", "switch $what: ready ${at} ms, drawn ${android.os.SystemClock.uptimeMillis() - t0} ms") } }
        }
    }
    /** Only paths the server hands out (never another host). */
    fun avatarOk(v: String) = Regex("^/avatar/(preset/[0-9a-f]{6,40}|[A-Za-z0-9_-]{1,24}\\?v=[0-9a-f]{6,40})$").matches(v)
    fun newId(now: Long = System.currentTimeMillis()) = "p" + now.toString(36) + (0 until 3).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
}

/** A profile's circle: its picture, else its initial on its colour; the same soft edge shading over every one, so
 *  photos, ready-made pictures and initials read as one set. Pictures come through Coil's disk cache (a new picture is a
 *  new URL), at one of two sizes so each is fetched once. */
@Composable
fun ProfileAvatar(p: Profile, size: Dp, modifier: Modifier = Modifier) = AvatarCircle(p.avatar, Profiles.initial(p.name), Profiles.color(p), size, modifier)

@Composable
fun AvatarCircle(avatar: String, initial: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(if (avatar.isNotEmpty()) Tv.surface2 else color), contentAlignment = Alignment.Center) {
        if (avatar.isEmpty()) Text(initial, color = Color.White, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.SemiBold)
        else coil.compose.AsyncImage(model = Repo.avatarUrl(avatar, if (size > 60.dp) 400 else 120), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(0.58f to Color.Transparent, 1f to Color.Black.copy(alpha = .28f))))
    }
}

/** "Who's watching?": the profiles as big tiles, and "Remember on this TV". Back keeps the current profile. */
@Composable
fun WhoIsWatching(onPick: (Profile, Boolean) -> Unit, onDismiss: () -> Unit) {
    val list = remember { Repo.profiles() }
    val current = Repo.profileId
    var keep by remember { mutableStateOf(Repo.rememberProfile) }
    val first = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { repeat(20) { delay(30); if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect } }
    // Over everything (the menu too); the highlight can't wander to the page underneath.
    Box(Modifier.fillMaxSize().background(Tv.bg).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Who’s watching?", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Tv.text)
            Spacer(Modifier.height(Tv.s10))
            Row(horizontalArrangement = Arrangement.spacedBy(Tv.s8)) {
                for (p in list) {
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.width(150.dp).let { if (p.id == current || (list.none { it.id == current } && p == list.first())) it.focusRequester(first) else it }
                        .onFocusChanged { focused = it.isFocused }.tvClickable({ onPick(p, keep) }, label = p.name),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        // The focus ring is the profile's own colour: the one place its accent shows.
                        Box(Modifier.scale(if (focused) 1.08f else 1f).border(3.dp, if (focused) Profiles.color(p) else Color.Transparent, CircleShape).padding(6.dp)) {
                            ProfileAvatar(p, 124.dp)
                        }
                        Spacer(Modifier.height(Tv.s3))
                        Text(p.name, fontSize = Tv.headline, color = if (focused) Tv.text else Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (focused) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(Tv.s12))
            var f by remember { mutableStateOf(false) }
            Row(Modifier.background(if (f) Tv.focusSurface else Color.Transparent, Tv.pill).onFocusChanged { f = it.isFocused }
                .tvClickable({ keep = !keep }, label = "Remember on this TV", selected = keep).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TvSwitch(keep, f)
                Spacer(Modifier.width(Tv.s3))
                Text("Remember on this TV", fontSize = Tv.body, color = if (f) Tv.onFocus else Tv.text2)
            }
        }
    }
}

/** "Change picture" on the TV (like Disney+'s): the owner's ready-made pictures in themed rows that scroll sideways,
 *  then the initial on each colour, and "Remove picture". Photos come from the phone (uploading from a TV isn't practical).
 *  The rows show at once from this TV's saved copy and are refreshed from the server. */
@Composable
fun PicturePicker(p0: Profile, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(Repo.cachedAvatarRows()) }
    LaunchedEffect(Unit) { runCatching { Repo.avatarRows() }.getOrNull()?.let { if (it != rows) rows = it } }
    val p = remember(Repo.version) { Repo.profiles().firstOrNull { it.id == p0.id } ?: p0 }
    val first = remember { FocusRequester() }
    BackHandler { onDone() }
    var anyFocused by remember { mutableStateOf(false) }
    val accent = Profiles.color(p)
    // Saved in the activity's scope: the picker closes at once (onDone), which would cancel a save started in its own scope.
    val owner = androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity
    fun pick(avatar: String, color: Int = p.color) { owner.lifecycleScope.launch { runCatching { Repo.saveProfile(p.id, if (p.named) p.name else "", color, avatar) } }; onDone() }
    // The highlight starts on the current picture if it's in the first row, else on the first picture (always on screen).
    val firstKey = rows.firstOrNull()?.second?.let { r -> if (p.avatar in r) p.avatar else r.first() } ?: "c${p.color}"
    // Keep asking until the highlight is in here (the page underneath can take it back while this opens).
    LaunchedEffect(firstKey) { repeat(30) { if (anyFocused) return@LaunchedEffect; delay(60); runCatching { first.requestFocus() } } }
    @Composable fun Option(key: String, selected: Boolean, label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        Box(Modifier.let { if (key == firstKey) it.focusRequester(first) else it }.onFocusChanged { focused = it.isFocused; if (it.isFocused) anyFocused = true }
            .tvClickable(onClick, label = label, selected = selected)
            .scale(if (focused) 1.08f else 1f)
            .border(3.dp, when { focused -> Tv.focusRing; selected -> accent; else -> Color.Transparent }, CircleShape).padding(6.dp)) { content() }
    }
    Box(Modifier.fillMaxSize().background(Tv.bg).focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        LazyColumn(contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 40.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(Tv.s5)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(p, 72.dp)
                    Spacer(Modifier.width(Tv.s5))
                    Column {
                        Text(if (p.named) "Picture for ${p.name}" else "Your picture", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Tv.text)
                        Text("Pick one below. Your own photos can be added from the app on your phone.", fontSize = Tv.body, color = Tv.muted)
                    }
                }
            }
            items(rows, key = { it.first }) { (theme, urls) ->
                Column {
                    Text(theme, fontSize = Tv.headline, fontWeight = FontWeight.Medium, color = Tv.text2)
                    Spacer(Modifier.height(Tv.s2))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tv.s4), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                        items(urls, key = { it }) { u ->
                            Option(u, p.avatar == u, "$theme picture", { pick(u) }) { AvatarCircle(u, "", Tv.surface2, 112.dp) }
                        }
                    }
                }
            }
            item {
                Column {
                    Text("Initial and colour", fontSize = Tv.headline, fontWeight = FontWeight.Medium, color = Tv.text2)
                    Spacer(Modifier.height(Tv.s2))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Tv.s4), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) {
                        itemsIndexed(Profiles.COLORS) { i, c ->
                            Option("c$i", p.avatar.isEmpty() && p.color == i, "Colour ${i + 1}", { pick("", i) }) { AvatarCircle("", Profiles.initial(p.name), Color(c), 112.dp) }
                        }
                    }
                }
            }
            if (p.avatar.isNotEmpty()) item {
                var f by remember { mutableStateOf(false) }
                Box(Modifier.background(if (f) Tv.focusSurface else Tv.surface, Tv.pill).onFocusChanged { f = it.isFocused }
                    .tvClickable({ pick("") }, label = "Remove picture").padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Text("Remove picture", fontSize = Tv.body, color = if (f) Tv.onFocus else Tv.text2)
                }
            }
        }
    }
}
