# Device support: the launch filter

A founder decision (`.claude/knowledge/play-store-readiness.md` RULE: device-support-is-a-decision-not-a-default).
It changes the listing, the store filters, and the test matrix. This doc holds the options and a
recommendation grounded in the shipping path and web research (council 2026-09-15). It does not decide.

## FACT: the-shipped-path-is-CPU-only-and-not-Snapdragon-locked
The speech model (Parakeet on sherpa-onnx) and the local polish model (S1-mini on llama.cpp) both run on
the CPU with arm64 binaries. The Qualcomm GenieX NPU path is a development override, never shipped
(`.claude/knowledge/polish-engines.md` FACT: the-npu-path-is-a-development-override). So nothing in the
shipped path needs a Snapdragon or a Samsung phone. The manifest already restricts to `arm64-v8a` and
`minSdk 33` (`app/build.gradle.kts`), and Play auto-excludes devices without that ABI.

The real constraint is memory and storage, not the chipset: the two models need roughly 1.2 GB of storage
and about 1 GB of RAM resident while transcribing. On a 4 GB phone with other apps active, that risks the
system killing the app under memory pressure.

## FACT: the-two-options
| | Option A (recommended) | Option B |
|---|---|---|
| Minimum RAM | 6 GB | 4 GB |
| Exclude Android Go / low-RAM | Yes | Yes |
| ABI / minSdk | arm64-v8a, 33 | arm64-v8a, 33 |
| Chipset restriction | None | None |
| Reach | Modern mid-to-high phones | ~30% more devices |
| Risk | Low: protects the first store reviews from out-of-memory crash loops | Higher: budget 4 GB phones can kill the app mid-dictation |

Both add a runtime pre-flight that needs ~1.5 to 2 GB free storage before the first download.

## RULE: the-recommendation
Launch on **Option A (6 GB minimum, Android Go excluded, arm64 only, no chipset restriction)**, then lower
to 4 GB after real 4 GB testing and Play vitals show acceptable crash and ANR rates. The device-catalog
exclusions are revisable without adding a hardware dependency, so starting strict costs nothing but reach
and buys clean early reviews.

Do NOT restrict to Samsung or Snapdragon: the shipped path is CPU-only, so a chipset filter would be
arbitrary and would shrink reach against the 100,000-user north star for no reliability gain.

## FACT: what-this-changes-once-decided
- The Play Console device catalog exclusion rules (RAM floor, Android Go).
- The listing's device-support line and the "works on" claim.
- The closed-beta test matrix: the beta runs on exactly the supported class, not on "Android".
- A runtime storage pre-flight before first download (stage 2 work, not yet built).
