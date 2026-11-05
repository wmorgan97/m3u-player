package com.wmorgan97.m3uplayer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** One viewer profile ("Who's watching?"). [named]: false for the default profile until someone names it. */
data class Profile(val id: String, val name: String, val color: Int, val deleted: Boolean = false, val named: Boolean = true)

/** Viewer profiles: the same rules as the web app (js/profiles.js) and the server (server/sync.js). */
object Profiles {
    const val MAIN = "main"
    const val MAX = 5
    /** The personal parts of /sync: per profile. Everything else (playlists, TVs, Play on TV) is shared. */
    val PERSONAL = listOf("favorites", "favAt", "recents", "recReset", "progress", "reminders", "follows", "watchlater")
    /** Calm tile colours, the web app's PROFILE_COLORS in the same order (a profile's colour is an index). */
    val COLORS = listOf(0xFF3D7BE0, 0xFFD4813A, 0xFF2A9D8F, 0xFF8A6FD1, 0xFFC95B8A, 0xFF5B8C3A, 0xFFC0504D, 0xFF4F6D8F)
    fun color(p: Profile) = Color(COLORS[Math.floorMod(p.color, COLORS.size)])
    fun initial(name: String) = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"
    /** Main first (always there, "Main" until named), then the others in the order they were added (ids are time-based). */
    fun list(raw: List<Profile>): List<Profile> {
        val m = raw.firstOrNull { it.id == MAIN && !it.deleted && it.name.isNotBlank() }
        val main = Profile(MAIN, m?.name ?: "Main", m?.color ?: 0, named = m != null)
        return listOf(main) + raw.filter { it.id != MAIN && !it.deleted && it.name.isNotBlank() }.sortedBy { it.id }
    }
    fun shouldAsk(list: List<Profile>, current: String, remember: Boolean) = list.size > 1 && !(remember && list.any { it.id == current })
    fun nextColor(list: List<Profile>): Int { val used = list.map { it.color }.toSet(); return COLORS.indices.firstOrNull { it !in used } ?: (list.size % COLORS.size) }
    fun newId(now: Long = System.currentTimeMillis()) = "p" + now.toString(36) + (0 until 3).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
}

/** A profile's square: its initial on its colour. */
@Composable
fun ProfileAvatar(p: Profile, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(Profiles.color(p), RoundedCornerShape(size * .2f)), contentAlignment = Alignment.Center) {
        Text(Profiles.initial(p.name), color = Color.White, fontSize = (size.value * .44f).sp, fontWeight = FontWeight.SemiBold)
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
                        Box(Modifier.scale(if (focused) 1.08f else 1f).border(3.dp, if (focused) Tv.focusRing else Color.Transparent, RoundedCornerShape(30.dp)).padding(5.dp)) {
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
