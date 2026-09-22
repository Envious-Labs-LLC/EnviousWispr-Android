# #213 emulator pass — 2026-09-22

Device: `emulator-5554` (AVD `EnviousWispr_Android_16_Play`), debug builds. Driven through `scripts/uat/wispr_eyes.py`; the wedge is staged with the new `freeze_thread`, which suspends only `AudioCaptureThread` through a JDWP debugger while the capture process keeps answering binder calls. Target editor: Gmail compose body. This stages the invariant (the sole cleanup thread never reaches its release, the recorder lease stays held); a vendor `AudioRecord.read()` that ignores `stop()` is NOT RUN.

## Baseline (main, `db37bb7`): red by today

- Live take; `freeze_thread` froze `AudioCaptureThread` inside `captureLoop` (pid 5476); a stop logged `Stopped by a stop request` and no `Live picture:` line followed (the release never ran).
- The next take, with the thread still suspended (`restore(keep_frozen_threads=True)`): `A recorder is still held in this process; refusing to start`. Every later take in that process would be refused the same way.
- After a plain `restore()` thawed the thread, an ordinary take landed in Gmail by COMMIT.
- An ordinary stop released the recorder 3 ms after `Stopped by` (`Live picture:` line).

## Fix (`85c2793`, app code unchanged since `a03371f`)

- Same wedge (pid 7542): `Stopped by`, no `Live picture:`.
- The next take, thread still suspended: `A recorder was never released; ending the capture process` at 18:11:11.073; the session owner logged `Audio capture disconnected` (.127), then `Failed to start recording (DeadObjectException at IAudioCaptureService$Stub$Proxy.startCaptureForTake:949)` (.139), then `Ignoring AUDIO_PROCESS_DIED: the take already has an ending` (.149): one ending, the process death, whichever arrived first.
- The take after it ran in a NEW capture process (pid 8064): `VERIFIED` ended by a stop request, 45 characters, `route=COMMIT written=true outcome=VERIFIED` into Gmail, the editor's whole text equal to the expectation.
- (o) Two ordinary back-to-back takes: both `VERIFIED` into Gmail with the whole text equal; the capture pid was 8064 after each (no process end); zero `A recorder was never released` lines.

## Not run

- The founder's phone: queued with #115, #161 and #212.
- A native `AudioRecord.read()` that ignores `stop()` (no fault-injected recorder).
