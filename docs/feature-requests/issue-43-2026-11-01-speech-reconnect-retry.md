# Issue #43: retry a disconnected speech request once

GitHub issue: #43. Tier: LARGE. Status: APPROVED.
**Lane:** Code
mixed_pr: true
Docs/dev-tooling obligations apply to this plan.
**PAR rows closed:** none. PAR-086 crash replay remains separate.
**Hardware UAT:** Y. Kill the speech helper during a real-model request, observe one transcript and one insertion after reconnect. Run another take afterward. Emulator and physical evidence must be labelled separately.

## Preface: User Rubric
Diana Foster dictates a work update into an existing editor. She wants her words delivered without switching apps or repeating a minute of speech. All existing recording entry points invoke the same owner. Natural examples: Please move our review to Tuesday; I will send the notes tomorrow; The deadline is Friday; We still need the revised draft; Please confirm the next steps. Success is the words arriving once after a transient helper loss; wrong-but-working means duplicated text or publishing after Cancel. Automatic retry fits this transient transport failure; cancellation remains immediate. Priya and Aaron need correctness; Marcus needs his words preserved; Elena needs audio on-device; Meera and Frank need predictable behaviour. No persona needs a recovery dialog.

## 0. TL;DR
Keep the closed audio file during one automatic speech reconnection attempt, under the original request deadline. Reject replies and exceptions from the superseded attempt. This is in-session helper-loss recovery, not app-crash replay or a guarantee that every interrupted take can be recovered.

## 1. Problem
The owner closes the speech wait and deletes captured audio immediately on helper disconnect. The live audit also found interrupted speech takes; their metadata does not distinguish process death from timeout. Root cause of each historical incident is NOT VERIFIED. The deletion path is independently source-demonstrated and reproducible by controlled helper death.

## 2. Goals and non-goals
Deliver the same recorded take after one transient speech-process death. Preserve cancellation, the existing total bound, one terminal and one insertion. Do not retry empty transcripts, ordinary typed engine failures or timeouts, and do not retain audio after the take concludes. Full main-process crash recovery, durable spooling, gain changes and performance tuning are outside this patch.

## 2.5 Grounding brief
Search: rg -n 'onSpeechDisconnected|closeSpeechWait|speechWait|transcribeFileForTake' app/src/main/java/com/envi/wispr/ui/{DictationSessionCoordinator.kt,PipelineBindings.kt,SpeechWait.kt}.
Producer: Android ServiceConnection disconnect clears the speech proxy in PipelineBindings.kt:73-75; connected installs a new proxy at69-70. BIND_AUTO_CREATE is requested at101. The platform retains a disconnected binding and calls onServiceConnected when its service runs again: https://developer.android.com/reference/android/content/ServiceConnection . onBindingDied is a different terminal binding condition, not this retry signal.
Owner: DictationSessionCoordinator.kt:580-594 immediately closes/deletes/ends a processing take. continueAfterEnding at870-973 reads the finished file, arms SpeechWait and sends its path through SpeechLink. The proxy at PipelineBindings.kt:162-169 posts answers to main. SpeechWait.kt:15-49 provides one atomic deadline and first-answer claim. CapturedAudioFiles.kt:24-37 queues archival/deletion outside the service lifetime. Consumers: callbacks update TakeOutcomeRecorder, TakeHistory, polishAndPublish and SessionFinalizer; finishing/destroy close the wait.
Existing authority search: rg -n 'SpeechWait|retry|reconnect' app/src/main/java/com/envi/wispr/ui/{SpeechWait.kt,TakePolishController.kt,PipelineBindings.kt,DictationSessionCoordinator.kt}. SpeechWait already owns request admission and closure; extend it rather than introduce another session owner. House precedent is the per-take polish decision and its first-wins acceptance.
Prior context: issue43 and its complete comments read; session-log November1 critical-fixes receipt read; catalog crash-audio-recovery/in-session-asr-salvage and catalog_gap queried. macOS audio-recovery owner distinguishes same-session rescue from crash replay; some helper descriptions are historical after its in-process migration. No claim of equivalent current helper architecture.
Problem-only review traced worker/main races, stale Binder snapshots, independent deletion and the fake speech disconnect mismatch. Its record lives outside the public checkout in the Android stability artifact run. Unchecked helpers in that review will be inspected where needed; old production incidents are not treated as a reproduced cause.

## 3. Design
Extend SpeechWait with opaque attempt tokens and a finite disconnect outcome. Its single timer remains armed across reconnect; retry never re-arms or extends it. One claim admits each attempt. First disconnect of an admitted request invalidates its token and allows exactly one subsequent claim. A disconnect before any request waits for connection; a second admitted request disconnect exhausts the retry and closes the wait. Answers/throws must claim their own token before changing files or take facts.
The coordinator retains one request closure while the wait is open. After arming and installing it, it invokes it immediately; onSpeechConnected invokes the same closure. It dispatches on the existing worker scope, then reads the current proxy and atomically claims an attempt immediately before the Binder call. The wait records the failed proxy identity and refuses that same proxy on retry; each production reconnect creates a fresh SpeechProxy. A DeadObjectException and platform disconnect for the same attempt, in either order, consume one loss only. A token-scoped late exception cannot exhaust its replacement. Repeated connect signals cannot submit twice. Cancellation/expiry before queued worker admission refuses submission. An already-admitted Binder call may race cancellation, as today; no Binder call runs under the wait lock, and its result cannot publish after cancellation. Superseded callbacks cannot close the current wait, delete audio or publish. DeadObjectException from an admitted request uses the same loss route with its token; ordinary exceptions keep the existing failure behaviour. Existing accepted-text polish fallback is preserved only for an actual platform disconnect, not a stale attempt exception.
No polling, new persistent state, audio upload or new UI. Cancellation/destruction close the wait and clear the closure; cleanup then requests file deletion through the current owner. Timeout and exhaustion do the same. The full app-crash case remains issue43 work.

## 3b. Ownership
Attempt identity and retry allowance live in SpeechWait because it already owns acceptance and lifetime. Coordinator continues to own take endings, facts and publication; PipelineBindings remains Android transport. A new speech coordinator would duplicate these authorities for this bounded patch.

## 4. Contract deltas
SpeechWait accepts only the current admitted attempt, once, within one unchanged deadline; connection loss can supersede one attempt. Coordinator now waits for a replacement speech proxy instead of ending at the first loss. No AIDL, Room, telemetry schema, insertion or capture contract changes.

## 5. State and lifecycle audit
NEW plus disconnect: no request to lose; capture continues and the finished-file request waits under its bound. OPEN without admitted attempt plus disconnect: wait. OPEN first admitted attempt plus disconnect: invalidate and allow one replacement. OPEN replacement plus disconnect: close and fail. DONE plus disconnect: do not reopen; preserve accepted-text fallback when applicable. Answer/cancel/timeout/destroy close acceptance atomically; stale callback/throw changes nothing.
Interrupted: audio/main deaths are unchanged; ASR death covered; polish loss still fails open. Deleted: user cancellation and cleanup win, no retry can revive a closed wait. Mutated: frozen take/preferences/target/file path retained. Concurrent: token claim serializes duplicate connects, answer versus loss, cancel versus claim. Absent: no proxy waits under existing bound, no reconnect times out. Stale: old callbacks/throws are rejected by token identity. Configuration changes and leaving the editor do not create a new take or repin a target. Calls/Doze/mic takeover still use existing capture outcomes.

## 6. Consumer matrix
| Delta | Consumer | Required behaviour | Verification |
| --- | --- | --- | --- |
| Superseded request | Speech callbacks/catch | No text, facts, cleanup or terminal | Stale result/failure/throw controls |
| Retained file | CapturedAudioFiles | Same path until accepted result or take closure | File existence/deletion assertions |
| Replacement result | History, polish, finalizer | One completion and insertion | Existing session rig, device take |
| Pending connection | Session surface | Processing until reconnect or bound | Explicit timer/reconnect controls |
| Disconnect after answer | Polish fallback | Keep accepted words, no new ASR | Existing PolishFailsOpenTest |

## 7. Failure-mode by caller
First ASR disconnect: processing remains visible, no terminal yet, file retained, one replacement request. Second request loss: existing speech-loss notice/ASR-error row, file deletion requested, no further retry. No reconnect/timeout: existing unresponsive notice and row. Cancel/destroy: existing cancellation/interruption, no late publication. Typed failure, empty transcript, capture failure: unchanged endings, no new retry. All recording entry points use this same owner.

## 8. Caller-visible signals
Attempt token is identity, not a take ID; its absence means no admitted request. Disconnect outcome distinguishes waiting/retrying/exhausted/finished. Closed wait permanently refuses admission. Audio path stays the same take-owned closed file. Terminal and telemetry facts are written only by the accepted ending/result; no new diagnostics contain audio or words.

## 9. Fallback source of truth
Before accepted ASR text, the only candidate is the same closed audio file; resubmit it through the current speech proxy only while the wait is open and retry allowance remains. After text acceptance, existing deterministic cleanup/polish fallback remains authoritative. If neither yields text, retain the existing explicit failed ending and cleanup; do not fabricate success or defer a represented ending to launch replay.

## 10. File changes
SpeechWait.kt; DictationSessionCoordinator.kt; existing SpeechWaitTest.kt and DictationSessionRig.kt. Add only a real-boundary device row if the existing device rig can stage helper death. No changes to capture/native decoding, models, providers, public privacy claims or store assets.

## 11. Testing
New session-rig rows are Harness Contract tests; they exercise the real owner with fake Binder peers and do not prove actual speech recognition. Token/deadline unit rows protect lifecycle logic. A controlled real service death plus shipped-model decoding is the Product Outcome boundary. Revert the retry transition to require RED before restoring. Test first-loss recovery, old result/failure/throw, second loss, no reconnect timeout, cancel/destroy during reconnect, initially absent proxy, duplicate reconnect and disconnect before speech admission. Include paired platform/DeadObjectException loss reports in both orders; refuse a stale proxy before platform clears it; gate the worker to cancel/expire before admission; reconnect near expiry without changing the timer; then reject a late replacement result/throw without touching a later take file. Existing successful/timeout/polish-fallback tests remain.
Hardware UAT: use Wispr Eyes; emulator first, then free Test Lab or available physical phone. Never install debug over the founder's Play app. Real-model recovery plus subsequent take, exact editor output once when staging allows. Preserve limitations: virtual audio is not a physical microphone test; a device probe with fake speech is not real-model evidence. No paid cloud run is authorised. Restore all device state.

## 12. Blast radius and rollback
Only main-process speech request lifetime changes. Roll back with a normal reviewed revert PR if recovery fails. No stored schema or format migration.

## 13. Ship criteria
A real speech-service loss reconnects and delivers one result within the original bound; Cancel cannot publish after cancellation; old attempts cannot delete the live recording. User-facing effect verified with source-matched candidate and exact evidence.

Coverage review completed once. Its three gaps (paired loss signals/stale proxy, queued admission cancellation, unchanged deadline and next-take cleanup) are incorporated above. EngineBindings.kt:47-70 forwards lifecycle events; its onBindingDied/onNullBinding remove their tracking record. These terminal binding cases are not retried; existing timeout bounds them. CaptureFiles.kt:15-25 gives production takes distinct files; the shared-path fixture remains an adversarial late-cleanup control. TakeArbiter.kt:58-107 owns terminal arbitration.

## 14. Open questions
Historical deaths versus timeouts remain indistinguishable in existing metadata. Physical-device availability and current free quota must be checked before submission. Full app-process crash recovery remains outside this patch.

## 15. Related
Part of issue43. PAR-086 is not closed. Prior issue180 gain investigation remains unresolved and unchanged.
