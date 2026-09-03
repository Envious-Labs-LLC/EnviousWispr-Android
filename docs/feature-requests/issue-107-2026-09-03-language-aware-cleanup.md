# Issue #107 — Language-aware deterministic cleanup — 2026-09-03

## Preface — Lane + Hardware UAT declaration

**Lane: `Code`.** Touches `app/**` and `app/build.gradle.kts`.
Obligations: `tests`, `codex-review`, `hardware-uat`.

**Hardware UAT: Y.** Deterministic cleanup is a limb, but it sits on the text-finalization stage of the
heart path and every dictation passes through it (`PolishPipeline.run` calls it ahead of every early
return). Catalog decision 2026-09-01 binds this specifically: a fix to the cleanup layer is not proven
until real recogniser output has been run through it. A hand-typed foreign string is not evidence.

## Preface — User Rubric

**Persona: the founder on his own S26, and the multilingual early adopter he will hand it to at stage 2.**

- **What does the user get?** Dictating in German, Dutch, Portuguese or any of the other 24 non-English
  languages Parakeet v3 decodes, the words come out as spoken instead of being rewritten by English
  number, date and money rules — **whenever the detector is confident enough.** Below the floor the old
  rewriting still happens, so this is an improvement on the identified cases rather than a promise about
  all 24. Dictating in English, `um` is removed again, which it has not been since 2026-09-02.
- **What does the user lose?** On a correct detection or an abstention, nothing: abstaining takes exactly
  today's path. **The residual risk is a CONFIDENT WRONG detection**, which is the one case that can take
  something away — English misread as German loses `$10` formatting, and Portuguese misread as English
  loses an authored `um`. Nothing above the floor was misidentified in the measured sample, and the
  hardware run is what tests it on real speech.
- **Would he notice if it were removed?** Yes, in both directions. Today Dutch "Dit is ten minste
  duidelijk." becomes "Dit is 10 minste duidelijk." on his own phone, proven on hardware 2026-09-02.
- **Does it work from the entry point he uses?** Yes. The side button reaches the session owner, which
  reaches both cleanup terminals; both are wired here.

### Cross-persona check
An English-only user sees one improvement, `um` removed again, and no change on the abstaining path: the
English branch is today's branch plus one filler token. The exception is the same residual risk — English
confidently misread as another language would skip its number, date and money formatting.

## 0. TL;DR

Read the language off the finished transcript with an on-device detector, and give that answer to
deterministic cleanup. When the detector confidently identifies a non-English language, the
English-shaped rewriting families are skipped. When it confidently identifies English, `um` is removed as
well. **When it does not clear the confidence floor the behaviour is today's, rewriting included** — so
this improves the cases the detector identifies and changes nothing where it abstains.

## 1. Problem

`DeterministicCleanup.apply` applies English word lists to text whose language it does not know, and it
runs on every dictation. Since Parakeet v3 shipped (#111) the recogniser produces 25 languages, so the
mismatch is now reachable on ordinary use rather than hypothetical.

Measured on hardware 2026-09-02: Dutch "Dit is ten minste duidelijk." was spoken through Azure Speech
into the real recogniser on the S26, came back correct, and cleanup turned it into "Dit is 10 minste
duidelijk." That is `cardinals` matching the English number `ten`.

The blocker recorded on #107 — that no language signal is available — was withdrawn 2026-09-03. The
language does not have to come from the engine; it can be read from the text.

## 2. Goals & non-goals

### 2.1 Goals
1. Establish the dictation's language from the finished transcript, with a confidence floor and
   abstention below it.
2. Give that answer to deterministic cleanup at BOTH cleanup terminals, not one.
3. Skip the English-shaped rewriting families on a confident non-English answer.
4. Restore `um` to the filler set on a confident English answer.

### 2.2 Non-goals
- Any user-visible language picker or setting. There is none and this change does not add one.
- `TextSafety.looksLikeQuestion`, whose auxiliary and filler lists are English. It only JUDGES model
  output and never deletes; on foreign text both sides return false and no refusal fires. Routed, not
  fixed (`code-design-rules.md` RULE: route-gaps-not-block-on-them).
- Changing the AIDL surface. See §3b.
- Restoring `err`. It is an English VERB ("To err is human"), so an English answer does not make it
  safe. It stays out at every language state.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer, end to end

**Producer.** `AsrService` returns the transcript. Nothing on it carries a language. The vendor result
type does declare `getLang` (external), verified 2026-09-03 by disassembling the PINNED
`app/libs/sherpa-onnx.aar` rather than reading upstream docs, but Parakeet decodes multilingually
without identifying and does not populate it (cross-platform catalog, `automatic-language-detection`).

**Owner.** `DeterministicCleanup` owns every English word list. `PolishPipeline.run` is the single
ordering boundary that invokes it.

**Consumers — and there are TWO, which is the fact a per-component grep misses.**

| Terminal | Call site | Process |
|---|---|---|
| the engine | `PolishService.run` → `PolishPipeline.run` | `:polish` |
| the session owner's own fallback | `DictationSessionService.deterministicFallback` → `PolishFallback.deterministic` | main |

`PolishFallback`'s own KDoc states why both exist: "the words a user gets cannot depend on which side
failed" (#69). Wiring one and not the other reintroduces exactly the divergence that type was created to
close.

### 2. Find the existing authority before proposing one

macOS already owns both halves of this decision and they are ported rather than invented.

- `Sources/EnviousWisprPipeline/DictationLanguageResolver.swift` — reads the language off the finished
  text with `NLLanguageRecognizer`, accepts at or above `minConfidence = 0.90`, abstains otherwise.
- `Sources/EnviousWisprPipeline/FillerRemovalStep.swift` — `languageProtectedTokens` keeps a token in
  the shared set and refuses to strip it in a language where it is a real word.
- `Sources/EnviousWisprPipeline/InverseTextNormalizationStep.swift` — `skipReason(language:)` skips the
  whole English number, date and money engine when the language is explicitly non-English, runs on
  explicit English, and runs on nil for a non-LID backend.

Android's detector authority is ML Kit language identification, `com.google.mlkit:language-id:17.0.6`,
model bundled in the APK, fully offline, returns a confidence per candidate and the sentinel `und` when
nothing clears the threshold (verified against Google's Android guide via context7 2026-09-03).
`grounding-discipline.md` RULE: grep-the-dependency-before-hand-rolling forbids writing our own n-gram
detector when a library ships one.

The platform alternative, `android.view.textclassifier.TextClassifier.detectLanguage`, is rejected: its
implementation is device-supplied and can be the NO_OP classifier, so the feature would silently do
nothing on an unknown fraction of phones, and the package is deprecated from API 35 against our
targetSdk 36.

### 3. Read prior attempts and live direction

#107 carries three prior enumerations. The second closed the producer list; the third (2026-09-03)
withdrew the blocker. The filler half was already fixed bluntly on 2026-09-02 by DELETING `um` and `err`
from the shared set, which is why the macOS protected-token table currently has no effect here: our set
is `uh|erm|ah|hmm|hm|mhm` and holds none of macOS's protected tokens. This change reads that table from
the other side — put `um` back and gate it ON English. That restores `um` removal for confidently
identified English and preserves the token for confidently identified non-English. It is not free:
confident misclassification in either direction is the tradeoff, and deleting the token outright had no
such failure mode.

### 4. Lifecycle, trust and process boundaries a naive design would miss

- **Two processes, two terminals.** Covered above; both are wired.
- **Detection is a limb.** It gets a deadline and converts recoverable failures to `Unknown`, per
  `architecture-rules.md` RULE: isolate-limbs. It catches `Exception`, not `Throwable`, so a VM error
  still propagates — deliberately, because an `OutOfMemoryError` hidden behind an un-language-aware
  transcript is worse than a crash.
- **Threads.** Detection never runs on the main thread. Normal engine work uses the engine's own
  executor; two early exits in `polishRequest` run on a pooled BINDER thread and can hold it for the
  bounded deadline, the worst of them paying a cold model load. That is inside
  `kotlin-patterns.md` RULE: never-block-a-binder-or-ui-thread, which bans the MAIN thread, but it is a
  real cost rather than a free one.
- **ML Kit's model is bundled, not downloaded.** No network, no Play Services dependency, no first-run
  acquisition step. This matters because stage 1 has no accepted model-download path.

### 5. Prove the high-risk premises

| Premise | How it is proven, and when |
|---|---|
| ML Kit language-id is bundled and offline | Google's Android guide names the bundled artifact explicitly against the Play-Services alternative. Confirmed at build time by the APK growing without a download step. |
| It identifies the sampled Parakeet v3 languages | MEASURED on NINE of the 25 — en, nl, de, pt, es, fr, it, pl, sv — table in section 11.0. **The other sixteen are unmeasured**, and nothing here claims them. An unmeasured language scoring below the floor abstains and keeps today's behaviour; one scoring above it and WRONG is the risk named in 11.0. |
| A confidence floor of 0.90 is reachable on ordinary sentences | MEASURED 2026-09-03 on the S26, table in section 11.0. macOS's 0.90 is a number for a DIFFERENT instrument, so it was re-derived here rather than copied; the agreement is a coincidence and is labelled as one in the code. |
| Both cleanup terminals reach cleanup | The call-site list above is read from the code. A source-shape smoke test checks both terminals still construct and pass a detector by today's spelling; it cannot show the detector they pass is a live one, and the behavioural rows cover that only inside the shared fallback. |

## 3. Design

Four pieces, three of them pure Kotlin with no Android types, so the whole policy is JVM-testable.

**`cleanup/CleanupLanguage.kt`** — the sealed answer and the policy.

```
sealed interface CleanupLanguage {
    data object Unknown : CleanupLanguage       // nothing established it; behave as today
    data class Known(val code: String) : CleanupLanguage
}
```

The policy exposes exactly two questions, one per macOS gate:
- `extraFillers(language)` — the tokens added to the shared filler set for this language. English only,
  and it holds `um`.
- `skipsEnglishRewrites(language)` — true only for `Known` whose code is not English.

`Unknown` answers "no extra fillers" and "does not skip", which is today's behaviour exactly. That is the
abstention contract and it is what makes the change safe.

**The seam, declared in the same file** — `LanguageDetector`, with `fun detect(text: String):
DetectedLanguage?`, alongside `DetectedLanguage(code, confidence)`. Null means the detector had no
answer. This is the direct analogue of macOS's injectable `identify:` closure, and it exists for the same
reason: a real recogniser cannot be made to reproducibly land either side of a threshold, so the boundary
is tested through the seam. It lives beside the policy rather than in a file of its own because the two
are one contract and splitting them would put the abstention type and its producer in different places.

**`polish/MlKitLanguageDetector.kt`** — the one Android-typed file. Wraps the ML Kit client, applies a
bounded wait, and maps `und`, an empty result, a timeout and any recoverable `Exception` to null. A VM
error is not mapped and propagates.

**`DeterministicCleanup.apply(raw, options, language)`** — `language` defaults to `CleanupLanguage.Unknown`
so the 5,995 existing parity rows and every English unit test keep asserting what they assert today. The
default is the documented risk in `validation-discipline.md` FACT: silent-empty-traps (a Kotlin default
has no call-site token, so no grep finds a caller that forgot). The control is not a better default — a
default that failed closed would skip English rewriting for every English user. The repair is at the
SIGNATURE instead: `PolishFallback.deterministic` takes the detector, which removes the constant
`CleanupLanguage` argument entirely. **It does not make abstention unreachable** — an abstaining detector
is still a legal argument — so what remains is a source-shape smoke test over the production wiring,
labelled as one rather than as a proof.

Inside `apply`, the gate is applied at exactly one place per family:
- the filler regex is built from the shared six plus `extraFillers(language)`;
- `skipsEnglishRewrites(language)` skips the spoken-emoji map, the two unconditional rewrites (`a
  hundred`, `Catch-22`), `normalizeStructured` and the spoken-punctuation map.
- `formatText` still runs. Whitespace collapse and sentence capitalisation after a full stop are
  script-agnostic, and Java's `uppercase()` is correct for Cyrillic and Greek, which is what the two
  non-Latin-script members of Parakeet v3's 25 need.

## 3b. Ownership justification

**Each caller owns its own detector instance, and the AIDL surface does not change.** There is no
injection seam in production: both services construct `MlKitLanguageDetector` directly and hold it
privately. The seam that exists is the `LanguageDetector` INTERFACE, which is what the shared fallback
takes, and that is where tests substitute a fake.

The alternative was to detect once in the session owner and append a v3 `polishRequest` transaction
carrying the language. It is rejected: `workflow-process.md` RULE: tier-routing classifies ANY change to
the AIDL surface as REFACTOR regardless of diff size, and this is a limb feature that does not need it.
Detecting per process costs a second 1.6 MB bundled model in `:polish`, which is not a heavy model under
`architecture-rules.md` RULE: isolate-limbs, whose subject is the several-hundred-megabyte inference
models.

**The terminals normally alternate, but they are NOT mutually exclusive.** The session owner arms a
watchdog before the binder call; when it fires it claims the ledger, cancels the
engine and runs its own fallback while the engine may already have detected for the same take. So one
dictation CAN load a detector in both processes. That does not change the decision — 1.6 MB twice on a
timed-out take is still not a reason to touch an append-only AIDL surface — but the earlier claim that
this could never happen was false and is what the cost has to be judged against.

The two terminals do not disagree about POLICY, because neither resolves one: they hand a detector to
`PolishFallback`, which applies the confidence floor itself. **They see the same TEXT, and an earlier draft of this paragraph had that wrong in the other
direction.** The session owner restores vocabulary before its own fallback and passes `preparedRaw` —
already restored — across the binder to the engine. Same input is not the same answer, though: each
terminal calls its own detector instance in its own process, so one can return a confident language while
the other times out or hits a recoverable vendor error and abstains.

## 4. Contract deltas

| Symbol | Before | After |
|---|---|---|
| `DeterministicCleanup.apply` | `(String, CleanupOptions)` | `(String, CleanupOptions, CleanupLanguage = Unknown)` |
| `PolishPipeline.run` | `(String, CleanupOptions, model)` | `(String, CleanupOptions, CleanupLanguage = Unknown, model)` |
| `PolishFallback.deterministic` | `(String, CleanupOptions)` | `(String, CleanupOptions, LanguageDetector)` — the DETECTOR, required and with no default. A `CleanupLanguage` parameter let a caller pass a constant abstention and still look wired; review round 2 defeated the drift guard that way. This removes the constant-language argument; an abstaining detector is still expressible. |
| AIDL | — | unchanged |

## 5. State and lifecycle audit

Six classes from `code-design-rules.md` RULE: async-edge-case-enumeration, against the detector.

| Class | Answer |
|---|---|
| Interrupted | The bounded wait expires; `detect` returns null; cleanup runs as today. |
| Deleted | Not applicable; the model is inside the APK. |
| Mutated | Not applicable; the detector holds no mutable state across takes. |
| Concurrent | `close` on a service destroy CAN race a `detect` on a binder thread. Each service constructs its detector eagerly in `onCreate`, so there is no outer laziness — round 5 caught a `Lazy` field reopening that window. **The detector itself now holds NO lock at all:** identification runs inline on the caller's thread and the only shared state is two atomics carrying pointers, so `close` cannot wait for anything. See section 11.2b for the four designs this replaced and why the simplest one was right. |
| Absent (open or closed?) | **Fails OPEN to today's behaviour**, for the shipped detector: it maps recoverable vendor exceptions, a timeout, `und`, an empty list and a post-close call to no answer, which the policy resolves as `Unknown`. VM errors propagate deliberately, and an arbitrary `LanguageDetector` implementation that throws would propagate too — `PolishFallback` does not wrap the call. The shipped implementation is the only one in production. |
| Stale | The app requests detection per take from that take's text. The observed hardware sequence shows only that one previous language answer was not reused for the next different transcript; it cannot prove nothing is cached, since a transcript-keyed cache would pass it. |

## 6. Downstream consumer matrix

| Consumer | Input changes when? | Effect |
|---|---|---|
| `PolishService` local + cloud + off | cleanup output | Foreign text stops being rewritten; English gains `um` removal. |
| `DictationSessionService.deterministicFallback` | same | same |
| Cloud polish prompt | receives cleaned text | Already language-safe; `ProviderPolishPrompt` carries an unconditional never-translate rule. |
| History | stores the finalized text | Stores the corrected text. |
| `TextSafety.refusal` | compares cleaned to model output | Unchanged; both sides move together. |

## 7. Failure-mode × caller table

| Failure | `PolishService` | `DictationSessionService` |
|---|---|---|
| ML Kit throws a recoverable `Exception` on init | `Unknown`, today's cleanup, logged as shape only | same |
| ML Kit exceeds the wait | `Unknown`, today's cleanup | same |
| ML Kit returns `und` or an empty list | `Unknown`, today's cleanup | same |
| Confidence below floor | `Unknown`, today's cleanup | same |
| ML Kit throws a VM error | **propagates**, deliberately | same |

Every recoverable row degrades to today, which is the limb contract. The VM-error row is the declared
exception to it: a dying process must not be hidden behind a transcript that merely looks uncleaned.

## 8. Caller-visible signals audit

One new diagnostic, content-free per `kotlin-patterns.md` RULE: no-content-in-diagnostics: the
detector's top language tag and its confidence. The resolved policy state and the skip decision are NOT
separately logged, and the hardware run reads this line to check the floor. A language tag and a score
are metadata about the take, never the take's content. No transcript, no sample text.

## 9. Fallback source-of-truth audit

`PolishFallback` is the single source of truth for "what text does the user get when polish did not
happen", and it stays that. Its signature gains a REQUIRED detector, which removes the constant-language
argument a caller could previously pass while still looking wired. An abstaining detector remains
expressible, so the production wiring is checked separately by a source-shape smoke test that says so.

## 10. File-by-file changes

**New**
- `app/src/main/java/com/envi/wispr/cleanup/CleanupLanguage.kt` — the sealed answer, the policy, and the
  `LanguageDetector` seam
- `app/src/main/java/com/envi/wispr/polish/MlKitLanguageDetector.kt`
- `app/src/test/java/com/envi/wispr/cleanup/CleanupLanguagePolicyTest.kt`
- `app/src/test/java/com/envi/wispr/cleanup/DeterministicCleanupLanguageTest.kt`
- `app/src/androidTest/java/com/envi/wispr/polish/EnginePolishLanguageTest.kt` — the only test that can
  reach the process-bootstrap defect

**Changed**
- `app/src/main/java/com/envi/wispr/cleanup/DeterministicCleanup.kt`
- `app/src/main/java/com/envi/wispr/cleanup/PolishPipeline.kt`
- `app/src/main/java/com/envi/wispr/polish/PolishFallback.kt`
- `app/src/main/java/com/envi/wispr/polish/PolishService.kt`
- `app/src/main/java/com/envi/wispr/ui/DictationSessionService.kt`
- `app/src/test/java/com/envi/wispr/polish/DeterministicFallbackTest.kt`
- `app/build.gradle.kts`

## 11. Testing

Classes declared per `testing-philosophy.md` RULE: every-test-declares-which-of-four-things-it-protects.

| Suite | Class | Protects |
|---|---|---|
| `CleanupLanguagePolicyTest` | Product Outcome, except `sampledLanguageStatesAllResolveToADeclaredExtraFillerSet`, which is a Harness Contract smoke test | The gate table: which language state removes which tokens and skips which families, and what the confidence floor accepts. |
| `DeterministicCleanupLanguageTest` | Product Outcome | The measured defects from #107 come out correct under a known language, and still reproduce under `Unknown`. Both unconditional rewrite sites, `a hundred` and `Catch-22`, have their own row, because the structured pass is gated separately from them. |
| `DeterministicFallbackTest` | Product Outcome, plus one Drift Guard | Extended rather than duplicated, because it already owned "both terminals agree". Two behavioural rows exercise the shared fallback with an injected detector — confident Dutch unchanged, silent detector still rewritten — The third matches source shape to check each terminal still builds and passes the real detector; it cannot prove detector behaviour and its name and KDoc say so. |
| `DeterministicMacParityTest` | unchanged | 5,995 rows still pass, proving `Unknown` is today's behaviour byte for byte. |
| `EnginePolishLanguageTest` (androidTest) | Product Outcome | The engine in `:polish` keeps Dutch `ten`, still cleans when nothing is established, and names the regression it guards. It is the ONLY test that can reach the ML Kit process-bootstrap defect: a JVM test has no processes, and an instrumentation test calling the detector directly runs in the default process where ML Kit's provider already ran. |
| `FillerCopyTest` | unchanged | The settings subtitle still names only words removed at every language state. |

Adversarial rows required by `code-design-rules.md` RULE: matcher-set-adversarial-tests, since `um` is
being added to a membership set: `um` under English removed; `um` under German kept; `um` under
`Unknown` kept; `err` kept at all three.

### 11.0 Detector measurement — RUN 2026-09-03, S26 Ultra (SM-S948U1), debug build

Sixteen ASR-shaped samples, lowercase and unpunctuated, driven through `MlKitLanguageDetector` on the
phone with `am instrument`. The probe was a throwaway instrument and was deleted in this change; it
asserted nothing, it only printed. **The detector named the correct language on all 16.**

| Language | Sample | Words | Confidence | At the 0.90 floor |
|---|---|---|---|---|
| en | i need ten dollars today | 5 | 0.998 | accepted |
| en | um i think so | 4 | 0.602 | abstains |
| en | on my way | 3 | 0.973 | accepted |
| en | yes | 1 | 0.800 | abstains |
| en | we should ship on friday at three o clock | 9 | 1.000 | accepted |
| en | call me at two zero three nine five four eight eight seven nine | 13 | 1.000 | accepted |
| nl | dit is ten minste duidelijk | 5 | 0.977 | accepted |
| de | wir treffen uns um drei | 5 | 0.961 | accepted |
| de | ich brauche hundert euro fuer das buch | 7 | 0.987 | accepted |
| de | rat war gut | 3 | 0.980 | accepted |
| pt | eu quero um cafe | 4 | 0.999 | accepted |
| es | quiero dos o tres manzanas | 5 | 0.999 | accepted |
| fr | je voudrais un cafe s il vous plait | 8 | 1.000 | accepted |
| it | ho bisogno di dieci euro | 5 | 0.998 | accepted |
| pl | potrzebuje dziesiec euro | 3 | 0.683 | abstains |
| sv | jag behover tio kronor | 4 | 0.972 | accepted |

**Conclusion, and its limits.** Thirteen of sixteen clear 0.90 and all thirteen are right. The floor is
kept at 0.90: no sample argued for lowering it, the two English abstentions cost nothing because
abstention and an English answer differ only in the `um` token, and tuning down on sixteen samples
written by the same person choosing the number is the circular reasoning macOS's own floor comment
warns against. **Named limit: a roughly three-word non-English sentence may not clear the floor and then
keeps today's behaviour.** This is one phone at one moment; it is not a claim about any other device.

**Second named limit, and it is a defect in this sample set rather than in the detector.** Every sample
above is a short ASR-shaped fragment, which is the region where the detector is weakest AND where
abstention already protects the user — so the evidence was shaped so that a wrong answer could not hurt.
The case that would actually cost a user is the opposite one: a long transcript identified CONFIDENTLY
and WRONGLY, which applies the wrong rules to a lot of text at once, and nothing above speaks to it. The
general form, worth carrying past this change: a sample assembled from the cases you already thought
about cannot surface the case you did not. Raised by the macOS session, 2026-09-03. The hardware run in
11.1 adds a 34-second continuous German passage specifically to probe it.

### 11.1b Hardware UAT — RUN 2026-09-03, S26 Ultra (SM-S948U1), debug build

Spoken fixtures synthesized with Azure Speech, played through the REAL recogniser on the phone via
`am instrument`. Every row reports the raw transcript, the detected language and confidence, the text
cleaned under that language, and the same text cleaned as today, so no stage is credited with another
stage's work.

**The measurement probe is deleted; the WIRING test is KEPT.** Review round 5 pointed out that deleting
the only test that could reach the process-bootstrap defect would leave that exact regression permanently
green, so it became a retained device test.

| Fixture | Raw transcript from the recogniser | Detected | Conf | Cleaned under the language | Cleaned as today | Gate changed it |
|---|---|---|---|---|---|---|
| en-short | `Um I think we should ship on Friday, and it will cost ten dollars.` | en | 0.9997 | `I think we should ship on Friday, and it will cost $10.` | `Um I think we should ship on Friday, and it will cost $10.` | **YES** |
| de-short | `Wir treffen uns um 3 Uhr am Bahnhof.` | de | 0.9701 | unchanged | unchanged | no |
| nl-short | `Dit is ten minste duidelijk.` | nl | 0.9740 | `Dit is ten minste duidelijk.` | `Dit is 10 minste duidelijk.` | **YES** |
| de-long, 34 s / ~90 words | `Der Bericht ist heute Morgen eingetro …` (full text in the run log) | de | 0.9990 | unchanged | unchanged | no |

**The `nl-short` row is the case this whole change exists for.** It is the 2026-09-02 regression, driven
through the real recogniser, and the two cleaned columns differ — so the gate is observed doing something
rather than asserted to.

**The `de-long` row probes the long-transcript risk named in 11.0 and did not exhibit it.** ONE 34-second
continuous German passage was identified correctly at 0.9990. A single passage failing to show a risk is
not the same as the risk being absent, and the limit in 11.0 stands as written.

**Why the two German rows show no change, and it is worth knowing rather than reading as a null result.**
Parakeet v3 performs its own inverse text normalization and had already emitted `3`, `100`, `9` and `200`
as digits, so no English number WORD survived for cleanup to rewrite. The gate bites where a foreign word
COLLIDES with an English number or filler word — Dutch `ten` — not on foreign numbers in general.

**On back-to-back**: the four fixtures ran in order, en → de → nl → de, through one detector instance,
and each got its own answer. That shows a previous language answer was not reused for the next different
transcript. It does NOT prove nothing is cached — an implementation caching by transcript would produce
the same four results.

#### The two defects only this run could find

Both were invisible to 441 unit tests and to four Codex rounds, and the second was introduced by the fix
for the first.

1. **ML Kit was never initialized in the `:polish` process.** Its bootstrap is `MlKitInitProvider`, a
   `ContentProvider` with no `android:process`, which Android instantiates in the DEFAULT process only.
   In `:polish` `getClient()` threw `IllegalStateException`, every detection returned null, and the
   feature was dead on the path that actually runs while looking healthy everywhere else. **The unit
   suite has no processes and could not see it; the first device probe could not either, because
   instrumentation runs in the default process where the provider HAD run.** Only driving the real
   `:polish` service over binder exposed it.
2. **`MlKit.initialize` is not idempotent**, so the naive fix broke the MAIN process instead. Verified by
   disassembling the pinned `common-18.11.0.aar`: it reaches
   `Preconditions.checkState(instance == null, "MlKitContext is already initialized")`. Both broken
   states were observed on the phone, one before the guard and one after.

**Wiring proof, which the mechanism rows do not give, and it is now a KEPT test.**
`EnginePolishLanguageTest` binds the real `:polish` service over AIDL with polish OFF, which runs
deterministic cleanup and nothing else. Run 2026-09-03: 3 tests green, and the engine process logged
`language=nl confidence=0.97397816` and `language=en confidence=0.9921858` from its OWN pid, returning
`Dit is ten minste duidelijk.` unchanged.

**Its red control is not hypothetical.** The same assertion was observed FAILING on this phone before the
ML Kit initialization fix, returning `Dit is 10 minste duidelijk.` — so the guard has been seen both ways
(`validation-discipline.md` RULE: a-guard-control-needs-the-guard-REMOVED). A source-shape test cannot
show any of that.

### 11.1 Hardware UAT spec

On the S26, through the real recogniser, not typed:
1. English sentence containing "um" and a spoken number. Expect the filler gone and the number formatted.
2. German sentence containing "um" and a spoken number. Expect both preserved as spoken.
3. Dutch "Dit is ten minste duidelijk." — the 2026-09-02 regression. Expect it unchanged.
4. Back-to-back English then German, to check that a previous language answer is not reused for the next
   different transcript. This cannot prove nothing is cached; a cache keyed by transcript would pass it.
5. **A 34-second continuous German passage.** Expect a confident `de` answer and no English-shaped
   rewrites. This is the scenario the sample set in 11.0 could not speak to, and it is the one that would
   cost a user most.
6. For every scenario record four separate lines — the raw transcript, the detected language, its
   confidence, and the cleaned output — plus the same text cleaned as today, so the two together show
   what the gate actually prevented and no stage is credited with another stage's work.

### 11.2b The detector concurrency design cost four rounds, and the measurement is why

Recorded because the shape is more useful than the fix. Review rounds 5 to 8 each found a defect of ONE
class in this file — **slow vendor work done while holding shared state** — and each of my fixes was
smaller and cleverer than the last while recreating the class:

| Round | Design | How it failed |
|---|---|---|
| 5 | bound only the async task | initialization ran BEFORE the timed wait, so the advertised cap bounded nothing |
| 6 | a lock held across initialization | a stuck init held the lock, so `close` hung and later takes hung with it |
| 7 | a cached thread pool | every extra thread blocked on the SAME lock: one abandoned thread per dictation, teardown still hung |
| 8 | two-phase publication | the loser branch closed a duplicate client WHILE HOLDING the lock |
| 9 | no lock at all, inline | **class CLOSED.** What remained was a separate use-after-close: `close` could release the client between a detection reading the pointer and calling through it. A pointer-level active count fixes it without reintroducing slow work under shared state. |

**The kill criterion was declared before round 8's answer was read**, per
`workflow-process.md` RULE: guard-design-pre-read: a fourth generation of the class means the machinery
is removed, not fixed again. Codex found the fourth and invoked it.

**Then the measurement showed the machinery had never been warranted.** Cold client acquisition on the
S26, three cold `:polish` starts, 2026-09-03: **17 ms, 19 ms, 22 ms**, timed from before
`MlKit.initialize` to a usable client; 0 ms for a later client in an already-initialised process. Four
rounds of concurrency defects had been spent bounding a twenty-millisecond call.

**The first version of this measurement was wrong and is recorded rather than quietly replaced.** It read
14 to 19 ms because its timer started AFTER `MlKit.initialize`, so it measured `getClient()` alone while
being cited as covering initialization — review round 9 caught it. The conclusion did not change, which is
luck: a number used to justify deleting a design has to measure the thing the design was about, and this
one did not until it was re-run.

The final shape has no executor, no `Future`, no cancellation and no lock: identification runs inline on
the caller's thread, and the only shared state is three atomics carrying a closed flag, the client
pointer, and an active-detection count. The unbounded
acquisition is a NAMED limit with a number and a per-cold-start log line, rather than a bound that was
claimed and did not hold.

### 11.2 Other obligations
`codex-review` to an explicit all-clear. `cited-symbols` per `--detect-only`.

## 12. Blast radius & rollback

Every dictation passes through cleanup, so the blast radius is total by reach and small by depth: with no
confident answer the code path is today's. Rollback is reverting the commit; there is no migration, no
stored state and no schema change.

## 13. Ship criteria

- Unit tests green with the count reported from `scripts/measure-tests.sh`.
- The parity corpora still pass at 5,995 rows.
- Codex clean with a confirming re-run.
- The five hardware scenarios pass on the phone with the transcripts and the detector evidence recorded.
  **Both new branches now have evidence:** the abstaining branch from the 5,995-row parity corpus, and
  the confident non-English branch from section 11.1b's Dutch rows and from `EnginePolishLanguageTest`
  running against the real engine in `:polish`. Repeat both on the release candidate rather than
  inheriting tonight's run.
- The confidence floor in the code is the one that was measured, and its comment says what was measured.

## 14. Open questions

- Whether the founder wants the protected-token scope re-taken. macOS's 2026-08-20 decision scoped it to
  four languages "confirmed by native-word meaning, not by corpus measurement". This change does not need
  that table at all, because it works from the English side instead, so the decision is not re-opened here.

## 15. Related

Part of #36. Blocked #107's own prior blocker, withdrawn 2026-09-03. macOS has the same unwired-detector
gap and was told.
