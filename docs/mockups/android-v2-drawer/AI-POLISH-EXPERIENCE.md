# AI Polish Android experience

## The page's one job

Let someone turn AI Polish on, choose where it runs, understand the privacy consequence, and complete only the setup their choice needs.

## Main screen

- Keep the existing hamburger, microphone action, and four-tab bottom navigation.
- Start with one master `AI Polish` switch.
- When the switch is on, show `WHERE POLISH RUNS` with two single-choice cards:
  - `On this phone`
  - `Your provider`
- Use the rainbow processing route only inside the selected card. It communicates where text goes rather than decorating the page.
- Keep the fallback message visible: `If polish cannot finish. Your cleaned transcript is still inserted.`

## On this phone

- This is the default.
- Show `S1-mini`, a visible `Ready` state, and `Text stays on this phone`.
- `Manage model` opens model management without changing the active mode.
- If the model is missing, replace `Ready` with `Model needed` and show `Download model`.
- Show download progress, Pause, Resume, Retry, Repair, Update, and Remove only when the existing model state requires them.
- Do not activate the local mode until the model is ready.

## Your provider

### Choose

- Tapping `Your provider` opens a native Material 3 bottom sheet.
- List OpenAI, Gemini, Claude, and Self-hosted as plain radio rows.
- Each row says whether it uses an API key or an endpoint.
- Tapping a row opens its focused setup subpage. No extra Apply step is needed in the picker.

### Set up

- Use a full-screen subpage with a back arrow and no bottom navigation.
- Show only fields required by the selected provider:
  - OpenAI, Gemini, Claude: Model ID and API key.
  - Self-hosted: Model ID, Server URL, and protocol.
- Keep the privacy disclosure next to the fields and above the Save action.
- Keep `Save provider` pinned above the system navigation bar and clear of the keyboard.
- Back discards an unsaved credential draft.
- Never save an API-key draft in screen state, logs, Room, DataStore, or an intent.
- A new setup never shows `Remove saved provider and key`.
- Editing an existing provider shows that action beneath the privacy disclosure.

### Saved

- Return to the main screen after Save.
- Show the provider, model ID, and `Configured`.
- Say "Configured", not "Connected", because saving validates the configuration but does not prove a live network connection.
- Show `Text is sent using your key` or the self-hosted endpoint equivalent.
- `Edit provider` returns to the focused setup subpage.

## Off

- Turning the master switch off selects the existing `OFF` mode.
- Collapse the engine choices.
- Show one quiet line: `AI Polish is off. Basic cleanup still runs.`
- Preserve the saved provider configuration so turning polish off does not silently delete credentials.

## States and feedback

- Save success: return to the main screen and show a short snackbar, for example `OpenAI saved`.
- Invalid model ID, API key, endpoint, or protocol: show the existing specific validation message under the relevant field.
- Loading: show `Checking polish settings` without blocking the rest of the app.
- Save failure: keep the entered non-secret values and show `Could not update AI Polish settings`.
- Color never carries readiness alone. Pair green with `Ready` or `Configured`.

## Native Android behavior

- Minimum touch target: 48 dp.
- Choice cards expose radio semantics to TalkBack.
- The provider picker is a modal bottom sheet with a drag handle and system back support.
- The setup page uses IME padding and a sticky Save action that remains above the keyboard.
- Use a short content-size animation when the master switch changes. Respect reduced-motion settings.
- Keep cards at 16 dp, buttons at 12 dp, and use fully rounded shapes only for pills and switches.

## Mockups

- `04-ai-polish-on-device.png`
- `04-ai-polish-provider-picker.png`
- `04-ai-polish-provider-setup.png`
- `04-ai-polish-provider-active.png`

