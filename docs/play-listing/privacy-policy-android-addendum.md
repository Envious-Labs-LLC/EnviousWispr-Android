# Privacy policy: the Android addendum

Play requires a public, non-PDF privacy policy URL; the app requests the Accessibility API and, since #176,
sends pseudonymous usage and crash reports (`docs/play-data-safety-answers.md`). The
policy already exists for macOS at https://enviouswispr.com/privacy-policy/ (Termly-generated, last
updated July 5, 2026, source `~/Developer/EnviousLabs/EnviousWispr/website/src/pages/privacy-policy.astro`).
One policy, one URL: extend that page to cover Android rather than publishing a second document.

## The change to the live page

Three edits, all in `website/src/pages/privacy-policy.astro` of the macOS repo:

1. **The product paragraph** (line 78 area, the single-line `<div>` that begins "EnviousWispr is a macOS
   desktop application"). Keep the macOS paragraph and add this paragraph directly after it:

```
EnviousWispr for Android is a mobile application for on-device voice-to-text dictation. Users tap a floating button, a Quick Settings tile, or a notification, speak, and the app transcribes their speech into text using machine learning models that run on the phone, then places the result in the text field the user was typing in. Audio capture and speech-to-text transcription run locally on the user's phone. EnviousWispr does not operate a transcription server, and the app is designed so that the user's dictation audio and transcripts are not sent to Envious Labs through the transcription or AI polish features. Polishing the transcribed text is optional and runs on the phone by default; if the user chooses a cloud polish provider, the transcribed text, together with the user's custom words list (which may include names the user has added), is sent (never the audio) from the user's phone directly to that provider, such as OpenAI, Anthropic, or Google Gemini (or, for a configuration saved by an earlier version, a server the user hosts), under the user's own API key and that provider's terms. The Android app requests the Android Accessibility permission solely to identify the text field the user is working in and to insert the transcribed text there after the user starts a dictation; the contents of that field are processed on the phone and are not sent to Envious Labs. The Android app uses the microphone only while the user is dictating. The app downloads its speech and polish models once, over the internet, from a model host; that download carries no user content. The Android app sends pseudonymous usage and crash reports to Envious Labs' service providers, PostHog (usage) and Sentry (crash reports), both hosted in the United States: whether a dictation finished and how long each step took, which settings are on, which app the words were placed in, model download outcomes, and a crash report when the app fails. PostHog may derive an approximate city or region from the request IP address; the app does not access the device's location. These reports are designed never to contain audio, dictated text, the contents of any text field, names entered by the user, file contents, or API keys. Crash reports may include sanitized technical stack-frame locations. Each report carries a random installation identifier generated on the phone that distinguishes one installation from another and is not linked to an account. The Android app creates no account. Dictation history, custom words, and settings are stored on the phone only and can be deleted by the user in the app or by uninstalling it.
```

2. **The page description** (two places: the JSON-LD `description` on line 10 and the `<BaseLayout
   description=…>` on line 29): change "No audio leaves your Mac." to "No audio leaves your device."

3. **"Last updated"**: set to the publish date.

## Why the wording is safe

Every sentence traces to code in the Android repo: the entry points and on-device transcription
(`.claude/knowledge/current-state.md`), what leaves the phone and to whom (`privacy/PrivacyDisclosure.kt` for the page's sentences,
`providers/Provider.kt` `disclosure()` for cloud text per provider, `docs/play-data-safety-answers.md`), the accessibility purpose and the field-contents promise
(`ui/AccessibilityGuideActivity.kt`, `.claude/rules/content-brand.md` RULE:
the-accessibility-disclosure-is-policy-not-copy), the model download (`models/ModelManifest.kt`), the
telemetry sentences (`privacy/PrivacyDisclosure.kt`, enforced by `telemetry/PayloadSanitizer.kt` and
`telemetry/InstallIdentity.kt`, issue #176). The Termly sections that follow (rights, retention, contact)
already apply to both products because they are written for "the Services".

## How to ship it

It is a marketing-site change in the macOS repo, so it follows that repo's rules (a worktree, a PR,
Codex review, its deploy). Owner for the deploy: `~/Developer/EnviousLabs/EnviousWispr/CLAUDE.md`.
Once live, paste https://enviouswispr.com/privacy-policy/ into Play Console > Store presence > Main store
listing > Privacy policy, and into Policy > App content > Privacy policy.
