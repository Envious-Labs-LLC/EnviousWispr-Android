# #449: Android local-polish compatibility research

Date: 2026-10-18. Status: information gathered; hardware execution and Gate 2 pending.
Issue: https://github.com/Envious-Labs-LLC/EnviousWispr-Android/issues/449
No replacement engine, recovery framework or support restriction is selected. No app code, build, release or device setting changed. Research queries and artifact inspection only; no paid testing.

## Question and first conclusion

Can our offered local AI polish work reliably and quickly enough across our intended Android range, and what requirements beyond RAM determine that? The current package is demonstrably incompatible with some admitted phones. The same S1 model has a portable CPU execution path in principle, but useful lower-end phone performance remains unmeasured. Narrower temporary requirements are an acceptable option to the founder; they must follow evidence rather than a guessed phone list.

## 1. What we now know about the shipped engine

- App source pins GenieX0.4.0 (app/build.gradle.kts:117), Android13/API33+ and arm64 (same file:18,33). Neither Android version nor arm64 guarantees newer optional CPU instructions.
- The exact vendor0.4.0 Android toolchain requests armv8.7a plus FP16, DOTPROD and I8MM globally. This is a materially newer CPU build target than plain arm64. Source: https://github.com/qualcomm/GenieX/blob/v0.4.0/sdk/cmake/arm64-android-llvm.cmake . Build flags establish a conservative target, not a complete measured list of instructions reached on every workload.
- The shipped libgeniex image's buildID matches the A23 SIGILL report; the reported instruction address contains LDAPRB (RCpc). This independently confirms a real beyond-baseline instruction in the actual distributed binary. Exact affected-phone feature readback is not performed.
- SDK initialization precedes app GPU/CPU backend selection (S1GenieXRuntime.kt:54-62). Selecting CPU inside that package cannot avoid its initialization requirements.
- GPU support is independent: the current accelerated path uses vendor/driver-specific facilities. CPU executability does not prove GPU support, and an advertised NPU is not an offered/qualified path here.
- The S1 Q4_K_M model is484219808bytes with the existing pinned hash/revision. It is a model artifact, not processor-specific executable code. Upstream S1 describes a Qwen3-based0.6B model supported by llama.cpp; pinned llama.cpp includes CPU Q4_K/Q6_K implementations. This supports feasibility, not an app/hardware performance receipt.
- A memory minimum is therefore necessary policy information, not a sufficient compatibility promise. Prior #418 initialization failures were already warning evidence independent of RAM. S26 successes and one short Pixel8 CPU success are bounded samples.

## 2. Native candidate evidence, without choosing a solution

| Candidate | Confirmed | Still unproven |
|---|---|---|
| Current GenieX0.4 package | S26 accelerated successes; one short physical Pixel8 CPU case; A23 SIGILL | full lowest CPU requirement and broader useful performance; cannot claim universal support |
| Vendor GenieX0.9.1 CPU-only package | Official7,673,103-byte artifact downloaded and release SHA256979dd51036cb530a73f6bfffac1c3234e0ca235032908f047aa1a7c13c6d9226 verified. Same register-plugin area uses baseline LDARB rather than the observed failing LDAPRB. QAIRT plugin is absent from the AAR; packaged Kotlin bytecode still enumerates LLAMA_CPP and QAIRT, matching source. | Successful app initialization; possible missing-QAIRT error remains a concrete integration obstacle. Static inspection is not generation acceptance or proof that no advanced instruction exists elsewhere. |
| Existing pinned llama.cpp CPU source | Generic ARM baseline build paths and S1 quantization support exist; a legacy JNI wrapper is already in this repo | actual packaged runtime, complete prompt/sampler/Unicode/token-ending behavior, Android page-size support, latency/memory and S26 regression |

The vendor CPU package is a different compiled artifact, not the existing CPU dropdown. No new model or recovery design follows from this assessment. Preserve local privacy and user choices while assessing alternatives.

## 3. Regional hardware is not a country allowlist

The chip itself does not change when a phone travels; the regional mix of products and regional SKUs changes. Brand, marketing name, purchase year, GHz and RAM alone cannot predict compatibility. Separate physical RAM from storage-backed "RAM Plus".

| Evidence-backed example | Hardware difference | Implication |
|---|---|---|
| A23 4G, catalog device a23 / affected SM-A235F | QTI SM6225 / Snapdragon680, Adreno610 | actual failing chip family |
| A23 5G/UW, a23xq | QTI SM6375 / Snapdragon695, Adreno619 | a lab named A23 5G is not the same CPU |
| Japanese A23 5G, SC-56C / SCG18 | MediaTek Dimensity700;4GB per carrier | same A23 naming family, different vendor; this retail variant is below our nominal RAM tier |
| US GalaxyS24 versus French/Gulf GalaxyS24/S24+ | US official spec sheet lists Snapdragon8Gen3; France/Gulf official sources list Exynos2400 | even premium Samsung names can have regional CPU differences |
| India2025 new-smartphone shipments | Counterpoint reports MediaTek47%, Qualcomm29% | Qualcomm-only would be a consequential market limitation; shipment share is NOT installed Android/user share |

Families to cover in the landscape: older/newer Qualcomm Snapdragon, MediaTek Helio/Dimensity, Samsung Exynos, Google Tensor and UNISOC (reported as Spreadtrum in Play). MediaTek also has flagship products; it is not synonymous with low-end. Huawei/Kirin is another ecosystem, with app-store availability a separate issue, not an assumption of Google Play reach.

Precise chip shares by Malaysia/SEA, Europe, Africa and Latin America are NOT established by this pass. Public regional brand/model data cannot be converted to CPU share without exact SKU mapping; do not invent country-to-chip or shipment-to-installed-base conversions. Region-specific market totals remain a separate research gap if needed for audience prioritization.

## 4. Actual remote-device access gathered

Authenticated read-only command succeeded:
gcloud --configuration=business --project=ageless-domain-493017-j8 firebase test android models list --format=json
Full catalog saved privately; these rows are PHYSICAL phones with arm64 ABI and listed Android13+ versions. Catalog presence/capacity is not a reservation or current account free-quota proof. Actual lab-unit RAM and CPU features must be read before interpreting a test.

| Hardware class | Candidate / Test Lab ID | Chip in Play metadata | Available API in lab | Evidence status |
|---|---|---|---|---|
| Exact affected phone | A23 4G / a23 | SM6225 | not found in current Test Lab catalog | exact reproduction access gap |
| Same older Qualcomm chip | Galaxy A05s / a05s | SM6225, Adreno610 |35 | real device listed, low capacity; lab RAM unknown |
| Older MediaTek | Galaxy A32 / a32 | MT6769T, MaliG52 |33 | real device listed, low capacity; note this is NOT affected second installation's SM-A326B/5G |
| Mid-range MediaTek | Galaxy A34 5G / a34x | MT6877V/TTZA, Dimensity1080 |36 | real device listed, low capacity |
| Mid-range Exynos | Galaxy A35 5G / a35x | s5e8835, Exynos1380 |34/35/36 | API34 medium capacity; real unit untested |
| Tensor | Pixel8 / shiba | TensorG3, MaliG715 |34/35/36 | API35 high capacity; previous single-case receipt is not current qualification |
| UNISOC | realmeC53 / RE58C2 | SpreadtrumT612, MaliG57 |35 | real device listed;4–8GB catalog variants, actual lab RAM unknown |
| Older flagship Qualcomm | GalaxyS21 5G / o1q | QualcommSM8350 |34/35 | optional generation/age sample, actual SKU to verify |
| Regional Exynos counterpart | GalaxyS21 5G / o1s | Exynos2100 |35 | optional paired variant; not inferred equivalent to o1q |
| Accepted flagship baseline | owned S26Ultra / m3q | SM8850, Adreno840 | owned device | existing bounded evidence; future regression must identify exact build/settings |

A05s is a strong CPU-compatibility proxy for A23 4G, not an exact phone/OS/RAM reproduction. A chip-level instruction failure may reproduce there; full A23 product behavior still needs exact-device evidence. Play metadata gives RAM ranges over variants; do not assume the lab supplied the6GB SKU.

Other services:
- BrowserStack App Live public list includes A34, A35, Pixel8 and current GalaxyS devices. No A23 match found in that public list. Account catalog/access and exact chip/RAM not verified; no subscription or upload performed.
- Samsung Remote Test Lab is documented as free remote Galaxy access. Its public device-list endpoint did not expose inventory in the accessible response. A23 availability/account access remains UNVERIFIED.
- Qualcomm Device Cloud documents real mobile/reference devices, catalog behind Qualcomm login and free minutes by allocation. No account, device inventory or minute allowance was verified. Do not assume it offers Snapdragon680.

## 5. Current Play scope and why model names are insufficient

Read-only Console search confirmed a23 is "Partially excluded by rule" with reported RAM3.7–8.0GB, Android12–14 and SM6225. Japanese4GB A23 variants were "Excluded by rule"; a23xq was partially excluded. Thus the current RAM rule permits some incompatible A23 4G variants, rather than excluding the whole model family.

A device CSV was exported from Console,7527 model rows with chip/GPU/ABI/API/RAM metadata. The visible list and export contain models with excluded variants; these7527 rows are NOT a count of currently eligible phone variants, people, downloads or market reach. The file does not carry per-variant targeting status, so aggregate eligibility cannot be reconstructed from it alone. Raw file stays private.

The export also contains UNISOC/Spreadtrum products and non-phone form factors. We need explicit form-factor and per-variant eligibility checks before any reach estimate. No exclusion rule or public requirement has been changed.

## 6. Smallest route to a defensible conclusion

1. Finish a requirements table for the exact shipped native closure and candidate portable builds: CPU instructions, ABI/API, dependencies, graphics-driver requirements and memory. Distinguish required from optional acceleration.
2. Choose a small physical matrix from the confirmed catalog, prioritizing A05s, A34, A35, Pixel8 and owned S26, with A32/UNISOC where needed to cover older cores/vendor gaps. Verify actual RAM, SoC, HWCAP and API first. No country-based selection.
3. Run the SAME existing model and fixed short/medium/long text with pinned prompts/settings. Record successful accepted polish, load/generation times, working memory and consecutive-generation behavior. Load-only, fallback, a skipped test or a crawler run is not AI acceptance.
4. Classify works usefully / executable but too slow / native incompatible / not verified. Only after those results recommend a temporary support requirement and smallest proactive change. Keep output quality and existing deadlines explicit; no automatic cloud enrollment.
5. Verify speech-to-editor flow separately on materially different classes before shipping. Remote fixed-text generation cannot prove microphone or insertion behavior.

Research gathered now cost$0. No tests/reservations started. Before any Test Lab execution check shared free allowance; before paid tests obtain a capped spend approval. Some low-capacity or missing physical SKUs can leave honest evidence gaps. No implementation approval is requested in this report; Gate2 remains pending the compatibility verdict.

## Sources and receipts

- Vendor0.4 exact build target: https://github.com/qualcomm/GenieX/blob/v0.4.0/sdk/cmake/arm64-android-llvm.cmake
- Android feature detection: https://developer.android.com/ndk/guides/cpu-features
- Model: https://huggingface.co/superwhisper/s1-mini-GGUF (current card; pinned artifact remains authority)
- Vendor CPU guidance/artifacts: https://github.com/qualcomm/GenieX/blob/v0.9.1/docs/en/resources/troubleshooting.mdx and https://github.com/qualcomm/GenieX/releases/tag/v0.9.1
- Exact candidate SDK source: https://github.com/qualcomm/GenieX/blob/v0.9.1/bindings/android/app/src/main/java/com/geniex/sdk/GenieXSdk.kt
- Google catalog semantics: https://firebase.google.com/docs/test-lab/android/available-testing-devices
- Play device variants/export: https://support.google.com/googleplay/android-developer/answer/7353455 and https://support.google.com/googleplay/android-developer/answer/9859371
- A05s Snapdragon680/6GB retail SKU: https://news.samsung.com/in/samsung-unveils-galaxy-a05s-with-stylish-design-snapdragon-processor-and-up-to-12gb-ram
- A34 MediaTek: https://news.samsung.com/id/lima-alasan-galaxy-a34-5g-awesome-lime-cocok-bagi-para-gamers
- A35 Exynos: https://semiconductor.samsung.com/processor/showcase/smartphone/
- Japan A23: https://www.docomo.ne.jp/support/product/sc56c/spec.html and https://www.au.com/online-manual/scg18/scg18_02/m_13_00_07.html
- US S24: https://images.samsung.com/is/content/samsung/assets/us/2401/business/mobile/phones/galaxy/Galaxy_S24_Series_B2B_Spec_Sheet.pdf
- France/Gulf S24: https://news.samsung.com/fr/galaxy-s24-series and https://www.samsung.com/ae/support/mobile-devices/what-are-the-application-processors-and-expected-os-updates-for-s24/
- India shipments: https://counterpointresearch.com/default.htm/insights/Premiumization-Gains-Strength-in-India-Smartphone-Market-in-2025-Apple-Records-its-Highest-Ever-Value-Share
- BrowserStack actual public inventory: https://www.browserstack.com/list-of-browsers-and-platforms/app_live
- Samsung service/inventory endpoint: https://developer.samsung.com/remote-test-lab and https://developer.samsung.com/remotetestlab/rtlyevicelist.action
- Qualcomm lab limitations: https://qdc.qualcomm.com/support/faq

Private receipts: docs/internal/p0-polish-hardware contains the source snapshots, native hashes/disassembly, verified CPU AAR and bytecode inspection, authenticated Firebase catalog, Play export and candidate-device matrix. No private brain files or raw installation identifiers are reproduced here.
