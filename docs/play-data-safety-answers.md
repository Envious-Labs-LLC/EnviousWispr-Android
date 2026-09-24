# Play Data Safety answers

The exact answers to give in the Play Console Data Safety form, grounded in the shipping code and the
privacy boundary in `CLAUDE.md` (the boundary is the network, not the phone). Verified against the tree
2026-09-20 (telemetry added by #176). Owner of the code truth: `app/src/main/java/com/envi/wispr/privacy/PrivacyDisclosure.kt`
(the Privacy page and telemetry sentences), `app/src/main/java/com/envi/wispr/providers/Provider.kt` (`disclosure()`,
the cloud-text sentence per provider, #308) with `app/src/main/java/com/envi/wispr/telemetry/PayloadSanitizer.kt` (what a row may carry).
This is not required for an internal-only release; it gates the first closed or public track.

## FACT: what-the-app-actually-sends
Grounded 2026-09-15. `INTERNET` is the only network permission (`AndroidManifest.xml`). Outbound hosts in
the code:

- `huggingface.co` — the two model files at a pinned revision, `models/ModelManifest.kt`. No user content.
- `api.openai.com`, `api.anthropic.com`, `generativelanguage.googleapis.com`, and the user's own
  self-hosted endpoint — cloud polish ONLY, reached only when the user turns on cloud polish and supplies
  their own key. The selected transcript TEXT plus the custom-words list is sent, straight to the provider
  the user chose, under the user's own key. `providers/ProviderPolishClient.kt` sends it through
  `providers/HttpProviderTransport.kt`; the exact body per provider is `providers/OpenAiAdapter.kt`,
  `GeminiAdapter.kt`, `ClaudeAdapter.kt`, `SelfHostedAdapter.kt`; the prompt is
  `providers/ProviderPolishPrompt.kt`.

- `us.i.posthog.com` (usage rows) and `o4511097055477760.ingest.us.sentry.io` (crash reports and our own
  defects), since #176. Content-free by construction: every property leaves only under a name and a shape the
  allowlist in `telemetry/PayloadSanitizer.kt` declares (numbers, booleans, and for a string the key's closed
  value set or shape in `telemetry/PostHogSchema.kt`: a token the app itself makes, a UUID, a package name, the
  device model); a transcript, a product name, a key, an email or a path cannot pass, and exception
  messages are always dropped. One random install id (`telemetry/InstallIdentity.kt`) tells installs apart; it
  is minted on the phone, never derived from the person or the account, and never reset. What the rows say is
  in `.claude/knowledge/telemetry.md`.

There is NO Envious Labs server. Audio never leaves the phone on any path. Provider API keys are stored in the
Android Keystore and are never sent to us (`providers/AndroidKeystoreSecretStore.kt`). Play builds carry the
two client keys from repository variables; a PR build has none and sends nothing (`scripts/release/build.sh`).

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
| Data type: **App activity / App interactions** | Collected: **Yes**. Shared: **No**. Required (no in-app switch). Purpose: **Analytics**. Not ephemeral. | The usage rows (#176): a dictation's outcome and timings, settings changes, onboarding steps, model downloads. Sent to PostHog, a processor acting for us, which Google does not count as "sharing". |
| Data type: **App info and performance / Crash logs** | Collected: **Yes**. Shared: **No**. Required. Purpose: **Analytics**. Not ephemeral. | Crash reports and our own defect reports to Sentry, a processor. No message text; frames only. |
| Data type: **App info and performance / Diagnostics** | Collected: **Yes**. Shared: **No**. Required. Purpose: **Analytics**. Not ephemeral. | Step timings and the take's route and ending, on the same usage rows. |
| Data type: **Device or other IDs** | Collected: **Yes**. Shared: **No**. Required. Purpose: **Analytics**. Not ephemeral. | The random install id on every row, plus the device model and OS version. Google counts an app-instance id here. |
| Data type: **Location / Approximate location** | Collected: **Yes**. Shared: **No**. Required. Purpose: **Analytics**. Not ephemeral. | PostHog, as our processor, derives an approximate city or region from the telemetry request's IP (a project-level setting shared with the Mac). The app never reads GPS or the device's location. Google requires this to be declared. |
| Data type: **Contacts, Personal info, Financial, Health, Photos, Files, Calendar, Web browsing** | **No** to all. | Nothing of the kind is read. On-device processing is exempt. |
| Is all collected data encrypted in transit? | **Yes** | Model downloads, both telemetry vendors and every provider endpoint are HTTPS. |
| Do you provide a way to request data deletion? | **No** | There is no account, and the app does not yet show the random install id that would locate one installation's rows reliably (an approximate date and device cannot). On-device History, custom words and settings are deleted in the app or by uninstalling. Stage 2: an in-app "copy my install id" row turns this into a Yes. |
| Privacy policy URL | **Required** (a public HTTPS non-PDF page). | Mandatory even when we collect nothing ourselves, because the app requests the Accessibility API. |

## FACT: the-listing-and-policy-must-agree
The four claims must match `PrivacyDisclosure.kt` (the page and telemetry sentences) and `Provider.disclosure()` (cloud
text per provider): audio never leaves the phone (true on every path);
selected TEXT goes to the user's chosen cloud provider under their key (true, and must appear); usage and
crash reports leave with no dictated words, entered names, file contents or keys, under a random install id,
with an approximate location derived by PostHog from the IP (true since #176, and must appear: the old "no
tracking, no analytics" and "anonymous" wordings are false); NPU polish is not shipped (keep it out). A listing
carrying only the audio claim answers this form wrongly (`../.claude/rules/content-brand.md` RULE:
the-listing-may-only-claim-what-ships).

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
