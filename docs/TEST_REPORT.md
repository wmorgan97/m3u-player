# Test report — Android TV (NVIDIA Shield)

Device: SHIELD Android TV, Android 11, Tegra X1. Display 3840x2160 @ 59.94 Hz; the UI renders at 1920x1080 (wm override).
All on-device tests used public test streams only (Tears of Steel HLS, Big Buck Bunny MP4) through the adb test hooks
`--es testStream` (pretend channel list) and `--es testPreview` (preview pinned to a public stream), so the provider's
single connection was never used. Emulator: not used. iPhone / web: not re-tested in this round.

## Automated
| Suite | Result |
|---|---|
| `./gradlew testReleaseUnitTest` (M3U parsing, catch-up URLs, guide time/overlaps/matching, recents, redaction, titles) | 52 tests, pass |
| `./gradlew assembleRelease` | pass |

## Performance (Shield, release build, AOT-compiled with the bundled baseline profile)
Measured with `dumpsys gfxinfo` and `atrace` (main thread). adb `input keyevent` itself costs CPU on the device, so
gfxinfo numbers are pessimistic.

| Workload | Before | After |
|---|---|---|
| TV Guide, moving across/down (gfxinfo 90th) | 77 ms | 29-34 ms |
| Live TV group list, resting on each group (main thread worst frame) | 946 ms | 25 ms |
| Live TV group list (main thread 99th / 90th) | 175 / 11 ms | 20 / 7 ms |
| Live TV mixed browsing (gfxinfo 99th) | 150 ms | 85 ms |
| Movies rows vertical (gfxinfo 90th) | 85 ms | 17 ms |
| Zap to first picture (fast CDN) | 2.2-3.5 s | ~0.65 s |

## On-device checks (Shield)
| Workflow | Result |
|---|---|
| Guide opens, D-pad up/down/left/right, info header follows selection | Verified |
| Guide on a group listing one stream twice (previous crash) | Verified fixed |
| Preview plays last channel; OK once = preview, Back from Guide = full screen | Verified (test stream) |
| Playing channel shown with blue tint/name | Verified |
| Live TV: group rest loads list without a click; 40dp rows, 9 per screen | Verified |
| Player: zap up/down, wrap at list ends, channel banner, slim channel list | Verified (test channels) |
| Menu: opens over the page; Back asks "Press Back again to exit" | Verified |
| Settings two-pane, toggles incl. Show channel numbers | Verified visually |
| Catch-up, reminders, sleep timer, PIN gate, error panel/retries, Home-button stop | Built, not yet exercised on device |
