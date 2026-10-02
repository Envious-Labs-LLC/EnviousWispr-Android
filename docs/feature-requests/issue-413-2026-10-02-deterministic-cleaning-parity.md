# Issue #413: Current macOS deterministic cleaning parity, 2026-10-02

GitHub issue: [#413](https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/413). Tier: REFACTOR because the final chunk extends the AIDL request surface. Status: APPROVED. Gate 2 approved by Saurabh in this chat on 2026-10-02.

## Preface: Lane and hardware declaration

**Lane:** Code

mixed_pr: true

Additional lane: Docs/dev-tooling for this plan and reference provenance. Obligations are collected through the project Phase 3 scripts.

**PAR rows closed:** none at planning. Targets: PAR-041, PAR-042, PAR-043, PAR-044; preservation evidence contributes to PAR-045 and PAR-040, but does not close all of either broad row. PAR-046's classifier contract is outside this change. English spelling is a catalog extension beyond the older parity rows.

**Hardware UAT:** Y. On the S26, a spoken version, address, command, measurement and emoji reach a real editor correctly, with AI off and local AI on. A failed polish keeps the cleaned text. Original speech stays available in History. Every completed chunk leaves dictation usable.

## Preface — User Rubric

1. **Who:** Priya Ramachandran, replying in Slack on her phone about a release and a test endpoint. She has the thought ready and wants the reply sent without repairing identifiers.
2. **Why:** “I want it to type the version and link I said, without fixing every symbol.”
3. **Invoke:** She uses the existing floating recorder while the editor is focused. Cleaning runs automatically at the end, under the existing controls.
4. **Apps:** Slack, GitHub and Notion from Priya's persona. Gmail and a Chrome input are accessible validation surfaces, not replacements for these product destinations.
5. **Natural inputs:** “We are on version two point five point zero.”; “The part number is S dash one.”; “Open localhost colon three thousand.”; “Run slash clear and try again.”; “Thanks for the fix, thumbs up emoji.”
6. **Success:** Finished text is ready to send and she does not reopen the keyboard to fix separators.
7. **Wrong but working:** It turns a verb, title, ordinary number or foreign word into an identifier, or loses a unit while producing a fluent sentence.
8. **Workaround:** Spell symbols individually, paste a known link, disable cleaning, or type the technical portion herself.
9. **Control:** Existing filler, emoji and punctuation switches stay. Contextual slash is an explicit spoken command in both punctuation positions, following the reference decision. American spelling stays default; British spelling is an explicit choice. No new automatic inference of tone, intent or region.

Cross-persona check: Priya and Aaron Wu value identifiers and protected names; Marcus Weber values literal prose and no inferred formatting; Diana Foster values a finished message without switching apps; Dr. Elena Vasquez values local formatting and intact units; Meera Patel values predictable emoji and minimal setup; Frank Chen values simple existing controls. Resolve the tension with explicit triggers, whole-form parsing, conservative refusals and the reference's literal controls. Do not add a classifier to guess spoken punctuation.

## 0. TL;DR

Android already cleans numbers and custom vocabulary, but its frozen reference corpora and rules predate newer macOS capability. Port the complete current normalization behavior and refusals, expand explicit emoji conversion, preserve locally polished emoji, and add optional British spelling to final text. Keep Android's safer language policy. Use one shared pure cleaning authority for both service and owner fallback, retaining original speech and exactly-once publication. Prove current parity with reference-derived exact outputs, negative controls, real Binder calls and real dictation into editors. No new model, network service, cloud spend or speech-engine change.

## 1. Problem

Pinned inspection: Android b0ceb0d1767a86b1c3839bd54dae04862fb1fe46; macOS 214e550071e782f8407371599f4467f5f001e566, clean checkout, read 2026-10-02. This is current main source parity, not a claim every reference feature is in a publicly released tag.

Local pure-source probe compiled the unchanged Android cleaner and current Swift normalizer. English rows used known English/default Android switches; foreign rows used their known language and the Swift neutral route. These are string-transform observations, not microphone, detector, model or insertion UAT:

| Input | Android | Current reference |
|---|---|---|
| Version two point five point zero. | Version 2.5 point zero. | Version 2.5.0. |
| The part number is s dash one. | unchanged | The part number is S-1. |
| Open localhost colon three thousand. | Open localhost colon 3,000. | Open localhost:3000. |
| Email me at john dot smith at gmail dot com today | Email me at john dot smith@gmail.com today | Email me at john.smith@gmail.com today |
| Run slash clear. | unchanged | Run /clear. |
| the gap is 5 mm and the battery is 5 Ah | the gap is 5 mm and the battery is 5 | unit retained |
| maría punto lópez arroba gmail punto com | unchanged | maría.lópez@gmail.com |
| GPT streepje vier | unchanged | GPT-4 |
| https dos puntos barra barra ejemplo punto es barra ayuda | unchanged | https://ejemplo.es/ayuda |

Controls retained Dutch “Dit is ten minste duidelijk.”, German “Wir treffen uns um drei.” and English “To err is human.” in both inspected transforms. The Swift probe above does not run its filler step; its measurement-unit retention is independently grounded in FillerRemovalStep.swift:51-59 and its measurement tests.

Fixture comparison, dated 2026-10-02: Android's curated corpus has 2,114 rows versus current macOS's 2,239; macOS adds 123 distinct input strings. Shared inputs differ in 32 curated and 43 holdout expected values. Both holdout files have 3,881 rows. These are fixture differences, not 75 newly executed failing Android tests. DeterministicMacParityTest.kt:12-41 only checks stored expectations with punctuation on, fillers and emoji off. It cannot establish default-path or current full parity.

## 2. Goals and non-goals

### 2.1 Goals

Every row of the finite scope inventory below gets a current-reference outcome or a declared platform difference, tested on Android. Both no-model terminals use the same rules and frozen options. Accepted local polish preserves intended emoji; British choice survives local/cloud polish and failure. Ambiguous structured forms remain literal according to reference refusals. Every family has positive and meaning-changing negative controls. No private audio/text is published in fixtures or diagnostics.

### 2.2 Non-goals

Speech recognition repair (#109, #412), a new/pinned-language speech engine (#36), AI rewriting quality/classifier parity, model-backed learned-word checks, model residency, snippets, vocabulary packs/import UI, cursor repair and full cross-platform UI parity. Existing six-pass vocabulary correction is retained and exercised at the integration seam, rather than redesigned. Snippets are user-authored expansion, a separate product capability with its own activation/storage surface, not a missing cleanup rule. Live partial-preview spelling and a speech-language picker stay in #36; this plan's spelling scope is final dictated text, including fallback.

## 2.5 Grounding brief

### 1. Producer to owner to consumer

Commands used: rg -n 'DeterministicCleanup|PolishPipeline|CleanupOptions|fallback|restore' against app/src/main/java/com/envi/wispr/cleanup, polish and ui; read the returned call sites; inspect app/src/main/aidl/com/envi/wispr/polish/IPolishService.aidl; inspect the publication construction separately.

* Settings and vocabulary flows: SessionPreferencesSource.kt:135-174,232-265. The collector merges built-ins with user terms before freezing. This loses provenance unless the user-only protection set is retained separately.
* ASR answer into TakePolishController.prepare at TakePolishController.kt:211-272. User vocabulary is restored before the request. The owner supplies the frozen three switches and policy over polishRequestForTake; request ID arbitrates, take ID is context.
* Service request entry points and workers: PolishService.kt:116-211,241-339. Off, unconfigured cloud, local S1 and configured cloud all enter PolishPipeline.run through run at :399-450; legacy polish is deterministic-only. Missing policy, poisoned runtime, duplicate admission, exception and cancellation have the fallback lane.
* Pure cleanup and model acceptance: DeterministicCleanup.kt:114-229 and PolishPipeline.kt:29-61. Cleanup precedes every bypass and optional model call. An accepted candidate currently returns without emoji or spelling repair.
* Service fallback: PolishFallback.kt:28-48, PolishService.fallbackText at PolishService.kt:383. Shared no-model pipeline with process-local detection; recoverable preparation errors return the handed words.
* Owner fallback, separate default process: TakePolishController.kt:411-462. Detection and cleanup run off main; vocabulary restoration surrounds cleanup. Recoverable exceptions retain the last successful input at each guard.
* Returned model/fallback answer: TakePolishController.kt:333-374 restores vocabulary, then emits prepared text once. The request ledger and cancellation govern delivery, not a cleanup component.
* Publication: DictationSessionCoordinator.kt:933-986 constructs final and original text separately and reserves one publication. SessionFinalizer is the existing delivery/History consumer. No new text writer after publication.

### 2. Existing authority and complete scope inventory

Android authority: `DeterministicCleanup`, `PolishPipeline` and `PolishFallback`; StructuredTermRestorer supplies vocabulary correction. Search by cleanup, normalize, filler, emoji, spelling, alias, compound and restore. StructuredTermRestorer.kt:5-10,94-107 already supplies compound, exact/fuzzy multiword, exact/fuzzy single and canonical fallback, with emoji trigger reservation. No second normalizer or session manager is needed.

Reference roots below: PP = Sources/EnviousWisprPostProcessing; PL = Sources/EnviousWisprPipeline, in the pinned macOS tree. Inventory is read from normalize's assignments and neutral-route call sequence, then from the registered surrounding steps in PL/KernelDictationDriver.swift:125-130, not inferred from examples. That registration also includes snippet expansion and a model-backed learned-word checker; both are explicitly outside deterministic-cleaning scope.

| Family population | Reference producer | Android disposition and required evidence |
|---|---|---|
| Vocabulary compound/exact/fuzzy/canonical and trigger reservation | PP/WordCorrector.swift; existing Android StructuredTermRestorer.kt | Retain; test technical names, alias collisions and emoji triggers with all new passes |
| Filler tokens, language collisions, numeric mm/Ah and explicit acronyms | PL/FillerRemovalStep.swift:51-59,81-108 | Preserve #107 policy, add source-backed elongations only for confident English, numeric unit protection; err remains literal at all language states; explicit all-caps acronyms and saved user word tokens are preserved. The acronym protection is an intentional safer Android difference from the reference, preventing ER/HMM content deletion |
| Canonical emoji, synonyms, phonetic ambiguity, literal discussion, spacing | PP/EmojiFormatter.swift:90-164,215-260,295-360 | Replace small map with bundled source table plus full trigger and refusal behavior; emoji off stays literal |
| Protected idioms, approximations, title/compound case | PP/InverseTextNormalizer.swift:258-327 | Port all protections, not only Android's existing four phrases |
| Email and URL host/path/protocol, TLD and discussion refusals | PP/InverseTextNormalizer.swift:825-1118 and URL routines | Current accepted forms and whole-form refusals, including paired foreign address wording and current selected ccTLDs |
| Dotted chains, minor versions, dashed codes/dates, localhost | PP/InverseTextNormalizer+SpokenCodes.swift:94-397 | New complete conversion, partial-chain refusal and calendar validation |
| Neutral Unicode/glued at-word email, schemes, paths, WWW, ports | PP/InverseTextNormalizer+NeutralAddresses.swift; +SpokenCodes.swift:397-410 | New subset on known non-English; no general foreign number grammar |
| Decimals including negative, mixed digits and magnitude | PP/InverseTextNormalizer.swift decimal routines, normalize:360,674-706 | Compare full current positive/negative population and close drift |
| Money and percent, cents, decimal percent, magnitude house style | PP/InverseTextNormalizer.swift:364,420,637-638 and moneyPct | Preserve existing behavior plus current deltas |
| Times, o'clock, idiom protection; spoken calendar dates | PP/InverseTextNormalizer.swift:367-406 | Current English parity, retaining literal refusal cases |
| Alphanumeric list markers and punctuation-bearing marker boundaries | PP/InverseTextNormalizer.swift:2084 onward | New marker behavior and refusal/line-boundary coverage |
| Simple, compound, additive, scale and contextual ordinals | PP/InverseTextNormalizer.swift ordinals routine | Compare current reference rows; duration collisions remain literal |
| Full US street/house/unit/city/state/ZIP addresses | PP/InverseTextNormalizer+StreetAddresses.swift:103-329 | New whole-address grammar, borrowed-number/start guards, ZIP+4, formatting/idempotence; no international postal-address inference |
| Years, phone/digit reads, digit-scale, ranges/slash/dimensions | PP/InverseTextNormalizer.swift:416-523 | Preserve current outcomes and updated continuations/refusals |
| Contextual cardinals, units/dosage/age, titles, trailing conjunction | PP/InverseTextNormalizer.swift:524-612 | Compare complete current behavior, preserving prose |
| Spoken marks/newlines, gated backslash, always-on contextual slash | PP/InverseTextNormalizer.swift:2480-2906 | Port fixed reading table, sentence-state reset, command casing, written-neighbor guards; default punctuation remains off |
| Protected-span restore, sentence casing, quotes/brackets, newline boundaries | PP/InverseTextNormalizer.swift:640-663,1584 onward | Match current source and regressions; do not copy known reference newline loss as a new Android defect |
| British spelling, user words and code/name exclusions, two passes | PP/BritishSpellingConverter.swift; PL/EnglishSpellingStep.swift:66-97 | New final-text capability with explicit Android spelling preference, separate from speech lock |
| Dropped emoji runs, retained variants, anchors and cost guard | PP/EmojiRestorer.swift; PL/EmojiRestoreStep.swift:110-174 | New repair for local S1; measure S1 separately; cloud remains unchanged like the reference's cloud scope |
| Short-input bypass, model safety, last successful text, empty floor | Android PolishPipeline.kt:38-75, TextSafety.refusal, coordinator:971-972 | Preserve; all new pre/post passes respect these existing decisions |

Catalog queried before source: feature_platform for number-date-formatting, spoken-emoji, emoji-preservation, spoken-punctuation, filler-removal, english-uk-spelling, custom-words and snippets; catalog_gap for the same; cleanup_language support grouped by subcategory; matching decision rows. Catalog is discovery evidence, not proof: its older numeric summary and UK release status are incomplete/stale against current source. The filler unit gap is already fixed in current macOS source. The current neutral route is the authority for each supported language/shape; derive paired wording and fixtures from it rather than claiming every subcategory works in every language.

### 3. Prior attempts and binding direction

Read issue #107 with all comments and the 2026-09-03 session account. The earlier “engine supplies no language, so cleanup cannot gate” premise was withdrawn. Text detection is wired on both sides; actual Binder testing found missing ML Kit initialization in the polish process when JVM and default-process instrumentation had passed. Keep that real process test.

Binding decisions: Android unknown language retains its previous English-shaped behavior; confident non-English skips English families; confident English permits um; err is excluded everywhere. macOS contextual slash is always on since #3038, backslash remains opt-in. British spelling is explicit, never inferred from region. Foreign full cardinal/currency/date grammars and regional restyling were declined on the reference; do not invent them here. Frozen Python oracle is stale; current Swift code/tests and separately adjudicated literal expectations govern, never wholesale old-oracle regeneration.

### 4. Lifecycle, process and trust boundaries

Default process owns the frozen take, user word snapshot and owner fallback; :polish owns optional inference, a second detector and service fallback. Pure transforms run in both processes without Room, settings or UI reads. ML Kit instances can disagree or fail; identical policy is not an identical detection guarantee. No new detector, confidence floor or retry machine. New option/protection data rides in the request; no service-owned live preference. Late callbacks lose the existing claim; stale workers cannot publish. Cloud transport and diagnostics privacy remain unchanged.

### 5. High-risk premises

Problem-only consult completed before design, with file:line trace of normal, bypass, service-failure and owner-failure routes. It confirmed absent post-model passes and reference execution gates. Unchecked premises are NOT VERIFIED: model-specific emoji losses, phone performance of ports, current installed APK behavior and final capture/insertion results. A small unchanged-source probe is not evidence for those.

Source population probes: rg -n 't =|func |static let' on the named Swift normalizer and extensions; rg -n 'value =|text =|private fun' on Android cleaner; rg -n 'PolishPipeline|fallbackText|PolishFallback|restoreVocabulary' on the named Android callers. This inventories the producing calls and both terminals. Reference tests to ingest include base/holdout, spoken slash core/traps, spoken codes, neutral addresses and more languages, street addresses, country TLD, dotted email, digit-string decimal, neighbor window, line-start pad, span restore, filler unit/language, emoji formatter/restorer and British converter suites.

## 3. Design

Use one Kotlin behavioral port beneath the existing cleanup facade. Port new structured forms as parsers with bounded spans and complete-or-refuse rendering; reuse existing proven numeric helpers where outputs already match. Keep one table per reference decision and one bundled emoji/spelling resource snapshot. Do not replace the current rules with NeMo or a new model. A shared C++/Swift rewrite would add a platform dependency and a much larger migration without improving the immediate product contract.

Ordered behavior: existing vocabulary restoration → language decision → enabled fillers → enabled explicit emoji → English full normalization OR non-English neutral subset → optional British pre-pass → existing optional model and safety → British post-pass → eligible local emoji repair → existing owner vocabulary restoration → publication. User canonical spellings are protected by the frozen set throughout British conversion. Unknown language keeps the existing English route, but British conversion requires confidently known English. Neutral normalization is not a fallback error; it is a narrower legitimate route. Empty model output is handled before any post-pass and cannot be decorated into a false nonblank success.

**English spelling surface:** add “English spelling” on Transcription with “American” (default) and “British”. This changes spelling only, not the speech engine's language. Helper: “Changes English spelling. Your saved words keep their spelling.” A saved British choice has no effect on known foreign or unknown text; state that in the helper detail. No region inference, preview-engine download, language-lock claim or #36 dependency. This is an explicit platform adaptation: macOS's picker also locks speech; Android has no lock today. Final-text capability is the same, activation is different.

**User spelling protection:** preserve user-only canonical words in the settings/vocabulary snapshot before BuiltinVocabulary.withUserTerms merges them. Freeze the converter's lowercase token set together with matcher/options. Do not protect every built-in American word, which would override the user's British preference. Vocabulary failure uses the last successful snapshot under the existing read contract. Post-model conversion and post-model vocabulary restore must not undo each other's authorized spellings. The actual Android built-in vocabulary contains brand/acronym terms and no British-convertible prose canonical. All user canonical words are protected before and after polish, so current dictionary restoration cannot undo the chosen spelling except to restore the user's explicitly saved spelling, which is authoritative. No additional owner converter, language acquisition, outcome bit or result-protocol change is needed. A future addition of a British-convertible built-in must extend this source-grounded consumer audit. This corrects the earlier anticipated built-in reversion premise after reading the actual producing vocabulary. The applied implementation is tested with the real matcher rather than a fake that invents an unproduced American canonical.

**Transport:** add `CleanupRequestOptions` (proposed), a bounded Parcelable carrying the three switches, spelling enum and frozen user-only spelling protection words. Append one take-aware request method to IPolishService; migrate the sole production caller and service path together. Keep old declared methods because installed test clients call them; they adapt to American/default protection and the same pure pipeline. No change to request identity, cancellation, outcome parcel or policy semantics. No duplicate normalizer implementation. Keep protection data intact rather than truncating it to make a Binder call fit. Measure serialized request size using the real parcel; an oversized/failed call follows the existing call-failure owner fallback, which has the complete local snapshot. Include a transport-failure test with a large legal vocabulary.

**Emoji repair:** only nonblank safety-accepted local-S1 output, never Off, bypass, rejected/blank answers or cloud. Use the reference's deletion-only word alignment, exact dropped-run slices, variant matching, sentence/neighbor anchoring and retained-emoji no-op. It is data driven, independent of the live converter switch. Measure actual S1 keep/drop cases before stating a model result. Keep the reference's alignment-token upper bound, and reduce it if S26 measurement exceeds the reference step's 50 ms budget; do not call a whitespace chunk count the alignment count. Above the bound or on repair failure, retain accepted model text exactly. All glyph indexing uses Android grapheme boundaries; never split surrogate pairs, variation selectors, tone modifiers or ZWJ sequences.

**Failure isolation:** semantic stages commit last-good plain text only after completing sentinel restoration and validation. A normalization stage's temporary sentinels never become a fallback candidate. Numeric/identifier stages remain in one atomic unit where their dependency ordering requires it. New parsers must be bounded before work, not by an uncancellable regex timeout. Existing process watchdogs remain the ultimate service bound. Do not add another job, timer or idle cost.

### Chunk sequence

1. Port full English normalization, neutral subset, literal refusals, contextual slash, filler/unit fixes and matching current-reference corpus. Keep all current controls and original speech. No transport change in this chunk.
2. Bundle full explicit emoji vocabulary and formatter; add bounded local emoji restoration inside PolishPipeline before successful return. PolishPipeline receives an explicit repair capability argument (proposed), default off; only the local adapter enables it. It never infers model identity from a lambda, engine label or text. Keep model safety and bypass semantics. Capture real local-model/editor evidence.
3. Add optional British spelling, user-only protections, settings row and append-only request transport. Both terminals and legacy adapters land together. Final exact-commit UAT and complete parity inventory closeout.

One task worktree/branch/PR. Intermediate commits leave all paths usable; final delivery is the reviewed full branch. Do not advertise full cleaning parity after chunk 1.

## 3b. Ownership

Consolidation: the dominant cause is an outdated shared cleaning authority, not independent editor defects. Replace old family maps/routines at DeterministicCleanup and keep all caller paths beneath PolishPipeline/PolishFallback. No new session manager, fallback machine or editor-side normalizer.

Cleaning lives in the cleanup package because its input/output contract is a pure text transform shared by both processes. Existing PolishPipeline owns pre/model/post ordering. SessionPreferencesSource owns frozen inputs and provenance; TakePolishController only transports them and retains existing request arbitration. The alternative, separate service-side and owner-side cleaners or repairs in SessionFinalizer, would diverge on failures and arrive after the text/metadata decision.

## 4. Contract deltas

* CleanupOptions gains an explicit spelling value and protection data used only on known-English text. Defaults retain American behavior. AppPreferencesState gains persistence with backward-compatible default; no Room schema change.
* CleanupResult keeps its text/changed/recovered meaning. A recovered later stage retains earlier completed plain text; no partial placeholder text. Changed means accepted output differs, not that every enabled family ran.
* PolishPipelineResult retains model-used/recovered/outcome semantics. Successful post-repair does not become a separate model attempt or reason. Declined post-repair retains accepted text and model credit.
* CleanupRequestOptions and the appended AIDL method transport frozen request values. Old methods remain callable and use documented defaults. Production does not reread settings in :polish.
* User-only spelling protection is retained beside, not reconstructed from, the merged vocabulary snapshot. No CustomTerm database field or provenance migration required.

## 5. State and lifecycle audit

| Enumerated population | Counterexample/current fact | Planned disposition |
|---|---|---|
| Service entry methods: polish, polishRequest, polishRequestForTake, new appended method | PolishService.kt:116-211; IPolishService.aidl | All use one request/cleanup implementation; old methods default American |
| Policies: Off, CloudUnconfigured, LocalS1, Cloud | PolishService.kt:414-443 | Pre-clean every route; spelling both accepted model families; emoji repair local only |
| Language: Unknown, Known(en), Known(other), detector exception | CleanupLanguage.kt; PolishFallback.kt:28-48 | Preserve abstention and exception floor; neutral only Known(other); British only Known(en) |
| Model outcomes: CLEANUP_RECOVERED, EMPTY_AFTER_CLEANUP, NO_MODEL, TOO_SHORT, MODEL_DECLINED, MODEL_REJECTED, MODEL_ACCEPTED (all seven PipelineOutcome members) | PolishPipeline.kt:4-18,38-61 | Preserve each outcome's text, usedModel, recovered and refusal contract. Exercise all seven with the new options; include recovered cleanup and empty cleanup separately from model rejection and blank model output. Post-passes run only on nonblank accepted model output. |
| Interruptions: cancellation, audio/ASR/polish death, low memory, Doze, call, editor loss | TakePolishController.kt:178-202,393-431; existing take arbiter | No new publication authority; original/last-good text through current terminal paths |
| Deletion: user word removed or preference store unavailable mid-take | SessionPreferencesSource.kt:232-265 | Current take keeps snapshot; next take sees change or existing read fallback |
| Mutation: switches/spelling/words change during processing | same snapshot; controller:211-272 | Both request and owner fallback keep the original snapshot |
| Concurrency: cancellation against callback/fallback, duplicate request, two fast takes | service:179-211; controller:333-335,393-431 | Claim once; repair cannot publish; per-call sentinel/alignment state |
| Absence: unavailable resource, detector, service, model, target | fallbackText/PolishFallback; existing finalizer | Stage no-op or last successful text; target follows existing copy-only outcome |
| Stale: callback after take destruction/new take | controller:333-335,426-431 | No publication, History rewrite or restoration into another take |

No new async operation is introduced. All interrupt/delete/mutate/concurrent/absent/stale axes above apply to both model and owner-fallback processing.

## 6. Downstream consumer matrix

| Delta | Consumer | Current behavior | Required behavior | Code change | Verification |
|---|---|---|---|---|---|
| Fuller normalization | local/cloud prompt, Off and both fallbacks | Older cleaned text | Same newer floor, under language/controls | cleanup and pipeline | exact-output corpora and service tests |
| Spelling and protection snapshot | :polish service, owner fallback | Three booleans only | Frozen identical requested options, user words protected | settings/source/controller/AIDL/service | snapshot tests and Binder parcel/service roundtrip |
| Post-model repairs | controller vocabulary restoration | Accepted candidate only | Repaired accepted candidate; user canonicals still authoritative | pipeline; controller only as needed for protection transport | local/cloud fake model outputs plus user words |
| Changed final text | publication/History/insertion/clipboard | final text plus original ASR | Same final text delivered/stored; original unchanged | no new publication path | editor and History exact strings |
| Spelling control | Transcription/AppActions/AppViewModel | absent | Explicit choice, next take effect, visible saved value | existing UI/settings seams | persistence, configuration and mid-take checks |
| Diagnostic trace | TakeLog | family text local only | New family observations local only, no user text network fields | reuse local trace | inspect local log and sanitizer regression |
| Legacy API | existing instrumentation clients | old call methods | callable, deterministic defaults | adapter only | old calls plus new Binder path |

## 7. Failure mode by caller

| Failure | Origin and caller | User sees | Persisted state | Retry |
|---|---|---|---|---|
| Ambiguous/invalid identifier or address | pure parser, either terminal | Existing literal text or reference's documented older transform | ordinary final/original text | none |
| Resource missing/invalid | emoji/spelling load, either process | Last-good text; feature skipped | unchanged preferences; normal History | next process load, no loop |
| Transform throws/fails safety | pure stage, either terminal | Last completed plain text | final fallback plus original | none |
| Blank/unsafe/failed model | pipeline/service | Full deterministic text, existing polish notice if applicable | existing reason/model facts | existing policy only |
| Dead :polish or watchdog | controller | Owner-side deterministic text, existing failure notice | existing fallback facts | existing controller only |
| Emoji alignment over bound or repair error | accepted local model path | Accepted model text unchanged | model success remains accurate | none |
| Foreign or unknown British request | language policy | No British respelling | saved preference unchanged | no detector retry |
| Cancellation/stale callback | ledger/take owner | existing cancel behavior, no late text | existing cancelled terminal | none |

No new customer-facing error sentence or telemetry failure identity is introduced. Reuse existing polish notices; declining a pure optional repair does not claim model failure.

## 8. Caller-visible signals

Audit fields: text, changed, recovered; PipelineOutcome, usedModel, refusal; requestId, takeId; policy, reason, engine, latency/statusCode; originalText and finalText; language Known/Unknown; frozen switches/spelling/protection. Text is authoritative only after a successful stage; blank model answer stays a failure signal. A changed text does not imply model usage. A model success repaired deterministically retains its model credit. Unknown is an intentional language decision, not an exception. No new Room fields, network fields, outcome reasons or sentinel-visible signals. Enum members, rather than a handwritten count, define the outcome population.

## 9. Fallback authority

| Failure branch | Candidate/source | Why authoritative | Acceptance | If unavailable | Consumer |
|---|---|---|---|---|---|
| New pre-pass fails | last completed plain-text stage input | earlier successful transforms belong to user | nonblank where input nonblank; no sentinel | original handed text at first stage | pipeline/model or no-model answer |
| Model fails/rejects/bypasses | cleaned text after British pre-pass | common deterministic floor | existing pipeline semantics | original only under existing empty-output floor | service answer/controller |
| Service loss | owner vocabulary → shared pipeline → vocabulary | same frozen inputs without dead inference dependency | existing guarded per-stage acceptance | last successful vocabulary/raw value | publication |
| Post-pass fails or exceeds bound | accepted model text before that pass | model passed existing safety | existing accepted nonblank value | no new retry or empty answer | publication |
| Final empty output | existing rawTranscript floor | lexical ASR is preserved independently | original is nonblank | existing no-speech/cancel path | History/clipboard/editor |

Private placeholders are never a candidate in this table. RuntimeException/StackOverflow recovery remains bounded at the existing facade; do not claim OutOfMemoryError is recoverable.

## 10. File-by-file changes

* app/src/main/java/com/envi/wispr/cleanup/DeterministicCleanup.kt and PolishPipeline.kt: preserve facade, order and outcome contracts; delegate new families to pure files in the same package, removing replaced old routines/maps in the same chunk.
* New pure files (proposed): spoken identifiers, neutral addresses, street addresses, slash reading, emoji formatting/restoration and British conversion; no new manager/service/process/module. Assign each one narrow behavior; do not scatter one rule across callers.
* app/src/main/assets/cleanup resources (proposed): pinned emoji and spelling tables, their generation/source/license metadata. Include applicable VarCon/emoji resource notices; copying the app's GPL license alone does not establish third-party table rights.
* app/src/main/java/com/envi/wispr/settings/AppPreferences.kt, ui/SessionPreferencesSource.kt: spelling storage/default and user-only snapshot, derived protection set.
* ui/TranscriptionScreen.kt, AppActions.kt, AppViewModel.kt and existing navigation bindings: one spelling control using existing callback groups; update cleaning copy to explain international subset and slash behavior accurately.
* polish/CleanupRequestOptions.kt and matching .aidl declaration (proposed), polish/IPolishService.aidl, polish/PolishService.kt, ui/TakePolishController.kt: append method, transport snapshot, legacy adapters, shared fallback arguments. Outcome/cancellation APIs remain unchanged.
* Existing app/src/test/java/com/envi/wispr/cleanup suites and app/src/test/resources/cleanup: extend current parity runner/resources, source manifest and dedicated literal regressions. Existing service/controller/vocabulary/settings tests cover deltas at their established rigs.
* app/src/androidTest service-option and language suites: invoke new and old real Binder paths, resource loading in :polish, saved settings and protected words. No instrumentation-only substitute for actual editor UAT.
* Product catalog SQL and canonical cleanup knowledge: update only behavior proved at completion, with revision/evidence. No hand-edit of catalog.db. No CI/hook policy changes.

## 11. Testing

1. New behavior assertions are Product Outcome: failures paste malformed codes, lose units/emoji or ignore explicit spelling. Reference manifest is a Drift Guard; its counts do not count as product cases. Decoder/Binder scaffolding checks are Harness Contract unless asserting delivered text. Local trace checks are Observability Contract.
2. Revert each new family delegation, neutral branch, slash reader, glyph repair or spelling pre/post call: exact-output cases must fail. Revert new request dispatch or snapshot protection: real service and mid-take protection tests must fail. Perform receipts after a committed checkpoint; do not restore against unrelated HEAD. Remove redundant tests that stay green against their declared revert.
3. Deliberately not tested here: other phone hardware, new recognition models, full foreign word-to-number grammar, cloud live calls requiring spend/keys, snippet/UI parity and macOS-only polish guards. These exclusions do not earn parity credit.

### 11.1 Hardware UAT

Subsystem: cleanup limb on finalization path. Recipes: wispr-eyes emulator injected dictation for UI/editor staging; silent physical-phone audio injection for exact real S26 pipeline/editor checks, plus actual human speech when practical. Synthetic audio is labeled synthetic, never human-origin evidence. Use existing offline-rendered fixtures; no Azure generation or cloud API charge without separate spending approval.

Oracle: editor's own exact formatted substring and preserved prose; History final matches delivered content, original matches captured ASR. Log labels only corroborate. Record APK/build/commit, input audio provenance, language answer, raw recognized text, intermediate cleanup, policy and editor result. A recognizer mishearing is a separate failure, not a cleanup pass or permission to change expected text.

Required cases: version 2.5.0; S-1 and localhost:3000; multilabel email; US address with unit/ZIP; contextual slash and literal slash verb; opt-in backslash/newline and punctuation-off prose; measurement mm/Ah and um hesitation; ordinary Dutch/German controls; Spanish Unicode email and URL; expanded emoji with repeated anchors, accepted local output and missing-model fallback; British prose with custom “Kennedy Center” and code color.js; settings changed mid-take; two fast successful takes preserving ordered insertion. Run Gmail body and a Chrome field; include accessible Slack/GitHub/Notion editor coverage before claiming those destinations were tested.

Cross states: Off, too-short bypass, local success, not-ready/timeout/service loss, configured cloud via local fake transport for automated wiring, unconfigured cloud. Binder tests exercise cloud post-spelling without paid requests. Live local model cannot run on emulator; observe it on S26. Foreign UAT must log the actual detector decision; Known language unit cases do not prove detection. Preserve and restore settings, custom test words, clipboard, target draft, developer recording switch and all device settings. Do not overwrite models, microphone route or daily phone state for this plan.

### 11.2 Other obligations

| Test population | Class | Proves | Revert |
|---|---|---|---|
| Old corpora plus current-source deltas and dedicated new-family positives/refusals | Product Outcome | current string behavior under declared switches/language | remove corresponding ported family |
| Both punctuation positions, foreign/unknown/English routes, emoji switch | Product Outcome | default behavior and semantic controls | remove route/gating |
| Optional model accepts/drops/reorders/duplicates emoji; rejects/blanks/throws | Product Outcome | no text loss, correct repair eligibility | remove repair or blank-first handling |
| British before/after model, failure, names/code, explicit preference | Product Outcome | chosen final spelling and exemptions | remove each pass/protection |
| Owner/service fallback with frozen settings, malformed callbacks and stale callbacks | Product Outcome | Exercise null outcome, mismatched request ID, blank outcome for nonblank speech, unexpected v1 result and v1 error through the owner callback seam. Each winning failure uses the shared deterministic floor with frozen spelling/protection and existing CALL_FAILED semantics; callbacks that lose the claim or arrive after destruction publish nothing. Assert final text, original speech and exactly-once publication, alongside existing service-loss and transport-failure cases. | remove transport/snapshot wiring or owner callback fallback dispatch |
| Real Binder entry points/resource loading | Product Outcome | actual remote process output | omit new method or remote resource initialization |
| Source digests and fixture provenance | Drift Guard | reference drift detectable | change referenced digest |

Freeze current source/resources/test input lists with SHA-256 and reference revision. Keep old corpora immutable as historical baseline; add current expected deltas and current suite-derived cases explicitly. The current parity runner applies reviewed, revisioned expectation overlays to historical rows whose expected behavior intentionally changed, then evaluates the effective current expectations and new-family rows. It must not assert both old and new conflicting outputs for one active input. Key overlays by corpus/row identity plus verified input digest, not input string alone; duplicate inputs may occupy distinct rows. Preserve an audit of old and new expected values, their reference revision and the reason. Unchanged rows keep their baked expectations. Never skip a row or weaken an assertion to obtain parity. Do not rewrite expected values from Android outputs or use the retired Python generator. Export public regression inputs only from nonprivate reference test rows, or paraphrase private founder examples with reviewed expectations. Differential source execution establishes parity, separately hand-written expectations adjudicate intentional Android differences and shared reference defects.

At implementation checkpoint 2026-10-02, local JVM tests exercise the active source. No APK or hardware UAT receipt is claimed yet. The unchanged-source probe compiled only the pure cleaner/normalizer; it did not build an APK. At implementation run the required full unit suite and assembly from this worktree, read XML counts and failures, then reviewed exact-commit device validation.

## 12. Blast radius and rollback

Changes affect final text in every dictation and prompt, both cleanup processes, one settings row and the appended request surface. Audio/capture/ASR/model weights, insertion routes, History schema, credentials and network destinations are not changed. Main risks are false formatting, unit/name deletion, expensive alignment, transport/snapshot drift and resource packaging. Refusals, literal controls, source-wide parity tests and Binder/editor checks address each.

Rollback the feature commits in reverse order on the same task branch before delivery; after a merge create reviewed revert commits using git revert, deliver normally. Do not reset shared main or delete user preferences/data. Rollback request caller and appended method implementation together; retain legacy declared methods, and account for installed test clients before removing any new method. Existing spelling preference can remain inert after rollback.

## 13. Specific ship criteria

* [ ] All inventory rows have current reference evidence or explicit platform difference; no unsupported global “full parity” claim.
* [ ] Technical identifiers, international addresses, units and controls reach the real S26 editor correctly with AI off and local AI on/failing.
* [ ] Explicit emoji survive the measured local path within the measured bound; untouched cloud behavior is declared.
* [ ] British final text survives polish/fallback while user canonicals and code stay intact; default American and foreign controls remain correct.
* [ ] Original ASR remains intact in History and two fast dictations insert once, in order.

## 14. Open questions

No product/spend question blocks planning. Gate 2 approves the explicit spelling surface and the declared cleaning-only scope. Performance, model-specific emoji behavior and new resource licensing/package checks are implementation proof obligations; a failure routes a revised plan instead of silent weakening. Any future preview/language-lock work remains #36.

## 15. Related and reviews

#107 language cleanup; #36 speech language choice; #109/#412 speech recognition, separate scope. macOS #2728 units, #3038 slash, #3210 identifiers, #3211 street addresses, #3226/#3233 international addresses/codes, #3124 British spelling, #761/#1948 emoji restoration.

Gate 0 prior context, Gate 0.5 rubric and Gate 1 scope announced before authoring. User requested planning through Gate 2. Problem-only consult finished and current transform probe recorded; self-review precedes coverage and grounded review. Coverage review checked the plan and all 21 allowed source/test files; two omissions were adopted: explicit seven-outcome enumeration and the five malformed/legacy callback producers. Author clarified adapter repair capability, resource/transport boundaries and reference limitations. Implementation source audit subsequently removed the unneeded final owner pass, because current built-ins cannot produce the hypothesized spelling reversion and saved user canonicals are protected. Grounded round 1 returned PROCEED-AS-PLANNED with no blocking findings, after inspecting correctness, placement, transport/process, cancellation, language/snapshots, spelling, fallback text, oracle integrity, emoji eligibility/graphemes/cost, validation, licensing, privacy and scope. A confirming review reads the final ordering and fixture-overlay clarifications before Gate 2 is presented. Review receipts are retained locally; the presenting session must verify the latest verdict before saying the plan is ready. Gate 2 approved by Saurabh on 2026-10-02; implementation follows the reviewed three-chunk plan.

Implementation checkpoint (2026-10-02): source and fixtures were frozen to the approved reference revision. Historical/current fixture comparison has zero baked-versus-compiled-Swift drift on the historical inputs. Current extension expectations expand substring assertions using the compiled pinned Swift output, not Android output; the original assertion fragment is retained. Current emoji expectations execute the pinned EmojiFormatter over every bundled canonical/synonym, including reference canonical-tier precedence where it shadows a synonym. A source-owned exact-output oracle is separate from application/Binder/editor evidence.

Code review 1 identified neutral-link admission, continuation and host-family completeness defects. Adopted the reachable findings; replaced the mixed neutral matcher with one owner for the four source-enumerated producers (scheme, path, WWW, localhost), all eight rows, all three host alternatives, and shared refusal tables. Removed the old neutral port preprocessing so a refused prefix cannot expose a tail. New product tests cover plain French prose, unsupported suffixes, domains/IPs/localhost, spoken/written schemes, and paths longer than eight segments. Review and hardware proof remain pending.
