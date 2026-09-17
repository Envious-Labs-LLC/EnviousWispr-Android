# Issue #168 — Serve the models from our own host, Hugging Face as the fallback — 2026-09-17

GitHub issue: `#168`. Tier: MEDIUM (model delivery is on the heart's model-loading path). Status: APPROVED
(founder "Yes" to `docs/play-listing/model-hosting-proposal.md`, 2026-09-17).

## Preface — Lane + Hardware UAT declaration

**Lane:** Code

`mixed_pr: true`: Code (`app/**`) plus Docs/dev-tooling (this plan; `cited-symbols` conditional).

**PAR rows closed:** none. Delivery mechanics are Android's own.

**Hardware UAT:** Y

A clean install on the emulator (first-run onboarding) downloads both models; the log names
`models.enviouslabs.co` as the host for all five files; both models verify and dictation works. Then, with
the host blocked at the emulator's resolver, a second clean install downloads from `huggingface.co` and
verifies. On the founder's phone the change is invisible: his models are already installed and verified,
and an installed model is never re-downloaded.

## Preface — User Rubric

1. **Who is this user in this moment?** Meera Patel, first launch on a new phone, one hand, kids nearby,
   waiting on the "Downloading your models" screen.
2. **Why would they want this?** "Why is it stuck?" She never wants to think about where the models come
   from; she wants the bar to move and finish.
3. **How would they invoke it?** She does not. It is the first-run download and the update download.
4. **What app are they in?** EnviousWispr onboarding.
5. **What is their natural input?** None; she taps Continue once.
6. **What does success feel like?** The bar finishes in the time a 1.2 GB download takes on her Wi-Fi,
   every time, including the day Hugging Face has an outage or rate-limits us.
7. **What does wrong-not-broken look like?** The download takes twice as long because the first host
   refused and the second served; she notices nothing but the wait.
8. **What would a power user hack around this to get?** Place the files by hand (the founder's dev
   state). No one else can.
9. **What level of control would they want?** None. There is no setting; the host is ours to choose.

### Cross-persona check

Every persona wants the same thing here: the download to finish. Elena (privacy) wants to know the
download carries no user content, which both hosts satisfy and the privacy policy says. No tension.

---

## 0. TL;DR

Consolidation: none. The change adds a second source to the existing single-source download path and
widens the one validator; it wraps no primitive and creates no new manager.

Every install pulls 1.15 GB from Hugging Face repositories we do not control. The host we already run for
macOS and Windows (`enviouslabs-models` R2 bucket behind `models.enviouslabs.co`, edge-cached per prefix)
holds the very S1-mini file Android pins (byte-identical, verified 2026-09-17) and now gets the four
Parakeet v3 int8 files under `parakeet-onnx/<revision>/`. The manifest lists our host first and Hugging
Face second; the validator accepts exactly those two hosts; the download opens the fallback only when
the primary cannot be opened; the byte count and SHA-256 check remain the one admission gate; the worker
logs which host served. MEDIUM because it touches model delivery. Proof: unit tests on the resolver and
the store, plus the emulator clean-install run twice (host reachable, host blocked).

## 1. Problem

`models/ModelManifest.kt` resolves every file to `huggingface.co/<repo>/resolve/<revision>/<file>`. The
repositories (`csukuangfj/…`, `superwhisper/…`) are not ours; Hugging Face's terms promise no bandwidth
and a repository can move or vanish. `validateModelSource` (`models/ModelDelivery.kt:263`) accepts only
`huggingface.co`, so the URL cannot simply be swapped, and `ModelFile` carries one `sourceUrl` with no
alternate. Codex found all three on the proposal (2026-09-17).

## 2. Goals & non-goals

### 2.1 Goals
1. Both models download from `models.enviouslabs.co` on a clean install (log line per file).
2. With our host unreachable, both models download from Hugging Face and verify.
3. No other host is ever contacted: the validator refuses everything but the two, and a redirect never
   leaves its host.
4. Byte counts and hashes are untouched; an already-installed model is not re-downloaded.

### 2.2 Non-goals
- A versioned manifest served from the host (a bump still ships with an app update).
- Cloudflare rule changes: the `/s1/` and `/parakeet-onnx/` cache rules already exist.
- Changing the resume logic: a partial started on one host resumes against whichever host opens first;
  both serve the same bytes and the hash decides.

## 2.5 Grounding brief

### 1. Trace producer → owner → consumer

`ModelManifest.parakeet` / `.s1` (`models/ModelManifest.kt:32-43`) produce `ModelFile.sourceUrl`.
`ModelDescriptor.isAvailable` (`:20-23`) gates every descriptor on `validateModelSource` and the
`/resolve/<revision>/` substring. `ModelDeliveryStore.download` (`models/ModelDelivery.kt:92`) opens
`transport.open(entry.sourceUrl, offset)` at `:135` and again at `:140` after a refused resume, streams
to `<name>.part`, verifies size and hash at `:168`, admits by rename. The worker
(`models/ModelDeliveryWorker.kt:84`) supplies `HttpsRangeTransport` (`:163`), which follows up to four
redirects only through `allowedHost` (`:202`) and throws `IOException("model source returned HTTP …")`
on any non-2xx (`:196`). Found by:
```
/usr/bin/grep -n "sourceUrl\|validateModelSource\|allowedHost\|transport.open" app/src/main/java/com/envi/wispr/models/*.kt
```

### 2. Existing authority

`validateModelSource` is the one source authority; `allowedHost` the one redirect authority; the
store's size-and-hash check the one admission authority. All three are kept and widened, none wrapped.
`new authority proposed`: none.

### 3. Prior attempts and live direction

`model-delivery.md` names "production model hosting" as the open item since the v3 bump (#36). The Mac
built the host (`eg1-operations.md` FACT: r2-hosting-runbook, 2026-07-02) and Windows joined it
(`/parakeet-onnx/` prefix). The proposal's first draft would have broken every download; Codex caught the
validator, the path contract and the missing fallback (`docs/play-listing/model-hosting-proposal.md`).

### 4. Boundaries

| Boundary | Current | Planned |
|---|---|---|
| Host reachable vs not | one host, fail | primary refused at open → fallback at the same offset |
| Bytes flowing then failure | interrupted, resume next run | unchanged: the next run tries the primary again from the partial |
| Redirect across hosts | HF → hf CDN only | our host → our host only; HF unchanged |
| Installed model | never re-downloaded (receipt) | unchanged: the receipt embeds sizes and hashes, not URLs |
| App process vs worker | worker owns the transport | unchanged; the store gains a callback the worker logs |

### 5. Premises

| Premise | Evidence |
|---|---|
| The S1 file on our host is byte-identical | downloaded 2026-09-17, 484,219,808 bytes, sha `3b41ebe2…` matches the manifest |
| The four Parakeet files match the manifest | downloaded from HF at the pinned revision 2026-09-17, all four sizes and hashes match |
| The receipt does not embed URLs | `receiptText(model)` in `models/ModelDelivery.kt` (read: sizes, hashes, revision) |
| Our host serves ranges | `accept-ranges: bytes`, HTTP 206 on a ranged GET, 2026-09-17 |
| The cache rules exist for both prefixes | `infra/cloudflare.md` FACT: model-delivery-edge-caching: `/s1/`, `/parakeet-onnx/` live |
| The Parakeet upload has landed | NOT YET at plan time: blocked on the gcloud business login; the PR is not pushed to the phone until the four public URLs answer 206 |

## 3. Design

- `ModelFile.fallbackUrl: String? = null`; `sources = listOfNotNull(sourceUrl, fallbackUrl)`.
- `ModelDescriptor.isAvailable`: every source validates and contains `/<pinnedRevision>/`.
- `validateModelSource`: HTTPS, no user-info, no fragment, port 443 or none, host exactly
  `models.enviouslabs.co` (path with at least `/<prefix>/<revision>/<file>`) or `huggingface.co` (path
  contains `/resolve/`).
- `openFirstSource(entry, transport, offset)`: tries the sources in order, returns the first that opens
  with the host that served; the last `IOException` propagates when none opens, into the store's existing
  `catch`, which reports `FAILED` "model download interrupted" exactly as one refusing host did before.
- `download(...)` gains `onSource: (file, host) -> Unit`; the worker logs `Model source: <id>/<file>
  from <host>`. Nothing about hosts reaches a screen or a notification.
- `allowedHost`: `models.enviouslabs.co` → itself only.
- Manifest: `hosted("parakeet-onnx", rev, file)` and `hosted("s1", rev, file)` first, `resolve(...)` second.

Rejected: reading the pick from a served manifest (non-goal); retrying the fallback mid-stream (a
mid-stream failure already resumes from the partial on the next run, and a mid-stream host switch would
add a second resume path to reason about).

## 3b. Ownership

This lives in `ModelDeliveryStore` because it already owns the open, the resume decision and the
admission; the alternative was the worker's transport, but a transport that silently swaps hosts would
hide which host served from the store's resume logic.

## 4. Contract deltas

- `ModelFile.fallbackUrl` (nullable): "the same bytes at a second host". Consumers: the store only.
- `ModelDescriptor.isAvailable`: now false if ANY source is invalid.
- `validateModelSource`: now true for our host too.
- `ModelDeliveryStore.download(onSource)`: a new optional callback; existing callers unchanged.

## 5. State and lifecycle audit

| Population | Enumeration |
|---|---|
| Call sites of `transport.open` | two, both in `download` (`:135`, `:140`), both replaced by `openFirstSource` |
| Callers of `download` | the worker (`ModelDeliveryWorker.kt:84`) and the store tests; the callback has a default |
| Readers of `sourceUrl` | `isAvailable`, `download`; enumerated by grep, no other |
| Places a host name is written | `ModelManifest.hosted` / `resolve`, the two validator constants, `allowedHost`: one grep hit each |
| Receipt inputs | `receiptText`: revision, names, sizes, hashes; URLs absent, so installed models stay installed |

## 6. Consumer matrix

| Contract delta | Consumer | Current | Required | Code change? | Verified by |
|---|---|---|---|---|---|
| `fallbackUrl` | `ModelDeliveryStore.download` | one URL | try in order | Yes | `ModelDeliveryStoreTest` fallback cases |
| validator widened | `isAvailable`, redirect rule | HF only | two hosts | Yes | `ModelManifestTest` both ways |
| `onSource` | worker | none | log the host | Yes | log read on the emulator run |
| Manifest URLs | receipts | HF | ours first | Yes | installed model not re-downloaded (emulator: reinstall keeps Ready) |

## 7. Failure modes

| Failure mode | Origin | Caller | What the user sees | Persisted | Retry |
|---|---|---|---|---|---|
| Our host 404/5xx/unreachable at open | R2 or network | store | Nothing; HF serves | partial as before | n/a |
| Both refuse at open | network | store | today's "model download interrupted" | partial | WorkManager backoff, unchanged |
| Our host serves, stream dies mid-way | network | store | today's interrupted | partial | next run resumes, primary first |
| Hash mismatch from either host | corrupt bytes | store | today's REPAIR_NEEDED | quarantined | unchanged |
| Redirect to another host | misconfiguration | transport | "unsafe model redirect" → interrupted | partial | unchanged |

## 8. Signals

| Signal | Meaning |
|---|---|
| `Model source: … from models.enviouslabs.co` | our host served that file |
| `… from huggingface.co` | the fallback served; our host refused at open |
| `fallbackUrl == null` | a test descriptor, or a future single-source file; the loop handles one source |

## 9. Fallback source-of-truth audit

| Branch | Expression | Source | Why authoritative | Acceptance | If none | Consumer |
|---|---|---|---|---|---|---|
| Which host to try next | `entry.sources` order | manifest | the founder chose our host first | opened without throwing | propagate the last error | store |
| Whether bytes are good | size + SHA-256 | manifest constants | the same gate as today | equal | quarantine | store |

## 10. File-by-file

- `models/ModelManifest.kt`: `fallbackUrl`, `sources`, `hosted`, `isAvailable`, the ten URLs.
- `models/ModelDelivery.kt`: constants, validator, `openFirstSource`, `onSource`.
- `models/ModelDeliveryWorker.kt`: `allowedHost`, the log line.
- Tests: `ModelManifestTest` (allow-list both ways, every shipped file two-host, revision in the
  fallback), `ModelDeliveryStoreTest` (fallback serves, primary first, both refuse).
- `docs/play-listing/model-hosting-proposal.md`: status line to SHIPPING once merged.
- Knowledge (local): `model-delivery.md` FACT: what-ships-today gains the host; `play-store-readiness.md`
  blocker 4 closes.

## 11. Testing

1. Product outcome: the three store tests ("when this fails, a first run hangs on Hugging Face" / "our
   host is never used" / "a double refusal crashes instead of failing cleanly") and the manifest tests
   ("a wrong host ships" / "a file quietly loses its fallback"). Drift guard: every shipped file two-host.
2. Reverts: drop the fallback loop → the fallback test fails; accept any host → the look-alike hosts pass
   and the allow-list test fails; drop `hosted` from one file → the every-file test fails.
3. Not tested: the real `HttpsRangeTransport` against the real hosts (the emulator run is the oracle).

### 11.1 Hardware UAT spec

- **Subsystem:** heart path (model loading).
- **Recipe:** emulator, clean install (`pm clear com.envi.wispr`), onboarding to the download; read
  `adb logcat -s ModelDelivery` for five `Model source:` lines naming `models.enviouslabs.co`; both
  models Ready; one dictation. Then block the host (`adb shell` cannot edit hosts on a Play image; use
  the emulator's `-dns-server` pointing at a resolver that returns NXDOMAIN for the host, or simply a
  manifest build with a deliberately wrong prefix on our host for the run) and repeat: five lines naming
  `huggingface.co`, both Ready.
- **Expected observation:** the log lines and the Ready state; the oracle for "did the right host serve"
  is the log the worker writes from the store's callback, which the transport does not write.
- **Restore:** nothing on the phone; the emulator is disposable.

### 11.2 Other obligations

| Test | Class | Proves | Revert that turns it red |
|---|---|---|---|
| `sourceAcceptsExactlyTheTwoHosts` | product outcome | allow-list both ways | accept any host |
| `everyShippedFileHasOurHostFirstAndHuggingFaceAsTheFallback` | drift guard | every file two-host, revision in both | drop `hosted` on one file |
| `aFallbackWithoutThePinnedRevisionMakesTheModelUnavailable` | product outcome | the revision contract holds for the fallback | check only `sourceUrl` |
| `theFallbackHostServesWhenOursCannotBeOpened` | product outcome | fallback at open failure, host logged | drop the loop |
| `ourHostIsTriedFirstAndTheFallbackIsNotTouchedWhenItServes` | product outcome | order and no extra request | swap the order |
| `bothHostsRefusingFailsTheDownloadWithTheLastError` | product outcome | clean FAILED, no crash | rethrow outside the catch |

## 12. Blast radius & rollback

- Touched: `models/` in `app` only. Not touched: capture, ASR, polish, insertion, UI, schema.
- Revert: revert the squash; installed models stay installed (receipts carry no URL).

## 13. Ship criteria

- [ ] Emulator clean install: five `Model source:` lines from our host, both models Ready, one dictation.
- [ ] Emulator with our host blocked: five lines from Hugging Face, both Ready.
- [ ] The four Parakeet objects answer 206 on the public URL before the branch goes to internal testing.

## 14. Open questions

- The upload of the four Parakeet files needs a gcloud login the founder must do once
  (`gcloud --configuration=business auth login --update-adc`); the upload script is ready.

## 15. Related

#168, #10, #36 (the v3 bump), `docs/play-listing/model-hosting-proposal.md`,
`.claude/knowledge/model-delivery.md`, `~/.claude/knowledge/infra/cloudflare.md`.

---

## Checklist for the plan author

- [x] Gate 0 prior context posted (chat, 2026-09-17)
- [x] User Rubric answered against named personas
- [x] §2.5 grounded in real code before §3
- [x] §4-9 answered
- [x] Lane declared and matching detection
- [ ] Self-reviewed to all-clear before any reviewer saw it

## Checklist for the reviewer

- [ ] Is the direction right, before anything else?
- [ ] Is any load-bearing negative claim unevidenced?
- [ ] Does any §5 row describe a topic instead of enumerating a population?
- [ ] Does the design answer a question the rules already answered?
- [ ] Would any test here pass with its behaviour removed?
