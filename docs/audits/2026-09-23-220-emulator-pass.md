# #220 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build and test APK of the #220 branch. Instrumentation ran through `adb shell am instrument`, never `connectedDebugAndroidTest`, which uninstalls the app and its models. Takes ran through `scripts/uat/wispr_eyes.py`.

| Check (plan §11.5, §11.1, §13) | Result |
|---|---|
| `CaptureBindingDeviceTest`: legacy bind held open, two take binds | VERIFIED: the actionless bind gets `IAudioCaptureService`; each take bind gets `IAudioTakeService`; the two take binders are distinct |
| Its revert: `setIdentifier` removed from `takeBindIntent`, both APKs rebuilt | RED: "the second take bind got the first take's binder"; restored and rebuilt, OK again. Android does hand the cached binder to a same-identity bind while the service lives |
| Legacy device test `CaptureWithSilenceStopDeviceTest` over the legacy binder | 5 of 7 VERIFIED. The 2 that play speech through the device speaker into the microphone fail: the emulator has no acoustic path from its speaker to its microphone (host microphone off). NOT VERIFIED here; a phone pass covers them |
| Two back-to-back dictations into Gmail through the take interface | VERIFIED: both takes ran live, 45 characters back from the speech engine each time, route COMMIT, the app's outcome line VERIFIED both times, and the editor held both sentences |
| First pair of takes, right after the reinstall | Take 1's outcome read UNVERIFIED after 18 attempts while its words landed: the reinstall had unbound auto-paste and the harness rebound it just before the take. The second pair, with the service bound, was VERIFIED both times |
| Harness expectation on take 2 | The expected text used a no-break space between the sentences; the app joins with a normal space, which is its insertion behaviour, not a #220 change |
| Restore | `restore()` answered "nothing was changed" after each run |

NOT VERIFIED rows are not counted as passes. The founder's phone pass is queued with the earlier audit items.
