# Issue #416: Unobstructed Accessibility setup, 2026-10-03

GitHub issue: #416. Tier: SMALL. Status: IMPLEMENTING.

## Preface: Lane and hardware
**Lane:** Code
mixed_pr: true
Additional lane: Docs/dev-tooling. Hardware UAT: Y, fresh S26 setup can press Android’s Allow control, return to onboarding, and insert one dictation in a real editor. PAR rows closed: none; this supplies first-run evidence, not full parity closure.

Authorization: Saurabh explicitly instructed consumer readiness, then “do everything but actually release it” on 2026-10-03. Routine pre-release fixes are within that mandate. Public rollout, new spending, new privacy/retention policy and changed device-support promises remain outside it. S26 app resets/reinstalls are explicitly authorized; never factory-reset the phone. Preserve source review and real-device gates.

## Preface: User rubric
Meera Patel installs Android dictation before replying in Messages/WhatsApp. Thirty seconds earlier she found the app; she wants to start speaking without technical help. Her words: “Let me set this up and send my reply.” She opens the app, follows its permission explanation, and chooses Android’s normal consent. Natural inputs: “We’ll be there at six”; “Please bring the blue bag”; “I’m running ten minutes late”; “Thanks, see you tomorrow”; “Can you send the address?” Success is approving access and receiving her first text. Wrong-but-working is a guide that looks helpful while hiding the approval button. A power user closes or moves the floating window; Meera should not need that workaround. Consent remains affirmative and reversible in Android Settings.

Cross-persona: Priya and Aaron need predictable access to real controls; Marcus wants no unsolicited action; Diana wants few interruptions; Elena needs the truthful disclosure; Meera and Frank need readable, unobstructed instructions. Keep the explanation, remove the interfering overlay.

## 0. TLDR
Play257’s automatic picture-in-picture guide covers Android’s Allow button on the S26. Stop entering picture-in-picture when handing off to system Settings. Keep the existing full-screen disclosure, instructions, failure/return actions and readiness checks. Remove the now-unreachable compact presentation and manifest capability together.

## 1. Observed problem
A fresh Play install on S26 (firstInstallTime 2026-10-03 12:24:19) reached verified models and microphone consent. After Agree, the guide entered bottom-right PiP. Android’s permission dialog remained visible with its Allow center covered by the guide. The window-checked harness refused the press; the screenshot /tmp/wispr-eyes-1791046171.png confirms it. Parsed evidence: docs/internal/consumer-readiness/phone-accessibility-guide-mismatch.json. This is a presentation obstruction, not permission failure.

## 2. Goals and non-goals
Goal: native consent controls remain visible and reachable; agree/decline and failed Settings launch retain their current semantics. Non-goals: changing access permissions, disclosure promises, insertion, capture, models, telemetry or privacy policy. Do not bypass native consent. No floating replacement, retry machine, new detector or service.

## 2.5 Grounding
Producer -> owner -> consumer: the disclosure Agree callback calls AccessibilityGuideActivity.openSettings; it replaces disclosure state, waits for pre-draw, calls enterPictureInPictureMode, then starts ACTION_ACCESSIBILITY_SETTINGS. Android chooses the PiP position; the compact AccessibilityGuide composable occupies it beside Settings and the native consent dialog. Source: app/src/main/java/com/envi/wispr/ui/AccessibilityGuideActivity.kt:57-74,106-147. The manifest allows PiP on this activity. Found with rg for Picture/pip/openSettings and ACTION_ACCESSIBILITY_SETTINGS.

The activity is the one shared owner; no second permission entry point is introduced. Bound-service observation closes the activity only after granted+bound, and onResume repeats the same check (:49-52,77-79). Android owns permission. The source intentionally autoplays illustrations every3400ms (:117-120), so its step number is not a current native-screen detector; no such claim is made.

Prior consumer baseline and code-only consultations are retained separately; they do not prove this fix. Current evidence is actual S26 UI+window geometry. Android’s normal dialog—not a custom substitute—is authoritative. Consent refusal leaves the app ungranted. Recreation preserves agreed; background observation follows lifecycle. Native position is outside our control. The pipe is automatic PiP admission; remove that source rather than catching obscured taps or guessing screen positions.

## 3. Design and ownership
Keep this in the existing activity because it owns disclosure and handoff. Remove PiP imports/state/entry/override and compact-only renderer branches, plus the manifest supportsPictureInPicture flag. Keep full-screen guide controls before/after system Settings, preserved agreed state, pre-draw handoff, safe failed-launch rendering, onResume and granted+bound observation. Android Settings is foreground alone. Alternative: reposition PiP, rejected because position varies by OEM/window and no public stable placement contract ensures consent remains uncovered.

## 4. Contract deltas
No public API, IPC or persisted preference changes. Agree still opens Settings after disclosure; it no longer opens an additional foreground floating surface. Decline still finishes without handoff.

## 5. Lifecycle audit
Enumerated paths: initial disclosure, agree, decline, Settings launch failure, background/recreation, granted-unbound, granted-bound, native denial and return. Only agree’s presentation changes. Preserve recreation and grant+binding completion predicates; do not treat enabled as bound. A failed launch keeps accessible retry/return controls. Unbound or denied never claims success.

## 6. Consumers
All shared grant callers keep the same activity and consent. Native Settings gains unobstructed controls. Onboarding consumes the existing permission+binding state. Full-screen guide loses only the unreachable compact variant. Manifest no longer declares the removed presentation capability.

## 7. Failures
Settings unavailable -> existing launchFailed message/retry/return; denial -> existing ungranted state; cold binding -> existing wait; recreation -> saved agreed; no native prompt -> no inferred grant. No new retry behavior.

## 8. Signals
agreed means consent to open Settings, not permission granted; launchFailed means Settings intent failed; AccessibilityPermission.isGranted and PasteAccessibilityService.isBound stay separate. Remove compact, which previously meant PiP presentation. No new Room, cloud or telemetry fields.

## 9. Fallback authority
Android permission and existing binding state remain authoritative. Unavailable Settings keeps the existing full-screen guide and launch-failure path; no automatic permission mutation or substitute success.

## 10. Files
AccessibilityGuideActivity.kt: remove PiP mechanism and compact renderer only. AndroidManifest.xml: remove its supportsPictureInPicture capability only. This plan and the existing readiness knowledge receive the observed evidence after validation. No unrelated activity flags or model code changes.

## 11. Validation
No new unit test mirroring source deletion: picture-in-picture overlap is a platform/OEM product outcome, so actual UI/window evidence is the meaningful oracle. Run the required complete unit suite and assembly. Preserve existing disclosure and readiness tests. Source review and confirming rerun precede assembly/device pass. Removal control: reverting this fix restores the observed covering window. Not tested by unit tests: native PiP positioning, real Android permission prompt or OEM settings. These require device evidence.

Enumerate and check each existing entry route: onboarding, Settings permissions, and any readiness/notice shortcut that opens this activity. For each, verify disclosure before first agreement, Not now returns without opening Settings, and Agree opens native Settings unobstructed. Record unavailable routes as UNCHECKED. Include re-entry while the existing singleTask guide remains alive; record whether it resumes the agreed guide or presents disclosure. Source search finds one activity launcher in SettingsActivity; its shared PermissionActions callback supplies these consumers.

Check background/return and activity recreation both before agreement and after agreement. Separately check denied, granted-but-unbound, and granted-and-bound states: only the last closes automatically. Exercise Settings launch failure and verify usable retry/return controls. Record launch-failure recreation behavior explicitly; current source saves agreed, but not launchFailed.

Hardware: fresh S26 consent flow via wispr-eyes, Play-only delivery; decline stays in app, agree opens native Settings with no guide overlay, native Allow remains exposed, grant+bound returns to usable onboarding; actual silent virtual-input dictation reaches a real editor. No microphone-acoustics claim. Record version/source, tree/screenshot, target/editor text and restore temporary test choices. Reinstall/reset app is authorized; saved words and local logs backed up first. Do not wipe phone or submit production.

## 12. Blast radius/rollback
Only the shared permission-guide presentation and its declared capability. Revert the activity/manifest change together through reviewed Git commits; existing consent and permission state remains. No data migration or signing change.

## 13. Ship criteria
- [ ] Native approval and denial controls are unobstructed on the S26.
- [ ] Agree, decline, launch failure and recreation preserve their contracts.
- [ ] Permission+binding correctly advances setup and first editor insertion succeeds.
- [ ] Internal Play delivery verified; public production remains inactive.

## 14. Open questions
No new product/spend decision. The prior consumer device-support decision is unchanged. Other consumer readiness gaps remain separately tracked and must not be declared completed by this fix.

## 15. Related
Consumer-readiness work; #10 Play setup; #406 continuation; previous #413 cleaning. Source 4c1c6416, installed internal Play257. No consumer rollout authorized.

## Consumer baseline update
Saurabh completed onboarding himself and said it looks good. A live phone check afterward found the main History screen, accessibility granted and bound, and his first saved dictation. Control plus screen read took 1.67 seconds. This proves setup completion and a History result, not independent editor insertion or the proposed obstruction fix.

Grounded review: PROCEED-AS-PLANNED, 2026-10-03; guide-grounded.txt. Gate 2 implementation proceeds under the existing explicit pre-release mandate, not a claim of separate artifact approval.
