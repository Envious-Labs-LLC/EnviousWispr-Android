# Issue #333: a warm hold's silent track that is not released is reported and latches the process (REF-09, regrade 6) (2026-09-24)

GitHub issue: `#333`. Tier: SMALL. Status: revised after the coverage round (`333-cov`), all five findings adopted; built 2026-09-24.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none. A platform release failure cannot be staged on a device without a production seam. The JVM rows drive the fake track, and the platform class keeps its one call shape.

## Preface: User Rubric

Persona: the founder with earbuds. The warm hold keeps his earbud route open for 30 seconds after a take by playing silence (`WarmHoldOwner.AudioTrackSilence`). Today `stop()` swallows a failed `AudioTrack.stop()` or `release()` and forgets the track either way. A native track that never released can keep the call route, or a playback slot, with nothing reporting it.

## 0. TL;DR

Precedent (#257, `SilenceWriterWatch`): a stopped hold's writer that has not exited within `EXIT_BOUND_MS` is reported once as a content-free defect (`AppDefect.SilenceWriterExitWedged`), and the process is latched against any further hold. The process is deliberately NOT ended, because that could cut off a take holding the handed-over route. The audit's fix ("terminate the audio process") conflicts with that recorded deviation, so this change follows the precedent.

Change:

1. `WarmHold.SilentTrack.released()`, a new member: the platform track has been released, or none was ever built.
2. `AudioTrackSilence.stop()` stops the writer, then the track, then releases it. It clears its reference only when `release()` returned, so `released()` reads false after a release that threw, and the handle is kept.
3. `SilenceWriterWatch` settles a stopped track only when its writer has exited AND it is released. A track that is not released by the bound latches the process against further holds, the same as a stuck writer. It is reported once as a new content-free `AppDefect.SilentTrackReleaseUnconfirmed` (`audio.silent_track_release_unconfirmed`), added to `AppDefect.all()` and the Sentry schema lists. Each kind is reported once per process.

AOSP `AudioTrack.release()` catches the `IllegalStateException` of its own internal `stop()`, so a throw here is an OEM or native anomaly. This is observability and a latch, not a hot path.

## 1. Tests

- `SilenceWriterWatchTest`: a stopped track whose writer exited but which is not released, past the bound, latches `admit()` false and reports `SilentTrackReleaseUnconfirmed` once. A track that is released and exited settles with no report. A stuck writer still reports `SilenceWriterExitWedged`. MUTATION m1: the watch ignores `released()`.
- The fakes in `WarmHoldTest`, `AudioLimbCloseTest` and `SilenceWriterWatchTest` implement `released()`.
- `DefectListCompletenessTest` and the Sentry schema tests cover the new defect.

## Results (2026-09-24)

- Coverage round (`333-cov`), all five findings adopted:
  1. Keep the #257 latch, never ending the process.
  2. The watch decides and the track owns the release. Each kind latches and reports once. The release state is `@Volatile`.
  3. A release that threw is "unconfirmed", not a proven leak, so the defect is named `SilentTrackReleaseUnconfirmed`.
  4. The defect is in `AppDefect.all()`, both `SentrySchema` lists and the `TelemetryContractsTest` snapshot. It adds no PostHog event.
  5. The watch never retries the release. The failed-build branch in `play()` keeps its handle when that release throws.
- MUTATION m1 RED (`anUnconfirmedReleaseIsReportedOnceAndLatchesTheProcess`; `333-mut.py`). `AudioServiceShapeTest`'s log-template baseline gains the release defect's own send warning.
- Suite 1379, 0 failures; visibility and citation checks clean.
