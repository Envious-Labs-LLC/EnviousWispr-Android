# #240 emulator pass, 2026-09-23

Device: `emulator-5554`, debug build of the #240 branch after the schema landed, driven through `scripts/uat/wispr_eyes.py` (`dictate_emulator`).

| Check | Result |
|---|---|
| Two back-to-back Gmail dictations | VERIFIED: both takes by COMMIT, the app's outcome VERIFIED, the editor's whole text equal to one sentence and then to both |
| What Sentry receives | NOT RUN on the device: the seam is a pure rewrite of the SDK's event and breadcrumb types, driven on the JVM by `SentrySchemaTest` (17 rows) |
| Native crash symbolication after demangled names are redacted | NOT VERIFIED: needs a pinned native crash payload |
