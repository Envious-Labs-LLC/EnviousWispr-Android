# Issue #181 — Wispr Eyes harness: fast and token-light without losing a read-back — 2026-09-20

GitHub issue: `#181`. Tier: MEDIUM. Status: APPROVED (Gate 1: founder "let's work on 181"; Gate 2 under the
standing "you decide these things with codex"; Codex Sol rounds 1-3, the last open point adjudicated in §14).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/src/debug/**`, one internal accessor in `app/src/main/**`, `scripts/uat/**`).
`mixed_pr: true`: Code (`tests`, `codex-review`; `hardware-uat` not required, the heart path is untouched:
the receiver reads windows and inserts nothing) and Docs/dev-tooling (`docs/**`, `scripts/**`:
`cited-symbols`).

**PAR rows closed:** none.

**Hardware UAT:** N. The app change is a DEBUG-only broadcast receiver that reads the window tree; the
release build does not contain it (`app/src/debug/` and the debug manifest, exactly like
`DebugInsertReceiver`). Nothing goes to the founder's phone through Play for this change. The proof runs
on the emulator's debug build (§11.1).

## Preface — User Rubric

User Rubric: N/A — test harness speed; no user of EnviousWispr sees any of it.

---

## 0. TL;DR

**Consolidation:** the dominant root is "read the screen", produced by one function (`tree`) and paid
for 18 times per errand at 1.9 s each. Its one owner stays `tree`; the transport under it changes.

The #177 benchmark showed the harness 2.4x slower than raw adb. A trace of every command inside the
flip-and-back errand (today, live AVD) says why: 18 `uiautomator dump` calls at 1.9 s each are 34.7 of
39.8 s; sleeps are 4.4 s; the other 28 adb calls are 0.6 s. The 1.9 s is `uiautomator`'s own process
start and does not shrink with `--compressed` or a stdout target. So the fix is a different eye: a
DEBUG-only broadcast receiver in the app walks the accessibility windows the bound
`PasteAccessibilityService` already holds and hands the same XML back in the broadcast result
(round trip measured 0.02 s for an existing debug broadcast). `tree` uses it when the installed build
answers, and falls back to `uiautomator dump` otherwise (release build on the phone, service unbound).
Then the switch restore puts a visible switch back in place instead of navigating from the app's start.
Benchmark target: flip-and-back under 10 s, spoken take under 15 s, correctness 5/5 and dirty 0 kept.

## 1. Problem

- `docs/benchmark-results/2026-09-20-issue-177-harness-vs-raw.md`: flip-and-back 39.7 s vs 8.0 s raw;
  spoken take 22.3 s vs 16.3 s; audio-free insert 8.1 s vs 4.2 s; unlock 9.6 s.
- Trace (Gate 0 comment on #181): `uiautomator dump` ×18 = 34.7 s; `time.sleep` total 4.4 s; every
  other adb call together 0.6 s. Founder 2026-09-20: "blazing fast and not crazy token heavy".
- Codex's ranked plan (`.codex/2026-09-20-issue-177-speed-tokens.txt.last`) assumed a targeted read costs
  0.15 s; there is no such read through `uiautomator`. Its item 4 (one adb session) buys ~1 s. Its item 1
  (one snapshot per screen generation) would rewrite `find`'s rule "the screen is read fresh, every
  time" for a saving that a 50 ms dump makes unnecessary.

## 2. Goals & non-goals

### 2.1 Goals
1. `tree()` on a debug build with the service bound costs under 0.2 s and sees the same nodes
   `uiautomator dump` did (same attributes, same ancestry), plus the recorder overlay.
2. The switch restore puts back a switch that is already on screen without navigating.
3. Benchmark rerun on the same six errands: flip-and-back < 10 s, spoken take < 15 s, unlock < 6 s;
   harness stays 5/5 correct and 0 dirty; output tokens no higher than #177's.
4. No release-build change: the receiver and its manifest entry live in `app/src/debug/`.

### 2.2 Non-goals
- A persistent UiAutomator/UiAutomation process (unbinds our service; RULE: no-appium-and-no-tap-by-position).
- Caching a tree across an input (Codex item 1): `find` keeps reading fresh BETWEEN operations. Within
  one `find`, the failure message is formatted from the snapshot that failed, not from a second read
  (`find` used to call `look`, which re-read the screen; review round 1; now `look(only_ours=True, nodes=snapshot)` at `wispr_eyes.py:739`).
- One framed adb shell session (Codex item 4): 0.6 s of 39.8 s; not worth a transport rewrite.
- The physical phone's release build gets no fast eye; it keeps `uiautomator dump`.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
"A screen reading" is produced in `tree` (`scripts/uat/wispr_eyes.py:499`) by `_adb("uiautomator dump …")`
+ `_adb("cat …")`, parsed by `visit` into node dicts, cached in `_STATE["tree"]`. Consumers: `find`
(`:625`, always `refresh=True`), `look`, `_rows_by_label`, `switches`, `on_screen`, `one_way`, `reveal`,
`scroll`, `_walk_this_screen`, `_tab_row`, `open_tab`, `open_page`, `open_app`, `_focused_field`,
`focus_field`, `unlock_emulator` (grep `tree(` at build for the count). Every consumer reads the SAME dict
shape, so a second transport that yields the same XML attributes needs no consumer change.

Inside the app: `PasteAccessibilityService` (`app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt`)
holds `instance` privately (`:134`) and publishes `isBound`; the service's config
(`app/src/main/res/xml/…`, `accessibilityFlags` include `flagRetrieveInteractiveWindows`,
`canRetrieveWindowContent=true`) permits `windows` reads. `DebugInsertReceiver`
(`app/src/debug/java/com/envi/wispr/debug/DebugInsertReceiver.kt`) is the precedent: a debug-only
`BroadcastReceiver`, declared in `app/src/debug/AndroidManifest.xml`, calling companion functions.

### 2. Existing authority
- Screen reading: `tree`. The new transport goes INSIDE it; nothing else learns a second eye.
- Debug rigs: `DebugInsertReceiver`, `DebugTelemetryReceiver`, the debug manifest. New:
  `DebugDumpReceiver` (proposed), action `com.envi.wispr.debug.DUMP`.
- Service access from debug code: today through public companion functions. New:
  `PasteAccessibilityService.windowTreeXml()` (proposed), a companion function in the main source set
  that returns the XML or null when no instance is bound. Placed in main because `instance` is private
  and stays private.
- Negative, the fast eye: `/usr/bin/grep -rn 'debug.DUMP\|windowTreeXml\|AccessibilityWindowInfo' app/src scripts/uat` → 0 hits. `new authority proposed`.
- Result transport: `am broadcast` prints `Broadcast completed: result=N, data="…"` when the receiver
  sets result data on an ordered broadcast (`am broadcast` sends ordered and waits). Base64 in the data
  avoids the quoting of XML inside that line.

### 3. Prior attempts and live direction
#177 (merged, PR #183), its benchmark and Codex speed plan; Gate 0 comment on #181 with today's trace.
`device-testing.md` FACT: the four eyes and which sees the recorder: the accessibility tree via
`uiautomator` does NOT see the overlay; the service's own `windows` includes accessibility overlays,
which is a new capability, measured in §11.1 rather than assumed.

### 4. Boundaries a naive design misses
- **Debug vs release, and the result codes.** The receiver exists only in debug. Result codes are the
  contract: `0` = nobody answered (no receiver: a release build) → `_STATE["eye"] = "slow"` for the
  rest of this process; `1` + data = fast; `3` = receiver present but the service is unbound; `2` =
  too big. `2`, `3`, a broadcast timeout (the `_adb` call carries `timeout=10`), an `am` error, or
  undecodable data → this call takes the slow path and the fast eye is retried after a cooldown of
  three slow reads (`_STATE["eye_retry_after"]`, proposed), so a service that comes back or a screen
  that shrinks is found again in the same process. Undecodable data raises `Blocked` once, naming the
  eye, then the next call is slow-with-cooldown. The phone's Play build answers `0` and is slow for
  the process. (Review round 1: "slow forever" on a transient failure was the trap.)
- **Bound vs unbound.** `windowTreeXml()` returns null with no instance → result `3`. `bound()` is not
  consulted first: the probe IS the check.
- **Binder size.** The 1 MB binder buffer is SHARED across concurrent transactions, so the cap is
  conservative: the receiver caps the ENCODED result at 256 KiB and answers `result=2` (`RESULT_TOO_BIG`)
  beyond it. 85 nodes ≈ 30 KB of XML, 40 KB base64, so a normal screen is a tenth of the cap.
- **Thread.** `windowTreeXml()` runs its walk through the service's existing `callOnMain` (used by
  `pinTargetForDictation` at `:184`), so the contract holds whoever calls it, not only a receiver
  that happens to be on the main thread.
- **Recycling.** `AccessibilityNodeInfo` objects from `getChild` are recycled after use on API < 34;
  on API 33+ `recycle()` is a no-op and deprecated. The floor is API 33, so no recycling calls.
- **Stale tree after input.** Unchanged: `find` reads fresh; `tap` invalidates.
- **The cache key.** `_STATE["dump_path"]` stays for the fallback.

### 5. High-risk premises
- P1 The dump cost is process start: measured, 1.90–1.97 s across five variants (Gate 0 comment).
- P2 A debug broadcast round-trips in 0.02 s: measured ×3 on the AVD.
- P3 `am broadcast` prints result data: NOT VERIFIED until chunk 1; the receiver's first run proves it.
- P4 The service's `windows` includes the recorder overlay: NOT VERIFIED; §11.1 measures it and the
  knowledge table is updated either way.
- P5 The API floor is 33 (`CLAUDE.md` Compatibility) so `recycle()` is not needed.

## 3. Design

**Chunk 1, the fast eye.**
- `app/src/main/…/PasteAccessibilityService.kt`, companion:
  `fun windowTreeXml(): String?` (proposed). Returns null when `instance` is null. Otherwise walks
  `instance.windows` in order; for each window, its `root`, depth-first; emits
  `<hierarchy rotation="0">` and one `<node …/>` per node with exactly the attributes `tree` reads:
  `text`, `content-desc`, `package`, `class`, `resource-id`, `clickable`, `enabled`, `selected`,
  `scrollable`, `focused`, `checkable`, `checked`, `bounds="[l,t][r,b]"` (`getBoundsInScreen`).
  Children nested inside their parent element so ancestry is preserved. ONE attribute escaper for
  EVERY string attribute (`text`, `content-desc`, `package`, `class`, `resource-id`): `&`, `<`, `>`,
  `"`, and control characters.
- `app/src/debug/…/DebugDumpReceiver.kt` (proposed), action `com.envi.wispr.debug.DUMP`: calls
  `windowTreeXml()`; null → `resultCode = 3`; else base64 → over 256 KiB → `resultCode = 2`, else
  the result data (`resultData`), `resultCode = 1`; any exception → `resultCode = 3`, logged under `DebugDump`.
  Declared in `app/src/debug/AndroidManifest.xml` with `android:exported="true"` and an intent filter
  for the action, exactly as `DebugInsertReceiver` is.
- `scripts/uat/wispr_eyes.py` `tree`: new private `_dump_xml()` (proposed) that returns the XML string,
  implementing the §2.5.4 contract: unless `_STATE["eye"] == "slow"` or a cooldown is running, run
  `am broadcast -a com.envi.wispr.debug.DUMP com.envi.wispr` through `_adb(…, timeout=10)`; parse
  `result=(\d+)` and `data="(.*)"`; result `1` with decodable data → `_STATE["eye"] = "fast"`, return
  it; result `0` → `_STATE["eye"] = "slow"` for the process; `2`, `3`, an `am` error or a timeout →
  cooldown of three slow reads (`_STATE["eye_retry_after"]`); undecodable data → `Blocked` naming the
  eye, cooldown set, so the next call is slow. Every non-fast outcome falls through to the existing
  `uiautomator dump` loop. The parse of the XML is unchanged.
- `find`'s failure branch formats "what is there" from the nodes it just searched (`look(nodes=…)`,
  a new keyword) instead of a second read (§2.2).
- The receiver is declared `android:permission="android.permission.DUMP"` (review round 2: an exported
  receiver with no sender restriction lets any app on a debug device read the screen). The shell holds
  `DUMP`; an ordinary app does not. §11.1 executes both: `am broadcast` from the shell answers `1`;
  `run-as com.envi.wispr am broadcast …` (the app's own uid, no `DUMP`) is refused.
- `overlay()` unchanged in this chunk (it reads `dumpsys window`); §11.1 measures whether the fast tree
  shows the recorder, and if it does, a follow-up makes `overlay()` prefer it.

**Chunk 2, restore in place.** `_restore_one_here`, `switch` arm: before `open_tab`/`open_settings`,
`if on_screen(wanted["where"]) and present(wanted["label"], exact=True)`: skip navigation; then the
existing `reveal`, read, tap, read-back. Same read-back either way.

**Chunk 3, action-specific settling.** `tap`'s fixed 1.2 s stays: a generic "the tree changed"
predicate fires on a ripple, an outgoing animation or the recorder's live clock before the press has
taken effect (review round 1). The two places that know what they are waiting for replace their sleep
with a predicate: `set_switch` (`:990`, 0.6 s) and the restore `switch` arm (`:2680`, 0.6 s) poll
`switch(label)` every 100 ms for up to 0.6 s until it reads the WANTED state on two consecutive reads,
then continue; the final read-back stays as it is. Measured in the benchmark; if the saving is under
1 s per errand the sleeps stay and this chunk is dropped (recorded in §14).

**Rejected:** a persistent UiAutomator (unbinds the service); a snapshot reused across input (rewrites
`find`'s safety rule for no measurable gain once a dump is 50 ms); one adb session (0.6 s).

## 3b. Ownership justification
The eye lives inside `tree` because every reader already goes through it; the receiver lives in the
debug source set because the Play build must not carry a window dumper; the accessor lives in the
service's companion because `instance` is private and the precedent (`pinTargetForDictation`) is a
companion function.

## 4. Contract deltas
- `tree()`: same nodes, same shape; a debug build with the service bound answers in tens of ms; a
  release build or an unbound service answers as before. New `_STATE["eye"]` ∈ {None, "fast", "slow"}.
- `PasteAccessibilityService.windowTreeXml()` (proposed): null when unbound; XML otherwise; never
  throws (a window whose root is null is skipped).
- Restore of a `switch` debt: same end state, no navigation when the switch is already on screen.
- `set_switch` and the restore `switch` arm: continue as soon as the switch reads the wanted state twice,
  never later than today's 0.6 s.

## 5. State and lifecycle audit
| Population | Enumerated |
|---|---|
| Every `tree(` call site | `/usr/bin/grep -n 'tree(' scripts/uat/wispr_eyes.py` at build; all go through the one function, none changes |
| Every attribute `visit` reads | `text content-desc package class resource-id clickable enabled selected scrollable focused checkable checked bounds` (`wispr_eyes.py:556-580`); the receiver emits exactly these |
| Every debug receiver | `DebugInsertReceiver`, `DebugTelemetryReceiver` (debug manifest); `DebugDumpReceiver` is the third |
| Every way a `tree` call takes the slow path | result `0` (no receiver: slow for the process); result `2` (too big), result `3` (unbound or exception), `am` error, timeout, base64/XML failure (slow for this call, fast retried after three slow reads) |
| Every sleep touched in chunk 3 | `set_switch` 0.6 s (`:990`), restore `switch` arm 0.6 s (`:2680`); `tap` 1.2 s (`:1061`) deliberately untouched; every other `time.sleep` (grep at build) untouched |
| Every restore arm | ten kinds (#177 §5); only `switch` changes |

## 6. Consumer matrix
| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| fast `tree` | every reader above | 1.9 s dump | same nodes, faster | no | fixture test: the receiver's XML shape parsed by `tree` gives identical dicts to a uiautomator fixture of the same screen |
| `_STATE["eye"]` | `tree` only | n/a | probe once, fall back | yes | test: broadcast answering `result=0` → uiautomator path taken; answering data → no uiautomator call |
| `windowTreeXml` | `DebugDumpReceiver` | n/a | null when unbound | yes | unit test on the serialiser with fake nodes; the unbound path on the emulator with the service off |
| restore in place | `_restore_one_here` | navigates | skips when visible | yes | test: fake screen showing the tab and label → no `am start`/tab tap sent |

## 7. Failure modes
| Failure | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| broadcast not delivered (release build) | result 0, no receiver | `tree` | nothing; slow path silently | `_STATE["eye"]="slow"` this process | next process probes again |
| service unbound | result 3 | `tree` | slow path this call | cooldown of three slow reads | fast retried after the cooldown |
| data too big | result 2 | `tree` | slow path this call | cooldown | same |
| broadcast timeout or `am` error | adb / framework | `tree` | slow path this call | cooldown | same |
| bad base64 / XML | receiver bug | `tree` | `Blocked` naming the eye once; the next call is slow | cooldown | same |
| `windows` throws | framework | receiver | result 3, logged under `DebugDump` | cooldown | same |
| a non-shell sender | no `DUMP` permission | receiver | not delivered (`SecurityException` at the sender) | none | n/a |
| restore in place: label visible on the wrong screen | `on_screen` false | `_restore_one_here` | navigates as today | none | n/a |

## 8. Caller-visible signals
- `look()` may now list the recorder overlay's nodes when the fast eye is on (P4); `overlay()` stays the
  authority until measured.
- A `NOTE: eye=fast|slow` line under `--verbose` only.
Not present in this change: any app signal.

## 9. Fallback source-of-truth audit
| Branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| fast eye | broadcast result data | the bound service's `windows` | the same accessibility tree the insertion path acts on | result 1, base64 decodes, XML parses to ≥1 node | slow path | `tree` |
| slow eye | `uiautomator dump` | as today | as today | as today | `Blocked` | `tree` |

## 10. File-by-file
- `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt`: companion `windowTreeXml()` and a
  private serialiser (`appendNode`, proposed).
- `app/src/debug/java/com/envi/wispr/debug/DebugDumpReceiver.kt` (new); `app/src/debug/AndroidManifest.xml`.
- `app/src/test/java/com/envi/wispr/paste/WindowTreeXmlTest.kt` (new): serialiser on fake nodes.
- `scripts/uat/wispr_eyes.py`: `_dump_xml`, `tree`, `find` (failure from its own snapshot), `look`
  (`nodes=` keyword), `_restore_one_here` switch arm (in-place + settle), `set_switch` settle,
  `_STATE["eye"]`, `_STATE["eye_retry_after"]`.
- `scripts/uat/test_wispr_eyes.py`: rows in §11.2.
- `docs/benchmark-results/2026-09-20-issue-181-harness-fast-eye.md` (new): the rerun.
- Gitignored: `device-testing.md` "four eyes" table (the fast eye and what it sees), skill note.

## 11. Testing
1. Class: harness contract for the Python rows; product-adjacent for the serialiser (a wrong attribute
   makes the harness press the wrong thing; the user is the founder running UAT).
2. Reverts named per row.
3. Not tested in the unit file: the broadcast round trip and the overlay visibility (§11.1).

### 11.1 Emulator run
- `tree()` twice with `--verbose`: `eye=fast`, wall time under 0.3 s, node count within ±5 of a
  `uiautomator dump` of the same screen, every label of the slow tree present in the fast one.
- With the recorder up (`open_recorder`): does the fast tree contain `EnviousWispr recording controls`
  nodes? Record yes/no in the knowledge table.
- Service unbound (`stop_app`): `tree()` reports `eye=slow` and still answers; after `enable_auto_paste()`
  and three reads it reports `eye=fast` again.
- Permission: `am broadcast -a com.envi.wispr.debug.DUMP com.envi.wispr` from the shell → result 1;
  `run-as com.envi.wispr am broadcast -a com.envi.wispr.debug.DUMP com.envi.wispr` → refused.
- Benchmark rerun, six errands, both ways.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| serialiser: two nested fake nodes carrying a NON-DEFAULT value in every one of the 13 attributes (`clickable=true`, `checked=true`, non-empty id, …) → XML whose `tree` parse equals the expected dicts, parent index included; one mutation per attribute omitted turns it red | product-adjacent | shape parity | omit any one attribute |
| serialiser escapes `&`, `<`, `>`, `"` and a control character in EACH of text, desc, package, class, id | product-adjacent | XML validity | drop the escaper on any one attribute |
| receiver: encoded result over 256 KiB → result 2 and no data; at the boundary → result 1 | product-adjacent | binder safety | raise the cap |
| serialiser: a window with null root is skipped, output still parses | product-adjacent | never throws | let it throw |
| `tree` with a fake broadcast answering result 1 + data → no `uiautomator` command sent, `_STATE["eye"]=="fast"` | contract | fast path | always dump |
| `tree` with result 0 → `uiautomator` path, `_STATE["eye"]=="slow"`, and the broadcast is NOT retried in the same process | contract | no receiver | probe every call |
| `tree` with result 3, then 3, then 3, then 1 → slow, slow, slow, then FAST again (cooldown of three) | contract | transient fallback | slow forever |
| `tree` with result 1 and undecodable data → `Blocked` naming the eye; the next call is slow and does not raise | contract | bad data | swallow |
| `find` on a fake screen that changes between reads: the refusal lists the labels of the FIRST read (one read counted) | contract | §2.2 | call `look()` afresh |
| restore in place: fake screen on the tab with the label visible → `open_tab`, `open_settings`, `nav` are SPIED and none is invoked; the switch is read back | contract | chunk 2 | always navigate |
| restore: label visible but `on_screen(where)` false → `open_tab` IS invoked | contract | wrong-screen guard | remove only the `on_screen` conjunct, keeping the visible-label fast path |
| `set_switch` continues after two consecutive reads of the wanted state (fake tree flips on the second read) and waits the full 0.6 s when it never flips (fake clock) | contract | chunk 3 | fixed sleep |

## 12. Blast radius & rollback
Touched: one companion function in the service (dead code in release), the debug source set, the
harness. Not touched: insertion, capture, ASR, any release manifest entry, the hook. Rollback: revert the
PR; the harness's slow path is the pre-change behaviour.

## 13. Ship criteria
- [ ] Benchmark rerun: flip-and-back < 10 s, spoken take < 15 s, unlock < 6 s, 5/5 correct, 0 dirty, output ≤ #177's.
- [ ] `tree()` on the AVD reports `eye=fast` under 0.3 s and the same labels as the slow eye.
- [ ] `./gradlew :app:testDebugUnitTest` green with the count; `:app:assembleDebug` builds.

## 14. Open questions
- P3, P4 (measured in chunk 1 / §11.1). Chunk 3 kept or dropped by its own measurement.
- Review round 3 asked for a SEPARATE unprivileged test app to prove the `DUMP` gate refuses other apps.
  Author decision: rejected as out of proportion. The property is "a sender without `DUMP` is refused";
  the app's own uid holds no `DUMP` (it is `signature|privileged|development`, never grantable to a
  normal app; `dumpsys package com.envi.wispr` lists no such grant), so `run-as` from that uid IS an
  unprivileged sender and executes the property. A second APK would prove the same thing at the cost of
  a new module. Recorded here so the code review adjudicates the decision rather than re-finding it.

## 15. Related
#177, PR #183, `docs/benchmark-results/2026-09-20-issue-177-harness-vs-raw.md`,
`.codex/2026-09-20-issue-177-speed-tokens.txt.last`.
