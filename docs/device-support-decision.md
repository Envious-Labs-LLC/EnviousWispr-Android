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

Keep the chosen support scope. Console rules, as saved: **exclude TotalMem <=4096 MB and Android Go**
(October12) **OR System on Chip in a 726-chip list** (October20, founder-approved, #449: every chip whose
cores Play lists are all A53, A55, A73, A75, A35 or older 32-bit designs, or Qualcomm Kryo 2xx/360 or
Krait/Scorpion; chips Play lists without cores stay allowed and the in-app `DeviceTier` block covers them).
Play reported 1,589 more models excluded, 21,685 in total. Checked after saving: Galaxy A23 4G, A05s and
Moto g04s excluded; Galaxy A15 only partially (its 4 GB variants); A35, S26 Ultra and Pixel 8 supported.
List and receipts: `docs/internal/p0-polish-hardware/2026-10-20/` (private). Lifting a tier needs #453's evidence.
The memory rule alone was the October12 state. Console offered discrete less-than-or-equal cutoffs; excluding
<=6144 MB risks excluding advertised6GB phones. This approximates the nominal6GB tier and does not
enforce exactly6GB. There is no6144MB minimum slider. Google rules use reported total memory, not
advertised memory: https://support.google.com/googleplay/android-developer/answer/7353455

Do not replace the core-design rule with a brand or Samsung/Snapdragon-only restriction. A new
support promise needs its own device/runtime evidence.

## FACT: what-this-changes-once-decided

Keep the listing, Console filters and qualification matrix aligned. Internal tester eligibility is
separate from production device exclusions. Test actual model loading and insertion on each added
cohort, not just installation. Approximate1.2GB model storage is a setup requirement; do not describe
a new storage-preflight feature as implemented without source/runtime proof.
