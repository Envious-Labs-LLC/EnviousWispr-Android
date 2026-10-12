# Submitted Play Console declarations

Saved/submitted October12,2026. These are current submitted answers, not Google approval.
[Release status](../maintenance.md) owns availability. Recheck actual form fields and candidate
permissions before changing declarations.

## Accessibility services

| Question | Submitted answer |
|---|---|
| Accessibility tool for disabilities | No |
| Usage | App functionality and Analytics |
| Collect/share sensitive data through Accessibility API | Yes |
| Data types through this API | Installed apps, App interactions, Diagnostics |
| Prominent disclosure video requirement | Acknowledged after actual-flow review |

The shared consent screen explains local field/cursor processing, insertion after user-started
dictation, target-app/outcome/timing reports to PostHog/Sentry and the random installation identifier.
Agree opens native Settings; Not now returns. Field contents are not sent to Envious Labs.
Source: app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt.

## Foreground service permissions

| Type | Tasks selected |
|---|---|
| Data sync | Other network processing; Other local processing |
| Microphone | Background audio input |

Network work includes model downloads and optional cloud text; local work includes verification and
on-device processing. Types remain microphone/dataSync for dictation and dataSync for WorkManager.
The live form used task choices and video links, not the old prose-description fields.

The same reviewed video serves the accessibility and three foreground-service slots:
https://drive.google.com/file/d/1vOOgHWrk_xMO8Cqq1LSvPGHOIqYl1UZE/view
The file was replaced in place October12 with current disclosure/refusal/acceptance/native grant,
recording/insertion and user-started download continuing after Home. It demonstrates the API features;
it does not prove speech accuracy or hardware qualification. Do not reuse the old September video.

## Other declarations

| Item | Submitted answer |
|---|---|
| App access | All functionality available without special access; no app account |
| Ads | No |
| Advertising ID | No; compiled bundle has no advertising-ID permission |
| IARC | All Other App Types; questionnaire No answers; Everyone/PEGI3 result |
| Target audience |18 and older; this is audience selection, not a claimed age gate |
| Government/financial/health | Not government; no financial features; not a health app |
| Data Safety | [Current submitted table](../play-data-safety-answers.md) |
| Device catalogue | [Actual filter and support scope](../device-support-decision.md) |
| Countries | Worldwide,177 countries/regions plus rest of world |

The founder accepted IARC and Device Catalog terms at action time. New binding agreements still need
their own action-time confirmation. Do not infer that saved declarations have been approved.
