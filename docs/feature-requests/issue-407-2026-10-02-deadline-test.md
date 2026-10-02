# Issue #407: deterministic discovery deadline test

Tier: SMALL. Approved scope: founder's 2026-10-02 instruction to continue Phase 1 stability work.

**Lane:** Code
mixed_pr: true
Docs/dev-tooling: plan only.
**PAR rows closed:** none.
**Hardware UAT:** N. This adds a clock seam with the existing production clock as its default and repairs a JVM fixture; no product behavior is intentionally changed. Emulator smoke check still required.
**User Rubric:** N/A: internal test reliability only.

## 0. TL;DR
Replace a server-log count measured after a real-time pause with a controlled clock, real probe pool, and fake transport. This protects the discovery budget without false failures under host load.
## 1. Problem
Issue #407 records CI run 36935074920 failing the deadline row with all nine probes logged. The current row polls for a quiet request count and skips when no request arrives; neither establishes when the client started a request.
## 2. Goals and non-goals
Prove queue cleanup and refusal to start requests after the discovery deadline. Do not widen timeouts, change model results or call live providers.
## 2.5 Grounding
Producer: discovery's whole-operation deadline in ProviderModelDiscoveryClient; probes submitted to its three-worker PROBE_EXECUTOR; each worker reads remaining time before probe; probeOnce also checks its logical deadline before ProviderTransport.run. ProviderDeadlines in HttpProviderTransport owns nanos-to-ms conversion, also used by ProviderPolishClient and HTTP transport. Existing transport injection already permits exact client-side request observation. The #110 key-rejection row separately proves every queued future is cancelled. Read issue #407 and the 2026-10-01 session-log entry; no parity behavior change, catalog lookup not relevant to this internal fixture.
## 3. Design
Add a monotonic clock callback to discovery, default System.nanoTime. Pass readings through ProviderDeadlines arithmetic (optional now argument retains defaults for other callers). Discovery and logical probe deadlines use the same clock.
Separate four deterministic rows. Hold all three real pool workers on bounded latches before discovery. Fake list transport returns nine models and advances an atomic clock to the discovery deadline. The clock callback parks only the discovery caller on its first post-list read, which occurs after all probes were submitted and before result collection/cancellation.
Budget row: release workers while the caller remains parked; drain FIFO using three simultaneous marker tasks; assert zero probe transport starts and then release the caller. Cancellation row: capture all nine queued Future instances while caller and workers are parked; release caller; await return and require every captured future cancelled before workers are released. Finally release workers and drain. Each wait is bounded and every gate released in finally; never shut down the shared pool.
Positive control: frozen live clock, one model, valid reply, AVAILABLE. Retry control: one model, caller parked with clock live, first probe returns inconclusive 200 and advances clock to its logical probe deadline; drain workers before releasing caller; assert exactly one transport start and UNVERIFIED result.
Mutation requirements: no cancel fails cancellation row; removal of both probe budget guards fails budget row; removal of probeOnce budget guard fails retry row; no cancel plus both guards removed fails both corresponding rows. For guard-removal mutation controls only, clamp transport timeout arguments to at least 1 ms so a zero budget cannot throw before the missing guard reaches transport. Require an observed extra transport call, not an exception, to turn those controls red. Production arithmetic remains unchanged. The old timing row is replaced, not weakened or skipped.
## 4. Contract deltas
Clock readings are injectable, default unchanged. Deadline units remain nanoseconds in and positive whole milliseconds out. No changes to result types, persistence, network contents, provider selection or cancellation.
## 5. State and lifecycle
Enumerated discovery list, worker admission, initial probe, retry, result wait, final cancellation. Discovery and probe deadlines share one clock. Marker tasks are test-only, release in finally, with bounded waits; no global executor shutdown. No phone state changes from the JVM row.
## 6. Consumers
Discovery uses injected readings; polish client and HTTP transport continue using System.nanoTime through the existing default. Production constructor callers omit the seam. The test observes transport calls, returned list/access, queued-future cancellation and executor drain.
## 7. Failure modes
Expired list budget refuses as timed out; expired probe budget leaves a model unverified; cancellation continues to cancel futures. A fixture wait expiring fails loudly. No new fallback or retry.
## 8. Signals
Fake-clock value defines deadline passage. A transport call records a client start, not server reception. Executor markers signal completion of earlier submitted work. The row must establish at least one successful pre-deadline probe, so an always-expired client cannot pass.
## 9. Fallback authority
Existing model rows are authoritative; probes with no accepted answer remain unverified. No substitute text or persisted state introduced.
## 10. Files
ProviderModelDiscoveryClient.kt, HttpProviderTransport.kt, ProviderModelDiscoveryClientTest.kt.
## 11. Validation
Run the deadline row, class, full JVM suite and assembleDebug. Mutation receipts: remove cancellation, remove budget checks, remove both; report which row catches each. Ensure no sleeps, polling or skips in replacement. Review plan coverage once, grounded review to all-clear, self-review then code review and confirming rerun.
## 11.1 Emulator
Open the installed app and AI Polish page; preserve settings. This is a smoke check, not live provider or dictation evidence.
## 12. Delivery
One worktree and PR. Merge after required CI and reviews; test-only/no behavior delta requires no phone speech evidence. The silent-speech notice and long-take fixes remain separate changes.
## 13. Acceptance
A deterministic test rejects post-deadline starts and proves queued cancellation without observing elapsed quiet time.
## 14. Open questions
None after grounded review staging revisions; awaiting confirming plan verdict.
## 15. Related
Issue #407 and prior #110.
