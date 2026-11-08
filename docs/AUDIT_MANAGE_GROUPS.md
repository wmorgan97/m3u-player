# Audit: group management (web PWA + iPhone app)

Scope: `groupsSheet()` in `js/app.js`, the chip row (`renderChips`), `setGroupsHidden` / `localHidden` / `adoptHidden`
(sync) and `server/sync.js` `mergeHidden`. Method: code read end to end plus a headless run on a fake 600-group playlist
(see "How it was checked"). Phone = 390x844, desktop = 1280x800. No real devices or servers were used.

## How a user manages groups today

* Entry: Home -> playlist "..." menu -> "Manage groups (hide, lock, reorder)..." (`js/app.js` ~5288). That is the ONLY entry
  point: 3 taps, invisible from the chip row where the user actually sees the groups. Long-press on a chip does nothing.
* The sheet: country-prefix chips, filter box, 4 bulk buttons (Show only these / Hide these / Show these / Show all), 3
  one-shot sort buttons (live only), then one row per group: a 22 px checkbox, name, count, star (Home pin), "Options"
  button (menu: show/hide, move top/bottom, lock, rename, delete), drag grip (live only).
* Hidden state: `p.hiddenGroups` (names) + `p.hiddenAt[name]`; synced as `hidden` `g\1<sourceKey>\1<name>` -> `{h, at}`,
  newest edit wins, show-markers live 90 days. Applies identically to Live, Movies and Series (one name set per playlist).

## Findings (severity ranked)

### High
1. **Every toggle rebuilds everything.** `setHidden` -> `setGroupsHidden` -> `save(p)` (writes the WHOLE playlist, 75k
   channels, to IndexedDB) -> `rerender()` (whole list view) -> `reopen()` (rebuilds the sheet HTML for every group,
   resets keyboard/focus). With 500+ groups, each tick is a visible stall and the keyboard closes. Not virtualised.
2. **"Show only these" with a filter that matches nothing hides EVERY group** (`names = []`, then all groups are added to
   the hidden set and none removed). Also no undo for any bulk action, so a mis-tap on "Hide these"/"Show only" loses a
   carefully curated list.
3. **Reordering destroys the provider order and is not synced.** `applyOrder` re-sorts `p.channels` by group and saves it.
   Provider order cannot be restored, "Shown first" is a one-off mutation (not a mode), order is lost/overwritten at the
   next playlist refresh, and other devices never see it. Live only; Movies/Series cannot be ordered at all.
4. **Remote hide changes do not show up while you are inside a playlist.** After a sync `adoptHidden` sets `changed`, but
   the redraw is gated by `!state.current`, so with the live SSE push the chips/list stay stale until you navigate away.
5. **Touch targets.** Only the 22 px checkbox toggles a group; tapping the row/name does nothing. "Options" is a small
   text button, the star 34 px, the grip is a 20 px glyph. iOS minimum is 44 px.

### Medium
6. **One hidden set for three tabs.** A group named "Kids" in Live and in Movies share one switch; hiding one hides both.
   (Keys are group names only. Changing the key now would break the Shield/other-device sync format, so documented, not
   changed. The new manage screen shows only the current tab's groups and says so.)
7. **Filter matches the raw provider name**, but rows display `prettyGroup()` (e.g. "UK| Sky" shows "UK . Sky"; superscript
   tags are stripped). Searching "uk sky" or "uk · sky" finds nothing; searching a stripped tag finds invisible hits.
   Accents/case folding are not applied.
8. **Provider groups under "All provider groups" (`entry.moreGroups`) are not in the sheet** at all, and `tmdb:*` groups show
   their raw ids. The sheet reads `vodCache` directly: if Movies/Series were never opened it shows "0 of 0 groups" with no
   explanation (no empty/loading state).
9. **No "Show newly added groups" policy.** After a provider refresh new junk groups appear shown; with 500 groups the
   user re-curates forever. TiviMate has the switch.
10. **No sorting mode, no per-group display name that survives refresh.** Rename rewrites `c.group` on the channels, which
    also changes the hide/sync key (old key stays hidden on other devices, rename is wiped on refresh).
11. **Accessibility.** Checkboxes have labels, but rows are not toggle switches (`role=switch`), no live count for screen
    readers, the sheet has no `role=dialog`/focus handling, drag reorder has no keyboard or button alternative.
12. **Header scrolls away.** Title, search and bulk buttons scroll with a 500-row list; after scrolling you cannot search
    or bulk-act without scrolling back to the top (4 stacked control rows ~ 280 px of a 844 px phone).

### Low
13. Count badges: chip counts exclude hidden groups (correct) but the "All" count ignores `moreGroups`; fine in practice.
14. Xtream vs M3U: `sourceKey()` is `x:host|user|content` vs `u:url|content`; a playlist re-added with a different content
    filter (live / movies / both) gets a different key, so its hides start from scratch (by design, listed for clarity).
    `*` (TV app "every playlist") entries are honoured by `adoptHidden` on read; the web app never writes `*`.
15. `adoptHidden` ignores entries whose `at` is not newer than a local un-stamped hide (`at` 1) correctly; verified OK.
16. The sheet keeps its own scroll memo and re-creates the DOM, which flickers the toggle animation and loses text selection.
17. Desktop: the sheet is a 640 px dialog with the same stacked controls; no keyboard shortcuts (Esc closes via backdrop only).

## What is good (kept)
Per-key timestamps with tombstones in `mergeHidden` are correct for offline/merge cases (see `tests/hidden.test.js`): an
older hide cannot resurrect a newer show, 90-day show-marker expiry, profile isolation, old clients unaffected. Pinning to
Home, PIN-locking groups, delete group and the prefix chips are kept.

## Plan / what was changed
See the "Status" section appended below after the fixes.
