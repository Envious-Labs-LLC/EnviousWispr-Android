# EnviousWispr Android navigation direction

This revision follows the live issue 47 information architecture and the current S26 Ultra build.

## Navigation decision

- There is no Home page. History is the landing page.
- The bottom bar contains exactly four labeled destinations: History, Dictionary, Transcription, and AI Polish.
- Keep the bottom labels visible. The icons for Transcription and AI Polish are not clear enough without words.
- Secondary links appear only in the hamburger drawer: What's New, Appearance, Microphone, Sounds, Clipboard, Permissions, and Open Source Licenses.
- The microphone action remains in the top right of each primary tab.
- A secondary settings page replaces the tab content and uses a back arrow. The bottom bar is hidden until the user returns.

## Recorder decision

The floating recorder overlays the lower conversation area above the message composer. It never sits between the composer and Gboard, never creates a keyboard row, and never changes the keyboard's size or spacing.

## Mockups

1. `01-history.png`
2. `01-history-collapsed.png`: every entry shows a two-line Final preview and metadata.
3. `01-history-expanded.png`: one selected entry expands in place to show full Final and Original text plus actions.
4. `02-dictionary.png`
5. `03-transcription.png`
6. `04-ai-polish.png`
7. `05-settings-drawer.png`
8. `06-floating-recorder.png`

## History interaction

- All cards start collapsed.
- A collapsed card shows at most two lines of the Final transcript, followed by the date, time, and engine.
- Tapping anywhere on the card expands it in place.
- Only one card stays expanded at a time.
- The expanded card shows the full Final transcript, full Original transcript, metadata, and Copy, Keep, and Delete actions.
- Tapping the card or upward chevron again collapses it.

## Prompt set

All images used the built-in image generator and the `ui-mockup` workflow, except the recorder placement correction, which used `precise-object-edit`. The live S26 screenshots were the authoritative navigation references. The first mockup set supplied the visual polish reference. Every primary-page prompt required the hamburger, top-right microphone action, exactly four labeled bottom tabs, no Home, no Models page, and no Settings tab. The drawer prompt kept only secondary text links. The recorder prompt required the original message composer and complete keyboard to remain unchanged.
