# Issue #330: the polish policy parcel needs no version (REF-06, regrade 6) (2026-09-24)

GitHub issue: `#330`. Tier: SMALL (evidence and documentation), if the coverage round confirms. Status: confirmed by the coverage round (`330-cov`); REF-06 closed with evidence, documentation only.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none. The evidence is the built instrumentation APK's own class list.

## Preface: User Rubric

Persona: the founder. The audit fears that a new app and an older instrumentation APK (`app-debug-androidTest.apk`) disagree on the `PolishPolicy` parcel at runtime. The only effect would be on device test runs, never on his dictation.

## 0. TL;DR

The audit asks for a versioned polish request transaction and a versioned policy parcel, kept alongside the old ones. Evidence against the premise, 2026-09-24: the instrumentation APK DEFINES none of the classes that write or read that parcel. Its dex `class_defs`, read directly (2444 classes), contain no `PolishPolicy`, `PolishOutcome`, `IPolishService` (Stub or Proxy), `IAudioCaptureService` or `IAsrService`. The only match is `PolishPolicyParcelTest`. The test APK runs in the app's process (its instrumentation targets `com.envi.wispr`), so every parcel it sends is written by the INSTALLED app's `PolishPolicy.writeToParcel`, through the installed app's `IPolishService.Stub.Proxy`, and read by the same APK's `PolishService` in `:polish`. Writer and reader are always one class from one APK, whatever the age of the test APK.

What an older test APK CAN break is its own compiled calls. A method it names that the new app no longer has fails at link time (NoSuchMethodError), not as a silent parcel disagreement. That is what `aidl-is-append-only` protects, and it stays.

Proposed outcome:

1. No versioned transaction or parcel. A version byte would guard a case that cannot occur, and it would add a compatibility path that nothing exercises.
2. Record the evidence where the next reader looks: the `PolishPolicy.CREATOR` comment, and the `IPolishService.aidl` header.
3. Sharpen `architecture-rules.md` RULE: aidl-is-append-only (a gitignored rule, edited in the primary checkout). The separately installed test APK breaks on a REMOVED or renamed method its code calls. It never sees a parcel format of its own, because the app's classes serve it.
4. Close REF-06 with the dex evidence, unless the coverage round shows a path where an older client writes or reads with its own copy.

## Results (2026-09-24)

- Coverage round (`330-cov`), confirmed: there is one app test setup, with no separate test module, no self-instrumentation, no process override and no test copy of the parcel or AIDL classes. The tests run in the app's main process and bind its `:polish` service. Both processes use the installed APK's classes, so its generated Stub and Proxy agree on transaction numbers. No policy or outcome parcel is placed in a pending intent, saved state or persistent store.
- Adopted as given: the drop-in sentences at `PolishPolicy.CREATOR` and the `IPolishService.aidl` header. The rule's rationale is now "an older installed test APK fails on a removed or renamed method it calls (NoSuchMethodError)". It no longer claims a parcel or transaction-number mismatch. The rule is edited in the primary checkout's gitignored `.claude/rules`.
- No behaviour change: no mutations and no device run.
