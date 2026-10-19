# Submitted Google Play listing

Exact text read from Console October12,2026 for the first public submission. Status and upgrade work:
[maintenance](../maintenance.md). Updating this file does not edit the submitted listing. Reconcile
changes with the application, privacy policy and Data Safety before the next Console update.

## App name

```text
EnviousWispr: Voice to Text
```

## Short description

```text
Press, speak, done. On-device dictation and AI polish, right where you type.
```

## Full description

```text
Speak naturally and put finished text where you were typing. EnviousWispr is free on-device dictation with built-in AI polish. Your voice never leaves your phone.

HOW IT WORKS
Tap the floating bubble beside a compatible text box, speak, and tap Stop. Speech becomes text on your phone, then AI polish helps turn it into clear writing. Finished text lands at your cursor in apps such as Gmail, WhatsApp and Chrome. Keep the keyboard you already use.

CLEANER WRITING
Remove filler words, tidy punctuation and choose a tone and format for your writing. Add names, product terms and aliases to your dictionary to help EnviousWispr recognize your words. Completed dictations are saved in History on your phone so you can copy them again.

ON YOUR DEVICE, ON YOUR TERMS
Speech recognition and local AI polish run on your phone. Audio is never uploaded. You can connect your own OpenAI, Anthropic or Google Gemini key for cloud polish; selected text and your custom words then go directly to that provider. The provider's charges and terms apply.

EnviousWispr sends limited usage and crash reports to PostHog and Sentry to help find and fix problems. Automatic reports exclude audio, dictated words, names you enter, file contents and API keys. They use a random installation ID, and PostHog may derive an approximate city or region from the connection. If you send feedback, your chosen message, optional reply email and any diagnostics you choose to include are sent to Envious Labs through Sentry.

FREE TO USE
No app subscription, trial, account or ads. Start from the floating bubble, Quick Settings tile or notification. Use the phone microphone or connected earbuds with automatic microphone selection.

WHAT YOU NEED
Android 13 or newer, a 64-bit ARM phone with at least 6 GB of memory, and about 1.2 GB of free storage for the two on-device models. Wi-Fi is recommended for setup. Performance varies by device.

ACCESSIBILITY ACCESS
EnviousWispr uses Android's Accessibility access to find the focused editable field, show the floating bubble and insert text after you start dictation. It does not operate your phone on its own or send field contents to Envious Labs. It is a general dictation app. You can turn this access off in Android Settings.
```

## Category, contact and assets

| Field | Submitted value |
|---|---|
| Category | Productivity |
| Contact | support@enviouslabs.co |
| Website | https://enviouswispr.com |
| Privacy policy | https://enviouswispr.com/privacy-policy/ |
| Default language | English (United States) |
| Icon | app-icon-512.png,512x512 |
| Feature graphic | [Submitted graphic](graphics/feature-graphic-1024x500.png),1024x500 |
| Phone screenshots | Eight v2 shots ([screenshots/v2](screenshots/v2/README.md)), 1080x1920, submitted for review October 19, 2026; live only after Google approves. October 12 set: Dictionary and History |
| Promotional video | https://www.youtube.com/watch?v=DdDj-2Bambw (EnviousLabs channel, public), submitted for review October 19, 2026. AI asset declaration: not labeled (founder 2026-10-19: stylized cartoon and narration, not realistic synthetic content). Declaration video is separate |
| Release notes | Welcome to EnviousWispr. |

## Claim owners

Audio/local processing and automatic reports: app/src/main/java/com/envi/wispr/privacy/PrivacyDisclosure.kt.
Cloud text: app/src/main/java/com/envi/wispr/providers/Provider.kt and the provider adapters. Voluntary
feedback is a separate content-bearing path; [Data Safety](../play-data-safety-answers.md) includes it.
Compatibility/filter: [device support](../device-support-decision.md), app/build.gradle.kts.
Local polish: app/src/main/java/com/envi/wispr/polish/S1Config.kt and qualified runtime routes.
History excludes wordless/cancelled takes; do not promise every attempted dictation is retained.
Do not restore the old any-text-box, only-cloud-text-leaves, CPU-only or names-always-right claims.
