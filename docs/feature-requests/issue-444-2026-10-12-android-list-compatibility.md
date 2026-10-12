# Android 13 and 14 number cleanup compatibility

Issue #444. SMALL. Authorized by the Android launch request.

Dr. Elena Vasquez needs dictated numbers to reach her editor without a crash on a supported phone. Existing number-formatting behavior must stay unchanged.

Google Play flags the mutable list removal in number cleanup. Android official guidance recommends indexed removal for apps compiled against Android 15+ and running on Android 14 or earlier: https://developer.android.com/about/versions/15/behavior-changes-15#kotlin-remove-first

Upstream: recognizer text passes through existing deterministic cleanup. Only the guarded trailing-and branch calls the new List API.
Downstream: number cleanup, finalized text, history and insertion consume the same formatted result; no consumer needs a behavior change.
State: pure text transformation. Replace one removal operation, preserving its existing nonempty precondition and output.
Terms: compatibility means no call to the Android-15-only List removal method, not new speech-quality or all-phone acceptance.

Sweep: production has this mutable-list removeLast call and one java.util.ArrayDeque.removeFirst call in emoji restoration. The deque API already exists on supported Android versions and stays unchanged. Test-only deque calls do not ship. No new manager, guard, test harness or feature.

Validation: existing cleanup and full required unit suites; inspect actual compiled call target; build once after review; Play pipeline builds/tests/signs the replacement; Google reruns the actual production submission scan. Existing JVM tests alone cannot reproduce a missing Android runtime method. No optional test hardening added. No parity row claimed.
