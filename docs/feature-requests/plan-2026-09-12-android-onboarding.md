# Android onboarding implementation, September 12, 2026

Tier: LARGE. Lane: Code. Hardware UAT: Y. Status: implementation authorized by founder after mock review. Floating launch bubble, push-to-talk and hardware shortcut setup are deferred to the next feature. PAR rows closed: none until hardware evidence.

## User rubric
Meera Patel is installing on Android before replying to family. She wants messy speech converted to useful writing without account creation or confusing setup. She voluntarily opens EnviousWispr after installation; practice stays inside onboarding, before later use in Messages/WhatsApp. Natural inputs: “I'll be there at six”; “Actually make that seven”; “Bring milk eggs and bread”; “Can you call me when you're free”; “Um tell Grandma I'll call Sunday.” Success is seeing her own words appear. Wrong-not-broken is a ready badge without working models or a sample substituted for her speech. Her workaround is keyboard dictation; controls must allow pause, decline, retry and skip practice. Priya values visible progress; Marcus needs faithful wording; Diana fewer handoffs; Elena accurate privacy; Aaron reachable controls; Frank simple labels. Same four steps suit all; notifications remain optional.

## 0. Outcome
Replace eight setup screens with the approved four-stage mock, backed by real downloads and exact-session practice results. Keep newer integration recorder/meter/duration features and the 16 KB native fix. Source baseline is local codex/android-onboarding fe67348, merging uat/integration with origin/main. Do not present this branch as a published release.

## 1. Problem and 2. scope
The current AppShell OnboardingScreen has eight steps, generic glyphs, separate Privacy, repair-style downloads and a final Open app page. Current practice launches the recorder without an editable destination. Implement the approved welcome videos/copy/lips, automatic download flow with one visible card, actual permission states and direct disclosure-to-Settings with a floating guide, editable practice, and Finish directly into History. No cloud spend or new generated marketing assets. No bubble permissions just for future features.

## 2.5 Grounding
- SettingsActivity -> EnviousWisprApp -> OnboardingScreen receives AppPreferences onboardingStep and readiness. AppPreferences persists completion/dismissal; EnviousWisprViewModel forwards writes. Incomplete older flows must restart the new sequence without changing completed installations.
- ModelDeliveryWorker owns unique model downloads, control-store pause/resume, pinned verification and foreground dataSync. ModelCards/modelUiState owns honest WorkInfo + disk projection; ModelWorkReadinessObserver refreshes readiness. Get Started currently does not enqueue missing downloads. Worker enqueue replaces work; onboarding must not restart active jobs on recomposition.
- DictationSessionService alone admits START, captures, transcribes, polishes and publishes through publishResult to durable History and then insertion. Practice's generic TOGGLE can stop another session. PasteAccessibilityService intentionally ignores own-package windows; do not weaken those exclusions or scrape latest History into practice. Grounded consult confirmed no existing practice identity/result channel.
- AutoPasteReadiness combines permission and live binding. An enabled-but-dead service is not Granted/ready. Notification permission is not required by AppReadiness.coreReady.
- Approved mock and tracker are the visual contract. Prior macOS study informs the shape; latest founder choices override older marketing priority and intermediate tutorial pages.

## 3. Design and ownership
Extract OnboardingScreen from AppShell. Small focused files own welcome media, lips, downloads and permission guidance; do not grow the shell. Reuse existing theme/fonts, generated muted video assets, model descriptors and work-state projection.

Add a dedicated onboarding ViewModel for download commands and the exact practice request/result, retained over rotation. Practice uses the existing non-exported session service through an explicit START command carrying a request token and a ResultReceiver. The service freezes the practice destination when admitting the take; its authoritative publication sends the final text to that receiver, while still retaining History. It does not aim practice output at an old external editor. START/STOP/CANCEL from practice carry the token; a mismatch cannot stop another take. Busy start returns a rejection to that request. UI never infers success from time/latest History or a sample phrase. No new audio/ASR/polish engine or AIDL changes.

The receiver targets a retained ViewModel rather than Activity. Finishing/disposal does not redirect output into another app; History remains the durable fallback. After process death, an interrupted attempt cannot be reconstructed as success; preserved draft stays editable and retry is explicit. Terminal receiver events are first-wins and carry the request token. Return success only for nonblank real output.

Get Started persists the new downloader step and admits missing model jobs using existing unique model names. Repeated entry is idempotent. Present the first not-ready model while observing both; jobs can run concurrently under existing WorkManager scheduling. Pause/resume apply to outstanding setup downloads, retaining partial bytes. No automatic resume after an explicit pause. Retry is explicit after failure. Wi-Fi is default; an explicit mobile-data choice can enqueue with CONNECTED constraints. Advance only when both pinned descriptors are verified ready; do not simulate inference warm-up or percentages.

Accessibility Grant directly shows the independently reviewed prominent disclosure. Agree launches an internal guide activity and the real system Accessibility page. Native PiP shows an original animated diagram matching the approved mock, not competitor media; collect live binding to dismiss only on successful connection. A supported-device fallback keeps written steps reachable. No global screen overlay permission is added for the guide. Android's own permission confirmation is retained. Microphone and notifications use native requests with settings recovery for permanent denial.

## 4. Contracts
New onboarding steps have a versioned preference key; completed users stay completed. Download readiness stays disk-authoritative. Practice commands/results are bound to an accepted request token. Ordinary dictation commands and delivery retain their existing contract. Guide readiness is live service binding, not just the secure settings string.

## 5. Lifecycle enumeration
Interrupted: app background pauses media, downloads stay WorkManager-owned, practice remains service-owned; calls use existing cancellation. Deleted: removed/corrupt models revoke ready; cancelled/wordless practice never succeeds. Mutated: rotation retains request receiver/draft; permission changes refresh state. Concurrent: unique work prevents duplicate downloads; service admission rejects practice while busy; token checks isolate stop/cancel. Absent: no matching Settings/PiP support yields written/manual route, no live Accessibility connection yields retry, failed History still delivers practice result. Stale: old callback/token or previous history cannot overwrite current draft; completed old onboarding does not reopen. Process death abandons in-memory practice ownership and offers retry rather than inventing completion.

## 6. Consumers
Onboarding consumes model-state projection and new practice results. Existing model settings retain existing actions. Ordinary launcher/tile/notification recorder consumers keep their commands. Existing accessibility overlay remains sole recording overlay listener; practice does not attach a competing listener. History retains actual dictated text. Tests that reference removed OnboardingScreen locations must follow its new owner without weakening predicates.

## 7. Failures
Download pause/offline/error: saved partials, visible pause/wait/retry. Model removal: downloader. Microphone denied: explain/retry/system settings. Accessibility allowed but unbound: connecting/retry, not false Granted. Practice busy: no new take, explain. Cancel/no speech/error: prior draft retained, no success. Receiver lost: History preserves output; no external insertion for practice. Video error: static original poster and usable Get Started. PiP unsupported: written guide, return-and-refresh.

## 8. Signals and 9. fallback
Use existing ModelHealth, WorkInfo and verified readiness; never parse display text. Use AutoPasteAvailability exhaustive states. Practice success requires matching accepted token and a nonblank finalized result; typed text alone never satisfies it. No model download or permission flag is a dictation success. User-generated practice text is never logged. Default visual animations respect disabled system animations and lifecycle.

## 10. Changes
AppShell extraction; focused onboarding UI/ViewModel/media files; AppPreferences versioned steps; narrow ModelDeliveryWorker setup admission/network parameters; a narrow token/receiver practice contract in session service and helper; internal Accessibility guide activity/manifest; approved media assets; behavioral tests and existing affected drift guards.

## 11. Validation
Product-outcome unit cases for step gating, request ownership/terminal results and missing/paused downloads. Reverting predicates must fail these cases. Existing full unit suite with fresh execution. Compile app and instrumented UI tests. Independent code review before hardware. Physical phone: welcome/video, pause/resume, permissions/return, real dictation into practice twice, finish direct History. Do not run connectedAndroidTest on daily phone because it uninstalls data. Do not wipe its models to stage empty storage; use isolated installation/emulator for model delivery mechanics and name remaining physical clean-install coverage honestly.

## 12. Blast radius and rollback
UI, onboarding preferences, worker admission and explicit practice delivery seam. Normal audio/model engines and ordinary insertion guards remain intact. Rollback is reverting onboarding commits; keep integration/native baseline. Play upload is separate from source completion and requires a correctly signed build.

## 13. Criteria and 14. questions
The phone visibly matches approved four-stage mock and uses real states/output. No extra completion page or mandatory mode tutorial. Exact device permission-guide behavior and clean-install performance require observation, not assumptions. User has authorized implementing this mock; no additional product approval is pending for this bounded scope.

## Coverage dispositions
- Skip practice completes onboarding and opens History directly; disabled while a take is active. Finish requires a genuine completed take, even if its draft is later edited.
- Practice owns a TextFieldValue draft and selection. Freeze the insertion range for a take, disable editing while active, merge one matched result at that range, then re-enable editing. Second take preserves the first result. Deduplicate terminal results by token; never replay on rotation.
- Back out of practice while active first sends token-scoped cancellation; retain text. Closing the activity clears the ViewModel and cancels only its active request. System recording controls remain usable for service-owned work. After process death, no active state is restored as success.
- Explicit pause and mobile-data choice survive app restart in preferences/control stores. Insufficient storage exposes the worker error and retry, never false progress. No missing file is downloaded silently after a user pause.
- Closing PiP leaves Settings usable; returning without permission stays on permissions with retry. Rotation recreates or retains guide safely; permission already on but unbound stays connecting/retry. Successful binding dismisses guide. No setting is changed by the guide itself.
- Both onboarding and the existing permissions/settings handoff route through the same prominent disclosure and affirmative consent. The guide is an optional helper after consent, never a replacement for disclosure.

## Grounded review dispositions
- Practice publication preserves clipboard loss prevention whenever History persistence fails, even if ResultReceiver.send returns normally; receiver delivery is not proof the user saw the text. Successful History remains durable when the UI is gone. No practice branch attempts external Accessibility insertion.
- WorkManager queued constraints are represented as waiting for Wi-Fi or sufficient storage where platform observations establish that condition, otherwise Waiting to download. Do not claim a worker error before execution. Persist the cellular choice and pass it into every setup retry/resume; normal model-card actions retain their existing Wi-Fi behavior.
