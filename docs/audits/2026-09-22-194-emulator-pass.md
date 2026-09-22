# #194 emulator pass, 2026-09-22 (local 23:17–23:23), build from `8dca565` sources

Device: `EnviousWispr_Android_16_Play` AVD (`emulator-5554`), debug APK installed with `adb install -r`, driven through `scripts/uat/wispr_eyes.py` (`WISPR_SERIAL=emulator-5554`). The founder's phone was not attached (his instruction: emulator only this morning).

## Staging note
`adb shell am force-stop` after the install CLEARED the emulator's accessibility settings (the `stop_app()` docstring says so; the raw command was used here). `enable_auto_paste()` then journaled the cleared state as the debt, and `dictate_emulator()` begins with `restore()`, which put the cleared state back and reported `BLOCKED: auto-paste is switched off`. Settled by `enable_auto_paste()` then `_settled()` of the `a11y-state` debt, so ON is the emulator's baseline again. Lesson for the recipe: after an install on the emulator use `stop_app()`, never raw `am force-stop`.

## Scene 1: a spoken take into a real third-party editor
`open_app('com.google.android.gm')`, `tap('Compose')`, `focus_field('Compose email')`, `dictate_emulator('the quarterly marker sentence lands tomorrow')`.

```
VERIFIED: a take ran, ended by a stop request
VERIFIED: 45 characters came back from the speech engine
NOTE: vice: insertion api=36 route=COMMIT written=true returned=VOID evidence=SURROUNDING outcome=VERIFIED attempts=1 ms=54 overrun=false target=com.google.android.gm
VERIFIED: the same editor now holds the sentence; it ends 'The quarterly marker sentence lands tomorrow.' (45 chars)
```

Logcat sweep (`logcat -c` before the scene; `logcat -d -v time` after, 1954 lines, 101 from our processes):

| Grep | Hits |
|---|---|
| `quarterly marker` / `marker sentence` / `lands tomorrow` (case-insensitive, any line) | 0 |
| `insertion api=36 route=COMMIT` (positive control: the take's own outcome line) | 1 |
| `Exception` lines from our tags | 1: `E/PolishService(21961): S1 unavailable; deterministic fallback active (IllegalStateException at S1GenieXRuntime.initializeSdk:137)` |

The one exception line is the new renderer live: class name plus the first in-app frame, no message (the old line carried the vendor SDK's message text).

## Scene 2: the debug insert receiver and the harness prefix
`debug_insert('The second marker report is ready.')` into a fresh Compose:

```
NOTE: vice: insertion api=36 route=COMMIT written=true returned=VOID evidence=SURROUNDING outcome=VERIFIED attempts=1 ms=18 overrun=false target=com.google.android.gm
NOTE: 09-21 23:23:15.170 20999 20999 I DebugInsert: pin=PINNED handoff=SCHEDULED textChars=34
VERIFIED: the same editor now holds the text; it ends 'The second marker report is ready.' (34 chars)
```

`grep 'second marker report'` over logcat: 1 hit, and it is `adbd` echoing the harness's own `am broadcast --es text …` shell command, not an app line. The app's line carries `textChars=34`. The harness's `DebugInsert: pin=` reader still matches.

## Not run
The instrumentation tests (they compile: `:app:compileDebugAndroidTestKotlin`; they are not executed in this change, plan §14). The physical phone (excluded by the founder this morning; the Play build goes to it through internal-testing).

`restore()` at the end: `nothing was changed`; auto-paste left ON on the emulator as its baseline.
