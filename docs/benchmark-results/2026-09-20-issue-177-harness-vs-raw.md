# Harness versus raw adb on the emulator, #177

Run 2026-09-20 09:59 on emulator-5554 (EnviousWispr_Android_16_Play), raw scripts from `HEAD`.
Wall time is first command to reported answer; commands are adb + grpcurl processes; correct is an
independent read (a separate `uiautomator dump`, gRPC `getMicrophoneState`, the log); dirty is what was
left behind before the benchmark itself cleaned up.

| Errand | Way | Seconds | Commands | Output chars | Correct | Dirty | Note |
|---|---|---|---|---|---|---|---|
| unlock | raw | 3.1 | 10 | 12 | False | clean | still locked |
| unlock | harness | 9.6 | 40 | 8 | True | clean | unlocked |
| read switch | raw | 1.9 | 4 | 4 | True | clean | True |
| read switch | harness | 2.0 | 4 | 4 | True | clean | True |
| flip and back | raw | 8.0 | 16 | 4 | True | clean | True |
| flip and back | harness | 39.7 | 92 | 4 | True | clean | True |
| spoken take | raw | 16.3 | 8 | 332 | True | microphone | {} [bench] inject exit=0 [bench] --- result --- 09-20 09:57:34.075 12329 18728 I |
| spoken take | harness | 22.3 | 68 | 180 | True | clean | OK bench: the same editor now holds the sentence; it ends '… tomorrow. And I wil |
| audio-free insert | raw | 4.2 | 4 | 349 | True | clean | [bench] --- outcome --- 09-20 09:58:38.788 11140 11140 I PasteService: insertion |
| audio-free insert | harness | 8.1 | 24 | 177 | True | clean | OK bench: the same editor now holds the text; it ends '… quarterly report is rea |

**raw:** 4/5 correct, 1 dirty, 33.5 s total, 42 commands, 701 output chars (about 175 tokens).

**harness:** 5/5 correct, 0 dirty, 81.7 s total, 228 commands, 373 output chars (about 93 tokens).
