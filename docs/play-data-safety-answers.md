# Submitted Play Data Safety answers

This records the October12,2026 submission. Source truth: app/src/main/java/com/envi/wispr/privacy/PrivacyDisclosure.kt,
app/src/main/java/com/envi/wispr/providers/Provider.kt, feedback sender and telemetry sanitizers.
Keep the form, listing and https://enviouswispr.com/privacy-policy/ aligned with each new candidate.
Submission is not Google approval.

## FACT: what-the-app-actually-sends

Audio stays on the phone. Optional cloud polish sends selected text/custom words straight to the
chosen provider under the user's own key. Model downloads use the Envious Labs mirror with pinned
Hugging Face fallback. Automatic PostHog/Sentry reports include usage, app/result/timing metadata,
crash locations and a random install identifier; PostHog may derive approximate location from IP.
Automatic reports exclude audio, dictated words, entered names, file contents and keys.
Voluntary feedback separately sends the chosen message, optional reply email and previewed diagnostics
to Envious Labs via Sentry. Do not describe all outbound data as content-free.

## FACT: the-form-answers

Collect/share data: Yes. Encryption in transit: Yes. No app accounts. No audio collection/sharing.
The submitted handling for every selected type is not ephemeral.

| Type | Collected | Shared | Choice | Collection purposes | Sharing purposes |
|---|---|---|---|---|---|
| Email address | Yes | No | Optional | App functionality | None |
| Other in-app messages | Yes | Yes | Optional | App functionality | App functionality |
| Other user-generated content | Yes | Yes | Optional | App functionality, Analytics | App functionality |
| Approximate location | Yes | No | Required | Analytics | None |
| App interactions | Yes | No | Required | Analytics | None |
| Installed apps | Yes | No | Required | Analytics | None |
| Crash logs | Yes | No | Required | Analytics | None |
| Diagnostics | Yes | No | Required | Analytics | None |
| Device or other IDs | Yes | No | Required | Analytics | None |

Cloud sharing is declared conservatively. PostHog/Sentry process reports for Envious Labs and are not
declared as sharing for that processor use. Installed apps covers target-app package metadata, not
a claimed full app inventory. Email and submitted feedback must not disappear from the answers.

The optional deletion badge/URL was not claimed; those optional form fields were left blank. The
privacy policy provides a contact-request route. Do not substitute the old No-deletion-path statement
or promise automated deletion/retention controls the app does not implement. Families/UPI-specific
answers are blank where inapplicable. Do not enter guessed FALSE values for empty optional fields.

## FACT: the-listing-and-policy-must-agree

Distinguish local audio, optional cloud text, automatic metadata reports and voluntary feedback.
Use random installation identifier, not anonymous or no personal details. NPU is not offered.
Cloud provider charges/terms apply; no Envious Labs subscription is required.

## FACT: the-foreground-service-and-accessibility-declarations-are-separate

Both were saved with current videos. [Submitted declarations](play-listing/console-declarations.md)
own their exact choices. Update them when permissions, use, data or the prominent disclosure changes.
