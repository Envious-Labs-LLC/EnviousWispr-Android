# Senior architecture audit, 2026-09-20

Rendered from `docs/audits/2026-09-20-senior-audit.json` (Codex gpt-5.6-sol, effort high, read-only, commit e6a5bf9). Prompt: `senior-audit-prompt.md`. Schema: `senior-audit-schema.json`. Regenerate this file from the JSON; never hand-edit it.

## Overall: C (76/100), confidence Medium

This is professional, senior-level engineering with serious debt, not AI slop, but it is not yet production-grade. Typed outcomes, first-wins concurrency controls, process isolation, verified models, defensive insertion, and privacy enforcement show sustained engineering judgment. The grade is capped by a credible wrong-editor insertion path, optional limbs that can prevent capture entirely, main-thread teardown waits, oversized central owners, and tests that sometimes prove log markers instead of the real user outcome.

*Confidence:* The dominant findings follow directly from current control flow and were re-cited from exact lines. Confidence stops at Medium because the sandbox was read-only, builds and device runs were prohibited, and Android lifecycle timing, binder behavior, and actual editor insertion require runtime proof.

## Reader guide

Start with the overall grade, then read REF-01 and the Architecture integrity dimension. That finding concerns a current heart-path risk: a stop command can change the editor selected for insertion before the session owner handles the command. Next read Concurrency discipline, Error handling, and Resource lifecycle; these explain why service teardown and optional settings work are not yet production-grade. The three risk dimensions are static assessments, not claims that the founder's phone has already shown the failure. Their falsifiability fields name the physical-phone or instrumentation test needed. Refactor targets are ranked by user impact, not by file size. The roadmap preserves that order: protect text delivery first, remove lifecycle blocking second, then split oversized owners and tighten compile-time rules. Strengths are intentional. They identify concurrency, privacy, model-integrity, and fallback patterns that should survive the refactors unchanged. Severity describes impact on the phone today; confidence describes certainty from source alone. Stage-two clean-install, download-from-empty, telemetry completeness, and reinstall recovery are excluded from the grade as instructed. No build, Gradle task, adb command, runtime test, or repository mutation was performed.

## Dimensions

| Dimension | Grade | Worst violation | Severity/Confidence |
|---|---|---|---|
| Architecture integrity | C | `app/src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt` L61-L79 | CRITICAL/High |
| Concurrency discipline | C | `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` L1887-L1904 | HIGH/High |
| Error handling and observability | C | `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` L564-L576 | HIGH/High |
| Testability | B | `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt` L169-L197 | MEDIUM/High |
| Code hygiene and maintainability | C | `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt` L143-L165 | MEDIUM/High |
| API surface | C | `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt` L122-L155 | LOW/High |
| Performance and latency | Medium Risk | `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` L869-L880 | MEDIUM/High |
| Resource lifecycle | High Risk | `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt` L1483-L1502 | HIGH/High |
| Security and privacy | Medium Risk | `app/src/main/java/com/envi/wispr/debug/DebugLogger.kt` L142-L148 | MEDIUM/Medium |

### Architecture integrity: C

All six entry surfaces ultimately command one session service, process assignments match the manifest, and TakeArbiter is a strong single-owner primitive. However, VoiceInputActivity mutates the global insertion target before the owner interprets ACTION_TOGGLE. During an active take, that toggle means stop, so the surface can replace the original target with the currently focused editor. The session service is also 1,937 lines and owns policy loading, binding, state transitions, History, notifications, haptics, insertion, telemetry, and teardown. AppShell is 1,013 lines with a 44-argument root composable. These are the declared extraction targets and have exceeded a thin-adapter role.

**Best example:** `app/src/main/java/com/envi/wispr/ui/TakeArbiter.kt` L53-L78. It makes terminal ownership explicit, supports reservation before an outcome is known, permits teardown to revoke work, and performs the callback outside the lock.

**Worst violation:** `app/src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt` L61-L79 [CRITICAL/High]. Rule: architecture-rules.md RULE: one-owner-for-the-session; architecture-rules.md RULE: insertion-fails-safe-never-silently

```
        val action = when {
            intent.getBooleanExtra(EXTRA_CANCEL, false) -> DictationSessionService.ACTION_CANCEL
            intent.getBooleanExtra(EXTRA_STOP, false) -> DictationSessionService.ACTION_STOP
            intent.getBooleanExtra(EXTRA_START, false) -> DictationSessionService.ACTION_START
            intent.getBooleanExtra(EXTRA_TOGGLE, false) -> DictationSessionService.ACTION_TOGGLE
            else -> DictationSessionService.ACTION_TOGGLE
        }
        if (action == DictationSessionService.ACTION_START ||
            action == DictationSessionService.ACTION_TOGGLE
        ) {
            // Pinned here because this window is closing and the user's editor is still focused.
            // The ANSWER is deliberately not carried: the session pins again in `beginSession` and
            // that later value is the one every announcement is judged against. Keeping this one
            // too would put two records of one fact in two components with different lifetimes,
            // and this one dies first.
            PasteAccessibilityService.pinTargetForDictation()
        }
        val trigger = triggerOf(intent.action, intent.getStringExtra(EXTRA_TRIGGER_SOURCE))
        runCatching { DictationSessionService.sendCommand(this, action, intent.getStringExtra(EXTRA_REQUEST), trigger) }
```

- What the user sees: A user starts dictating in editor A, moves to editor B, and stops with the Samsung side button. The activity can re-pin B, allowing the words intended for A to be inserted into B.
- Why this confidence: ACTION_TOGGLE is pinned before the owner decides whether it starts or stops. An active-session toggle follows the stop branch and never calls beginSession to restore the original target.
- How to prove it wrong: On the physical phone, start a take in editor A, switch focus to editor B, stop through android.intent.action.ASSIST, and assert that the transcript lands only in A or falls back visibly to clipboard. Any insertion into B confirms the finding; consistent delivery to A with source tracing would disprove it.
- Interleaving: 1. Take A starts and the owner pins editor A. 2. While recording, the user focuses editor B. 3. The side button launches VoiceInputActivity with the default ACTION_TOGGLE behavior. 4. VoiceInputActivity calls pinTargetForDictation before sending the command, replacing A with B. 5. DictationSessionService receives ACTION_TOGGLE while RECORDING and runs stopAndTranscribe, not beginSession. 6. Publication calls pasteWhenTargetReturns using the accessibility service's now-current pin, B.
- Refactor targets: REF-01, REF-02, REF-03

### Concurrency discipline: C

The repository contains unusually good first-wins arbiters, stale-token rejection, lock-free audio rings, and deterministic race tests. The dominant violation is lifecycle blocking: DictationSessionService runs runBlocking from Service.onDestroy, awaits a deferred Room insert, then cancels and joins the service job. Android service lifecycle callbacks run on the main thread. A slow or non-cooperative child can therefore stall process teardown and UI dispatch. PasteAccessibilityService repeats the same pattern, and AudioCaptureService joins its capture thread for up to two seconds.

**Best example:** `app/src/main/java/com/envi/wispr/paste/MainThreadHandoff.kt` L47-L73. The caller and body race through one atomic claim, eliminating the timeout-then-act interleaving that could insert text after a reported failure.

**Worst violation:** `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` L1887-L1904 [HIGH/High]. Rule: kotlin-patterns.md RULE: never-block-a-binder-or-ui-thread; kotlin-patterns.md RULE: structured-concurrency-with-a-real-scope

```
            runCatching {
                runBlocking(Dispatchers.IO) {
                    draftId.get().takeIf { it > 0L }
                        ?: draftCreation?.await()?.takeIf { it > 0L }
                        ?: 0L
                }
            }.onFailure { error ->
                DebugLogger.warn(TAG, "Unable to resolve interrupted history row during teardown: ${error.message}")
            }.getOrDefault(0L)
        } else {
            0L
        }
        // Stop any in-flight finalization before writing the terminal teardown state.
        // This keeps a late polish callback from changing an interrupted row back to ready.
        runBlocking(Dispatchers.IO) {
            serviceJob.cancel()
            serviceJob.join()
        }
```

- What the user sees: Stopping or destroying a session can freeze main-thread work, delay the next trigger, or contribute to an ANR while Room, model, binder, or cancellation work finishes.
- Why this confidence: Service.onDestroy is a main-thread callback, and both runBlocking calls synchronously wait for asynchronous work.
- How to prove it wrong: In an instrumentation test, delay draftCreation or a serviceScope child for five seconds, destroy the service, and run a main-looper heartbeat. If heartbeats continue and onDestroy returns without waiting, the finding is wrong.
- Interleaving: 1. Android calls DictationSessionService.onDestroy on the main looper. 2. A serviceScope child is awaiting Room or another non-immediate operation. 3. onDestroy enters runBlocking and awaits draftCreation or serviceJob.join. 4. The child does not finish immediately after cancellation. 5. The main looper remains blocked and cannot dispatch lifecycle, command, toast, or UI work until the child returns.
- Refactor targets: REF-04, REF-03

### Error handling and observability: C

Polish, cleanup, vocabulary, insertion, and telemetry generally return typed outcomes and preserve the last good text. The main exception is before capture: the session waits up to ten seconds for cleanup settings and vocabulary, both declared limbs, then terminates the entire take if either readiness signal never completes. Their collectors catch errors without completing the signals, making the failure path reachable. This turns optional enhancement storage into a hard dependency of trigger and capture.

**Best example:** `app/src/main/java/com/envi/wispr/cleanup/PolishPipeline.kt` L37-L60. Every optional exit returns text plus a closed outcome, and rejected or failed model output preserves deterministic cleanup.

**Worst violation:** `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` L564-L576 [HIGH/High]. Rule: architecture-rules.md FACT: heart-and-limbs; kotlin-patterns.md RULE: fail-open-to-the-last-good-text

```
        serviceScope.launch {
            val ready = withTimeoutOrNull(10_000L) {
                cleanupPreferencesReady.await()
                structuredTermsReady.await()
                true
            } == true
            if (!ready) {
                withContext(Dispatchers.Main.immediate) {
                    if (state.get() == SessionState.STARTING) {
                        showError(TerminalReason.SETTINGS_UNAVAILABLE)
                    }
                }
                return@launch
```

- What the user sees: A settings or custom-term read failure produces ten seconds of starting state followed by an error. The microphone never begins capturing, so the user gets no text.
- Why this confidence: Cleanup and vocabulary are declared limbs. Their readiness is awaited before bindPipelineServices, and timeout calls showError rather than continuing with the initialized defaults.
- How to prove it wrong: Inject an AppPreferences flow error and a CustomTermRepository observation error, issue ACTION_START, and assert AudioCaptureService starts promptly with default cleanup and an empty user vocabulary. If current code captures successfully, the finding is wrong.
- Interleaving: 1. onCreate launches the settings and vocabulary collectors. 2. Either collector throws before completing its readiness deferred; its catch logs and the coroutine ends. 3. beginSession enters STARTING and awaits both deferred values. 4. Ten seconds expire. 5. showError ends the session before bindPipelineServices or audio capture.
- Refactor targets: REF-02, REF-03

### Testability: B

Pure policy types are common, ViewModel dependencies are injectable, concurrency races are staged with latches, and tests often declare whether they protect product behavior or drift. The suite still has a weak heart-path boundary: launcherRecordsPhonePlaybackAndReachesClipboardStep sleeps, polls logcat, and passes when it sees a handoff marker. It does not prove that text reached a third-party editor exactly once. DictationSessionService also constructs repositories, database, provider configuration, language detection, handlers, threads, and binders internally, preventing fast direct testing of the owner as one unit.

**Best example:** `app/src/test/java/com/envi/wispr/paste/MainThreadHandoffTest.kt` L14-L22. The suite names the user-visible failure and deterministically stages both interleavings rather than using timing as evidence.

**Worst violation:** `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt` L169-L197 [MEDIUM/High]. Rule: testing-philosophy.md RULE: never-guess-when-the-subject-is-finished; testing-philosophy.md RULE: the-heart-crosses-a-real-boundary-at-least-once

```
    fun launcherRecordsPhonePlaybackAndReachesClipboardStep() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        shell("logcat -c")

        val launchIntent = Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        Thread.sleep(2_000)

        playFixtureThroughSpeaker()

        context.startActivity(
            Intent(context, com.envi.wispr.ui.VoiceInputActivity::class.java)
                .putExtra(com.envi.wispr.ui.VoiceInputActivity.EXTRA_STOP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        val deadline = System.currentTimeMillis() + 30_000
        var logs = ""
        while (System.currentTimeMillis() < deadline) {
            logs = shell("logcat -d -v brief -s DictationSession:I AsrService:I PolishService:I AudioCapture:I '*:S'")
            if (logs.contains("Auto-insert handed") || logs.contains("transcript kept on clipboard")) break
            Thread.sleep(500)
        }

        assertTrue("Launcher path did not reach S1: $logs", logs.contains("Polish result received (S1-mini by Superwhisper (NPU)"))
        assertTrue(
            "Launcher path did not reach the clipboard/paste step: $logs",
            logs.contains("Auto-insert handed") || logs.contains("transcript kept on clipboard"),
        )
```

- What the user sees: Insertion can stop writing text, write twice, or write into the wrong editor while this test stays green as long as the same handoff log remains.
- Why this confidence: The test explicitly waits by elapsed time and asserts diagnostic strings rather than editor contents or exactly-once delivery.
- How to prove it wrong: Run this test against a temporary build where pasteWhenTargetReturns logs the existing handoff line but does not perform the write. If the test fails, this finding is wrong; if it passes, the coverage gap is demonstrated.
- Refactor targets: REF-09, REF-03

### Code hygiene and maintainability: C

The repository has strong naming around closed outcomes and many comments explain actual mechanisms. Maintainability is nevertheless constrained by several oversized files: AudioCaptureService is 1,505 lines, DictationSessionService 1,937, AppShell 1,013, AppViewModel 836, PasteAccessibilityService 1,488, and ProviderPolishClient 1,323. ProviderPolishClient calls itself small while combining retries, cancellation, model discovery, key checks, HTTP transport, five wire formats, response classification, JSON construction, and a custom JSON parser. This is not generated-looking code, but its change surface is too broad for reliable ownership.

**Best example:** `app/src/main/java/com/envi/wispr/audio/CaptureEnding.kt` L48-L62. A closed domain type owns wire decoding, labels, and the success decision with exhaustive whens and no boolean flag bag.

**Worst violation:** `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt` L143-L165 [MEDIUM/High]. Rule: Single Responsibility Principle; architecture-rules.md RULE: keep-central-types-thin

```
/**
 * Small platform-only provider client. It deliberately does not log request bodies, response
 * bodies, endpoint credentials, or API keys. The caller can keep the raw transcript when every
 * provider fails, without this layer ever persisting or exposing it.
 */
class ProviderPolishClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val overallTimeoutMs: Int = DEFAULT_OVERALL_TIMEOUT_MS,
    /** Test-only endpoint overrides allow local HttpServer coverage without changing cloud origins. */
    private val endpointOverrides: Map<Provider, String> = emptyMap(),
    /** Test-only overrides for the key-check (model-list) endpoints, the same shape as [endpointOverrides]. */
    private val keyCheckOverrides: Map<Provider, String> = emptyMap(),
    /** Where the content-free diagnostics go; a JVM test passes no-ops so android.util.Log is never touched. */
    private val logInfo: (String) -> Unit = { DebugLogger.log(TAG, it) },
    private val logWarn: (String) -> Unit = { DebugLogger.warn(TAG, it) },
    /** The live model list's bounds (#84); a test shortens them. */
    private val discoveryTimeoutMs: Int = DISCOVERY_TIMEOUT_MS,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
    /** The retry policy's bounds (#4), the Mac's two retries at 1 s then 3 s; a test shortens or disables them. */
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val maxRetries: Int = MAX_RETRIES,
) : ProviderKeyChecker, ProviderModelDiscoverer {
```

- What the user sees: A provider API change risks unrelated key validation, discovery, retry, parsing, and polish behavior in one review unit; ownership and regression scope remain unclear.
- Why this confidence: The inventory measures 1,323 lines, and the file directly implements transport, retries, discovery, request encoding, response decoding, and JSON parsing.
- How to prove it wrong: Run `rg -n "fun polish|override fun check|override fun discoverModels|executeRequest|requestPlan|class JsonParser" app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt`. If those responsibilities already delegate to separately owned production types, this finding is wrong.
- Refactor targets: REF-06, REF-11, REF-07

### API surface: C

The six AIDL interfaces preserve transaction order, legacy methods, typed outcomes, file-path audio transfer, and small parcelables. Kotlin visibility is much weaker. App-only UI state, ViewModels, repositories, services, provider clients, model types, and helpers frequently rely on Kotlin's public default even though nothing crosses a Gradle module. The AppViewModel file alone publicly exposes its aggregate state and ViewModel while its collaborators are all app-internal. This expands accidental coupling and makes future extraction harder.

**Best example:** `app/src/main/aidl/com/envi/wispr/polish/IPolishService.aidl` L6-L12. It preserves installed-client transaction compatibility and documents why apparently unused transactions cannot be removed.

**Worst violation:** `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt` L122-L155 [LOW/High]. Rule: architecture-rules.md RULE: minimize-visibility; kotlin-patterns.md RULE: internal-is-the-right-default-inside-app

```
data class EnviousWisprUiState(
    val loading: Boolean = true,
    val preferences: AppPreferencesState = AppPreferencesState(),
    val readiness: AppReadiness = AppReadiness(),
    val allCustomTerms: List<CustomTermRecord> = emptyList(),
    val customTerms: List<CustomTermRecord> = emptyList(),
    val customTermTotalCount: Int = 0,
    val customTermSearch: String = "",
    val customTermMessage: String = "",
    val customTermError: String? = null,
    val autoPaste: AutoPasteAvailability = AutoPasteReadiness.initial,
    val history: List<TranscriptEntity> = emptyList(),
    val historyTotalCount: Int = 0,
    val historySearch: String = "",
    val historyError: String? = null,
    val providerSettings: ProviderSettingsUiState = ProviderSettingsUiState(),
) {
    val shouldShowOnboarding: Boolean
        get() = !loading &&
            !preferences.onboardingComplete &&
            !preferences.onboardingDismissed
}

class EnviousWisprViewModel(
    private val appPreferences: AppPreferences,
    private val repository: TranscriptRepository,
    private val customTermRepository: CustomTermRepository,
    private val providerRepository: ProviderConfigurationRepository,
    private val appContext: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The live model list (#84): the discoverer and the per-provider cache, both replaceable by a test. */
    private val discoverer: ProviderModelDiscoverer = ProviderPolishClient(),
    private val modelCache: ModelListCache = ModelListCache(appContext),
) : ViewModel() {
```

- What the user sees: Developers can couple unrelated app code to implementation types that were never intended as contracts, increasing the cost and blast radius of later package or module extraction.
- Why this confidence: Neither type crosses a Gradle module, yet both use Kotlin's public default. The same pattern recurs across app packages.
- How to prove it wrong: Mark the app-only declarations internal and run `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`. Any required cross-module consumer would disprove the specific visibility claim.
- Refactor targets: REF-08, REF-03, REF-06

### Performance and latency: Medium Risk

The audio hot path preallocates buffers, uses an SPSC ring, moves VAD and spectrum work off capture, and releases heavy processes after use. The live spectrum still crosses process boundaries by polling: a dedicated thread performs a synchronous binder getter every 33 ms, and the audio service locks and copies a FloatArray for each request. This contradicts the project's pushed-level rule and creates about 30 binder transactions, array copies, and state publications per second of recording. Static reading cannot establish whether this is material on the S26 Ultra, but the mechanism is real.

**Best example:** `app/src/main/java/com/envi/wispr/audio/BlockRing.kt` L47-L62. The producer never waits, allocates a slot, logs, takes a contended lock, or calls binder; overload drops the optional limb instead of delaying capture.

**Worst violation:** `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` L869-L880 [MEDIUM/High]. Rule: architecture-rules.md RULE: no-idle-cost; architecture-rules.md RULE: protect-audio-asr-stability

```
    private fun startMeter() {
        val takeSerial = RecordingOverlayState.snapshots.value.takeSerial
        runCatching {
            Thread({
                while (state.get() == SessionState.RECORDING) {
                    val service = audioService ?: break
                    val bands = runCatching { service.spectrumBands }.getOrElse { RecordingOverlayState.NO_BANDS }
                    if (RecordingOverlayState.snapshots.value.takeSerial != takeSerial) break
                    RecordingOverlayState.updateBands(takeSerial, bands)
                    Thread.sleep(METER_INTERVAL_MS)
                }
            }, "DictationMeterThread").start()
```

- What the user sees: Long recordings spend avoidable CPU, binder, allocation, and wakeup budget on the visual limb, potentially increasing heat, battery use, or contention near the capture path.
- Why this confidence: The code explicitly performs the synchronous AIDL getter every 33 ms while recording. Runtime impact, not mechanism, remains unmeasured.
- How to prove it wrong: Capture a Perfetto trace and allocation profile for a 60-second recording. Compare binder calls, allocations, CPU time, dropped audio, and power against an appended push-callback implementation. No measurable difference would lower this risk.
- Refactor targets: REF-05, REF-11

### Resource lifecycle: High Risk

Services generally unbind, cancel registries, close models, release AudioRecord, remove callbacks, and stop foreground state deliberately. AudioCaptureService nevertheless blocks its main-thread onDestroy for up to two seconds waiting for the capture thread. DictationSessionService and PasteAccessibilityService also block teardown on coroutine work. Whether Android reaches these slow paths during ordinary founder use is runtime-dependent, but the worst case is coded into lifecycle callbacks and is large enough to affect ANR and restart behavior.

**Best example:** `app/src/main/java/com/envi/wispr/polish/PolishService.kt` L420-L441. It distinguishes healthy shutdown from a potentially wedged native runtime, cancels request ownership, and never reuses a poisoned engine.

**Worst violation:** `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt` L1483-L1502 [HIGH/High]. Rule: kotlin-patterns.md RULE: never-block-a-binder-or-ui-thread; Android lifecycle responsiveness principle

```
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) {
            thread.join(2_000L)
            if (thread.isAlive) {
                // The capture thread is the sole owner of AudioRecord and the file. Do not
                // close either resource here after the bounded wait. Process termination of
                // the isolated :audio service will reclaim them without an ANR-length wait.
                DebugLogger.warn(TAG, "Capture thread did not finish during service teardown")
            }
        }
        if (captureThread?.isAlive != true) {
            captureThread = null
            synchronized(sessionLock) {
                isRecording.set(false)
            }
        }
        // A hold that slipped in between the flag and the join is ended here, before its expiry dies.
        synchronized(sessionLock) { warmHold?.end(WarmHold.END_DESTROYED) }
        // After the join: the capture thread's cleanup removed its listener; nothing else posts here.
        routeThread.quitSafely()
```

- What the user sees: If AudioRecord.read or capture cleanup stalls during teardown, the audio process main thread freezes for up to two seconds, delaying destruction, rebinding, or the next take.
- Why this confidence: Service.onDestroy runs on the service main thread, and Thread.join can block it for the full two-second bound.
- How to prove it wrong: Instrument AudioRecord so its read thread ignores stop for 2.5 seconds, destroy AudioCaptureService, and measure main-looper progress and rebind latency. If onDestroy remains responsive, the finding is wrong.
- Interleaving: 1. The capture thread is blocked in AudioRecord.read or cleanup. 2. Android calls AudioCaptureService.onDestroy on the :audio main thread. 3. onDestroy calls stopRecording, but the capture thread does not exit promptly. 4. onDestroy calls join(2000). 5. The :audio main looper dispatches nothing until the capture thread exits or two seconds expire.
- Refactor targets: REF-04, REF-11

### Security and privacy: Medium Risk

The network boundary is unusually strong: provider secrets use Android Keystore with AES-GCM and AAD, provider endpoints require HTTPS except exact loopback, redirects are refused, models are pinned by revision, bytes, SHA-256, host, and app-private storage, GenieX logging is silenced before inference, PostHog is allowlist-first, and Sentry drops exception messages and source context. The remaining concern is local diagnostics: DebugLogger accepts arbitrary messages and Throwable objects, prints the full throwable to logcat, and can write part of stackTraceToString to an external-storage path. That bypasses the otherwise shape-only diagnostic design. No production caller of external file logging was found, so current exposure is conditional.

**Best example:** `app/src/main/java/com/envi/wispr/telemetry/PayloadSanitizer.kt` L79-L112. Unknown properties are dropped before value handling, dynamic strings have per-key shapes, and closed tokens cannot silently carry arbitrary prose.

**Worst violation:** `app/src/main/java/com/envi/wispr/debug/DebugLogger.kt` L142-L148 [MEDIUM/Medium]. Rule: kotlin-patterns.md RULE: no-content-in-diagnostics

```
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        if (fileLoggingEnabled) {
            writeToFile("${now()} [ERROR:$tag] $message")
            throwable?.stackTraceToString()?.take(500)?.let { trace ->
                if (trace.isNotEmpty()) writeToFile(trace)
            }
        }
```

- What the user sees: A vendor or parsing exception that includes prompt, response, file, endpoint, or other user-controlled text can place it in logcat and, if file logging is enabled on a privileged development device, the external debug log.
- Why this confidence: The helper indisputably emits arbitrary messages and throwable messages. Whether a current vendor exception actually contains dictated content requires runtime injection; external file logging is presently dormant.
- How to prove it wrong: Pass a Throwable whose message contains a unique dictated-text marker through DebugLogger.error, then inspect logcat and the enabled debug file. If the marker is absent from both, the finding is wrong.
- Refactor targets: REF-10

## Refactor targets

| ID | Tier | Sev/Conf | LOC | Depends on | Title |
|---|---|---|---|---|---|
| REF-01 | LARGE | CRITICAL/High | +180 |  | Make the session owner the only insertion-target writer |
| REF-02 | MEDIUM | HIGH/High | +90 |  | Let settings and vocabulary fail open before capture |
| REF-03 | LARGE | HIGH/High | +260 | REF-01, REF-02, REF-04 | Extract a testable session coordinator from the service |
| REF-04 | MEDIUM | HIGH/High | +150 |  | Remove blocking waits from service teardown |
| REF-05 | REFACTOR | MEDIUM/High | +170 |  | Push spectrum snapshots instead of polling binder |
| REF-06 | MEDIUM | MEDIUM/High | +220 |  | Split provider transport from provider codecs and discovery |
| REF-07 | MEDIUM | MEDIUM/High | +90 |  | Reduce AppShell to navigation and screen composition |
| REF-08 | SMALL | LOW/High | -20 | REF-03, REF-06, REF-07 | Make app-only APIs internal and closed whens exhaustive |
| REF-09 | MEDIUM | MEDIUM/High | +240 | REF-01, REF-02, REF-04 | Bind heart-path tests to editor outcomes and owner signals |
| REF-10 | SMALL | MEDIUM/Medium | -60 |  | Make local diagnostics structurally content-free |
| REF-11 | MEDIUM | MEDIUM/High | +190 | REF-05 | Split audio capture ownership by lifecycle |

### REF-01: Make the session owner the only insertion-target writer

- Dimension: Architecture integrity
- Rule: architecture-rules.md RULE: one-owner-for-the-session; architecture-rules.md RULE: insertion-fails-safe-never-silently
- Evidence: `app/src/main/java/com/envi/wispr/ui/VoiceInputActivity.kt:L61-L79`; `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L531-L557`; `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:L444-L451`
- Fix: Delete pre-command calls to PasteAccessibilityService.pinTargetForDictation from VoiceInputActivity and PasteAccessibilityService.startDictationFromBubble. Keep target acquisition inside DictationSessionService.beginSession only, after the owner has atomically admitted a new take. Add a two-editor device test covering start in A, focus B, stop/toggle, and busy-start refusal.

### REF-02: Let settings and vocabulary fail open before capture

- Dimension: Error handling and observability
- Rule: architecture-rules.md FACT: heart-and-limbs; kotlin-patterns.md RULE: fail-open-to-the-last-good-text
- Evidence: `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L316-L323`; `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L388-L432`; `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L564-L600`
- Fix: Replace the mandatory cleanupPreferencesReady and structuredTermsReady gate with a SessionPreferencesProvider that returns the latest snapshot immediately. Initialize it with CleanupOptions, empty user terms, default clipboard policy, and PolishPolicy.Off. Collect updates opportunistically; a read failure records a typed limb outcome and starts capture with the last successful snapshot.

### REF-03: Extract a testable session coordinator from the service

- Dimension: Architecture integrity
- Rule: architecture-rules.md RULE: keep-central-types-thin
- Evidence: `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L109-L323`; `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L531-L627`; `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L1080-L1412`
- Fix: Introduce internal DictationSessionCoordinator, PipelineBindings, SessionPreferencesProvider, SessionPublication, and SessionTeardown types. Move the state machine, arbitration, fallback, History, and insertion decisions into the coordinator. Leave DictationSessionService as the Android lifecycle, command parsing, foreground-notification, and binder-binding adapter. Delete the moved state and methods from the service in the same change.

### REF-04: Remove blocking waits from service teardown

- Dimension: Concurrency discipline
- Rule: kotlin-patterns.md RULE: never-block-a-binder-or-ui-thread
- Evidence: `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L1861-L1918`; `app/src/main/java/com/envi/wispr/paste/PasteAccessibilityService.kt:L609-L640`; `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:L1471-L1503`
- Fix: Add an application-owned, idempotent HistoryWriteQueue backed by one SupervisorJob plus Dispatchers.IO. Enqueue transcript terminal updates there before stopSelf. In all three service onDestroy methods, commit only in-memory terminal state synchronously, cancel service-owned work without join, signal worker threads, release bindings and callbacks, and return without runBlocking or Thread.join.

### REF-05: Push spectrum snapshots instead of polling binder

- Dimension: Performance and latency
- Rule: architecture-rules.md RULE: no-idle-cost; architecture-rules.md RULE: aidl-is-append-only
- Evidence: `app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl:L33-L37`; `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:L368-L372`; `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt:L869-L880`
- Fix: Append a spectrum-listener registration transaction to IAudioCaptureService and add an IAudioSpectrumCallback AIDL interface. Publish the already computed bands from the analyzer thread at a bounded rate, never from the capture thread. Register once per take and unregister during stop. Retain getSpectrumBands as a legacy transaction but remove all production callers and the DictationMeterThread.

### REF-06: Split provider transport from provider codecs and discovery

- Dimension: Code hygiene and maintainability
- Rule: Single Responsibility Principle
- Evidence: `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt:L143-L165`; `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt:L659-L865`; `app/src/main/java/com/envi/wispr/providers/ProviderPolishClient.kt:L1193-L1323`
- Fix: Replace ProviderPolishClient's provider switchboard with ProviderAdapter implementations for OpenAI, Gemini, Claude, and self-hosted protocols. Move HttpURLConnection execution and cancellation into HttpProviderTransport, model listing into ProviderModelDiscoveryClient, and JSON parsing into a bounded parser utility or pinned JSON library. Keep ProviderPolishClient as a small dispatcher over the adapters.

### REF-07: Reduce AppShell to navigation and screen composition

- Dimension: Code hygiene and maintainability
- Rule: architecture-rules.md RULE: keep-central-types-thin
- Evidence: `app/src/main/java/com/envi/wispr/ui/AppShell.kt:L128-L236`; `app/src/main/java/com/envi/wispr/ui/AppShell.kt:L317-L447`; `app/src/main/java/com/envi/wispr/ui/AppShell.kt:L464-L1013`
- Fix: Introduce AppActions containing screen-specific action groups, move drawer/navigation chrome to AppNavigation.kt, move SettingsGroup, SettingsToggleRow, SettingsSliderRow, SettingsActionRow, chips, and glyphs to SettingsComponents.kt, and leave EnviousWisprApp responsible only for destination selection and screen composition.

### REF-08: Make app-only APIs internal and closed whens exhaustive

- Dimension: API surface
- Rule: architecture-rules.md RULE: minimize-visibility; kotlin-patterns.md RULE: exhaustive-when-no-else
- Evidence: `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt:L64-L155`; `app/src/main/java/com/envi/wispr/ui/OnboardingScreen.kt:L69-L74`; `app/src/main/java/com/envi/wispr/models/ModelDeliveryWorker.kt:L120-L143`
- Fix: Mark app-only top-level classes, objects, data classes, enums, functions, and companion members internal unless an androidTest or Gradle-module boundary requires public. Replace enum and sealed-type else branches in OnboardingScreen and ModelDeliveryWorker with explicit members. Add a static Kotlin source check limited to app/src/main that rejects new public defaults and closed-set else branches.

### REF-09: Bind heart-path tests to editor outcomes and owner signals

- Dimension: Testability
- Rule: testing-philosophy.md RULE: never-guess-when-the-subject-is-finished; testing-philosophy.md RULE: the-heart-crosses-a-real-boundary-at-least-once
- Evidence: `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt:L47-L103`; `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt:L169-L197`; `app/src/androidTest/java/com/envi/wispr/VoicePipelineDeviceTest.kt:L247-L317`
- Fix: Replace Thread.sleep and logcat polling in VoicePipelineDeviceTest with subject-fired phase and insertion-result signals. Assert the destination editor's text, exactly-once insertion, unchanged non-target editor text, and the recorded route. Add the REF-01 two-editor side-button case and injected settings/vocabulary failure cases. Keep log assertions only in separately named observability-contract tests.

### REF-10: Make local diagnostics structurally content-free

- Dimension: Security and privacy
- Rule: kotlin-patterns.md RULE: no-content-in-diagnostics
- Evidence: `app/src/main/java/com/envi/wispr/debug/DebugLogger.kt:L27-L75`; `app/src/main/java/com/envi/wispr/debug/DebugLogger.kt:L128-L148`; `app/src/main/java/com/envi/wispr/polish/PolishService.kt:L239-L251`
- Fix: Delete external shared-storage file logging. Replace DebugLogger.error(tag, message, throwable) with a typed diagnostic accepting a closed event name, numeric or token fields, and an optional throwable class only. Log stack frame locations without Throwable.toString or exception messages. Migrate existing callers to the typed API and add marker-based tests proving transcript, prompt, endpoint, key, and exception messages never appear.

### REF-11: Split audio capture ownership by lifecycle

- Dimension: Resource lifecycle
- Rule: architecture-rules.md RULE: keep-central-types-thin; architecture-rules.md RULE: protect-audio-asr-stability
- Evidence: `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:L122-L257`; `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:L609-L830`; `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:L1006-L1173`; `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt:L1295-L1445`
- Fix: Extract AudioRouteController for communication-device ownership and listeners, WarmHoldOwner for silent playback lifetime, VadFeeder for detector binding and rings, and SpectrumPublisher for analysis delivery. Keep AudioCaptureService as the binder and CaptureSession owner. Each extracted type receives one close method, and the capture thread remains the sole AudioRecord and PCM-file owner.

## Phased roadmap

**Phase 1: Protect words and remove lifecycle stalls** (REF-01, REF-02, REF-04, REF-10). These targets have no dependencies and address wrong-destination insertion, optional limbs blocking capture, main-thread teardown waits, and diagnostics that can carry uncontrolled text.

**Phase 2: Separate the oversized boundaries** (REF-03, REF-05, REF-06, REF-07, REF-11). Once the immediate heart risks are closed, extract the session, audio, provider, and UI responsibilities. The audio split follows the appended spectrum callback so it does not preserve the polling design.

**Phase 3: Lock the architecture with compiler and outcome tests** (REF-08, REF-09). Apply visibility and exhaustive-when enforcement after package movement settles, then make physical boundary tests assert real editor outcomes and the newly fixed failure paths.

## Strengths to preserve

- **First-wins terminal arbitration** (`app/src/main/java/com/envi/wispr/ui/TakeArbiter.kt` L3-L108): One token owns publication or cancellation, teardown can revoke reservations, and callbacks run outside the lock.
- **Preallocated SPSC audio handoff** (`app/src/main/java/com/envi/wispr/audio/BlockRing.kt` L5-L85): Capture never waits for optional VAD or spectrum consumers and overload drops the limb instead of audio.
- **Atomic timeout-versus-action claim** (`app/src/main/java/com/envi/wispr/paste/MainThreadHandoff.kt` L7-L77): It closes the exact late-insertion race where the user could be told failure while text still lands.
- **Typed last-good-text fallback** (`app/src/main/java/com/envi/wispr/cleanup/PolishPipeline.kt` L29-L60): Cleanup, model decline, model rejection, and success all return a value plus a closed outcome.
- **Pinned verified model catalog** (`app/src/main/java/com/envi/wispr/models/ModelManifest.kt` L19-L58): Every model carries a pinned revision, exact bytes, SHA-256, controlled HTTPS sources, and an availability gate.
- **Keystore AES-GCM with provider AAD** (`app/src/main/java/com/envi/wispr/providers/AndroidKeystoreSecretStore.kt` L137-L172): Plaintext stays out of preferences, ciphertext is bound to provider identity, and the key remains in Android Keystore.
- **Allowlist-first telemetry sanitation** (`app/src/main/java/com/envi/wispr/telemetry/PayloadSanitizer.kt` L79-L162): Unknown properties are dropped, dynamic strings have strict shapes, paths and credentials are redacted, and free objects do not pass.
- **Closed capture-ending domain** (`app/src/main/java/com/envi/wispr/audio/CaptureEnding.kt` L12-L114): Wire codes, labels, transcription eligibility, unknown handling, and atomic first-wins ownership are explicit.

## Meta recommendations

- Add a required physical-device heart matrix for start editor, focus change, stop surface, and insertion route. The gate should assert destination text and exactly-once delivery, not logs. (triggered by REF-01 and REF-09)
- Enable debug StrictMode plus a lifecycle watchdog that fails instrumentation when a Service lifecycle callback blocks the main looper beyond a small bound. (triggered by REF-04)
- Add scoped static checks for central-file size growth, app-only public defaults, closed-set else branches, and direct Log or raw Throwable use. (triggered by REF-03, REF-06, REF-07, REF-08, and REF-10)

## Not assessed

- **Stage-two clean-install setup, telemetry completeness, download-from-empty, and reinstall recovery**: Explicitly excluded from Stage 1 grading by the audit contract. Cover by: Run the Stage 2 acceptance flow on a factory-reset stranger phone with no models, no permissions, and no retained app data.
- **:accelerator-benchmark**: Explicitly excluded because it is an experiment that does not gate the app and does not fully build. Cover by: Audit it separately only if it becomes a shipped module or a release gate.
- **Current physical-phone latency, battery, thermal behavior, and Bluetooth routing**: Static reading cannot measure trigger-to-first-frame, stop-to-text, binder cost, NPU residency, or Samsung routing behavior. Cover by: Run Perfetto, power, memory, and six-entry-surface UAT on the founder's S26 Ultra, including cold processes and earbuds.
- **Process-death and accessibility rebinding behavior**: Android callback ordering and service restart timing cannot be settled from source. Cover by: Kill :audio, :asr, :vad, :polish, the main process, and the accessibility service at each session phase; verify visible fallback, History state, clipboard ownership, and the next take.
- **Build and test execution**: The audit contract prohibited Gradle, adb, and builds. Cover by: Run `./gradlew :app:testDebugUnitTest --rerun-tasks`, assembleDebug, the selected androidTest suites, and the physical-phone heart matrix after refactoring.
- **Release shipment, Play policy, Data Safety, and third-party license acceptance**: The app is Stage 1 and these require release artifacts, store configuration, policy review, and legal evidence outside the bounded source audit. Cover by: Audit the signed release AAB, merged manifest, Play declarations, privacy policy, licenses, 16 KB compatibility, and release-installed UAT during Stage 3.

## Severity by confidence

| | High | Medium | Low |
|---|---|---|---|
| CRITICAL | 1 | 0 | 0 |
| HIGH | 4 | 0 | 0 |
| MEDIUM | 5 | 1 | 0 |
| LOW | 1 | 0 | 0 |

## Internal red team

**Least confident finding:** REF-10 diagnostic-content exposure. The logger structurally emits Throwable messages, but the review did not observe a current production exception containing dictated text, and external file logging has no production caller in the inventoried source. Resolve by: Inject transcript-shaped markers into every vendor and parsing exception path on a debug build, inspect logcat and app files, and remove or downgrade the finding if no marker can reach a diagnostic sink.

**Top gap static review cannot close:** Actual destination behavior when focus changes during a live take and the take is stopped from the Samsung side button Runtime test: Start in editor A, speak, focus editor B, stop through ACTION_ASSIST, then inspect both editors, clipboard, History, terminal telemetry, and the accessibility target trace. Why it matters: Insertion is the final heart stage. Static control flow shows the target can be replaced, but only the real Samsung accessibility and focus behavior proves whether the user's words actually reach the wrong field.
