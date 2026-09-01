# Dictionary screen simplification — 2026-08-31

No GitHub issue. Tier: MEDIUM. Status: APPROVED — Codex plan review PROCEED-AS-PLANNED, round 3, 2026-08-31.

> Founder request, in chat, 2026-08-31: remove the one-sentence explainer at the top of the Dictionary
> screen, and remove the "Use custom vocabulary" on/off toggle entirely, to free up space on the page.

**Consolidation:** none. This is a straight end-to-end deletion of one capability's plumbing (the
`vocabularyEnabled` flag, threaded through six files) in a single pass, not a merge of duplicate
implementations or a dedup of repeated logic. The one place a genuine consolidation choice appears —
`ScreenContainer.subtitle` going nullable instead of adding a second `ScreenContainer` variant for
screens with no subtitle — is decided in §3 and is widening a single existing owner, not creating a
second one.

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none. This is a deliberate DIVERGENCE from macOS, not a parity fix — see §2.5.3.

**Hardware UAT:** Y — dictate with a custom term present, once before and once after this change, into a
real editor, and confirm the term still gets restored in the finished text. A saved word with no way to
disable its use is exactly the kind of silent behaviour change a screenshot cannot catch.

## Preface — User Rubric

1. **Who is this user in this moment?** Frank Chen, 72, retired teacher — he taught himself to add three or
   four names EnviousWispr keeps mishearing, and now he is looking at the Dictionary page wondering what
   the switch at the top does.
2. **Why would they want this?** He never asked for a way to turn his own saved words off. If he bothered
   to type a word in, he wants it used, always — the toggle is a question nobody asked.
3. **How would they invoke it?** They do not "invoke" the removal — this is a standing simplification of a
   page they open occasionally to add or fix a word.
4. **What app are they in?** N/A — this is the Dictionary settings page itself, not an in-the-moment
   dictation flow.
5. **What is their natural input?** N/A for this change; the input in question is prior saved words, not
   speech.
6. **What does success feel like?** They open Dictionary, see their words with more room and less to read,
   and never think about the page again. The page answers "what did I save" instead of also asking
   "should this be on."
7. **What does wrong-not-broken look like?** A word they saved months ago quietly stops being applied
   because some earlier version of them (or Codex, or a stray tap before this change shipped) had switched
   it off, and there is now no visible control telling them why, or letting them fix it by looking at the
   page. This is the one real risk in this change, and §3 names the mitigation.
8. **What would a power user hack around this to get?** Priya Ramachandran, if she genuinely wanted one
   term to stop firing mid-dictation without deleting it, would delete the term and re-add it later. That
   is a real workflow loss but a narrow one — nobody in the seven personas describes wanting a blanket
   pause switch as their actual goal; the founder's read (§ below) is that this was solving a problem no
   one has.
9. **What level of control would they want?** Per-term, via delete/re-add, which already exists. Not a
   second, redundant global on/off — that is the founder's own stated reasoning and this plan does not
   relitigate it.

### Cross-persona check
No disagreement found. Every persona either never notices a global vocabulary switch (Meera, Frank, Diana)
or would reach for a narrower per-term control if they wanted one (Priya, Aaron) — none of the seven wants
a page-level pause switch as their actual goal.

---

## 0. TL;DR

Remove the subtitle sentence and the "Use custom vocabulary" toggle from the Dictionary screen. Saved
custom words apply to every dictation from now on, unconditionally — there is no on/off concept left
anywhere in the app. Tier MEDIUM because the toggle's gate lives inside `DictationSessionService`, a
heart-adjacent service, not only in the Compose layer; removing it changes runtime text-restoration
behaviour, not just the screen. Evidence: existing installs where the flag was last set `false` change
behaviour with this update — the fix for that is stated in §3, not deferred.

## 1. Problem

Not a bug. The founder, looking at the Dictionary screen: the explainer sentence is unnecessary ("I think
people know what the dictionary is") and the toggle is a false choice ("People are either putting words in
the dictionary or they're not"). Both cost vertical space on a page that is mostly a list.

## 2. Goals & non-goals

### 2.1 Goals
- The subtitle sentence is gone from the Dictionary screen only; every other screen using
  `ScreenContainer` is visually unchanged.
- The "Use custom vocabulary" toggle, its Card, and its book-icon badge are gone from the screen.
- Saved custom terms are always applied to a dictation — no stored flag gates it anywhere, including inside
  `DictationSessionService`.
- A phone that previously had the flag set to `false` behaves identically to one that had it `true` after
  this ships — no silent "your words stopped applying" for an existing install.

### 2.2 Non-goals
- Not touching per-term delete/edit, search, import/export, or the multi-select flow shipped earlier today.
- Not adding any new control in place of the toggle. The founder's direction is fewer controls, not a
  relocated one.
- Not migrating or reading the old `vocabulary_enabled` DataStore key on startup to decide anything — it is
  simply never read again (see §9).
- Not touching macOS or Windows. This is a named, recorded divergence (§2.5.3), not a claim they should
  follow.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

Full chain, cited by an Explore agent this session and re-verified:

- `app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt:331` — `ScreenContainer(subtitle = "Improve
  recognition with your own words...")`.
- `DictionaryScreen.kt:333-340` — `Card { SettingsToggleRow(title = "Use custom vocabulary", checked =
  enabled, icon = { BookGlyphBadge() }, onCheckedChange = onEnabledChange) }`.
- `DictionaryScreen.kt:181-216` — `BookGlyph()` and `BookGlyphBadge()`, used only at line 213 (inside the
  badge) and line 338 (the toggle's icon) — dead once the toggle is gone.
- `AppShell.kt:308` — `EnviousWisprApp` passes `enabled = uiState.preferences.vocabularyEnabled` into
  `DictionaryScreen`.
- `AppShell.kt:315` — `onEnabledChange = onVocabularyEnabledChanged`.
- `AppShell.kt:193` — `EnviousWisprApp`'s own `onVocabularyEnabledChanged: (Boolean) -> Unit` parameter.
- `SettingsActivity.kt:86` — wired at construction: `onVocabularyEnabledChanged =
  viewModel::setVocabularyEnabled`.
- `AppViewModel.kt:320-324` — `fun setVocabularyEnabled(enabled: Boolean)` calls
  `appPreferences.setVocabularyEnabled(enabled)` inside `viewModelScope.launch`.
- `AppPreferences.kt:108-112` — writes Jetpack Preferences DataStore key `Keys.VOCABULARY_ENABLED`
  (`AppPreferences.kt:149`, `booleanPreferencesKey("vocabulary_enabled")`).
- `AppPreferences.kt:26` — `AppPreferencesState.vocabularyEnabled: Boolean = true` (default).
- `AppPreferences.kt:67` — read mapping: `vocabularyEnabled = preferences[Keys.VOCABULARY_ENABLED] ?:
  true`.
- **The actual gate** — `DictationSessionService.kt:561-565`:
  ```kotlin
  private fun restoreTakeVocabulary(text: String, preferences: SessionPreferences): String {
      if (!preferences.vocabularyEnabled) return text
      val restored = preferences.matcher.restore(text)
      return if (TextSafety.isSafe(text, restored)) restored else text
  }
  ```
  Called from four sites in the polish/publish pipeline: `DictationSessionService.kt:517` (raw text before
  polish), `:532` (polished-result callback), `:555` and `:558` (the regex-fallback path, before and after
  `DeterministicCleanup`/`RegexPolisher`).
- `DictationSessionService.kt:135` — `@Volatile private var vocabularyEnabled = true`, populated at
  `:246-250` from the live `AppPreferences.authoritativeState` flow.
- `DictationSessionService.kt:103` (approx., `SessionPreferences` data class) carries `vocabularyEnabled`,
  snapshotted per-session at `:322-330` so a mid-dictation preference change cannot tear one take.

This is the whole chain, screen to gate. Nothing else touches it — see §2.5.4.

### 2. Find the existing authority before proposing one

No new authority is being introduced; this deletes one. `grep -rn "\.vocabularyEnabled\b" app/src` (rerun
this session, see task notification) returns exactly the three read sites named above and no others.

### 3. Read prior attempts and live direction

`session-log.md`'s two 2026-08-31 entries cover the two prior Dictionary redesigns; neither touched this
toggle beyond restyling it, and macOS still has the equivalent switch — the catalog's `setting` table
carries both:

| setting_slug | platform | user_name | default | effect |
|---|---|---|---|---|
| `b6-word-correction-enabled` | macos | Custom word correction | `true` | False skips the entire correction step. |
| `android-vocabulary-enabled` | android | Use custom vocabulary | `true` | Applies saved terms and aliases to new dictations. |

No catalog `decision` row currently blesses or forbids removing Android's copy of this switch. This plan
therefore records a NEW decision at wind-down: Android drops the on/off switch macOS keeps, on direct
founder instruction, reasoning stated in §1. This is a divergence, not an oversight, and gets written down
so a future session does not "fix" Android back toward macOS parity.

### 4. Name the lifecycle, trust and process boundaries a naive design would miss

- **App process (Compose/ViewModel) vs `:audio`/`:asr`/`:polish` processes:** `DictationSessionService`
  itself runs in the main app process per `code-gotchas.md`, so no cross-process signal is involved —
  this is a same-process field removal, not a new cross-process contract.
- **Live preference vs snapshotted `SessionPreferences`:** unaffected by this change in either direction;
  removing the field removes the snapshot slot too, so the "no mid-dictation tear" property is preserved
  by construction (there is nothing left to tear).
- **Existing installs with the flag persisted `false`:** the one real state transition this plan changes.
  Before: gate reads `false`, vocabulary is skipped. After: no gate exists, vocabulary always applies. This
  is the single behaviour change a returning user could notice, and it is the INTENDED one (§2.1) — the
  founder's direction is that this was never a real choice to begin with.

### 5. Prove the high-risk premises

| Claim | Evidence |
|---|---|
| No other UI surface, and no OTHER TOGGLE, reads or displays this flag | `grep -rn -i "custom vocabulary" app/src/main --include="*.kt"` → besides `DictionaryScreen.kt:335`'s own toggle label, three unrelated hits: `VocabularyCore.kt:78` and `CustomTermRepository.kt:172,179`, all generic "cannot exceed N terms/aliases" limit-error copy that never reads `vocabularyEnabled` and has no toggle of its own (re-run and corrected by Codex plan review, round 1 — the first pass used a case-sensitive pattern and missed these three, but they do not change the design) |
| No existing test exercises this flag by name | `grep -rn "setVocabularyEnabled\|vocabularyEnabled\|VOCABULARY_ENABLED" app/src/test app/src/androidTest` → no output (Explore agent, this session; re-confirmed by Codex plan review) |
| It is a DataStore key, not a Room column | `AppPreferences.kt:16`, `Context.enviousWisprDataStore`; no `app/schemas/*.json` entry exists for it |
| `restoreTakeVocabulary`'s guard has no existing test either way | No test names or exercises `vocabularyEnabled` or `restoreTakeVocabulary` specifically. Some unit tests do inspect `DictationSessionService`'s source for other reasons — `AutoPasteWiringTest.kt:203`, `InsertionOutcomeMessagesTest.kt:286` — so "no test touches this file" is too broad; the narrower, verified claim is the one that matters here (corrected by Codex plan review, round 1) |

No process-lifecycle or who-calls-whom uncertainty here; a problem-only Codex consult was not needed before
this design.

## 3. Design

Two independent removals, done together because the toggle's UI has no purpose once nothing reads its
value:

1. **UI.** Delete the subtitle string from `DictionaryScreen`'s `ScreenContainer` call, and delete the
   toggle `Card`, `BookGlyph`, and `BookGlyphBadge`. `DictionaryScreen` drops its `enabled` and
   `onEnabledChange` parameters entirely — there is nothing left to pass.
2. **Plumbing.** Delete the parameter chain end to end: `EnviousWisprApp`'s `onVocabularyEnabledChanged`
   parameter and its call into `DictionaryScreen`; `SettingsActivity`'s wiring;
   `AppViewModel.setVocabularyEnabled`; `AppPreferences.setVocabularyEnabled`,
   `Keys.VOCABULARY_ENABLED`, and the `vocabularyEnabled` field on `AppPreferencesState` and its mapping
   read; `DictationSessionService`'s `vocabularyEnabled` field, its population from the preferences flow,
   its slot on `SessionPreferences`, and the `if (!preferences.vocabularyEnabled) return text` guard inside
   `restoreTakeVocabulary` — made unconditional, not deleted, since it still does real work.

**`ScreenContainer.subtitle` becomes nullable, not blank.** It renders inside its own `LazyColumn` `item {}`
unconditionally today (`AppShell.kt:668-677`), so passing `""` would still reserve a text row's height and
the 16dp gap around it — exactly the space this change exists to reclaim. Making the parameter
`subtitle: String? = null` and skipping that `item {}` when null is additive: every other of the 9 call
sites (§4/§6) keeps passing a non-null string and is pixel-identical. `DictionaryScreen` is the only caller
passing null.

**No migration for the stale DataStore key.** Preferences DataStore has no schema to violate; an orphaned
`vocabulary_enabled` key on an existing phone just sits unread in the file. `AppPreferencesState` no longer
having that field is what makes "unread" true by construction — nothing new needs to check for or clear
the old key.

Rejected alternative: leave the flag wired but hardcode `true` at the read site. Rejected because it is a
shim — a live-looking preference nothing can ever set to `false` again is worse than no preference, per
`../rules/code-design-rules.md` and this repo's own `GR-MIGRATION-COMPLETE`-equivalent
(`RULE: architecture-definition-of-done` "no convenience logic"). Full removal costs one extra file
(`DictationSessionService.kt`) over the shim and leaves no dead code behind.

## 3b. Ownership justification
No new type or owner is created. `ScreenContainer.subtitle` going nullable is a widening of an existing
shared primitive's contract, not a new one — justified in §3 above.

## 4. Contract deltas

| Type / signature | Before | After | Consumer impact |
|---|---|---|---|
| `ScreenContainer(subtitle: String, ...)` | subtitle always renders | `subtitle: String? = null`; renders only when non-null | All 9 other existing callers unaffected (still pass non-null); `DictionaryScreen` passes nothing |
| `DictionaryScreen(...)` | takes `enabled: Boolean, onEnabledChange: (Boolean) -> Unit` | neither parameter exists | `EnviousWisprApp`'s call site drops both arguments |
| `EnviousWisprApp(...)` | takes `onVocabularyEnabledChanged: (Boolean) -> Unit` | parameter removed | `SettingsActivity`'s call site drops the argument |
| `AppPreferencesState` | has `vocabularyEnabled: Boolean` | field removed | Nothing else in `app/src` reads it (§2.5.5) |
| `AppPreferences` | has `setVocabularyEnabled(Boolean)` and `Keys.VOCABULARY_ENABLED` | both removed | `AppViewModel.setVocabularyEnabled` (its only caller) removed too |
| `DictationSessionService.SessionPreferences` | has `vocabularyEnabled: Boolean` | field removed | `restoreTakeVocabulary` no longer takes the branch; call sites unchanged (still call `restoreTakeVocabulary(text, preferences)`) |

## 5. End-to-end state and lifecycle audit

| Row | Population | Answer |
|---|---|---|
| Every state `vocabularyEnabled` (DataStore) can hold | `true`, `false`, unset (defaults `true`) | All three collapse to "vocabulary always applies" post-change; enumerated, no fourth state exists (`booleanPreferencesKey`, no nullable-tristate). |
| Every reader of `AppPreferencesState.vocabularyEnabled` | `AppShell.kt:308`, `DictationSessionService.kt:247` | Both removed in this change; enumerated, none found beyond these two + the definition site. |
| Every writer of the DataStore key | `AppViewModel.setVocabularyEnabled` (only caller of `AppPreferences.setVocabularyEnabled`) | Removed; enumerated, no second writer found. |
| Sessions in flight when this ships (a running `DictationSessionService` instance mid-update) | N/A — this is a Compose/APK-level code change, not a live migration; a phone reinstalls the whole app | Not applicable; enumerated, none found. |

## 6. Downstream consumer matrix

| Contract delta | Consumer | Current behaviour | Required behaviour | Code change? | Verified by |
|---|---|---|---|---|---|
| `ScreenContainer.subtitle` nullable | The 10 real call sites (Codex plan review, round 1, corrected from an unverified ~8): `SettingsPages.kt:33,79,113,139,171,204,228` (seven settings pages), `TranscriptionScreen.kt:50`, `PolishScreen.kt:77`, `DictionaryScreen.kt:330`. `HistoryScreen.kt` and `OnboardingScreen` do NOT call `ScreenContainer` and are not consumers. | Pass a non-null string, subtitle renders | Unchanged for the 9 non-Dictionary call sites; `DictionaryScreen` alone stops passing one | Yes for `DictionaryScreen`/`AppShell.kt` (the `subtitle` param and its item become nullable); no code change for the other nine | Visual: screenshot the intentionally-changed Dictionary screen and the nine unchanged consumers on the phone during UAT, confirm the nine are pixel-identical |
| `DictionaryScreen` drops `enabled`/`onEnabledChange` | `EnviousWisprApp` at `AppShell.kt:~306-316` | Passes both | Drops both arguments | Yes | Compiles; UAT on Dictionary screen |
| `EnviousWisprApp` drops `onVocabularyEnabledChanged` | `SettingsActivity.kt:86` | Passes `viewModel::setVocabularyEnabled` | Drops the argument | Yes | Compiles |
| `AppViewModel` drops `setVocabularyEnabled` | Only `SettingsActivity.kt:86` (removed above) | N/A once caller removed | N/A | Yes | Compiles |
| `AppPreferencesState` drops `vocabularyEnabled` | `AppShell.kt:308`, `DictationSessionService.kt:247` (both removed) | N/A | N/A | Yes | Compiles |
| `SessionPreferences` drops `vocabularyEnabled` | `restoreTakeVocabulary` (unconditional now), `DictationSessionService.kt:322-330` (snapshot site, drops the field) | Gate on the field | No gate | Yes | No test exercises `vocabularyEnabled` or `restoreTakeVocabulary` today (§2.5.5); covered by Hardware UAT instead |

## 7. Failure-mode × caller table

| Failure mode | Origin | Caller | What the user sees | Persisted state | Retry |
|---|---|---|---|---|---|
| A phone had the flag stored `false` before this update | Prior user action or a stray tap | N/A — passive, on app update | Their saved words start being applied again, silently | Old `vocabulary_enabled` key stays on disk, unread | N/A — not a failure, the intended new behaviour (§2.1) |
| A saved term is now restored where it previously would not have been, in an editor the user is watching live | `restoreTakeVocabulary`, unconditional | The polish/publish pipeline, per §2.5.1's four call sites | Text they did not expect, if they had deliberately turned vocabulary off | Nothing new persisted | User deletes the specific term if unwanted — existing path, unchanged |

No new crash, hang, or data-loss mode exists; this removes a branch, it does not add one.

## 8. Caller-visible signals audit

Not present in this change. No field's presence, absence, value, staleness or identity carries new
meaning; a field is deleted, not repurposed.

## 9. Fallback source-of-truth audit

| Failure branch (§7) | Candidate expression | Source | Why authoritative here | Acceptance predicate | If none qualifies | Consumer (§6) |
|---|---|---|---|---|---|---|
| Phone had `vocabulary_enabled=false` stored | Ignore the stored key entirely | N/A — deliberate | The founder's direction is that the choice itself is gone, not that the old value should be honoured once more | Always accepted; there is no predicate to fail | N/A | `DictationSessionService` (no longer reads the key at all) |

## 10. File-by-file changes

- `app/src/main/java/com/envi/wispr/ui/DictionaryScreen.kt` — remove the `ScreenContainer(subtitle = ...)`
  string (pass none), remove the toggle `Card`/`SettingsToggleRow` block, remove `BookGlyph` and
  `BookGlyphBadge`. `SettingsToggleRow` itself is a same-package symbol (defined in `AppShell.kt`, no
  import statement involved) so there is nothing to clean up there.
- `app/src/main/java/com/envi/wispr/ui/AppShell.kt` — make `ScreenContainer.subtitle` nullable and skip its
  `item {}` when null; remove the `enabled`/`onEnabledChange` arguments at the `DictionaryScreen(...)` call
  site; remove `EnviousWisprApp`'s `onVocabularyEnabledChanged` parameter and its use.
- `app/src/main/java/com/envi/wispr/ui/SettingsActivity.kt` — remove the `onVocabularyEnabledChanged =
  viewModel::setVocabularyEnabled` argument.
- `app/src/main/java/com/envi/wispr/ui/AppViewModel.kt` — remove `setVocabularyEnabled`.
- `app/src/main/java/com/envi/wispr/settings/AppPreferences.kt` — remove `vocabularyEnabled` from
  `AppPreferencesState`, its mapping read, `Keys.VOCABULARY_ENABLED`, and `setVocabularyEnabled`.
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt` — remove the `vocabularyEnabled` field
  (both the `@Volatile` cache and the `SessionPreferences` slot), its population from the preferences
  flow, and make `restoreTakeVocabulary` unconditional (delete the early-return guard; keep the restore
  call and the `TextSafety.isSafe` check).

Every path and symbol above is cited with `file:line` in §2.5.1.

## 11. Testing

1. **Class of every new test:** none proposed. This removes a branch; there is no new behaviour to name a
   test after. The Product Outcome that matters — "a saved term still gets restored" — is already exactly
   what Hardware UAT proves. Some existing unit tests do inspect `DictationSessionService`'s source for
   unrelated reasons (`AutoPasteWiringTest.kt:203`, `InsertionOutcomeMessagesTest.kt:286`), but none
   exercises `vocabularyEnabled` or `restoreTakeVocabulary` (§2.5.5).
2. **What revert would turn a test red:** N/A, no test is added.
3. **What is deliberately NOT tested, and why:** the removed `AppPreferencesState.vocabularyEnabled` field
   had no test naming it before this change either (§2.5.5) — nothing regresses in coverage because
   nothing covered it.

### 11.1 Hardware UAT spec
- **Subsystem:** limb (vocabulary restoration, per `architecture-rules.md` FACT: heart-and-limbs), reached
  through the heart's text-finalization step.
- **Recipe:** new — not yet in `device-testing.md`. Add one there at wind-down: save a distinctive custom
  term (e.g. one already on the phone from this session's earlier UAT), dictate a sentence containing its
  known misspelling into a real third-party editor, and confirm the corrected term lands in the inserted
  text.
- **Expected observation:** the saved term's PREFERRED spelling appears in the editor, not the misheard
  form. Oracle: read the actual inserted text, not a toast or a log line.
- **Phone state to restore afterwards:** none — no phone setting is changed by this recipe.

### 11.2 Other obligations
| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `./gradlew :app:testDebugUnitTest` | Harness Contract (existing suite, unrelated to this change) | Nothing else broke | N/A — regression net only |

## 12. Blast radius & rollback

- **Touched:** `DictionaryScreen.kt`, `AppShell.kt`, `SettingsActivity.kt`, `AppViewModel.kt`,
  `AppPreferences.kt`, `DictationSessionService.kt` — all in `app/src/main/java/com/envi/wispr/`.
- **Deliberately not touched:** every other screen file, every other `AppPreferencesState` field, the
  vocabulary matching/storage logic itself (`vocabulary/` package), and macOS/Windows (recorded as a
  divergence, §2.5.3).
- **Rollback:** `git revert` the merge commit. No data migration was performed, so revert is exact — a
  reverted build simply starts reading the stale `vocabulary_enabled` key again, which still holds whatever
  value it had before this shipped.

## 13. Ship criteria specific to this change
- [ ] The Dictionary screen has no explainer sentence at the top and no "Use custom vocabulary" toggle.
- [ ] Every other screen using `ScreenContainer` looks pixel-identical on the phone.
- [ ] A custom term saved on the phone is restored in a real dictation into a third-party editor, with no
      way to turn that off anywhere in the app.
- [ ] `./gradlew :app:testDebugUnitTest` count unchanged or improved, zero failures.
- [ ] Codex review clean on the plan (this document) and, separately, on the resulting diff.

## 14. Open questions
None. The founder's direction and reasoning were both given directly; nothing here is ambiguous enough to
need a second opinion beyond Codex's grounded plan and code review.

## 15. Related
- Today's two Dictionary redesigns: PR #58, PR #59, both referenced in `session-log.md`'s 2026-08-31 entry.
- Catalog rows to update at wind-down: `feature_platform` for `custom-words` (android), the
  `android-vocabulary-enabled` row in `setting`, and a new `decision` row recording the divergence from
  macOS's `wordCorrectionEnabled`.

---

## Checklist for the plan author
- [x] Gate 0 prior context posted before this file was written (this session's own prior Dictionary work,
      re-read via `session-log.md` and a fresh Explore pass)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3 was written
- [x] §4-9 answered, none struck through
- [x] Lane declared and matching detection (Code; only `app/src/main/java/...` touched)
- [x] Self-reviewed to all-clear before Codex sees it
