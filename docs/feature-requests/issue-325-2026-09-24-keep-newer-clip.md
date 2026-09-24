# Issue #325: the direct clipboard fallback and a newer clip (REF-01, regrade 6) (2026-09-24)

GitHub issue: `#325`. Tier: SMALL if the finding holds, else a documented rejection. Status: closed as not feasible as written after the coverage round (`325-cov`); documentation only.

## Preface: Lane + Hardware UAT declaration

**Lane:** Code

**PAR rows closed:** none.

**Hardware UAT:** none unless the coverage round finds a platform path that needs measuring.

## Preface: User Rubric

Persona: the founder dictating on his S26. The rule `code-gotchas.md` RULE: never-clobber-a-clipboard-you-do-not-own says that a user who copied something between the dictation and the insertion keeps that clip. The audit asks the owner's direct fallback (`SessionFinalizer.keepOnClipboard`, a non-scheduled handoff: no pinned editor, the service not running, no answer) to compare the clipboard with a fingerprint taken at admission. It also asks the fallback not to copy when the clipboard changed or could not be read.

## 0. TL;DR

The fix the audit proposes cannot tell "changed" from "unreadable". Android 10 and later refuse `primaryClip` to any app that is not in focus and is not the default keyboard. This was measured on the S26 and on the emulator on 2026-09-13 (`ClipboardService: Denying clipboard access to com.envi.wispr`; `paste/AccessibilityInsertionRunner.kt` `clipboardOwner`, and `device-testing.md` chunk 1). The session owner runs in the Service while another app's editor, or the launcher, has focus. So both reads are almost always null. The same limit applies to `hasPrimaryClip`, the clip description and clip-changed listener dispatch, which `ClipboardService` checks against the same read permission.

Result: "unreadable, so do not copy" would remove the transcript from the clipboard on nearly every fallback. That is the destination the user was told to paste from, and it is the designed destination for the tile and the app's own button (`NO_PINNED_TARGET`, `code-gotchas.md` "Announce a fallback only where auto-paste was EXPECTED to work"). Since #288 the words are kept on the phone and recovered into History whatever the clipboard does. So the clipboard copy is the words' fast path, not their only copy.

Proposed outcome (for the coverage round to confirm or break):

1. Keep the direct fallback's copy. It is the take's first write of its own words, never a restore. The rule governs a restore or a second write of a clip we wrote. A copy that cannot see the clipboard cannot tell a newer clip from an old one.
2. Make the platform limit explicit where the next reader looks: a comment at `keepOnClipboard` naming the rule and the measured read denial, and a sentence in the rule itself that scopes it to restores and to writes over our own earlier clip.
3. Close REF-01 as not feasible as written, with this evidence, unless the coverage round names a platform path that reads or observes a newer clip from a background Service, or an accessibility service, on Android 13 to 16.

## 1. Questions for the coverage round

- Is there any API on Android 13 to 16 through which a background Service, or a bound accessibility service without focus, can learn that the primary clip changed since a given moment? Candidates: listener dispatch, clipboard access for a `TYPE_ACCESSIBILITY_OVERLAY` window, the Android 13 clipboard overlay's accessibility window events.
- Is there any path where the direct fallback runs with our app in focus (the app's own dictate button, onboarding practice), and would a readable-only comparison be worth its code there?
- Does the proposed rule wording weaken the rule for the insertion runner's restore path? It must not.

## Results (2026-09-24)

- Coverage round (`325-cov`): REF-01 is not feasible as written. AOSP `ClipboardService` checks the read permission before each listener notification and before `primaryClip`, its description and `hasPrimaryClip`. It exempts the focused app's uid and some privileged roles, never an accessibility service. The recorder overlay is `FLAG_NOT_FOCUSABLE`, and the Android 13 clipboard confirmation is no dependable signal. The own-app direct fallback (the top-bar microphone) is the only case where both reads could succeed, which does not justify a separate path.
- Adopted: keep the direct fallback copy; a comment at `SessionFinalizer.keepOnClipboard` states the limit; `code-gotchas.md` RULE: never-clobber-a-clipboard-you-do-not-own is scoped to restoring or rewriting a clip this take wrote, and says an unreadable clip is never permission to restore or rewrite. The insertion runner's restore guard is unchanged.
- No behaviour changes, so there are no mutations and no device run.
