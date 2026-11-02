package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import androidx.tv.material3.*

// Building blocks of the TV settings screen (TiviMate-style): a section column on the left, dense 48dp rows on the
// right with the current value at the end. Rows are the shared [TvListRow]: white fill, dark text, no scale.

/** One row in a settings section. Every focusable row does something (D11); [Header], [Info] and [Note] are plain
 *  text and never take focus. */
sealed class SettingRow {
    /** [opens]: the row opens a chooser or form (a quiet chevron after the value); otherwise it acts at once. */
    data class Action(val icon: ImageVector, val title: String, val desc: String, val value: String = "", val danger: Boolean = false,
                      val opens: Boolean = false, val onClick: () -> Unit) : SettingRow()
    data class Toggle(val icon: ImageVector, val title: String, val desc: String, val on: Boolean, val onChange: (Boolean) -> Unit) : SettingRow()
    /** A group heading inside a section ("Subtitles & audio", "Player"). */
    data class Header(val text: String) : SettingRow()
    /** A read-only fact (diagnostics): title on the left, value on the right. */
    data class Info(val icon: ImageVector, val title: String, val value: String, val warn: Boolean = false) : SettingRow()
    data class Note(val text: String) : SettingRow()
    val focusable get() = this is Action || this is Toggle
}

/** A section in the left column. [selected] marks the section the right pane is showing (quiet fill, accent mark). */
@Composable
fun SectionItem(icon: ImageVector, title: String, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    TvListRow(onClick, Modifier.fillMaxWidth().height(SettingsDims.sectionRow).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocus() },
        selected = selected, label = title) { focused ->
        Icon(icon, null, Modifier.size(Tv.icon), tint = if (selected && !focused) Tv.accent else LocalContentColor.current)
        Spacer(Modifier.width(Tv.s3))
        Text(title, fontSize = Tv.body, fontWeight = if (selected) Tv.wStrong else Tv.wLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The pane's title: the section name, then its rows. */
@Composable
fun SettingsPaneTitle(text: String) {
    Text(text, Modifier.padding(start = Tv.s3, top = Tv.s1, bottom = Tv.s1), style = TvType.rowTitle, color = Tv.text)
}

/** A 48dp row: icon, title over a one-line description, and the current value right-aligned. */
@Composable
fun SettingRowView(row: SettingRow, modifier: Modifier = Modifier, first: Boolean = false) {
    when (row) {
        is SettingRow.Note -> Row(modifier.fillMaxWidth().heightIn(min = Tv.s10).padding(horizontal = Tv.s3, vertical = Tv.s2), verticalAlignment = Alignment.CenterVertically) {
            Text(row.text, fontSize = Tv.bodySmall, color = Tv.muted, lineHeight = Tv.bodySmallLine)
        }
        is SettingRow.Header -> Text(row.text.uppercase(), modifier.padding(start = Tv.s3, top = if (first) Tv.s2 else Tv.s5, bottom = Tv.s1),
            fontSize = Tv.label, lineHeight = Tv.labelLine, fontWeight = Tv.wStrong, color = Tv.muted, letterSpacing = SettingsDims.headerTracking)
        is SettingRow.Info -> Row(modifier.fillMaxWidth().height(Tv.listRow).padding(horizontal = Tv.s3), verticalAlignment = Alignment.CenterVertically) {
            Icon(row.icon, null, Modifier.size(Tv.icon), tint = Tv.muted)
            Spacer(Modifier.width(SettingsDims.iconGap))
            Text(row.title, Modifier.weight(1f), fontSize = Tv.body, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(Tv.s4))
            if (row.warn) Box(Modifier.padding(end = Tv.s2).size(SettingsDims.dot).background(Tv.warning, Tv.pill))
            Text(row.value, fontSize = Tv.bodySmall, fontWeight = Tv.wLabel, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = SettingsDims.infoValueMax))
        }
        is SettingRow.Action -> Line(row.icon, row.title, row.desc, row.value, null, row.danger, row.opens, modifier, row.onClick)
        is SettingRow.Toggle -> Line(row.icon, row.title, row.desc, if (row.on) "On" else "Off", row.on, false, false, modifier) { row.onChange(!row.on) }
    }
}

/** [toggle]: null for an action row, else the switch state (drawn as a small switch instead of a text value). */
@Composable
private fun Line(icon: ImageVector, title: String, desc: String, value: String, toggle: Boolean?, danger: Boolean, opens: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TvListRow(onClick, modifier.fillMaxWidth().height(Tv.settingRow), label = listOf(title, desc, value).filter { it.isNotEmpty() }.joinToString(", ")) { focused ->
        val ink = LocalContentColor.current
        Icon(icon, null, Modifier.size(Tv.icon), tint = if (danger && !focused) Tv.danger else ink)
        Spacer(Modifier.width(SettingsDims.iconGap))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = Tv.body, lineHeight = SettingsDims.titleLine, fontWeight = Tv.wLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
            if (desc.isNotEmpty()) Text(desc, fontSize = Tv.caption, lineHeight = Tv.captionLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) ink.copy(alpha = .7f) else Tv.muted)
        }
        if (toggle != null) {
            Spacer(Modifier.width(Tv.s4))
            TvSwitch(toggle, focused)
        } else if (value.isNotEmpty()) {
            Spacer(Modifier.width(Tv.s4))
            Text(value, fontSize = Tv.bodySmall, fontWeight = Tv.wLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = SettingsDims.valueMax), color = if (focused) ink.copy(alpha = .8f) else Tv.text2.copy(alpha = .75f))
        }
        if (opens) {
            Spacer(Modifier.width(Tv.s1))
            Icon(Icons.Default.ChevronRight, null, Modifier.size(Tv.icon), tint = if (focused) ink.copy(alpha = .6f) else Tv.muted.copy(alpha = .7f))
        }
    }
}

/** On a row that sits under a group heading: when it takes focus, what's scrolled into view reaches [above] higher, so
 *  moving up onto the group's first row brings its heading in with it (edge scrolling alone stopped at the row and
 *  left the heading cut off at the top). One request through the same scroll spec, so it's still one smooth move. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Modifier.revealAbove(above: androidx.compose.ui.unit.Dp): Modifier {
    if (above <= 0.dp) return this
    val req = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { above.toPx() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    return this.bringIntoViewRequester(req).onSizeChanged { size = it }.onFocusChanged { f ->
        if (f.isFocused) scope.launch { req.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -px, size.width.toFloat(), size.height.toFloat())) }
    }
}

/** How much of a [SettingRow.Header] sits above the row after it (its text and padding, plus the list's 2dp spacing). */
fun headerReach(first: Boolean) = Tv.labelLine.value.dp + Tv.s1 + (if (first) Tv.s2 else Tv.s5) + 2.dp

/** Sizes used only by the settings screen (kept together rather than inline). */
internal object SettingsDims {
    val sectionColumn = 260.dp
    val sectionRow = 44.dp
    val paneMax = 780.dp
    val iconGap = 14.dp
    val titleLine = Tv.bodySmallLine
    val valueMax = 260.dp
    val infoValueMax = 420.dp
    val dot = 6.dp
    val toTop = 400.dp     // "reveal everything above": the pane's first row scrolls the list back to its top
    val headerTracking = androidx.compose.ui.unit.TextUnit(0.08f, androidx.compose.ui.unit.TextUnitType.Em)
}

/** A short confirmation that fades out by itself (shown bottom-right, inside the overscan margin). */
@Composable
fun SettingsToast(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Box(modifier.background(Tv.surface2, Tv.shape).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(text, fontSize = Tv.bodySmall, color = Tv.text)
    }
}
