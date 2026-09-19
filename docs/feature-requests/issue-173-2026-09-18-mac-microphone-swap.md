# Issue #173 — A picked microphone that is gone swaps to Auto the way the Mac does — 2026-09-18

GitHub issue: `#173`. Tier: MEDIUM (a notice removed from the session service; the Settings row logic changes). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/**`).

**PAR rows closed:** `none`. PAR-021 (the picker) and PAR-022 (routing) are the contract; this change aligns the picker's DISPLAY and the recorder's words with the macOS behaviour those rows already imply. Nothing new is claimed closed.

**Hardware UAT:** Y. With Input Device set to the AirPods Pro 3, take the AirPods off and connect the Bose "Storm": Settings shows Auto selected with "Using Storm", the AirPods row is not listed, a take records through the Bose (History card "Microphone: Storm") and the recorder says nothing about the AirPods (the Bluetooth tip may show once per process). Put the AirPods back on: the AirPods row reappears selected, the next take records through them. The emulator cannot stage Bluetooth, so the phone is the only oracle.

## Preface — User Rubric

1. **Who.** Saurabh on the S26 Ultra, 2026-09-18, testing the Bose headset with the AirPods pick still saved. Meera Patel, who picked her AirPods once, then dictates in Messages with the phone alone the next morning because the AirPods are charging.
2. **Why.** "I told it which earbuds I like. When they're not here, just use what's here, and go back to them when they're back. Don't lecture me every time."
3. **How.** Nothing to invoke. She takes the earbuds off or puts them on; the app follows.
4. **Apps.** Any app she dictates into; the only screens involved are the recorder pill and the Microphone settings page.
5. **Natural input.** Unchanged dictation: "on my way", "can you send me the deck", "yes to Saturday", "running late sorry", "call me when you're free".
6. **Success.** She notices nothing. Or she opens Settings and sees Auto selected with "Using Storm" and thinks "right, it is using the Bose".
7. **Wrong-not-broken.** The recorder says "AirPods Pro 3 is not connected. Using Auto instead." on every take while the Bose records fine; she reads it as broken and stops trusting the picker (this is what happened on 2026-09-18).
8. **Power user hack.** Open Settings and tap Auto by hand before every take. The Mac does that for her; Android should too.
9. **Control ladder.** Auto (follow what is connected); pick a device (honoured exactly while it is connected, remembered while it is not); tap Auto or another device to forget it. No new setting.

### Cross-persona check

Priya, Diana, Aaron, Marcus: fewer words on the recorder, no objection. Dr. Vasquez: nothing leaves the phone; the device list is read in the app's own process, as today. Frank: one less sentence to read and a settings page that shows what is actually being used; he wants exactly this. Nobody wants the setting to reset itself (the Mac does not, and the founder's own scenario needs the memory), so the pick is kept.

---

## 0. TL;DR

Consolidation: none. This deletes one recorder sentence and replaces one Compose row rule with a pure, tested one; the single owner of "which microphone" stays `InputDeviceResolver.resolve`, and the Settings page reads it instead of keeping its own copy of the absent-pick rule.

Today an explicit Input Device pick that is not connected already records through Auto for that take (`InputDeviceResolver.resolve`, reason `PICK_MISSING`), so the microphone is right. Two surfaces say otherwise: the recorder shows "AirPods Pro 3 is not connected. Using Auto instead." on every take, and Settings keeps the absent AirPods row selected with "Not connected. Dictation uses Auto until it is." The Mac (`InputDevicePreferencePolicy.swift`, founder decisions 2026-07-30 and 2026-09-18) shows Auto selected with a "Using Storm" pill, hides the absent device, keeps the pick so the device reclaims the selection when it reconnects, and has no recorder sentence for an absent pick. This change makes Android do the same: a pure `InputDeviceRows.build` (proposed) decides the rows from `resolve`; the recorder's pick-missing line and its latch are removed. Capture, the History card and the bubble colour are untouched; the Bluetooth tip's wording and gate are untouched, and it now also reaches an absent-pick Bluetooth take (§2.1). MEDIUM. Proof: unit rows on the row rule, the notice test updated, and the founder's phone pass with the AirPods and the Bose.

## 1. Problem

Founder, S26 Ultra, Play build 141, 2026-09-18, Input Device set to AirPods Pro 3, AirPods off, Bose "Storm" connected: "it's saying airpod 3s not connected, please use auto. checked settings, It's still set to Saurabh's airpod pro 3. Looks like you failed to set up the proper switching." The take did switch (`resolve` line 55 to 64: an absent pick resolves to Auto, and Auto takes the connected Bluetooth microphone), so the defect is in what the app SAYS and SHOWS: `CaptureNotices.pickMissingLine` (line 42) said once per take by `DictationSessionService.publishMicrophoneNoticesIfNeeded` (line 797 to 811), and the `pickedButAbsent` row in `ui/SettingsPages.kt` (line 404 to 421).

## 2. Goals & non-goals

### 2.1 Goals
- Settings, Input Device: when the stored pick is not connected, the Auto row shows selected with the subtitle "Using <label>" naming the microphone Auto would open, the absent device is not listed, and the stored pick is unchanged.
- When the picked device reconnects, its row is listed and selected again with no action from the user; the next take records through it (already true of `resolve`).
- Tapping Auto, or any listed device, replaces the stored pick (the Mac's "forget").
- The recorder has no missing-pick sentence. The History card still names what recorded.
- The bubble colour (#171) keeps its behaviour: it already reads `resolve`.
- The Bluetooth tip ("Recording through your earbuds", once per process, tips on) now also reaches a take whose absent pick resolved to another Bluetooth microphone; today the pick-missing branch returned before the tip was considered. That is the tip doing its job on a Bluetooth take, and it is a stated change, not a preserved one.

### 2.2 Non-goals
- No second stored key (the Mac's `selectedInputDeviceUID` (external)). See §3.
- No change to capture, the live gate, the warm hold, the AIDL surface, or `InputRouteReason` (the `PICK_MISSING` code stays for the `:audio` log and the resolver tests).
- No change to the "When using Bluetooth" group, the tip's wording or gate, or the hold toggle.
- The "few taps" on the Bose cold start is a separate finding on #173, classified from the phone log, not this plan.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

- **Stored pick:** `AppPreferences` DataStore field `inputDevicePick` (a string, `InputDevicePick.serialize`). Written by `MicrophonePage` through `onInputDevicePickChanged` (`ui/SettingsPages.kt` line 399 to 419). Read by `DictationSessionService` (line 368, frozen per take at line 553 into `startCaptureWithInputDeviceHeld`), by `PasteAccessibilityService` for the bubble colour (line 245, `applyEarbuds`), and by `MicrophonePage` (line 375). Found with `grep -rn "inputDevicePick" app/src/main`.
- **Device list:** `AudioManager.getDevices(GET_DEVICES_INPUTS)` (external) via `InputDeviceCandidate.from`; the page reads it in `rememberConnectedInputs` (line 473 to 494, `pickable` filter, `AudioDeviceCallback` on add/remove); the capture service reads its own list at take start (`AudioCaptureService.resolveRoute`, line 599 to 607); the accessibility service reads it for the colour.
- **Decision:** `InputDeviceResolver.resolve(pick, inputs)` (line 55): explicit pick present → `PICKED`; explicit pick absent → Auto order with reason `PICK_MISSING`; Auto → `AUTO`. `earbudsAreTheMicrophone` (line 86) is a projection of it.
- **Reason consumer in the app process:** `IAudioCaptureService.getInputRouteReason` (AIDL line 56 to 57) → `DictationSessionService.publishMicrophoneNoticesIfNeeded` (line 800 to 805) → `CaptureNotices.pickIsMissing` / `pickMissingLine` → `sayWhileRecording`. That is the ONLY reader of the reason code in the app process (`grep -rn "inputRouteReason\|InputRouteReason" app/src/main` lists the AIDL, the enum, `EffectiveDevice`, `AudioCaptureService` and this one site).
- **Settings rows:** `MicrophonePage` builds the rows inline (line 393 to 423): Auto, then `inputs.map { it.pick to it.label }`, then the `pickedButAbsent` row. `InputDeviceRow` (line 497) is a radio row with an optional subtitle.
- **History card:** `HistoryScreen.kt` line 276 to 278 shows "Microphone: <captureDevice>" from the row written at stop. Unchanged.

### 2. Existing authority

Capability search "which device will Auto open" → `InputDeviceResolver.resolve` (one owner; callers `AudioCaptureService.resolveRoute`, `PasteAccessibilityService.applyEarbuds` via `earbudsAreTheMicrophone`, the tests). "which row is selected" → inline in `MicrophonePage`, no separate authority; this plan extracts it as `InputDeviceRows` (proposed), reading `resolve`, so the page and the take apply one rule, each over its own snapshot (§2.5.4). "the words when the pick is absent" → `CaptureNotices.pickMissingLine` (removed) and the `pickedButAbsent` subtitle (removed). No other file mentions either sentence (`grep -rn "Dictation uses Auto\|Using Auto instead" app docs .claude scripts` → the two sources and `CaptureNoticesTest` only).

### 3. Prior attempts and live direction

- #26 plan `issue-26-2026-09-16-microphone-picker-and-bluetooth.md` §7 chose "one recorder line" for an absent pick and shipped it in #165 (2026-09-18). That row is superseded by this plan on the founder's 2026-09-18 instruction and the Mac's behaviour.
- macOS: `InputDevicePreferencePolicy.reconciled` (two keys: the active override and a remembered UID) run by `InputDevicePreferenceReconciler` (external) on every device-list change; `AudioSettingsView` (external) shows a "Using X" pill when the override is empty, lists connected devices only, and writes both keys on a manual pick. `InputDeviceResolver.swift` rung 3: a pinned device that cannot be found swaps to Auto silently (founder 2026-07-30); a reconnected pinned device reclaims the next take. Catalog `microphone-selection` macOS: "an explicit device UID is pinned per take, with a physical-microphone fallback"; user copy has no absent-pick sentence. Founder 2026-09-18, describing the Mac: "disconnected storm -> system swaps back to auto with built in microphone ... connecting storm hardswaps to Storm hard-selected."
- #171 (blue lips) reads `resolve` for the colour and documents the absent-pick cell as "matches what the take does"; unchanged by this plan.
- Catalog Android rows for `microphone-selection` and `bluetooth-routing` still read `partial` from before #165; corrected in this project's catalog file at wind-down.

### 4. Boundaries a naive design would miss

- **Two processes read the pick:** the page and the accessibility service (default process) and the capture service (`:audio`). All three call the same `resolve`; the page's new row rule is a projection of it, never a second decision. Nothing crosses a new boundary.
- **The Settings page is not always open:** the Mac reconciles on every device change because it writes the stored keys; Android writes nothing on a device change, so no listener is needed anywhere new. The page recomputes its rows from `rememberConnectedInputs`, which already re-reads on add/remove while the page is open.
- **Tapping an already-selected Auto row:** Compose `selectable` fires `onClick` whether or not `selected` is true, so tapping Auto while it shows selected for an absent pick writes `InputDevicePick.Auto` and forgets the remembered device. Same for tapping a listed device.
- **A take in flight when the earbuds reconnect:** the take keeps its device (freeze per take, #165); the row flips at once; the next take uses the pick. Unchanged.
- **Session service latch:** `pickMissingNoticeShown` is reset in `tryStartRecording` (line 549) and read in the notice guard (line 799). Both sites go; `CaptureNoticesTest` pins the new guard text.
- **AIDL:** `getInputRouteReason` keeps its slot (append-only); after this change nothing in the app process calls it. Named as deliberate, not deleted.
- **Screen reader:** the Auto row's subtitle is read as part of the row; "Using Storm" is the same fact the Mac pill carries. When the selected device row vanishes (disconnect while the page is open), Compose moves accessibility focus as it does today for any listed device that disconnects; this plan removes one row kind (the absent pick) and adds none, so row removal is not new behaviour.
- **Leaving and reopening Settings:** the list and its callback belong to the composition (`rememberConnectedInputs`, `DisposableEffect`); a page opened after a reconnect reads the list fresh in `read()` at composition, so the rows are right on every open with no stored state.
- **Reconnect between the take's start call and the live gate:** the pick is frozen per take at `startCaptureWithInputDeviceHeld` (line 553) and the capture service resolves it once at its own start (`resolveRoute`); a device that connects during the wait is used by the NEXT take (#165 freeze-per-take). Unchanged by this plan.
- **Page snapshot versus take snapshot:** the page runs the same rule over the list it read; the take runs it over the list `:audio` reads when it starts. The two can differ across a disconnect in between, and the page never claims otherwise: the History card is the record of what recorded.
- **`getDevices` throwing on the page:** `read()` in `rememberConnectedInputs` guards a null `AudioManager` but not an exception from `getDevices`; this plan wraps the read in `runCatching` (as `PasteAccessibilityService.readInputsAndApply` already does) so a failure shows the empty-list rows rather than crashing the settings screen.
- **Older or malformed saved picks:** parsed by `InputDevicePick.parse` before the row rule sees them (garbage reads as Auto); `InputDeviceRows.build` takes the parsed value and adds no second parser.

### 5. High-risk premises

- "An absent pick already records through Auto." Evidence: `resolve` line 57 to 63; `InputDeviceResolverTest` rows asserting `PICK_MISSING` with an Auto target. Verified by reading and by the existing rows.
- "The reason code has no other consumer in the app process." Evidence: the grep in §2.5.1, pasted from the working tree on 2026-09-18.
- "Compose `selectable` fires on an already-selected row." Platform behaviour (`Modifier.selectable` documents `onClick` as unconditional). Also observed in this app: the Auto row today can be tapped while selected without error. Not otherwise verified; the phone pass includes the tap.
- "The Mac hides the absent device and shows the pill on Auto." Evidence: `AudioSettingsView.swift` line 85 to 98 (picker over `availableInputDevices` (external); pill when `preferredInputDeviceIDOverride.isEmpty`).

## 3. Design

- `ui/InputDeviceRows.kt` (proposed): `object InputDeviceRows` with `data class Row(val pick: InputDevicePick, val title: String, val subtitle: String?, val selected: Boolean)` and `fun build(pick: InputDevicePick, inputs: List<InputDeviceCandidate>): List<Row>`. Rule: `val resolution = InputDeviceResolver.resolve(pick, inputs)`; Auto is selected iff `resolution.reason != InputRouteReason.PICKED`; the Auto subtitle is `"Using ${target.label}"` when Auto is selected and a target exists, `"No microphone found"` when Auto is selected and there is none, and the existing explainer "Earbuds when they are connected, otherwise the phone" when a device is selected; one row per `InputDeviceResolver.pickable(inputs)` entry, selected iff `pick == it.pick`; no row for an absent pick.
- `MicrophonePage`: replaces the inline rows with `InputDeviceRows.build(pick, inputs).forEach { ... }`; `onSelect` writes `row.pick` as today.
- `DictationSessionService`: delete the `pickMissingNoticeShown` field, its reset, and the pick-missing branch; `publishMicrophoneNoticesIfNeeded` no longer reads `inputRouteReason`, only `inputRouteKind` for the tip. The KDoc describes one line (the tip) after the forced and silence notices.
- `CaptureNotices`: delete `pickMissingLine` and `pickIsMissing`.

Rejected: the Mac's two-key model with a reconciler on device changes (Android has no always-running app process to host it; the resolver already carries the fallback per take, so one stored pick plus a display rule gives every outcome the founder listed). Rejected: rewriting the stored pick to Auto on disconnect (the reconnect-reclaims-it behaviour needs the memory; the Mac keeps it).

## 3b. Ownership justification

The row rule lives in `ui/` beside `CaptureNotices` because it is presentation over `InputDeviceResolver`, and it reads the resolver rather than copying it so the page and the take apply one rule (over their own snapshots, §2.5.4); the alternative was a method on `InputDeviceResolver`, but that object is the `:audio` decision and should not learn row titles.

## 4. Contract deltas

- `InputDeviceRows.build`: given the stored pick and the connected microphones, the rows the page shows, with exactly one selected. Consumers: `MicrophonePage` only.
- `CaptureNotices.pickMissingLine` / `pickIsMissing` (removed): the recorder no longer has a sentence for an absent pick.
- `DictationSessionService.publishMicrophoneNoticesIfNeeded`: says at most the Bluetooth tip; the silence and forced notices still outrank it. A take whose absent pick resolved to a Bluetooth microphone is now an ordinary Bluetooth take for the tip: it can show and spend the once-per-process tip (tips on), exactly as an Auto Bluetooth take does (`BluetoothTipGate.shouldShow` rows in `CaptureNoticesTest`: once per process, never with tips off, never on a phone or wired take, the allowance kept when tips were off).
- `IAudioCaptureService.getInputRouteReason`: unchanged signature; no app-process caller after this change.

## 5. State and lifecycle audit

- Producers of the stored pick: one (`MicrophonePage` via `onInputDevicePickChanged`); enumerated with `grep -rn "onInputDevicePickChanged\|setInputDevicePick" app/src/main`. Unchanged.
- Readers of the stored pick: three (session service, accessibility service, page); all through `InputDevicePick.parse`; none changes.
- Notice latches in the session service: `silenceNoticeShown`, `durationWarningShown`, `pickMissingNoticeShown` (removed), `forcedNoticeShown`; each reset at `tryStartRecording`; enumerated at line 546 to 550.
- Device-list readers: three (`rememberConnectedInputs`, `resolveRoute`, `readInputsAndApply`); none changes.

## 6. Consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| rows from `InputDeviceRows.build` | `MicrophonePage` | inline rule with an absent row | Auto selected + "Using X" when the pick is absent; no absent row | yes | `InputDeviceRowsTest` (proposed) rows |
| pick-missing line removed | `publishMicrophoneNoticesIfNeeded` | says the line once per take | no missing-pick sentence | yes | `CaptureNoticesTest` guard text |
| `getInputRouteReason` | none in the app process | read for the line | unread; kept | no | `SilenceStopWiringTest` still lists the AIDL method |
| bubble colour (#171) | `applyEarbuds` | `resolve` projection | unchanged | no | existing `InputDeviceResolverTest` rows |
| History card | `HistoryScreen` | names what recorded | unchanged | no | none needed |

## 7. Failure modes

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| Pick absent, another microphone connected | user | page, capture | Settings: Auto selected, "Using Storm"; take records through Storm; no missing-pick sentence (the Bluetooth tip may show once per process); card "Microphone: Storm" | pick unchanged | n/a |
| Pick absent, phone only | user | page, capture | Auto selected, "Using Phone"; take on the phone; card "Microphone: Phone" | pick unchanged | n/a |
| Pick reconnects | user | page, capture | the device row reappears selected; next take through it | pick unchanged | n/a |
| No microphone at all | phone | page, capture | Auto selected, "No microphone found"; take fails with the macOS sentence (unchanged, `CaptureNotices.NO_MICROPHONE`) | none | next press |
| Device list read fails on the page (`getDevices` throws or returns null) | AudioManager | page | Auto selected, "No microphone found" (empty list, `runCatching`); the take reads its own list | none | next add/remove |
| User taps Auto while it already shows selected | user | page | the remembered pick is forgotten; nothing visible changes | pick = Auto | n/a |
| User taps another listed device while the remembered pick is absent | user | page | that row is selected; when the old device reconnects it is listed but NOT selected (the memory was replaced) | pick = the new device | n/a |
| Absent pick resolves to another Bluetooth microphone, tips on, first Bluetooth take this process | user | session service | the tip "Recording through your earbuds" once; the take proceeds | tip spent for the process | n/a |
| Wired headset plugged in and earbuds connected, Auto | user | page | "Using <wired headset>" (Auto order: wired first); both rows listed | none | n/a |
| A hearing aid and the built-in microphone, Auto | user | page | "Using Phone" (excluded from Auto); the hearing aid row is listed and pickable | none | n/a |
| Same product name on two Bluetooth transports | phone | page | two rows with one label, as today (`pickable` dedupes on type and name); the picked TYPE's row is the selected one | none | n/a |
| Blank product name | phone | page | the kind word ("Bluetooth microphone"), as today (`InputDeviceLabels.labelFor`) | none | n/a |

## 8. Caller-visible signals

- `Row.selected`: exactly one true per list; the page draws the radio from it. Absent pick → the Auto row.
- `Row.subtitle` on Auto: "Using X" carries the meaning "this is what Auto opens now"; its absence (the explainer) means a device is picked and connected.
- `InputRouteReason.PICK_MISSING` in the `:audio` log: still written; now carries no user-visible meaning.

## 9. Fallback source of truth

| Failure branch (§7) | Candidate expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| no target for Auto | "No microphone found" | `CaptureNotices.NO_MICROPHONE` wording (macOS `Recording failure` copy) | the same fact the take reports | `resolution.target == null` | n/a | Auto row subtitle |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/ui/InputDeviceRows.kt` (new): the row rule above.
- `app/src/main/java/com/envi/wispr/ui/SettingsPages.kt`: `MicrophonePage` Input Device group uses `InputDeviceRows.build`; the `pickedButAbsent` block and its comment go; the group comment states the Mac rule (Auto shows what it opens; an absent pick is remembered, not shown); `rememberConnectedInputs.read()` wraps `getDevices` in `runCatching`.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: remove `pickMissingNoticeShown` (field, reset, guard) and the pick-missing branch; KDoc updated.
- `app/src/main/java/com/envi/wispr/ui/CaptureNotices.kt`: remove `pickMissingLine` and `pickIsMissing`.
- `app/src/test/java/com/envi/wispr/ui/InputDeviceRowsTest.kt` (new): rows in §11.2.
- `app/src/test/java/com/envi/wispr/ui/CaptureNoticesTest.kt`: guard text updated; `onlyThePickMissingReasonArmsThePickMissingLine` deleted (its subject is deleted; what it protected, the reason-to-line mapping, no longer exists); the forced-notice comment no longer names the pick-missing line.

## 11. Testing

1. **Class.** `InputDeviceRowsTest` rows: product outcome (when they fail, the user sees the wrong row selected, an absent device listed, or the wrong "Using X"). `CaptureNoticesTest` guard: drift guard, named as such.
2. **Revert that turns each red.** Rows: restore the `pickedButAbsent` behaviour inside `build` (select the absent pick) → the absent-pick row fails; drop the `"Using "` prefix → the subtitle row fails; select Auto on `reason == AUTO` only → the absent-pick row fails. Guard: put `pickMissingNoticeShown` back in the guard line.
3. **Not tested.** Compose rendering of the rows (no Compose test rig in the unit suite; the phone pass covers it). Bluetooth on the emulator (impossible).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (Settings display and a recorder sentence; capture unchanged).
- **Recipe:** new, in `device-testing.md`: Input Device set to AirPods Pro 3 → AirPods off, Bose on → Settings: Auto selected, "Using Storm", no AirPods row → dictate into Messages: no AirPods line on the recorder (the one Bluetooth tip may appear on the first Bluetooth take of the process), History card "Microphone: Storm" → AirPods on: the AirPods row is listed and selected; Auto shows the explainer → dictate: card "Microphone: AirPods Pro 3" → AirPods off: Auto selected, "Using Storm"; tap Storm: Storm selected → AirPods on: AirPods listed, NOT selected (the memory was replaced) → Bose off, AirPods off: Auto selected, "Using Phone" → leave Settings, AirPods on, reopen Settings: rows are right on open (AirPods listed, not selected; Storm is the memory) → AirPods off → tap the already-selected Auto row → Bose on: Auto stays selected, "Using Storm" (the memory is gone).
- **Expected observation:** the rows and subtitles by eye, the History card's microphone line as the independent check of what recorded.
- **Phone state to restore afterwards:** Input Device back to the founder's choice.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `InputDeviceRowsTest`: Auto with phone only → Auto selected, "Using Phone" | product outcome | the pill names what Auto opens | prefix dropped |
| Auto with earbuds → "Using <earbuds>" | product outcome | Auto follows the resolver order | order changed in the row rule |
| picked device present → that row selected, Auto shows the explainer | product outcome | an explicit pick is honoured on screen | selection rule inverted |
| picked device absent, another earbud connected → Auto selected, "Using <other>", no absent row, rows count == pickable count + 1 (Auto) | product outcome | the Mac swap | absent row restored |
| picked device absent, nothing connected → Auto selected, "No microphone found" | product outcome | the empty case is honest | fallback wording missing |
| Auto with a wired headset and earbuds → "Using <wired>" | product outcome | the subtitle follows the Auto order, not "earbuds first" | subtitle picks Bluetooth |
| Auto with a hearing aid plus the built-in microphone → "Using Phone", the hearing aid row listed | product outcome | excluded-from-Auto devices are offered, never chosen | hearing aid chosen |
| same name on LE and SCO, SCO picked → two rows, the SCO row selected | product outcome | selection is by type and name | selection by name only |
| blank name → the kind word as title | product outcome | no empty row title | label not applied |
| exactly one row selected for every case above | product outcome | the radio group is consistent | two selected |
| `CaptureNoticesTest.theRecorderSaysAtMostOneMicrophoneLinePerTakeAndACaptureWarningOutranksIt` (updated) | drift guard | the guard reads `silenceNoticeShown || forcedNoticeShown` and no pick-missing branch precedes the tip | latch restored |

## 12. Blast radius & rollback

- Touched: `ui/` (four files, two new). Not touched: `audio/`, `paste/`, AIDL, preferences schema, the History card, the tip, the hold, the live gate.
- Rollback: revert the PR; no stored data changes.

## 13. Ship criteria

- [ ] With the AirPods picked and off, and the Bose on, Settings shows Auto selected with "Using Storm", no AirPods row, and a take records through the Bose with nothing said about the AirPods.
- [ ] Putting the AirPods back on selects them again without touching Settings.
- [ ] Confirmed on the founder's S26 Ultra with the AirPods Pro 3 and the Bose "Storm".

## 14. Open questions

None blocking. The Bose "few taps" finding is read from the phone log when the phone is plugged in and classified on #173 or a new issue.

## 15. Related

#173, #26, #165, #171, PAR-021, PAR-022, catalog `microphone-selection`, macOS `InputDevicePreferencePolicy.swift`, `InputDeviceResolver.swift`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it
