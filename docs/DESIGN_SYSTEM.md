# Design System: Shield TV app

**Source of truth.** The token definitions live in `tv/Theme.kt` (the `object Tv` block, lines 32–73). Paths are relative to `android/app/src/main/java/com/wmorgan97/m3uplayer/`, and line numbers refer to the audit working tree.

**Theme.** `TvActivity` maps these tokens into tv-material3's `darkColorScheme`:
- `primary = accent`
- `background = bg`
- `surface = surface`
- `onSurface = text`, `onBackground = text`
- `surfaceVariant = surface2`

This mapping is at `TvActivity.kt:76`. The quick scroll-into-view animation is installed there too (`TvActivity.kt:77`, defined in `Theme.kt:136-138`).

## Tokens (`Tv`, `Theme.kt:32-73`)

### Layout
| Token | Value | Use |
|---|---|---|
| `margin` | 48.dp | Left and right page margin (overscan-safe) |
| `topMargin` | 28.dp | Page top |
| `gap` | 14.dp | Between cards in a row |
| `rowGap` | 26.dp | Above each row title |
| `radius` / `shape` | 10.dp / `RoundedCornerShape(10)` | Cards, posters |
| `bigShape` | `RoundedCornerShape(18.dp)` | Hero, dialogs |

### Card widths
| Token | Value | Use |
|---|---|---|
| `poster` | 128.dp | 2:3 posters in rows |
| `posterWide` | 140.dp | Grid cells (`GridScreen`) |
| `wide` | 236.dp | 16:9 cards: Continue watching, episodes (episodes use `wide + 20.dp`) |
| `channel` | 176.dp | 16:9 channel cards |

### Type scale
| Token | Size | Use |
|---|---|---|
| `display` | 34.sp | Hero and Details title |
| `title` | 24.sp | Page title, guide and live header |
| `rowTitle` | 19.sp | Row headings |
| `body` | 16.sp | List titles, settings |
| `bodySmall` | 14.sp | Buttons, secondary text |
| `caption` | 13.sp | Under cards, guide cells |
| `label` | 12.sp | Smallest size the comment allows ("nothing under 12sp", `Theme.kt:27`) |

### Colours
| Token | Value | Use |
|---|---|---|
| `bg` | `#0E1014` | Page |
| `rail` | `#0A0C10` | Side menu |
| `surface` | `#181B22` | Rows, dialogs, preview background |
| `surface2` | `#232733` | Picture placeholder, unfocused cells |
| `surface3` | `#2E3341` | Shimmer highlight, selected channel cell |
| `line` | `#FFFFFF` at 12% | Dialog border |
| `text` | white | Primary text |
| `text2` | `#D5DAE3` | Secondary text |
| `muted` | `#9AA3B5` | Tertiary text, counts |
| `accent` | `#5B93FF` | Progress bars, ▶ marker, cursor |
| `accentDeep` | `#2A4FB8` | On-air guide cell (at 50% alpha) |
| `star` | `#FFD166` | Ratings, favourite star |
| `live` | `#FF4550` | Guide "now" line |
| `danger` | `#E5484D` | "Remove…" options |
| `focusRing` | white | Focus frame |

### Shared primitives
These live in `Theme.kt`:
- `shimmerBrush` and `Modifier.shimmer`, one shared animation (84–89);
- `SkeletonRow` and `SkeletonTitle` (93–109);
- `rememberPageState` and `AheadPrefetch`, which prefetch rows (116–132);
- `holdOk`, hold OK for options (143–154);
- `RowTitle` (158), `PageTitle` (169), `Tag` (178), `EmptyState` (184), `Clock` (197), `ProgressLine` (205).

## Components

| Component | Location | Anatomy | Focus treatment |
|---|---|---|---|
| `FocusCard` | `Cards.kt:67-79` | Plain `focusable` box, one accessibility node (`clearAndSetSemantics`), click on OK KeyUp, optional `holdOk` | Scales to 1.06 over 110 ms (a graphics-layer property) with a 3 dp white rounded frame drawn in `drawWithContent`. No recomposition on focus. |
| `PosterCard` | `Cards.kt:123-143` | 2:3 `FocusCard` with a `Picture` (resized to 300 px through the server). Fallback is a gradient with the title. Optional ★ rating chip, optional rank number (44 sp) over a gradient. `Caption` underneath (title + year). | `FocusCard` default (1.06) |
| `WideCard` | `Cards.kt:147-163` | 16:9 `FocusCard`, optional badge `Tag`, `ProgressLine` at the bottom, `Caption` | 1.04 scale |
| `ChannelCard` | `Cards.kt:167-180` | 16:9 logo tile on a gradient (initials if there is no logo), 3 dp progress line | 1.06 |
| `ChannelRow` | `Screens.kt:326-344` | tv-material `ListItem`. Leading: `LogoTile` 68×40. Headline: number (or ▶) + name + ★. Supporting: now playing + time + 220 dp progress. Trailing: "Next …" (only when the preview is off). | tv-material `ListItem` default (not `FocusCard`) |
| Guide `Cell` | `Guide.kt:245-256` | Box at x offset and width from minutes. 6 dp radius. Title (13 sp), time (11 sp) when width > 90 dp, `↺` prefix for catch-up, 3 dp progress when on air. | Not focusable. The selected cell is drawn as a white fill with dark text plus a 2 dp white border. On-air: `accentDeep` at 50%. Past: `surface` at 55%. Otherwise `surface2`. |
| `GuideRow` channel cell | `Guide.kt:225-230` | 210 dp wide, number or ▶, `LogoTile` 50×30, name + ★ | Selected row: `surface3` background |
| `Options` | `Screens.kt:564-577` | `Dialog`, 440 dp wide, `surface`, `bigShape`, 1 dp `line` border, title, `ListItem` per action, Cancel | tv-material default. Focus on the first item. |
| `PinDialog` | `Guide.kt:293-322` | `Dialog`, 360 dp wide, 4 dot indicators, 3×4 keypad of 72×48 `Surface` keys | Focused key: white fill, `bg` text, scale 1.0 |
| `PreviewBox` | `Preview.kt:71-99` | `AndroidView(PlayerView)`, 16:9, height 186 dp minus padding, clipped to 10 dp. Channel-name placeholder on `surface` until the first frame. | Not focusable |
| `Hero` | `Screens.kt:160-202` | 340 dp tv-material `Carousel` with backdrop, two gradients, `Tag`s and a pseudo-button | 4 dp white border on the whole carousel (no scale). The pseudo-button turns white. |
| Settings `Row` / `Toggle` | `Settings.kt:76-96` | `ListItem` at 70% width. `Toggle` draws its own 44×24 switch. | tv-material default |

## Inconsistencies (defects)

| # | Defect | Location |
|---|---|---|
| DS1 | **There is more than one focus treatment, despite the comments.** `Theme.kt:27` and `Cards.kt:49` both promise one. In practice there are eight:<br>• `FocusCard`: scale + 3 dp ring<br>• `Hero`: 4 dp border, no scale<br>• Guide cell: inverted white fill + 2 dp border<br>• `PinDialog`: white fill, no scale<br>• Search field: 2 dp ring + `surface2`<br>• `ListItem`s: tv-material default<br>• Player buttons: white disc<br>• Player channel list: 2 dp stroke + 20% fill | `Cards.kt:76-77`; `Screens.kt:168`; `Guide.kt:247-249`; `Guide.kt:313-314`; `Screens.kt:536-537`; `PlayerActivity.java:629`; `PlayerActivity.java:793` |
| DS2 | **Text below the 12 sp minimum** stated at `Theme.kt:27`. | `Cards.kt:134` (11.sp), `Cards.kt:157` (11.sp), `Guide.kt:226` (11.sp), `Guide.kt:252` (11.sp) |
| DS3 | **Type sizes that are not on the scale.** | `Cards.kt:137` (44.sp), `Cards.kt:171`/`:173` (24.sp initials), `TvActivity.kt:194` (30.sp zap digits) |
| DS4 | **Corner radii that are not tokens:** 4, 5, 6, 8, 9, 12 and 14 dp, while only `radius` (10 dp) and 18 dp exist. `PreviewBox` and the zap box use 10 dp literally instead of `Tv.shape`. | `Theme.kt:100`, `:108`, `:179`; `Cards.kt:133`, `:185`; `Guide.kt:225`, `:246`, `:305`, `:312`; `Screens.kt:190`, `:536`; `Settings.kt:91-92`; `Preview.kt:86`; `TvActivity.kt:192` |
| DS5 | **Spacing is off the "8dp spacing grid"** (`Theme.kt:26`). The tokens themselves break it: `gap` 14, `rowGap` 26, `topMargin` 28. Paddings of 6, 10, 14, 18 and 22 dp also appear throughout. | `Theme.kt:35-37` |
| DS6 | **Colour literals instead of tokens:**<br>• `Tag` background `0x26FFFFFF` and scrims `0xB3000000`/`0xCC000000`.<br>• The Hero gradients repeat `bg` as `0xF20E1014`/`0xB30E1014`.<br>• The Details gradients do the same. | `Theme.kt:178`; `Cards.kt:133`, `:136`, `:157`; `Screens.kt:174-175`, `:418-419` |
| DS7 | **The player duplicates the palette as Java literals,** with no shared source:<br>• accent `0xFF5B93FF`<br>• muted `0xFF9AA3B5`<br>• live red `0xFFFF4550`<br>• bg `#0E1014`<br>• surface `0xF0181B22`<br>• `0xFF7FAAFF`, a blue that is not in `Tv` at all | `PlayerActivity.java:703`, `:831`, `:821`, `:680`, `:633`, `:761`, `:457` |
| DS8 | **Two nearly identical reds,** `live` (`#FF4550`) and `danger` (`#E5484D`). Each is used exactly once. | `Theme.kt:70-71`; `Guide.kt:194`; `Screens.kt:570` |
| DS9 | **Destructive styling is chosen by label text** (`startsWith("Remove")`). "Hide this channel" and "Hide this group" are not red, but "Remove from favourites" is. | `Screens.kt:570` |
| DS10 | **The locked-group state is drawn two ways:** Live shows an icon, 36 dp, and a 14 dp spacer; the Guide shows no icon and a 12 dp spacer. The lock marker in group names is an emoji "🔒", but `Icons.Default.Lock` is used elsewhere. | `Screens.kt:273-281` vs `Guide.kt:162-168`; `Screens.kt:269`, `Guide.kt:158` |
| DS11 | **Dead tokens and helpers:**<br>• The aliases `Bg`, `Surface1`, `Surface2`, `Accent`, `Muted` are never referenced.<br>• `cardBorder`, `cardGlow`, `cardScale` are never referenced. | `Theme.kt:75-80`; `Cards.kt:81-86` |
| DS12 | **Settings draws its own switch** instead of tv-material `Switch`, so it has no focus or animation state of its own. | `Settings.kt:91-93` |
| DS13 | **Channel rows use two components with different focus treatments:** `ChannelRow` is a `ListItem`, and `ChannelCard` is a `FocusCard`. The guide channel cell is a third, non-focusable variant. Logo tiles come in three sizes (68×40, 50×30, and the 176 dp card) with different radii (6 dp vs `Tv.shape`). | `Screens.kt:329`; `Guide.kt:227`; `Cards.kt:169-185` |
| DS14 | **The live header hint "Hold OK for options · Type a number to zap"** appears only when the preview is off or the group is locked. The default preview header hides it. | `Screens.kt:244`, `:261` |
