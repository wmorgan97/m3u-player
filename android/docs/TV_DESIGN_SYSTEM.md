# TV design system (Shield app)

Direction: calm premium. The app is dark, cinematic and quiet. White means focus, accent means state, and nothing moves unless the remote moved it.

This file covers the shared layer that later phases build on. Implementation-contract v0 (`DESIGN_CONTRACT_v0.md`) is implemented here, and any deviations are listed at the end.

Paths are relative to `android/app/src/main/java/com/wmorgan97/m3uplayer/tv/`.

| File | What it holds |
|---|---|
| `Theme.kt` | Tokens (`object Tv`), text styles (`TvType`), `holdOk` / `freshOkOnly`, `RowTitle`, `SpotRow`, `PageTitle`, `Tag`, `QualityTag`, `Clock`, `ProgressLine` |
| `Components.kt` | Focus (`FocusStyle`, `Modifier.tvFocus`, `Modifier.tvClickable`), buttons, chips, switches, list rows, panels, dialogs, badges, progress, empty/error states, skeletons, `FastText` |
| `Cards.kt` | Picture cards (`FocusCard`, `PosterCard`, `WideCard`, `ChannelCard`), `Picture`, `CardRow`, `Caption`, `LogoTile`, `Nav` restore |
| `Motion.kt` | Scrolling (`TvScroll`, `rememberRowKeyline`, `rememberRowAnchor`, `WithScroll`), held-key pace (`KeyPace`, `HeldPace`), `rememberPageState` / `rememberRowState` / `AheadPrefetch`, `RowEnd` |
| `Dialogs.kt` | Shared pop-ups: `Options`, `ProgrammeDialog`, `PinDialog`, `FormDialog` / `FormField` |

## Tokens (`Tv` in Theme.kt)

### Colour roles

| Token | Value | Role |
|---|---|---|
| `bg` | #0E1014 | Page background |
| `rail` | #0A0C10 | Side menu |
| `surface` / `surface2` / `surface3` | #181B22 / #232733 / #2E3341 | Base, raised and highest surfaces |
| `scrimSoft` / `scrim` / `scrimStrong` | black 40% / 62% / 80% | Light dimming / panels over content / side panels |
| `line` / `lineStrong` | white 12% / 20% | Hairlines and outlines |
| `text` / `text2` / `muted` (= `text3`) | white / #D5DAE3 / #9AA3B5 | Primary, secondary and tertiary text |
| `accent` / `onAccent` / `accentDeep` | #5B93FF / white / #2A4FB8 | State: selected, playing, progress, primary action |
| `live` | #FF4550 | Only as a dot or a thin line, never a large fill |
| `star` | #FFD166 | Ratings, favourites, reminders |
| `success` / `warning` / `danger` (= `error`) | #3DD68C / #FFB224 / #E5484D | Status |
| `focusSurface` / `onFocus` | white / #0E1014 | A focused filled element, and the content on it |
| `focusRing` / `focusGlow` | white | Frame and halo on focused pictures |

### Type

Each token is a size with a matching `…Line` line height. Weights: `wDisplay` and `wTitle` are SemiBold, `wBody` is Normal, `wLabel` is Medium and `wStrong` is SemiBold. `TvType.*` holds the same values as ready `TextStyle`s.

| Token | Size / line | Use |
|---|---|---|
| `display` | 30 / 36 | Banner and details title |
| `title` | 22 / 28 | Page title, dialog title |
| `headline` | 20 / 26 | Empty states, panel titles |
| `rowTitle` | 18 / 24 | Row heading |
| `body` | 15 / 22 | Plots, list titles |
| `bodySmall` | 14 / 20 | Buttons, secondary lines |
| `caption` | 13 / 18 | Under cards |
| `label` | 13 / 18 | Tags, meta, counts |
| `micro` | 12 / 16 | Badges and guide times. This is the floor: no text is smaller |
| `numeral` | 22 | Channel initials |
| `rank` | 40 | Top 10 numbers |

### Spacing

The grid is 4 dp: `s1`=4, `s2`=8, `s3`=12, `s4`=16, `s5`=20, `s6`=24, `s8`=32, `s10`=40, `s12`=48.

| Token | Value |
|---|---|
| `margin` | 48 (overscan-safe sides) |
| `topMargin` / `bottomMargin` | 28 |
| `gap` (between cards) | 14 |
| `rowGap` (above a row title) | 26 |
| `panelPadding` | 24 |
| `playerSafeBottom` | 32 |

### Shape

| Token | Value | Use |
|---|---|---|
| `radiusS` / `shapeS` | 6 | Badges, tags, small tiles |
| `radius` / `shape` | 10 | Cards |
| `rowShape` | 8 | Dense list rows |
| `radiusL` / `shapeL` (= `bigShape`) | 18 | Panels, dialogs, banner |
| `pill` | 50% | Buttons, chips, search field |

### Sizes

| Token | Value |
|---|---|
| `poster` | 128 (2:3 rows) |
| `posterWide` (= `gridPoster`) | 140 (grids) |
| `wide` | 236 (16:9) |
| `channel` | 176 |
| `landscape` | 300 |
| `episodeStill` | 176 |
| `listRow` | 40 |
| `settingRow` | 48 |
| `chipHeight` | 32 |
| `switchWidth` / `switchHeight` | 36 / 20 |
| `liveDot` | 6 (the inline red on-now dot) |
| `buttonHeight` | 40 |
| Icons `iconS` / `icon` / `iconM` / `iconL` / `iconXL` | 16 / 20 / 24 / 32 / 56 |

### Focus

| Token | Value |
|---|---|
| `focusFrame` | 3 dp |
| `scaleCard` | 1.07 |
| `scaleWide` | 1.05 |
| `scaleLarge` | 1.02 |
| `scaleButton` | 1.04 |
| `scaleDisc` | 1.05 |

### Motion

| Token | Value | Use |
|---|---|---|
| `durShort` | 150 | Exits, quick fades |
| `durMed` | 250 | Overlays entering, content swaps |
| `durLong` | 400 | Large surfaces |
| `durCrossfade` | 800 | Banner and backdrop cross-fades |
| `easeEnter` | (0.05, 0.7, 0.1, 1) | Emphasized decelerate: things arriving |
| `easeMove` | (0.2, 0, 0, 1) | Standard: things moving |
| `easeExit` | (0.3, 0, 0.8, 0.15) | Things leaving |
| `focusSpring` | no bounce, stiffness 400 (~230 ms) | Focus size |
| `focusFadeSpring` | no bounce, stiffness 1400 | Frame and halo opacity |
| `scrollSpring` | no bounce, stiffness 650 (~180 ms) | Rows sideways, lists |
| `rowScrollSpring` | no bounce, stiffness 450 (~220 ms) | Pages of rows, episodes |
| `heldCardMs` / `heldRowMs` / `heldListMs` | 100 / 150 / 90 | Held D-pad pace |

Overlays enter with a fade plus an 8 dp slide over `durMed` with `easeEnter`. They exit with a `durShort` fade.

## Focus rules

The focus language is unified but contextual. The vocabulary is shared everywhere: white is focus, and changes move on a no-bounce spring.

| Element | Unfocused | Focused | How |
|---|---|---|---|
| Poster | Picture; caption at 80% | Grows to 1.07, 3 dp white frame and soft white halo fade in; caption at 100% | `FocusCard` / `PosterCard` (`FocusStyle.Card`) |
| 16:9 card (continue watching, channel, programme, sport) | Same | Same, grows to 1.05 | `FocusStyle.CardWide` / `scale = Tv.scaleWide` |
| Wide row card (episode) | Faint fill | Same, grows to 1.02 | `scale = Tv.scaleLarge` |
| List row (channels, groups, settings, menu and dialog options, PIN keys) | Transparent | Solid white, content `onFocus`, no scale | `TvListRow` (`FocusStyle.Row`) |
| Selected list row (open group or section) | `surface2` fill with an accent mark at its start | As above | `TvListRow(selected = true)` |
| Primary button | Accent fill, white content | White, content `onFocus`, grows to 1.04 | `TvButton(style = Primary)` |
| Secondary button | `surface2` at 72% with a `lineStrong` hairline | As above | `TvButton` |
| Tertiary button | Text only | As above | `TvButton(style = Tertiary)` |
| Chip or tab | `surface2` at 72% with a hairline; selected: accent 16% fill and 1.5 dp accent outline | White, content `onFocus`, grows to 1.03 | `TvChip` |
| Icon button | Dark disc; selected: accent disc | White disc, grows to 1.05 | `TvIconButton` (`FocusStyle.Disc`) |
| Text field (search, forms) | `surface` with a hairline | Lifts to `surface2`/`surface3` with a 2 dp white outline, no fill, so the text stays readable | Inline in Search and `FormDialog` |
| Guide cell (drawn, not focusable) | Grey; on-air in deep accent | Solid white, text `onFocus` | `Guide.kt Cell` |
| Banner (Home/Movies/Series) | Backdrop and text | Its buttons take the pill focus (white) | `Spotlight.kt` (`SpotlightHeader`, `HeroButtons`) |

Why the treatments differ:
- Pictures keep their picture. They grow a little and gain a frame, so the art is never covered.
- Text-first elements invert, because a white bar reads instantly from a sofa. The inversion is instant, not faded: a fade passes through mid-grey, where neither colour is readable.
- Rows never grow, because they are packed tightly and growing would overlap their neighbours.
- Nothing grows beyond 1.07.
- The size change rides `focusSpring` and keeps its velocity when the target changes. The frame uses a quicker spring, so the highlight reads at once while the size eases.
- Selected-but-not-focused is always accent and never white. Two white things on screen would make focus ambiguous.

Implementation notes:
- `Modifier.tvFocus(style)` goes before the element's focus target, because it watches the focus of what follows it. It draws and scales only, with no recomposition, so a focus move costs two redraws.
- For filled styles, the content's colour flip is done by the component (`TvListRow`, `TvButton`, `TvChip`, `TvIconButton`). `TvListRow` also provides tv-material's `LocalContentColor`.
- `Modifier.tvClickable` clicks on OK release and long-clicks when OK is held. With a label, it gives one accessibility node. A disabled control stays focusable but dimmed, so focus never falls off the page.

## Motion rules

The default spec is installed in `TvActivity`.

| Where | Strategy |
|---|---|
| Rows of cards, sideways | `CardRow` applies the keyline: the focused card stays on the 48 dp left keyline and the row slides under it. Near the end of a row, the focus walks on instead. |
| Pages of rows | `WithScroll(rememberRowAnchor(top))` around the page's `LazyColumn`. The focused row's cards always settle at `top` below the list top. Home uses 42 dp (under the spotlight), Movies/Series `PAGE_ROW_TOP` (90 dp) and Sports 124 dp. |
| Dense lists (channels, settings, menus) | `TvScroll.edge` (the default): scrolls only when needed, and keeps a peek of the next item. |
| Episodes | `TvScroll.centered`. |
| Held keys | `TvActivity.dispatchKeyEvent` drops stale repeats and paces repeats. Sideways moves go at most every 100 ms. Up/down moves go every 90 ms in lists, and every 150 ms on pages that call `HeldPace()` (Home, Movies/Series, Search, Sports). Nothing queues after the key is released. |
| Pictures | Fade in over 200 ms on a `surface2` placeholder. A picture already in memory shows at once. |
| Loading | `SkeletonRow`, `SkeletonTitle` and `SkeletonBlock` use one shared shimmer. |
| Empty and error states | `EmptyState` (icon in a disc, title, message, optional primary action) and `ErrorState` (adds Try again when given). |

## Components (public signatures)

```kotlin
// Components.kt
class FocusStyle { Card, CardWide, CardLarge, Row, Pill, Disc; fun card(scale, shape); fun copy(scale, shape) }
fun Modifier.tvFocus(style: FocusStyle, interaction: MutableInteractionSource? = null): Modifier
@Composable fun Modifier.tvClickable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, enabled: Boolean = true, label: String? = null, selected: Boolean? = null): Modifier
@Composable fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, style: TvButtonStyle = TvButtonStyle.Secondary, enabled: Boolean = true, onLongClick: (() -> Unit)? = null)
@Composable fun TvIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, selected: Boolean = false)
@Composable fun TvChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null, onLongClick: (() -> Unit)? = null, icon: ImageVector? = null)
@Composable fun TvSwitch(on: Boolean, focused: Boolean, modifier: Modifier = Modifier)   // at the end of a TvListRow; darkens on the white row
@Composable fun TvListRow(onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, onLongClick: (() -> Unit)? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp), label: String? = null, onFocus: ((Boolean) -> Unit)? = null, content: @Composable RowScope.(focused: Boolean) -> Unit)
@Composable fun TvPanel(modifier: Modifier = Modifier, title: String? = null, content: @Composable ColumnScope.() -> Unit)
@Composable fun TvDialogCard(width: Dp, modifier: Modifier = Modifier, horizontalAlignment: Alignment.Horizontal = Alignment.Start, content: @Composable ColumnScope.() -> Unit)
@Composable fun TvBadge(text: String, modifier: Modifier = Modifier, tone: BadgeTone = BadgeTone.Neutral)   // Neutral | Accent | Live | Star
@Composable fun TvProgress(fraction: Float, modifier: Modifier = Modifier, live: Boolean = false)   // track: white 35%; inside a focused TvListRow it follows the row's dark content colour
@Composable fun FastText(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1, overflow: TextOverflow = Ellipsis, inherit: Boolean = true, color: ColorProducer? = null)   // hot paths only
@Composable fun EmptyState(icon: ImageVector, title: String, message: String, action: Pair<String, () -> Unit>? = null)
@Composable fun ErrorState(title: String, message: String, retry: (() -> Unit)? = null)
@Composable fun shimmerBrush(): Brush;  @Composable fun Modifier.shimmer(): Modifier
@Composable fun SkeletonBlock(width: Dp, height: Dp, modifier: Modifier = Modifier, brush: Brush = shimmerBrush(), shape: Shape = Tv.shapeS)
@Composable fun SkeletonRow(count: Int = 7, width: Dp = Tv.poster, aspect: Float = 2f / 3f);  @Composable fun SkeletonTitle()
fun Modifier.onFocusedChange(onChange: (Boolean) -> Unit): Modifier

// Cards.kt
@Composable fun FocusCard(modifier: Modifier, scale: Float = Tv.scaleCard, restoreKey: String? = null, label: String = "", onLongClick: (() -> Unit)? = null, onFocus: (() -> Unit)? = null, focused: MutableState<Boolean>? = null, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit)
@Composable fun PosterCard(v: Vod, width: Dp = Tv.poster, rank: Int? = null, restoreKey: String? = null, onLong: (() -> Unit)? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit)
@Composable fun WideCard(image: String?, title: String, sub: String, progress: Float?, width: Dp = Tv.wide, badge: String? = null, restoreKey: String? = null, onLong: (() -> Unit)? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit)
@Composable fun ChannelCard(name: String, logo: String?, now: String, progress: Float? = null, restoreKey: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit)
@Composable fun <T> CardRow(items: List<T>, key: ((T) -> Any)? = null, card: @Composable (T, Int) -> Unit)   // keyline scrolling built in
@Composable fun Caption(title: String, sub: String?, width: Dp, label: String = title, focused: State<Boolean>? = null)
@Composable fun Picture(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, fade: Boolean = true, fallback: @Composable BoxScope.() -> Unit = {})

// Motion.kt
object TvScroll { val default; val edge; fun keyline(px, spec); fun anchor(px); val centered }
@Composable fun rememberRowKeyline(keyline: Dp = Tv.margin): BringIntoViewSpec
@Composable fun rememberRowAnchor(cardTop: Dp): BringIntoViewSpec
@Composable fun WithScroll(spec: BringIntoViewSpec, content: @Composable () -> Unit)
@Composable fun HeldPace(verticalMs: Long = Tv.heldRowMs)
@Composable fun rememberPageState(): LazyListState
@Composable fun rememberRowState(): LazyListState   // card rows: 6 cards built ahead (ROW_AHEAD)
@Composable fun RowEnd(last: Boolean, content: @Composable () -> Unit)

// Theme.kt (shared rows and tags)
@Composable fun SpotRow(title: String, modifier: Modifier = Modifier, extra: String? = null, count: Int? = null, content: @Composable () -> Unit)
@Composable fun QualityTag(q: String);  fun premiumQuality(q: String): Boolean

// Dialogs.kt
@Composable fun Options(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit)
@Composable fun Options(title: String, options: List<Pair<String, () -> Unit>>, selected: String?, onDismiss: () -> Unit)   // chooser: tick on the current choice, highlight starts there; long lists scroll
@Composable fun ProgrammeDialog(act: TvActivity, c: Channel, p: Programme, now: Long, chans: List<Channel>, teams: List<String> = emptyList(), onDismiss: () -> Unit)
@Composable fun PinDialog(title: String, verify: ((String) -> Boolean)? = null, onResult: (String?) -> Unit)
@Composable fun FormDialog(title: String, intro: String?, fields: List<FormField>, submit: String = "Save", busy: Boolean = false, error: String? = null, onSubmit: (List<String>) -> Unit, onDismiss: () -> Unit)
```

Intended use:
- Build new UI from these components, not from tv-material `Surface`, `Button` or `ListItem`. The side menu (`NavigationDrawerItem`) is the one remaining tv-material control.
- New colours, sizes and durations belong in `Tv`, never inline. Existing inline literals are being retired screen by screen.

## File ownership (Phases 2–5)

| Files | Owner |
|---|---|
| `Theme.kt`, `Components.kt`, `Cards.kt`, `Motion.kt`, `Dialogs.kt`, this doc | Shared. Changes go through the coordinator only. Later phases consume them and don't edit them; a needed change is requested. |
| `PlayerActivity.java`, `PlayerHolder`, `MiniGuide`, `ShiftBar`, `StreamBadges`, new player Compose files | Phase 2 (player) |
| `Vod.kt`, `Details.kt` | Phase 3 |
| `LiveTv.kt`, `Guide.kt`, `Preview.kt` | Phase 4 |
| `Home.kt`, `HomeFeed.kt`, `Settings.kt`, `SettingsParts.kt`, `TvActivity.kt` key handling | Phase 5 |
| `Search.kt`, `Sports.kt`, `Setup.kt` | Unassigned. Go through the coordinator. |
| `Repo.kt`, `Parsing.kt`, `SearchIndex.kt`, `Text.kt` (data) | Not UI. Any phase may need them; coordinate. |

## Contract changes (vs DESIGN_CONTRACT_v0)

These are additive and source-compatible unless marked otherwise.
- **`FocusStyle`:** an immutable class instead of a plain enum. `FocusStyle.Card`, `Row`, `Pill` and `Disc` still read the same at call sites. It adds `CardWide`, `CardLarge`, `card(scale, shape)` and `copy()`.
- **`TvButton`:** adds `onLongClick`. When `enabled = false`, the button stays focusable but is dimmed and inert.
- **`TvChip`:** adds `onFocus` and `onLongClick`. The selected chip has an accent 16% fill plus a 1.5 dp accent outline. Focused scale is 1.03.
- **`TvListRow`:** adds `contentPadding`, `label` (a single accessibility description) and `onFocus`. Selected means a `surface2` fill plus an accent mark at the start.
- **`EmptyState`:** the third parameter is now `message: String` (it was `hint: String?`).
- **New tokens:** `scrimSoft`, `line`/`lineStrong`, `onAccent`, `success`/`warning`, `headline`, line heights, weights `wDisplay`/`wTitle`/`wBody`/`wLabel`/`wStrong`, the spacing scale, `shapeS`/`rowShape`/`shapeL`, card sizes, focus scales, icon sizes, `easeExit`, `durCrossfade`, `focusFadeSpring`, `scrollSpring`, `rowScrollSpring`, held-key paces.
- **Weights:** display and title are now SemiBold where they were Bold.
- **`focusSpring`:** stiffness 400, matching the contract's "medium-low". The frame and halo use a quicker spring (1400) so focus reads instantly.
- **New components:** `TvDialogCard`, `Modifier.tvClickable`, `SkeletonBlock`.
- **Retired names:** `Bg`, `Surface1`, `Surface2`, `Accent` and `Muted` aliases, plus `cardBorder`, `cardGlow` and `cardScale`. All were unused or superseded.

## Performance notes (cards)

- tv-material 1.0's `Text` always passes an `onTextLayout` callback (so every string takes the AnnotatedString path) and wraps each text in an offscreen graphics layer. Card captions and badges use `FastText` (plain-string text node, no layer, theme style merged once). Use it in anything built per card or per cell; elsewhere `Text` is fine.
- A `PosterCard` is 8 layout nodes (was 10) and has no offscreen layers (was 3): the picture is the card's own child (no wrapper Boxes), rating labels are formatted once per value (`ratingLabel`), and focus styles, brushes and placeholder painters are constants.
- Card rows prefetch 6 cards beyond each end (`rememberRowState`). Compose only builds a prefetched card in the gap between frames when its average build time fits, so a cheaper card is what lets prefetch keep up while a key is held.
