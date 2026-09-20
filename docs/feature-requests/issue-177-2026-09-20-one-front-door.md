# Issue #177 — One front door for driving the emulator and the phone — 2026-09-20

GitHub issue: `#177`. Tier: MEDIUM. Status: APPROVED (founder 2026-09-20: "You are cto, you decide these things with codex"; Gate 1 "yes, do it").

## Preface — Lane + Hardware UAT declaration

**Lane:** Docs/dev-tooling (`scripts/**`, `docs/**`; the `.claude/**` edits are gitignored and land in the
main checkout by hand, listed in §10 so the reviewer sees them).

**PAR rows closed:** none. Dev tooling, no product surface.

**Hardware UAT:** N. Nothing in the app changes. The proof runs on the EMULATOR (§11.1 names it anyway,
because the harness calls only mean something when they ran).

## Preface — User Rubric

User Rubric: N/A — the change is to the test harness, the command hook and the knowledge that names them.
No user of EnviousWispr sees any of it.

---

## 0. TL;DR

**Consolidation:** the dominant root is "a command that drives the device", today produced at six sites
(`scripts/uat/grpc-take.sh`, `inject-audio.sh`, `launch-grpc.sh`, `debug-insert.sh`, `rail_signals.py`,
and any session's raw adb). Its one owner becomes `scripts/uat/wispr_eyes.py`; the four scripts are
deleted, `rail_signals.py` calls the owner, and the Bash hook refuses the sixth site.

Four doors exist to see or drive the emulator (raw adb, the console, gRPC, Appium) and the raw one keeps
winning, so sessions drive the device by hand and the founder reports "you ignore the guide every session".
Fix: `scripts/uat/wispr_eyes.py` becomes the only door. It gains the six emulator calls that today live in
four loose shell scripts (launch, unlock, host mic, feed audio, one spoken take, audio-free insert), the
four scripts are deleted, `rail_signals.py` moves onto the harness calls, the Bash hook refuses raw
`adb shell input|uiautomator|screencap`, `adb emu` and `grpcurl` against the emulator controller with a
message naming the harness call, and Appium leaves the project. MEDIUM. Evidence: the harness's own tests
plus `test-hooks.sh` in both directions, then every new call run on the live AVD including one full
spoken take whose editor text is checked.

## 1. Problem

- Founder, 2026-09-20: "You continue to refuse to read the Wispr Eyes guide for Android, every single
  session." The rule-only fix landed the same day (`tools-and-apps.md` RULE:
  drive-the-phone-through-wispr-eyes-before-any-raw-adb). Astra's adjudication
  (`.codex/2026-09-20-emulator-doors-astra.txt.last`): a rule and more functions will not make the next session
  choose right; the raw doors have to be refused at the tool boundary.
- The emulator paths are four shell scripts with hardcoded coordinates and their own restore-less state:
  `scripts/uat/grpc-take.sh` taps `1224,1864` to start a take and `1222,1860` to confirm, turns the host
  mic off and never back on; `launch-grpc.sh` kills the AVD with no check of who is using it;
  `inject-audio.sh` and `debug-insert.sh` each carry their own packet builder / broadcast. None journals a
  change, none is covered by `test_wispr_eyes.py` (86 tests, all on the harness).
- `scripts/uat/rail_signals.py:165,448,180` is a fifth copy: its own `grpcurl injectAudio`, its own
  `hostmicoff`, its own take start through `VoiceInputActivity --ez toggle true`.
- `ready()` (`wispr_eyes.py:389`) refuses ANY locked device, so on the emulator the harness says "only its
  owner can open it" about a PIN we set ourselves (`1234`). Today's workaround is five raw adb lines in the
  skill.

## 2. Goals & non-goals

### 2.1 Goals
1. Every emulator job has a named harness call; each is journaled and restored like the existing calls.
2. The four scripts are gone and `rail_signals.py` has no `grpcurl`/`adb emu`/take-start of its own.
3. The Bash hook refuses the raw driving shapes and allows every read shape; `test-hooks.sh` asserts both.
4. Appium is gone from the rules, the skill and the MCP registration; the RC=137 fallback is named.
5. `ready()` unlocks the emulator itself and still refuses a locked physical phone.

### 2.2 Non-goals
- The physical-phone recording lock (`RECORDING_IS_OFF`) is not deleted or weakened for the phone.
- #161's parser and false-pass fixes.
- Any app code.

## 2.5 Grounding brief

### 1. Producer → owner → consumer

**The thing being changed is "a command that drives the device".** Producers, each with its carrier:

| Producer | Carrier the hook sees | Mechanism |
|---|---|---|
| A session typing `adb shell input tap …` | Bash tool, `command` string | `scripts/hooks/command-safety.py:274` `main()` reads `tool_input.command`; `shell_tokens` → `command_segments` → `executable` |
| `python3 scripts/uat/wispr_eyes.py …` | Bash tool; the inner adb is a `subprocess` inside Python | invisible to the hook by design; the harness IS the road |
| `scripts/uat/grpc-take.sh` etc. | Bash tool sees `scripts/uat/grpc-take.sh`, not the adb inside | a raw door wearing a script name; deleted, not matched |
| `rail_signals.py` | same as the harness | moved onto harness calls so it is not a second implementation |
| Appium MCP tools | MCP tool call, NOT Bash | the Bash hook cannot see it; removal is at the registration |
| `adb emu avd hostmicon` inside `hear_on_emulator` (`wispr_eyes.py:1862`) | subprocess | stays; becomes the body of `set_host_mic` (proposed) |

Command: `/usr/bin/grep -n 'subprocess\|adb\|grpcurl\|hostmic' scripts/uat/rail_signals.py` (pasted in Gate 0).

### 2. Existing authority
- Journal + restore: `_owe`/`_settled`/`_restore_one`/`restore` (`wispr_eyes.py:192,214,2104,2206`),
  ten kinds enumerated in §5. New kind: `host-mic` (proposed). Scope: `device()`.
- Take ownership: `open_recorder` context manager (`wispr_eyes.py:1431`), the ONLY start; `_dictation`
  refuses "start" (`:1272`). `dictate_emulator` (proposed) uses `open_recorder`, never a coordinate.
- Host mic: `hear_on_emulator` (`:1862`) already wraps `hostmicon`. Generalised to `set_host_mic`.
- Emulator identity: `is_emulator` (`:1857`) is prefix-only today; it gains `getprop ro.kernel.qemu` == `1`
  (round 1 axis 1: an explicit `WISPR_SERIAL` is checked only for attachment, `:328-340`).
- Hook shape: `check_connected_android_test` (`command-safety.py:176`) is the precedent for "deny a device
  shape, name the road, allow the emulator variant".
- Launch: `scripts/enviouswispr-emulator.sh` boots `EnviousWispr_Android_16` (no `-grpc`); `launch-grpc.sh`
  boots the Play AVD (`PLAY_AVD`) with `-grpc 8554`. `launch_emulator` (proposed) takes the AVD name and always
  passes `-grpc 8554 -allow-host-audio`; the shell launcher stays for the non-Play AVD (it also installs the
  APK, which is a build concern).
- Negative: no unlock exists: `/usr/bin/grep -n 'unlock\|KEYCODE_ENTER\|input text' scripts/uat/wispr_eyes.py`
  → 0 hits. `new authority proposed`: `unlock_emulator` (proposed).

### 3. Prior attempts and live direction
Gate 0 comment on #177. Binding: harness is the default eye (device-testing.md RULE:
drive-the-phone-with-wispr-eyes); gRPC feed is canonical, BlackHole fallback (RULE:
feed-emulator-audio-over-grpc-injectaudio-not-blackhole); Astra ONE-FRONT-DOOR. Catalog: not consulted, no
product behaviour changes.

### 4. Boundaries a naive design misses
- **Physical phone vs emulator.** `ready` gains one branch: keyguard up AND `is_emulator` →
  `unlock_emulator`, then re-read the keyguard; keyguard up AND phone → the existing refusal, verbatim.
- **Recording lock.** `RECORDING_IS_OFF` checks at `wispr_eyes.py:1452,1519,2255,2457,2507` (grep
  `RECORDING_IS_OFF`, six hits, one is the definition) become `if RECORDING_IS_OFF and not is_emulator()`.
  The phone path is byte-identical.
- **Host mic state across processes.** The harness is one process per errand; the mic state is journaled
  to `~/.cache/wispr-eyes/restore.json` under kind `host-mic` so `restore` from a later process turns it
  back.
- **gRPC endpoint reachability.** `inject_audio` (proposed) first calls `getStatus` on `localhost:8554`; a
  refusal is `Blocked` naming `launch_emulator`.
- **Take lifecycle on the emulator.** `open_recorder` already ends the take in `finally`; a failed
  inject still cancels. `open_recorder` POPS the per-process `restored_for` allowance (`:1464-1470`),
  so every take is preceded by its own `restore()`; `rail_signals.py` and `dictate_emulator` both do.
- **Hook fail-open.** `command-safety.py:274-281` wraps only the PARSE in its `try`; the check calls run
  outside it, and an uncaught exception exits 1 with a traceback (Claude Code treats exit 1 as a
  non-blocking error, so the command still runs; it is noise, not a block). `check_raw_device_driving`
  is wrapped in its own `try/except Exception: return` so it is silent as well as open.

### 5. High-risk premises
- P1 `grpcurl` reaches `EmulatorController` on 8554 on the running AVD: measured this session (method
  list pasted in the Astra prompt).
- P2 The unlock recipe opens the AVD: measured this session (locked → `mScreenLocked=false`).
- P3 `injectAudio` needs an open mic and a `timestamp` per packet: `device-testing.md` RULE:
  feed-emulator-audio-over-grpc-injectaudio-not-blackhole, proven 2026-09-13.
- P4 The hook cannot see an MCP tool call: `settings.json` matchers are `Edit|Write|MultiEdit` and `Bash`
  only.
- P5 `test_wispr_eyes.py` runs with no device: 86 passed this session.
- P6 gRPC `getMicrophoneState.realAudioEnabled` TRACKS the console toggle and `setMicrophoneState` flips
  the same switch: measured 2026-09-20 on the running AVD (`hostmicoff` → `{}`; `hostmicon` → `true`;
  `setMicrophoneState false` → `{}`; `setMicrophoneState true` → `true`). So the console is not needed
  for the mic and the state is READABLE, which the console never offered. Whether the app HEARS the
  same under the gRPC switch is proven by the full take in §11.1, not by this measurement.
- P7 The AVD's host mic was found OFF at the start of this session: `grpc-take.sh` turns it off and never
  back on. That is the leak the `host-mic` journal closes.

## 3. Design

**One public surface, private transports.** New public calls in `wispr_eyes.py`, all raising `Blocked`
rather than guessing, all emulator-only by `is_emulator` unless stated:

```python
def launch_emulator(avd=PLAY_AVD, restart=False) -> str      # boot with -grpc 8554 -allow-host-audio, wait for sys.boot_completed; reuse a booted one unless restart
def unlock_emulator() -> str                                 # wake, swipe, PIN 1234, ENTER, read isKeyguardShowing back; no-op when open
def set_host_mic(on: bool) -> str                            # read previous via gRPC getMicrophoneState, JOURNAL it, then setMicrophoneState, then read back; Blocked keeps the debt
def inject_audio(pcm_path) -> str                            # 48 kHz mono s16le -> ~100 ms AudioPackets with timestamps -> grpcurl injectAudio; requires recording() True; the ONLY packet builder
def dictate_emulator(sentence, target_package="com.google.android.gm") -> list[str]   # say -> pcm, set_host_mic(False), with open_recorder(): wait 3 s, inject_audio, stop; then last_take() and the editor's own text
def debug_insert(text) -> list[str]                          # am broadcast com.envi.wispr.debug.INSERT (DEBUG build only), then the insertion api= line and the field text
```

All six are (proposed); `PLAY_AVD` (proposed) is the constant `EnviousWispr_Android_16_Play`.
`ready` unlocks the emulator. `hear_on_emulator` (removed) becomes `set_host_mic(True)` (one caller,
`say_into_emulator`). Shell form gains `launch unlock mic inject dictate-emu insert`.

**Emulator identity, hardened (round 1).** `is_emulator` = serial prefix `emulator-` AND
`getprop ro.kernel.qemu` reads `1`; anything else is False. `unlock_emulator` repeats the probe
immediately before its first `input`, and raises `Blocked` on any unclear answer without sending input.

**Host mic over gRPC, console dropped (P6).** `set_host_mic` reads `previous` from `getMicrophoneState`,
writes `("host-mic", previous)` to the journal and reads the entry back BEFORE `setMicrophoneState`, then
reads the state back; on a non-matching read-back or any exception the debt stays. The only console
command left in the harness is `adb emu kill` inside `launch_emulator(restart=True)`.

**Restore across processes (round 1 axis 4).** `restore` iterates the host book plus EVERY serial in the
book that is currently attached (`devices()`), not only `device()`; a serial owed but not attached is
reported as `not attached, debt kept`. So a later plain `restore()` from a phone-selected process still
puts the emulator's mic back. Each foreign serial is restored through `_restore_one(entry, serial=…)`
(a new keyword; `_adb` gains the same) WITHOUT touching `_STATE["serial"]`, so `device()` and
`restored_for` stay on the originally selected device (round 2).

**Hook.** `check_raw_device_driving` (proposed) in `command-safety.py`: normalise an executable named
`$ADB` or `${ADB}` to `adb` first (the tokenizer keeps the variable literal); for an executable whose basename is
`adb`, skip `-s <serial>`/`-d`/`-e`, then deny when the subcommand is `emu`, or is `shell` and the first
shell word is one of `input uiautomator screencap screenrecord monkey`; for `grpcurl`, deny when any arg
contains `EmulatorController`. Allowed and asserted: `adb shell dumpsys`, `adb logcat`, `adb install`,
`adb shell am instrument`, `adb shell pm`, `adb devices`, `grpcurl … list`. The denial names the harness
call for the shape it saw. Same threat model as the file docstring: cooperative agents, no adversary,
no grammar parser.

**Appium.** Delete `device-testing.md` RULE: use-appium-to-read-the-screen-not-screenshots and the
Appium row in its tool table; remove the appium-mcp registration (removed) (location: project or user MCP
config, found at build time; the config file is a hook-protected read so it is enumerated then, not here).
RC=137 fallback: `tree` already retries once; on a second 137 the harness returns `Blocked` naming
`shot` and `overlay` as the degraded eyes.

**Rejected:** (a) keep four doors and document them: the failure is discovery, not documentation.
(b) a hook that whitelists only `python3 scripts/uat/wispr_eyes.py`: refuses `adb logcat`, `adb install`
and every read a session needs. (c) the gRPC key and touch RPCs (external) as the transport for unlock: not measured,
and adb input already works.

## 3b. Ownership justification
The calls live in `wispr_eyes.py` because it already owns the journal, the take, the device choice and the
tests; the alternative was a new emulator module, but a second module is a second door.

## 4. Contract deltas
- `ready`: on an emulator, a locked screen is OPENED, then verified; on a phone, unchanged refusal.
- `RECORDING_IS_OFF`: now means "no take starts on a PHYSICAL phone from this harness"; the emulator is
  exempt because a leaked take there costs nothing and is killed by `restore`.
- `hear_on_emulator` (removed) → `set_host_mic(True)`; now over gRPC, journaled, and read back.
- Journal kind `host-mic` (proposed): value `"on"|"off"` read from `getMicrophoneState` before the change,
  restored with `setMicrophoneState` and read back.
- `restore`: now covers every attached serial in the book, not only the selected one.
- `is_emulator`: now also requires `ro.kernel.qemu` = `1`.
- Hook: the Bash tool now refuses five adb shapes and one grpcurl shape everywhere in this repo.

## 5. State and lifecycle audit
| Population | Enumerated |
|---|---|
| Every `RECORDING_IS_OFF` read | `/usr/bin/grep -n RECORDING_IS_OFF scripts/uat/wispr_eyes.py` → 249 (definition), 1452, 1519, 2255, 2457, 2507; all five reads get the `is_emulator` scope |
| Every `ready` caller | `:992, 1190, 1470, 2257, 2375, 2459, 2509`; none passes a device, all inherit the unlock branch |
| Every journal kind in `_restore_one` | `grep -n 'what ==' ` over `wispr_eyes.py:2104-2206` → `mac-volume, mac-input, log-buffer, take, parked-fixture, media-volume, a11y-state, a11y-services, switch, screen-timeout` (ten); `host-mic` is the eleventh, with a read-back. `_restore_locked` (`:2238`) formats every kind as `<kind> back to <value>`, so `host-mic` reads `host-mic back to on` with no formatter change |
| Every raw-transport site in tracked scripts | `grpc-take.sh, inject-audio.sh, launch-grpc.sh, debug-insert.sh` (deleted), `rail_signals.py:150-167` packet builder (deleted; `inject_audio` owns packets and takes raw PCM), `:165` → `inject_audio`; `:448` → `set_host_mic(False)`; `:180/185` → `open_recorder`; `:452` `am start VIEW about:blank` → the harness's `_start`; `:177/188` screenrecord+pull stay outside the take owner. Per-signal order: `restore()` → write PCM → `set_host_mic(False)` → start screenrecord → `with open_recorder(): inject_audio(pcm)` → stop screenrecord → `restore()` |
| Every knowledge/skill pointer at the deleted scripts | `device-testing.md:344,446-447`, `SKILL.md:86`, `rail_signals.py:18`, `docs/feature-requests/issue-26-2026-09-16-microphone-picker-and-bluetooth.md:385` (historical, left), `docs/internal/audits/**` (frozen snapshots, left) |
| Every host-mic console site (grep for hostmic at plan time) | `wispr_eyes.py` `hear_on_emulator` (removed), `grpc-take.sh` (removed) line 27, `launch-grpc.sh` (removed) line 19, `rail_signals.py` line 448 (now `set_host_mic`), `enviouswispr-emulator.sh` (unchanged, the non-Play launcher) |

## 6. Consumer matrix
| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `ready` unlocks emulator | 7 callers above | Blocked on locked AVD | proceed | no | `test_wispr_eyes.py` new row with a fake keyguard=true + emulator serial |
| `hear_on_emulator` removed | `say_into_emulator` | calls it | `set_host_mic(True)` | yes | test row |
| Hook denies raw shapes | every session | allowed | denied with road | yes | `test-hooks.sh` both ways |
| Scripts deleted | `rail_signals.py` docstring, `device-testing.md`, `SKILL.md` | cite them | cite calls | yes | `scripts/check-cited-symbols.py` |
| Appium removed | `device-testing.md` tool table, MCP config | present | absent | yes | grep for appium → 0 in `.claude/` and `scripts/` |

## 7. Failure modes
| Failure | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| gRPC port closed | AVD launched without `-grpc` | `inject_audio` | `BLOCKED: … launch_emulator() does` | none | after relaunch |
| PIN rejected / still locked | wrong AVD or PIN changed | `unlock_emulator` | `BLOCKED: the emulator stayed locked after one attempt` | none | none; one attempt only |
| mic read-back mismatch | gRPC refused or emulator busy | `set_host_mic` | `BLOCKED: …` with requested and read values | `host-mic` debt kept | next `restore` |
| inject while mic closed | caller order | `inject_audio` | `BLOCKED: recording() is False` | none | none |
| take start fails | app not installed | `dictate_emulator` | existing `open_recorder` message | journal `take` settled | none |
| second uiautomator 137 | dump race | `tree` | `BLOCKED: … shot() and overlay() still answer` | none | caller |
| hook self-error | tokenizer | any Bash | allowed (fail open) | none | n/a |

## 8. Caller-visible signals
- `restore` output lines gain `host microphone back on|off`.
- `dictate_emulator` report lines start with `ISSUE`/`BLOCKED`/`OK` like `test_dictation`; the shell exit is 1 on any ISSUE/BLOCKED.
- Hook denial text carries the exact harness call.
- `restore` reports `host-mic back to on|off` through the existing formatter at `wispr_eyes.py:2238`.
Not present in this change: any app signal.

## 9. Fallback source-of-truth audit
| Branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| unlock | `isKeyguardShowing` | `dumpsys activity activities` | the window manager owns the keyguard (existing `ready` comment) | exactly one value, `false` | Blocked | `ready` |
| host mic | `realAudioEnabled` | gRPC `getMicrophoneState` | the emulator's own switch, readable (P6) | read-back equals the requested value | Blocked, debt kept | `set_host_mic` |
| take result | editor text + `insertion api=` line | tree + logcat | the field is the oracle, not the clipboard | expected text equals field | ISSUE | `dictate_emulator` |

## 10. File-by-file
- `scripts/uat/wispr_eyes.py`: `PLAY_AVD`, `launch_emulator`, `unlock_emulator`, `set_host_mic`,
  `inject_audio`, `dictate_emulator`, `debug_insert` (all proposed above); `ready` branch; three
  `RECORDING_IS_OFF` scopes; `hear_on_emulator` (removed); `_restore_one` `host-mic` arm; `_main` commands.
- `scripts/uat/test_wispr_eyes.py`: rows in §11.
- `scripts/uat/rail_signals.py`: import the harness; replace lines 165, 180, 185, 448, 452.
- `scripts/uat/wispr_eyes.py:9-13` module docstring: add the CLI examples `launch unlock mic inject dictate-emu insert`.
- `scripts/uat/grpc-take.sh`, `scripts/uat/inject-audio.sh`, `scripts/uat/launch-grpc.sh`,
  `scripts/uat/debug-insert.sh`: deleted.
- `scripts/hooks/command-safety.py`: `check_raw_device_driving` (proposed above), called from `main`.
- `scripts/hooks/test-hooks.sh`: rows in §11.
- Gitignored, main checkout: `.claude/skills/wispr-eyes/SKILL.md` (emulator section → calls; insertion
  section → `debug_insert`), `.claude/knowledge/device-testing.md` (Appium rule and row removed; script
  names → calls; RC=137 fallback reworded), `.claude/rules/tools-and-apps.md` (the rule names the hook).
- MCP registration for `appium-mcp`: removed where found.

## 11. Testing
1. Class: harness contract (the harness is the product here; when a row fails, a session drives the phone
   wrong or is refused wrongly).
2. Reverts named per row.
3. Not tested: the real gRPC stream and the real unlock in the unit file (no device); those are §11.1.

### 11.1 Emulator run (stands in for hardware UAT)
- `launch_emulator(restart=True)` → `booted, grpc 8554`.
- Lock with `KEYCODE_SLEEP` through the harness test hook, `ready` → open.
- `dictate_emulator("and I will send the deck tomorrow")` into Gmail compose → `OK` with the field text
  equal, `route=` line printed; then `restore` → host mic back on, draft discarded.
- `debug_insert("The quarterly report is ready.")` → `insertion api=` line and field text.
- `rail_signals.py` one run → its report unchanged in shape.
- State to restore: host mic on, Gmail draft discarded, take settled.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `ready` on emulator serial with fake keyguard=true calls unlock then passes | contract | emulator unlock path | drop the `is_emulator` branch |
| `ready` on phone serial with keyguard=true still raises the owner message AND sends no `input` command | contract | phone refusal intact | make the unlock branch unconditional for every locked device (the assertion on "no input sent" goes red) |
| `inject_audio` with `getStatus` unreachable (fake grpcurl exit 1) → `Blocked` naming `launch_emulator` | contract | §7 row 1 | drop the status probe |
| `unlock_emulator` with keyguard still true after one attempt → `Blocked` "stayed locked", exactly one PIN entry sent | contract | §7 row 2 | loop the attempt |
| `set_host_mic` whose read-back does not match → `Blocked` carrying both values, debt REMAINS in the book | contract | §7 row 3 | settle the debt on failure |
| `dictate_emulator` with recorder start failing → the `open_recorder` refusal, `take` debt settled | contract | §7 row 5 | swallow the exception |
| `tree` with two 137s → `Blocked` naming `shot` and `overlay` | contract | §7 row 6 | retry forever |
| hook: malformed JSON stdin and a tokenizer `ValueError` both exit 0 with no decision | guard | §7 row 7 | raise instead of return |
| `dictate_emulator` with the field text unequal or absent → report starts `ISSUE` | contract | §9 row 3 | accept any changed text |
| `set_host_mic(False)` journals `("host-mic", "on")` BEFORE the fake `setMicrophoneState` call and `restore` sends `setMicrophoneState true` then reads it back | contract | restore | journal after the set |
| `restore` from a process whose `device()` is the phone still restores an attached emulator's `host-mic` debt | contract | cross-process | iterate only `device()` |
| `is_emulator` with serial `emulator-5554` and `ro.kernel.qemu` = `0` → False, and all five recording guards stay closed | contract | axis 1 | prefix-only check |
| `inject_audio` refuses when `recording` is False | contract | order | remove the check |
| `unlock_emulator` whose FIRST probe says `1` and whose pre-input re-probe says `0` or is unclear → `Blocked`, zero `input` commands sent | contract | identity re-probe | drop the second probe |
| `set_host_mic` whose journal read-back is missing or differs → `Blocked`, `setMicrophoneState` never called, debt remains | contract | journal-before-change | drop the read-back |
| `restore` restoring an attached emulator's debt from a phone-selected process leaves `device()` and `restored_for` on the PHONE afterwards | contract | selection preserved | restore by switching selection and not switching back |
| hook: `check_raw_device_driving` raising an ordinary exception (forced by a fake) → exit 0, no decision printed | guard | fail-open of the new check | move the call outside its `try` |
| packet builder: 9600-byte chunks, timestamps ascending, `format` present | contract | P3 | drop `timestamp` |
| `open_recorder` on phone with `RECORDING_IS_OFF` still refuses; on emulator proceeds to `am start` (fake adb) | contract | scope | invert the condition |
| hook: 7 deny rows (`adb shell input tap`, `adb -s emulator-5554 shell uiautomator dump`, `adb shell screencap`, `adb emu avd hostmicoff`, `$ADB shell input text x`, `${ADB} shell input text x`, `grpcurl … EmulatorController/injectAudio`) | guard | wall | remove the check; for the `$ADB` rows, remove the variable normalisation (`executable()` returns basename `$ADB`, `command-safety.py:128-140`) |
| hook: 8 allow rows (`adb logcat -d`, `adb shell dumpsys window`, `adb install -r x.apk`, `adb shell am instrument …`, `adb shell pm list packages`, `adb devices`, `grpcurl -plaintext localhost:8554 list`, `python3 scripts/uat/wispr_eyes.py look`) | guard | road | over-broad match |

## 12. Blast radius & rollback
Touched: `scripts/uat/`, `scripts/hooks/`, gitignored `.claude/` files, MCP config. Not touched: `app/`,
`llama-android/`, CI, the physical-phone silent take (`scripts/uat/silent-audio/`). Rollback: revert the
PR; restore the four scripts from git; re-add the Appium registration by hand.

## 13. Ship criteria
- [ ] **Benchmark, harness versus raw adb, on the emulator (founder ask 2026-09-20).** Six errands run both
  ways: unlock; read one switch; flip it and put it back; one spoken take into Gmail compose; one
  audio-free insert; leave-clean check. Per errand, per way: wall time first command → reported answer,
  commands sent, answer correct (field text / switch state read back by an INDEPENDENT adb read), state
  left dirty (mic, switch, draft, live take). Written to `docs/benchmark-results/2026-09-20-issue-177-harness-vs-raw.md`
  with the raw numbers. The harness may lose on wall time; it must win or tie on correct answers and on
  dirty state, or the PR does not merge.
- [ ] One spoken take on the AVD through `dictate_emulator` lands the sentence in Gmail compose, verified by the field text.
- [ ] A raw `adb shell input tap 1 1` in this repo is refused with a message naming `tap`.
- [ ] `grep -rn appium .claude scripts` → 0.

## 14. Open questions
- Where the `appium-mcp` registration lives (read at build; the config file is a protected read).

## 15. Related
#161 (queued harness correctness), #141 (gRPC feed origin), #151 (`rail_signals.py`), Astra audit
`.codex/2026-09-20-emulator-doors-astra.txt.last`.
