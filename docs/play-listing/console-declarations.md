# Play Console declarations, ready to paste

Policy > App content. Each block is the exact answer. Grounded 2026-09-17 against
`app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/accessibility_service_config.xml`
(`isAccessibilityTool="false"`, pinned by `AccessibilityPolicyDeclarationTest`),
`ui/AccessibilityGuideActivity.kt` (the disclosure screen) and `docs/play-data-safety-answers.md`.

## 1. Foreground service permissions

The manifest declares two types. Android 14+ (targetSdk 36) requires a Console declaration per type.

### FOREGROUND_SERVICE_MICROPHONE

**Which service:** `ui.DictationSessionService` (`microphone|dataSync`).

**Describe the feature (paste):**
```
EnviousWispr is a dictation app. When the user starts a dictation (by tapping the floating bubble, the Quick Settings tile, or the notification), the app records from the microphone for the length of that one dictation, transcribes it on the device, and inserts the text into the app the user was typing in. The microphone foreground service exists only while the user is dictating and stops when the dictation ends. Audio is processed on the device and never uploaded.
```

**Video:** the same video as the accessibility declaration below (it shows a dictation starting from the bubble with the recorder visible, and ending).

**Why this type is required:** the user starts dictation from another app, so the recording runs while EnviousWispr is not in the foreground; Android requires the microphone foreground service type for that.

### FOREGROUND_SERVICE_DATA_SYNC

**Which services:** `ui.DictationSessionService` (`dataSync` half) and WorkManager's
`SystemForegroundService` (model delivery).

**Describe the feature (paste):**
```
On first run the app downloads its two on-device models (about 1.2 GB) so that speech recognition and text polish can run entirely on the phone. The data-sync foreground service keeps that one-time download alive with a visible notification and a cancel action, and stops when the download completes or is cancelled. The dictation service also carries the data-sync type because, when the user has connected their own cloud polish provider key, the transcript text is sent to that provider at the end of a dictation.
```

## 2. Accessibility API declaration (AccessibilityService)

**Is your app an accessibility tool designed to help users with disabilities?** No.
(`isAccessibilityTool="false"`; EnviousWispr is a general dictation app.)

**Core functionality that requires the AccessibilityService API (paste):**
```
Inserting dictated text into the text field the user is working in, in any app. After the user starts a dictation, EnviousWispr uses the AccessibilityService API to identify the focused editable field, place the transcribed text at the cursor, and verify that it appeared. It also shows a small floating bubble beside the focused field so the user can start a dictation without switching apps. The service does not operate the phone on its own, does not read screens outside the focused field, and never sends field contents to Envious Labs.
```

**Prominent disclosure and consent:** the app shows a full-screen explanation (`Let the bubble float beside
your text box`) with the four required statements and the choices "Agree and open Settings" / "Not now"
BEFORE any route into Android's Accessibility settings. All three routes (the Settings "Auto-paste access"
row, the onboarding accessibility step, the Home "Auto-paste is not connected" card) pass through it.

**Video requirements (record on the emulator, see `launch-checklist-2026-09-17.md`):** the disclosure
screen, tapping "Not now" (returns without opening Settings), the disclosure again, tapping "Agree and
open Settings", the Android Accessibility toggle, returning to the app, then one dictation from the bubble
inside Gmail or Chrome with the text landing in the field.

## 3. Data safety

Enter exactly the table in `docs/play-data-safety-answers.md` FACT: the-form-answers. One-line reminder of
the shape: collected and shared = **Messages / Other in-app messages** (transcript text), optional,
purpose App functionality, only under the user's own cloud polish key; audio **not** collected; no other
type; encrypted in transit; no account and no server-side data.

## 4. Content rating (IARC questionnaire)

| Question | Answer |
|---|---|
| Category | Utility, Productivity, Communication, or Other |
| Violence, sexuality, language, controlled substances | None |
| User-generated content shared with other users | No (dictations stay on the phone) |
| Users can communicate with each other | No |
| Shares user location | No |
| Purchases digital goods | No |
| Contains ads | No |
Expected rating: Everyone / PEGI 3.

## 5. Ads, news, government, financial, health

Ads: No. News app: No. Government app: No. Financial features: None. Health: not a health app.

## 6. Target audience and content

Target age: 18 and over (the app is not designed for children; a lower band adds Families policy work
for no reach). "Does your app appeal to children?" No.

## 7. Device catalogue (Reach and devices > Device catalogue > Manage exclusion rules)

Founder decision 2026-09-16, Option A of `docs/device-support-decision.md`:

| Rule | Value |
|---|---|
| RAM | Exclude devices with less than 6 GB (set the RAM slider to 6144 MB minimum) |
| Android Go | Exclude (system feature `android.hardware.ram.low`) |
| ABI | arm64-v8a only (already enforced by the bundle; no rule needed) |
| SoC / manufacturer | No restriction |

## 8. App access

"All functionality is available without special access." (No login. The optional cloud polish needs a
user's own provider key, which reviewers do not need: local polish is the default.)

## 9. Government-issued ID and sensitive permissions

No contacts, SMS, call log, location or file permissions are requested (manifest:
`RECORD_AUDIO`, `INTERNET`, `VIBRATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE*`). Nothing to declare
beyond sections 1 and 2.
