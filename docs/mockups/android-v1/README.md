# EnviousWispr Android visual direction

This first-pass mockup set translates the actual EnviousWispr macOS 2.4.6 design language into native Android layouts.

## Visual direction

- Near-black violet backgrounds with layered eggplant surfaces.
- Envious purple for primary actions and selection.
- The rainbow waveform is a functional signature, used for recording, voice level, and identity rather than decoration.
- Mint green is reserved for ready and successful states.
- Large, friendly headers with calm explanatory copy.
- 16 dp cards, 12 dp buttons, and fully rounded shapes only for controls that behave like pills.
- Android navigation, touch targets, status bars, and one-handed hierarchy. The desktop sidebar is not carried over.

## Screens

1. `01-home.png`: the ready state and primary dictation action.
2. `02-floating-recorder.png`: the recorder over another app with the keyboard visible.
3. `03-history.png`: recent dictations, search, copy, and keep.
4. `04-your-words.png`: vocabulary management and categories.
5. `05-models-and-ai.png`: local speech, local polish, and optional cloud providers.
6. `06-settings.png`: grouped Android settings with clear readiness states.

## Generation prompt set

All six images used the `ui-mockup` workflow and the built-in image generator. Each prompt requested a high-fidelity, shippable Android screen based on inspected macOS screenshots, with dark Material 3 foundations customized to EnviousWispr. Shared constraints were: practical Android touch targets, no macOS window chrome or desktop sidebar, no iOS patterns, no generic sample-app styling, restrained rainbow use, and legible product copy.

The page-specific prompts were:

- Home: title and privacy subtitle, central microphone hero, readiness chips, local model status, and five-item bottom navigation.
- Floating recorder: compact Level Rail overlay above a generic keyboard, timer, live rainbow meter, transcript preview, Cancel, and Finish.
- History: privacy subtitle, search, count/filter controls, recent dictation cards, copy actions, and a kept state.
- Your Words: enable control, Add and Import actions, search, category chips, and editable vocabulary rows.
- Models and AI: Parakeet v3 and S1-mini local model cards with ready states, plus an optional cloud-provider row.
- Settings: Appearance, System-wide dictation, and Product groups with native rows, switches, status labels, and chevrons.
