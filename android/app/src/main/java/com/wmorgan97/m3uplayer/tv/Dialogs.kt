package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

// Shared pop-ups: options (hold OK), a programme's details, the PIN pad, small forms. All are a [TvDialogCard]: options
// are [TvListRow]s, actions are [TvButton]s (the first one Primary), so a dialog reads like the rest of the app.

/** A short list of choices (hold OK on a card or row, a playlist's menu). "Remove …" entries are in the danger colour. */
@Composable
fun Options(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        TvDialogCard(440.dp) {
            Text(title, fontSize = Tv.body, lineHeight = Tv.bodyLine, fontWeight = Tv.wStrong, color = Tv.text, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEachIndexed { i, (label, fn) ->
                    OptionRow(label, if (i == 0) Modifier.focusRequester(first) else Modifier, danger = label.startsWith("Remove"), onClick = fn)
                }
                OptionRow("Cancel", Modifier, onClick = onDismiss)
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}

@Composable
private fun OptionRow(label: String, modifier: Modifier, danger: Boolean = false, onClick: () -> Unit) {
    TvListRow(onClick, modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 12.dp), label = label) { f ->
        Text(label, fontSize = Tv.bodySmall, fontWeight = Tv.wLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (danger && !f) Tv.danger else androidx.tv.material3.LocalContentColor.current)
    }
}

/** A programme's details with what you can do: watch, watch from the start / play from the archive, remind me. */
@Composable
fun ProgrammeDialog(act: TvActivity, c: Channel, p: Programme, now: Long, chans: List<Channel>, teams: List<String> = emptyList(), onDismiss: () -> Unit) {
    val onAir = p.start <= now && p.end > now
    val archive = Repo.catchupUrl(c, p.start, p.end)
    val first = remember { FocusRequester() }
    var reminded by remember { mutableStateOf(Repo.hasReminder(c.url, p.start)) }
    val following = remember { mutableStateMapOf<String, Boolean>().apply { teams.forEach { put(it, Repo.isFollowing(it)) } } }
    val actions = buildList<Pair<String, () -> Unit>> {
        if (onAir) add("Watch now" to { onDismiss(); act.playLive(chans, chans.indexOf(c).coerceAtLeast(0)) })
        if (archive != null) add((if (onAir) "Watch from the start" else "Play (catch-up)") to { onDismiss(); act.playCatchup(c, p) })
        if (p.start > now) add((if (reminded) "Cancel reminder" else "Remind me") to { Repo.toggleReminder(c, p); reminded = !reminded })
        if (!onAir) add("Watch the channel now" to { onDismiss(); act.playLive(chans, chans.indexOf(c).coerceAtLeast(0)) })
        // A match ("Arsenal v Spurs"): follow either side (My teams on the Sports page shows their next match).
        for (t in teams) add((if (following[t] == true) "Following $t ✓" else "Follow $t") to {
            val on = Repo.toggleFollow(t); following[t] = on
            if (on && p.start > now + 120_000 && !reminded) { Repo.toggleReminder(c, p); reminded = true }   // this match only
        })
        add("Close" to onDismiss)
    }
    Dialog(onDismissRequest = onDismiss) {
        TvDialogCard(600.dp) {
            if (!c.name.equals(p.title, true)) Text(c.name, fontSize = Tv.label, color = Tv.muted, fontWeight = Tv.wStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.title, fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            Text("${dayLabel(p.start, now)}${hhmm(p.start)}–${hhmm(p.end)}  ·  ${(p.end - p.start) / 60_000} min" + (if (onAir) "  ·  On now" else if (p.end <= now) "  ·  Finished" else "") + (if (reminded) "  ·  Reminder set" else ""),
                fontSize = Tv.bodySmall, color = Tv.text2, modifier = Modifier.padding(top = 6.dp))
            if (p.desc.isNotBlank()) Text(p.desc, fontSize = Tv.bodySmall, color = Tv.text2, lineHeight = Tv.bodySmallLine, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(20.dp))
            // Wraps onto a second line rather than pushing a button out of the dialog.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, (label, fn) ->
                    TvButton(label, fn, if (i == 0) Modifier.focusRequester(first) else Modifier, style = if (i == 0) TvButtonStyle.Primary else TvButtonStyle.Secondary)
                }
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}

/** Four-digit PIN pad (the Shield remote has no number keys, so it's on screen; number keys work too). null = cancelled. */
@Composable
fun PinDialog(title: String, verify: ((String) -> Boolean)? = null, onResult: (String?) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }   // with [verify]: a wrong PIN says so and clears; 5 wrong tries closes
    val first = remember { FocusRequester() }
    fun press(d: String) {
        if (pin.length < 4) pin += d
        if (pin.length < 4) return
        val v = verify
        if (v == null || v(pin)) { onResult(pin); return }
        wrong++; pin = ""
        if (wrong >= 5) onResult(null)
    }
    Dialog(onDismissRequest = { onResult(null) }) {
        TvDialogCard(360.dp, Modifier.onPreviewKeyEvent { e ->
            val n = e.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_0
            if (e.type == KeyEventType.KeyDown && n in 0..9) { press("$n"); true } else false
        }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (wrong > 0) "Wrong PIN, try again" else title, fontSize = Tv.body, fontWeight = Tv.wStrong, color = if (wrong > 0) Tv.danger else Tv.text, maxLines = 2)
            if (wrong > 0) Text("${5 - wrong} tries left", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(4) { k -> Box(Modifier.size(16.dp).background(if (k < pin.length) Tv.text else Tv.surface3, RoundedCornerShape(8.dp))) } }
            Spacer(Modifier.height(18.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
            for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                for (k in keys.subList(r * 3, r * 3 + 3)) {
                    TvListRow(onClick = { when (k) { "⌫" -> pin = pin.dropLast(1); "✕" -> onResult(null); else -> press(k) } },
                        modifier = Modifier.size(width = 72.dp, height = 48.dp).background(Tv.surface2, Tv.rowShape).let { if (k == "1") it.focusRequester(first) else it },
                        contentPadding = PaddingValues(0.dp), label = when (k) { "⌫" -> "Delete"; "✕" -> "Cancel"; else -> k }) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(k, fontSize = Tv.body, fontWeight = Tv.wStrong, color = androidx.tv.material3.LocalContentColor.current) }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
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
    Dialog(onDismissRequest = onDismiss) {
        TvDialogCard(520.dp) {
            Text(title, fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle, color = Tv.text)
            if (!intro.isNullOrBlank()) Text(intro, fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, color = Tv.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            fields.forEachIndexed { i, f ->
                var on by remember { mutableStateOf(false) }
                Text(f.label, fontSize = Tv.label, fontWeight = Tv.wStrong, color = Tv.text2, modifier = Modifier.padding(bottom = 6.dp, top = if (i == 0) 0.dp else 12.dp))
                // A field keeps its text readable when focused: it lifts and gets the white focus outline (no fill).
                Box(Modifier.fillMaxWidth().height(44.dp).background(if (on) Tv.surface3 else Tv.surface2, Tv.rowShape).border(2.dp, if (on) Tv.focusRing else Color.Transparent, Tv.rowShape).padding(horizontal = 14.dp),
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
                TvButton(if (busy) "Checking…" else submit, { if (!busy) onSubmit(values.map { it.trim() }) },
                    if (fields.isEmpty() || fields.first().value.isNotEmpty()) Modifier.focusRequester(first) else Modifier, style = TvButtonStyle.Primary)
                TvButton("Cancel", onDismiss)
            }
        }
        LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    }
}
