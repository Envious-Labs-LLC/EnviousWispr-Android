# Issue #171 — Blue rainbow lips when the earbuds are the microphone — 2026-09-18

GitHub issue: `#171`. Tier: MEDIUM (a new device listener in the accessibility service, the surface seen most). Status: SHIPPED (founder phone pass on Play build 140, 2026-09-18: "yes works great"; PR #172).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code (`app/**`, plus the mock under `docs/mockups/`).

**PAR rows closed:** `none`. PAR-022 owns the routing this colour reports; the colour itself is a founder request, not a parity row.

**Hardware UAT:** Y. With AirPods connected and Input device on Auto, the idle lips over Messages are the blue rainbow; a take started from them shows a blue level rail on the pill (the pill carries no lips, `buildPill`; its hold-side `RecordMarkView` stays text-coloured); taking the AirPods off (or picking Phone in Settings) turns the bubble back to the brand rainbow within a second, without a restart. The emulator cannot stage Bluetooth, so the phone is the only oracle.

## Preface — User Rubric

1. **Who.** Meera Patel, walking with AirPods in, replying in Messages one-handed. Thirty seconds ago she put the earbuds in; thirty seconds from now she wants the reply sent. Also Saurabh on the S26 Ultra with AirPods Pro 3, whose 2026-09-18 phone pass is where the request came from.
2. **Why.** "I want to know it is using my earbuds before I start talking, not find out after."
3. **How.** Nothing to invoke. The bubble is blue when the earbuds are the microphone; she glances, then taps.
4. **Apps.** Messages, WhatsApp, Gboard-hosted fields anywhere. The bubble looks the same over all of them.
5. **Natural input.** "on my way, ten minutes", "can you grab milk", "running late sorry", "yes to Saturday", "call me when you're free". None of these change; the colour is the only difference.
6. **Success.** She notices the bubble is blue, thinks "ah, the earbuds", and dictates. Or she notices nothing and moves on, which is also success.
7. **Wrong-not-broken.** The bubble is blue while the phone microphone records, or vice versa; she stops trusting the colour and ignores it.
8. **Power user hack.** Open the History card after a take to read which microphone was used. That card exists (#165) and is the fallback if the colour is ever doubted.
9. **Control ladder.** Off (no colour change) through automatic. The picker already gives full control of the microphone; the colour only reports the picker's answer, so it has no switch of its own. If a user wants the colour gone they pick Phone, which is the state where the colour is gone.

### Cross-persona check

Priya, Diana, Aaron and Marcus: a free, silent signal; no objection. Dr. Vasquez: no data leaves the phone; a device listing is read in the app's own process. Frank: one more thing the bubble does, but nothing to set and nothing to learn; brand rainbow means not the earbuds, blue means earbuds. No persona wants a setting for it, so none is added.

---

## 0. TL;DR

Consolidation: none. This adds one palette and one boolean input to views that already exist; the one owner of "which microphone" stays `InputDeviceResolver.resolve`, and no second copy of that decision is created (the new predicate is a projection of it).

The floating lips (idle bubble) and the recording pill's level rail switch to a cool, blue-centred nine-stop rainbow whenever the earbuds are the chosen microphone, and back to the brand rainbow otherwise. Brand rainbow means "not the earbuds": the phone, a wired or USB headset, or unknown. The bubble's spoken label says "using your earbuds" in the same state, so a screen reader gets the same fact. "Chosen microphone" is what `InputDeviceResolver.resolve` returns for the current Input device pick and the phone's current inputs; since #165 the app never falls back to the phone on its own while earbuds are connected (an explicit Phone pick is honoured, `resolve` line 57), so the microphone chosen when a take starts is the one it records from; the colour itself is a live report of the current choice, not of any take's route. The accessibility service, which already collects the bubble look from `AppPreferences`, gains an `AudioDeviceCallback` (external) and combines the two into one boolean it hands the overlay. MEDIUM. Proof: unit rows on the pure predicate, source guards on the wiring, and the founder's phone pass with AirPods.

## 1. Problem

The lips look the same whether the take records from the earbuds or from the phone. The only place the microphone is named is the History card after the take, and the one-line notice "Recording through your earbuds" under the pill once it is live. Founder, 2026-09-18, after the #165 phone pass: "Can we make it so the lips are a blue centric rainbow? Maybe show me a mock when it's Bluetooth enabled? I think that would be a good distinction it's connected to Bluetooth." Mock built at `docs/mockups/android-lips-bluetooth-v1/index.html`; option A chosen.

## 2. Goals & non-goals

### 2.1 Goals
- Idle bubble lips are the earbud rainbow when the earbuds are the chosen microphone, the brand rainbow otherwise.
- The pill's level rail (tap pill and hold pill alike; both are built by `buildPill` and `layOutPill`) follows the same choice. The bubble's roll while starting uses the same palette. The bubble's content description names the earbuds in the blue state.
- The colour reports the resolver's current choice at every moment, mid-take included, independently of the route the take is on; the History card reports what recorded.
- The colour changes when the earbuds connect or disconnect and when the pick changes, without a take and without a restart.
- Zero idle cost beyond a push callback: no poll, no timer.

### 2.2 Non-goals
- No outline or glow on the bubble (the looks dropped the violet ring on 2026-09-14; the mock's blue ring is decorative).
- The onboarding lips, the launcher icon, the Settings pages and the website keep the brand rainbow.
- No new setting.
- Not a route report: the colour says which microphone is CHOSEN now, never which one a finished take recorded; that stays on the History card (#165 decision, a moved route is recorded, not fought).
- Not a wired or USB indicator: those draw the brand rainbow like the phone.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

- **Colour producer today:** `BrandPalette.RAINBOW` (`paste/BrandPalette.kt`). Consumers: `BrandMarkView.onDraw` (both lips, `RAINBOW[(index + colourShift) % BAR_COUNT]`), `RecordingLevelMeterView.onLayout` (the rail's `LinearGradient`), and `ui/OnboardingDemo.kt` (the onboarding practice rail, Compose, stays brand). Found with `grep -rn "BrandPalette.RAINBOW" app/src/main/java`.
- **Views' owner:** `RecordingAccessibilityOverlay` holds `bubbleMark = BrandMarkView(service)` and `meter` (`RecordingLevelMeterView`), created once per `PasteAccessibilityService` instance in `onServiceConnected` (`recordingOverlay == null` guard) and stopped in `onDestroy`.
- **Preference producer:** `AppPreferences.state` (DataStore flow) carries `bubbleLook` and `inputDevicePick` (a string, parsed by `InputDevicePick.parse`). `PasteAccessibilityService.onServiceConnected` collects it on `lookScope` and posts `recordingOverlay?.setLook(preferences.bubbleLook)` to the main thread; `onDestroy` cancels `lookScope` first.
- **Device producer:** `AudioManager.getDevices(GET_DEVICES_INPUTS)` (external) mapped through `InputDeviceCandidate.from`; changes pushed by `AudioDeviceCallback` (external). Already used this way by `ui/SettingsPages.kt` (`rememberConnectedInputs`: read on add/remove, unregister on dispose).
- **Decision:** `InputDeviceResolver.resolve(pick, inputs)` returns `Resolution(target, reason)`; `needsBluetoothRoute(target)` is true exactly for `InputRouteKind.BLUETOOTH`. `AudioCaptureService.resolveRoute` uses the same call to choose the take's microphone, and since #165 there is no "resolve without Bluetooth" branch: `allowBluetooth` is only false after `LINK_REFUSED`, which keeps the earbud source and fails or forces the take, never silently records from the phone.
- **Process:** the overlay and the accessibility service are in the DEFAULT process (`PasteServiceProcessManifestTest` pins that); the capture service is `:audio`. The colour is computed in the default process from the same inputs the capture service reads in `:audio`; no binder call is added.

### 2. Existing authority

Capability search: "which microphone will be used" → `InputDeviceResolver.resolve` (one owner, callers: `AudioCaptureService.resolveRoute`, `InputDeviceResolverTest`, `InputDeviceRouteTest`). "current inputs with change push" → `ui/SettingsPages.kt` `rememberConnectedInputs` (Compose-scoped; not reusable from a service, so the service gets its own registration of the same `AudioDeviceCallback`, not a new abstraction). "overlay colour" → none; `new authority proposed`: a `palette` property on `BrandMarkView` and `RecordingLevelMeterView`, and `RecordingAccessibilityOverlay.setEarbuds`.

`grep -rn "palette\|RAINBOW_" app/src/main/java` → only `BrandPalette.RAINBOW` and the onboarding demo; no second palette exists.

### 3. Prior attempts and live direction

- #165 (2026-09-18, merged): the picker, Auto order (wired, then LE Audio, then SCO, then built-in), the live gate, the 30 s hold. Founder rule recorded in `bluetooth-capture-android.md`: never fall back to the phone unless the earbuds disconnect. Session log 2026-09-18.
- Catalog: `bluetooth-routing` macOS `shipped`, Android `partial` (row predates #165); `bluetooth-guide` Android `absent`. No macOS row colours the recorder by route; macOS shows a Bluetooth card and a Settings guide (PAR-028). Nothing settled forbids a colour; nothing on macOS to copy.
- Bubble look history: the violet ring was dropped 2026-09-14 (`RecordingAccessibilityOverlay.buildBubble` comment); three looks exist (`BubbleLook`). The earbud palette must work on all three, including BARE on a white page (the ink edge stays).

### 4. Boundaries a naive design would miss

- **Accessibility service alive vs enabled-but-crashed:** the overlay only exists on a live instance; the callback is registered with the overlay and unregistered in `onDestroy` before `recordingOverlay?.stop()`. A crashed service shows no bubble, so no stale colour.
- **onServiceConnected fires again on the same instance** (measured 2026-09-12): the registration sits inside the existing `recordingOverlay == null` guard, so one callback per instance.
- **Callback thread:** `registerAudioDeviceCallback(callback, handler)` with `mainHandler` delivers on the main thread; the preference collect runs on IO and already posts to main. Both writes to the overlay land on main.
- **First value:** the callback does not fire for already-connected devices; `onAudioDevicesAdded` is called once on registration with the current list (Android documents this for `registerAudioDeviceCallback`; verified on the emulator during build, and stated as a premise to re-check on the phone). The service also reads the list once at registration so the first paint is right whether or not that initial call arrives.
- **Warm hold:** after a take with Keep earbuds ready, the earbuds stay connected for 30 s and the pick is unchanged, so the bubble stays blue; when the hold ends nothing changes (earbuds still connected). Consistent with the founder rule.
- **Take in flight:** the palette is applied to the views directly; a change mid-take recolours the rail at once, which is the stated contract (§2.1). `AudioCaptureService.resolveRoute` resolved that take once at its start; the colour is not a claim about that resolution.
- **Overlay hidden:** `remove()` detaches the window but keeps the views; a palette set while detached is held by the view and drawn on the next show. The rail rebuilds its shader in the setter (`rebuildShader`, proposed), not only in `onLayout`, so an unchanged-size rail changes colour rather than losing its gradient.
- **First paint before the first preference:** the overlay is created before the preference collect emits. The pick is held nullable and the colour is computed only once the first emission has arrived; until then the views keep the brand palette. The device list is read once at registration so the first computation has both inputs.
- **Screen reader:** `BrandMarkView` and the rail are `IMPORTANT_FOR_ACCESSIBILITY_NO`; the bubble's `contentDescription` is the spoken surface, so `setEarbuds` rewrites it ("EnviousWispr, using your earbuds. Double tap to dictate. Touch and hold to talk. Drag to move.").
- **Process death of `:audio`:** irrelevant to the colour; the colour is computed in the default process.

### 5. High-risk premises

- "The chosen microphone is the recorded microphone." Scope: at the moment the take starts. Evidence: `AudioCaptureService.resolveRoute` calls `InputDeviceResolver.resolve` with the same pick and `getDevices(GET_DEVICES_INPUTS)`; the `LINK_REFUSED` branch keeps the earbud source (#165 round 5 comment). Not verified: a race where the earbuds disconnect between the overlay's last callback and the take's resolve; then the take records from the phone and the History card says so, and the callback recolours the bubble within the same second. Accepted and named.
- "`registerAudioDeviceCallback` delivers an initial `onAudioDevicesAdded`." Verified on the emulator in the build chunk; the plan reads the list once at registration regardless.
- "No idle cost." A registered callback is a push; the settings page already carries one while open. `architecture-rules.md` RULE: no-idle-cost lists timers and polls, not push callbacks.

## 3. Design

- `BrandPalette.RAINBOW_EARBUDS`: nine stops, option A of the mock: `#3DFFB0, #00F5D4, #00E5FF, #00B4FF, #1E90FF, #2E6BFF, #4169E1, #5B4BEA, #8A2BE2`. Same length as `BAR_COUNT`, violet on the last bar as the brand drawing has.
- `BrandMarkView.palette` and `RecordingLevelMeterView.palette`: an `IntArray` property defaulting to `BrandPalette.RAINBOW`; setting a different array invalidates (the meter's setter calls `rebuildShader` at once, so no layout is needed). `BrandMarkView.onDraw` reads `palette` where it read `BrandPalette.RAINBOW`; the roll keeps working because the index arithmetic is unchanged.
- `RecordingAccessibilityOverlay.setEarbuds(earbuds: Boolean)`: sets both views' palette and the bubble's content description; idempotent; main thread.
- `InputDeviceResolver.earbudsAreTheMicrophone(pick, inputs)`: `resolve(pick, inputs).target?.let(::needsBluetoothRoute) ?: false`. Pure, tested without a phone.
- `PasteAccessibilityService`: alongside the look collect, one `AudioDeviceCallback` registered on `mainHandler` inside the `recordingOverlay == null` block; it re-reads `getDevices` on add/remove and stores `inputs` (read once at registration too); the preference collect stores the parsed pick (nullable until the first emission); a private `applyEarbuds()` computes the boolean once both are known and calls `recordingOverlay?.setEarbuds`. Unregistered in `onDestroy` before the overlay stops.

Rejected: publishing the route through `RecordingOverlayState` from the session owner (only exists during a take, so the idle bubble could not be coloured, which is the ask); a new shared "current microphone" singleton (a second home for a fact `InputDeviceResolver` already computes on demand; guilty until proven innocent, and not needed).

## 3b. Ownership justification

The listener lives on `PasteAccessibilityService` because that is the one object whose lifetime matches the overlay it colours, and it already owns the preference collect that feeds the overlay; the alternative was the session service, but it is not alive while the bubble idles, and the idle bubble is the ask.

## 4. Contract deltas

- `BrandPalette.RAINBOW_EARBUDS`: a second nine-stop array; every consumer of `RAINBOW` that draws the recorder must now read a palette property rather than the constant.
- `BrandMarkView.palette`, `RecordingLevelMeterView.palette`: which nine colours are drawn; the default is unchanged behaviour.
- `RecordingAccessibilityOverlay.setEarbuds`: "the earbuds are the chosen microphone" is now an input to the overlay, like `setLook`.
- `InputDeviceResolver.earbudsAreTheMicrophone`: a projection of `resolve`; true iff the take would open a Bluetooth route.

## 5. State and lifecycle audit

- Service instance created → overlay + callback + collect; reconnect on same instance → guarded, nothing added (`onServiceConnected` guard, enumerated: one site). Destroy → unregister, cancel collect, stop overlay (three sites, in that order).
- Inputs list: set at registration (read once) and on every add/remove callback; pick: set on every preference emission. Either write recomputes. Enumerated producers of the boolean: two; both on main.
- Views: `palette` set on main only (the overlay's contract, same as `setLook`).

## 6. Consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| palette on the mark | `BrandMarkView.onDraw`, `setBusy` roll | reads `RAINBOW` | reads `palette` | yes | `RecorderBrandTest` row: roll with the earbud palette draws only earbud colours |
| palette on the rail | `RecordingLevelMeterView.onLayout` | shader over `RAINBOW` | shader over `palette`, rebuilt on change | yes | `RecordingLevelMeterViewTest` row |
| `setEarbuds` | `RecordingAccessibilityOverlay` | none | sets both views | yes | `LipsBubbleWiringTest` source guard |
| `earbudsAreTheMicrophone` | `PasteAccessibilityService.applyEarbuds` | none | computed on pick or device change | yes | `InputDeviceResolverTest` rows; `LipsBubbleWiringTest` guard that the service registers and unregisters the callback |
| `RAINBOW` in `ui/OnboardingDemo.kt` | onboarding practice rail | brand | brand (unchanged) | no | none needed |

## 7. Failure modes

| Failure mode | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| `getDevices` throws or returns null | AudioManager | service | brand rainbow (empty inputs → no target → false) | none | next callback |
| Callback never fires initially | Android | service | first paint from the registration read; later changes still push | none | n/a |
| Earbuds disconnect between last callback and take start | timing | capture | take from the phone, History card says phone, bubble recolours within the callback | History (already) | n/a |
| Pick names earbuds that are not connected | user | resolver | `PICK_MISSING` → Auto → whatever Auto picks (another Bluetooth input → blue; wired, USB or phone → brand); matches what the take does | none | n/a |
| No microphone at all (`target == null`) | phone | resolver | brand rainbow; the take itself fails on its own path | none | n/a |

## 8. Caller-visible signals

- `palette` identity: `===` `BrandPalette.RAINBOW` means brand; any other array is a variant. Only two arrays exist; a third would need its own name in `BrandPalette`.
- The boolean handed to `setEarbuds`: presence of the earbud colours IS the signal; nothing else in the UI reads it.

## 9. Fallback source of truth

| Failure branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| any read failure | brand rainbow | the constant | the colour the app has always drawn; false says "not known to be the earbuds", the safe claim when unknown | palette is `RAINBOW` | n/a | both views |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/paste/BrandPalette.kt`: add `RAINBOW_EARBUDS` with the nine stops and a comment naming the mock and the founder choice.
- `app/src/main/java/com/envi/wispr/paste/BrandMarkView.kt`: `var palette: IntArray` with invalidate; `onDraw` reads it.
- `app/src/main/java/com/envi/wispr/paste/RecordingLevelMeterView.kt`: `var palette: IntArray`; a private `rebuildShader()` builds the gradient from the current bounds and `palette`, called by `onLayout` and by the setter, which then invalidates.
- `app/src/main/java/com/envi/wispr/paste/RecordingAccessibilityOverlay.kt`: `fun setEarbuds(earbuds: Boolean)`: both palettes and the bubble's content description.
- `app/src/main/java/com/envi/wispr/audio/InputDeviceResolver.kt`: `fun earbudsAreTheMicrophone(pick, inputs): Boolean`.
- `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt`: callback field, registration inside the overlay-creation block, pick captured in the existing collect, `applyEarbuds()`, unregister in `onDestroy`.
- `docs/mockups/android-lips-bluetooth-v1/index.html`: the mock (already written), plus a `README.md` naming the choice.
- Tests as in §11.

## 11. Testing

1. **Class.** `earbudsAreTheMicrophone` rows: product outcome (when they fail, the user sees the wrong colour for the microphone). Palette rows on the mark and rail: product outcome (the lips draw phone colours on earbuds). Wiring guards: drift guards, named as such.
2. **Revert that turns each red.** Resolver rows: swap `needsBluetoothRoute` for `true`. Mark row: revert `onDraw` to the constant. Rail row: remove the `rebuildShader` call from the setter (stale gradient). Wiring guards: delete the `registerAudioDeviceCallback` line, or the `unregister` in `onDestroy`.
3. **Not tested.** The initial-callback premise (platform behaviour; the registration read covers both outcomes). Bluetooth on the emulator (impossible; phone pass).

### 11.1 Hardware UAT spec

- **Subsystem:** limb (a colour on the recorder).
- **Recipe:** new, in `device-testing.md`: AirPods connected, Input device Auto → open Messages, keyboard up → bubble lips are blue; tap the bubble and speak → the tap pill's active bars are blue while speaking and grey at rest during silence; hold the bubble and speak → the same on the hold pill; remove AirPods (case) → bubble returns to brand within a second; pick Phone in Settings with AirPods in → brand; pick the AirPods by name → blue. Repeat the idle-bubble check in each of the three looks (Bare over a white page, Clear, Smoke) and with the bubble docked left and docked right.
- **Expected observation:** the colours, by eye, and a screenshot per state saved with the run. Oracle: the eye against the mock; the History card's microphone line as the independent check of what recorded.
- **Restore:** Input device back to Auto.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `InputDeviceResolverTest.earbudsAreTheMicrophone*` (rows: Auto with LE Audio; Auto with SCO; Auto with wired and BT both present (false); Auto with USB only (false); Auto phone only (false); picked Phone with BT present (false); picked BT missing with another BT present (true); picked BT missing with phone only (false); no microphone at all (false)) | product outcome | the boolean matches the route the take would open | predicate hard-coded |
| `RecorderBrandTest` row | product outcome | with the earbud palette the lips draw only earbud colours, rolling included | `onDraw` reads the constant |
| `RecordingLevelMeterViewTest` row | product outcome | a palette change on a laid-out rail rebuilds the gradient without another layout | setter does not call `rebuildShader` |
| `LipsBubbleWiringTest` row | drift guard | the bubble's content description names the earbuds in the blue state and returns to the existing wording in the brand state | description not rewritten, or not restored |
| `LipsBubbleWiringTest` rows | drift guard | the service registers on the main handler inside the creation guard and unregisters in `onDestroy` before the overlay stops; the overlay applies the palette to both views | line deleted |
| `RecorderBrandTest` size row | drift guard | `RAINBOW_EARBUDS.size == BAR_COUNT` | a stop removed |

## 12. Blast radius & rollback

- Touched: `paste/` (four files), `audio/InputDeviceResolver.kt`, tests, one mock folder. Not touched: `:audio` capture, the session service, AIDL, Compose screens, onboarding, preferences schema.
- Rollback: revert the PR; no stored data changes.

## 13. Ship criteria

- [x] With AirPods in and Auto, the idle bubble lips and the pill's rail are the blue rainbow on the S26, on the tap pill and the hold pill; taking them out returns the brand rainbow without a restart.
- [x] Picking Phone with AirPods in shows the brand rainbow; picking the AirPods by name shows blue.
- [x] Confirmed in Messages over Gboard on the founder's S26 Ultra (phone pass).

## 14. Open questions

None blocking. The mock's blue outline is not built (non-goal); if the founder wants it, it is a follow-up on `BubbleLook`.

## 15. Related

#171, #165, #26, PAR-022, catalog `bluetooth-routing`, `docs/mockups/android-lips-bluetooth-v1/`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [x] Self-reviewed to all-clear before any reviewer saw it
