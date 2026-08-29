# EnviousWispr Android Parity Contract

## Reference

- macOS source: EnviousWispr `main` at `544dae8be7ff07e7c1d7a5c2656d353711aaeed2`
- Android source: the S26 Ultra proof of concept in this repository
- Product rule: parity means the same user outcome. A macOS-only mechanism must become the closest native Android mechanism, not a literal imitation.
- Runtime rule: recording, transcription, text finalization, and insertion are the heart. Optional features must fail open to the last successful text.
- Privacy rule: audio stays on the phone. Text reaches a cloud provider only when the user selects that provider and supplies their own key. Envious Labs receives metadata only.

The initial Android proof targets one offline speech engine, one offline polish model, a small custom-word list, clipboard publication, and accessibility paste. This foundation is not implemented parity. Every requirement below is part of the parity target unless it is explicitly described as a platform mapping.

## 1. Setup and app shell

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-001 | Guided first-run setup with saved progress | Full-screen Compose onboarding with resumable steps |
| PAR-002 | Explain and request microphone access | Android runtime permission step with live status |
| PAR-003 | Explain and enable system-wide text insertion | Accessibility setup with a deep link, live status, and explicit confirmation that the user's existing keyboard remains unchanged |
| PAR-004 | Download, verify, pause, resume, retry, and remove required models | Manifest-pinned WorkManager downloads with checksum verification |
| PAR-005 | Complete a real practice dictation before setup finishes | Editable practice field driven by the real mic, ASR, polish, and insertion path |
| PAR-006 | Open into a useful home with visible readiness and record action | Adaptive Material 3 home, not a launcher-only overlay |
| PAR-007 | See release changes and whether they are unread | In-app What's New screen and unread badge |
| PAR-008 | See version, open-source license, and third-party notices | About and licenses screen |
| PAR-009 | Check for updates using the platform's supported delivery path | Play update status and store deep link, with sideload fallback messaging |

## 2. Native Android dictation controls

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-010 | Start dictation from any app | Samsung side-button double-press launches the floating EnviousWispr recorder over the current editor |
| PAR-011 | Start dictation without switching keyboards | Transparent floating recorder with Gboard or the user's chosen keyboard left unchanged |
| PAR-012 | Start dictation from a hardware or system shortcut | Samsung side-button Open app mapping to the recorder as primary, plus a configurable Quick Settings tile as fallback |
| PAR-013 | Choose push-to-talk or toggle behavior | Native setting applied consistently to the side button, overlay, notification, and tile |
| PAR-014 | Lock a push-to-talk session hands-free | Explicit lock action with visual and haptic confirmation |
| PAR-015 | Cancel immediately without transcribing | Cancel action on every active dictation surface |
| PAR-016 | Optionally recover an accidental cancel | Escape Recovery equivalent with a short restore offer and 24-hour History hold |
| PAR-017 | Add the currently selected word to vocabulary | Selection-aware Quick Add action with editable confirmation |
| PAR-018 | Know the current phase | Recording, transcribing, polishing, complete, advisory, and error states on every active surface |
| PAR-019 | Receive accessible non-color-only feedback | TalkBack labels, state announcements, large targets, and reduced-motion support |
| PAR-020 | Receive tactile confirmation without noise | Haptics for start, lock, stop, cancel, paste, and failure, respecting system settings |

## 3. Audio capture and recording behavior

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-021 | Choose Auto or a specific input microphone | Audio-device picker using Android communication-device APIs where supported |
| PAR-022 | Record reliably from built-in, wired, USB, Bluetooth, and headset inputs | Route resolution with clear fallback and route-change handling |
| PAR-023 | Reduce first-word clipping | Warm capture policy and finite pre-roll buffer |
| PAR-024 | Choose microphone readiness duration | Off, 10 seconds, 30 seconds, 60 seconds, and Always options with battery warning |
| PAR-025 | Automatically stop after silence | Neural VAD switch with adjustable pause duration |
| PAR-026 | Survive microphone removal or interruption | Salvage captured audio and mark the History item as interrupted |
| PAR-027 | Hear optional exact-once start and stop cues | Master switch, selectable sound pairs, safe preview, and audio-focus handling |
| PAR-028 | Understand Bluetooth cold-start behavior | Dismissible education card and permanent microphone guide |
| PAR-029 | Record up to the product's graceful long-dictation cap | Foreground microphone service, elapsed time, and explicit limit notice |

## 4. Offline transcription and language

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-030 | Choose between a fastest engine and a broad-language engine | Two offline ASR adapters behind one session coordinator |
| PAR-031 | See honest speed, language, size, and hardware tradeoffs | Selectable engine cards sourced from measured Android data |
| PAR-032 | Download, verify, repair, pause, resume, and remove each ASR model | Shared model-delivery layer with per-model state |
| PAR-033 | Transcribe while the user is still speaking | Optional streaming ASR for every capable engine |
| PAR-034 | Auto-detect language | Stored Auto language mode with observed-language feedback |
| PAR-035 | Lock dictation to one supported language | Searchable language picker, recents, native names, and an Auto escape route |
| PAR-036 | Keep a language choice when switching engines and disclose incompatibility | Capability-based language routing with an inline warning |
| PAR-037 | Reset dismissed language suggestions | Reset action for suggestion state |
| PAR-038 | Unload an idle model on the user's schedule | Never, immediately, 2, 5, 10, 15, and 60 minute policies |
| PAR-039 | Avoid simultaneous heavy-model residency | Independent processes and unload-before-swap orchestration |
| PAR-040 | Continue with raw transcription when an optional engine feature fails | Last-successful-text fallback on every limb failure |

## 5. Deterministic text cleanup

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-041 | Remove filler words when enabled | Deterministic filler-removal step with unchanged-text fallback |
| PAR-042 | Format spoken numbers, ordinals, money, dates, times, phone numbers, emails, and URLs | Locale-aware inverse text normalization before AI polish |
| PAR-043 | Convert explicit spoken emoji commands | Toggleable phrase-to-emoji formatter and post-polish restoration |
| PAR-044 | Convert explicit spoken punctuation commands | Separate opt-in punctuation command toggle |
| PAR-045 | Preserve dictated meaning and never silently drop text | Output validation and fallback to the last successful stage |
| PAR-046 | Protect against hallucinated or structurally unsafe polish output | Safety classifier, retry policy, and raw-text fallback |

## 6. Your Words and vocabulary

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-047 | Enable or disable custom-word correction | Master switch applied per recording |
| PAR-048 | Add, edit, delete, search, and bulk-delete terms | Structured local vocabulary database and Compose editor |
| PAR-049 | Store exact spelling, aliases, category, priority, force-replace, and case sensitivity | Full custom-term schema, not a string-only list |
| PAR-050 | Suggest useful spoken aliases | Offline suggestion service with review before saving |
| PAR-051 | Show usage frequency | Per-term use count in the term list |
| PAR-052 | Import names from Contacts with preview and confirmation | Android Contacts permission, add-only preview, re-scan, remove-imported, and optional launch sync |
| PAR-053 | Import vocabulary from files, pasted text, and supported rival exports | One import flow with source detection, limits, collision review, and atomic commit |
| PAR-054 | Export user-authored terms for backup and migration | Share-sheet export using the versioned transfer format |
| PAR-055 | Install, inspect, search, enable, and disable vocabulary packs | Pack catalog and detail sheets with spoken variants |
| PAR-056 | Add a selected word from another app | Quick Add ranking, search, confirmation, clipboard fallback, and clipboard restoration |
| PAR-057 | Keep concurrent vocabulary writes safe | Transactional database mutations and cross-process invalidation |

## 7. AI Polish

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-058 | Turn AI Polish off without disabling dictation | Master switch with raw/deterministic fallback |
| PAR-059 | Use a first-party offline polish model | S1-mini initially, branded and versioned as the Android first-party local option |
| PAR-060 | Download, verify, pause, resume, retry, update, and remove the offline model | Shared model-delivery state and integrity checks |
| PAR-061 | Use OpenAI, Gemini, or Claude with a personal key | Encrypted Android Keystore-backed key setup, validation, model discovery, refresh, and clear |
| PAR-062 | Use a self-hosted OpenAI-compatible or Ollama endpoint | Endpoint, model, health, and privacy-aware setup |
| PAR-063 | Select among discovered models with sensible recommendations | Provider-specific model picker with selected-model persistence |
| PAR-064 | See whether the selected provider is ready and why it is not | Provider rail or adaptive list with actionable status |
| PAR-065 | Send active-app context, surrounding text, custom vocabulary, and formatting intent when allowed | Per-provider prompt planner with bounded context and privacy disclosure |
| PAR-066 | Keep polish from blocking the dictation heart | Isolated process, timeout, cancellation, crash recovery, and unchanged-text fallback |
| PAR-067 | See original and polished text in History | Both versions stored with provider and model metadata |

## 8. Live preview and active overlay

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-068 | Optionally see words while speaking | Live partial transcript in the floating recording overlay |
| PAR-069 | Choose a preview engine where more than one is available | Capability-backed engine selector |
| PAR-070 | Manage optional preview language packs | Searchable installed/available catalog with download state |
| PAR-071 | Choose compact, level-rail, or words-visible active designs | Three polished responsive overlay presentations |
| PAR-072 | Place the floating overlay at the top or bottom | Persisted position plus drag-safe bounds |
| PAR-073 | Show an honest live level history | Sampled rolling audio history, not decorative random animation |
| PAR-074 | Stay usable in portrait, landscape, split screen, and with the existing keyboard visible | Window-inset, fold, cutout, and configuration-change handling |

## 9. Clipboard and insertion

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-075 | Copy completed text automatically when enabled | Auto-copy toggle |
| PAR-076 | Insert directly into the current editor | Accessibility target tracking plus bounded retries after the floating recorder closes |
| PAR-077 | Paste into an editor without changing keyboards | `ACTION_PASTE`, selection-preserving `ACTION_SET_TEXT`, and copy-only fallbacks with eligibility checks |
| PAR-078 | Preserve and restore the previous clipboard | Guarded snapshot with change detection before restoration |
| PAR-079 | Match spacing, capitalization, trailing space, and seam words to cursor context | Smart insertion using bounded Accessibility node text and selection, with sensitive-field and unsafe-context fallback |
| PAR-080 | Keep the target field stable from recording start through insertion | Editor identity snapshot and guarded retargeting |
| PAR-081 | Tell the user when insertion cannot happen | Copy-only success state with a clear action, never silent failure |

## 10. History and recovery

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-082 | Persist and search transcript history | Room database, full-text search, stats, and adaptive list/detail layout |
| PAR-083 | Copy, paste, keep, or delete one transcript | Detail actions with current permission and target checks |
| PAR-084 | Delete all transcripts with an exact confirmation count | Destructive confirmation sourced from the database snapshot |
| PAR-085 | Distinguish polished, recovered, interrupted, and engine states | Text-plus-icon metadata chips |
| PAR-086 | Recover audio after an app or process crash | Encrypted recovery spool, replay on next launch, delete on success |
| PAR-087 | Salvage a take when the ASR process dies | Rebind, reload, and retry from the captured audio once |
| PAR-088 | Keep Escape Recovery text temporarily or permanently | 24-hour expiry while the app runs, Keep action, and restore metrics |

## 11. Diagnostics, privacy, and reliability

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-089 | See microphone, insertion, model, provider, and storage readiness | Diagnostics screen with copyable report and no secrets or dictated content |
| PAR-090 | Enable local debug logging deliberately | Debug switch, log-level choice, bounded rotation, and share action |
| PAR-091 | Keep optional dictation audio only when explicitly enabled | Debug-only local archive switch with clear retention controls |
| PAR-092 | Understand what leaves the device | Per-provider privacy copy and a central privacy screen |
| PAR-093 | Send metadata-only telemetry when enabled by product policy | Content-free events with stable session and failure identities |
| PAR-094 | Avoid background battery drain | No polling or model residency at rest, bounded foreground services, WorkManager constraints |
| PAR-095 | Recover from partial model downloads and integrity failures | Atomic admission, checksum validation, quarantine, and retry |
| PAR-096 | Remain functional when any optional feature fails | Heart-and-limbs failure isolation across every feature above |

## 12. Material 3 Expressive quality bar

| ID | Required user outcome | Android expression |
|---|---|---|
| PAR-097 | Feel native to the user's Galaxy theme | Dynamic color with an Envious purple fallback and high-contrast support |
| PAR-098 | Use the whole display safely | Edge-to-edge layouts with status, navigation, IME, and cutout insets |
| PAR-099 | Navigate the full product without a desktop-style settings dump | Adaptive navigation suite, focused screens, search, and clear hierarchy |
| PAR-100 | Understand tap, press, loading, success, and failure instantly | Expressive motion, shapes, state layers, progress, haptics, and semantic color |
| PAR-101 | Retain state through rotation and process recreation | Saved state plus repository-backed settings and workflows |
| PAR-102 | Work with TalkBack, large text, switch access, and reduced motion | Semantics, focus order, scalable layouts, and motion alternatives |
| PAR-103 | Avoid visual defects across portrait and landscape | Screenshot and physical-device review at supported font and display sizes |

## 13. Release proof

Parity is proven only when all of these receipts exist together:

1. Every requirement above is implemented or mapped to a documented Android-native equivalent.
2. Automated tests cover deterministic transformations, persistence, routing, lifecycle, and failure fallbacks.
3. A physical-phone run crosses the real microphone, shipped ASR model, selected polish model, and a real target editor.
4. Insertion is proven in representative plain-text, rich-text, browser, terminal, chat, and unsupported fields.
5. Portrait, landscape, split screen, dark theme, dynamic color, large text, and TalkBack passes have no unresolved visual or interaction defects.
6. Battery and memory are measured for idle, repeated short dictation, and a long dictation. No service, wake lock, model, or polling loop remains active without a user-visible reason.
7. The critic pass reports no missing feature, unsafe fallback, stale target insertion, or dated/unpolished surface.
