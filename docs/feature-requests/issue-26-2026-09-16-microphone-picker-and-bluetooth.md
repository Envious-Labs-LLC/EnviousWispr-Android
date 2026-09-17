# Issue #26 — Microphone picker, Bluetooth routing and the Bluetooth tip — 2026-09-16

GitHub issue: `#26`. Tier: MEDIUM. Status: APPROVED (founder 2026-09-17, warm hold declined: option A).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

`mixed_pr: true`: Code (`app/**`) plus Docs/dev-tooling (this plan under `docs/**`; `cited-symbols`
conditional).

**PAR rows closed:** `PAR-021` (microphone selection: Auto plus an explicit pick, honoured exactly),
`PAR-022` (Bluetooth routing: route resolution, fallback, removal handling, route-change observer),
`PAR-028` (Bluetooth guide: settings text plus a once-per-process notice). Evidence for each: the
hardware UAT in §11.1, which reports the EFFECTIVE device per run, never only that text appeared.

**Hardware UAT:** Y

The founder puts his AirPods Pro 3 in, connected to the S26, leaves the phone face down on the desk, walks
two metres away, holds the bubble and says "testing the earbuds from across the room, one two three". The
words land in a Gmail draft, and the History card for that take reads "AirPods Pro 3" as the microphone.
He takes the AirPods out mid-sentence on a second take; the take keeps recording on the phone microphone
and the card reads "AirPods Pro 3, then phone". He picks "Phone microphone" on the Microphone page,
dictates with the AirPods in, and the card reads "Phone microphone".

## Preface — User Rubric

1. **Who is this user in this moment?** Diana Foster, senior PM, on her commute with AirPods in, replying to
   a Slack thread on her Galaxy. Thirty seconds ago she was listening to a podcast; thirty seconds from now
   she wants her reply sent without taking the earbuds out or lifting the phone to her mouth.
2. **Why would they want this?** "I have earbuds in. Why is it listening through the phone in my hand?"
3. **How would they invoke it?** They do not. Auto picks the earbuds because they are connected, like a phone
   call would. The picker exists for the person who wants the phone microphone anyway.
4. **What app are they in?** Slack, Gmail, WhatsApp, Notes, Chrome (Diana's list). The microphone choice is
   the same for every target app; the target does not change it.
5. **What is their natural input?** "hey can we move the sync to thursday", "yes go ahead and ship it",
   "I'm on the train, will look when I'm in", "add Priya to the thread", "sounds good, thanks".
6. **What does success feel like?** Nothing. The words arrive as they do at the desk. The only visible trace
   is the History card naming the earbuds.
7. **What does wrong-not-broken look like?** The earbuds are picked but the first word is missing because
   the link took a second to open, and she learns to pause before speaking, silently, without knowing why.
8. **What would a power user hack around this to get?** Disconnect the earbuds before dictating, or hold the
   phone to their mouth. Both are what today's users do.
9. **What level of control would they want?** Off is not a level here (some microphone always records). The
   ladder is: Auto (default, no setting), an explicit device that is honoured exactly, and the History card
   that names what was used so a wrong pick is discoverable rather than a mystery.

### Cross-persona check

Priya and Aaron want the explicit pick and the History evidence. Marcus and Diana want Auto to just work
with earbuds. Elena wants nothing to leave the phone (nothing does; a device name in local History is not
network). Meera and Frank never open the setting and are served by Auto. The one tension: Frank would be
hurt by a first word lost to a slow earbud link. Resolved in §3: the first-word loss is a link property
(0.6 to 0.9 s measured), the app waits it out instead of cancelling, and the tip explains it once.

---

## 0. TL;DR

Consolidation: none. A capability grep for routing, device selection, headset and Bluetooth handling
returns nothing in `app/src/main/java` (§2.5.2), so this plan adds one new authority, the resolver, and
wraps no existing primitive. The one shared mechanism it reuses is the binder-argument pattern already
carrying the silence-stop setting.

With earbuds connected, the app records through the phone's own microphone, because nothing asks Android
for the earbuds (V1, 2026-09-16). This change adds, all inside the `:audio` process beside the
`AudioRecord`: a per-take input-device resolution (Auto, or the user's explicit pick), the two Android
calls that actually move a `VOICE_RECOGNITION` recording onto a Bluetooth headset
(`setCommunicationDevice` (external) then `setPreferredDevice` (external), measured as the only sequence
that both routes and opens the link), a routing-change observer, a silent-earbud rescue, and a binder
getter that reports the device that ACTUALLY captured. The app process gains an "Input Device" row on the
Microphone page, the Bluetooth tip, and a History column so every card names its microphone. MEDIUM:
audio capture plus new runtime behaviour. Proof: the hardware UAT in §11.1 with the effective device read
off the History card and the pipeline log for every run.

## 1. Problem

`audio/AudioCaptureService.kt:300` builds `AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, …)`
and nothing else. Measured on the S26 with AirPods Pro 3 connected (probe `default` mode, 2026-09-16):
`routed-now id=22 type=BUILTIN_MIC`. Android's input engine only routes `VOICE_RECOGNITION` to a SCO
headset when the communication device is that headset, and never routes it to an LE Audio headset
(`.claude/knowledge/bluetooth-capture-android.md` FACT: what-android-16-does-with-a-recording-today).
The founder saw exactly this: AirPods in, "Microphone Access" still says microphone, no way to change it.
On macOS the same gap shipped as the worst bug in the product's history (issue body).

## 2. Goals & non-goals

### 2.1 Goals
1. Auto records from connected earbuds (SCO headset with a microphone, or LE Audio headset) when no wired
   or USB headset is attached; otherwise wired/USB; otherwise built-in. Verified by the effective device.
2. An explicit pick from the Microphone page is honoured exactly when present; when absent, the take still
   happens and the card says what was used.
3. Earbuds removed mid-take: the take continues on the built-in microphone with no lost take.
4. A Bluetooth route that never delivers audio is rescued to the built-in microphone, never cancelled.
5. Every History card names the device that captured (and the fallback, if one happened).
6. The Bluetooth tip: a settings paragraph and a once-per-process recorder line, with a "Show Bluetooth
   tips" switch.

### 2.2 Non-goals
- No warm hold between takes: Android drops the communication route about 6 s after the recorder stops,
  with the process alive (V9). Nothing to build; nothing to promise in copy.
- No audio-quality warning: the link is wideband (7.7 kHz edge, V12) and Parakeet consumes 16 kHz.
- No `MODE_IN_COMMUNICATION` (external), no `VOICE_COMMUNICATION` (external) source (V5, V6: no gain).
- No `BLUETOOTH_CONNECT` (external) permission (measured unnecessary; names readable, routing works without it).
- No telemetry (stage 2). The History column and the pipeline log are the record.
- No macOS-style 30-second "Microphone Readiness" copy: the feature cannot exist on Android.
- No Galaxy Buds, Bose or USB-C headset measurements in this change (hardware not on hand); the LE Audio
  branch ships from source reading and is labelled unmeasured in §14.

## 2.5 Grounding brief — MANDATORY before §3

### 1. Trace producer → owner → consumer, end to end

The setting: `settings/AppPreferences.kt` (`Keys` object, `booleanPreferencesKey` /
`floatPreferencesKey` / `stringPreferencesKey`) is read in the app process by
`ui/DictationSessionService.kt`, which carries `autoStopOnSilence` and `silencePauseSeconds` over the
binder as ARGUMENTS: `audioService?.startCaptureWithSilenceStop(autoStopOnSilence, silencePauseSeconds)`
(`ui/DictationSessionService.kt:508`). The capture process never reads preferences itself; issue #69 is
the bug that follows from a process reading its own settings, and this plan keeps the argument shape.

The take: `AudioCaptureService.startRecording` (`audio/AudioCaptureService.kt:259`) builds the
`AudioRecord` at `:300`, `startRecording()` at `:323`, spawns `captureLoop` (`:381`), which reads
`READ_CHUNK_BYTES` at a time (`:414`) and writes the file. `getTerminalReason` and `getSilenceStopStatus`
are the existing per-take status getters the session owner polls (`startPolling`,
`ui/DictationSessionService.kt:566`, `publishSilenceNoticeIfNeeded` `:665`).

The record: the draft row is inserted at `ui/DictationSessionService.kt:520` with `speechEngine =
"Parakeet"`; the finished row is written by `transcriptRepository.finalize(…)` (`:967`) and shown by
`ui/HistoryScreen.kt:263` (`"$timestamp · ${transcript.speechEngine}"`). Schema: `history/TranscriptEntity.kt`,
`history/EnviousWisprDatabase.kt` `version = 6`, `MIGRATION_5_6` adds columns with `ALTER TABLE … ADD
COLUMN … NOT NULL DEFAULT`.

Found by:
```
/usr/bin/grep -rn "startCaptureWithSilenceStop\|\.startCapture()" app/src/main/java | /usr/bin/grep -v audio/AudioCaptureService
/usr/bin/grep -n "AudioRecord(\|setPreferredDevice\|setCommunicationDevice\|getRoutedDevice\|addOnRoutingChangedListener" -r app/src/main/java
```
The second grep returns exactly one hit, `audio/AudioCaptureService.kt:300` (the constructor). No routing
call exists anywhere in `app/`, `llama-android/`, `androidTest/` or `debug/` sources.

### 2. Find the existing authority before proposing one

Capability search (device, route, microphone, headset, bluetooth, input):
```
/usr/bin/grep -rniE "AudioDeviceInfo|AudioManager|getDevices|routing|headset|bluetooth|inputDevice" app/src/main/java app/src/androidTest app/src/debug llama-android/src 2>/dev/null
```
Hits: none in `app/src/main/java` for routing; `AudioManager` is not imported anywhere in the app. The
only Bluetooth code in the tree is the throwaway probe under `.claude/tools/bt-probe/` (gitignored, its own
package). `new authority proposed`: `InputDeviceResolver` (proposed) in `audio/`, and `InputDevicePick`
(proposed) in `audio/` as the value type crossing the binder.

Existing authorities reused, not wrapped: `AppPreferences` for the stored pick; the binder argument
pattern for carrying it; `CaptureSession` for per-take ownership; `RecordingOverlayState.showNotice` via
`sayWhileRecording` (`ui/DictationSessionService.kt:697`) for the one recorder line;
`transcriptRepository.finalize` for the record; `PolishEngineLabels` as the precedent for a user-facing label
object.

### 3. Read prior attempts and live direction

- Issue #26 body and comments: macOS decisions carried over (Auto just works; explicit pick honoured
  exactly; never dead-end; record the effective device). Founder 2026-09-15: ONE project; Auto uses
  earbuds when connected. Catalog `decision` 2026-09-15 says the same in one row.
- `docs/internal/poc-architecture.md:169` recorded "OS manages routing, never touch Bluetooth SCO" to avoid
  a crash class. That was written before `setCommunicationDevice` (API 31) replaced `startBluetoothSco` (external);
  the probe ran the new call 20+ times on the S26 without a fault. Superseded by the founder decision.
- Catalog: `microphone-selection` android `partial`, `bluetooth-routing` android `partial`,
  `bluetooth-guide` android `absent`; macOS copy for "Input Device", "Auto", "No microphone found. Please
  connect one.", and the Bluetooth card and settings copy (§7 reuses it).
- macOS lessons that transfer (`~/Developer/EnviousLabs/EnviousWispr/.claude/knowledge/gotchas-audio.md`
  FACT: bt-mic-warmup-delay-is-industry-wide-not-eviouswispr-specific): a cold Bluetooth link is silent for
  0.5 to 1 s and the Mac lost 14.55 % of earbud takes by aborting inside that window; founder 2026-07-25:
  degrade, do not cancel, add no failure-time copy. The 3.0 s ceiling shipped there is the number reused
  here for the rescue in §3.
- macOS lessons that do NOT transfer: warm hold (Android drops the route itself, V9); "never mutate the
  system default" (the communication device is per-process on Android, not a system default); the
  aggregate-device crash class (no such primitive here).
- Session log entries for #26 (2026-09-15 research; 2026-09-16 measurements) and
  `.claude/knowledge/bluetooth-capture-android.md`, every FACT.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

| Boundary | Current | Planned |
|---|---|---|
| App process vs `:audio` | The pick would be read in the app process | Carried as a binder argument on an APPENDED method; `:audio` never reads preferences |
| `setCommunicationDevice` lifetime | n/a | Lives as long as the `:audio` process; cleared at every take end AND in `onDestroy`, and Android clears it ~6 s after the recorder stops anyway (V9) |
| Route change mid-take | n/a | `AudioRecord.addOnRoutingChangedListener` (external) on the capture service's own `Handler`; the listener belongs to the `CaptureSession` and is removed in `closeResources`; a callback for a dead session is ignored by token |
| Bound service vs dead one | Session owner polls over the binder | The label is read once at stop after `waitForFileReady`; a dead binder yields `''` and the row stores `''` (unknown), never a guess |
| Explicit pick vs device absent | n/a | Resolve to Auto for THIS take, record `fallback` on the effective device, say one line |
| Link opened vs link silent | n/a | 3.0 s of exact-zero samples on a Bluetooth route → rescue to built-in mid-take, recorded |
| Telephony owns the route | n/a | During a call every source is `VOICE_COMMUNICATION`; the take proceeds on whatever telephony gives and the effective device records it. Not tested in this change |
| Success vs cancel vs process death | Row finalised or draft discarded | Effective device is read at stop; a cancelled take stores nothing (decision 2026-08-31) |

### 5. Prove the high-risk premises

| Premise | Evidence |
|---|---|
| Both calls are needed; either alone is wrong | V3 (comm only: starts on built-in, 848 ms late), V4 (preferred only: pure silence) |
| The sequence works without `BLUETOOTH_CONNECT` | Probe with the permission revoked: name readable, link opened 1.4 s |
| `AudioDeviceInfo.getId()` (external) is not stable | Same earbuds: id 88 in one run, 154 in another (same day) |
| Route falls back on its own when the headset disappears | V7: `routing-changed` to `BUILTIN_MIC` (external), no read error, <0.25 s lost |
| No warm window exists to manage | V9: route dropped ~6 s after stop with the process alive |
| The link is wideband | V12: 7.7 kHz edge |
| `VOICE_RECOGNITION` and no in-communication mode is right | V5, V6: no gain over V2 |
| LE Audio headsets need `setPreferredDevice` with a `TYPE_BLE_HEADSET` (external) source | Android 16 `Engine.cpp` reading only; UNMEASURED (no LE Audio headset on hand) |
| The polling thread survives a slow binder getter | The getter returns a `String` held under a lock, no I/O; issue #115 is the trap and the getter does nothing that can wait |

Problem-only Codex consult: run in the coverage round (§ ten-step step 4) with the question "trace who can
touch the `AudioRecord` route mid-take, and name the naive-design traps in the listener and the rescue".

## 3. Design

**Value type.** `InputDevicePick` (proposed), `audio/InputDevicePick.kt` (proposed): `Auto`, or
`Device(type: Int, name: String)`. Serialised for DataStore and the binder as `auto` or
`"$type|$name"`. Identity is type plus product name, never `getId()` and never the address (redacted
without the permission).

**Resolver.** `InputDeviceResolver` (proposed), `audio/InputDeviceResolver.kt` (proposed), pure over a
list of `AudioDeviceInfo` (external) (unit-testable with fakes). `resolve(pick, inputs)` returns
`Resolution(target: AudioDeviceInfo?, reason)`:
- Explicit pick present in `inputs` → that device, `reason = picked`.
- Explicit pick absent → Auto order, `reason = pick_missing`.
- Auto: first source in this order: `TYPE_WIRED_HEADSET` (external), `TYPE_USB_HEADSET` (external), `TYPE_USB_DEVICE` (external); then
  `TYPE_BLE_HEADSET`, `TYPE_BLUETOOTH_SCO` (external); then `TYPE_BUILTIN_MIC` (external); else `null`, `reason = none`.
  Excluded from Auto, never from the explicit list: `TYPE_HEARING_AID` (external), `TYPE_BLE_BROADCAST` (external). A watch that
  exposes a SCO microphone cannot be told apart by type; the explicit list is the escape (V10 unrun).
- Auto resolving to a wired, USB or built-in device makes NO routing call (Android already routes
  `VOICE_RECOGNITION` there; today's behaviour, untouched). Only a Bluetooth target, or any explicit pick,
  makes calls.

**Route ownership is a per-take object that exists BEFORE the session.** `RouteHold` (proposed) (proposed, in
`InputDevicePick.kt`) is created first in `startRecording`, holds whether a communication device was set
and which listener was registered, and has one `release()` that removes the listener and calls
`clearCommunicationDevice` (external). Every failure path in `startRecording` (buffer size, constructor,
file, `startRecording()`, thread start) releases it, and `closeResources` releases it for a published
session. So a routing request never outlives the attempt that made it, session or no session.

**Per-take sequence** in `startRecording`, under `sessionLock` as today:
1. `inputs = audioManager.getDevices(GET_DEVICES_INPUTS)` (external); `resolution = resolver.resolve(pick, inputs)`.
2. `target == null` → return false. The owner's line becomes the macOS sentence "No microphone found. Please
   connect one." only when the capture service reports `getLastStartFailure() == NO_INPUT_DEVICE`
   (proposed getter, §4). Every other false keeps today's "Microphone capture could not start safely".
3. Bluetooth target: find the SINK with the same name AND the same transport type in
   `audioManager.availableCommunicationDevices` (external). No sink, a `false` return, or a thrown call →
   log, release the hold, resolve again with the Auto order WITHOUT Bluetooth, `reason = link_refused`.
   The device list is re-read for that second resolution so a headset that disappeared between the two
   calls is not picked again.
4. Construct the `AudioRecord`; `record.setPreferredDevice(target)` for every routed target; a `false`
   return → log, release the hold, `reason = preferred_refused`, and the take proceeds on whatever Android
   routes, recorded truthfully by `routedDevice` (never by the target). Then
   `addOnRoutingChangedListener(listener, routeHandler)`; `startRecording()`; log `routedDevice`.
   V2 shows `routedDevice` is already the Bluetooth source at `startRecording()` when the preferred device
   was set first, so the first byte of the take comes from the target, never from the phone.
5. The `CaptureSession` gains `effective: EffectiveDevice` (proposed, in `InputDevicePick.kt`): the
   target, the routed device at start, a list of route changes (type+name+take position), `rescued`,
   `reason`, `kind`. Written on the capture thread and the route handler; read by the binder. All writers
   and readers take `effectiveLock` (proposed), and NO platform call is made while it is held: the lock
   guards the record, the calls happen outside it.
6. At `closeResources`: `routeHold.release()` (listener off, communication device cleared).

**The route handler thread.** A `HandlerThread` (external) named `AudioRouteThread` (proposed), created in
`onCreate`, quit in `onDestroy`, owns every routing callback and nothing else.
A callback carries the session token it was registered for and is ignored when it does not match the
live session; a callback already running when `closeResources` starts sees a released `RouteHold`
(checked under `effectiveLock`) and writes nothing. Binder threads never register listeners; the capture
service does, on this thread's `Looper`.

**Ordering against stop, silence-stop and destruction.** Rescue and cleanup are both mutations of the
route. Rescue runs on the capture thread and takes `sessionLock` for its check `session === active &&
isRecording.get()`, as `endTake` does (`audio/AudioCaptureService.kt:739`); a take that has been claimed
as ended is never rescued. **A rescue is `setPreferredDevice(builtIn)` and nothing else**: the preferred
device wins over the communication device for every source (Android 16 engine, FACT:
what-android-16-does-with-a-recording-today), so the capture moves without clearing the communication
request, and the request is cleared when the take's own `RouteHold` is released. Nothing is deferred, so
no queued clear can outlive its take or touch the next one. The `setPreferredDevice` call is a local
method on the `AudioRecord` and is wrapped: an exception is logged and the take continues unchanged (the
capture loop's fatal catch is not reached). Rescue is ONE-SHOT per take: once `rescued` is set, no further
rescue runs whatever the samples do.

**Release is synchronous and ordered before the session slot frees.** `releaseSession`
(`audio/AudioCaptureService.kt:779`) runs on the capture thread after the loop ends, under `sessionLock`,
and calls `closeResources` before clearing the session. **The final route is observed BEFORE `record.stop()`, because
`routedDevice` returns null once the recorder is inactive.** One helper, `observeFinalRoute` (proposed),
reads `record.routedDevice` and appends it to the effective history under `effectiveLock` if it differs
from the last recorded device; a null read preserves the history as it stands and is never treated as
proof of completeness. It is called at exactly two places: in `endTakeLocked`
(`audio/AudioCaptureService.kt:751`) immediately before `record.stop()` (manual, silence and destruction
stops), and at the top of `releaseSession` before `closeResources` (capture-loop endings: cap, byte
ceiling, error, where the recorder is still active). So a headset removed just before the stop is
represented even when its routing callback runs late. Inside `closeResources`, before the recorder is
released: `routeHold.release()`: remove the listener, then `clearCommunicationDevice`
on this thread (the loop is over, so the call delays no read; `onDestroy`'s two-second join already
covers `closeResources`). Only then is the session cleared, so a later `startRecording` can never install
a request that an earlier release still has to clear. `waitForFileReady` joins the capture thread
(`:814`), which is after `closeResources`, so the label the owner reads at stop is complete: route
history readiness is the same event as file readiness.

**Rescue.** In `captureLoop`, after every read, if the take's target is Bluetooth and the take has not yet
seen a non-zero sample: count exact-zero bytes READ. The bar is audio time delivered, not wall-clock: a
blocked read makes no progress and no decision (no audio means nothing to judge; a read that stays blocked
is unblocked by the user's stop, which calls `record.stop()` at `audio/AudioCaptureService.kt:754`, and
that is unchanged by this plan), a zero-length read is skipped as today (`audio/AudioCaptureService.kt:420`), a
partial read counts its bytes, and the bar is a `>=` crossing on 1,024-byte reads. At `RESCUE_AFTER_BYTES` (proposed)
(3.0 s at 16 kHz 16-bit = 96,000 bytes) with still no non-zero sample: attempt `record.setPreferredDevice(builtIn)` once, record the attempt and
its outcome on the session's effective record (`rescued`), log. Rescue never calls `clearCommunicationDevice`; the take's
`RouteHold` clears its communication request during cleanup before the session slot is freed. The take continues; nothing is cancelled;
no copy at failure time (macOS founder decision 2026-07-25). Once any non-zero sample has arrived the
counter is retired for the take (a healthy link cannot re-arm it; macOS FACT drained-pre-roll lesson
applied: the bar is measured from the first byte of THIS take, there is no pre-roll here). The first
non-zero sample is Bluetooth audio, not phone audio, because the route is the target from the first read
(step 4). A take that stays silent AFTER a rescue is not rescued again (one-shot) and is a silent take like
any other today: the 2026-08-31 decision stores nothing for a take with no words. Policy-imposed silence
(another app holding the microphone, a call arriving mid-take, Android's input-sharing rules) looks
identical to a link that never opened; on a Bluetooth route it triggers the same one rescue, which is
harmless, and on the phone route it is unchanged from today. The plan never claims that picking the phone
restores access during a call. 3.0 s is the
macOS ceiling with 50 % headroom over the founder's observed 2 s worst case; the Android cold link-up
measured 0.56 to 0.96 s, so the bar sits 3x past the measured tail. **Not a guard nobody arms**: V4
reproduced pure silence on a routed Bluetooth source, and a headset that walks out of range between the
device list and the link opening is the production arming.

**The one recorder line** (`PAR-028`): the first time in the `:app` process lifetime that a take's
effective device at START is Bluetooth, and "Show Bluetooth tips" is on, `sayWhileRecording("Bluetooth mic:
give it a moment before you speak")`. Once per process, like macOS's once per launch. The setting text on
the Microphone page is permanent.

**The row.** `TranscriptEntity.captureDevice` (proposed) `TEXT NOT NULL DEFAULT ''`, `MIGRATION_6_7` (proposed)
(proposed), written in `finalize` and shown in the expanded History card under the engine line as
"Microphone: AirPods Pro 3", "Microphone: AirPods Pro 3, then phone", "Microphone: Phone". Empty for
rows older than this change (nothing shown).

**Alternatives rejected.**
- FUTO's shape (communication device only, a "prefer Bluetooth mic" toggle default off): V3 shows the
  first word comes from the phone; and the toggle is the opt-in the founder rejected on both platforms.
- Reading the pick in `:audio` from DataStore: issue #69's bug class; the argument pattern already exists.
- A new failure reason for "no microphone": `CaptureEnding` is for how a RUNNING take ended; a start that
  never began is a different population, hence the separate start-failure getter.
- Cancelling a silent Bluetooth take: the macOS 14.55 % lesson.

## 3b. MANDATORY for placement-affecting plans — ownership justification

This will live in `AudioCaptureService` in `:audio` because `setCommunicationDevice`'s selection dies with
the process that made it and `setPreferredDevice` is a method on the `AudioRecord` that only that process
holds; the alternative was `DictationSessionService` in the app process, but its communication-device
call would be attributed to a different UID-process pairing and be cleared when THAT process's audio
activity ends, and it cannot reach the `AudioRecord` at all. The resolver is a pure object so the policy is
unit-tested without a phone; the service owns only the calls.

## 4. MANDATORY — contract deltas

- `IAudioCaptureService.startCaptureWithInputDevice(boolean autoStopOnSilence, float pauseSeconds,
  String inputDevicePick)` (proposed, APPENDED): `startCaptureWithSilenceStop(a, p)` keeps its exact
  meaning and equals `startCaptureWithInputDevice(a, p, "auto")`. An unparseable pick string is treated as
  `auto` and logged (a caller we do not control; the take is untouched).
- `IAudioCaptureService.getEffectiveInputDevice()` (proposed, APPENDED): a `String` in the form
  `"<label>"` or `"<label>, then <label>"`. It means: the device(s) that captured the CURRENT OR MOST
  RECENT take, in order. It is set at `startRecording()` and updated on every route change and rescue,
  and it PERSISTS after the take ends until the next start, so a take that ends before the first poll, or
  changes route just before an automatic stop, still reports its complete history when the owner reads it
  once at stop (after `waitForFileReady`, the same place it reads the file). Empty only before the first
  take of the process. Consumers must treat it as a display label, never parse it.
- `IAudioCaptureService.getInputRouteKind()` (proposed) (APPENDED): `0` none, `1` phone, `2` wired or USB,
  `3` Bluetooth: the kind of the device the current or most recent take STARTED on. The app process uses
  this, never the label and never its own device list, to decide the Bluetooth tip.
- `IAudioCaptureService.getInputRouteReason()` (proposed) (APPENDED): `0` auto, `1` picked, `2` pick
  missing, `3` link refused, `4` preferred refused, `5` rescued (the latest event wins). The app process
  uses `2` for the pick-missing notice.
- `IAudioCaptureService.getLastStartFailure()` (proposed, APPENDED): `0` none, `1` no input device, `2`
  other. Set before `startCapture…` returns false; reset on the next start.
- `AppPreferences.inputDevicePick` (proposed): the stored pick; default `auto`.
- `AppPreferences.showBluetoothTips` (proposed): default `true`.
- `TranscriptEntity.captureDevice` (proposed): `''` means unknown (older rows). Non-empty is the display
  label above.
- `TranscriptRepository.finalize` gains `captureDevice: String` (proposed parameter).

## 5. MANDATORY — end-to-end state and lifecycle audit

| Population | Enumeration |
|---|---|
| Callers of `startCaptureWithSilenceStop` | One: `ui/DictationSessionService.kt:508`. Moves to the new method. The debug rig `scripts/uat/debug-insert.sh` drives the SESSION owner, not the binder: unaffected |
| Places a take is started | One (`startRecording`); five entry points into one session owner all pass through `tryStartRecording` |
| Places a take ends | `claimEnding` call sites (max duration, byte ceiling, manual, error, silence) and `closeResources`: the listener removal and `clearCommunicationDevice` (external) go in `closeResources`, the single funnel |
| Places `finalize` is called | `ui/DictationSessionService.kt:967` and `insertReadyTranscript` (`:975`, `:1071`): all three get `captureDevice` |
| Routing listener lifetime | Registered after construction, removed in `closeResources`; the callback checks `session === active` |
| Communication device set but not cleared | `closeResources` on every path; `onDestroy` as the backstop; Android's own ~6 s drop (V9) as the floor |
| Rescue armed while the link is healthy | Retired at the first non-zero sample; a Bluetooth take that started audibly can never rescue |
| Readers of the new getters | `startPolling` reads `getInputRouteKind` (proposed) and `getInputRouteReason` (proposed) once per tick for the two notices; `stopAndTranscribe` reads `getEffectiveInputDevice` once after `waitForFileReady` for the row. Enumerated: no other reader |
| Failure paths in `startRecording` before a session exists | buffer size (`:279`), constructor/init (`:300-308`), file (`:312-318`), `startRecording()` (`:323`), thread start (`:356-366`), `SecurityException` and `Exception` catches (`:369-380`): every one calls `routeHold.release()` |
| Callers that can end a take while a rescue runs | binder `stopCapture`, the VAD feeder's `endTake`, `claimEnding` on the capture thread, `onDestroy`: rescue checks under `sessionLock` and is skipped once the take is claimed |
| Route mutations after a take ends | enumerated, none: the rescue never clears; the only clear is inside `closeResources` before the session slot frees; the listener is removed in the same call |
| Sources of the final route in the history | the routing callbacks (may run late) and the synchronous `routedDevice` read in `closeResources` (always runs): the label is complete when the capture thread exits |
| Android API floor | `minSdk = 33` (`app/build.gradle.kts:18`); `setCommunicationDevice` is API 31. No branch for older phones. The comment at `ui/DictationSessionService.kt:1397` saying "minSdk 30" is stale and is corrected in this change |

## 6. MANDATORY — downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| New binder start method | `DictationSessionService.tryStartRecording` | Calls the silence-stop overload | Calls the new overload with the stored pick | Yes | Unit test on the pick serialisation; UAT |
| `getEffectiveInputDevice` (proposed) | `startPolling`, stop path | none | Poll; carry into `finalize` | Yes | UAT reads the card |
| `getLastStartFailure` (proposed) | `tryStartRecording` failure branch | Generic error line | macOS sentence for `NO_INPUT_DEVICE` (proposed) | Yes | Unit test on the mapping |
| `captureDevice` column | `HistoryScreen` expanded card | Not shown | "Microphone: …" when non-empty | Yes | Compose preview + UAT |
| `captureDevice` column | `HistoryRecoveryTest`, `HistoryPublicationPolicyTest` | Build entities without it | Default `''` keeps them compiling | No | Tests run |
| Migration 6→7 | Every install on the phone | version 6 | `ALTER TABLE ADD COLUMN` | Yes | Migration test (existing pattern) |
| `inputDevicePick` preference | `MicrophonePage` | Placeholder paragraph | "Input Device" row: Auto + connected inputs | Yes | UAT |
| `showBluetoothTips` | `MicrophonePage`, session owner | none | Switch + gate on the recorder line | Yes | Unit test on the once-per-process gate |

## 7. MANDATORY — failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| No input device at all | Resolver `none` | `tryStartRecording` | "No microphone found. Please connect one." (macOS `Recording failure` copy) | No row (draft never created) | Next press |
| Explicit pick absent | Resolver `pick_missing` (proposed) | capture | Take proceeds on Auto; one recorder line "AirPods Pro 3 is not connected, using the phone" | Card: "Microphone: Phone" | Next press re-resolves |
| `setCommunicationDevice` returns false | Android | capture | Nothing at the time; card names the phone | Card: "Microphone: Phone" | Next press |
| Link never opens (silent) | Android/headset | capture rescue at 3.0 s | Nothing at the time (macOS decision); card "AirPods Pro 3, then phone" | Card | Next press |
| Headset removed mid-take | Android route change | listener | Nothing; take continues | Card "AirPods Pro 3, then phone" | n/a |
| Headset connected mid-take | Android | listener | Nothing; the take keeps its device (freeze per take); next take uses it | Card unchanged | Next press |
| Routing listener never fires | Android | none | Nothing | Card shows start device only | n/a |
| Phone call in progress | Telephony | capture | Whatever telephony routes; card records it | Card | n/a |
| Unparseable pick string | A caller we do not control | capture | Treated as Auto | Card | n/a |
| No matching communication sink for the Bluetooth target | Android list mismatch | capture | Nothing; card names what captured | Card | Next press |
| `setPreferredDevice` returns false | Android | capture | Nothing; card names what Android routed | Card | Next press |
| A routing call throws | Android | capture | Nothing; hold released; take proceeds on Android's route | Card | Next press |
| Another app or a call takes the microphone mid-take | Android input-sharing policy | capture | Nothing at the time (unchanged from today); on a Bluetooth route one harmless rescue | Card; a wordless take stores nothing | Next press |
| Take ends before the first poll | Short take | stop path | Nothing; card still names the device (label persists until next start) | Card | n/a |

## 8. MANDATORY — caller-visible signals audit

| Signal | Meaning beyond its type |
|---|---|
| `getEffectiveInputDevice()` empty | No take has started in this `:audio` process yet, or the binder died; the row stores `''` (unknown) |
| `getInputRouteKind() == 3` on the first Bluetooth take of the app process | Arms the once-per-process tip line |
| `getInputRouteReason() == 2` | Arms the one pick-missing notice for this take |
| `", then "` in the label | A fallback or rescue happened during the take; the card shows it verbatim |
| `captureDevice == ''` on a row | The row predates this change or the getter was never non-empty (binder died before the first poll); shown as nothing, never as "Phone" |
| `getLastStartFailure() == 1` | Distinguishes the macOS sentence from every other start failure; stale after the next successful start |
| `inputDevicePick` string not matching any current device | Absent this take, not invalid: never rewritten by the app |

## 9. MANDATORY — fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| No input device | `null` target | `AudioManager.getDevices` in `:audio` at start | The same list Android will route from | Empty after filtering to sources | Return false with reason 1 | Session owner |
| Pick absent | Auto order | Resolver | The founder's Auto order | First source present | Built-in is always present on a phone | capture |
| Link silent | Built-in | Resolver's built-in entry | Always present, never needs a link | `setPreferredDevice` returns true | Keep the Bluetooth device and record it | capture |
| Effective label | `record.routedDevice` (external) | `AudioRecord` itself | The only API that says what captured | Non-null | Fall back to the target's label with `?` suffix, never to the pick | row |

## 10. File-by-file changes

- `app/src/main/aidl/com/envi/wispr/audio/IAudioCaptureService.aidl`: five APPENDED methods (§4).
- `app/src/main/java/com/envi/wispr/audio/InputDevicePick.kt` (proposed): `InputDevicePick`,
  `EffectiveDevice` (proposed), the serialisation, the display labels (`labelFor(AudioDeviceInfo)`: product name for
  Bluetooth/USB/wired, "Phone" for built-in).
- `app/src/main/java/com/envi/wispr/audio/InputDeviceResolver.kt` (proposed): the Auto order, exclusion
  set, explicit match, sink lookup for a Bluetooth target.
- `app/src/main/java/com/envi/wispr/audio/AudioCaptureService.kt`: the `AudioRouteThread` in
  `onCreate`/`onDestroy`, the per-take sequence with `RouteHold`, the listener, the rescue counter in
  `captureLoop`, `closeResources` cleanup, the five binder methods.
- `app/src/main/java/com/envi/wispr/settings/AppPreferences.kt`: two keys, two fields, two setters.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`: pass the pick; poll kind and reason;
  read the label once at stop; carry it to `finalize`; the once-per-process tip line; the pick-missing
  line; the no-microphone sentence; correct the stale "minSdk 30" comment at `:1397`.
- `app/src/main/java/com/envi/wispr/ui/SettingsPages.kt`: `MicrophonePage` gains the "Input Device"
  group (Auto + current inputs from `AudioManager.getDevices` read in the app process for DISPLAY only, with
  a live refresh via `AudioDeviceCallback` (external)), the "When using Bluetooth" paragraph, and the "Show
  Bluetooth tips" switch; the placeholder paragraph goes.
- `app/src/main/java/com/envi/wispr/history/TranscriptEntity.kt`, `TranscriptDao.kt`,
  `TranscriptRepository.kt`, `EnviousWisprDatabase.kt` (`version = 7`, `MIGRATION_6_7`), plus the exported
  schema JSON.
- `app/src/main/java/com/envi/wispr/ui/HistoryScreen.kt`: the "Microphone:" line in the expanded card.
- Catalog: a `data/NNN-*.sql` row set at wind-down flipping the three android rows to `shipped` with the
  Android copy.

## 11. Testing

1. **Class of every new test.** Product outcome: resolver order and explicit match ("when this fails, the
   user's earbuds are ignored"); the once-per-process tip gate ("the user is nagged every take"); the
   start-failure mapping ("the user reads a generic error instead of 'connect a microphone'"); the rescue
   counter as a pure function over byte chunks ("a silent take is cancelled or never rescued"). Drift
   guard: the migration test; the AIDL append-order test if one exists (check `ThirdPartyNoticesTest`'s
   sibling pattern). Harness contract: none.
2. **What revert would turn it red?** Resolver: swap the Bluetooth and built-in order → the Auto test with
   both present fails. Tip gate: remove the "once" flag → the second call asserts no line and fails.
   Rescue: remove the retire-on-first-nonzero → the healthy-link case asserts no rescue and fails.
   Migration: drop the `ALTER` → the 6→7 test fails on the missing column.
3. **Deliberately NOT tested.** The `AudioManager` calls themselves (no fake is honest; the phone is the
   oracle, §11.1). The routing listener firing (platform behaviour; V7 measured it). Telephony.

### 11.1 Hardware UAT spec

- **Subsystem:** heart path (capture).
- **Recipe:** new recipe "earbud capture" added to `device-testing.md`: for each run below, dictate into
  a Gmail draft, then read the History card's "Microphone:" line AND the pipeline log lines
  `route start=`, `route change=`, `route rescue=` (proposed log tokens) via `adb logcat -s AudioCapture`.
  The card is the user-facing oracle; the log is the mechanism oracle; both must agree.
- **Runs** (issue body's five, minus the USB-C headset, which is not on hand):
  1. AirPods in, phone face down, speak from two metres: words arrive; card "AirPods Pro 3".
  2. AirPods in, speak into the phone: card "AirPods Pro 3" (the earbuds captured, not the phone).
  3. AirPods removed mid-take: take completes; card "AirPods Pro 3, then Phone".
  4. Start with no earbuds, connect them mid-take: card "Phone"; the NEXT take reads "AirPods Pro 3".
  5. Explicit pick "Phone" with AirPods in: card "Phone".
  6. Explicit pick "AirPods Pro 3" with the AirPods in the case: take completes on the phone; one recorder
     line; card "Phone".
  7. Back-to-back: two takes within 3 s with AirPods in: both cards "AirPods Pro 3" (the ~6 s Android
     window, V9, must not confuse the second take).
  8. Music playing on the AirPods during a take: take completes; music returns to normal after (ears).
- **Expected observation:** as listed per run; the oracle is the History card text, which the capture
  code does not write (the session owner writes it from the binder label, and the label comes from
  `routedDevice`, which Android writes).
- **Phone state to restore afterwards:** the explicit pick back to Auto; "Show Bluetooth tips" back on;
  the probe app uninstalled (`adb uninstall co.enviouslabs.btprobe`).

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `InputDeviceResolverTest` (proposed) | product outcome | Auto order, exclusions, explicit match, pick-missing fallback | Reorder or drop an entry |
| `InputDevicePickTest` (proposed) | drift guard | Round-trip serialisation; garbage → Auto | Change the separator |
| `SilentRouteRescueTest` (proposed) | product outcome | Rescue at exactly the bar; never after a non-zero sample | Remove the retire |
| `BluetoothTipGateTest` (proposed) | product outcome | Once per process, gated by the setting | Remove the flag |
| Migration 6→7 test in the existing migration test class | drift guard | Column exists with default `''` | Drop the `ALTER` |
| `StartFailureCopyTest` (proposed) | product outcome | reason 1 → macOS sentence; others → today's line | Swap the mapping |
| `RouteHoldTest` (proposed) | product outcome | `release()` is idempotent and clears only what was set | Drop the guard on the second release |

## 12. Blast radius & rollback

- Touched: `app` module only: `audio/`, `settings/`, `ui/` (session owner, Microphone page, History card),
  `history/` (schema 7). NOT touched: `:asr`, `:polish`, `:vad`, insertion, models, `llama-android`,
  `third_party`, onboarding, permissions.
- Revert: revert the squash commit. The schema goes back to 6 on the next install only with a data wipe
  (Room refuses a downgrade without one); dev state on the founder's phone, a chore not an incident
  (`CLAUDE.md` stage 1).

## 13. Ship criteria specific to THIS change

- [ ] AirPods in, phone on the desk, words arrive from two metres, card says "AirPods Pro 3".
- [ ] AirPods out mid-sentence, the take finishes, card says "AirPods Pro 3, then Phone".
- [ ] "Input Device" row lists Auto, Phone and the connected AirPods, and a pick is honoured.
- [ ] All eight UAT runs above on the S26, internal build number reported.

## 14. Open questions

- LE Audio headsets (Galaxy Buds): the `TYPE_BLE_HEADSET` branch ships from source reading; unmeasured
  until a pair is on hand. Labelled in the tip? No: the tip is about cold starts, which apply to both.
- The watch trap (V10): a Galaxy Watch exposing a SCO microphone would be picked by Auto; the explicit
  list is the escape. Measure when the founder has the watch on and the AirPods off.
- USB-C headset: the wired/USB branch is today's OS behaviour, untouched; untested here.

## 15. Related

Issue #26; #27 (pre-roll: a cold link cannot be pre-rolled, the two are independent); #115 (the polling
thread must never wait on the binder: the new getter is lock-and-return); #69 (why the pick crosses as an
argument). `PAR-021`, `PAR-022`, `PAR-028`. Catalog features `microphone-selection`, `bluetooth-routing`,
`bluetooth-guide`. Knowledge: `.claude/knowledge/bluetooth-capture-android.md`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted before this file was written (issue #26 comment 2026-09-16)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered
- [x] Lane declared and matching detection (Code, mixed with Docs)
- [x] Self-reviewed to all-clear before any reviewer saw it; Codex coverage round + three grounded rounds to PROCEED-AS-PLANNED (2026-09-17)

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
