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
5. `02-dictionary-compact.png`: recommended dense list with a compact labeled Add pill.
6. `02-dictionary-fab-alternative.png`: alternate layout using a floating plus button.
7. `02-dictionary-import-screen.png`: dedicated full-screen import source picker with large cards.
8. `03-transcription.png`
9. `04-ai-polish.png`
10. `04-ai-polish-on-device.png`: redesigned main screen with local polish active.
11. `04-ai-polish-provider-picker.png`: native provider-picker bottom sheet.
12. `04-ai-polish-provider-setup.png`: focused new-provider setup page.
13. `04-ai-polish-provider-active.png`: configured-provider state after Save.
14. `05-settings-drawer.png`
15. `06-floating-recorder.png`

## History interaction

- All cards start collapsed.
- A collapsed card shows at most two lines of the Final transcript, followed by the date, time, and engine.
- Tapping anywhere on the card expands it in place.
- Only one card stays expanded at a time.
- The expanded card shows the full Final transcript, full Original transcript, metadata, and Copy, Keep, and Delete actions.
- Tapping the card or upward chevron again collapses it.

## Dictionary interaction

- Remove the introductory description below the page title.
- Keep the vocabulary switch compact.
- Put the four management actions in one horizontal row, followed by the search field.
- Make search visually prominent with a filled surface, strong violet outline, large icon, and the label `Search your words`.
- Use one grouped list surface with compact rows and dividers instead of separate cards.
- Each row shows the spelling, alias count, and one overflow menu for Edit and Delete.
- Prefer the compact labeled `+ Add` pill. A floating plus competes with the persistent microphone action and can cover dictionary content.
- Import opens a dedicated subpage with a back arrow and no bottom navigation.
- Paste words and Open a file are large enabled source cards.
- From another app stays visible but disabled with a Coming soon label.
- Back is the only dismissal. The import screen does not repeat a Cancel button.

## AI Polish interaction

The complete behavior is specified in `AI-POLISH-EXPERIENCE.md`. The main screen asks whether polish is on and where it runs. Provider choice and credentials appear only after the user chooses the provider path. The selected engine owns the only rainbow processing route on the page.

## Prompt set

All images used the built-in image generator and the `ui-mockup` workflow, except the recorder placement correction, which used `precise-object-edit`. The live S26 screenshots were the authoritative navigation references. The first mockup set supplied the visual polish reference. Every primary-page prompt required the hamburger, top-right microphone action, exactly four labeled bottom tabs, no Home, no Models page, and no Settings tab. The drawer prompt kept only secondary text links. The recorder prompt required the original message composer and complete keyboard to remain unchanged.
