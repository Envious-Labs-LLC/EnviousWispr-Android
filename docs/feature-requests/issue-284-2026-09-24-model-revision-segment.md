# Issue #284: the pinned model revision is one whole path segment in its place (2026-09-24)

GitHub issue: `#284`. Tier: SMALL (one validator and its one caller). Status: built as the issue proposes; Codex reviewed plan and diff together (round 1: three parser rows and one sentence, adopted).

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** Y, light. Emulator: the app starts with both shipped models ready (the manifest still admits its own sources).

## Preface — User Rubric

User Rubric: persona, the founder dictating on his S26. Nothing he sees changes. A model address the app would accept is now exactly the one the manifest pins: the pinned version in its place, the file's own name last, on one of the two hosts. A mistyped or tampered address makes the model unavailable instead of being downloaded and then caught only by the byte and hash checks.

---

## 0. TL;DR

REF-08 of `docs/audits/2026-09-23c-senior-audit.json` (`architecture-rules.md` RULE: models-are-pinned-verified-and-app-private). `ModelDescriptor.isAvailable` accepted a source when `validateModelSource(url)` passed (host and a loose path check) and `url.contains("/$pinnedRevision/")`, a substring test anywhere in the address. `validateModelSource(url, revision, fileName)` now parses the address once and requires the host's exact path shape: `/<prefix>/<revision>/<file>` with no query on `models.enviouslabs.co`, `/<owner>/<repo>/resolve/<revision>/<file>` with no query or `download=true` on `huggingface.co`. The revision must be a full 40-character lowercase commit hash, every segment a plain name (no escape, no dot segment), and the last segment the file's own name. The byte count and SHA-256 checks are unchanged.

## 1. Grounding (main f8db721)

- `ModelManifest.kt` L29 to L33: `isAvailable`, the only caller of `validateModelSource`.
- `ModelDelivery.kt` L299 to L307: the old validator.
- The two shipped descriptors use full 40-character revisions and the two path shapes above; redirects use a separate host policy in `ModelDeliveryWorker` that permits Hugging Face CDN hosts and does not recheck the revision path; byte count and SHA-256 still verify the result (review round 1).
- `ModelDeliveryStoreTest` and `ModelAdoptionTest` built their test descriptors with revisions `r1` and `r2`, which the new rule refuses; they now use 40-character hashes.

## 2. Tests and receipts

- `ModelManifestTest`: the two-host and https rows now pass the revision and file; new rows `theRevisionIsOneWholeSegmentInItsPlace` (a revision inside a longer segment, in the wrong place, an extra segment, the Hugging Face repo place, an escaped separator, a dot segment, a query on our host, another query on Hugging Face, a fragment, an escaped ordinary character, a trailing slash, a doubled slash) and `theRevisionIsAFullCommitHashAndTheLastSegmentIsTheFile` (blank, branch, short, uppercase, another file), and `aRevisionOutOfPlaceOrOfTheWrongShapeMakesTheModelUnavailable` on the descriptor. The shipped files are checked through the validator itself.
- Mutations (`284-mut.py`): m1 the revision as a substring, m2 any revision shape, m3 no file check, m4 any query on Hugging Face, m5 the manifest back to substring containment: all RED.
- Suite 1352, 0 failures; app and androidTest build; visibility and cited-symbol checks clean.
- Emulator: this build installed over the admitted models; Settings, Storage lists Parakeet (670.5 MB) and S1-mini (484.2 MB) with no download or repair offer.

## 3. Blast radius

A shipped address in a shape the rule does not know would make that model unavailable; the production row pins both descriptors available. Rollback: revert the squash commit.
