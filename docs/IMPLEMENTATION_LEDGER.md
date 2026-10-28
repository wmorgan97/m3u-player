# Implementation Ledger

Scope: the shared Node server (`server/*.js`), the web app (`index.html`, `js/*.js`), and mainly the native Android TV app for NVIDIA Shield (`android/app/src/main/java/com/wmorgan97/m3uplayer/`). Paths below are relative to that package unless they start with `server/`, `js/`, `tests/` or `scripts/`.

**Evidence base.** Everything here comes from reading the code. Line numbers refer to the audit working tree: commit `122890b` plus the staged, uncommitted Shield changes (`tv/Preview.kt` is new; `PlayerActivity.java`, `PlayerHolder.java`, `tv/Cards.kt`, `tv/Guide.kt`, `tv/Repo.kt`, `tv/Screens.kt`, `tv/Settings.kt` and `tv/TvActivity.kt` are modified). Once those changes are committed, line numbers may shift.

**Status vocabulary**
- **Inspected**: the code was read and behaves as described. Not run.
- **Defect confirmed**: the defect follows directly from the code. Not reproduced on the device.
- **Implemented, not verified**: the code path exists, but nobody has measured or tested it on the Shield.
- **Verified on Shield**: used only for the measurements listed below.

**Shield measurements (the only ones available).** These are gfxinfo 90th-percentile frame times.

| Screen | Measurement |
|---|---|
| Home | 18 ms |
| Movies | 17 ms |
| Series | 5 ms |
| Live channel list | 13 ms |
| Guide | 77 ms before the rewrite, 29–34 ms after it (preview playing) |
| Live group column | 77 ms before the debounce; not re-measured since |
| Zap to picture | about 0.65 s on a fast CDN |

At 60 Hz the frame budget is 16.7 ms. Home (18 ms), Movies (17 ms) and the Guide (29–34 ms) are all over it.

---

## 1. Startup / setup
- **Current state.** `tv/TvActivity.kt:onCreate` (61–80) works like this:
  - With no server key, it starts `MainActivity` with the `setup` extra and finishes (64–67). The web app then calls `NativePlayerPlugin.tvConfig`, which saves the key and starts `TvActivity` again (`NativePlayerPlugin.java:61-72`).
  - Otherwise it loads the cached `sync.json` (`tv/Repo.kt:cachedSync` 151) and starts several background jobs:
    - a fresh `/sync` call (69);
    - warming the live channels (70);
    - the movie and series overviews after 1.5 s (71);
    - `PlayerHolder.warm` after 2.5 s (72);
    - loading the saved guide (73);
    - a reminder loop every 15 s (74).
- **Evidence.** `TvActivity.kt:61-80`, `Repo.kt:69-73`. A default server URL is hard-coded at `Repo.kt:66`, and `scripts/android-tv.sh` also writes it into the build.
- **Desired outcome.** The first frame shows cached content. A failed sync is visible to the user, not silent. The server is configured, not hard-coded.
- **Status.** Implemented, not verified (cold start has not been measured).
- **Risks.** Sync failures are swallowed (`TvActivity.kt:69`, `:84`). Hard-coding the server ties every build to one host.

## 2. Live TV
- **Current state.** `tv/Screens.kt:LiveScreen` (207–311) has two columns:
  - **Groups**, listed in this order: `★ Favourites`, `All channels`, then the groups arranged by `Repo.arrange`.
  - **Channels**, drawn by `ChannelRow` (326–344).

  Channels come from `Repo.liveChannels` (`Repo.kt:216-259`): M3U files go through `parseM3U` (264–302) and Xtream sources use `get_live_streams`. Both are cached on the device for 12 h.

  The group column is debounced: focus has to rest on a group for 280 ms before the channel list follows (`Screens.kt:220-221`).
- **Evidence.** As cited above. The guide data is requested for the first 300 channels, and again in steps of 100 while scrolling (`Screens.kt:232-235`).
- **Desired outcome.** Moving through the group column stays under the 16.7 ms frame budget, and the channel list never shows locked or hidden content.
- **Status.**
  - Live channel list: Verified on Shield (13 ms).
  - Group column debounce: Implemented, not verified (77 ms before the change; not re-measured).
- **Risks.**
  - The group names `★ Favourites` and `All channels` are magic strings (`Screens.kt:216`, `Guide.kt:58`). A playlist group with either name would collide with them.
  - Channel numbers fall back to a running index across all playlists (`Repo.kt:296`) and Xtream sources (`Repo.kt:250`), so numbers change when a source is added.

## 3. Channel switching (zapping)
- **Current state.** In the player (`PlayerActivity.java:dispatchKeyEvent`, 262–331):
  - Up/Down zap channels. A held key zaps one channel every ~3 key repeats (300–303).
  - CH+/CH− and the media next/previous keys also step (288–289).
  - Number keys collect up to 4 digits, then send a `zap` event after 1.3 s (279–284).
  - The channel panel offers a "Last channel" entry (`openChannels` 442–469).
  - A channel banner appears for 4.5 s (`showBanner` 185–198).

  `TvActivity.kt:onPlayerEvent` (281–300) turns these events into `play`, which reuses the open `PlayerActivity` (`play` 264). `PlayerHolder.build` (35–48) starts playback after 700 ms of buffer, and `extractors()` (51–55) reads less of a TS stream before starting.
- **Evidence.** As cited above. Each switch logs `picture in Nms` (`PlayerActivity.java:121`).
- **Desired outcome.** Picture in under 1 s, and zapping that respects locks and hidden channels.
- **Status.** Zap to picture: Verified on Shield (about 0.65 s on a fast CDN). Wrap-around and lock handling: Defect confirmed (see D1 and D9).
- **Risks.** No wrap at the ends of the list: `TvActivity.kt:285` returns when `n !in playList.indices`. Zapping by number uses the full channel list (D1).

## 4. Guide / EPG
- **Current state.** `tv/Guide.kt:GuideScreen` (49–209) shows:
  - a header with `PreviewBox` and details of the selected programme;
  - a row of group chips;
  - a grid that is a single focus target, which moves its own cursor (`move` 110–135) and draws only the programmes inside a 2-hour window (`GuideRow` 219–242).

  Guide data comes from server `/guide` (`server/guide.js:handleGuide` 87–121) through `Repo.loadGuide` (`Repo.kt:386-410`). It is requested at most every 3 hours per channel and saved to `guide.json`, keeping 6 hours back to 30 hours ahead (`saveGuideSoon` 349–361).
- **Evidence.** As cited above.
- **Desired outcome.** Guide frame time under 16.7 ms, and every visible row has data.
- **Status.** Verified on Shield: 29–34 ms with the preview playing, down from 77 ms. Still about 2× over budget.
- **Risks.**
  - Up to 600 rows are shown (`Guide.kt:62`), but the guide is only requested for the first 300 (`Guide.kt:67`). There is no scroll-triggered request as in Live (D5).
  - The window reaches 24 h back as soon as any channel has an archive, whatever its `catchupDays` (`Guide.kt:74-75`).

## 5. Preview
- **Current state.** `tv/Preview.kt`:
  - `Preview` (34–67) keeps one `ExoPlayer` created by `PlayerHolder.create`.
  - `PreviewBox` (71–99) plays the channel 150 ms after it changes and shows the channel name until a frame arrives.
  - The first OK on a channel puts it in the preview; a second OK goes full screen (`Screens.kt:284`, `Guide.kt:100`).
  - The preview is stopped in `TvActivity.onStop` (88) and when `PreviewBox` leaves the screen (85). It is released before full-screen playback (`TvActivity.kt:258`).
- **Evidence.** As cited above. The setting is `Repo.preview` (`Repo.kt:77`); its toggle is at `Settings.kt:50`.
- **Desired outcome.** One provider connection at a time, a visible error when a stream fails, and no writes to state during composition.
- **Status.** Implemented, not verified. Defect confirmed (D4).
- **Risks.**
  - If the stream never reaches READY, `PreviewBox` polls every 100 ms forever (`Preview.kt:83`) and shows no error.
  - `Preview.current()` calls `select()`, which writes snapshot state, from inside `remember` during composition (`Screens.kt:243`, `Guide.kt:141`, `Preview.kt:42`).

## 6. Catch-up
- **Current state.** `Repo.catchupUrl` (`Repo.kt:310-329`) supports Xtream `timeshift` URLs and M3U `catchup-source` templates.
  - In the Guide, OK on a finished programme with an archive calls `playCatchup` (`Guide.kt:106`). The dialog offers "Watch from the start" or "Play (catch-up)" (`Guide.kt:267`).
  - `TvActivity.playCatchup` (229–234) plays it as non-live, so it can be seeked.
- **Evidence.** As cited above. The web version is in `js/app.js:catchupUrlFor` (2979) and is tested in `tests/epg.test.js:49`.
- **Desired outcome.** Catch-up behaves the same on Shield and web, with unit tests for the Kotlin port.
- **Status.** Implemented, not verified (there are no Kotlin tests).
- **Risks.**
  - Catch-up items carry no `sync`, so there is no progress, no resume and no recents.
  - A guide cell shows `↺` using the condition `p.start > now - days` (`Guide.kt:238`), which can differ from `catchupUrl`'s `start < now - days` check at the exact boundary.

## 7. Reminders
- **Current state.**
  - `Repo.toggleReminder` (`Repo.kt:105-108`) stores reminders in SharedPreferences.
  - `TvActivity.checkReminders` (239–248) runs every 15 s while `TvActivity` is alive. It fires a reminder from 90 s before to 30 s after the start, showing either `PlayerActivity.notice` (OK to watch, Back to dismiss, 20 s; `PlayerActivity.java:516-521`) or the `due` Options dialog (`TvActivity.kt:189-191`).
- **Evidence.** As cited above.
- **Desired outcome.** Reminders also fire when the app is in the background or has been killed (for example through AlarmManager).
- **Status.** Implemented, not verified.
- **Risks.**
  - Reminders exist only on the device and are not synced. The web app has its own (`js/app.js:toggleReminder` 3036, `scheduleReminders` 3050).
  - Nothing fires if the process is dead.
  - Every reminder write bumps `Repo.version`, which recomposes every screen that keys on it (`Repo.kt:103`).

## 8. Parental lock
- **Current state.**
  - The PIN is stored in **plaintext** SharedPreferences (`Repo.kt:84`); the web app hashes it (`js/app.js:hashPin` 588).
  - Locked groups and session unlocks are handled by `Repo.kt:85-89`.
  - A locked group shows "Enter PIN" (`Screens.kt:273-281`, `Guide.kt:161-168`).
  - `PinDialog` (`Guide.kt:293-322`) submits automatically on the 4th digit.
- **Desired outcome.** Locked content never appears or plays without the PIN on any path. A wrong PIN gives feedback. The PIN is hashed.
- **Status.** Defect confirmed (D1, D6, D7).
- **Risks.** The lock can be bypassed in several places (D1).

## 9. Hide / reorder
- **Current state.**
  - Hide a group or a channel, and move a group to the top or back again (`Repo.kt:90-99`). These are reached by holding OK on a group (`Screens.kt:290-302`) or through `ChannelOptions` (`Screens.kt:131-140`).
  - "Show hidden groups and channels" clears everything at once (`Settings.kt:57`, `Repo.kt:97`).
- **Desired outcome.** Arbitrary reordering, unhiding one item at a time, and settings shared with the web app.
- **Status.** Inspected.
- **Risks.**
  - All of this is stored only on the device. The web app keeps per-playlist `hiddenGroups`/`lockedGroups` (`js/app.js:visibleGroup` 581), and the two are not shared.
  - Hidden channels still appear in Search (`Screens.kt:529`) and are reachable by number (`TvActivity.kt:119`).

## 10. Favourites
- **Current state.** `Repo.toggleFavourite` (`Repo.kt:187-194`) posts the whole list with `favAt`. The server keeps whichever list is newer (`server/sync.js:merge` 40).
  - Favourites appear as a group in Live and Guide, and as a row on Home (`Screens.kt:86-89`).
  - The star is shown in `ChannelRow` (334) and `GuideRow` (229).
- **Desired outcome.** Favourites are synced reliably, and the user is told when a sync fails.
- **Status.** Implemented, not verified. Defect confirmed (D3).
- **Risks.** A failed POST is swallowed (`Repo.kt:192`). The change stays in memory and is overwritten by the next `/sync`, so the favourite is silently lost. There are no movie or series favourites on the Shield.

## 11. Recents
- **Current state.**
  - `TvActivity.play` calls `Repo.saveRecent` (263); entries from the test hook are excluded.
  - `Repo.recentChannels` (177–180) filters out VOD by URL path.
  - The server merges recents by `at` (`server/sync.js:41-51`).
  - Home shows the row (`Screens.kt:90-93`).
- **Status.** Implemented, not verified.
- **Risks.** `saveRecent` swallows errors (`Repo.kt:207`). Recents ignore the parental lock (D1).

## 12. Search
- **Current state.** `Screens.kt:SearchScreen` (518–559) waits 350 ms after typing and needs at least 2 characters.
  - It searches channel names locally (529), limited to 40 results.
  - It searches movies and series through server `/vod` with `q` (`Repo.kt:search` 489–500).
- **Desired outcome.** Search respects hidden and locked content, separates "error" from "no results", and can search programmes in the guide.
- **Status.** Inspected.
- **Risks.**
  - A server error returns empty lists (`Repo.kt:496`), which the screen shows as "Nothing matches" (`Screens.kt:552`).
  - There is no programme or EPG search.

## 13. VOD (movies / series)
- **Current state.**
  - `VodScreen` (349–361) shows a hero, Top 10, Popular, New and TMDB genre rows (`GroupRow` 365–391), with "See all" leading to `GridScreen` (394–403).
  - `DetailsScreen` (408–472) offers Play, Resume or Start over for films. For series it offers Continue or Play SxEy (`nextEpisode` 475–483), season chips and episode cards.
  - Progress is saved through `Repo.saveProgress` (`Repo.kt:199-205`).
  - The player runs a 10 s next-episode countdown (`PlayerActivity.java:startCountdown` 379–392).
  - Catalogues are cached: overview 3 h (`Repo.kt:443-460`), groups 6 h (478–487), all groups prefetched 4 at a time (468–476).
- **Status.**
  - Home: Verified on Shield (18 ms).
  - Movies: Verified on Shield (17 ms).
  - Series: Verified on Shield (5 ms).
  - Home and Movies are still just over 16.7 ms.
- **Risks.**
  - `Overview.recent` is parsed and run through `tidy` but never shown (`Repo.kt:459`), which is wasted work.
  - Background refreshes use `GlobalScope` (`Repo.kt:449`, `:470`).
  - `DetailsScreen` requests focus on the series Play button even while it is disabled (`Screens.kt:415`, `:457`).

## 14. Player overlay / tracks
- **Current state.** `PlayerActivity.java` builds its UI in code (`buildUi` 643–841).
  - **Controls:** previous, −30 s, play, +30 s, next, audio, subtitles, picture size, sleep, info, list (719–733).
  - **Tracks:** `pickTracks` (398–421). Captions are off by default (118 and `PlayerHolder.java:46`).
  - **Other dialogs:** aspect (435–439), sleep timer (524–535), and a media info dialog (487–513).
  - **Seeking:** builds up while the key is held and commits after 600 ms (334–345).
  - **Errors:** reconnects once on a behind-live-window or IO error (203–215), then shows "This stream couldn't be played".
- **Status.** Implemented, not verified.
- **Risks.**
  - `onStop` only pauses the player (581). Its comment says the provider connection should not stay open, but `pause()` does not release the media source. This is suspected, not measured.
  - Live has no pause or timeshift: the seek bar is hidden when `live` is true (157).

## 15. Playlists / sources
- **Current state.** Sources come from server `/sync` (`sources`, merged newest-wins in `server/sync.js:61-65`). The Shield only lists them (`Settings.kt:41-46`). Editing them opens the web settings (`TvActivity.openWeb`, 108).
- **Status.** Inspected.
- **Risks.**
  - The playlist rows in Settings have an empty `onClick` (`Settings.kt:45`, `:59`): they take focus but do nothing.
  - Only the first non-live Xtream source is used for VOD (`Repo.kt:153-163`).

## 16. Offline caching
- **Current state.** Data is saved in `filesDir`:

  | File | Refresh rule |
  |---|---|
  | `sync.json` | replaced on every sync |
  | `live-*.m3u` and `live-x-*` | 12 h |
  | `vod-*.json` | 3 h, stale copy served while refreshing |
  | `group-*` | 6 h, older copy used if offline |
  | `eps-*` | network first, cached copy as fallback |
  | `guide.json` | written 3 s after guide data arrives |

  `Repo.online` drops to false only on an IOException to the server (`Repo.kt:125-129`), and the top bar then says "Offline · showing saved data" (`TvActivity.kt:186`).
- **Status.** Implemented, not verified.
- **Risks.**
  - If a playlist was never downloaded, the failure is swallowed (`Repo.kt:228`, `:241`, `:253`), and the screen shows "No live channels · Add a playlist with live TV in Settings" (`Screens.kt:211`). That message is wrong when a playlist exists but failed to download.
  - `clearCache` deletes `guide.json` but leaves the in-memory guide and `guideLoaded` as they were (`Repo.kt:117`, `:362`).

## 17. Settings
- **Current state.** `tv/Settings.kt:SettingsScreen` (23–68) has these entries:
  - English groups only, Channel preview, Prefer 4K;
  - Set, change or remove the PIN; Show hidden;
  - Server, Clear saved data, Full settings (opens the web settings), and the version number.
- **Status.** Inspected.
- **Risks.**
  - "Remove PIN" clears the PIN but not `lockedGroups` (`Settings.kt:56`). Setting a new PIN later silently locks the old groups again.
  - A wrong current PIN closes the dialog without any message (`Settings.kt:66`).

## 18. Accessibility / remote navigation
- **Current state.**
  - Each card is collapsed to one accessibility node with a label (`Cards.kt:75`).
  - The guide grid, guide rows, captions and info headers clear their semantics completely (`Guide.kt:144`, `:181`, `:224`; `Cards.kt:115`; `Screens.kt:248`).
  - Focus on entry is retried 20 times at 150 ms intervals (`TvActivity.kt:163-171`), and Back puts focus back on the card it came from (`Cards.kt:53-61`).
  - Player buttons have content descriptions (`PlayerActivity.java:625`).
- **Status.** Inspected.
- **Risks.**
  - TalkBack users get nothing from the Guide.
  - Back behaves differently in Live and Guide (D2).

## 19. Tests
- **Current state.**
  - `npm test` runs `tests/*.test.js`: `m3u`, `epg`, `xtream` and `server` (all web or server).
  - `tests/smoke.mjs` is a headless-Chrome smoke test of the live server.
  - Android has only the Capacitor template tests (`android/app/src/test/.../ExampleUnitTest.java`, `androidTest/.../ExampleInstrumentedTest.java`).
  - The Shield has an adb test hook that plays fake channels from a given stream: `testStream`, `testIndex`, `testPreview` (`TvActivity.kt:93-100`).
- **Desired outcome.**
  - JVM unit tests for `Repo.catchupUrl`, `Repo.parseM3U`, `Text.kt` (`vodMeta`, `normName`, `isEnglishGroup`), the Guide's `move`/`pick`, and the lock filters.
  - A scripted gfxinfo run per screen.
- **Status.** Inspected (there is no Kotlin test coverage).

---

## Defects found while reading

| ID | Severity | Defect | Location |
|---|---|---|---|
| D1 | P0 | **The parental lock can be bypassed in several ways:**<br>• The Favourites group does not filter locked groups, unlike `All channels`.<br>• The Home favourite and recent rows play without a check.<br>• Search lists channels from locked groups.<br>• Zapping by number and the player's `zap` use the full channel list, so Up/Down then walks through locked groups too.<br>The web app does carry locks into Favourites (`js/app.js:583`). | `tv/Screens.kt:226`; `tv/Guide.kt:62`; `tv/Screens.kt:86-93`, `:126`; `tv/Screens.kt:529`; `tv/TvActivity.kt:119-120`, `:290` |
| D2 | P1 | **Back loop:** with the preview on and a preview channel set, Back on Live and Guide always opens that channel full screen. Back from the player returns to the screen, and Back then opens the player again. Only Left reaches the menu. Back with the menu open quits the app without confirmation. | `tv/Screens.kt:303-306`; `tv/Guide.kt:200-203`; `tv/TvActivity.kt:132` |
| D3 | P1 | **Swallowed exceptions hide failures:** sync on start and resume; favourite, progress and recent POSTs; playlist and Xtream downloads and parsing; guide batches; the VOD refresh; search; TMDB info. | `tv/TvActivity.kt:69`, `:84`; `tv/Repo.kt:192`, `:204`, `:207`, `:228`, `:241`, `:253`, `:407`, `:450`, `:496`, `:530`; `PlayerActivity.java:100`, `:214`, `:220`, `:243`, `:248`, `:383`, `:482`, `:809`; `PlayerHolder.java:29` |
| D4 | P1 | **Preview:**<br>• It polls forever when a stream never becomes ready, and there is no error state.<br>• `Preview.current()` writes state during composition. | `tv/Preview.kt:83`; `tv/Preview.kt:42` called from `tv/Screens.kt:243` and `tv/Guide.kt:141` |
| D5 | P2 | **Guide data is never requested for rows 301–600.** | `tv/Guide.kt:62`, `:67` (compare `tv/Screens.kt:235`) |
| D6 | P2 | **A wrong PIN gives no feedback, and there is no attempt limit:** the dialog just closes. | `tv/Screens.kt:309`; `tv/Guide.kt:208`; `tv/Settings.kt:66` |
| D7 | P2 | **PIN handling:**<br>• The PIN is stored in plaintext.<br>• Removing the PIN leaves `lockedGroups`, so they lock again when a new PIN is set. | `tv/Repo.kt:84`; `tv/Settings.kt:56` |
| D8 | P2 | **Misleading empty state** when a playlist fails to download. | `tv/Screens.kt:211`; `tv/Guide.kt:52` |
| D9 | P3 | **Zapping stops at the ends of the list** instead of wrapping. | `tv/TvActivity.kt:285` |
| D10 | P3 | **Dead code:**<br>• Legacy colour aliases `Bg`, `Surface1`, `Surface2`, `Accent`, `Muted` (no references).<br>• `cardBorder`, `cardGlow`, `cardScale` (no references).<br>• `Caption`'s `label` parameter is unused.<br>• `Overview.recent` is computed but never shown.<br>• `R_PLAY` is a misleadingly named local (it holds the pause icon). | `tv/Theme.kt:75-80`; `tv/Cards.kt:81-86`; `tv/Cards.kt:114`; `tv/Repo.kt:459`; `PlayerActivity.java:718` |
| D11 | P3 | **Focusable rows that do nothing:** the playlist and Server rows in Settings. | `tv/Settings.kt:45`, `:59` |
| D12 | P3 | **Garbled comment:** the reminder comment was merged into the `resumeTick` line. | `tv/TvActivity.kt:59` |
| D13 | P3 | **OK keys are handled inconsistently:** NumPadEnter is missing from some OK checks. | `tv/Screens.kt:169`; `PlayerActivity.java:275`, `:305`, `:324` |
| D14 | P3 | **No TODO/FIXME markers** were found in `server/`, `js/`, the Android sources or `ios/App/App`. The deferred work is instead recorded in comments, for example "(catch-up has no sync entry)". | `tv/TvActivity.kt:263` |
| D15 | P2 | **Reminders only fire while `TvActivity` is alive**, because they run in a coroutine loop. | `tv/TvActivity.kt:74` |
| D16 | P3 | **`PlayerActivity.onStop` pauses but does not stop the player**, so the provider connection may stay open after Home is pressed. Suspected; not measured. | `PlayerActivity.java:581` |


## Status update (2026-10-28, after the second round)
| Defect | Status |
|---|---|
| D1 parental lock bypass | Implemented: one PIN gate in `TvActivity.playLive` covers favourites, home rows, search, number zap, guide; PIN hashed. Not verified on device. |
| D2 Back loop / instant exit | Back from Guide/Live = full screen is intended (TiviMate). Exit now needs a second Back within 2 s. Verified. |
| D3 swallowed exceptions | Repo and player failures now logged (redacted); `Repo.liveError` shown as an empty state. Implemented, not verified. |
| D4 preview never gives up | Fixed: 15 s / error -> "No picture", stream closed. Implemented. |
| D5 guide rows 301+ | Fixed: guide requested around the selected row. Implemented. |
| D6 wrong PIN feedback | Settings: retries + 5-try lockout. Live/Guide dialogs still close silently. Partly done. |
| D7 PIN storage / locked groups after removal | Fixed (hash; removal clears locks). |
| D8 misleading empty state | Fixed with `Repo.liveError`. |
| D9 zapping stops at ends | Fixed: wraps. Verified. |
| D11 dead Settings rows | Fixed in the new Settings. Verified visually. |
| D13 OK keys | Fixed (`isOk`). |
| D16 Home keeps stream open | Fixed: onStop stops, onStart re-prepares. Not verified on device. |
| D10, D12, D14, D15 | Open (cosmetic / reminders only while the app runs). |
