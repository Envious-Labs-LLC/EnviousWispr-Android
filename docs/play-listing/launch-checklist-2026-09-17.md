# Launch checklist for the evening of 2026-09-17

Founder decisions in force: Option A device support (6 GB, arm64, Android 13+, no chipset rule) and no
closed beta: internal testing goes straight to a public release (`docs/device-support-decision.md`,
2026-09-16). This page is the order of operations. Every "paste" points at a file beside it.

## Done overnight (2026-09-17, no founder action)

- [x] Store listing text, category, contact, release notes: `store-listing.md`.
- [x] Console declarations: foreground services, accessibility, data safety pointer, content rating,
      device catalogue rules, target audience: `console-declarations.md`.
- [x] Privacy policy: the Android paragraph and the exact edits to the live page:
      `privacy-policy-android-addendum.md`.
- [x] 16 KB page-size check on the signed bundle (version 136): compliant for everything Android loads:
      `16kb-page-size-check-2026-09-17.md`.
- [x] Model hosting: costed proposal, recommendation R2 at under $1/month: `model-hosting-proposal.md`.
- [x] Earbud microphone, picker, Bluetooth tip: PR #165, build 136 on internal testing, awaiting the
      founder's phone pass (plan §11.1, eight runs).

## Founder, in the morning (about 20 minutes, in this order)

1. **Phone pass of build 136** with the AirPods: the eight runs in
   `docs/feature-requests/issue-26-2026-09-16-microphone-picker-and-bluetooth.md` §11.1. Say "good" and
   PR #165 merges and `main` goes to internal testing.
2. **Say "yes R2"** (or not) on `model-hosting-proposal.md`. Under $1/month.
3. **Say "publish the policy"**: the Android paragraph goes onto https://enviouswispr.com/privacy-policy/
   through the macOS repo's website process (its own PR and review).
4. **Play Console, Store presence > Main store listing:** paste from `store-listing.md`; upload the icon,
   feature graphic and screenshots from `graphics/` and `screenshots/` beside this file.
5. **Play Console, Policy > App content:** answer from `console-declarations.md` and
   `docs/play-data-safety-answers.md`; upload the accessibility video from `video/`.
6. **Reach and devices > Device catalogue:** the two exclusion rules in `console-declarations.md` §7.
7. **Production track:** create the release from the same bundle as the internal build that passed the
   phone pass (promote from internal testing, never a fresh upload), countries: all, rollout: staged at
   20 percent, then send for review.

## What Google's review will take

Expect 1 to 7 days for a first review with an Accessibility API declaration. The staged 20 percent
rollout can be raised from the Console at any time after approval without another review.

## Left open on purpose

- LE Audio earbuds (Galaxy Buds) are unmeasured; the code path ships from source reading and the plan
  says so. First user report on Galaxy Buds is the measurement.
- Storage pre-flight before the first 1.15 GB download (a clear message on a full phone) is stage 2 work
  and not built. Play's pre-launch report may exercise a low-storage device; the download fails with the
  existing error state rather than a crash (`model-delivery.md` FACT: the-ui-states).
- Hexagon DSP files in the bundle: see the 16 KB record; a packaging exclusion is queued for after launch.
