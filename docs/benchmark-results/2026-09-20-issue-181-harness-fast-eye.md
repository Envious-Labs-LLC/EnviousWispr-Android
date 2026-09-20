# Harness versus raw adb on the emulator, #177

Run 2026-09-20 11:34 on emulator-5554 (EnviousWispr_Android_16_Play), raw scripts from `0622e6fe3ef54aa706a61cd972ecd13fa2578bd1^`.
Wall time is first command to reported answer; commands are adb + grpcurl processes; correct is an
independent read (a separate `uiautomator dump`, gRPC `getMicrophoneState`, the log); dirty is what was
left behind before the benchmark itself cleaned up.

| Errand | Way | Seconds | Commands | Output chars | Correct | Dirty | Note |
|---|---|---|---|---|---|---|---|
| unlock | raw | 2.5 | 10 | 12 | False | clean | still locked |
| unlock | harness | 4.7 | 34 | 8 | True | clean | unlocked |
| read switch | raw | 2.1 | 4 | 5 | True | clean | False |
| read switch | harness | 0.1 | 2 | 5 | True | clean | False |
| flip and back | raw | 8.1 | 16 | 4 | True | clean | True |
| flip and back | harness | 4.3 | 54 | 4 | True | clean | True |
| spoken take | raw | 15.7 | 8 | 130 | False | clean | {} [bench] inject exit=0 [bench] --- result --- 09-20 11:32:50.639 28423 6766 I  |
| spoken take | harness | 15.7 | 54 | 132 | True | clean | OK bench: the same editor now holds the sentence; it ends 'And I will send the d |
| audio-free insert | raw | 4.2 | 4 | 349 | True | clean | [bench] --- outcome --- 09-20 11:33:38.901 25639 25639 I PasteService: insertion |
| audio-free insert | harness | 4.6 | 20 | 135 | True | clean | OK bench: the same editor now holds the text; it ends 'The quarterly report is r |

**raw:** 3/5 correct, 0 dirty, 32.6 s total, 42 commands, 500 output chars (about 125 tokens).

**harness:** 5/5 correct, 0 dirty, 29.4 s total, 164 commands, 284 output chars (about 71 tokens).
