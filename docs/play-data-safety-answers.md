# Play Data Safety answers

The exact answers to give in the Play Console Data Safety form, grounded in the shipping code and the
privacy boundary in `CLAUDE.md` (the boundary is the network, not the phone). Verified against the tree
2026-09-15. Owner of the code truth: `app/src/main/java/com/envi/wispr/privacy/PrivacyDisclosure.kt`.
This is not required for an internal-only release; it gates the first closed or public track.

## FACT: what-the-app-actually-sends
Grounded 2026-09-15. `INTERNET` is the only network permission (`AndroidManifest.xml`). Outbound hosts in
the code:

- `huggingface.co` — the two model files at a pinned revision, `models/ModelManifest.kt`. No user content.
- `api.openai.com`, `api.anthropic.com`, `generativelanguage.googleapis.com`, and the user's own
  self-hosted endpoint — cloud polish ONLY, reached only when the user turns on cloud polish and supplies
  their own key. The selected transcript TEXT plus the custom-words list is sent, straight to the provider
  the user chose, under the user's own key. `providers/ProviderPolishClient.kt`,
  `providers/ProviderPolishPrompt.kt`.

There is NO Envious Labs server and NO analytics/telemetry SDK on Android (no PostHog, Sentry, Firebase, or
Crashlytics on the classpath; verified 2026-09-15). Audio never leaves the phone on any path. Provider API
keys are stored in the Android Keystore and are never sent to us (`providers/AndroidKeystoreSecretStore.kt`).

## FACT: the-form-answers
Google defines "collected" as data leaving the device (including via an integrated third party) and
"shared" as transfer to a third party. Cloud polish is a user-initiated transfer, but because an enabled
cloud-polish setting sends every subsequent transcript automatically, declare it as collected AND shared
rather than lean on the user-initiated-sharing exception (the conservative, review-safe answer; council
2026-09-15).

| Form section | Answer | Why |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | Cloud polish transmits transcript text off-device. |
| Data type: **Messages / Other in-app messages** (the transcript text) | Collected: **Yes**. Shared: **Yes**. Optional. Purpose: **App functionality**. | Only when the user enables cloud polish under their own key. |
| Data type: **Audio / Voice or sound recordings** | Collected: **No**. Shared: **No**. | Audio never leaves the phone. |
| Data type: **App activity, App info & performance, Device/other IDs, Location, Contacts, Personal info, Financial** | **No** to all. | No telemetry, no accounts, no server. On-device processing is exempt. |
| Is all collected data encrypted in transit? | **Yes** | Model downloads and every provider endpoint are HTTPS. |
| Do you provide a way to request data deletion? | **The app does not allow users to create accounts; no user data is stored on developer servers.** | No account, no server. Provider-held copies are governed by the provider the user chose. |
| Privacy policy URL | **Required** (a public HTTPS non-PDF page). | Mandatory even when we collect nothing ourselves, because the app requests the Accessibility API. |

## FACT: the-listing-and-policy-must-agree
The three claims must match `PrivacyDisclosure.kt`: audio never leaves the phone (true on every path);
selected TEXT goes to the user's chosen cloud provider under their key (true, and must appear); NPU polish
is not shipped (keep it out). A listing carrying only the audio claim answers this form wrongly
(`../.claude/rules/content-brand.md` RULE: the-listing-may-only-claim-what-ships).

## FACT: the-foreground-service-and-accessibility-declarations-are-separate
Beyond Data Safety, two Console declarations are still owed and are the highest review risk:

- **Foreground service types.** The manifest declares `microphone|dataSync` on the dictation service and
  `dataSync` on model delivery (`AndroidManifest.xml`). Android 14+ requires a Console declaration for each
  type with a short use description and, for microphone, a demo.
- **Accessibility API.** `isAccessibilityTool` must be declared **false** (this is a general dictation app,
  not an assistive tool), plus the prominent-disclosure screen (already built,
  `ui/AccessibilityGuideActivity.kt`), plus a demo video showing the disclosure, the Agree choice, the
  settings hand-off, and cross-app insertion. Owner: `../.claude/rules/content-brand.md`
  RULE: the-accessibility-disclosure-is-policy-not-copy.
