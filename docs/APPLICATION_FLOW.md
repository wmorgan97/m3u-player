# Application Flow: NVIDIA Shield app

This document describes the flow as it is implemented in code. Paths are relative to `android/app/src/main/java/com/wmorgan97/m3uplayer/`. Line numbers refer to the audit working tree (commit `122890b` plus the staged Shield changes).

## Activities

| Activity | Role | Entry |
|---|---|---|
| `tv/TvActivity` | Compose for TV shell: side menu and a stack of screens | Launcher, once a server key exists (`TvActivity.kt:63-67`) |
| `MainActivity` | Capacitor web app: first-run setup, full settings | Started by `TvActivity` when there is no key (`setup` extra), or by `openWeb(hash)` (`TvActivity.kt:108`). Back is first offered to `window.__tvBack` (`MainActivity.java:18-29`). |
| `PlayerActivity` | Full-screen ExoPlayer | `TvActivity.play` (`TvActivity.kt:256-266`). If a player is already open, it reuses it through `load()`. |

**First run.** `TvActivity` → `MainActivity` (setup) → the web app calls `NativePlayer.tvConfig` (`NativePlayerPlugin.java:61-72`) → the key is saved → `TvActivity` starts and `MainActivity` finishes.

## Navigation model (`TvActivity`)

**The stack.** Navigation is a stack of `Screen` objects (`TvActivity.kt:38-47`, `stack` at 54):
- `go(s)` pushes a screen.
- `back()` pops one and sets `Nav.restoreKey = Nav.pending`, so the card you opened it from takes focus again.
- `root(s)` replaces the whole stack. The side menu uses it.

Each screen keeps its scroll position and state through `rememberSaveableStateHolder` (`TvActivity.kt:138`, `:173`).

**Side menu.** `NavigationDrawer` (`TvActivity.kt:139-158`) lists Search, Home, Live TV, TV Guide, Movies, Series and (at the bottom) Settings. When focus enters the menu, it lands on the current section (142–143). Picking an item closes the menu and calls `root(...)`.

**Focus when a page opens.** `TvActivity.kt:163-171` retries `contentFocus.requestFocus()` up to 20 times, 150 ms apart, until the content has focus. For the first 6 tries it waits, so a card that is restoring focus can claim it first (`Cards.kt:restorable` 56–61). Pages with dialogs or fields request their own focus:

| Page | Focus goes to |
|---|---|
| Details | the Play button (`Screens.kt:415`) |
| Search | the text field (`Screens.kt:524`) |
| Guide | the grid (`Guide.kt:204`) |
| Options | the first option (`Screens.kt:575`) |
| PinDialog | the "1" key (`Guide.kt:320`) |
| ProgrammeDialog | the first action (`Guide.kt:287`) |

### Global keys (outside the player)

| Key | Behaviour | Code |
|---|---|---|
| Back, menu open | Quits the app (`finish()`) | `TvActivity.kt:132` |
| Back, page stack > 1 | Pops the page, closes the menu, puts focus back on the card it came from | `TvActivity.kt:133`, `:103-106` |
| Back, at a root page | Opens the menu | `TvActivity.kt:133` |
| Back on Live/Guide, preview on and a preview channel set, no dialog open | **Overrides the rows above:** plays the preview channel full screen | `Screens.kt:303-306`, `Guide.kt:200-203` |
| 0–9 (not on Search or Settings) | Collects up to 4 digits and shows them with the channel name (top right, `TvActivity.kt:192-197`). After 1.3 s, tunes with `playLive(all channels, index)` | `TvActivity.kt:112-125` |
| OK (KeyUp) on a card | `onClick`. `FocusCard` acts on KeyUp, not KeyDown | `Cards.kt:74` |
| Hold OK on a card or list row | The first key repeat (~0.5 s) runs `onHold`, and the matching KeyUp is swallowed | `Theme.kt:holdOk` 143–154 |

**Overlays shown over every page:**
- The clock, plus "Offline · showing saved data" when offline. Hidden on Details (`TvActivity.kt:185-188`).
- The reminder `Options` dialog (`TvActivity.kt:189-191`).
- The number-zap box (`TvActivity.kt:192-197`).

## Screens

### Home (`Screens.kt:HomeScreen` 58–117)
Rows, top to bottom:
1. **Hero carousel**, auto-advancing every 9 s. OK opens Details (`Hero` 160–202, key handling at 169).
2. **Continue watching** (`WideCard`). OK resumes. Hold OK opens Options: Resume / Start over / Remove from Continue watching (109–115).
3. **Favourite channels** and **Recently watched channels** (`ChannelRowCards` 121–128). OK plays the row as the zap list. Hold OK opens `ChannelOptions`: Watch / Add or Remove favourite / TV guide / Hide this channel (131–140).
4. **TMDB rows** of movies and series (Top 10 with rank numbers, Popular, New). OK opens Details. Holding OK does nothing (`onLong` is not set).

### Live TV (`Screens.kt:LiveScreen` 207–311)
**Header.** With the preview on and the group unlocked, it shows `PreviewBox` and the now/next of the focused channel (243–255). Otherwise it shows the title, the group and the count, plus the hint "Hold OK for options · Type a number to zap" (256–262).

**Group column** (264–272):
- When focus enters the column, it lands on the open group.
- If focus rests on a group for 280 ms, that group opens (221).
- OK opens a group immediately.
- Hold OK on a real group opens the group Options: Move to the top / Move back, Hide this group, Lock with PIN / Unlock this group, TV guide for this group (290–302). `★ Favourites` and `All channels` have no Options.

**Channel list** (`ChannelRow` 326–344):
- OK with the preview on and this channel not yet previewed: preview it.
- OK otherwise: `playLive(list, i)` full screen (284).
- Hold OK: `ChannelOptions`.
- The previewed channel shows ▶ instead of its number.

**Locked group.** Shows a lock icon, "This group is locked" and an "Enter PIN" button that opens `PinDialog` (273–281, 307–310). A correct PIN adds the group to `Repo.unlocked` for this process.

### TV Guide (`Guide.kt:GuideScreen` 49–209)
**Header.** `PreviewBox` (when the preview is on) and the selected programme's title, time, duration, state (On now / Catch-up / Reminder set) and description (139–156).

**Group chips** (`FilterChip`). OK switches group (157–159). Locked groups show 🔒.

**Grid.** The grid is one focusable `LazyColumn`. The cells are not focusable (181–188).

| Key | Behaviour |
|---|---|
| Right | Next programme. If the gap is more than 30 min or nothing follows, moves the window +30 min. Stops at now + 24 h. |
| Left | Previous programme or −30 min. At the earliest time, returns false, so focus leaves the grid (towards the menu). |
| Up | Row −1. On row 0, returns false, so focus goes to the chips. |
| Down | Row +1. Consumed on the last row. |
| PageUp/PageDown, ChannelUp/ChannelDown | ±8 rows |
| OK (KeyUp) | `ok(hold=false)`, see below |
| Hold OK | Opens `ProgrammeDialog` for the cell |

What OK does depends on the cell under the cursor (`ok` 96–109):

| Cell | OK does |
|---|---|
| Empty | Watch the channel |
| On air | Watch. With the preview on, the first OK previews and the second goes full screen. |
| Ended, with an archive | `playCatchup` |
| Anything else | `ProgrammeDialog` |

The red "now" line is drawn at 193–194.

**ProgrammeDialog** (260–289). Actions, in this order:
- "Watch now" (on air);
- "Watch from the start" or "Play (catch-up)" (if there is an archive);
- "Remind me" or "Cancel reminder" (future programmes);
- "Watch <channel>" (not on air);
- "Close".

Back or a click outside dismisses it.

### Movies / Series (`Screens.kt:VodScreen` 349–361)
- Hero, then Top 10, Popular now, New releases and TMDB genre rows (`GroupRow` 365–391).
- A row with more than 20 titles ends with a "See all" card that opens `GridScreen` (394–403).
- OK on a poster opens Details.

### Details (`Screens.kt:DetailsScreen` 408–472)
- **Film:** Play, or "Resume · x left" plus "Start over" (444–453).
- **Series:** one button that says "Loading episodes…", "No episodes", "Continue SxEy" or "Play SxEy" (454–460). Below it:
  - season chips: moving focus onto a chip switches the season (497);
  - episode cards: OK plays the season as a list starting at that episode (485–489).

### Search (`Screens.kt:SearchScreen` 518–559)
- Focus starts in the text field; the on-screen keyboard is the system IME.
- Results appear for 2 or more characters, after a 350 ms pause.
- Results come in rows: Channels (`ChannelRowCards`, with hold OK for options), Movies and Series (OK opens Details).

### Settings (`tv/Settings.kt:SettingsScreen` 23–68)
- Toggles act on OK.
- The PIN rows open `PinDialog`, chained when changing the PIN (53–56).
- "Full settings" opens the web app at `#/settings`.
- The playlist rows and the Server row have no action.

## Dialogs

| Dialog | Defined | Behaviour |
|---|---|---|
| `Options` | `Screens.kt:564-577` | Title, list of actions, Cancel. Focus on the first action. Labels starting with "Remove" are drawn red. Back dismisses. |
| `PinDialog` | `Guide.kt:293-322` | 3×4 on-screen keypad (1–9, ⌫, 0, ✕). Number keys also work. Submits automatically at 4 digits, and the caller checks the PIN. A wrong PIN just closes the dialog. ✕ or Back cancels. |
| `ProgrammeDialog` | `Guide.kt:260-289` | See TV Guide. |
| Reminder due | `TvActivity.kt:189-191` | `Options` with "Watch now" / Cancel. |

## Player (`PlayerActivity.java`)

**How it opens.** Live items open with the controls hidden and the channel banner showing (bottom left, 4.5 s). VOD items open with the controls showing (`load` 180). The controls hide after 5 s (`showOverlay` 360–367).

**Keys at any time** (`dispatchKeyEvent` 262–331):

| Key | Behaviour |
|---|---|
| Play/Pause | Toggle; the controls stay up while paused |
| CH+/CH−, media Next/Prev | Step to the next or previous item (channel or episode) |
| FF/RW | ±30 s (VOD only) |

### Live, controls hidden
| Key | Behaviour | Line |
|---|---|---|
| OK (short press, act on release) | Channel panel if the list has more than 1 entry, otherwise the controls | 269–274 |
| Hold OK (repeat 1) | Controls; the release is swallowed | 304 |
| Up / Down | Zap −1 / +1. A held key steps every 3rd repeat | 300–303 |
| Left / Right | Show the banner | 312 |
| 0–9 | Zap HUD, then a `zap` event after 1.3 s. `TvActivity` tunes with `channelByNumber` (`TvActivity.kt:290`) | 279–284 |
| Back | Hides the banner if it is showing, otherwise closes the player | 316–318 |

### VOD, controls hidden
| Key | Behaviour |
|---|---|
| OK, Up, Down | Show the controls |
| Left / Right | Seek ±10 s; after more than 3 repeats, ±30 s. Seeks build up and are applied 600 ms after the last press (`seekBy` 334–345) |
| Back | Close the player |

### Controls shown
- **Arrows** move between the buttons:
  - previous, −30 s, play, +30 s, next;
  - audio, subtitles, picture size, sleep, info, list.
- **OK** presses the focused button.
- **Up/Down on live** still zap, unless focus is on a button other than play (300).
- **OK on live while the controls came up by themselves** opens the channel panel instead (323–324).
- **Left/Right on the seek bar** seek (326–329).
- **Back** hides the controls (322).

### Panels and dialogs inside the player
| Element | Keys | Code |
|---|---|---|
| Channel panel (left, 420 dp) | Up/Down browse; OK switches channel. The first row is "↩ Last channel" when there is one, and the current channel is shown in blue. Right or Back closes it. | `openChannels` 442–469, item click 802–810, keys 294–297 |
| Next-episode panel | 10 s countdown with Play now / Cancel. Back cancels and shows the controls. | `startCountdown` 379–392, 293 |
| Reminder notice (top) | OK watches the programme; Back dismisses it. Auto-hides after 20 s. | `notice` 516–521, 264–268 |
| Audio / Subtitles / Sleep / List | `AlertDialog` single-choice lists | `pickTracks` 398, `pickSleep` 524, `showList` 472 |
| Info | `AlertDialog` with codec, resolution, frame rate, HDR and bitrate | `showInfo` 487–513 |
| Error | "This stream couldn't be played" in the centre; the controls stay up. One automatic retry happens first. | `onError` 203–215 |

### Leaving the player
- `finish()` sends a `closed` event with the playback position. `TvActivity` saves progress and refreshes Continue watching (`TvActivity.kt:291-297`).
- Home (`onStop`) pauses the player (581).
- `onDestroy` gives the ExoPlayer back to `PlayerHolder` (602).
- Coming back to `TvActivity` bumps `resumeTick`, which restarts the preview (`TvActivity.kt:85`, `Preview.kt:76`).
