# Test report — Android TV (NVIDIA Shield)

Device: SHIELD Android TV, Android 11, Tegra X1. Display 3840x2160 @ 59.94 Hz; the UI renders at 1920x1080 (wm override).
All on-device tests used public test streams only (Tears of Steel HLS, Big Buck Bunny MP4) through the adb test hooks
`--es testStream` (pretend channel list) and `--es testPreview` (preview pinned to a public stream), so the provider's
single connection was never used. Emulator: not used. iPhone / web: not re-tested in this round.

## Automated
| Suite | Result |
|---|---|
| `./gradlew testReleaseUnitTest` (M3U parsing, catch-up URLs, guide time/overlaps/matching, recents, redaction, titles) | 54 tests, 0 failures |
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

## Live TV list: Compose vs RecyclerView (A/B on the Shield, 2026-10-28)
Instrument: `bench.sh` (atrace main-thread `Choreographer#doFrame`, `deliverInputEvent` to the next frame end), same APK
with an A/B switch, release build AOT-compiled, preview pinned to a public stream, 3 alternating runs each.
Scenario A: 10 group changes resting 1.2 s each. Scenario B: 60 Down presses in a 151-channel group.

| Group changes (per run of 10) | Compose (1 row/frame) | RecyclerView prototype |
|---|---|---|
| frames > 16.7 ms | 17 / 23 / 20 | 10 / 10 / 11 |
| frames > 33 ms | 1 / 0 / 0 | 5 / 6 / 5 |
| worst frame | 36 / 29 / 28 ms | 42 / 65 / 47 ms |
| input -> frame p95 | 22 / 21 / 24 ms | 276 / 20 / 275 ms |

Scrolling within a group (B): both versions 0 frames over 16.7 ms; worst frame 12-18 ms; input -> frame p50 11-14 ms.
Verdict: the RecyclerView prototype is not a repeatable improvement (fewer late frames but more visible >33 ms jolts,
because a group change rebinds every visible row and its logo in one frame). The Compose list stays; the prototype is
kept on branch `rv` only. Earlier steps on the same benchmark: group-change frames >16.7 ms went 41 (2 rows/frame) ->
17-23 (1 row/frame); worst 83 ms (all rows in the first frame) -> 27-36 ms.

Memory: Java heap after the benchmark 64-77 MB in use (dumpsys meminfo); holding Up/Down through groups previously
reached 163-188 MB and crashed; 320-press stress test now completes without a crash.
