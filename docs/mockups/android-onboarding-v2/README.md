# Onboarding v2: the floating lips

Open `index.html` directly. All CSS, JavaScript, SVG and the v1 Accessibility guide are inline. The two Plus Jakarta Sans faces sit beside it, with a system sans-serif fallback. The phone starts at 3×; choose **Fit window** for a full-height walkthrough.

## Flow in ten lines

1. Welcome is unchanged from v1.
2. Downloads are unchanged from v1.
3. Permissions introduces the lips beside an illustrated text field.
4. Microphone lets EnviousWispr hear your voice.
5. Accessibility finds your text box, shows the lips and pastes your words.
6. The existing disclosure and Samsung guide lead to the service switch; Notifications stays optional.
7. Practice A focuses a real editable mock field with the keyboard up: tap, speak, check, words land.
8. The first success enables Finish setup and encourages a second take using press and hold.
9. Practice B finishes on release; success explains when and where the lips appear in other apps.
10. Finish setup or Skip practice opens the app directly, with no completion screen.

## Exact copy

Semicolons separate independent labels or variants within a row. The keyboard and Android Settings text are simulated destination UI, not additional app permissions. The sample transcript stands in for the user’s own speech.

| Screen | Element | Exact text |
| --- | --- | --- |
| Welcome | title | Welcome |
| Welcome / Downloads | placeholder | unchanged from v1 |
| Welcome | action | Get Started! |
| Downloads | title | Downloads |
| Downloads | placeholder action | Continue |
| Permissions | title | How it works |
| Permissions | introduction | Tap the lips to dictate. Hold them to talk. They appear beside any text box. |
| Permissions | illustrated field | Write a message… |
| Permissions | permissions introduction | Allow EnviousWispr to hear your words and put the polished text where you need it. |
| Permissions | card title | Microphone |
| Permissions | card copy | To hear your voice for transcription. |
| Permissions | card title | Accessibility |
| Permissions | card copy | To find your text box, show the floating lips button beside it, and paste your words after you start a dictation. |
| Permissions | card title | Notifications |
| Permissions | card copy | Recording controls in your notification panel. |
| Permissions | optional note | Notifications are optional. You can enable them later. |
| Permissions | grant action | Grant |
| Permissions | granted state | Granted |
| Permissions | action | Try dictation |
| Permissions | required note | Enable Microphone and Accessibility to try dictation. |
| Practice A | idle title | Try your first dictation. |
| Practice A | prompt | Tap the lips and say… |
| Practice A | sample | “Um, tell Grandma, uh, I’ll call her on Sunday.” |
| Practice A | recording title | Go ahead. We’re listening. |
| Practice A | recording instruction | Speak naturally. Tap the check when you’re done. |
| Practice B | idle title | Try holding the lips. |
| Practice B | prompt | Now hold the lips and talk; let go when you are done. |
| Practice B | sample | “And, um, let her know I miss her.” |
| Practice B | hold title | Keep holding. We’re listening. |
| Practice B | hold instruction | Let go when you’re done. |
| Practice B | short tap hint | For this try, keep holding the lips until you’ve finished speaking. |
| Practice A / B | processing title | Tidying your words… |
| Practice A / B | processing text | Your words will appear in the text box. |
| Practice A / B | success title | Nice, that worked! |
| Practice A | success copy | Your words are in the box. Try holding the lips next. |
| Practice B | success copy | You can tap to dictate or hold to talk. |
| Practice success | expectation | In any app, the lips appear when a text box is active, just above the keyboard at the edge you chose. |
| Practice A success | action | Try press and hold |
| Practice A / B | finish action | Finish setup |
| Practice A | skip action | Skip practice |
| Practice A / B | no speech title | No words were detected. |
| Practice A | retry instruction | Tap the lips and speak, then tap the check. |
| Practice B | retry instruction | Hold the lips and speak, then let go. |
| Practice A / B | interrupted title | Practice was interrupted. |
| Practice A / B | interrupted copy | Your text is saved. Try again when you’re ready. |
| Practice A / B | cancelled title | No new text was added. |
| Practice A / B | cancelled copy | Try again when you’re ready. |
| Practice A / B | field placeholder | Your words will appear here. |
| Practice A / B | field accessible name | Your practice text |
| Practice A | sample result | Tell Grandma I’ll call her on Sunday. |
| Practice B | sample result | And let her know I miss her. |
| Practice A / B | idle control accessible name | Tap the lips to dictate, or press and hold to talk |
| Practice A / B | processing accessible name | Working on your words |
| Practice A | cancel accessible name | Cancel recording |
| Practice A | finish accessible name | Finish recording |
| Practice B | hold accessible name | Hold recording, release to finish |
| Android prompt | microphone | Allow EnviousWispr to record audio? |
| Android prompt | microphone action | While using the app |
| Android prompt | notifications | Allow EnviousWispr to send you notifications? |
| Android prompt | action | Allow |
| Android prompt | deny action | Don’t allow |
| App destination | brand | EnviousWispr |
| App destination | tab | History |
| App destination | empty title | Your words will live here. |
| App destination | empty copy | Your dictations will appear here after you record. |
| App destination | tab | Dictionary |
| App destination | tab | Transcription |
| App destination | tab | AI Polish |
| Accessibility disclosure | headline | Let the lips float beside your text box |
| Accessibility disclosure | purpose and access | EnviousWispr finds the text box you’re using, shows the floating lips button beside it, and pastes your words after you start a dictation. It uses Android’s Accessibility service to read the field’s text, cursor position and app information, put your words in the right place, and check that they appeared. |
| Accessibility disclosure | privacy | This field information is processed on your phone. Field contents are not sent to Envious Labs. |
| Accessibility disclosure | user control | Insertion happens after you start a dictation. EnviousWispr does not operate your phone on its own. |
| Accessibility disclosure | revoke | You can turn this access off in Android Settings at any time. |
| Accessibility disclosure | action | Agree and open Settings |
| Accessibility disclosure | dismiss | Not now |
| Samsung guide | title | Setup guide |
| Samsung guide | frame 1 title / Settings title | Accessibility |
| Samsung guide | frame 1 caption | Tap Installed apps |
| Samsung guide / Settings | row | Interaction and dexterity |
| Samsung guide / Settings | row / title | Installed apps |
| Samsung guide | frame 2 caption | Choose EnviousWispr |
| Samsung guide / Settings | service name | EnviousWispr |
| Samsung guide | other row | Other installed apps |
| Samsung guide | frame 3 caption | Turn on the service |
| Samsung guide / Settings | switch state | Off |
| Samsung guide / Settings | shortcut row | EnviousWispr shortcut |
| Samsung guide | frame 4 title | Review Android’s prompt |
| Samsung guide | frame 4 caption | Review access, then Allow |
| Samsung guide / Settings prompt | question | Allow EnviousWispr access? |
| Samsung guide / Settings prompt | allow | Allow |
| Samsung guide | frame 5 caption | Then return to EnviousWispr |
| Samsung guide / Settings | switch state | On |
| Samsung guide | frame 5 note | Leave the shortcut off |
| Samsung guide | pause / resume | Pause / Play |
| Samsung guide | next | Next › |
| Samsung guide | counter | 1 / 5; 2 / 5; 3 / 5; 4 / 5; 5 / 5 |
| Samsung Settings | row | TalkBack |
| Samsung Settings | row | Vision enhancements |
| Samsung Settings | row | Hearing enhancements |
| Samsung Settings | instruction | Use the service switch above. You don’t need to enable the Accessibility shortcut. |
| Samsung Settings | return action | Return to EnviousWispr |
| Samsung Settings prompt | access summary | This access lets the app read screen content and interact with apps on your behalf. |
| Samsung Settings prompt | details | Review the full access details shown by Android on your phone. |
| Samsung Settings prompt | deny | Don’t allow |
| Permissions after Settings | enabled note | Accessibility is ready. The lips can appear beside your text box and paste your words. |
| Permissions after Settings | disabled note | Accessibility is still off. Tap Grant to try again; your other choices are saved. |
| Samsung guide | accessible names | Accessibility animation guide; Pause accessibility guide; Play accessibility guide; Next guide step |
| Samsung Settings | accessible names | Samsung Accessibility settings preview; Back in Settings; Enable EnviousWispr; Android access confirmation preview |
| Disclosure | accessible name | Accessibility setup |
| Persistent brand | accessible name | EnviousWispr |
| Recording | clock | 0:00, advancing once per second; selector snapshot: 0:07 |
| Recording | clock accessible name | Recording time |
| Phone chrome | status | 9:41; 100% |
| Keyboard illustration | keys | 1 2 3 4 5 6 7 8 9 0; q w e r t y u i o p; a s d f g h j k l; ⇧ z x c v b n m ⌫; ?123 , ☺ [space] . ↵ |
| External controls | title | Meet the floating lips. |
| External controls | brand | EnviousWispr / Android |
| External controls | screen selector | 1 Welcome; 2 Downloads; 3 Permissions; Accessibility disclosure; 4 Practice: tap; Practice: hold; Skip path |
| External controls | appearance | Appearance; Dark; Light |
| External controls | state label | Review a state |
| External controls | permission states | Normal flow; All permissions granted; Accessibility revoked; Accessibility disclosure; Samsung Settings guide |
| External controls | practice states | Practice A: Idle prompt; Practice A: Recording: tap pill; Practice A: Processing; Practice A: Success; Practice A: No speech; Practice A: Interrupted; Practice A: Cancelled; Practice B: Idle prompt; Practice B: Recording: hold pill; Practice B: Processing; Practice B: Success; Practice B: No speech; Practice B: Interrupted; Practice B: Cancelled |
| External controls | skip states | Skip practice; Skip: app opened |
| External controls | scale | Preview size; 3×; Fit window |
| External controls | outcome | Next take result; Sample words; No speech; Interrupted |
| External controls | reset | Restart walkthrough |
| External controls | simulation note | Interactive design prototype. Permissions, keyboard, recording and text insertion are simulated. No microphone or network is used. |
| External controls | interaction note | Tap the floating lips, or hold with a pointer or Space. Release to finish a hold. State selections stay still until you interact. At 3×, scroll to see the full phone. |
| External controls | skip note | Skip path: no successful take. Tap Skip practice to open the app directly. |
| External controls | skip state note | Tap Skip practice to open the app directly. |
| External controls | destination note | App destination after skipping. No completion screen. |
| External controls | processing snapshot note | Processing snapshot. Tap the working lips to show the simulated result. |
| External controls | Settings note | Interactive Samsung Settings and floating guide simulation. Tap the Settings rows to try the path. The animation demonstrates taps; it never grants access. Final video and native picture-in-picture need phone validation. |

## Design decisions

- Put “How it works” at the top of Permissions. The picture explains why Accessibility matters before the permission request, while preserving the four-screen sequence. It is an illustration here, not a live focus target before access is granted.
- Keep v1’s Plus Jakarta Sans, centered headings, purple dark ground `#251F37`, white light ground `#FFFFFF`, dark surface `#201B2C`, light surface `#F0ECF8`, dark action `#6B4FD1` and light action `#241432`. The rainbow lips are the visual anchor; no competitor branding is carried over.
- Keep the persistent top lips on every onboarding screen. Practice uses a compact header to leave space for the focused field, actions, floating recorder and keyboard. The Samsung destination preview retains a top brand mark as well as the existing floating guide.
- Keep Microphone on Permissions rather than moving it to first tap. The brief preserves the existing permission sequence. There is no Appear on top request.
- Use one field for both takes, preserving the first result when the second lands. The sample phrases extend v1’s Grandma example. Real practice accepts whatever the user says.
- Encourage hold with “Try press and hold” after A. Finish setup is already available, so leaving at this point skips B without another exit button or extra screen. During any active take or processing, finishing and skipping are temporarily disabled.
- A short tap during the hold lesson repeats the hold instruction without starting a tap take. This is a proposed practice-only teaching guard; outside onboarding, tap remains a normal recording gesture. It prevents a second tap take from being counted as hold practice.
- Retry directly from the lips after no speech, cancellation or interruption. Previously inserted text and A’s completion stay intact. The new take is never labelled successful until sample words land.
- Dock at the right edge, 4 dp above the keyboard. Smoke uses the exact supplied surface: 48 dp square, radius 14 dp, 38 dp lips inside a 56 dp target, `rgba(19,16,25,.64)` and `0 2px 5px rgba(19,16,25,.20)`. No outline or blur.
- Tap pill is 232 × 60 dp, with a 48 dp clock, 66 dp rail and two 40 dp controls. Hold pill is 168 × 60 dp with only a 134 dp rail. Keep the supplied `#2A2733` cancel and `#7C3AED` accept fills from `themes.html`. The brief’s named CSS is the visual authority for this mock.
- Processing rolls only the lips’ rainbow fills. Reduced motion freezes fills and rails, adds the supplied still indicator, and starts the Samsung guide paused; its Next and Play controls remain available.

## What is simulated

The page does not access the microphone, grant Android permissions, download models or create a native overlay. The keyboard is a drawn keyboard; the practice box is an editable HTML textarea with browser software-keyboard activation suppressed. Pointer taps and 500 ms holds simulate gesture recognition. The rail is a shifting illustrative sample history and the clock counts elapsed mock seconds. Processing lasts 1.4 seconds and inserts the selected fixed sample, or returns the selected no-speech/interrupted outcome. These are design timings, not latency claims.

Use **Next take result** before recording to reach errors through the actual mock interaction. Use the external screen/state selectors to inspect any A/B state directly; B snapshots seed a successful A, and processing snapshots remain visible until clicked. Recording snapshots freeze the clock at 0:07 while the rail animates. Press and release a hold snapshot to continue it. Browser window focus loss during recording shows interruption. Restart clears all simulated permissions and text.

The original `accessibility.js` interaction and `accessibility.css` are embedded, with only the disclosure and permission-return words updated. Clicking the illustrated service row and approving Android’s simulated prompt grants mock access; the guide animation itself never grants it. The guide has the same five frames, pause, next, back and return behavior as v1. Its colors represent Samsung Settings independently of the app theme.

Welcome and Downloads are expressly static placeholders because the v1 story videos are absent. The app destination is v1’s empty History preview, not a completion screen. Dragging, left docking, Drop to hide, appearance selection and real Android insertion are not simulated. The right-docked recorder demonstrates the supplied phone dimensions; no claim of live phone validation is made. The mechanism note below is the proposed app work needed to make practice use the real overlay.

## Built 2026-09-14 (founder approved the mock the same night)

The app follows this mock with these differences:

- **No "How it works" picture on the Permissions screen.** Founder, phone pass of build 121: the fake "Write a message…" box on a permissions page is "terrible UX", and it pushed the Notifications card off screen. Removed; the heading is back to "Your models are ready. Let's try them." The lips are introduced where they are real, beside the practice box. The Accessibility card and disclosure keep the new words.
- **The engines warm up during permissions.** Same phone pass: the first practice take paid the speech model's cold start and the polish model's first-ever load and read as a slow app. The speech and polish services are bound while the permissions and practice screens are on screen (`ui/EngineWarmUp.kt`).
- **Back goes one screen back, never out of setup.** Same phone pass: back used to jump to the welcome and a second back skipped setup into the app. Now practice goes back to permissions, permissions to the welcome, and at the welcome Android's own back closes the app. "Set up later" stays the one way to dismiss setup.

- **A tap during the hold lesson still records.** The mock's practice-only guard would have taught the overlay about lessons; instead the take runs and lands, and the screen says "That was a tap. Now hold the lips while you talk, and let go when you are done." Finish setup is earned either way.
- **No "Practice was interrupted" state.** The draft text is saved across the interruption, and the screen returns to the lesson prompt; there is no reliable signal that distinguishes an interruption from a cancel.
- **One extra state, "Your words are saved in History."**, for a take that finished but whose words did not reach the box (the insertion path's own fallback). The mock had no such state because it simulated insertion.
- **"No speech" and "Cancelled" are one state, "No words were added."** The session owner deletes the draft row of a silent, cancelled or failed take alike and publishes the same busy phase for all three, so the screen says the one thing true of each.

Mechanism: option (a) of `mechanism-adjudication.md`, as recommended. The practice box is the one field of the app's own that the accessibility service admits (`paste/OwnFieldAdmission.kt`), identified by its accessibility view id; a take from the lips pins it and inserts through the ordinary route, and the screen judges the take from the History row it wrote together with the box having changed (`ui/OnboardingPolicy.kt` `judgePracticeTake`), and follows takes only while the screen is in front. The gesture rides on the bubble's request token (`shortcuts/BubbleRequests.kt`), which is how the hold lesson tells a hold from a tap.
