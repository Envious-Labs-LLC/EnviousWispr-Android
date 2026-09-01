# Dictionary import picker — 2026-08-31

No GitHub issue. Tier: SMALL. Status: APPROVED — Codex plan review PROCEED-AS-PLANNED, round 3, 2026-08-31.

**Post-approval addendum, 2026-09-01:** during hardware UAT the founder asked for two cosmetic changes,
both reviewed by Codex against the built diff rather than re-running plan review, since neither changes
behaviour, state, or the file touched: (1) the Add/Import/Export button row uses tighter padding so it
never needs horizontal scrolling; (2) §3's plain-text picker rows became icon-tile cards with a
description line and a trailing chevron, matching a reference screenshot the founder supplied, with the
disabled "From another app" card keeping its merged TalkBack semantics unchanged.

> Founder request, in chat, 2026-08-31: collapse the four button-row pills to three (Add, Import, Export).
> "Import" opens a picker with three choices — Paste Words, Open a file, From another app — each leading to
> its own next step. The first two already work today and should keep working; the third does not exist
> yet, so it shows "Coming soon" and is greyed out rather than tappable.

**Consolidation:** none. This adds one new picker surface and re-points two existing, working entry points
at it; it does not merge duplicate implementations or dedup repeated logic.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. Purely an Android-side UI reorganization; no macOS reference for a "from
another app" import exists to parity against — see §2.5.3.

**Hardware UAT:** Y — plain English: tap Import, confirm the picker shows three rows with "From another
app" visibly greyed out and untappable, tap Paste Words and confirm the same working paste-import flow
opens, cancel, tap Open a file and confirm the same working system file picker opens, cancel, confirm
Export still works unchanged.

## Preface — User Rubric

1. **Who is this user in this moment?** Frank Chen, 72, retired teacher, who has a `.txt` list of names
   from his old class roster he wants to bring in, and is looking at the Dictionary page trying to figure
   out how.
2. **Why would they want this?** He doesn't want to type fifteen names one at a time. "Import" is the
   obvious word for "get my list of words in here"; today it is silently split into two similar-sounding
   buttons ("Import file" and "Paste import") that make him guess which one matches "I have a text file".
3. **How would they invoke it?** From the Dictionary page's button row. Voluntary, not reactive; he
   already knows he wants to bring in a list before he taps anything.
4. **What app are they in?** N/A — this is the Dictionary page and the screens it opens, not an
   in-the-moment dictation flow.
5. **What is their natural input?** N/A for this change; the input in question is prior text he already
   has (a file or pasted text), not speech.
6. **What does success feel like?** He taps Import once, sees three plainly labelled choices, picks the
   one that matches what he has, and the thing he expected to happen happens — no dead end, no button that
   looks clickable but silently does nothing.
7. **What does wrong-not-broken look like?** "From another app" LOOKS tappable and does nothing, or looks
   identical to the two working choices, so he taps it expecting an app picker and gets silence. That is
   exactly what "grey it out" and "Coming soon" exist to prevent — the greying must be visually obvious
   enough that a 72-year-old on a bright screen still reads it as off, not broken.
8. **What would a power user hack around this to get?** Priya Ramachandran, wanting to pull her terms out
   of Gboard's personal dictionary, would export from Gboard's own settings and paste the result in —
   which is exactly the existing "Paste Words" path, so the placeholder costs her nothing today.
9. **What level of control would they want?** A named source for exactly where their words come from
   (paste, file, or another app), not a single opaque "Import" that guesses. The three-way picker IS that
   control; the placeholder is honest about which of the three actually works today rather than pretending
   all three do.

### Cross-persona check
No disagreement. Every persona reads "Import" then a named choice as clearer than two same-shaped buttons
guessing at "file" versus "paste" for them; none of the seven has a use for "from another app" today, so
placeholder-and-grey costs nobody a capability they currently have.

## 0. TL;DR

Replace the two existing import buttons ("Import file", "Paste import") and keep "Add"/"Export" as three
pills: Add, Import, Export. "Import" opens a small picker with three rows — Paste Words, Open a file, From
another app — the first two calling the exact same working code the old two buttons called, the third
shown disabled with a "Coming soon" label. No data model, preference, or DictationSessionService change;
Compose-only.

## 1. Problem

Not a bug. Founder, in chat: the four-pill row is confusing because two of the four pills ("Import file",
"Paste import") are both forms of "import" with no shared entry point naming that, and there is no path
today toward importing from another app at all.

## 2. Goals & non-goals

### 2.1 Goals
- The button row shows exactly three pills: Add, Import, Export, in that order.
- Tapping Import opens a picker with three rows: Paste Words, Open a file, From another app.
- Paste Words and Open a file each still do exactly what today's "Paste import" and "Import file" buttons
  do — same dialog, same file picker, same `onImport` callback, unchanged behaviour.
- From another app is visibly disabled (dimmed, non-clickable) and labelled "Coming soon".

### 2.2 Non-goals
- Not building a real "from another app" import. That is future work with no design yet.
- Not touching Add, Export, search, the term list, per-term edit/delete, or multi-select — all shipped
  earlier today and unrelated to this change.
- Not changing what `VocabularyImportDialog` or the file-picker `ActivityResultContracts.OpenDocument`
  flow DO internally — only how a user reaches them.
- Not adding a nav-graph destination or back-stack entry. This app has no multi-screen stack inside a tab
  today (every other "next step" in Dictionary — add, edit, delete-confirm, paste-import — is a full-width
  `AlertDialog`, not a pushed screen); the picker and its "coming soon" row follow that same existing
  pattern rather than introducing new navigation infrastructure for one placeholder row. Named because the
  founder said "new screen" — the visual result reads as a screen (full width, its own title, covers the
  content beneath it) even though the mechanism is the same `AlertDialog`-based overlay every other
  Dictionary flow already uses.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

- `app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt:300-344` — the button row, currently four
  `OutlinedButton`s: Add (`showNewEditor = true`), Import file (`importFile.launch(...)`), Paste import
  (`showImport = true`), Export (inline clipboard-copy `onClick`).
- `DictionaryScreen.kt:260-276` — `importFile`, an `ActivityResultContracts.OpenDocument()` launcher whose
  callback reads the picked file and calls `onImport` (the screen's own parameter, wired from outside).
- `DictionaryScreen.kt:278-282` — the screen's local dialog-visibility state: `showNewEditor`, `editTarget`,
  `deleteTarget`, `confirmBulkDelete`, `showImport` (line 277, `selectedIds`, is multi-select state, not a
  dialog flag — kept separate here since it is not part of this change).
- `DictionaryScreen.kt:453-460` — the paste-import call site, gated on `showImport`, calling
  `VocabularyImportDialog` and forwarding to `onImport`. `DictionaryScreen.kt:578-610` — that dialog's own
  definition, further down the same file (line 409 begins the unrelated add/edit dialog, not this one).
- `onImport: (String) -> Unit` is `DictionaryScreen`'s own parameter (signature at line 256), wired from
  `AppShell.kt`'s `EnviousWisprApp` call site to `onImportCustomTerms`, in turn wired in
  `SettingsActivity.kt` to `viewModel::importCustomTerms`. **None of this chain changes** — both new picker
  rows call the exact same `onImport`/`importFile` already in scope in `DictionaryScreen`.

### 2. Find the existing authority before proposing one

`showImport`/`VocabularyImportDialog` and `importFile` are the existing owners of "paste text" and "pick a
file" respectively; this change re-points their triggers, it does not replace either. No new authority is
proposed for those two paths. For the third path ("from another app") there IS no existing authority —
confirmed by the grep in §2.5.5 — so the placeholder is honestly a stub, not a wrapper around something
real.

### 3. Read prior attempts and live direction

`session-log.md`'s two earlier 2026-08-31 entries built the current button row and, separately, removed
the vocabulary on/off toggle from the same screen. Neither touched the import buttons' grouping. No catalog
`decision` row exists for an import-picker shape on any platform — checked `SELECT * FROM decision WHERE
decision_text LIKE '%import%'` against `~/.claude/knowledge/enviouswispr/catalog.db`, no rows. This is a
genuinely new UI decision, not a re-litigation of a settled one.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **Picker open vs. a chosen sub-flow open:** two independent booleans (`showImportPicker`,
  `showImport`) rather than one, so closing the picker and opening Paste Words cannot leave the picker
  showing underneath it. Tapping Paste Words or Open a file sets `showImportPicker = false` in the same
  click handler that starts the next step.
- **The disabled row must not be reachable by any path.** No `Modifier.clickable` is applied to that row at
  all — not even a disabled one — so it has no `onClick` of any kind, live or no-op. A Compose clickable
  modifier always requires a callback to attach to; the way to have none is to not attach the modifier,
  not to attach one and disable it. This matches §2.2's non-goal precisely, rather than half-wiring a
  callback nothing implements. (Corrected by Codex plan review, round 2 — the plan's first pass
  contradictorily said both "no `onClick` exists" and "`enabled = false` on the clickable," which cannot
  both be true, since a clickable's `enabled` parameter has nothing to disable without an `onClick` in the
  first place.)
- **Dismissing the picker (tap outside, back press)** behaves like every existing Dictionary dialog: closes
  without side effects, per `AlertDialog`'s own `onDismissRequest` convention already used four times in
  this file.

### 5. Prove the high-risk premises

| Claim | Evidence |
|---|---|
| `showImport`/`VocabularyImportDialog` is the only Paste-Words implementation | `grep -n "showImport\|VocabularyImportDialog" app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt` — one dialog definition, one state flag, one trigger site (being moved, not duplicated) |
| `importFile` is the only file-import implementation | `grep -n "importFile" app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt` — one launcher, one trigger site (being moved, not duplicated) |
| No existing "import from another app" capability anywhere in the app | `grep -rni "app.selector\|import.from.app\|share.target\|ACTION_SEND\|receive.*intent\|another.app" app/src/main --include="*.kt"` — two kinds of hit, both unrelated: code comments about accessibility focus tracking ("another app's text field/focus"), and the real `ModelDeliveryCancelReceiver.onReceive` at `ModelDeliveryNotification.kt:120`, which handles the model-download notification's pause/resume/cancel buttons and is declared `android:exported="false"` in the manifest — an internal broadcast receiver, not a way for another app to hand this one data. `AndroidManifest.xml`'s only `<intent-filter>`s are `MAIN`, `ASSIST`, `AccessibilityService` and `QS_TILE` (lines 21-103) — no receive/share/import filter exists for another app to hand data to this one. (The plan's first pass used `import.*app`, which is too broad — it also matches ordinary Kotlin `import android.app.Activity` lines — corrected by Codex plan review, round 1; the receiver classification corrected round 2.) |
| No test names the button row or the paste/file-import trigger sites | `grep -rn "showImport\|importFile\|Paste import\|Import file" app/src/test app/src/androidTest` — no hits, so nothing needs updating |

## 3. Design

Replace the four-`OutlinedButton` row with three, and add one new picker dialog plus one new boolean:

1. **Row becomes Add / Import / Export.** Add and Export keep their exact existing `onClick` bodies,
   unchanged. The old "Import file" and "Paste import" buttons are removed; a single "Import" button sets
   a new `showImportPicker = true`.
2. **`ImportPickerDialog`**, a new private composable, `AlertDialog`-based like every other Dictionary
   flow (§2.2), showing three rows:
   - **Paste Words** (existing `ClipboardGlyph`) — `onClick`: `showImportPicker = false; showImport =
     true`. Reuses the existing `VocabularyImportDialog` completely unchanged.
   - **Open a file** (existing `UploadGlyph`) — `onClick`: `showImportPicker = false;
     importFile.launch(arrayOf("application/json", "text/plain"))`. Reuses the existing launcher
     completely unchanged.
   - **From another app** (new `AppGlyph`, drawn in the same hand-drawn `Canvas` style as every other icon
     in this file) — row wrapped in `Modifier.alpha(0.45f)`, no `onClick`, subtitle "Coming soon" in
     `bodySmall`/`onSurfaceVariant`. Established here as this file's first "disabled row" pattern since
     none existed before (§2.5.5); the next disabled affordance in this app reuses it rather than
     inventing a second shape.

No other file changes. `onImport`, `importFile`, `VocabularyImportDialog`'s own internals, and every
signature outside `DictionaryScreen.kt` are untouched.

Rejected alternative: a real Android navigation destination (a back-stack entry) for the picker. Rejected
per §2.2 — this app has no such stack inside a tab today, and inventing one for a single placeholder row is
disproportionate; the `AlertDialog` pattern already reads as "a screen" to the user (full width, own
title, covers the list beneath it) without new infrastructure.

## 3b. Ownership justification
No new cross-cutting type. `ImportPickerDialog` and `AppGlyph` are private to `DictionaryScreen.kt`,
exactly like `VocabularyImportDialog` and every existing glyph already are.

## 4. Contract deltas

| Type / signature | Before | After | Consumer impact |
|---|---|---|---|
| `DictionaryScreen`'s button row | 4 `OutlinedButton`s | 3 `OutlinedButton`s (Add, Import, Export) | None outside this file — `DictionaryScreen`'s own external signature (`terms`, `onImport`, etc.) is unchanged |
| Local dialog state | `showImport: Boolean` only | adds `showImportPicker: Boolean` | Local to `DictionaryScreen`; no external consumer |
| `VocabularyImportDialog`, `importFile` | triggered directly from the button row | triggered from `ImportPickerDialog` rows | Internal call-site move only; their own bodies and the outer chain — `onImport` → `AppShell.kt:192,312` → `SettingsActivity.kt:85` → `AppViewModel.kt:262` → `CustomTermRepository.kt:76`, read downstream by `DictationSessionService.kt:232` — are reviewed and unchanged |

## 5. End-to-end state and lifecycle audit

| Row | Population | Answer |
|---|---|---|
| Every state the picker's visibility can be in | shown, hidden | Boolean, two states, enumerated — no third state possible |
| Every way to reach "From another app"'s action | tap the row | None — no `Modifier.clickable` is applied to that row at all, so it has no `onClick` of any kind; enumerated, zero live paths |
| Every dialog that can be open at once from this screen | `showNewEditor`/`editTarget`, `deleteTarget`, `confirmBulkDelete`, `showImport`, `showImportPicker` | These flags are independent booleans, not a mutually-exclusive state machine (corrected by Codex plan review, round 1 — the state SHAPE does not prevent two being true at once). Exclusivity for the two new picker rows is enforced at the CALL SITE instead: each row's `onClick` sets `showImportPicker = false` in the same handler that sets `showImport = true` (or launches the file picker), so the picker never stays open underneath the dialog it just opened. Every other existing flag is set by a UI action a user cannot trigger two of at once (tapping one row's Edit, one row's Delete, or the bulk-delete button), so this is the only genuinely new coexistence to reason about. |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| Button row goes from 4 to 3 pills | The user, visually | Two import-shaped buttons | One Import button | Yes | UAT screenshot |
| Paste Words / Open a file triggers move into `ImportPickerDialog` | `VocabularyImportDialog`, `importFile` | Triggered from the row directly | Triggered from the picker | Yes (call site only) | UAT: both still complete their existing flow |
| None — call-site move only, no signature change | `onImport` → `AppShell.kt:192,312` → `SettingsActivity.kt:85` → `AppViewModel.kt:262` (`importCustomTerms`) → `CustomTermRepository.kt:76`, read downstream by `DictationSessionService.kt:232` | Unchanged | Unchanged | No | Reviewed end to end (§2.5.1); not exercised differently by this change |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| User taps "From another app" | N/A — no `Modifier.clickable` on that row | N/A | Nothing happens; the row's dimmed look and "Coming soon" label already told them this before the tap | None | N/A, nothing to retry |
| User dismisses the picker without choosing | `onDismissRequest` | Standard `AlertDialog` dismiss | Picker closes, back to the button row | None | Tap Import again |

No new crash, hang, or data-loss mode; this only changes which tap opens which existing dialog.

## 8. Caller-visible signals audit
Not present in this change. No field's presence, absence, value, staleness or identity carries new
meaning; `showImportPicker` is a plain visibility flag, the same shape every sibling flag in this file
already is.

## 9. Fallback source-of-truth audit
Not applicable. No failure branch in §7 has a fallback to select between competing sources — the two live
paths call unchanged existing code, and the disabled path has no code to fall back from.

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt` — the only file touched.
  - Add `var showImportPicker by remember { mutableStateOf(false) }` beside the existing dialog-state
    `remember`s.
  - Replace the button row's "Import file" and "Paste import" `OutlinedButton`s with one Import button
    (`showImportPicker = true`), keeping Add first and Export last.
  - Add `ImportPickerDialog`, a private `@Composable` `AlertDialog` with the three rows described in §3,
    taking `onPasteWords: () -> Unit`, `onOpenFile: () -> Unit` and rendering itself when
    `showImportPicker` is true; wire its two live callbacks to `{ showImportPicker = false; showImport =
    true }` and `{ showImportPicker = false; importFile.launch(...) }` at the call site.
  - Add `AppGlyph`, a small hand-drawn `Canvas` icon (a simple rounded-square grid, matching this file's
    existing glyph style) for the disabled row only.

## 11. Testing

1. **Class of every new test:** none proposed. This is a Compose layout/navigation change with no new
   business logic — the Product Outcome that matters ("Paste Words and Open a file still do what they did
   before") is exactly what Hardware UAT proves, and this file has no existing Compose test rig to extend
   (`#48`, noted in an earlier session-log entry, tracks that gap generally).
2. **What revert would turn a test red:** N/A, no test added.
3. **What is deliberately NOT tested, and why:** the disabled "From another app" row has no automated
   assertion that it stays unreachable; there is nothing to click in a Compose test today (§1 above), so
   this is covered by reading the code (no `Modifier.clickable` on that row) plus UAT confirming it
   visually reads as off.

### 11.1 Hardware UAT spec
- **Subsystem:** limb (Dictionary is not on the heart path).
- **Recipe:** new, manual — not yet in `device-testing.md`, and not warranting a permanent entry since it
  is a one-time visual/interaction check, not a repeatable regression recipe like the audio ones.
  Sequence: open Dictionary, tap Import, confirm three rows with "From another app" visibly dimmed and
  unresponsive to a tap; tap Paste Words, confirm the same paste dialog opens, cancel; tap Import, tap
  Open a file, confirm the system file picker opens, cancel; confirm Export still copies to the clipboard
  as before.
- **Expected observation:** every row does exactly what its label says, or (for "From another app")
  visibly nothing, with no crash and no dialog left open underneath another.
- **Phone state to restore afterwards:** none — no phone setting is touched by this recipe.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `./gradlew :app:testDebugUnitTest` | Harness Contract (existing suite, unrelated to this change) | Nothing else broke | N/A — regression net only |

## 12. Blast radius & rollback

- **Touched:** `DictionaryScreen.kt` only.
- **Deliberately not touched:** every other file in the app. `AppShell.kt`, `SettingsActivity.kt`,
  `AppViewModel.kt`, `CustomTermRepository.kt` and `DictationSessionService.kt` all sit on the existing
  `onImport` chain (§2.5.1) and were reviewed as part of grounding this plan, but none of them needs a code
  change — `AppPreferences.kt` holds unrelated settings only and is not part of this chain at all.
- **Rollback:** `git revert` the merge commit. No state, preference, or data-model change exists to unwind.

## 13. Ship criteria specific to this change
- [ ] The Dictionary screen shows exactly three pills: Add, Import, Export.
- [ ] Tapping Import shows a picker with Paste Words, Open a file, and a visibly greyed "From another app"
      row labelled "Coming soon".
- [ ] Paste Words and Open a file each still do exactly what they did before this change.
- [ ] Tapping "From another app" does nothing.
- [ ] `./gradlew :app:testDebugUnitTest` count unchanged, 0 failures.
- [ ] Codex review clean on the plan and, separately, on the resulting diff.

## 14. Open questions
None. The founder gave the three row labels, their order, and the placeholder treatment directly.

## 15. Related
- `docs/feature-requests/plan-2026-08-31-dictionary-simplify.md` — the immediately prior change to this
  same screen and file, landed the same day.

---

## Checklist for the plan author
- [x] Gate 0 prior context posted before this file was written (this session's own prior Dictionary work)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection (Code; only `DictionaryScreen.kt` touched)
- [x] Self-reviewed to all-clear before Codex sees it
