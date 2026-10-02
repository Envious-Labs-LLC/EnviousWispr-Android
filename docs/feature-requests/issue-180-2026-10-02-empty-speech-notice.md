# Issue #180: explain an unreadable recording
Tier: SMALL. Approved: founder asked for Phase 1 dictation stability and full overnight autonomy, 2026-10-02.
**Lane:** Code
mixed_pr: true
Docs/dev-tooling: this plan.
**PAR rows closed:** none.
**Hardware UAT:** Y. Silent synthetic noise/quiet fixture must run through installed pipeline and show one calm failure line; true silence stays silent; normal speech still inserts. Emulator used for the new build first, physical S26 through Play after review.
## Preface: User Rubric
Named persona: founder Saurabh, messaging from his phone. Wants to know why spoken words disappeared and try again. Reactive single sentence after empty recognition; no new control. Inputs may be a soft short phrase, a normal message, background sound, true silence or cancellation. Success: understandable retry hint rather than unexplained closure. Wrong-but-working: claiming actual speech from amplitude alone. Keep the notice honest about unreadable words. Other personas share this narrow failure feedback, no new workflow.
## 0. TLDR
Say a calm retry line only when measurable audio reached the speech engine but its transcript was empty. Do not change gain, classification, telemetry or History behavior. Part of #180, not full closure.
## 1. Problem
Issue #180 records founder's soft take disappearing with audio peak 0.0589 and no text. TakeNotices returns null for this ending. Current coordinator's rawText.isBlank branch also never calls TakeNotices, so changing the table alone would be dead code.
## 2. Goals and non-goals
One notice after winning terminal ownership. Quiet room, cancelled/interrupted take and unmeasured empty audio stay silent. Gain remains a separate measurement question; no new recording retention or telemetry.
## 2.5 Grounding
SpeechEvidence does not check finiteness. Null means ASR_EMPTY_UNMEASURED; values below 0.01 mean NO_SPEECH; everything else means ASR_EMPTY_DESPITE_AUDIO. Enumerated input classes: null is unmeasured; negative infinity, negative finite, zero and finite below floor are silence; NaN, positive infinity, at-floor and above-floor are audible-empty. This patch gates only the notice on finiteness; classification stays unchanged. Non-finite runtime reachability is UNCHECKED. Coordinator polishAndPublish commits reason first, discards History, releases pin and finishes. SessionNoticePresenter.sayFailure posts service toast on main. Notice table is authority, frozen literals test every enum member. Existing audibleNonSpeechLeavesNothing coordinator row stages peak and empty callback; extend it to assert one literal toast and no History. Quiet and cancelled neighboring rows are controls. Catalog user-copy query found no corresponding empty-audio notice. macOS kernel explicitly keeps asrEmptyDespiteAudio silent (RecordingSessionKernel.swift, KernelDictationDriver.swift), whereas Android issue #180 explicitly requests this notice; declared scoped divergence. Audio energy is not speech recognition, so copy must not claim the user spoke or prescribe gain as the measured root cause.
## 3. Design
Set ASR_EMPTY_DESPITE_AUDIO line to "Couldn't make out the words. Please try again." In rawText.isBlank branch freeze peak and reason, commit once, discard and release pin; only with a finite measured peak use TakeNotices.line(reason) and notices.sayFailure if nonnull (terminal classification unchanged); finish normally. Do not route through announceError, which would add failure haptics and change teardown. Keep first-wins behavior.
## 4. Contract deltas
A single previously silent ending now has a toast. TerminalResult, telemetry channels, draft deletion, capture and ASR unchanged. No new coordinator or state.
## 5. Lifecycle
Enumerated blank callback after processing, late callback after cancellation, teardown and duplicate callback. Only commit winner can emit notice; loser returns before any side effect. SessionNoticePresenter posts to main as for existing ending sentences.
## 6. Consumers
TakeNotices literal map and coordinator consume new line; existing notices presenter draws it. Tests' speaking expectation distinguishes this audio-empty result from silence. Telemetry and History retain current behavior.
## 7. Failure modes
Empty with measured audio: retry toast, no History. Empty true silence: no toast, no History. Empty unmeasured: no speech claim. Cancel/teardown: commit loses, no late toast. Normal speech: existing polish/insertion unchanged.
## 8. Signals
The existing peak classifier and terminal claim own the decision. Toast event in coordinator rig is the wiring oracle, not just line mapping. No new persisted flag.
## 9. Fallback authority
No text exists to fall back to. Discard empty draft as today; no fabricated History or clipboard contents. Notice is truthful about unreadable words, not the cause.
## 10. Files
TakeNotices.kt, DictationSessionCoordinator.kt, TakeNoticesTest.kt, DictationSessionCoordinatorTest.kt, DictationSessionRig.kt.
## 11. Validation
Full unit suite, assembleDebug, notice literal and coordinator wiring tests. Mutation remove notice invocation must fail coordinator test; restore old null map must fail notice test. Test quiet/late-cancel controls, duplicate blank callback and blank callback after cancellation and teardown. Assert no failure haptic, no insertion, pin release, no History for the completed empty take; destruction preserves its existing interrupted draft; rig service toast asserts main-thread invocation. Update mapper literal/speaking expectation and misleading old-silence test names. Code review and confirming rerun before delivery.
## 11.1 Device
New build: emulator noise take through existing documented silent injector if supported (never bypass wispr-eyes recording ban); physical phone Play update, silent synthetic speech take into Chrome, unreadable audio and true silence cases with local log + visible toast receipt. Any unavailable case remains NOT RUN, not inferred from units.
## 12. Delivery
Separate branch/PR, Part of #180. No closure until gain measurement is resolved. Phone receives reviewed build through Play.
## 13. Acceptance
Unreadable audio gets one line; normal speech inserts; true silence and cancellation don't gain error messages.
## 14. Open questions
Quiet-input gain awaits evidence and is outside this patch.
## 15. Related
#180, #176 existing terminal/notice vocabulary.
