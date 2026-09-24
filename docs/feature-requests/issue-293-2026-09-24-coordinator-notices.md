# Issue #293: trim the session owner's notice delivery and polish telemetry (2026-09-24)

GitHub issue: `#293`. Tier: LARGE by surface (the session owner), a behaviour-preserving move. Status: revised after the coverage round (`293-cov`), both findings adopted; grounded rounds 1 and 2 (`293-g1`, `293-g2`) adopted.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: a take still lands its words; a polish failure still shows its notice (a cloud provider with no key).

## Preface — User Rubric

User Rubric: N/A — internal-only move; every sentence, surface, telemetry field and defect stays exactly as it is today.

---

## 0. TL;DR

REF-04 of `docs/audits/2026-09-23d-senior-audit.json` (`.claude/rules/architecture-rules.md` RULE: keep-central-types-thin). `DictationSessionCoordinator.publishResult` writes the four polish facts, builds the `polish_done` breadcrumb, and posts the polish-failure toast plus notification itself; `announceError` posts the failure toast itself. Move the notice delivery into the existing `SessionNoticePresenter` and the polish facts plus their breadcrumb into `TakeFacts`; delete the moved code from the owner in the same change. The reservation, the commits and the insertion decision stay in the owner.

Consolidation: every notice the owner raises goes through `SessionNoticePresenter`; every polish fact is written by `TakeFacts.recordPolish`.

Prior context: #256 (the presenter: the owner picks which notice, the presenter picks where), #176 (the facts are written before the reservation so any later ending carries them; the defect is raised once), #252 (`reportDefect` never lets a throwing sink stop publication), #77 (the polish notice precedes the owner's continuation).

## 1. Grounding (main af8c4ce, after #292)

- `publishResult` (`ui/DictationSessionCoordinator.kt`): lines writing `takeFacts.polishProvider/polishReason/polishMs/polishStatus`, then `Telemetry.breadcrumb("take", "polish_done", mapOf(...))`, then `TelemetryChannels.defectOf(reason)?.let { reportDefect(...) }`. Later `payload.polishFacts.notice?.let { host.postToMain { host.toastFromService(notice.toastLine); host.showPolishNotice(notice) } }`.
- `announceError`: `if (line != null) host.postToMain { host.toastFromService(line) }`.
- `SessionNoticePresenter(surface, insertion, host, scope, mainDispatcher)` in `ui/SessionNotice.kt`: `say(SessionNotice)` only. Built in `DictationSessionService` and `DictationSessionRig`.
- `TakeFacts` (`telemetry/TakeFacts.kt`): `@Volatile` fields, `terminal(reason)`, and companion token helpers; no `recordPolish` yet. The presenter is built at `DictationSessionService.kt:219` and `DictationSessionRig.kt:138` (`git grep -n "SessionNoticePresenter("`); the rig records `toast:<line>` (`DictationSessionRig.kt:278`) and `polish-notice` (`:289`), asserted by `DictationSessionCoordinatorTest.kt:217,475,554,572,614,627,638,657,1185`, `PolishFailsOpenTest.kt:80,98,185,355` and `HistoryNeverHoldsTheWordsTest.kt:78,229`.
- Tests reading the moved source text: `PolishPublicationRoutesTest.theFactsAreDerivedOnce...` (`host.showPolishNotice(notice)` index in `publishResult`). Behaviour tests on the rig's `toast:` and `polish-notice` events: `DictationSessionCoordinatorTest`, `PolishFailsOpenTest`, `HistoryNeverHoldsTheWordsTest`.

## 2. Design

1. `SessionNoticePresenter.sayPolishFailure(notice: PolishFailureNotice)`: `host.postToMain { host.toastFromService(notice.toastLine); host.showPolishNotice(notice) }`, byte-for-byte the moved lines. `SessionNoticePresenter.sayFailure(line: String)`: `host.postToMain { host.toastFromService(line) }`. The owner keeps its log line and calls these. The order is unchanged: the posts happen at the same point of `publishResult` and `announceError`.
2. `TakeFacts.recordPolish(reason, latencyMs, statusCode, provider: String): PolishRecord` (coverage finding A): writes the four fields and answers `PolishRecord(breadcrumb, defect, defectData)`: the `polish_done` breadcrumb's map, `TelemetryChannels.defectOf(reason)`, and `mapOf("take_id", "polish_status")`. The owner calls it as `recordPolish(reason, latencyMs, statusCode, polishContext.encode())`, passes the breadcrumb to `Telemetry.breadcrumb`, and keeps the one `reportDefect` call (#252; the sink is the owner's injected seam) at the same point, after the breadcrumb and before the payload and the reservation.
3. The owner's stray doc comment above `reportDefect` ("Not destroyed and still live ...") moves to `polishStillWanted`, where it belongs.
4. Nothing else moves. `publishResult` keeps the payload, the reservation, the commits and the delivery.

## 3. Tests

1. `SessionNoticePresenterTest`: `sayPolishFailure` posts the toast line then the notification, in that order, on main; `sayFailure` posts the toast. MUTATION m1: drop `showPolishNotice` from `sayPolishFailure` (RED here and in `PolishFailsOpenTest`). MUTATION m2: drop the toast from `sayFailure` (RED in `DictationSessionCoordinatorTest` failure rows).
2. `TakeFactsTest`: `recordPolish` writes all four fields and answers the breadcrumb map with the take id, the reason name, the ms and the provider, plus the defect for a defect-naming reason (with `take_id` and `polish_status`) and none for `NONE`-class reasons. MUTATION m3: skip the `polishStatus` write. MUTATION m4: the breadcrumb omits `polish_provider`.
3. `PolishPublicationRoutesTest` reads `notices.sayPolishFailure(notice)` in place of `host.showPolishNotice(notice)`, still ahead of the continuation, and asserts the presenter's body reaches `host.showPolishNotice(notice)`. It also asserts the owner's exact call `recordPolish(reason, latencyMs, statusCode, polishContext.encode())` (coverage finding C), and, within `publishResult`, the order `recordPolish(` < `Telemetry.breadcrumb("take", "polish_done"` < `reportDefect(` < `val payload` < `synchronized(publishLock)` (grounded round 1).
4. The owner no longer names `host.toastFromService` or `host.showPolishNotice`: a source row. MUTATION m5: restore the direct toast in `announceError`.

## 4. Blast radius

None intended: the same sentences, surfaces, order, fields, breadcrumb and defect. Risk: a notice posted at a different moment; the order row and the existing behaviour rows guard it. Rollback: revert the squash commit.

## 5. Ship criteria

- [ ] Rows green, m1 to m5 RED; the suite green; app and androidTest build; visibility and cited-symbol checks clean.
- [ ] Emulator: a take lands its words.
- [ ] Codex code review ALL-CLEAR with a confirming round.
