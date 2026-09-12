# Issue #135 — Floating lips button as the primary trigger — 2026-09-12

GitHub issue: `#135`. Tier: LARGE. Status: APPROVED (founder Gate 2, 2026-09-12; Codex PROCEED-AS-PLANNED after seven grounded
rounds, session `01a0979b-dde0-7761-9937-711cf28c5ddf`).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code. `app/src/main/java/com/envi/wispr/paste/**`, `app/src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt`,
`app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`, `app/src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt`,
`app/src/main/res/xml/accessibility_service_config.xml`, `app/src/main/res/values/strings.xml`, tests.

**PAR rows closed:** `PAR-072` (persisted position plus drag-safe bounds; evidence: the hardware run in §11.1 shows a dragged
position surviving an app switch and a reboot). **Updated, not closed:** `PAR-010`, `PAR-012` (the bubble becomes the primary
trigger and the side button the fallback), `PAR-013` (tap-to-start and hold-to-talk on one button with no mode setting; the
hands-free lock `PAR-014` stays open, tracked by #29). `PAR-071` untouched (#34 keeps the three presentations).

**Hardware UAT:** Y, on the laptop's Android 16 emulator first (founder instruction 2026-09-12), the S26 only for the two
questions §11.1 names. A person opens Google Messages, taps into the compose field, sees the lips at the right edge above the
keyboard, taps them, says "I'll be there at seven, can you bring milk eggs and bread", taps the check, and the polished sentence
lands in the compose field while the keyboard never moved. Then, in Chrome, they hold the lips, say "actually make that eight",
let go, and the words land. Then they drag the lips to the left edge, switch apps, and the lips are at the left edge above the
next field.

## Preface — User Rubric

1. **Who is this user in this moment?** Meera Patel, replying to her mother in Google Messages, phone in one hand, child in the
   other. Thirty seconds ago she read the message; thirty seconds from now she wants to have answered without typing. She has a
   Pixel, not a Samsung, so there is no side button to double-press.
2. **Why would they want this?** "I just want a button right there where I'm typing. I'm not going to remember a shortcut."
3. **How would they invoke it?** Voluntary, whenever the keyboard is up. Already in the right app; she never opens EnviousWispr.
4. **What app are they in?** Messages, WhatsApp, Gmail, Chrome (a form), Keep. Each has a different composer and send control near
   the bottom right, which is exactly where the bubble lands by default; she must be able to drag it clear.
5. **What is their natural input?** "I'll be there at seven." "Actually make that eight." "Bring milk eggs and bread." "Can you
   call me when you're free." "Um, tell Grandma I'll call Sunday."
6. **What does success feel like?** She taps, talks, taps the check, and the sentence is in the box. She notices nothing else.
7. **What does wrong-not-broken look like?** She drags the bubble out of the way of the send button and a recording starts. Or
   she taps, nothing visibly happens for a second, she taps again, and the second tap cancels the first. She stops trusting the
   button and goes back to Gboard's microphone.
8. **What would a power user hack around this to get?** Aaron Wu maps the side button anyway and turns the bubble off, or drags
   it to the far corner. Priya pins it to the left edge so it never covers Slack's send button.
9. **What level of control would they want?** Off (hide until the next field, later a setting to turn the bubble off), position
   (drag), gesture (tap or hold, chosen per use, never a mode screen). Nothing automatic beyond showing when a field is active.

### Cross-persona check

Priya: wants it out of the way of code and send controls, hence drag plus dock. Marcus: long paragraphs, so tap mode must be the
one that stays recording after release. Diana: zero app switching, which is the whole point. Elena: no new permission and nothing
new crosses the network, both true here. Aaron: one-hand trigger with no modal, and hold-to-talk is the one-hand gesture. Meera:
simple and predictable, so a drag must never record and a second tap must never cancel. Frank: no setup, so the bubble appears
without configuring anything; his tremor makes the slop threshold matter, resolved in §3 by using Android's own touch slop.

---

## 0. TL;DR

Consolidation: none. This adds a sixth caller of the one session owner and a second shape to the one floating window;
it does not merge duplicate owners. Every existing start path and the one overlay listener are retained; the two `hide()` calls
in the accessibility service are removed and the owner gains phase publication and the request ledger, as §3 specifies.

Android's only primary trigger is a Samsung-specific side-button mapping. Add a floating lips bubble, hosted in the accessibility
window EnviousWispr already owns, that appears when an editable field in another app is focused, starts a dictation on tap or
hold, becomes the existing recorder pill anchored at its docked edge, and remembers where the user put it. No new permission, no
new process, no new pipeline: the bubble is a sixth caller of the existing session owner, through the same transparent launcher
the Quick Settings tile already uses. LARGE because it is the trigger stage of the heart and it adds a phase to the overlay state
bridge the session owner publishes. Proof: pure-Kotlin product-outcome tests for the gesture and placement rules, and a hardware
run in three real editors with the exact words named.

## 1. Problem

- `docs/enviouswispr-android-parity-spec.md:31` names the side-button double press as the launch mechanism, and
  `app/src/main/AndroidManifest.xml:55` binds it through `android.intent.action.ASSIST`. Samsung's "Open app" mapping does not exist on Pixel,
  Xiaomi, OnePlus or Motorola phones, so on those the primary trigger is the Quick Settings tile, two swipes away from the field.
- Founder decision 2026-09-12 (tracker, "Generalized activation direction"): the floating lips button is the primary trigger; the
  hardware press is an optional convenience. Mock approved 2026-09-12 (PR #134).
- Today the only floating surface is the recording pill, shown between `RecordingOverlayState.show()` and `hide()`, pinned to the
  top centre (`RecordingAccessibilityOverlay.kt:296`), with no idle state and no way to move it (#34).

## 2. Goals & non-goals

### 2.1 Goals
1. A lips bubble is visible over another app exactly while a safe focused editable field exists there, and never over our own
   windows.
2. Tap starts a dictation; the check stops; the cross cancels. Hold for the system long-press timeout starts; release stops.
   Movement past the system touch slop before the hold threshold is a drag and never records.
3. The bubble morphs into the existing pill, anchored to its docked edge, growing inward. Keyboard and editor focus are untouched.
4. Drag docks to the nearest edge; side and height persist per phone across app switches, process death and reboot.
5. Drop on the bottom target hides the bubble until a different editable field is focused.
6. A tap while a dictation is starting or processing does nothing, and a release during startup finishes the take rather than
   cancelling it.
7. No new permission, no new foreground service, no idle work: every transition is driven by an accessibility event or a touch.

### 2.2 Non-goals
- Hands-free lock, a mode setting, the three presentations (#29, #34 keep them).
- A "turn the bubble off" setting (route to an issue; hide-until-next-field is the v1 control).
- Live partial transcript in the bubble (PAR-068, #33).
- The practice-step bubble inside our own onboarding. Our own windows are excluded from target tracking by design
  (`code-gotchas.md` RULE: accessibility-service-must-ignore-our-own-windows), so the practice screen renders its own Compose lips
  that call Codex's token-carrying practice START. That is a follow-up on top of `codex/android-onboarding` once it merges.
- Any change to what is inserted, or to the insertion path.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

The thing being changed is **how a dictation is started from another app**, and **what the floating window shows at idle**.

Today, start:
- Samsung side button → `ASSIST` → `ui/VoiceInputActivity.kt:24` `onCreate`: checks `RECORD_AUDIO`, maps extras to an action
  (default `ACTION_TOGGLE`), calls `PasteAccessibilityService.pinTargetForDictation()` (line 54), then
  `DictationSessionService.sendCommand(this, action)` (line 56), then finishes. Its window is 1×1, not focusable, not touchable
  (lines 63-75), so the editor keeps focus.
- Quick Settings tile → `shortcuts/DictationTileService.kt:37` `onClick`: reads `DictationSurfaceState.read`, and when IDLE starts
  `VoiceInputActivity` with `EXTRA_TOGGLE` through `startActivityAndCollapse` (lines 47-62). When not idle it sends
  `ACTION_TOGGLE` straight to the service (line 43).
- Notification → `shortcuts/DictationNotificationController.kt:163` `serviceIntent`: Stop and Cancel go straight to the service
  as `PendingIntent.getService` (lines 126-140). There is no idle Start action on the notification.
- The service → `ui/DictationSessionService.kt:323` `onStartCommand`: `ACTION_START` begins a session only from IDLE (line 361);
  `ACTION_TOGGLE` from STARTING cancels (line 357); `ACTION_STOP` from STARTING cancels (line 351), from RECORDING transcribes.
  `beginSession` (line 368) pins the target again (line 375) and binds the three pipeline services.
- The overlay bus → `shortcuts/RecordingOverlayState.kt`: `show()` at `DictationSessionService.kt:483` once capture is RECORDING;
  `showNotice` at 620; `hide()` at 637 (stop and transcribe), 1098, 1153, 1171, 1349 (cancel and teardown paths).
  `DictationSurfaceState.update` at 482, 638, 1100, 1134, 1143, 1155, 1172 writes IDLE/LISTENING/PROCESSING to SharedPreferences
  and pokes the tile.
- The pill → `paste/RecordingAccessibilityOverlay.kt`: the single `RecordingOverlayState.Listener`, attached by
  `PasteAccessibilityService.onServiceConnected` (line 185). Window type `TYPE_ACCESSIBILITY_OVERLAY`, flags NOT_FOCUSABLE,
  NOT_TOUCH_MODAL, LAYOUT_IN_SCREEN (lines 42-45), width set to the painted width because the window rectangle is the touch area
  (lines 33-39, 295-306). Its cross and tick send `ACTION_CANCEL` and `ACTION_STOP` to the service (lines 216-227), which proves a
  touch on this window already reaches the session owner.

Today, "is a field active":
- `paste/PasteAccessibilityService.kt:225` `onAccessibilityEvent` → `rememberEditableTarget` (line 338): ignores our own package
  (line 340), keeps an editable focused or focused/clicked node as `lastTarget`. Event mask: `TYPE_VIEW_FOCUSED`,
  `TYPE_VIEW_CLICKED`, `TYPE_WINDOW_STATE_CHANGED` (lines 107-109; also `accessibility_service_config.xml`). Nothing consumes
  `lastTarget` at idle; `pinTarget` (line 422) re-validates it with `refresh()` and `isSafeFocusedEditor` (line 494) when a
  dictation starts.

Commands that found it:
```
/usr/bin/grep -n "RecordingOverlayState\.\(show\|hide\|showNotice\)\|DictationSurfaceState.update" app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt
/usr/bin/grep -n "Intent\|startActivity\|sendCommand\|onClick\|ACTION_" app/src/main/java/com/envi/wispr/shortcuts/DictationTileService.kt
/usr/bin/grep -rn "TYPE_INPUT_METHOD\|TYPE_WINDOWS_CHANGED\|softKeyboardController\|AccessibilityWindowInfo" app/src/main/java   # no hits
```

The bubble intercepts at ONE point: it is a new touch source inside the window `PasteAccessibilityService` already hosts, and it
starts a dictation through `VoiceInputActivity`, the same path as the tile. It observes "field active" from the same
`rememberEditableTarget` path every other consumer already trusts.

### 2. Find the existing authority before proposing one

| Concern | Existing authority | Verdict |
|---|---|---|
| Who starts a session | `DictationSessionService` (one owner, `architecture-rules.md` RULE: one-owner-for-the-session) | reuse |
| Starting from a non-activity context | `VoiceInputActivity` trampoline, used by the tile | reuse; add `EXTRA_START` (proposed) |
| Which field is focused | `PasteAccessibilityService.lastTarget` + `isSafeFocusedEditor` | reuse |
| The floating window | `RecordingAccessibilityOverlay`, one window, one listener | extend |
| Session phase for surfaces | `RecordingOverlayState` (in-process, pushed) and `DictationSurfaceState` (SharedPreferences, for the tile) | extend `RecordingOverlayState.Snapshot` with a phase; do not read SharedPreferences from a touch |
| Persisted service-local state | `PasteAccessibilityService.lifecyclePreferences()` pattern (line 265) | same pattern, new file name |
| Gesture timing constants | `ViewConfiguration` (external), its `getLongPressTimeout` (external) and `getScaledTouchSlop` (external) | reuse, never hard-code |
| Haptics | `DictationSessionService.HapticCue` at RECORDING (line 1309); `View.performHapticFeedback` (external) for the hold cue | reuse |

Negative sweep for an existing idle bubble or drag logic:
```
/usr/bin/grep -rn -i "bubble\|floating button\|snapToEdge\|dock" app/src/main/java | /usr/bin/grep -v "FloatingActionButton" → 0 hits
```
`new authority proposed` for gesture classification, placement and visibility policy, all pure Kotlin.

### 3. Read prior attempts and live direction

- #34 (2026-08-29): the pill covered Keep's pin button; asks for a persisted top or bottom position with drag-safe bounds. Never
  started. This plan supersedes its position half with side plus height.
- #29: three macOS modes. Catalog `push-to-talk` android: absent; macOS: press starts, release stops, 500 ms multi-press window.
  Catalog decision 2026-05-30: macOS fresh installs default to Push to Talk. The bubble gives both gestures without a setting,
  which is the founder's 2026-09-12 direction and the study's recommendation (`wispr-flow-android-overlay-study.md` § Toggle and
  push-to-talk investigation).
- Catalog `pill-position` android: partial, "hard-coded to top-centre".
- Session log 2026-09-06: the overlay is invisible to `uiautomator dump` and `screencap` (external); `dumpsys window windows` is the oracle
  for "is the window shown". Force-stopping the app clears the accessibility permission (#131).
- Window mechanism adjudicated 2026-09-12 (`docs/mockups/android-lips-bubble-v1/window-mechanism-adjudication.md`): extend the
  accessibility overlay; no `SYSTEM_ALERT_WINDOW`. Binding.
- Codex's onboarding plan (`plan-2026-09-12-android-onboarding.md` §6): "Existing accessibility overlay remains sole recording
  overlay listener". Honoured: the bubble is inside that listener.

### 4. Lifecycle, trust and process boundaries

| Boundary | Current | Planned |
|---|---|---|
| Accessibility service alive vs enabled-but-crashed | pill exists only while bound; `isBound` pushed | bubble likewise; nothing to show when unbound, and insertion is impossible then anyway |
| Service reconnect mid-take | `onServiceConnected` recreates the overlay (line 184) | the recreated overlay reads the persisted position and the current `RecordingOverlayState` snapshot; a release already sent is in the owner and still lands; a finger still down on the old overlay is a hold never released, which the pill's check or the session's own cap resolves |
| Main process vs `:audio`/`:asr`/`:polish` | the bubble, the service and the session owner share the main process | unchanged; no AIDL change |
| Foreground start | `VoiceInputActivity` is the visible window that permits the microphone foreground service on every path today | the bubble starts the same activity from the service (`startActivity` with NEW_TASK); background activity starts are permitted for a system-bound accessibility service, verified in §2.5.5 |
| Our own windows | excluded at `PasteAccessibilityService.kt:340` and `:471` | the bubble window is ours; a touch on it produces no event for another package, so it cannot become a target |
| Process death | position lives only in the overlay | position in service-private SharedPreferences, loaded on connect |
| Stale completion | `RecordingOverlayState` delivers the state at delivery time (its own doc) | phase rides the same snapshot; the bubble never caches a phase |
| User action vs background | every transition is a touch or an accessibility event | no timer at idle; the only timers are the hold timeout during a press and the pill's existing one-second tick during a take |

### 5. High-risk premises

| Premise | Evidence | Status |
|---|---|---|
| A touch on our accessibility overlay can start a dictation | the pill's tick and cross already send `ACTION_STOP`/`ACTION_CANCEL` (`RecordingAccessibilityOverlay.kt:216-227`); START goes through `VoiceInputActivity` exactly as the tile does | STOP/CANCEL proven on device (every dictation today); START from the overlay is device probe P3 |
| An accessibility service may start an activity from the background | Android's documented exemption list for background activity starts names services bound by the system, accessibility services among them (https://developer.android.com/guide/components/activities/background-starts) | NOT VERIFIED on the S26; probe P3 |
| Accessibility overlays draw above the keyboard | Measured 2026-09-12 on `emulator-5554` (Android 16, Gboard up in Chrome, `mInputShown=true`): `dumpsys window windows` listed the window titled "EnviousWispr recording controls" at Window #2, type ACCESSIBILITY_OVERLAY, above the window titled "InputMethod" at Window #8, with a real take running ("Recording started" in both processes) | VERIFIED on stock Android 16; NOT VERIFIED on Samsung; probe P1 on the S26 |
| A drag across the keyboard keeps touch delivery | overlay is NOT_TOUCH_MODAL and the window rectangle is the touch area | probe P2 |
| `TYPE_WINDOWS_CHANGED` (external) delivers keyboard show and hide | Needs all three of: the event subscription, `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (set at `accessibility_service_config.xml:6` and `PasteAccessibilityService.kt:218-220`), and `canRetrieveWindowContent` (set at `accessibility_service_config.xml:7`). Reference: https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS | NOT VERIFIED on any device; probe P1 |
| A touchable accessibility overlay receives drag motion on Android 12+ | Android 12's untrusted-touch restriction exempts accessibility windows: https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events | drag continuity across the window's own move and morph is NOT VERIFIED; probe P2 |
| Only one listener may attach to `RecordingOverlayState` | `attach` replaces the listener (`RecordingOverlayState.kt:41`) | by construction; the bubble is inside the one listener |
| No `SYSTEM_ALERT_WINDOW` (external) today | `/usr/bin/grep -rln "SYSTEM_ALERT_WINDOW" app/src` → 0 hits | verified 2026-09-12 |
| Release during STARTING currently cancels | `DictationSessionService.kt:351` | verified by read |

The plan depends on who-calls-whom and on platform behaviour, so the grounded review asks Codex to trace current reality and name
naive-design traps (§14), not to design.

## 3. Design

This section is one contiguous statement, rewritten after review round 5 invoked the pre-committed remedy. §4 to §9 are derived
from it and say nothing it does not.

**One window, two shapes.** `RecordingAccessibilityOverlay` keeps owning the one accessibility window and the one
`RecordingOverlayState` listener. It gains an idle shape, the bubble, beside its recording shape, the pill. The window rectangle
is always exactly the painted shape, so nothing transparent eats taps. `PasteAccessibilityService` hosts it as today.

**When the bubble shows.** `PasteAccessibilityService.rememberEditableTarget` already fires on a focused editable in another
package and keeps a node copy (`PasteAccessibilityService.kt:338-355`); it now also hands the overlay `fieldActivated`
(proposed) with a field key. The key is the editor's node identity, which `AccessibilityNodeInfo` (external) equality defines as
window id plus source node id, so two editors in one window are two keys and repeated events for one editor are one key; never
the window or the view id alone. On `TYPE_WINDOW_STATE_CHANGED` and on `TYPE_WINDOWS_CHANGED` (added to the event mask) the
service re-validates `lastTarget` with `refresh()` and `isSafeFocusedEditor`, hands the overlay `fieldLost` (proposed) when
that fails, and reads the IME window bounds from `windows` to hand the overlay `keyboardBounds` (proposed). This is the
adjudication's reading of the no-idle-cost law, recorded here as the interpretation: zero RECURRING idle work, event-driven
transitions allowed (`window-mechanism-adjudication.md` § B). Three guards keep event-driven from becoming recurring. A
windows-changed event whose `getWindowId` (external) matches the overlay's own accessibility window id is ignored before any
refresh, with `getWindowChanges` (external), available from API 28, used only to classify the change type; an event whose
ownership cannot be told goes through the next guard. Eligibility and geometry are handed to the overlay only when they changed,
and the overlay never re-attaches or updates a window whose bounds are unchanged, extending the existing skip at
`RecordingAccessibilityOverlay.kt:301-304`. Probe P8 counts events for ten seconds after external activity stops and requires
zero. Whether Chrome's web editors keep a stable node identity across events is NOT VERIFIED and is probe P7.

**Hide until the next field.** Dropping the bubble on the bottom target sets an in-memory flag in the overlay together with the
current field key. A `fieldActivated` with a different key clears the flag; the same key keeps it. The flag dies with the process.

**Gestures, pure Kotlin.** `BubbleGestureClassifier` (proposed) takes pointer down, move, up and cancel with timestamps and
returns exactly one of Tap, HoldStart, HoldRelease, HoldCancelled, DragMove, DragEnd (inside or outside the hide target), or
Nothing, using the injected long-press timeout and touch slop (`ViewConfiguration` (external) on the phone, literals in tests).
Movement past slop before the timeout is a drag and cancels the hold. A pointer cancel before the timeout is Nothing; during a
hold it is HoldCancelled; during a drag it is DragEnd outside, which restores the previous dock and persists nothing. A second
pointer arriving or leaving is ignored; the primary pointer leaving while a second is down is treated as up. The overlay's own
configuration callback (today only a resize, `RecordingAccessibilityOverlay.kt:145-149`) supplies a cancel to the classifier
and clears the hold timer BEFORE relayout, so rotation never depends on Android delivering a pointer cancel. HoldStart gives
`View.performHapticFeedback` (external) with the long-press constant on the overlay view.

**Placement, pure Kotlin.** `BubblePlacement` (proposed) holds side (LEFT or RIGHT) and a height fraction of the usable range.
It takes one usable rectangle (screen minus status, navigation, cutout and landscape side insets, or the multi-window rectangle)
plus a keyboard top, the bubble's 56 dp and the pill's size, and returns the bubble rectangle, or the pill rectangle anchored to
the docked side at the bubble's centre line, growing inward. The keyboard top clamps the bubble upward without changing the
stored fraction. An IME rectangle that does not touch the bottom edge (floating or split keyboard) is ignored. A usable range
shorter than the bubble hides the bubble for that layout; an unavailable rectangle leaves the last good placement, mirroring the
guard at `RecordingAccessibilityOverlay.kt:109-113`. Drag end snaps to the nearer side and writes side plus fraction through
`BubblePositionStore` (proposed): service-private SharedPreferences in the `lifecyclePreferences()` pattern
(`PasteAccessibilityService.kt:265`), loaded once on connect inside the existing IO launch, written on drag end. An absent,
unreadable, malformed or out-of-range store yields the default placement, right side above the keyboard, overwritten on the next
drag; a failed write leaves the in-memory position for this process.

**Requests, and the ledger that orders them.** Every bubble start is a request with a token. Tokens come from `BubbleRequests`
(proposed), a process-local object in `shortcuts/` shared by the overlay and the session owner, holding a random per-process
epoch and a counter: a token is `epoch:seq`, and `seq` increases with every request the bubble makes. The ledger also holds the
high-water mark, the highest `seq` the owner has admitted or retired in this epoch, and at most one early note: a `seq` above the
high-water mark whose START has not arrived, marked released or cancelled. The one slot never silently drops a release: it
keeps the HIGHEST `seq`, and any `seq` it displaces or declines is RETIRED by advancing the high-water mark to it first, so that
`seq`'s START is refused as stale rather than admitted unreleased. Concretely, a note for a higher `seq` retires the lower one
it displaces; a note for a lower `seq` than the one held retires itself and leaves the held note; for the same `seq`,
cancelled wins over released. Before any of this, a token from another epoch is rejected for EVERY command, START, STOP and
CANCEL alike, before active-token matching, sequence comparison or note mutation, so an old-epoch STOP with `seq` N can never
create a note that a new-epoch START N consumes. Because the object is process-local it survives the
session service being destroyed between takes (`DictationSessionService.kt:1167-1180`) and dies only with the process, where
the epoch changes and every older token becomes stale by construction.

**Start.** Tap and HoldStart mint a token and start `VoiceInputActivity` with `EXTRA_START` (proposed, maps to `ACTION_START`,
which unlike TOGGLE cannot cancel a STARTING take, `DictationSessionService.kt:355-361`) and `EXTRA_REQUEST` (proposed) carrying
the token. The activity keeps its microphone check and its 1×1 non-focusable window and forwards the token on the service
intent. In `onStartCommand`, before today's dispatch (`DictationSessionService.kt:323-362`), the owner applies the ledger:
a START whose token has another epoch or a `seq` at or below the high-water mark is refused as stale and nothing changes; a START
above the mark while the owner is not IDLE is refused and its `seq` becomes the mark, so the user simply taps again; a START above
the mark while IDLE is admitted, its `seq` becomes the mark, its token is bound to the take, and the early note for that `seq`,
if any, is consumed: released means stop as soon as RECORDING is reached, cancelled means cancel from STARTING. A START without
a token (side button, tile, practice) is unchanged.

**Stop and cancel.** The pill's check and cross keep sending unmarked `ACTION_STOP` and `ACTION_CANCEL`, as do the tile,
notification and side button, with today's meaning. HoldRelease sends `ACTION_STOP` with the bubble's token at once, whatever
the snapshot shows; HoldCancelled sends `ACTION_CANCEL` with the token. A token-marked STOP or CANCEL is resolved by the owner
in `onStartCommand`, after the epoch check: for the admitted token (whose `seq` equals the mark, which is why the admitted
token is matched BEFORE the mark is consulted), STOP during STARTING sets stop-after-RECORDING instead of today's cancel (line
351 stays for unmarked STOP), STOP during RECORDING transcribes, CANCEL cancels; for a `seq` above the high-water mark it becomes
the early note under the replacement rule above; for anything else it is ignored as retired. Admitting or retiring a
`seq` drops any note at or below it, and a START for a dropped note is refused as stale by the high-water rule, so a release can
neither be lost onto a later admission nor stop a take it did not belong to. Two sequences settle it: STOP(A) noted, START(B)
admitted (mark = B, note A dropped), B finishes, delayed START(A) arrives: refused, A never records. STOP(A) noted, START(A)
admitted first: it stops after RECORDING; START(B) then arrives while A runs: refused, mark = B.

**Phase on the bus, published by the owner alone.** `RecordingOverlayState.Snapshot` gains `phase: Phase` (proposed, IDLE,
STARTING, RECORDING, PROCESSING) and `requestToken` (proposed, the admitted take's token or null). Only `DictationSessionService`
writes them: STARTING in `beginSession`, RECORDING where it calls `show()` today (`DictationSessionService.kt:483`), PROCESSING
in `stopAndTranscribe` (637), on cancel (1098, 1171) and on `showError` (1153, which hides before finishing, lines 1150-1159),
because CANCELLING, FINISHING and ERROR are still not accepting a start; IDLE at every point its own state becomes IDLE
(enumerated at build time from every `SessionState.IDLE` write) and in its orderly `onDestroy` (1349). `hide()` sets IDLE and
clears the token. `PasteAccessibilityService.onInterrupt` and `onDestroy` (236, 264) stop calling `hide()` and detach the
surface only, so a reconnect renders whatever the owner retained and a hidden pill never means IDLE. `visible` keeps meaning
"draw the pill", drawn during RECORDING; during STARTING and PROCESSING the window shows the bubble in a working state with its
controls off. The overlay reads the latest snapshot for rendering and for one decision, tap eligibility: a tap starts a new
request only when the phase is IDLE. Nothing in the snapshot is a command acknowledgement, so coalescing cannot lose one.

**Rejected alternatives.**
- Application overlay from a persistent foreground service: adds "Appear on top", a permanent notification, and a second host;
  the adjudication's option 2.
- Sending `ACTION_START` straight from the overlay: skips the microphone check and the visible-window exemption
  `VoiceInputActivity` provides today on every path; would need its own device proof.
- A latch or acknowledgement in the bubble (rounds 2 to 4): the trampoline makes START asynchronous and the service is destroyed
  between takes, so any bubble-side wait had an ordering hole. Deleted in favour of the ledger, where every command is serialized.
- Reading `DictationSurfaceState` on tap: a SharedPreferences read on the main thread per touch.
- A `TYPE_WINDOW_CONTENT_CHANGED` subscription for finer focus tracking: fires constantly while typing; the service reserves it
  for the insertion window (line 212).

## 3b. Ownership justification

The bubble will live in `RecordingAccessibilityOverlay`, hosted by `PasteAccessibilityService`, because that service already owns
the only window allowed to float over other apps without a new permission, already knows which editable field is focused, and
already ignores our own windows. The ledger will live in `shortcuts/` beside `RecordingOverlayState` because both are
process-local bridges between the overlay and the owner. The alternative was a new overlay service with `SYSTEM_ALERT_WINDOW`
(external), but that adds a permission step to onboarding, a permanent foreground service against the no-idle-cost law, and a
second host for one gesture that has to survive the morph from bubble to pill.

## 4. Contract deltas

| Type | Delta | What it now means |
|---|---|---|
| `RecordingOverlayState.Snapshot` | `phase` (proposed), `requestToken` (proposed); `visible` kept | the owner's word for the admitted take, for rendering and tap eligibility only; never an acknowledgement; readers of `visible` alone see no change |
| `RecordingOverlayState` | `showStarting` (proposed), `showProcessing` (proposed), each taking the token; `hide()` clears the token | written by the owner only; the accessibility service no longer calls `hide()` |
| `BubbleRequests` (proposed) | new process-local object: epoch, counter, high-water mark, one early note | the only place a bubble request is ordered; shared by overlay and owner in the main process |
| `VoiceInputActivity` | `EXTRA_START` (proposed), `EXTRA_REQUEST` (proposed) beside the three existing extras (`VoiceInputActivity.kt:17-19`) | START maps to `ACTION_START`; the token is forwarded on the service intent; default stays TOGGLE |
| `DictationSessionService` | reads `EXTRA_REQUEST` on START, STOP and CANCEL; `sendCommand` gains an optional token | applies the ledger rules of §3 before today's dispatch; unmarked commands keep today's meaning |
| `PasteAccessibilityService` event mask | `TYPE_WINDOWS_CHANGED` (external) added | keyboard show and hide reach the service; `rememberEditableTarget` ignores it (no source) |
| `PasteAccessibilityService` → overlay | `fieldActivated` (proposed) with a field key, `fieldLost` (proposed), `keyboardBounds` (proposed) | the overlay never reads the node tree itself |
| Accessibility disclosure string | names the floating button | policy surface; changed in the same commit as the behaviour |

## 5. End-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Every site that hides the pill | `DictationSessionService.kt:637, 1098, 1153, 1171, 1349` plus `PasteAccessibilityService.onInterrupt` (236) and `onDestroy` (264), listed by the grep in §2.5.1 and confirmed complete by the coverage round. 637, 1098, 1153, 1171 publish PROCESSING; 1349 publishes IDLE; IDLE is otherwise published at every `SessionState.IDLE` write, enumerated at build time, and the guard asserts those distinct publications; 236 and 264 publish nothing and detach only |
| Every site that shows the pill | `DictationSessionService.kt:483` only |
| Every start path into the session | ASSIST activity, tile (idle branch via the activity, non-idle direct), notification (no start), the app's practice START (Codex, its own token), the bubble (new, via the activity with a ledger token). None bypasses `onStartCommand` |
| Every state `ACTION_STOP` can land in | IDLE (stopIfIdle), STARTING (cancelStarting for unmarked; stop-after-RECORDING for the admitted token), RECORDING (stopAndTranscribe), PROCESSING, CANCELLING, FINISHING, ERROR (stopIfIdle). A token-marked STOP additionally resolves against the ledger before dispatch: admitted, above the mark (noted), or retired (ignored) |
| Every token outcome at the owner | Any command: other epoch, rejected first. START: stale (`seq` at or below the mark), refused busy (above the mark, owner not IDLE), admitted. STOP or CANCEL: admitted token, above the mark (noted under the replacement rule), retired. Seven exits, each named in §7 |
| Every reason the overlay can be recreated | `onServiceConnected` first and repeat, after `onDestroy`. Both reload the position and re-read the snapshot; the ledger is untouched |
| Every window our package can put on screen | `SettingsActivity`, `VoiceInputActivity`, `AccessibilityGuideActivity` (Codex, in flux), the overlay, notifications, the tile, and the app's text toasts (`VoiceInputActivity.kt:28,55`; `DictationSessionService.kt:630,884,1067,1157`; `PasteAccessibilityService.kt:1022`). Activities and the overlay are excluded by the own-package check at lines 340 and 471; toasts and notifications carry no editable node |
| Every bound the bubble must respect | the inputs of `BubblePlacement` in §3, plus its two degenerate cases |
| Gesture outcomes | the seven outputs and the input cases of §3; sealed result, `when` without `else` |
| Phases | IDLE, STARTING, RECORDING, PROCESSING as an enum; `when` without `else` |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `Snapshot.phase`, `requestToken` | `RecordingAccessibilityOverlay` | draws on `visible` | draws bubble, pill or working state by phase; taps only at IDLE | yes | unit test on the overlay's shape decision |
| `Snapshot.phase` | `PasteAccessibilityService.onInterrupt`, `onDestroy` | call `hide()` | detach only | yes | drift guard: the accessibility service publishes nothing to the bus |
| `BubbleRequests` | overlay (mints), owner (resolves) | none | as §3 | yes | owner tests in §11.2 |
| `EXTRA_START`, `EXTRA_REQUEST` | `VoiceInputActivity` | three extras | five; default TOGGLE | yes | drift guard on the extras |
| token on `sendCommand` | tile, notification, pill, side button | unmarked | unmarked, unchanged | no | drift guard: unmarked STOP and CANCEL keep today's meaning |
| event mask | `accessibility_service_config.xml`, `configureEventMode` | three types | four | yes | `PasteServiceProcessManifestTest` extended |
| `DictationSurfaceState` | tile, notification | unchanged | unchanged | no | none needed |
| Disclosure string | onboarding (Codex), Settings | names field detection and insertion | also names the floating button | string only | read |
| `dumpsys window` title | Wispr Eyes `overlay` command | "EnviousWispr recording controls" | unchanged | no | run the command |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Tap while STARTING or PROCESSING | user | bubble | nothing; the working state keeps showing | none | the take finishes on its own |
| START stale (other epoch, or `seq` at or below the mark) | ledger | owner | nothing changes; a delayed launcher from a dead process or a dropped note cannot start a take | none | tap again |
| START refused busy (above the mark, owner not IDLE) | ledger | owner | nothing; its `seq` is retired so a later release for it is ignored | none | tap again |
| START admitted with a released note | ledger | owner | pill appears for an instant, then working state; the words are transcribed | none | none needed |
| START admitted with a cancelled note | ledger | owner | nothing lands; bubble idle | none | tap again |
| STOP with the admitted token during STARTING | user | owner | stop-after-RECORDING; the take reaches RECORDING and stops at once | none | none |
| STOP or CANCEL above the mark (START not yet arrived) | user | owner | noted, cancelled winning over released; resolved at that token's admission or dropped when a newer `seq` is admitted or retired | none | tap again if nothing happened |
| STOP or CANCEL retired (at or below the mark, not admitted) | user | owner | ignored | none | none |
| STOP(A) noted, START(B) admitted, B finishes, delayed START(A) | user | owner | B runs to completion; A is refused stale; no unreleased recording | none | none |
| Unmarked STOP or CANCEL from another surface during a bubble take | user | owner | today's meaning; the phase moves as the owner decides; the bubble follows | none | none |
| Practice START while a bubble take runs | onboarding | owner | refused by the owner's own busy check | none | existing |
| Take never reaches RECORDING, any origin | service (`showError`) | owner | existing error toast; phase returns to IDLE; the bubble returns to idle | none | tap again |
| Activity start throws | Android | bubble | toast "Dictation could not start", the launcher's own copy | none | tap again |
| Activity start silently denied (no phase ever arrives) | Android | bubble | nothing changes; the bubble stays idle and accepts the next tap; no timer is armed, by the no-idle-cost law | none | tap again; P3 measures whether the case exists |
| Microphone permission missing | Android | `VoiceInputActivity` | existing toast and Settings launch | none | grant, tap again |
| Service reconnect mid-take | Android | overlay | redraw from the snapshot the owner retained; a release already sent is in the ledger; a finger still down on the old overlay is a hold never released, resolved by the pill's check or the duration cap | position survives | tap the check |
| Accessibility turned off mid-take | user | service | the overlay goes with the service; the take continues headless with the notification's Stop and Cancel; turning it back on recreates the overlay from the live snapshot | position survives | existing |
| Process dies during a take | Android | session | no `onDestroy` is assumed; the bus, ledger and hidden flag die with the process; on the next connect the overlay reads IDLE and shows the bubble at the persisted position; recovery of the interrupted take is UNCHECKED and not this change's promise | position survives | tap again |
| Field lost mid-take (app switched) | user | service | pill stays; insertion falls back as today | none | existing |
| A second field gains focus mid-take | user | service | pill unaffected; insertion targets the node pinned at start (`PasteAccessibilityService.kt:593-606`); at idle the bubble follows the new field; the hide key compares node identity | none | none |
| Keyboard closes while the field stays focused | user | placement | bubble or pill moves down to the navigation inset | none | none |
| Keyboard bounds unreadable, or floating and split keyboards | Android | placement | navigation inset only; the bubble may sit behind a docked keyboard until the next windows event | none | next event |
| Rotation before the hold timeout, during a hold, or during a drag | user | overlay | the callback cancels: Nothing, HoldCancelled (cancel sent), or previous dock restored | nothing written | again |
| Overlay window fails to size or attach at idle | Android | overlay | no bubble until the next accessibility event retries, mirroring `RecordingAccessibilityOverlay.kt:109-119` | none | next event |
| Position store absent, unreadable, malformed, out of range | disk | store | default placement, overwritten on the next drag | rewritten | none |
| Position store write fails | disk | store | in-memory position for this process | none | next drag |

## 8. Caller-visible signals audit

| Signal | Meaning beyond its type |
|---|---|
| `Snapshot.phase == IDLE` | the bubble may mint a request; not an acknowledgement of anything |
| `Snapshot.requestToken` | which bubble request the live take answers, or null for an unmarked take; rendering only |
| `Snapshot.visible` | the pill is drawn; unchanged meaning |
| ledger high-water mark | every `seq` at or below it is retired: its START is stale; its STOP or CANCEL still applies when it is the admitted token, and is ignored otherwise |
| ledger early note | the highest `seq` above the mark, released or cancelled; consumed at admission; a `seq` it displaces or declines is retired by advancing the mark first |
| ledger epoch | a token from another epoch belongs to a dead process and is rejected for every command before anything else is read |
| stop-after-RECORDING mark on the admitted take | a release already arrived; the take stops the moment it reaches RECORDING |
| `lastTarget` non-null | a field was active at some point; NOT proof it still is, so every show re-validates with `refresh()` |
| hidden-until-next-field flag with its field key | absence means not hidden; the key is node identity |
| `BubblePositionStore` absent file | first run; default placement, not an error |
| overlay window title | unchanged, so the device harness keeps finding it |

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| take never starts | bubble returns to idle | `Snapshot.phase == IDLE` from the owner | the owner decides the phase | IDLE observed | stay in working state until the next snapshot | overlay |
| a START's admissibility | stale, refused busy, admitted | the ledger high-water mark and the owner's state, both in one serialized call | one place, arrival order | the exit named in §5 | none needed | owner |
| position unreadable | default placement | `BubblePlacement` default | a literal, no machinery | bubble visible in bounds | none | overlay |
| keyboard bounds unreadable | navigation inset | `WindowInsets` (external) | already used by the pill | bubble within screen | none | placement |
| activity start refused | toast | `VoiceInputActivity` copy | one sentence, one owner | toast shown | log only | user |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/shortcuts/RecordingOverlayState.kt`: `Phase` enum (proposed) and `requestToken` (proposed)
  in `Snapshot`; `showStarting` (proposed), `showProcessing` (proposed); `show()` sets RECORDING; `hide()` sets IDLE and
  clears the token.
- `app/src/main/java/com/envi/wispr/shortcuts/BubbleRequests.kt` (proposed): the process-local ledger of §3: epoch, counter,
  high-water mark, one early note, and the three resolve functions the owner calls (start, stop, cancel), pure Kotlin.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: publish STARTING in `beginSession` (after the CAS at line 369),
  PROCESSING in `stopAndTranscribe` (637), on cancel (1098, 1171) and on `showError` (1153); IDLE at every `SessionState.IDLE`
  write and in `onDestroy` (1349). `PasteAccessibilityService.onInterrupt` and `onDestroy` drop their `hide()` calls.
- `app/src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt`: `EXTRA_START` (proposed) in the `when` at line 40, and
  `EXTRA_REQUEST` (proposed) forwarded onto the service intent.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` also reads the request extra in `onStartCommand`, resolves it
  through the ledger before today's dispatch, binds an admitted token to the take, consumes the early note at admission
  (stop-after-RECORDING, consumed at the RECORDING transition at line 462-487, or cancel), and publishes IDLE at every
  `SessionState.IDLE` write. `sendCommand` gains an optional token parameter.
- `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt`: add `TYPE_WINDOWS_CHANGED` to `BASE_EVENT_TYPES`; in
  `onAccessibilityEvent` call `revalidateBubbleField(event)` (proposed) which, for window events, refreshes `lastTarget` and
  reads IME bounds from `windows`, then tells the overlay; in `rememberEditableTarget` tell the overlay a field activated with its
  key. Loads the position store on connect inside the existing IO launch.
- `app/src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt`: idle bubble view (the existing `BrandMarkView` scaled
  in a 56 dp circle with the violet outline), the gesture classifier wiring, the placement, the morph (window bounds from
  `BubblePlacement`, anchored side fixed), the working state, the hide target. No latch: release and cancel go straight to
  the owner with the token, and the bubble mints tokens from the ledger.
- `app/src/main/java/com/envi/wispr/paste/BubbleGestureClassifier.kt` (proposed), `BubblePlacement.kt` (proposed),
  `BubblePositionStore.kt` (proposed): pure Kotlin, no Android types beyond `SharedPreferences` in the store.
- `app/src/main/res/xml/accessibility_service_config.xml`: `typeWindowsChanged` (external) added to the event types.
- `app/src/main/res/values/strings.xml`: the disclosure sentence gains the floating button.
- Tests in `app/src/test/java/com/envi/wispr/paste/`: `BubbleGestureClassifierTest` (proposed), `BubblePlacementTest` (proposed),
  `BubbleVisibilityTest` (proposed); `RecorderBrandTest` and `PasteServiceProcessManifestTest` extended.
- `.claude/knowledge/device-testing.md`: recipe "the-lips-bubble-uat" (probes P1 to P8 with commands).
- Not touched: `AndroidManifest.xml` activity attributes (`code-gotchas.md` RULE: the-overlay-activity-is-transient-by-manifest),
  any AIDL, `:audio`/`:asr`/`:polish`, `AppPreferences` (Codex is editing it and the position is service-local state),
  insertion code.

## 11. Testing

1. **Class of every new test.** `BubbleGestureClassifierTest` (proposed): Product Outcome ("when this fails, the user sees a drag
   start a recording, or a hold that never stops"). `BubblePlacementTest` (proposed): Product Outcome ("the bubble sits behind the
   keyboard, or forgets where it was put"). `BubbleVisibilityTest` (proposed): Product Outcome ("the bubble shows over our own app,
   or stays hidden after a new field"). `BubbleRequestsTest` (proposed): Product Outcome ("a short hold throws the words away, or a
   stale start records with nobody holding"). `RecorderBrandTest` and
   `PasteServiceProcessManifestTest` additions: Drift Guard, and named so.
2. **What revert turns each red.** Remove the slop check → a move past slop before the timeout classifies as HoldStart → the
   "drag never records" case fails. Remove the ledger's early note → a STOP arriving before its START is ignored → the case
   asserting "the admitted take stops after RECORDING" fails. Remove the high-water rule → a delayed START after a newer
   admission is admitted → the case asserting "a dropped note's START is refused" fails. Remove the keyboard clamp → placement returns a rectangle below the keyboard top. Remove the
   own-package guard in visibility → a target from our package shows the bubble. Each revert is performed once, watched fail, and
   restored, in the commit body.
3. **Deliberately not tested.** Pixel-exact drawing of the bubble; Samsung's window layering (a device fact, probed not tested);
   TalkBack navigation of the bubble (v1 gives it a content description and no custom actions; route to an issue).

### 11.1 Hardware UAT spec

- **Subsystem:** heart path (trigger).
- **Where it runs.** Founder instruction 2026-09-12: run the UAT on the laptop's Android environment, the running
  `EnviousWispr_Android_16` AVD (`emulator-5554`, `sdk_gphone64_arm64` (external), Android 16), not on the phone
  (`device-testing.md` RULE: the-emulator-cannot-answer-the-questions-that-matter, emulator FIRST). Every probe below runs on
  the emulator with `scripts/enviouswispr-emulator.sh` and `am instrument`; speech is staged through the existing UAT audio path
  or, where the emulator's microphone is not routed, the take ends on the silence stop and the probe's oracle is the session log
  line, never the transcript. Only two questions are left for the S26, and both are named as NOT RUN until they are: P1's
  Samsung layering, and P6's Play-installed build.
- **Recipe:** new, added to `device-testing.md` as FACT: the-lips-bubble-uat. Probes, each one command or one on-screen check:
  - P1 keyboard layering: focus Chrome's field with the keyboard up; `dumpsys window windows` lists our window above the IME
    window and the bubble is visible in the emulator screen. Stock Android on the emulator; Samsung is a separate phone check.
  - P2 drag across the keyboard: `input swipe` from the right edge across the keyboard to the left edge; the bubble follows, no
    key is typed into the field, no session starts (`logcat` shows no "Recording started").
  - P3 one gesture, one session: tap → exactly one "Recording started"; hold 1.5 s and release → exactly one started and one
    stop in order; an ordinary short tap (under the slop and the long-press timeout) → one started and the pill stays until the
    check; separately, a release 200 ms after HoldStart → one started, then a stop after RECORDING, never a cancel; tap twice
    quickly → one session.
  - P4 rotation: dock left at mid-height, rotate, rotate back; side and height unchanged.
  - P5 process death and reboot: `am force-stop` is not the check here (it clears the permission, #131); instead kill the
    process with `am kill`, then focus a field: the bubble appears at the remembered position; then reboot the emulator and
    repeat.
  - P6 Play build: enable accessibility on the internal-test install on the S26; no restricted-settings gate appears. Phone
    only; NOT RUN until then.
  - P7 field identity: hide the bubble on Chrome's upper text box, tap the same box again (stays hidden), tap the lower box (bubble
    returns); repeat in Messages. Logs the field key on each event, content-free.
  - P8 event settle: after a keyboard show and hide with the bubble docked, count accessibility events handled for ten seconds of
    no external activity; the count must be zero, so the overlay's own moves do not feed the events that move it.
- **Expected observation:** on the emulator, the words named in the Preface land in Messages and Chrome once each with the
  keyboard unmoved when the microphone is routed; when it is not, one session per gesture, ending in the silence stop, with the
  bubble back at idle. Oracle: the field's own content read through Appium or Wispr Eyes, never the clipboard, and the session
  log lines for the count.
- **Phone state to restore afterwards:** none on the emulator. If P1 or P6 runs on the S26, restore accessibility to its prior
  state and leave the bubble position, which is product state.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| gesture: move past slop before timeout is a drag | Product Outcome | a drag never records | delete the slop branch |
| gesture: hold past timeout starts, release stops | Product Outcome | hold-to-talk works | delete the timeout branch |
| gesture: pointer cancel during hold cancels | Product Outcome | a lost touch does not leave a take running | delete the cancel branch |
| ledger: STOP with the admitted token during STARTING stops after RECORDING, never cancels | Product Outcome | a short hold keeps the words | route it to `cancelStarting` |
| ledger: STOP for `seq` T above the mark is noted, and START T admitted afterwards stops after RECORDING | Product Outcome | a hold released before the launcher delivered its start still finishes | delete the note |
| ledger: CANCEL noted wins over a released note for the same `seq` | Product Outcome | a lost touch never inserts words | let released overwrite cancelled |
| ledger: STOP(A) noted, START(B) admitted, B finishes, delayed START(A) is refused stale | Product Outcome | hold-to-talk never keeps recording after the finger lifts | admit any `seq` above zero |
| ledger: START with `seq` at or below the mark, or another epoch, is refused stale | Product Outcome | a dead process's launcher cannot start a take | drop the epoch or the mark check |
| ledger: START above the mark while busy is refused and retires its `seq` | Product Outcome | a release for a refused start is ignored later | leave the mark unchanged on refusal |
| ledger: STOP or CANCEL at or below the mark and not admitted is ignored | Product Outcome | a late release cannot end the next take | delete the retired check |
| ledger: STOP(A) then STOP(B) before either START, both START orders: the displaced lower `seq` is retired, so START(A) is refused and START(B) stops after RECORDING | Product Outcome | no early release is ever silently dropped | replace the note without advancing the mark |
| ledger: STOP(B) then STOP(A) before either START: A retires itself, B's note is kept | Product Outcome | the same, from the other arrival order | keep the lower note |
| ledger: a STOP or CANCEL from another epoch creates no note and touches nothing | Product Outcome | a dead process cannot stop or cancel a new one's take | check the epoch on START only |
| ledger: unmarked STOP and CANCEL keep today's meaning | Drift Guard | the pill, tile, notification and side button are unchanged | require a token everywhere |
| gesture: an ordinary short tap is Tap, not a hold released early | Product Outcome | tap-to-start works | merge the two branches |
| gesture: release 200 ms after HoldStart is HoldRelease | Product Outcome | a quick hold still finishes | delete the HoldStart branch |
| gesture: configuration change cancels the classifier and timer and restores the dock | Product Outcome | rotation never starts a take or moves the bubble | delete the explicit cancel |
| visibility: a windows-changed event naming only our overlay is ignored | Product Outcome | the bubble cannot move itself in a loop | delete the own-window filter |
| placement: keyboard top clamps without changing the stored fraction | Product Outcome | the bubble returns to its spot when the keyboard closes | write the clamped value back |
| placement: snap picks the nearer side | Product Outcome | docking feels right | invert the comparison |
| visibility: own package never shows | Product Outcome | no bubble over our own app | delete the package check |
| visibility: hidden clears only on a different editor node, including a second editor in the same window | Product Outcome | hide means until the next field | compare window and package instead of node identity |
| every `SessionState.IDLE` write publishes IDLE, and the accessibility callbacks publish nothing | Drift Guard | the bubble cannot get stuck working, and a reconnect cannot fake IDLE | remove one write's publication, or restore a `hide()` in the service |
| manifest carries no `SYSTEM_ALERT_WINDOW` | Drift Guard | the decision holds | add the permission |
| event mask carries the windows-changed type | Drift Guard | keyboard tracking stays armed | remove it |

## 12. Blast radius & rollback

- Touched: `paste/` (overlay, service, three new files), `shortcuts/RecordingOverlayState.kt`, `ui/DictationSessionService.kt`
  (phase publication and the ledger resolve before dispatch), `shortcuts/BubbleRequests.kt` (new), `ui/VoiceInputActivity.kt`
  (two extras), one XML, one string.
- Not touched: audio, ASR, polish, insertion, Room, AIDL, manifest activity attributes, `AppPreferences`, the Compose shell.
- Rollback: revert the feature commits; the overlay returns to pill-only, the event mask drops one type, the disclosure string
  reverts with it in the same revert.

## 13. Ship criteria specific to THIS change

- [ ] In Messages and Chrome on the emulator, the lips appear when the field is focused, and a tap, a hold and a drag each do
      only what the mock shows.
- [ ] One session per gesture in the session log, and where the microphone is routed the named sentences landed in each field
      exactly once with the keyboard unmoved.
- [ ] A dragged position survived an app switch, a rotation, a process kill and an emulator reboot.
- [ ] Probes P1 to P8 recorded in `device-testing.md` with the target (emulator or S26), build and result; P6 and Samsung
      layering marked NOT RUN until the phone pass.
- [ ] The disclosure names the floating button and Codex's onboarding disclosure carries the same sentence.
- [ ] Catalog rows `pill-position`, `push-to-talk`, `toggle-recording`, `side-button-trigger` updated for Android.

## 14. Open questions

For the grounded review, not the founder:
1. Does `TYPE_WINDOWS_CHANGED` need `AccessibilityServiceInfo.flags` beyond `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` on API 30 to 36?
2. Is `startActivity` from an `AccessibilityService` context with `FLAG_ACTIVITY_NEW_TASK` subject to any Samsung One UI 8
   background-start restriction the documented exemption does not cover?
3. Should the PROCESSING working state live in the same window as the pill, or is a second `RecordingOverlayState.visible`
   consumer cleaner? The plan says same window.
4. Should the bubble stay hidden when the focused editable is a password field? Founder decision 2026-08-28 says no special case
   for sensitive fields; the plan follows it.

## 15. Related

#135 (this), #29, #34, #50 (recorder brand, shipped on `uat/integration`), #131 (force-stop clears accessibility), #8
(notification trigger). PAR-010, 012, 013, 072. Catalog: `pill-position`, `push-to-talk`, `toggle-recording`,
`side-button-trigger`. Mock: `docs/mockups/android-lips-bubble-v1/`. Adjudication:
`docs/mockups/android-lips-bubble-v1/window-mechanism-adjudication.md`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written (issue #135 comment)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it
