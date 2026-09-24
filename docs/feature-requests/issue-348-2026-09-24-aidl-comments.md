# Issue #348: the AIDL files give the current append-only reason (REF-06, regrade 7) (2026-09-24)

GitHub issue: `#348`. Tier: SMALL (comments only). Status: built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code (comments only; no method, order or signature changed).

**PAR rows closed:** none.

**Hardware UAT:** none.

## 0. TL;DR

#330 showed that the installed test APK defines no AIDL or parcel class of its own. It calls every service through the app APK's generated Stub and Proxy, so transaction numbers always match. The append-only rule stays, for a different reason: an older test APK calls methods by name, and a removed or renamed one fails when that test invokes it (`architecture-rules.md` RULE: aidl-is-append-only, sharpened in #330).

These comments still gave the old transaction-number reason, and each now gives the current one:

- `IAudioCaptureService`
- `IAudioSpectrumListener`
- `ITakeListener`
- `IAudioTakeService`
- `IAsrService`
- `IAsrCallback`
- `ISilenceVadService`
- the comment in `SilenceStopWiringTest`

`IPolishService` was corrected in #330. Every method and its order is unchanged.

## Results (2026-09-24)

- The app builds; the audio and speech tests pass. A search of `app/src` finds no remaining "transaction number" claim.
