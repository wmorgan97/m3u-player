package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

// Building blocks of the TV settings screen (TiviMate-style): a section column on the left, dense 48dp rows on the
// right with the current value at the end. Focus is the app's list treatment: white fill, dark text, 8dp corners, no scale.

/** One row in a settings section. Every focusable row does something (D11); [Note] is plain text and never takes focus. */
sealed class SettingRow {
    data class Action(val icon: ImageVector, val title: String, val desc: String, val value: String = "", val danger: Boolean = false, val onClick: () -> Unit) : SettingRow()
    data class Toggle(val icon: ImageVector, val title: String, val desc: String, val on: Boolean, val onChange: (Boolean) -> Unit) : SettingRow()
    data class Note(val text: String) : SettingRow()
    val focusable get() = this !is Note
}

private val rowShape = RoundedCornerShape(8.dp)

@Composable
private fun rowColors(selected: Boolean = false) = ClickableSurfaceDefaults.colors(
    containerColor = if (selected) Tv.surface2 else Color.Transparent, focusedContainerColor = Tv.text,
    contentColor = Tv.text, focusedContentColor = Tv.bg)

/** A section in the left column. [selected] keeps a quiet fill on the section the right pane is showing. */
@Composable
fun SectionItem(icon: ImageVector, title: String, selected: Boolean, requester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().height(44.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(rowShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), colors = rowColors(selected)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = Tv.body, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A 48dp row: icon, title over a one-line description, and the current value right-aligned. */
@Composable
fun SettingRowView(row: SettingRow, modifier: Modifier = Modifier) {
    when (row) {
        is SettingRow.Note -> Row(modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.text, fontSize = Tv.bodySmall, color = Tv.muted, lineHeight = Tv.bodySmallLine)
        }
        is SettingRow.Action -> Line(row.icon, row.title, row.desc, row.value, false, row.danger, modifier, row.onClick)
        is SettingRow.Toggle -> Line(row.icon, row.title, row.desc, if (row.on) "On" else "Off", row.on, false, modifier) { row.onChange(!row.on) }
    }
}

@Composable
private fun Line(icon: ImageVector, title: String, desc: String, value: String, on: Boolean, danger: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth().height(48.dp).onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(rowShape), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), colors = rowColors()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val ink = LocalContentColor.current
            Icon(icon, null, Modifier.size(20.dp), tint = if (danger && !focused) Tv.danger else ink)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = Tv.body, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (danger && !focused) Tv.danger else ink)
                if (desc.isNotEmpty()) Text(desc, fontSize = Tv.caption, lineHeight = Tv.captionLine, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (focused) ink.copy(alpha = .7f) else Tv.muted)
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Text(value, fontSize = Tv.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1,
                    color = when { focused -> ink.copy(alpha = .8f); on -> Tv.accent; else -> Tv.muted })
            }
        }
    }
}

/** A short confirmation that fades out by itself (shown bottom-right, inside the overscan margin). */
@Composable
fun SettingsToast(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Box(modifier.background(Tv.surface2, Tv.shape).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Text(text, fontSize = Tv.bodySmall, color = Tv.text)
    }
}

/** One text box in a [FormDialog]. */
data class FormField(val label: String, val value: String = "", val hint: String = "", val secret: Boolean = false, val url: Boolean = false)

/** A small native form (playlist login, server key, TMDB key): labelled text boxes, then Save / Cancel. OK on a box opens
 *  the on-screen keyboard; Up/Down move between boxes (closing the keyboard); the keyboard's Next/Done moves on.
 *  [busy] shows a quiet "Checking…" while the caller works; [error] appears in red above the buttons. An empty form starts
 *  on its first box (keyboard up); a filled-in one (editing) starts on Save, so the keyboard doesn't cover what's there. */
@Composable
fun FormDialog(title: String, intro: String?, fields: List<FormField>, submit: String = "Save", busy: Boolean = false, error: String? = null,
               onSubmit: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val values = remember { fields.map { it.value }.toMutableStateList() }
    val first = remember { FocusRequester() }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.freshOkOnly().width(520.dp).background(Tv.surface, Tv.bigShape).border(1.dp, Tv.line, Tv.bigShape).padding(24.dp)) {
            Text(title, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
            if (!intro.isNullOrBlank()) Text(intro, fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, color = Tv.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            fields.forEachIndexed { i, f ->
                var on by remember { mutableStateOf(false) }
                Text(f.label, fontSize = Tv.label, fontWeight = FontWeight.SemiBold, color = Tv.text2, modifier = Modifier.padding(bottom = 6.dp, top = if (i == 0) 0.dp else 12.dp))
                Box(Modifier.fillMaxWidth().height(44.dp).background(if (on) Tv.surface3 else Tv.surface2, rowShape).border(2.dp, if (on) Tv.focusRing else Color.Transparent, rowShape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart) {
                    if (values[i].isEmpty() && f.hint.isNotEmpty()) Text(f.hint, fontSize = Tv.body, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    androidx.compose.foundation.text.BasicTextField(values[i], { values[i] = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Tv.text, fontSize = Tv.body),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Tv.accent),
                        visualTransformation = if (f.secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false,
                            keyboardType = when { f.secret -> androidx.compose.ui.text.input.KeyboardType.Password; f.url -> androidx.compose.ui.text.input.KeyboardType.Uri; else -> androidx.compose.ui.text.input.KeyboardType.Text },
                            imeAction = if (i < fields.lastIndex) androidx.compose.ui.text.input.ImeAction.Next else androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }, onDone = { kb?.hide(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) }),
                        modifier = Modifier.fillMaxWidth().let { if (i == 0 && fields.first().value.isEmpty()) it.focusRequester(first) else it }.onFocusChanged { on = it.isFocused }
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (e.key) {
                                    Key.DirectionDown -> { kb?.hide(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down); true }
                                    Key.DirectionUp -> { kb?.hide(); focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Up); true }
                                    else -> false
                                }
                            })
                }
            }
            if (error != null) Text(error, fontSize = Tv.bodySmall, color = Tv.danger, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (!busy) onSubmit(values.map { it.trim() }) }, modifier = if (fields.isEmpty() || fields.first().value.isNotEmpty()) Modifier.focusRequester(first) else Modifier) { Text(if (busy) "Checking…" else submit, fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold) }
                OutlinedButton(onClick = onDismiss) { Text("Cancel", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold) }
            }
        }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
    }
}
