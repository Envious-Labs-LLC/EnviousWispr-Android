# Issue #217 — The paste service delegates targeting, insertion and the bubble — 2026-09-23

GitHub issue: `#217`. Tier: MEDIUM (one service, the insertion path; behaviour unchanged). Status: DRAFT.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. The change moves code; no parity row changes status.

**Hardware UAT:** Y. Insertion is the heart. Success for a person: they tap into Gmail's compose field, the lips bubble appears; they dictate a sentence and it lands once in that field; they dictate again at once and it lands once; they switch to another app and back and the bubble follows the field. Run on the emulator through `wispr_eyes`; the founder's phone pass is queued with #115, #161 and #212 to #216 (his 2026-09-21 instruction excludes the phone from the audit queue).

## Preface — User Rubric

User Rubric: N/A — a statement-for-statement move of the paste service's code into three collaborators; every insertion and every bubble decision takes the same path in the same order on the same thread.

---

## 0. TL;DR

`paste/PasteAccessibilityService.kt` (1,490 lines) owns the Android service, the editor targeting, the insertion attempt with its clipboard and History completion, and the floating bubble. REF-02 of the 2026-09-22 senior audit asks it to keep the lifecycle callbacks, `AccessibilityServiceInfo`, input-method creation and binding publication, and delegate the rest to `EditorTargetTracker` (proposed), `AccessibilityInsertionRunner` (proposed) and `AccessibilityBubbleHost` (proposed), each with one idempotent `close`. Evidence: the existing pure-logic rows unchanged, a new Drift Guard red before and green after, every source-reading row re-pointed, and emulator insertion plus bubble passes.

## 1. Problem

Audit REF-02 (Code hygiene, confidence High) cites `PasteAccessibilityService.kt:L31-L58`, `L240-L342` and `L693-L1414` against `architecture-rules.md` RULE: keep-central-types-thin. One class holds three domains' state: the remembered and pinned editors (`lastTarget`, `pinnedTarget`), the pending insertion and its retry (`pendingInsertion`, `retryScheduled`, `retryRunnable`), and the bubble (`recordingOverlay`, `lookScope`, `connectedInputs`, `inputDevicePick`, `audioDeviceCallback`, five discovery-retry fields). `wc -l` gives 1490. The field `transcriptRepository` is never read (`grep -n "transcriptRepository"` finds only its declaration), and three imports are unused (`DictationNotificationController`, `RecordingOverlayState`, `TranscriptRepository`).

## 2. Goals & non-goals

### 2.1 Goals
1. `PasteAccessibilityService` keeps: `onServiceConnected`, `onAccessibilityEvent` (dispatch only), `onInterrupt`, `onUnbind`, `onDestroy`, `configureEventMode` and `BASE_EVENT_TYPES`, `onCreateInputMethod` and `editorInputSession`, the companion (`instance`, `boundState`, `isBound`, `publishBinding`, and the statics `pasteWhenTargetReturns`, `pinTargetForDictation`, `releasePinnedTarget`, `pinnedFieldId`, `windowTreeXml`, `refreshBubble`, each resolving `instance` and hopping to main as today; `windowTreeXml` remains a service-owned `callOnMain` read of `windows` through `WindowTreeXml`; the other statics delegate as specified; `refreshBubble` stays asynchronous: resolve `instance`, then `mainHandler.post { bubble.refresh() }`, never `callOnMain`), `mainHandler`, `mainCall`, `callOnMain`, the stop marker (`reportPreviousStop`, `markStopWasClean`, `writeStopMarker`, `lifecyclePreferences`, `historyScope`), and `startDictationFromBubble` (the bubble's route into the session owner; `RecordingAccessibilityOverlay` holds the service).
2. `EditorTargetTracker` owns `TargetSnapshot`, `TargetToken`, `lastTarget`, `pinnedTarget`, `rememberEditableTarget`, `pinTarget`, `findFocusedEditableTarget` (both overloads), `isSafeFocusedEditor`, `isInFocusedWindow`, `withPinnedNode`, `findPinnedWindowRoot`, `matchesPinnedWindow`, `matchesPinnedTarget`, `clearTarget`, `clearPinnedTarget`, `fieldKey`/`FieldKey`, and the bubble's two target questions (the remembered field's key, and the revalidate-and-discover step of `revalidateBubbleField`).
3. `AccessibilityInsertionRunner` owns `PendingInsertion`, `pendingInsertion`, the retry (`retryScheduled`, `retryRunnable`, `scheduleRetry`, `RETRY_INTERVAL_MS`, `INSERTION_TIMEOUT_MS`), `requestInsertion`, `tryPendingInsertion`, `finish`, `logOutcome`, `ServiceEditor`, `snapshotOf`, `isSensitive`, the clipboard functions (`restorePreviousClipboardIfSafe`, `keepTranscriptOnClipboard`, `writeTranscriptClipboard`, `ClipboardOwner`, `clipboardOwner`), `finalizeInsertion`, `recordAndAnnounce` and `performResultHaptic`.
4. `AccessibilityBubbleHost` owns `recordingOverlay`, `bubblePositionStore` (its loads and saves stay on the service's `historyScope`, passed in; `bubble.close()` never cancels it), `lookScope`, `connectedInputs`, `inputDevicePick`, `audioDeviceCallback`, `readInputsAndApply`, `applyEarbuds`, `updateBubbleFromEvent`, `discoveryWarranted`, `revalidateBubbleField`, the discovery retry (`DISCOVERY_RETRY_DELAYS_MS`, the five fields, `discoverFieldWithRetry`, `scheduleDiscoveryRetry`, `cancelDiscoveryRetries`), `focusedWindowId`, `isOwnOverlayWindow` and `dockedKeyboardTop`.
5. Each collaborator has exactly one idempotent `close()`, called from `onDestroy`.
6. Behaviour unchanged: same thread (all main), same order of every framework call and every History, clipboard, Toast and telemetry side effect.

### 2.2 Non-goals
- No change to `InsertionAttempt`, `EditorInputSession`, `MainThreadHandoff`, `RecordingAccessibilityOverlay` or `OwnFieldAdmission`.
- The companion statics keep their names and signatures, so `InsertionGateway`, `OnboardingViewModel` and the three debug receivers do not change.
- No manifest or accessibility-config change.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- Events: framework → `onAccessibilityEvent` (main) → today `rememberEditableTarget`, `updateBubbleFromEvent`, and the pending retry. After: `tracker.rememberEditableTarget(event)`, `bubble.onEvent(event, remembered)`, then `runner.onEvent(eventPackage)` with the same package test. Same thread, same order.
- Pin: `InsertionGateway.pinTargetForDictation` → companion → `callOnMain` → today `pinTarget()`; after `tracker.pinTarget(insertionPending = runner.isPending)`, the only cross-home read `pinTarget` makes (`:789`).
- Insertion: `InsertionGateway.pasteWhenTargetReturns` → companion → `callOnMain` → `runner.requestInsertion(...)`; the runner reads the pin through the tracker and toggles content-change events through a `setContentChanges` callback the service supplies (`configureEventMode` stays the service's, the only writer of `serviceInfo`).
- Completion: `finalizeInsertion` → `ModelBootstrapApplication.historyWrites(...)` (the application queue, #115) and `Telemetry`; `recordAndAnnounce` → clipboard, `FallbackAnnouncement`, `Toast.makeText(service, ...)`. Unchanged but for the Context now being a field.
- Bubble: overlay created in `onServiceConnected` once per instance, the look collect, the audio-device callback, the position load after `reportPreviousStop` in the same coroutine: `bubble.attach()` at the same point, and `bubble.restorePosition()` (suspend) inside the service's `historyScope` coroutine after `reportPreviousStop`, so the order holds.

### 2. Existing authority
`MainThreadHandoff` (the main-thread hop), `InsertionAttempt` (the attempt), `OwnFieldAdmission` (which packages may be searched), `ModelBootstrapApplication.historyWrites` (the History queue) are reused unchanged. `grep -rn "EditorTargetTracker\|AccessibilityInsertionRunner\|AccessibilityBubbleHost" app/src` finds nothing: new authorities, named by the audit.

### 3. Prior attempts and live direction
#186 split the session owner; #216 split it further (the same pattern, merged 2026-09-23). #192 made the owner the only pin caller: `SessionOwnerShapeTest.onlyTheOwnerPinsTheTarget` requires the pin-call file set `DictationSessionCoordinator.kt`, `InsertionGateway.kt`, `PasteAccessibilityService.kt`; the companion keeps its one `pinTarget(` call (now `service.tracker.pinTarget(`), and the tracker declares `pinTarget` but never calls it, so the set holds. #141 (the input method and discovery retry), #171 (bubble colour), #135 (the bubble) are carried unchanged.

### 4. Boundaries
All work is on the service's main thread except the stop-marker disk work and the position load/save (IO scopes) and the History queue (the application's worker). Every collaborator is constructed in the service's property initialisers with the service and its `mainHandler`; none outlives the instance. The companion's `instance` stays the one liveness authority.

### 5. High-risk premises
- The accessibility service cannot run in a JVM test (it is a framework `AccessibilityService`), so the evidence for unchanged behaviour is the source rows plus the emulator; the pure logic (`InsertionAttempt`, `AccessibilityInsertionRules`, `MainThreadHandoff`, `OwnFieldAdmission`, `WindowTreeXml`) keeps its own JVM rows unchanged.
- Five test files read the service's text (inventory: `AutoPasteWiringTest`, `InsertionOutcomeMessagesTest`, `OwnFieldAdmissionTest`, `LipsBubbleWiringTest`, `SessionOwnerShapeTest`); `AutoPasteLivenessExportTest` reflects on `boundState` and `publishBinding`, which stay.

## 3. Design

Construction, in the service:
```kotlin
private val tracker = EditorTargetTracker(this)
private val runner = AccessibilityInsertionRunner(this, mainHandler, tracker, setContentChanges = ::configureEventMode, inputSession = { editorInputSession })
private val bubble = AccessibilityBubbleHost(this, mainHandler, tracker, historyScope)
```
Lifecycle, in the order of today's statements:
- `onServiceConnected`: `publishBinding(this)`; `configureEventMode(false)`; `bubble.attach()` (the whole `if (recordingOverlay == null)` block, once per instance); log; `bubble.cancelDiscoveryRetries()` then the generation-guarded post of `bubble.discoverFieldWithRetry()` (`bubble.discoverOnConnect()`, proposed, carries the guard); `historyScope.launch { reportPreviousStop(); bubble.restorePosition() }`.
- `onInterrupt`: `runner.abandon(ServiceFallbackReason.SERVICE_INTERRUPTED, InsertionOutcomeLine.Outcome.INTERRUPTED)` (the pending outcome, announce, clear, and the retry removal); `tracker.clearPinnedTarget()`; `bubble.cancelDiscoveryRetries()`.
- `onUnbind`: unchanged but `bubble.cancelDiscoveryRetries()`.
- `onDestroy`: `publishBinding(null)` first; `bubble.close()` (look scope, audio callback, overlay stop, discovery retries; NOT `historyScope`); `runner.close()` (retry removal, then the pending `DESTROYED` outcome); `historyScope.cancel()` after the runner, as today (the position saves stay on it); `tracker.close()` (pin then remembered target); `markStopWasClean()` last; `super.onDestroy()`.
Declared order change: today `onDestroy` removes the insertion retry before cancelling discovery retries and `onInterrupt` clears the pin before removing the retry; both pairs are in-memory main-thread steps with no reader in between, so the order within each pair is not observable.

**Ordering contracts (grounded round 1).**
- In `onUnbind` and `onDestroy`, retain `if (instance === this) publishBinding(null)` at its current position. Drift Guard (e) checks the guard, not only the call.
- `restorePosition()` loads on `historyScope`, then posts to `mainHandler` and checks that the overlay still exists before `setPosition`.
- `finish`: clear pending, capture pinned package, set `pending.targetPackage`, clear pin, disable content-change events, log outcome. `abandon` and `close`: set `pending.targetPackage` from the live pin, log, copy/announce, then clear pending. The service clears the pin only afterward. `onInterrupt` keeps its warning log first.
- `refreshFocusedField(discover)` checks the remembered node and focused window first. On discovery, find and log the candidate; only if found, recycle the old target and adopt the new one. Return the resulting field key or absence. The bubble then calls `fieldActivated` or `fieldLost`, followed by `keyboardBounds`, in that order.
- Give each collaborator its own private `TAG = "PasteService"` so existing log tags and messages stay unchanged.
- Kept as-is: `requestInsertion`'s refusal order, retry removal, pending assignment, event-mask change, log and immediate attempt; each `tryPendingInsertion` branch's `finish` before clipboard restore or fallback; `recordAndAnnounce`'s clipboard result before History/telemetry and Toast; the bubble's event branch order and the discovery retry's generation check, coalescing, absolute deadline and cancellation.

Alternatives rejected: (a) moving the companion statics into the collaborators: the audit keeps binding publication in the service, and the statics ARE the published binding's API; (b) giving the discovery retry to the tracker: it runs only when the overlay exists and only lights the bubble.

## 3b. Ownership justification
Targeting lives on `EditorTargetTracker` because both the pin and the bubble ask it the same question (which editor is focused in the focused window); the alternative, leaving it in the service, is the finding. Insertion lives on `AccessibilityInsertionRunner` because the pending attempt, its clipboard and its outcome are one lifecycle that starts at a request and ends at one terminal outcome. The bubble lives on `AccessibilityBubbleHost` because every piece of it exists to show the overlay.

## 4. Contract deltas
New `internal` classes with one `close()` each. The service's public surface (`startDictationFromBubble`, the companion) is unchanged. `EditorTargetTracker.pinTarget(insertionPending)` replaces the private `pinTarget()`.

## 5. State and lifecycle audit

| Population | Enumeration |
|---|---|
| Service members | Per the helper inventory table: every member assigned in §2.1; removed as dead: `transcriptRepository` and the three unused imports. |
| State read by two homes | `pendingInsertion` has two cross-home readers: `pinTarget` and the companion's `releasePinnedTarget`. Keep `releasePinnedTarget` inside `callOnMain(Unit)` and clear the tracker's pin only when `!runner.isPending`. The companion's `pinnedFieldId` reads the tracker's pinned view id on main; `pinnedTarget` (read by the runner and the service's event dispatch: the tracker exposes a read-only pinned token, or equivalent package, window id and node-access methods, to the runner; token ownership and recycling stay in the tracker, and every current commit-eligibility, locate, paste and read check (`PasteAccessibilityService.kt:999-1163`) is preserved); `lastTarget` (read and written by the bubble's revalidate: `tracker.rememberedFieldKey()`, `tracker.refreshFocusedField(discover)`); `recordingOverlay` (the companion's `refreshBubble`: `bubble.refresh()`); `mainHandler`, `windows`, `packageName` (the service, passed or read through the service reference). |
| Teardown steps | onInterrupt: 6 steps; onUnbind: 3; onDestroy: 15. Each listed in §3 with its new owner. |
| Closes | Three `close()`: each safe to call twice (a `closed` flag in the bubble; null checks in the runner and the tracker). |

## 6. Consumer matrix

| Delta | Consumer | Change | Verified by |
|---|---|---|---|
| Code moves out of the service file | `AutoPasteWiringTest`, `InsertionOutcomeMessagesTest`, `OwnFieldAdmissionTest`, `LipsBubbleWiringTest`, `SessionOwnerShapeTest` | Rows re-pointed through a shared test reader `PasteSources` (proposed): `service`, `tracker`, `runner`, `bubble`, `all`. Every negative scan and every "exactly N" count reads `all`. Rows that pinned a service callback's text now pin the delegation call in the service AND the body in the collaborator. The two already-silent rows are repaired: `LipsBubbleWiringTest:241-243` (dead end marker) and `OwnFieldAdmissionTest:54-57` (unguarded, whole-file fallback). Declaration-order markers become body-end markers. Every source-row extraction fails when either its start or its end marker is missing (one guarded slice helper in `PasteSources`). For the audio row: the service delegates attachment once, and the bubble host registers, reads and unregisters the callback in order. | revert per row, §11 |
| Stale comments | `SessionFinalizer.kt`, `DictationSessionService.kt`, `HapticCue.kt`, `DictationTargetPin.kt`, `BubblePositionStore.kt` | Point to the new owner where they name a moved function. | grep |

## 7. Failure modes
None new. Every refusal, outcome line, History value, Toast and haptic keeps its trigger.

## 8. Signals
`not present in this change`: every log line moves with its code, same text (the insertion outcome line is read by `wispr_eyes.last_take`).

## 9. Fallbacks
Unchanged.

## 10. Files
New `paste/EditorTargetTracker.kt`, `paste/AccessibilityInsertionRunner.kt`, `paste/AccessibilityBubbleHost.kt`; `PasteAccessibilityService.kt` reduced; tests per §6 plus the Drift Guard; comments per §6; `.claude/knowledge/architecture.md` paste row updated in place (primary checkout).

## 11. Testing
1. New Drift Guard `PasteServiceShapeTest` (proposed), read as code only through `scripts/check-visibility.py --code-only`: (a) the service's code holds none of `pendingInsertion`, `pinnedTarget`, `lastTarget`, `recordingOverlay`, `setPrimaryClip`, `finalizeInsertionOutcome`, `Toast.makeText`, `registerAudioDeviceCallback`, `findFocus(`; (b) each collaborator declares exactly one `fun close()`; (c) the runner holds the one `Toast.makeText(`, the one `historyWrites(` enqueue and every `setPrimaryClip(`; the tracker holds every `findFocus(` and `pinnedTarget =`; the bubble holds the one `registerAudioDeviceCallback(` and the one `RecordingAccessibilityOverlay(`; (d) no collaborator touches `serviceInfo` or `publishBinding`; (e) the service's delegation is present and ordered: `onAccessibilityEvent` calls the tracker, then the bubble, then the runner; each companion static that owns collaborator work delegates to that collaborator; `windowTreeXml` remains a service-owned `callOnMain` read of `windows` through `WindowTreeXml`; `onDestroy` calls `if (instance === this) publishBinding(null)`, `bubble.close()`, `runner.close()`, `historyScope.cancel()`, `tracker.close()`, `markStopWasClean()` in that order. When it fails, the user sees nothing at once; a future edit is putting a domain back into the service. Revert: move `clearTarget` back into the service (a); add a second `close` (b); a `Toast.makeText` in the bubble (c); `serviceInfo =` in the runner (d); drop `tracker.close()` from `onDestroy` or swap `runner.close()` and `historyScope.cancel()` (e).
2. Existing JVM rows for the pure logic stay unedited.
3. Every rewritten source row gets a revert receipt.
4. Not tested in the JVM: the service itself (framework class); covered by the emulator.

### 11.1 Hardware UAT
- Subsystem: heart path (insertion) and limb (bubble).
- Recipe: `wispr_eyes.dictate_emulator` into Gmail compose, twice back to back (`route="COMMIT"`, the literal whole text); `debug_insert` into the same field; the bubble's presence read by `overlay()` on the focused field, after switching to the launcher (absent) and back (present).
- Restore: `restore()`.

## 12. Blast radius
`paste/` only, plus tests and five comments. Rollback: revert the squash commit.

## 13. Ship criteria
- [ ] Two dictations land once each in Gmail on the emulator; a debug insert lands; the bubble follows the field across an app switch.

## 14. Open questions
None.

## 15. Related
#186, #216 (the same pattern), #192, #141, #171, #135; audit REF-02.
