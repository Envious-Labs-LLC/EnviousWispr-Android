# The #115 emulator scenes

Drivers for the wedge, kill, force-stop and ten-minute-cap scenes, through `scripts/uat/wispr_eyes.py`
(`freeze_process`, `kill_process`, `stop_app`, `dictate_emulator`). Emulator only.

```bash
WISPR_SERIAL=emulator-5554 python3 scripts/uat/scenes-115/wedge_scenes.py scripts/uat a <label>   # a, b, c or d
WISPR_SERIAL=emulator-5554 python3 scripts/uat/scenes-115/force_stop_scene.py scripts/uat <label>  # scene e
WISPR_SERIAL=emulator-5554 python3 scripts/uat/scenes-115/cap_scene.py scripts/uat <label>         # scene g, eleven minutes, host IDLE
```

Each writes `115-scene-*.txt` into `SCENE_OUT` (default: the current directory). The oracle for a wedge is
the OWNER's log (a terminal line after the freeze), never the capture process's own lines, which a frozen
process cannot write. Scene g needs an idle host: a Gradle run stalled the emulator for 11 s on 2026-09-21
and the bound fired on that stall (`docs/audits/2026-09-21-115-emulator-pass/`).
