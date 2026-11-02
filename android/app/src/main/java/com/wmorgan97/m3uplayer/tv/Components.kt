package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// The shared component layer: ONE focus language, applied per element type. Tokens are in Theme.kt, picture cards in
// Cards.kt, scrolling in Motion.kt. Rules (also in android/docs/TV_DESIGN_SYSTEM.md):
//  - White means focus, everywhere. Nothing else on screen is solid white.
//  - Picture-first things (posters, 16:9 cards, channel tiles, sport cards) keep their picture: a no-bounce spring
//    grows them a little (posters 1.07, 16:9 1.05, wide rows 1.02), a 3dp white frame and a soft white halo fade in.
//  - Text-first things (list rows, buttons, chips, tabs, menu and dialog options, guide cells) turn solid white with
//    dark content. That flip is instant: a fade would pass through a mid-grey where the text can't be read. Rows don't
//    grow; buttons and chips grow a touch (<=1.04) on the same spring as cards.
//  - Selected-but-not-focused (the open group, the current tab or season) is marked in accent, never white.

// ---------- Focus ----------

/** How an element shows focus. [Card] for pictures, [Row] for list rows, [Pill] for buttons/chips, [Disc] for icon
 *  buttons. Cards come in three sizes; [card] makes one with its own scale or shape. */
@Immutable
class FocusStyle private constructor(val kind: Kind, val scale: Float, val shape: Shape) {
    enum class Kind { Card, Row, Pill, Disc }
    val frame get() = kind == Kind.Card
    val fill get() = kind != Kind.Card
    fun copy(scale: Float = this.scale, shape: Shape = this.shape) = FocusStyle(kind, scale, shape)
    override fun equals(other: Any?) = other is FocusStyle && other.kind == kind && other.scale == scale && other.shape == shape
    override fun hashCode() = (kind.hashCode() * 31 + scale.hashCode()) * 31 + shape.hashCode()
    companion object {
        val Card = FocusStyle(Kind.Card, Tv.scaleCard, Tv.shape)
        val CardWide = FocusStyle(Kind.Card, Tv.scaleWide, Tv.shape)
        val CardLarge = FocusStyle(Kind.Card, Tv.scaleLarge, Tv.shape)
        val Row = FocusStyle(Kind.Row, 1f, Tv.rowShape)
        val Pill = FocusStyle(Kind.Pill, Tv.scaleButton, Tv.pill)
        val Disc = FocusStyle(Kind.Disc, Tv.scaleDisc, CircleShape)
        fun card(scale: Float = Tv.scaleCard, shape: Shape = Tv.shape) = FocusStyle(Kind.Card, scale, shape)
    }
}

/**
 * The focus treatment for [style]. Put it BEFORE the element's `focusable()` / focus target (it watches the focus of
 * what follows it). It draws and scales only: no recomposition when focus moves, so a move costs two redraws.
 *  - Card: content clipped to the style's shape, spring scale, white frame and halo fading in.
 *  - Row / Pill / Disc: a solid white fill behind the content while focused (Pill/Disc also spring-scale). The
 *    content's own colour flip (to [Tv.onFocus]) is the component's job: [TvListRow], [TvButton], [TvChip] do it.
 * [interaction]: optionally follow a focus [MutableInteractionSource] (e.g. a text field's) instead of focus events.
 */
fun Modifier.tvFocus(style: FocusStyle, interaction: MutableInteractionSource? = null): Modifier = this then TvFocusElement(style, interaction) then TvFocusLayerElement

// Two nodes: the first watches focus, runs the springs and draws the halo, fill and frame around the element (outside
// any scaling layer); the second, right after it, is the element's own layer (scale, and the card's rounded clip),
// reading the first node's spring. (One node doing both drew inside its own layer, where the clip hid the frame.)
private object TvFocusLayerElement : ModifierNodeElement<TvFocusLayerNode>() {
    override fun create() = TvFocusLayerNode()
    override fun update(node: TvFocusLayerNode) {}
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

private class TvFocusLayerNode : Modifier.Node(), LayoutModifierNode {
    private var owner: TvFocusNode? = null
    override fun onAttach() { owner = findNearestAncestor(TvFocusNode.KEY) as? TvFocusNode }
    override fun onDetach() { owner = null }
    private val layerBlock: GraphicsLayerScope.() -> Unit = {
        val o = owner
        if (o != null) {
            val s = o.currentScale()
            scaleX = s; scaleY = s
            if (o.style.kind == FocusStyle.Kind.Card) { shape = o.style.shape; clip = true }
        }
    }
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) { p.placeWithLayer(0, 0, layerBlock = layerBlock) }
    }
}

private class TvFocusElement(val style: FocusStyle, val interaction: MutableInteractionSource?) : ModifierNodeElement<TvFocusNode>() {
    override fun create() = TvFocusNode(style, interaction)
    override fun update(node: TvFocusNode) { node.update(style, interaction) }
    override fun equals(other: Any?) = other is TvFocusElement && other.style == style && other.interaction === interaction
    override fun hashCode() = style.hashCode() * 31 + (interaction?.hashCode() ?: 0)
    override fun InspectorInfo.inspectableProperties() { name = "tvFocus"; properties["style"] = style.kind }
}

private class TvFocusNode(var style: FocusStyle, var interaction: MutableInteractionSource?) : Modifier.Node(), DrawModifierNode, FocusEventModifierNode, TraversableNode {
    override val traverseKey get() = KEY
    fun currentScale() = 1f + (style.scale - 1f) * grow.value
    private val grow = Animatable(0f)    // 0..1 toward the focused scale (spring)
    private val fade = Animatable(0f)    // 0..1 frame / halo opacity (quicker spring)
    private var focused = false
    private var job: Job? = null
    private var watch: Job? = null
    private var outlineSize = Size.Unspecified
    private var outline: Outline? = null
    private var frameOutline: Outline? = null
    private val glowOutlines = arrayOfNulls<Outline>(GLOW.size)

    fun update(s: FocusStyle, i: MutableInteractionSource?) {
        if (s.shape != style.shape) outlineSize = Size.Unspecified
        style = s
        if (i !== interaction) { interaction = i; if (isAttached) watchInteraction() }
        invalidateDraw()
    }

    override fun onAttach() {
        watchInteraction()
        // A lazy row reuses this node for another card: it starts unfocused, never with the last card's scale or frame.
        if (grow.value != 0f || fade.value != 0f) coroutineScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { grow.snapTo(0f); fade.snapTo(0f); invalidateDraw() }
    }
    override fun onDetach() { job = null; watch = null; focused = false }

    private fun watchInteraction() {
        watch?.cancel(); watch = null
        val src = interaction ?: return
        watch = coroutineScope.launch {
            var n = 0
            src.interactions.collect { when (it) { is FocusInteraction.Focus -> n++; is FocusInteraction.Unfocus -> n--; else -> {} }; set(n > 0) }
        }
    }

    override fun onFocusEvent(focusState: FocusState) { if (interaction == null) set(focusState.isFocused) }

    private fun set(f: Boolean) {
        if (f == focused) return
        focused = f
        if (!isAttached) return
        invalidateDraw()   // (the fill of a Row / Pill flips at once)
        job?.cancel()
        // animateTo keeps the current velocity, so a quick in-out-in never restarts from rest.
        job = coroutineScope.launch {
            // (Each frame redraws this node explicitly: the frame / halo / fill are drawn from these values.)
            launch { grow.animateTo(if (f) 1f else 0f, Tv.focusSpring) { invalidateDraw() } }
            launch { fade.animateTo(if (f) 1f else 0f, Tv.focusFadeSpring) { invalidateDraw() } }
        }
    }

    override fun ContentDrawScope.draw() {
        val st = style
        if (outlineSize != size) {
            outlineSize = size
            outline = st.shape.createOutline(size, layoutDirection, this)
            val stroke = Tv.focusFrame.toPx()
            frameOutline = st.shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
            GLOW.forEachIndexed { k, (spread, _) -> val sp = spread.dp.toPx(); glowOutlines[k] = st.shape.createOutline(Size(size.width + sp * 2, size.height + sp * 2), layoutDirection, this) }
        }
        val f = fade.value
        if (st.kind == FocusStyle.Kind.Card) {
            val s = 1f + (st.scale - 1f) * grow.value
            if (f > 0.01f) scale(s, pivot = center) {   // the halo, under the (scaled) card
                GLOW.forEachIndexed { k, (spread, a) -> val sp = spread.dp.toPx(); translate(-sp, -sp) { drawOutline(glowOutlines[k]!!, Tv.focusGlow.copy(alpha = a * f)) } }
            }
            drawContent()
            if (f > 0.01f) scale(s, pivot = center) {
                val stroke = Tv.focusFrame.toPx()
                translate(stroke / 2, stroke / 2) { drawOutline(frameOutline!!, Tv.focusRing.copy(alpha = f), style = Stroke(stroke)) }
            }
        } else {
            // The fill is drawn inside the scaled layer's parent space, so scale it the same way.
            if (focused) { val s = 1f + (st.scale - 1f) * grow.value; scale(s, pivot = center) { drawOutline(outline!!, Tv.focusSurface) } }
            drawContent()
        }
    }

    companion object {
        const val KEY = "m3u.tvFocus"
        /** The halo: twelve fine 1dp steps out from the card's edge, each adding a little white, so it fades out evenly to
         *  ~12dp with no visible rings (dp spread to opacity at full focus; the layers add up to ~16% at the edge). */
        val GLOW = (1..12).map { k -> k.toFloat() to .0135f }
    }
}

/** OK (on release, as tv-material does) clicks; holding OK (the remote's first key repeat) long-clicks. Semantics say
 *  it's a button, with [label] as its only description when given (one accessibility node per element). */
@Composable
fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, enabled: Boolean = true, label: String? = null, selected: Boolean? = null): Modifier {
    val click by rememberUpdatedState(onClick)
    return this
        .let { if (onLongClick != null && enabled) it.holdOk(onLongClick) else it }
        .onKeyEvent { e -> if (enabled && e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { click(); true } else false }
        .let { m ->
            val sem: androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit = {
                role = Role.Button
                if (label != null) contentDescription = label
                if (selected != null) this.selected = selected
                onClick { if (enabled) click(); enabled }
                if (onLongClick != null) onLongClick { onLongClick(); true }
            }
            if (label != null) m.clearAndSetSemantics(sem) else m.semantics(mergeDescendants = true, properties = sem)
        }
        .focusable()   // (a disabled control still takes focus, dimmed: the highlight never falls off the page)
}

// ---------- Buttons, chips, rows ----------

enum class TvButtonStyle { Primary, Secondary, Tertiary }

/** A pill button. Primary: accent; Secondary: translucent dark with a hairline; Tertiary: text only. Focused: white with
 *  dark content, grown 1.04 on the focus spring. Disabled: dimmed, OK does nothing (it can still hold the highlight). */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, style: TvButtonStyle = TvButtonStyle.Secondary, enabled: Boolean = true,
             onLongClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val (bg, fg, edge) = when (style) {
        TvButtonStyle.Primary -> Triple(Tv.accent, Tv.onAccent, Color.Transparent)
        TvButtonStyle.Secondary -> Triple(Tv.surface2.copy(alpha = .72f), Tv.text, Tv.lineStrong)
        TvButtonStyle.Tertiary -> Triple(Color.Transparent, Tv.text2, Color.Transparent)
    }
    Row(modifier.height(Tv.buttonHeight)
        .then(if (enabled) Modifier else Modifier.graphicsAlpha(.4f))
        .background(bg, Tv.pill).let { if (edge != Color.Transparent) it.border(1.dp, edge, Tv.pill) else it }
        .onFocusedChange { focused = it }
        .tvFocus(FocusStyle.Pill)
        .tvClickable(onClick, onLongClick, enabled, label = text)
        .padding(start = if (icon != null) 16.dp else 22.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val ink = if (focused) Tv.onFocus else fg
        if (icon != null) { Icon(icon, null, Modifier.size(Tv.icon), tint = ink); Spacer(Modifier.width(8.dp)) }
        Text(text, style = TvType.button, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon-only button (the mic, player controls): a translucent dark disc, accent when [selected], white focused. */
@Composable
fun TvIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, selected: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.size(size).background(if (selected) Tv.accent else Tv.surface2.copy(alpha = .72f), CircleShape)
        .onFocusedChange { focused = it }
        .tvFocus(FocusStyle.Disc)
        .tvClickable(onClick, label = contentDescription, selected = selected), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .46f), tint = when { focused -> Tv.onFocus; selected -> Tv.onAccent; else -> Tv.text2 })
    }
}

/** A pill for a choice among a few (seasons, guide groups, UK / all countries). Selected: accent outline and tint.
 *  [icon]: an optional small icon before the text, in the text's colour (the guide's "Now"). */
@Composable
fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null, onLongClick: (() -> Unit)? = null,
           icon: ImageVector? = null) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.height(Tv.chipHeight)
        .background(if (selected) Tv.accent.copy(alpha = .16f) else Tv.surface2.copy(alpha = .72f), Tv.pill)
        .border(if (selected) 1.5.dp else 1.dp, if (selected) Tv.accent else Tv.line, Tv.pill)
        .onFocusedChange { focused = it; onFocus?.invoke(it) }
        .tvFocus(FocusStyle.Pill.copy(scale = 1.03f))
        .tvClickable(onClick, onLongClick, label = text, selected = selected)
        .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp), contentAlignment = Alignment.Center) {
        val ink = when { focused -> Tv.onFocus; selected -> Tv.text; else -> Tv.text2 }
        if (icon == null) Text(text, style = TvType.label, fontWeight = if (focused || selected) Tv.wStrong else Tv.wLabel, maxLines = 1, color = ink)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(Tv.iconS), tint = ink)
            Spacer(Modifier.width(6.dp))
            Text(text, style = TvType.label, fontWeight = if (focused || selected) Tv.wStrong else Tv.wLabel, maxLines = 1, color = ink)
        }
    }
}

/** A small on/off switch (settings rows, anywhere a toggle sits at the end of a [TvListRow]). On: accent track, white
 *  knob at the end. Off: quiet track, knob at the start. On a [focused] (white) row the off track darkens so it still reads. */
@Composable
fun TvSwitch(on: Boolean, focused: Boolean, modifier: Modifier = Modifier) {
    val track = when { on -> Tv.accent; focused -> Color.Black.copy(alpha = .22f); else -> Tv.surface3 }
    val knob = when { on -> Tv.onAccent; focused -> Tv.onFocus.copy(alpha = .55f); else -> Tv.muted }
    Box(modifier.size(Tv.switchWidth, Tv.switchHeight).background(track, Tv.pill).padding(SWITCH_PAD),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(Tv.switchHeight - SWITCH_PAD * 2).background(knob, Tv.pill))
    }
}
private val SWITCH_PAD = 3.dp

/** A list row (channels, groups, settings, menu options): transparent, white when focused with content in
 *  [Tv.onFocus] (also given to [content] and as tv-material's LocalContentColor). [selected]: the open one, a quiet
 *  raised fill with an accent mark at its start. No scale. Height and inner padding are the caller's. */
@Composable
fun TvListRow(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, onLongClick: (() -> Unit)? = null,
              contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp), label: String? = null, onFocus: ((Boolean) -> Unit)? = null,
              content: @Composable RowScope.(focused: Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier
        .let { if (selected) it.background(Tv.surface2, Tv.rowShape) else it }
        .onFocusedChange { focused = it; onFocus?.invoke(it) }
        .tvFocus(FocusStyle.Row)
        .tvClickable(onClick, onLongClick, label = label, selected = if (selected) true else null)) {
        if (selected && !focused) Box(Modifier.align(Alignment.CenterStart).padding(start = 3.dp).size(3.dp, 16.dp).background(Tv.accent, Tv.pill))
        val ink = if (focused) Tv.onFocus else Tv.text
        CompositionLocalProvider(LocalContentColor provides ink, LocalRowInk provides ink) {
            Row(Modifier.fillMaxSize().padding(contentPadding), verticalAlignment = Alignment.CenterVertically) { content(focused) }
        }
    }
}

/** A side panel over video or a page (player panels, menus): strong scrim, large corners, 24dp padding. */
@Composable
fun TvPanel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(Tv.scrimStrong, Tv.shapeL).padding(Tv.panelPadding)) {
        if (title != null) Text(title, style = TvType.headline, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

/** A dialog's card (options, programme details, PIN, forms): raised surface, hairline edge, large corners. OK only
 *  counts once pressed afresh inside it ([freshOkOnly]). */
@Composable
fun TvDialogCard(width: Dp, modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.freshOkOnly().width(width).background(Tv.surface, Tv.shapeL).border(1.dp, Tv.line, Tv.shapeL).padding(Tv.panelPadding),
        horizontalAlignment = horizontalAlignment, content = content)
}

enum class BadgeTone { Neutral, Accent, Live, Star }

/** A small label on a picture or in a meta line: EPISODE, 4K, LIVE, ★ 8.1. */
@Composable
fun TvBadge(text: String, modifier: Modifier = Modifier, tone: BadgeTone = BadgeTone.Neutral) {
    val (bg, fg) = when (tone) {
        BadgeTone.Neutral -> Tv.scrim to Tv.text
        BadgeTone.Accent -> Tv.accentDeep to Tv.text
        BadgeTone.Live -> Tv.live.copy(alpha = .25f) to Tv.text
        BadgeTone.Star -> Tv.scrim to Tv.star
    }
    // (Most badges sit on cards in rows, so a plain badge is one text node: no Row, no tv-material Text layer.)
    if (tone != BadgeTone.Live) { FastText(text, badgeStyle(fg), modifier.background(bg, Tv.shapeS).padding(horizontal = 6.dp, vertical = 2.dp), inherit = false); return }
    Row(modifier.background(bg, Tv.shapeS).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(Tv.liveDot).clip(CircleShape).background(Tv.live)); Spacer(Modifier.width(5.dp))
        FastText(text, badgeStyle(fg), inherit = false)
    }
}
private val badgeStyles = HashMap<Color, androidx.compose.ui.text.TextStyle>()
private fun badgeStyle(fg: Color) = badgeStyles.getOrPut(fg) { TvType.micro.merge(androidx.compose.ui.text.TextStyle(color = fg, fontWeight = Tv.wStrong)) }

/** The content colour of the [TvListRow] around (white, or dark on a focused row); null outside one. */
internal val LocalRowInk = compositionLocalOf<Color?> { null }
private val TRACK = Color(0x59FFFFFF)

/** A thin progress line (under a picture, in a row). Accent; [live] in the live red (a thin line is the one place it's
 *  used besides a dot). 4dp unless the modifier sets a height. The track is white at 35%; inside a focused (white)
 *  [TvListRow] it follows the row's dark content colour instead, so it never vanishes into the highlight. */
@Composable
fun TvProgress(fraction: Float, modifier: Modifier = Modifier, live: Boolean = false) {
    val track = LocalRowInk.current?.copy(alpha = .35f) ?: TRACK
    Box(modifier.height(4.dp).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(if (live) Tv.live else Tv.accent))
    }
}

// ---------- States ----------

/** Nothing here: an icon, a line, what to do, and optionally a button that does it. */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: Pair<String, () -> Unit>? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).background(Tv.surface, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Tv.muted, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(18.dp))
            Text(title, style = TvType.headline, color = Tv.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (message.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(message, style = TvType.bodySmall, color = Tv.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            if (action != null) { Spacer(Modifier.height(20.dp)); TvButton(action.first, action.second, style = TvButtonStyle.Primary) }
        }
    }
}

/** Something failed: what, why in plain words, and Try again when there's something to retry. */
@Composable
fun ErrorState(title: String, message: String, retry: (() -> Unit)? = null) =
    EmptyState(androidx.compose.material.icons.Icons.Default.CloudOff, title, message, retry?.let { "Try again" to it })

// ---------- Loading ----------

/** One soft moving sheen, shared by every placeholder on screen (one animation, not one per block). */
@Composable
fun shimmerBrush(): Brush {
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    return Brush.linearGradient(listOf(Tv.surface2, Tv.surface3, Tv.surface2), start = Offset(t * 1400f - 400f, 0f), end = Offset(t * 1400f, 300f))
}
@Composable
fun Modifier.shimmer(): Modifier = this.background(shimmerBrush())

/** A grey block in the shared sheen (text lines, pictures) while something loads. */
@Composable
fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, brush: Brush = shimmerBrush(), shape: Shape = Tv.shapeS) =
    Box(modifier.width(width).height(height).clip(shape).background(brush))

/** A row of grey card-shaped blocks while the real row loads: the page has shape from the first frame. */
@Composable
fun SkeletonRow(count: Int = 7, width: Dp = Tv.poster, aspect: Float = 2f / 3f) {
    val brush = shimmerBrush()
    Row(Modifier.padding(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
        repeat(count) {
            Column(Modifier.width(width)) {
                Box(Modifier.width(width).aspectRatio(aspect).clip(Tv.shape).background(brush))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(width * .7f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
        }
    }
}

@Composable
fun SkeletonTitle() {
    Box(Modifier.padding(start = Tv.margin, top = Tv.rowGap, bottom = 12.dp).width(180.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).shimmer())
}

// ---------- small helpers ----------

/** onFocusChanged that only reports focus of the element itself (not a descendant's). */
fun Modifier.onFocusedChange(onChange: (Boolean) -> Unit): Modifier = this.onFocusChanged { onChange(it.isFocused) }

private fun Modifier.graphicsAlpha(a: Float) = this.graphicsLayer { alpha = a; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }

// ---------- Text for hot paths ----------

/**
 * Plain one-style text, as tv-material's `Text` draws it, for things built many times a second (card captions, badges):
 *  - tv-material 1.0's Text always passes an onTextLayout callback, which sends every string down the AnnotatedString
 *    path (TextAnnotatedStringNode, MultiParagraph), and wraps each one in an OFFSCREEN graphics layer. This uses the
 *    plain-string node (TextStringSimpleNode) and no layer. For opaque text the pixels are the same.
 *  - [style] is the whole look (size, line height, weight, colour). With [inherit] it's merged over the theme's text
 *    style exactly as `Text(fontSize = …, …)` would be (merged once per style, not per call); without, it's used as is,
 *    as `Text(style = …)` would. [color], when given, is read at draw time (a colour change never recomposes).
 */
@Composable
fun FastText(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1, overflow: TextOverflow = TextOverflow.Ellipsis,
             inherit: Boolean = true, color: androidx.compose.ui.graphics.ColorProducer? = null) {
    val s = if (inherit) MergedStyles.of(androidx.tv.material3.LocalTextStyle.current, style) else style
    androidx.compose.foundation.text.BasicText(text, modifier, s, overflow = overflow, maxLines = maxLines, color = color)
}

/** Theme style + a call site's style, merged once and kept (styles are constants, so the map stays tiny). Main thread only. */
private object MergedStyles {
    private var base: androidx.compose.ui.text.TextStyle? = null
    private val merged = java.util.IdentityHashMap<androidx.compose.ui.text.TextStyle, androidx.compose.ui.text.TextStyle>()
    fun of(theme: androidx.compose.ui.text.TextStyle, style: androidx.compose.ui.text.TextStyle): androidx.compose.ui.text.TextStyle {
        if (theme != base) { base = theme; merged.clear() }
        return merged.getOrPut(style) { theme.merge(style) }
    }
}
