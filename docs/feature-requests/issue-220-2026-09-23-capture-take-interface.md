# Issue #220 — The owner binds a take-sized capture interface — 2026-09-23

GitHub issue: `#220`. Tier: REFACTOR (the cross-process API between the app and the `:audio` process). Status: DRAFT (coverage round adopted; grounded round 1 adopted).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The change narrows an internal binder surface; no parity row changes status.

**Hardware UAT:** Y (the heart: every take crosses this binder). Success for a person: a dictation starts, the bubble shows the live picture, the words land, and a second dictation right after it starts without a wait, exactly as today. Run on the emulator through `wispr_eyes.dictate_emulator` twice back to back; the founder's phone pass is queued with the rest of the audit work.

## Preface — User Rubric

User Rubric: N/A — the owner reaches the same five service operations through a smaller binder; no take, route, timing or sentence changes.

---

## 0. TL;DR

`IAudioCaptureService` has 28 transactions. Production (`ui/PipelineBindings.kt` `CaptureProxy`) calls five: `startCaptureForTake`, `stopCapture`, `finishTake`, `registerSpectrumListener`, `registerTakeListener`. Two device tests call ten distinct legacy methods; fifteen have no caller in the reviewed files. REF-06 of the 2026-09-22 senior audit (Interface Segregation) asks for a take-sized interface for production while the legacy one stays byte-for-byte append-only. Add `IAudioTakeService` (proposed) with exactly those five operations, return it from `AudioCaptureService.onBind` for the explicit action `ACTION_BIND_TAKE` (proposed), keep the legacy binder for every other bind, and move `CaptureProxy` to the new interface.

## 1. Problem

The production owner holds a proxy to all 28 transactions, including four legacy starts, polled getters and a blocking `waitForFileReady`. `CaptureLink` narrows what the owner's Kotlin code calls, but the binder it holds still offers the rest, so nothing but review stops a polled read coming back (the #44 and #115 class). The audit cites `IAudioCaptureService.aidl:L6-L17`, `L122-L135` and `AudioCaptureService.kt:L204-L300`.

## 2. Goals & non-goals

### 2.1 Goals
1. `IAudioTakeService.aidl` (proposed) declares, in this order: `startCaptureForTake`, `stopCapture`, `finishTake`, `registerSpectrumListener`, `registerTakeListener`. Signatures identical to the legacy ones.
2. `AudioCaptureService` builds a second stub, `takeBinder` (proposed), whose five methods call the same service functions the legacy five call, with the same arguments (`startCaptureForTake` keeps `mayRecover = true`).
3. `onBind(intent)` returns `takeBinder` when `intent?.action == ACTION_BIND_TAKE`, else the legacy `binder`. An actionless bind (the two device tests) keeps the legacy binder.
4. `PipelineBindings` binds with the action and wraps `IAudioTakeService.Stub.asInterface`; `CaptureProxy` takes `IAudioTakeService`.
5. The legacy AIDL file is unchanged, byte for byte.

### 2.2 Non-goals
- No legacy transaction is removed or renumbered; the 15 uncalled ones stay (a separately installed client binds by transaction number).
- No change to REF-07's warm hold: #219 was closed by the founder, so the post-take disposition the audit mentions is today's `finishTake`, which the new interface carries.
- No change to `IAudioSpectrumListener` or `ITakeListener`.
- No stable-AIDL build versioning: that is a platform build mechanism, not a smaller interface.

## 2.5 Grounding brief

Grounded by Codex (`220-g0`, read-only) and re-read by Claude.
1. Callers: production `PipelineBindings.kt:131,132,139,141,151`; instrumentation `CaptureWithSilenceStopDeviceTest.kt` and `SilenceStoppedTakeTranscribesDeviceTest.kt` bind actionless and decode the legacy binder (`C:54-69`, `S:69-73`).
2. Binding: `AudioCaptureService.onBind` returns one binder regardless of intent (`AudioCaptureService.kt:319`); no action or extra is read anywhere in the service. The manifest declares the service `:audio`, `exported="false"`, no intent filter; an explicit component intent with an action needs none. Android caches one binder per `Intent.filterEquals` identity (action included), so an actionless and an actioned bind get separate `onBind` calls.
3. Listener slots: one `spectrumListener` and one `takeListener` `AtomicReference`, set by the register calls, cleared with a binder-identity `compareAndSet` by the legacy unregister calls, and cleared outright by `onUnbind` (`AudioCaptureService.kt:327-330`).
4. Pinning tests: `SilenceStopWiringTest` (legacy transaction order), `LiveAudioMeterWiringTest.theCaptureProxyMakesThreeCommandsAndTwoRegistrationsAndAsksNothing` and `.theServiceClearsOnlyTheObservedListener`, `RecorderLeaseTest.onlyTheProductionStartMayRecover` (slices the legacy stub and expects five starts).
5. Already solved: `CaptureLink` already narrows the Kotlin surface; no Android mechanism narrows an app's binder for us, so the second interface is the fix (Android AIDL guidance: https://developer.android.com/develop/background-work/services/aidl).

## 3. Design

- `app/src/main/aidl/com/envi/wispr/audio/IAudioTakeService.aidl` (proposed): the five methods of §2.1.1, with a header comment naming it the owner's interface and saying it is append-only from its first release, like the legacy one.
- `AudioCaptureService`:
  - `companion object { const val ACTION_BIND_TAKE = "com.envi.wispr.audio.BIND_TAKE" }` (proposed).
  - Create a take binder for each binding epoch instead of keeping one service-lifetime `takeBinder`: `newTakeBinder(epoch)` (proposed) returns an `IAudioTakeService.Stub`. Its registration methods carry that epoch into `ListenerSlots`. Invalidate the epoch on take unbind; reject registrations from an invalidated epoch, including after a later take binds. (Android calls `onBind` once per intent identity while bound and again after the last unbind, so each take binding gets a fresh binder.) The owner's command lane can run a queued registration after the unbind (`CaptureSessionController.command` keeps the link it read); today such a late registration survives into the next take, where only the surface's serial check drops its pictures. Each other method is a one-line delegation to the SAME service function the legacy method calls. The four function bodies that are today inline in the legacy stub (the start, `finishTake`, the two registrations) move to private service functions both stubs call, so there is one implementation per operation.
  - `onBind(intent) = if (intent?.action == ACTION_BIND_TAKE) newTakeBinder(slots.openTakeEpoch()) else binder`; the legacy `binder` stays one service-lifetime object.
  - Listener slots per binding: a slot is cleared on `onUnbind` only when the unbinding intent's binder is the one that set it. Keep the publisher-facing `AtomicReference` slots (`TakeEventPublisher` reads the take slot). Serialize each listener's registration, origin update, legacy unregister and per-origin unbind under one lock (`ListenerSlots` (proposed), one per slot). Legacy unregister requires both legacy origin and matching binder identity. Keep publisher reads as atomic `get()` calls. Route the spectrum publisher's failed-delivery clear (`PicturePublisher.kt:143-151`) through `ListenerSlots.clearIfCurrent`, under the same short lock as registration and unbind. Hold no listener callback, recording operation, or `finishTake` call under that lock. Clear slot and origin together on destroy. With one binding (production today, or the device tests alone) this is exactly today's behaviour; with both bound, a device test unbinding never drops the owner's listeners.
- `PipelineBindings`: `Intent(appContext, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_BIND_TAKE)`; `CaptureProxy(IAudioTakeService.Stub.asInterface(binder))`. `stopAudioService` keeps its actionless intent (it stops, it does not bind).

Alternatives rejected: (a) keep only `CaptureLink` (Codex's `220-g0` recommendation): it narrows the Kotlin calls but the owner still holds a binder with 28 transactions, which is the audit's finding; (b) remove the 15 uncalled legacy transactions: renumbers the legacy interface and breaks the installed client; (c) a separate bound service class for takes: two services would share one recorder and one warm hold across two lifecycles.

## 3b. Ownership justification

The new AIDL lives beside the legacy one in `audio/` because the `:audio` process owns both; the proxy stays in `ui/PipelineBindings.kt`, the one place that binds the pipeline.

## 4. Contract deltas

New binder contract `IAudioTakeService` on action `ACTION_BIND_TAKE`, append-only from release. Legacy `IAudioCaptureService` unchanged and still served to actionless binds.

## 5. State audit

| Population | Enumeration |
|---|---|
| Legacy transactions | 28, unchanged in order and signature; `SilenceStopWiringTest` pins the order (extend it to include `getSpectrumBands`, which its regex skips today). |
| Production calls | 5, all moved to `IAudioTakeService`; `CaptureProxy` names no legacy type. |
| Listener slots | 2, each with an origin; cleared by `onUnbind` of their origin only; unregister stays legacy-only (production never unregisters, `PipelineLinks.kt` `CaptureLink` docs). |

## 6. Consumers

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| New interface | `PipelineBindings.kt` | action on the bind intent; new stub type | `LiveAudioMeterWiringTest` rows updated; new Drift Guard |
| Shared implementations | `AudioCaptureService.kt` legacy stub | delegates to the moved functions | `RecorderLeaseTest.onlyTheProductionStartMayRecover` rewritten per §11.4 |
| Per-origin unbind | `AudioCaptureService.onUnbind` | clears by origin | `LiveAudioMeterWiringTest.theServiceClearsOnlyTheObservedListener` updated; new JVM row if the slot logic is extracted to a small class (`ListenerSlots` (proposed)) |
| Device tests | two `androidTest` classes | none: actionless bind, legacy binder | compile, `assembleDebugAndroidTest`, and `CaptureBindingDeviceTest` at runtime (§11.5) |

## 7-9. Failure modes, signals, fallbacks

A bind with the action to an older installed `:audio` build cannot happen: both sides ship in one APK. `not present in this change` for new signals.

## 10. Files

New `IAudioTakeService.aidl`, `audio/ListenerSlots.kt` (proposed); `AudioCaptureService.kt`, `PicturePublisher.kt`, `PipelineBindings.kt`; tests per §11.

## 11. Testing

1. New Drift Guard `CaptureTakeInterfaceShapeTest` (proposed): the new AIDL declares exactly the five methods in order; `CaptureProxy` names `IAudioTakeService` and not `IAudioCaptureService`; the bind intent carries `ACTION_BIND_TAKE`; `onBind` returns a fresh `newTakeBinder(...)` for the action and `binder` otherwise; each take-binder method is a one-line call of a service function the legacy stub also calls. Revert: point `CaptureProxy` back at `IAudioCaptureService` (red); drop the action from the bind (red).
2. Legacy order: `SilenceStopWiringTest` also pins `getSpectrumBands` at transaction 13. Revert: swap two appended lines (red).
3. Listener origin (JVM, Product Outcome, `ListenerSlots`): a take registration survives a legacy unbind; a legacy registration is cleared by a legacy unbind; a take registration is cleared by a take unbind; a legacy unregister never clears a take registration of the same listener binder; a registration racing an unbind (latched threads) never leaves a slot whose origin disagrees with its listener; unbind, rebind, then a late registration from the first epoch is rejected and the second epoch's listener stays; a failed-delivery clear through `clearIfCurrent` clears slot and origin together. Add a source wiring assertion that `AudioCaptureService.onUnbind` calls the per-origin clear operation for both slots and contains no direct listener clear. Keep the JVM behavior tests; revert either the slot logic (clear regardless of origin) or its service wiring (`set(null)` in `onUnbind`) and verify the corresponding test fails.
4. Existing rows updated: `LiveAudioMeterWiringTest` (the proxy inventory now reads the take interface). Include `AudioServiceShapeTest` and `LiveGateWiringTest` in the test updates. Change their assertions to pin per-origin unbind, the new binder and helper ownership, and the shared `finishTake` helper's call under `sessionLock`; preserve their teardown and warm-hold guarantees. If the spectrum publisher constructor changes, update its construction in `AudioLimbCloseTest.kt:69`. Update the binder-selection shape rows (§11.1) for the binder factory. `RecorderLeaseTest.onlyTheProductionStartMayRecover`: replace the legacy-stub slice with checks that the four older starts remain refusal-only, both `startCaptureForTake` transactions delegate to the same helper, and only that helper passes `mayRecover = true`. Make redirecting any older start to that helper the named red revert.
5. Binder selection on a device: add a microphone-free device check `CaptureBindingDeviceTest` (proposed) that binds both intents concurrently and asserts each returned binder's interface descriptor (`IAudioCaptureService` for the actionless intent, `IAudioTakeService` for `ACTION_BIND_TAKE`). Run it on the emulator with the installed test APK through `adb shell am instrument -w -e class ...`, never `connectedDebugAndroidTest`, which uninstalls the app and its models. Run a legacy device test where its fixture is available; report a skip as unverified legacy behavior.

### 11.1 UAT
Two back-to-back `dictate_emulator` takes into Gmail by COMMIT, each editor's whole text equal; `overlay()` sees the bubble during a take; logcat shows the take listener events for both takes and `finishTake`'s hold decision. Restore: `restore()`.

## 12. Blast radius

The binder between the app and `:audio`: every take. A defect shows at once as a take that never starts, which both UAT takes would catch. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Two back-to-back emulator takes land by COMMIT.
- [ ] The two device test classes still compile against the legacy binder, and `CaptureBindingDeviceTest` passes on the emulator; a legacy device test is run where its fixture is available, and a skip is reported as unverified legacy behavior.

## 14. Open questions
None.

## 15. Related
#219 (REF-07, closed by the founder: the warm hold stays), #115 (the owner's five-call surface), #187 (the spectrum push); audit REF-06.
