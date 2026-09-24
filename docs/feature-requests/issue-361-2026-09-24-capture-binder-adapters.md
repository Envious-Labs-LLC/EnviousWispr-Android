# Issue #361: the capture process's binders leave the service (REF-06, regrade 8) (2026-09-24)

GitHub issue: `#361`. Tier: MEDIUM (the `:audio` binder surface, no behaviour change). Status: built, before the combined Codex coverage and review round.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** light. An emulator take still binds `:audio`, goes live and lands its words; that is the take interface end to end. The legacy interface's device test client is unchanged and not rerun here.

## Preface: User Rubric

No user-visible change. `audio/AudioCaptureService` (1,000 lines) wrote both binder interfaces inline: the legacy `IAudioCaptureService.Stub` (every transaction older clients and the device tests call) and the owner's five-operation `IAudioTakeService.Stub` (#220), each reaching into the service's private fields.

## 0. TL;DR

- New `audio/CaptureOperations`: what the binders may do to the service, as named operations and reads (`startLegacy`, `startTake`, `finishTake`, `stopCapture`, and the reads the legacy getters return).
- New `audio/CaptureBinderAdapters(ops, spectrumListener, takeListener)`: `legacy` keeps every `IAudioCaptureService` transaction and its meaning (`architecture-rules.md` RULE: aidl-is-append-only; no AIDL file changes); `forTake(epoch)` is the take interface, one binder per take binding, registering under its epoch. Each method is one call of `ops` or one listener slot. Log lines keep the service's `AudioCapture` tag.
- The service implements `CaptureOperations` in one private object whose bodies are the old getters' bodies, and `onBind` hands out `binders.forTake(...)` or `binders.legacy` exactly as before.

## 1. Tests

- `CaptureBinderAdaptersTest` (JVM; this module's unit tests stub `android.os.Binder`): every start means what it always meant, the four legacy ones refusal-only with their old arguments and both take starts the recovering one (m1: the held start drops `keepEarbudsReady`; m2: the legacy take start loses recovery); every read and operation passes straight through; a take binder's registration dies with its binding (m3: it registers as legacy); the legacy binder registers and unregisters as legacy.
- The source-reading guards (`AudioServiceShapeTest`, `CaptureTakeInterfaceShapeTest`, `LiveAudioMeterWiringTest`, `LiveGateWiringTest`, `RecorderLeaseTest`, `SilenceStopWiringTest`) now read the adapters where the transactions moved; each keeps its check.
