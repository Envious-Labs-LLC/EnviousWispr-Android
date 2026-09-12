# Which window hosts the lips bubble: Codex adjudication, 2026-09-12

Read-only run of gpt-6-astra over the study, the recording overlay, the accessibility service and the
architecture rules. The prompt asked for a known-problem web pass, an option table, one recommendation
and device probes. Outcome: extend the existing accessibility overlay; no new permission. Verbatim answer
follows.

### A. Known-problem pass

**This is a known design choice; neither window type is table stakes.**

Shipped products use both approaches. [Zello’s floating PTT button](https://support.zello.com/zc/using-overlay-aka.-floating-buttons-on-android) requires “Display over other apps”; its exact window/service implementation is **NOT VERIFIED**. [Tasker documents accessibility overlays](https://tasker.joaoapps.com/userguide/en/help/ah_java_code.html) for interactive controls above other overlays. Our study reports Wispr Flow uses an application overlay and foreground service; I did not independently verify its APK.

Canonical platform answers:

- **Touch, drag, hold:** `TYPE_ACCESSIBILITY_OVERLAY` explicitly supports intercepting user interaction. Ordinary View touch events can classify tap/hold/drag; dragging updates window coordinates. Accessibility overlays are not display-only. [Android window reference](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)
- **Android 12+:** accessibility overlays are trusted and exempt from untrusted *pass-through* touch blocking. Application overlays are untrusted. This restriction does not prohibit touching the bubble itself; keep either window tightly bounded. [Android behavior changes](https://developer.android.com/about/versions/12/behavior-changes-all#untrusted-touch-events)
- **Keyboard layering:** application overlays sit below the IME. AOSP places accessibility overlays above it; Samsung’s actual placement remains a device check. [Android reference](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY), [AOSP window policy](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/policy/WindowManagerPolicy.java)
- **Restricted settings:** Android 13+ adds approval barriers for Internet-sideloaded apps. Play internal testing is not that installation route, so no sideload-unlock step is expected; this phone’s result is **NOT VERIFIED**. [Google security explanation](https://security.googleblog.com/2024/05/io-2024-whats-new-in-android-security.html?hl=bn)
- **Play:** non-disability uses are permitted with declaration, review, prominent disclosure and consent. Existing insertion approval does not automatically cover newly described behavior; disclose the floating trigger too. [Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- **Lifetime:** a foreground service is not immortal. Users can stop it through Android’s active-app controls. Samsung promises compliant foreground-service behavior from One UI 6, but that does not establish every OEM’s survival behavior or this idle bubble’s eligibility. OEM kill rates are **NOT VERIFIED**. [Android stop controls](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping), [Google/Samsung statement](https://android-developers.googleblog.com/2023/05/improving-consistency-of-background-work-on-android.html)

### B. Option table

“Accessibility” below means the already-required insertion service.

| Option | Permissions added to onboarding | Idle cost versus law | Lifetime / who can kill it | Editable-focus source | Touch, drag, hold | Accessibility off | Play policy exposure | IME z-order |
|---|---|---|---|---|---|---|---|---|
| **1. Extend accessibility overlay** | None | Static window; no recurring work. Visibility changes require window calls | System-bound service; disabling access, process death, force-stop or OEM intervention removes it | Existing accessibility events, directly | All supported | Bubble disappears; insertion impossible | Existing accessibility declaration must describe trigger | Above IME in AOSP |
| **2. Application overlay + persistent foreground service** | “Appear on top” | Can sleep without polling, but adds persistent service/notification; transitions still call system | Foreground service; user Stop, force-stop, process/OEM kill, overlay revocation | Still needs accessibility to push focus state | All supported | Bubble can remain; field awareness and insertion unavailable | Accessibility plus overlay use and additional foreground-service justification | Below IME |
| **3. Application idle bubble + accessibility recording pill** | “Appear on top” | Two hosts and handoff state; no idle-cost advantage | Both lifetimes and permissions matter | Accessibility forwards events | All supported; gesture continuity crosses hosts | Idle bubble may remain; insertion impossible | Same additional exposure as option 2 | Idle below; recording above |

A persistent service does **not inherently mean polling**. However, a literal ban on every binder call whenever recording is inactive prevents either option from showing/hiding on focus changes. Define the law as zero recurring idle work, allowing event-driven window transitions. [Foreground-service requirements](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)

### C. Recommendation

**Choose option 1.** It reuses the service insertion already depends on, adds no permission, and supports every requested gesture.

Concrete changes:

- **`RecordingAccessibilityOverlay.kt`:** extend the existing renderer/window with idle lips and recording states. Preserve tight bounds and non-focusable flags. Add persisted `side` and normalized `y`; clamp temporarily around keyboard/insets, expand inward, and preserve the preferred position.
- **`PasteAccessibilityService.kt`:** retain window ownership. `onAccessibilityEvent()` already calls `rememberEditableTarget()`; `TYPE_VIEW_FOCUSED` supplies the editable source, with `TYPE_VIEW_CLICKED` also accepted. Push visibility from this path; use existing window-state events to invalidate eligibility without tree searches. A remembered target alone is not proof of current focus.
- **Commands:** tap → `ACTION_START`; check → `ACTION_STOP`; recognized hold → `ACTION_START`; release → `ACTION_STOP`; explicit cancel/interrupted gesture → `ACTION_CANCEL`. Drag sends none. Keep the gesture receiver alive through expansion.
- **`DictationSessionService.kt`:** define release-during-startup behavior explicitly: current `ACTION_STOP` cancels `STARTING`. This is an integration requirement, not an unbuilt-bubble defect.
- **Config/manifest:** no added accessibility subscription, overlay permission or idle foreground service.
- **Architecture:** trigger belongs to the heart; rendering sends commands, while the existing owner handles dictation. No model/polish work belongs in the bubble.
- **Onboarding mock:** no new permission step; existing accessibility disclosure explains field detection, floating controls and insertion. No “Appear on top.”

### D. Device probes

After a prototype exists:

1. Does focusing Chrome’s practice field show the bubble without dismissing the keyboard?
2. Does dragging across the keyboard preserve touch delivery and avoid starting recording?
3. Does one tap/hold/release script produce exactly one session per gesture, including early release?
4. Does rotation preserve the chosen edge and preferred height?
5. After an overnight screen-off interval, does focusing a field restore the bubble?
6. Does enabling accessibility on the Play-internal build avoid a restricted-settings gate?