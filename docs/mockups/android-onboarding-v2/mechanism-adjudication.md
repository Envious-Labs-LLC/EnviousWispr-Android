# Real floating-button practice inside EnviousWispr

Scope: mechanism proposal only. No app code changed.
Source reads are limited to the files and service/overlay ranges in the brief.

## Grounding

- `PasteAccessibilityService.kt:600-640`: `rememberEditableTarget` rejects our package before inspecting the node; otherwise it saves the editable node, package, window and view ID.
- `PasteAccessibilityService.kt:370-400`: `revalidateBubbleField` refreshes/discovers the focused target, calls `fieldActivated` or `fieldLost`, and supplies keyboard bounds.
- `RecordingAccessibilityOverlay.kt:120-160`: field activation and loss control rendering; keyboard bounds are separate from the field key.
- `RecordingAccessibilityOverlay.kt:400-510`: taps mint a bubble request; a hold retains its own request for STOP/CANCEL and compact-pill identity. The start route tries the service, then a launcher fallback.
- `OnboardingViewModel.kt`: `startPractice` snapshots the draft, creates a UUID and ResultReceiver, and merges FINISHED text into Compose state. That path alone sets `practiceComplete` today.
- `PracticeDelivery.kt`: commands and receiver updates carry a practice token; terminal delivery is single-shot.
- `OnboardingScreen.kt`: the editable practice field uses view-model state, and Finish setup depends on practice completion and no active take.
- UNCHECKED: definitions of `isSafeFocusedEditor`, `findFocusedEditableTarget`, target pinning and insertion guards outside the allowed ranges; session-owner token parsing, launcher behavior and native completion signals. Do not assume changing one exclusion is sufficient.

## (a) Admit only the onboarding practice field to normal insertion

Changes:
- Give the practice field a stable accessibility identity and a lifecycle-scoped active-practice registration. Require our package, the registered field, focused window and active lesson; keep every other own-app field excluded.
- Apply that exception consistently to event capture, discovery, focus revalidation and the normal pin/insertion guards. Clear it on leaving practice, field loss or teardown.
- Keep the real overlay gestures and normal bubble request path. Let normal insertion write into the Compose field, with its edit callback persisting the resulting draft; stop receiver-based merging for this path.
- Replace the practice-only editing lock as needed so the normal insertion action can update Compose during a take.
- Observe this request’s recording/processing/terminal outcome and confirmed field insertion to drive lesson state. A correlated nonempty insertion unlocks Finish; manual typing alone must not.
Proves:
- Once implemented and exercised, practice can prove real overlay triggering, request ownership, target pinning, capture/transcription and normal insertion into this Compose editor.
- It does not prove Gmail or another app’s editor behavior, cross-app window transitions, or launcher fallback behavior.
One risk:
- An exception applied inconsistently across discovery and insertion can display a working bubble while rejecting its own target; scope the same field predicate through the whole path.

## (b) Route the real bubble to the existing practice receiver

Changes:
- Register and activate only the focused practice field as an overlay host, with keyboard bounds and lifecycle cleanup; the existing own-package exclusion still prevents automatic activation today.
- When that field is focused, have tap/hold start the view model’s practice-token plus ResultReceiver path instead of minting a bubble request. Ordinary fields retain normal bubble routing.
- Carry the practice token through check, X, hold release and hold cancellation using `PracticeDelivery.command`, not a bubble-token encoding.
- Adapt retained hold identity and compact-pill selection to the request kind, preserving early-release ordering and never stopping another request.
- Keep receiver FINISHED merging and completion bookkeeping; add A/B lesson state and the scoped short-tap guard for B.
Proves:
- Once implemented and exercised, practice can prove the real overlay view/gestures, recorder states and speech pipeline, plus receiver delivery into the practice box.
- It cannot prove accessibility pinning or normal paste delivery; those are bypassed even when words appear in the box.
One risk:
- Mixing practice and bubble token ownership can misroute a fast hold release or cancellation; keep one explicit request identity for every start and terminal command.

## Recommendation

Choose (a). The lesson promises the same interaction and insertion users will rely on in other apps; (a) covers more of that real path without a separate practice delivery route.
Use one narrow field exception, not a package-wide allowance. Correlate successful insertion with the take before unlocking Finish; preserve saved text on empty or interrupted results.
Keep the existing receiver only until the normal-path practice replaces it, not as a second writer into the same field.
The short-tap guard for B is a practice-only proposal; normal tap behavior elsewhere is unchanged.
All uninspected guards and completion signals above remain UNCHECKED implementation dependencies, not claims that this proposal already works.
