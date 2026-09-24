# Issue #283: the legacy capture wait helper is private (2026-09-24)

GitHub issue: `#283`. Tier: SMALL (one visibility modifier). Status: built as the issue proposes; Codex reviews plan and diff together.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** N. Visibility only: the binder method the instrumentation client calls is unchanged, and the androidTest build compiles against it.

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes. One helper that only the capture service's own binder calls is no longer reachable from the rest of the app.

---

## 0. TL;DR

REF-07 of `docs/audits/2026-09-23c-senior-audit.json` (`architecture-rules.md` RULE: minimize-visibility). `AudioCaptureService.waitForFileReady` was `internal` (since #275). Its only caller is the service's binder stub, which implements the append-only AIDL method `boolean waitForFileReady(long timeoutMs)`; the device tests reach it through `IAudioCaptureService.Stub.asInterface`, never the service instance. It is now `private`. The AIDL method and its delegation are unchanged.

## 1. Grounding (main 17cf5c2)

- `AudioCaptureService.kt`: the stub's `override fun waitForFileReady(timeoutMs: Long): Boolean = this@AudioCaptureService.waitForFileReady(timeoutMs)`; the helper at L953.
- `CaptureWithSilenceStopDeviceTest` and `SilenceStoppedTakeTranscribesDeviceTest` bind `IAudioCaptureService.Stub.asInterface(it)` and call `capture.waitForFileReady(...)` on that interface.

## 2. Tests and receipts

- `AudioServiceShapeTest.theLegacyFileWaitHelperIsPrivateAndTheAidlMethodStays` reads the compiled class: every non-synthetic `waitForFileReady*` method is private (RED on main, where the `internal` helper compiles to a public module-tagged method), and the AIDL method and the stub's delegation are still there.
- Mutations: m1 back to `internal`, m2 public: both RED (`283-mut.py`).
- Suite 1349, 0 failures; app and androidTest build; visibility and cited-symbol checks clean.

## 3. Blast radius

A caller of the helper outside the service would stop compiling; none exists. Rollback: revert the squash commit.
