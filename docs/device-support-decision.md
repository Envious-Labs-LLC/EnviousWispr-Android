# Device support

Founder decision September 16, 2026: nominal 6 GB+ memory, Android13+, arm64, Android Go excluded,
no chipset restriction; internal testing goes directly to public release. Worldwide availability was
chosen October12. Support promises must remain separate from the tested device sample.

## FACT: the-shipped-path-is-CPU-only-and-not-Snapdragon-locked

The heading is historical. Parakeet uses CPU; S1-mini uses Qualcomm GenieX with qualified GPU/CPU
routes where the processor and OpenCL allow it, and the repository llama.cpp build on the processor everywhere
else (#449; owner `.claude/knowledge/polish-engines.md` FACT: two-runtimes-chosen-by-processor). NPU is not
offered. Founder direction 2026-10-19: block low-end phones and warn on mid-tier phones; tiers, evidence and the
pending Play exclusion are in `docs/feature-requests/issue-449-2026-10-19-portable-local-polish.md` §16. CPU and GPU were checked on the
S26; one short standard-model CPU polish check passed on a real Pixel8. No universal portability or
performance claim follows from that sample. Build configuration sets Android13+ and arm64.

## FACT: the-two-options

The original 6GB-versus-4GB options are historical. The founder selected the nominal6GB tier.
Do not lower the supported tier without real lower-memory qualification and current stability evidence.

## RULE: the-recommendation

Keep the chosen support scope. The actual Console rule saved October12 is **exclude TotalMem <=4096 MB
and Android Go**, with no chipset rule. Console offered discrete less-than-or-equal cutoffs; excluding
<=6144 MB risks excluding advertised6GB phones. This approximates the nominal6GB tier and does not
enforce exactly6GB. There is no6144MB minimum slider. Google rules use reported total memory, not
advertised memory: https://support.google.com/googleplay/android-developer/answer/7353455

Do not replace the chipset decision with an unmeasured Samsung/Snapdragon-only restriction. A new
support promise needs its own device/runtime evidence.

## FACT: what-this-changes-once-decided

Keep the listing, Console filters and qualification matrix aligned. Internal tester eligibility is
separate from production device exclusions. Test actual model loading and insertion on each added
cohort, not just installation. Approximate1.2GB model storage is a setup requirement; do not describe
a new storage-preflight feature as implemented without source/runtime proof.
