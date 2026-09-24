# Google Play store listing, ready to paste

Every field below is the exact text for the Play Console (Grow > Store presence > Main store listing).
Grounded 2026-09-17 against the tree: `privacy/PrivacyDisclosure.kt` (the Privacy page and telemetry sentences), `providers/Provider.kt` `disclosure()`
(cloud text per provider),
`app/build.gradle.kts` (`minSdk = 33`, arm64 only), `docs/device-support-decision.md` (Option A, 6 GB),
`docs/play-data-safety-answers.md`. Rules: `.claude/rules/content-brand.md` (relief-centred, no dashes,
claim only what ships). Every claim here has a code owner named in the "Why this is true" column of the
claims table at the end; change the code and this page together.

## App name (30 characters max)

```
EnviousWispr: Voice to Text
```
(27 characters.)

## Short description (80 characters max)

```
Press, speak, done. Private on-device dictation, typed into the app you use.
```
(76 characters.)

## Full description (4,000 characters max)

```
Typing on a phone is slow, and talking to it usually means sending your voice to someone's server. EnviousWispr is the third option: press, speak, and finished text lands in the app you were typing in. Your voice never leaves your phone.

HOW IT WORKS
Tap the floating bubble beside any text box, say what you want, and tap the check. EnviousWispr turns your speech into text on your phone, cleans it up, and puts it right where your cursor was, in apps like Gmail, WhatsApp and Chrome. You keep your own keyboard. Nothing to switch, nothing to learn.

PRIVATE BY DESIGN
Speech recognition runs entirely on your phone. Audio is never uploaded, to us or to anyone. There is no account, no sign-in, and no ads. The only time any text leaves your phone is if you choose to connect your own AI provider key for cloud polish, and then your text goes straight from your phone to the provider you picked. The app sends usage and crash reports so problems get fixed; they never carry your words.

FINISHED TEXT, NOT A RAW TRANSCRIPT
Say it the way you would say it out loud. EnviousWispr removes the ums, fixes punctuation and capitals, and keeps your voice and your meaning. On-device polish is built in. If you want more, plug in your own OpenAI, Anthropic, or Google Gemini key.

YOUR WORDS
Names, product terms, and jargon that speech engines get wrong: add them once to Your Words and they land right every time. Aliases let you say one thing and type another.

MADE FOR REAL PHONES
Works with your earbuds: when they are connected, they are the microphone. A one-tap floating bubble, a Quick Settings tile, and a notification all start a dictation. Every dictation is kept in History on your phone, so you can copy it again later.

FREE
No subscription, no trial, no word count or daily quota. Each dictation can run up to ten minutes. Bring your own key only if you want cloud polish.

WHAT YOU NEED
Android 13 or newer, a 64-bit phone with 6 GB of memory or more, and about 1.2 GB of free storage for the two on-device models, downloaded once (Wi-Fi recommended).

ABOUT ACCESSIBILITY ACCESS
EnviousWispr asks for Android's Accessibility permission so it can find the text box you are using and put your words there after you start a dictation. It is a dictation app, not an assistive tool. It never operates your phone on its own, and field contents are never sent to Envious Labs. You can switch the access off in Android Settings at any time; dictation then copies your words to the clipboard instead.
```
(2,419 characters.)

## Category and contact

| Field | Value |
|---|---|
| App category | Productivity |
| Tags | Voice to text, Dictation, Speech recognition, Keyboard, Notes |
| Email | support@enviouslabs.co |
| Website | https://enviouswispr.com |
| Privacy policy | https://enviouswispr.com/privacy-policy/ (after the Android addendum in `privacy-policy-android-addendum.md` is published) |

## Graphics

| Asset | Spec | Source |
|---|---|---|
| App icon | 512 x 512 PNG, 32-bit, no alpha | Export from `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml` (adaptive icon) at 512 px; the 192 px raster in `mipmap-xxxhdpi` is too small to upload |
| Feature graphic | 1024 x 500 PNG or JPEG | To make: brand palette (`.claude/knowledge/design-language.md`), the lips mark, the line "Press, speak, done." |
| Phone screenshots | 2 to 8, 16:9 or 9:16, 320 to 3840 px | `screenshots/` beside this file, captured on the emulator (see `launch-checklist-2026-09-17.md`) |

## What's new (release notes, 500 characters max)

```
First release. Press, speak, and finished text lands in the app you were typing in. Speech recognition runs on your phone; your voice is never uploaded. On-device polish removes filler and fixes punctuation. Your Words teaches it names and jargon. Earbuds are the microphone when they are connected. Free, no account.
```
(317 characters.)

## Claims table: every sentence above that a reader could act on

| Claim | Why this is true today | Owner in code or docs |
|---|---|---|
| Your voice never leaves your phone | `INTERNET` reaches only model downloads and the user's own polish provider; audio is never sent on any path | `privacy/PrivacyDisclosure.kt` (`ON_DEVICE_SUMMARY`), `providers/Provider.kt` (`disclosure()`), `docs/play-data-safety-answers.md` |
| Text leaves the phone only for the user's own cloud polish key | Cloud polish is opt-in, straight to the provider | `providers/ProviderPolishClient.kt`, `providers/HttpProviderTransport.kt`, and the four provider adapter files |
| On-device polish is built in | S1-mini on llama.cpp in `:polish`, CPU path, shipped | `.claude/knowledge/polish-engines.md` |
| Providers: OpenAI, Anthropic, Google Gemini | The three a new install can connect; self-hosted exists only for a configuration an older build saved (`ui/PolishLadder.kt` excludes it from the pickable set) | `Provider.capabilities().offeredAsSetupTile`, `CloudProviders` (`ui/PolishLadder.kt`) |
| Earbuds are the microphone when connected | PR #165, Auto prefers connected earbuds | `audio/InputDeviceResolver.kt` |
| Bubble, Quick Settings tile, notification start a dictation | Three of the five entry points into the session owner | `.claude/knowledge/current-state.md` |
| Apps like Gmail, WhatsApp and Chrome | The three named are the ones insertion was proven in on the phone (Gmail and WhatsApp 2026-09-03, Chrome 2026-09-16); "like" because insertion works through the focused editable field, which some apps do not expose | `.claude/knowledge/device-testing.md` |
| Removes the ums | Filler removal is on by default | `settings/AppPreferences.kt`, `cleanup/` |
| History keeps every dictation, copy it again later | Room `transcripts` table with Copy on the expanded card; wordless and cancelled takes are not stored, and a crash mid-take loses that take (#43), which is why the listing promises copying later, not that nothing is lost | `history/`, `ui/HistoryScreen.kt` |
| Your Words with aliases | Structured custom vocabulary | `vocabulary/` |
| Android 13+, 64-bit, 6 GB | `minSdk = 33`, `arm64-v8a`, founder Option A 2026-09-16 | `app/build.gradle.kts`, `docs/device-support-decision.md` |
| About 1.2 GB of models, Wi-Fi recommended | Speech 670 MB + polish 484 MB; onboarding has a mobile-data switch, so Wi-Fi is a recommendation, not a requirement | `models/ModelManifest.kt`, `docs/launch-readiness-2026-09-15.md` |
| Clipboard fallback without accessibility | Guarded insertion with clipboard fallback | `.claude/knowledge/current-state.md` |
| Free, no account, no ads; usage and crash reports with no words | No server, no accounts, no ads; PostHog and Sentry rows are allowlist-scrubbed (`telemetry/PayloadSanitizer.kt`) and keyed to a random install id | `docs/play-data-safety-answers.md`, `privacy/PrivacyDisclosure.kt` |
| Each dictation up to ten minutes | `RecordingLimits.MAX_DURATION_MS` is 600,000 ms; the capture loop stops the take at the cap and transcribes what it has | `audio/RecordingLimits.kt` |

Not claimed on purpose: NPU polish (development override, never shipped); language coverage beyond
English (Parakeet v3 recognises 25 European languages but nothing in the app names or selects them, so
the listing stays silent until a picker or a claim owner exists); a warm microphone (declined 2026-09-17).
