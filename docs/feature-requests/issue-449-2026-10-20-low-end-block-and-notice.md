# Issue #449 chunk 2: In-app low-end block and mid-tier notice, 2026-10-20

GitHub issue: `#449`. Tier: MEDIUM (new runtime behaviour at app start, dictation start and model delivery;
no change to capture, speech, polish or insertion code). Status: DRAFT.

Parent plan: [`issue-449-2026-10-19-portable-local-polish.md`](issue-449-2026-10-19-portable-local-polish.md)
§16.4 (approved direction, founder 2026-10-19). Chunk 1 shipped in #451 (build 279/280).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Consolidation:** none. One new decision (this phone's performance tier) gets one owner,
`DeviceTier` (proposed); three existing funnels read it.

**Hardware UAT:** Y. On the S26 (Standard tier) nothing changes: setup, Settings and a side-button take behave
as today. On the emulator, a debug-only tier override shows the unsupported screen, refuses the side button with
one short message, and starts no model download; a second override shows the mid-tier notice.

## Preface: User Rubric

1. **Who.** Meera Patel, who installed EnviousWispr on a hand-me-down Galaxy A05s (Snapdragon 680) she uses for
   WhatsApp; and Priya on a Galaxy A15.
2. **Why.** Meera: "I want to talk instead of type." On her phone each sentence would take 8 seconds to become
   text (measured on the same chip), so the honest answer is no. Priya: it works, a bit slower.
3. **How.** Meera opens the app after install, or presses the side button after setting a shortcut.
4. **Apps.** Any; the block happens before any app is involved.
5. **Input.** None reaches the app on a blocked phone.
6. **Success.** Meera learns in one screen, before a 0.5 GB download, that this phone is not supported, instead
   of discovering 8-second waits. Priya keeps everything and is told once that it is slower on her phone.
7. **Wrong-not-broken.** A phone that would have worked is blocked (the reason unknown cores are never blocked),
   or the notice makes a working phone sound broken.
8. **Hack.** Sideloading on a blocked phone still meets the same in-app screen.
9. **Control.** None added: support is a property of the phone, not a setting.

### Cross-persona check
Frank Chen on an older budget phone is the case the block protects; every flagship persona sees no change.

## 0. TL;DR

Read the phone's processor core designs once. Blocked tier (every core an old design: A53, A55, A73, A75 and the
Qualcomm cores built on them): the app shows one "not supported" screen instead of setup or Settings, refuses
every dictation start with one short message, and the model worker refuses downloads. Mid tier (no I8MM, so the
portable runtime class): one notice in setup and one line under "This phone" in AI polish settings. Everything
else unchanged.

## 1. Problem (evidence, Test Lab 2026-10-19/20, synthetic speech, real speech service)

| Phone (chip, cores) | Transcript of a 3 s sentence | 57 s of speech | Polish, one line | Tier |
|---|---|---|---|---|
| Galaxy A05s (Snapdragon 680; A73/A53, parts `0xd09`,`0x801`) | 8.0 s | 40 s | 4.7 s (real service) | Blocked |
| Moto g04 (Unisoc T606; A75/A55, `0xd0a`,`0xd05`) | 7.4-7.6 s | 34 s | 3.1-3.7 s | Blocked |
| Galaxy A15 (Helio G99; A76/A55, `0xd0b`,`0xd05`) | 3.6-5.0 s | 15-16 s | 2.5 s | Mid |
| Galaxy A34 (Dimensity 1080; A78/A55, `0xd41`,`0xd05`) | 2.7-2.9 s | 14.5 s | 2.2 s (real service) | Mid |
| Galaxy A35 (Exynos 1380; A78/A55) | 2.0 s | 8.7 s | 1.7 s (real service) | Mid |
| S26 Ultra (owned) | ~0.7 s | ~3 s for 40 s | 0.2 s | Standard |

The A15 is the borderline case and the most common chip family in the catalog (Helio G99/G96, Dimensity
6100+/6300: 642 of 1,402 mid-tier models). Its sentence-length wait is about twice the A34's; the blocked phones'
is about three times. This plan keeps it mid tier; §14 asks the founder.

## 2. Goals & non-goals

### 2.1 Goals
1. A blocked-tier phone never downloads a model, never loads one and never starts capture.
2. It always shows the same one-screen explanation, whatever entry the user tries.
3. A mid-tier phone keeps every feature and sees the notice once in setup and as a line in AI polish settings.
4. Unknown or incomplete processor information never blocks.
5. A stored model or user data on a phone that becomes blocked (upgrade) is kept, not deleted.

### 2.2 Non-goals
Play exclusion and the published phone list (chunk 3); any change to runtime choice (`S1RuntimeKind`); deleting
anything; a user override.

## 2.5 Grounding brief

### 1. Producer → owner → consumer
- Dictation starts: every surface calls `DictationSessionService.sendCommand` (`ui/DictationSessionService.kt:65`):
  side button `VoiceInputActivity` (`ui/VoiceInputActivity.kt:72`), tile `DictationTileService`
  (`shortcuts/DictationTileService.kt:42-49`, via the activity when idle), in-app button (`ui/SettingsActivity.kt:104-105`,
  via the activity), bubble `PasteAccessibilityService.startDictationFromBubble`
  (`paste/PasteAccessibilityService.kt:260-267`), onboarding practice (a bubble take). The notification has no
  start action (`shortcuts/DictationNotificationController.kt:164-178`). START/TOGGLE use
  `startForegroundService` (`DictationSessionService.kt:69-71`), so a refusal must happen in `sendCommand`,
  before the service is started, or Android requires a foreground notification within seconds.
- Downloads: `ModelDeliveryWorker.doWork` (`models/ModelDeliveryWorker.kt:26`) is the only caller of
  `ModelDeliveryStore.download` (`:95`); every enqueue helper is in its companion (`:295-368`). Removal also runs
  through `doWork` (`:35-41`) and must stay allowed.
- Model loads: speech loads in `AsrService.onCreate` (`asr/AsrService.kt:262`) on any bind; binds come only from a
  session (`ui/PipelineBindings.kt:100`) and `EngineWarmUp` (`ui/EngineWarmUp.kt:78`), which runs only from
  onboarding (`ui/OnboardingScreen.kt:84-88`). Polish loads from warm-up, a session, or the Settings processing
  check (`polish/ProcessingCheckClient.kt:54`, from Settings).
- First screen: `EnviousWisprApp` (`ui/AppShell.kt:39`) shows onboarding when `shouldShowOnboarding`, else the
  full app; the unsupported screen goes after the loading block and before that branch.
- "This phone" body: `PolishScreen.kt:154-177`; a one-line `QuietCard` helper exists (`PolishScreen.kt:221`).

### 2. Existing authority
No device-tier code exists (no `SOC_MODEL`, `isLowRamDevice` (external), cpuinfo reads in `app/src/main`).
`telemetry/SupportedPlatform.kt` decides only the review-sandbox telemetry filter and has no UI; it stays
separate. `polish/S1RuntimeKind.kt` owns runtime choice and its `CpuFeatures` parser is reused for the I8MM bit.

### 3. Prior attempts
None for a tier gate. Codex grounded round 6 of the parent plan set the rules adopted here: complete processor
identification only, unknown allows, tier decided apart from runtime kind, enforcement at every funnel.

### 4. Boundaries
Main process: AppShell and `sendCommand`. WorkManager process: the worker runs in main. `:asr`/`:polish` are only
reached through those funnels. Upgrade: an installed blocked-tier phone (for example the reported A23) keeps its
files; the app shows the screen on next open.

### 5. Premises
| Premise | Evidence |
|---|---|
| Every start goes through `sendCommand` | Explore sweep 2026-10-20 (file:line above); re-verified with `grep -rn "sendCommand\|ACTION_START\|ACTION_TOGGLE" app/src/main` at build |
| `/proc/cpuinfo` part lists for the measured phones | Test Lab logs 2026-10-19/20 (`cpuParts` lines), table §1 |
| Linux lists only online processors | grounded round 6; handled by the completeness rule |

## 3. Design

`device/DeviceTier.kt` (proposed): enum `BLOCKED` (proposed), `MID` (proposed), `STANDARD` (proposed); `DeviceTier.current` computed once per
process.
- Parse a nonempty set of possible CPU IDs from `/sys/devices/system/cpu/possible` (comma-separated ranges such as
  `0-3,4-7`). Require exactly one `/proc/cpuinfo` `processor` record for each ID, no duplicate or extra IDs, and a
  valid `CPU implementer` and `CPU part` in every record. Any read or parse failure, or an offline core missing
  from the list: not blocked.
- `BLOCKED` only if complete and every core is in `OLD_CORES` (proposed): Arm (`0x41`) A35 `0xd04`, A53 `0xd03`,
  A55 `0xd05`, A73 `0xd09`, A75 `0xd0a`; Qualcomm (`0x51`) Kryo 2xx/3xx/4xx-silver `0x800`, `0x801`, `0x802`,
  `0x803`, `0x805` (A73/A53/A75/A55 derivatives per pytorch/cpuinfo `src/arm/uarch.c`; `0x804`, A76-class, is
  deliberately absent). `OLD_CORES` (proposed) is a conservative support-policy list, not an exhaustive pre-A76
  classification: A57 `0xd07`, A72 `0xd08`, Samsung custom cores and unknown parts stay allowed pending evidence.
- Otherwise `MID` when `CpuFeatures.read()` lacks `HWCAP2_I8MM`, else `STANDARD` (unknown features: `STANDARD`,
  no notice).
- No override of any kind in the shipped code (grounded round 1: a debug override is a guard bypass under
  `test-honesty.md` RULE: a-test-seam-on-a-GUARD-is-a-bypass). The classifier is a pure function of the two file
  texts, unit-tested with fixtures. The emulator stages `BLOCKED` and `MID` with a disposable APK built from a
  private, uncommitted copy whose `DeviceTier.current` returns the staged tier; the real enforcement paths run
  unchanged, and that APK is never committed or distributed.

Enforcement (three funnels, one decision):
1. `EnviousWisprApp`: `BLOCKED` renders `UnsupportedPhoneScreen` (proposed) and returns, before onboarding and the
   full app, so no setup step, Settings check or in-app button exists.
2. `DictationSessionService.sendCommand`: `BLOCKED` with START or TOGGLE shows a short toast and returns before
   any service start. STOP and CANCEL pass through unchanged. `VoiceInputActivity` checks the tier before its
   microphone-permission check (`ui/VoiceInputActivity.kt:47-57`) so a blocked phone is never sent to grant a
   permission it cannot use; it shows the same toast and finishes.
3. `ModelDeliveryWorker.doWork`: `BLOCKED` refuses every non-removal request with a failure reason
   `unsupported phone` before any network or file work; removal still runs.

Mid-tier notice: one sentence on the onboarding welcome step and a `QuietCard` above `S1Card` under "This phone".

Copy (no dashes; content review at sign-off):
- Screen title: "This phone isn't supported"
- Screen body: "This phone's processor is outside the range EnviousWispr currently supports. Dictation and model
  downloads are turned off on this phone."
- Start refusal toast: "EnviousWispr isn't supported on this phone"
- Mid-tier line: "Dictation and on-device AI polish may be slow on this phone."

Rejected: blocking by chip name (regional variants, unseen chips); blocking by RAM (A05s fails on processor, not
memory); blocking inside the session coordinator only (foreground-service start already happened).

## 3b. Ownership
`DeviceTier` lives in a new `device/` package because it is about the phone, read by UI, session and model
delivery; putting it in `polish/` would make two unrelated domains depend on polish.

## 4. Contract deltas
New: `DeviceTier.current`. `sendCommand` may now refuse START/TOGGLE (only on `BLOCKED`). `doWork` may now refuse
a download (only on `BLOCKED`). No AIDL, schema or preference change.

## 5. State and lifecycle audit
| Population | Answer |
|---|---|
| Start entry points | side button, tile, in-app, bubble, onboarding practice: all `sendCommand`; notification PendingIntents carry only STOP/CANCEL, which cannot admit an idle take (`ui/DictationSessionCoordinator.kt:263-281`); capture, speech, VAD, polish and session services are not exported (`AndroidManifest.xml`) |
| Coverage limit | The inspected entry routes are covered. Exhaustive model-load coverage is NOT VERIFIED: Settings view-model initialization (`ui/SettingsActivity.kt:40-62`, read at build), readiness helpers, pipeline bindings and native service initialization are re-read at build |
| Download producers | onboarding, speech card, S1 card, notification resume, app-start bootstrap: all `doWork` |
| Model load producers | session, onboarding warm-up, Settings processing check: unreachable on `BLOCKED` (no UI, no session) |
| Upgrade onto a blocked phone with models | files kept; screen shown; no load because no session or UI path |
| Tier changing mid-process | impossible: cores and features are fixed |
| Process death | recomputed after process death; an incomplete snapshot may produce a different, allowed result |

## 6. Consumer matrix
| Delta | Consumer | Now | Required | Change? | Verified by |
|---|---|---|---|---|---|
| tier | `EnviousWisprApp` | onboarding or app | screen on `BLOCKED` | yes | emulator override |
| tier | `sendCommand` | always starts | refuses on `BLOCKED` | yes | unit test + emulator |
| tier | `ModelDeliveryWorker.doWork` | downloads | refuses on `BLOCKED` | yes | unit test |
| tier | onboarding welcome, `PolishScreen` | none | mid notice | yes | emulator override |

## 7. Failure modes
| Failure | User sees |
|---|---|
| cpuinfo unreadable or incomplete | app as today (never blocked) |
| A phone with old cores everywhere but fast clocks | blocked; accepted: no such shipping phone is known with A75 or older cores only that measured fast |

## 8. Signals
No telemetry added. One local log line at startup names the tier.

## 9. Fallback source of truth
Not applicable: no limb fallback changes.

## 10. File-by-file changes
`device/DeviceTier.kt` (proposed) with tests; `ui/AppShell.kt`; `ui/UnsupportedPhoneScreen.kt` (proposed);
`ui/DictationSessionService.kt`; `models/ModelDeliveryWorker.kt`; `ui/OnboardingScreen.kt` (welcome line);
`ui/PolishScreen.kt` (mid line); `res/values/strings.xml` if the screens use resources (match the file's style).

## 11. Testing
1. `DeviceTierTest` (proposed) (Product Outcome): literal cpuinfo fixtures from the six measured phones plus incomplete,
   unknown-part and Samsung-custom rows; revert: return `BLOCKED` for everything, or never.
2. Worker refusal test (Product Outcome): a blocked tier returns failure and never calls download; removal still
   runs. Revert: drop the check.
3. `sendCommand` refusal test if the existing service tests allow a seam without a guard bypass; otherwise
   covered by the emulator run.
4. Not tested: every chip on the market.

### 11.1 Hardware UAT
S26: open app, Settings, a side-button take into Messages (unchanged). Emulator: debug tier override `BLOCKED`
(screen shown, side button refused, no download started in WorkManager), then `MID` (notice shown), then
override removed and the emulator's original app restored.

## 12. Blast radius & rollback
Touches app start screen choice, dictation start command, model download worker. Revert the PR to remove.

## 13. Ship criteria
- [ ] A blocked phone shows one screen and nothing downloads (emulator override).
- [ ] The S26 behaves exactly as today.

## 14. Open questions (founder)
1. **Galaxy A15 class (Helio G99 and similar).** Keep as mid tier with the notice (recommended: about twice the
   A34's wait, and the most common chip family), or block it too.
2. **Copy** in §3: approve or edit.

## 14b. Grounded round 1 (Codex, 2026-10-20) adjudication
PROCEED-WITH-REVISIONS. All six adopted: coverage claim narrowed (1); no override, private-copy staging (2);
nonempty one-record-per-ID completeness (3); `OLD_CORES` stated as policy (4); copy replaced with Codex's wording
and heading dashes removed (5); persona phone corrected (6). Plus the `VoiceInputActivity` order fix.

## 15. Related
#449, #451, parent plan §16.
