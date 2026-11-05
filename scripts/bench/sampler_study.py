#!/usr/bin/env python3
"""Validate the preregistered portable sampler study; never select a subset of its pairs.

Exit 0 screen/confirmation passed (manual semantic review still required), 10 no material gain,
11 environment not qualified, 12 independent quality failure, 2 malformed/incomplete evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
import re
import statistics
from collections import Counter
from pathlib import Path


class InvalidStudy(ValueError):
    pass


class QualityFailure(ValueError):
    pass


PILOT = [(1, (0, 1), "SML"), (2, (1, 0), "SML"), (3, (0, 1), "SML")]
CONFIRM = [
    (1, (0, 1), "MLS"), (2, (1, 0), "LM"), (3, (0, 1), "ML"),
    (4, (1, 0), "LM"), (5, (0, 1), "MLS"), (6, (1, 0), "LM"),
    (7, (0, 1), "ML"), (8, (1, 0), "LM"), (9, (0, 1), "ML"), (10, (1, 0), "LMS"),
]


def demand(condition: bool, message: str) -> None:
    if not condition:
        raise InvalidStudy(message)


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def integer(value, name: str) -> int:
    demand(type(value) is int, f"invalid integer: {name}")
    return value


def intent(case: dict, text: str) -> None:
    for pattern in case["require"]:
        if re.search(pattern, text, re.IGNORECASE | re.MULTILINE) is None:
            raise QualityFailure(f"independent intent failed: {case['id']}")
    for pattern in case["forbid"]:
        if re.search(pattern, text, re.IGNORECASE | re.MULTILINE) is not None:
            raise QualityFailure(f"independent format failed: {case['id']}")


def native_contracts(data: list) -> None:
    demand(isinstance(data, list) and len(data) >= 2 and (len(data) - 2) % 4 == 0, "native contract schema")
    demand(all(type(x) is int for x in data) and data[0] == 1 and data[1] >= 0, "native contract types/version")
    expected = {(arm, case) for arm in (0, 1) for case in range(13) if not (arm == 0 and case in (5, 6, 7))}
    seen = set()
    for index in range(2, len(data), 4):
        arm, case, actual, auxiliary = data[index:index + 4]
        demand((arm, case) in expected and (arm, case) not in seen, "unexpected/duplicate native contract")
        seen.add((arm, case))
        wanted = {0: 0, 1: 1, 2: 0, 3: 0, 4: 2, 5: -1, 6: -1, 7: -1, 8: -1,
                  9: 2, 10: -2 if data[1] == 0 else 1, 11: 3, 12: 1}[case]
        aux = data[1] if case == 10 else (0 if case in (5, 6, 7, 8) else 1)
        demand(actual == wanted and auxiliary == aux, f"native contract arm{arm}/case{case}")
    demand(seen == expected, "missing native contracts")


def validate(result: dict, corpus_bytes: bytes) -> tuple[dict, list, list]:
    corpus = json.loads(corpus_bytes)
    demand(isinstance(result, dict) and isinstance(corpus, dict), "study/corpus must be objects")
    demand(type(result.get("version")) is int and result["version"] == 1 and result.get("complete") is True and result.get("status") == "COMPLETED", "incomplete study")
    demand(result.get("stage") in ("pilot", "confirm"), "unknown stage")
    demand(result.get("corpus_sha256") == digest(corpus_bytes), "corpus identity changed")
    demand(result.get("model_sha256") == corpus["model_sha256"], "model identity changed")
    demand(result.get("system_prompt_sha256") == corpus["system_prompt_sha256"], "system prompt changed")
    demand(result.get("settings") == corpus["settings"], "generation settings changed")
    demand(isinstance(result.get("backend"), str) and result["backend"].startswith("libggml-cpu-android_"), "not portable CPU")
    demand(integer(result.get("api"), "API") == 36 and result.get("device", "").lower() == "samsung/sm-a266b", "outside the preregistered physical device/API")
    native_contracts(result.get("native_contracts"))
    demand(result.get("phase_waiter_control") == "EXPIRED" and result.get("timer_turnover_control") == "PASSED", "missing waiter/timer control")
    demand(result.get("phase_waiter_observation") == [0, 2, 0, 1], "never-signalled waiter observation")
    phase = result.get("timer_phase_observation")
    demand(isinstance(phase, list) and len(phase) == 4 and all(type(x) is int for x in phase), "phase observation schema")
    demand(phase[0] > 0 and phase[1] == 2 and phase[2] == phase[0] and phase[3] == 0, "successor native phase not staged")
    cases = {case["id"]: case for case in corpus["timing"] + corpus["quality"]}
    demand(len(cases) == len(corpus["timing"]) + len(corpus["quality"]), "duplicate corpus case")
    for case in cases.values():
        demand(isinstance(case.get("text"), str), "corpus text type")
        demand(digest(case["text"].encode()) == case["sha256"], "corpus text identity")
        for pattern in case["require"] + case["forbid"]:
            re.compile(pattern)
    rows = result.get("rows")
    demand(isinstance(rows, list), "missing rows")
    schedule = PILOT if result["stage"] == "pilot" else CONFIRM
    wanted_timing = [(role, block, case, arm) for block, arms, ids in schedule for arm in arms
                     for role, case in [("warmup", "S")] + [("timing", x) for x in ids]]
    observed_timing = []
    actual_controls = Counter()
    expected_controls = Counter({("ordinary_compatibility", "S", 0): 1, ("after_reload", "S", 0): 1,
                                 ("timer_reference", "L", 0): 1})
    for arm in (0, 1):
        for phase_id in range(1, 5):
            expected_controls[(f"abort{phase_id}", "S", arm)] += 1
            expected_controls[(f"after_abort{phase_id}", "S", arm)] += 1
        for role, case in (("cap", "S"), ("context_limit", "context_limit"), ("unloaded", "S")):
            expected_controls[(role, case, arm)] += 1
        for case in corpus["quality"]:
            expected_controls[("quality", case["id"], arm)] += 1
    expected_controls[("ordinary_eos", "S", 0)] += 1
    expected_controls[("ordinary_after_reload", "S", 0)] += 1
    for phase_id in range(1, 5):
        expected_controls[(f"ordinary_abort{phase_id}", "S", 0)] += 1
        expected_controls[(f"ordinary_after_abort{phase_id}", "S", 0)] += 1
    for role, case in (("ordinary_cap", "S"), ("ordinary_context_limit", "context_limit"), ("ordinary_unloaded", "S")):
        expected_controls[(role, case, 0)] += 1
    serials = set()
    row_serials = []
    signatures = {}
    timings = {}
    environment_errors = []
    for row in rows:
        demand(isinstance(row, dict), "row must be an object")
        role, block, case_id, arm = row.get("role"), row.get("block"), row.get("case"), row.get("arm")
        demand(isinstance(role, str) and isinstance(case_id, str) and type(block) is int and type(arm) is int and arm in (0, 1), "row identity")
        sample = row.get("sample", {})
        demand(isinstance(sample, dict), "sample must be an object")
        output, end, metrics, tokens, chain = (sample.get(k) for k in ("output", "end", "metrics", "tokens", "chain"))
        demand(isinstance(output, str) and digest(output.encode()) == sample.get("output_sha256"), "output identity")
        demand(isinstance(end, list) and len(end) == 3 and all(type(x) is int for x in end), "ending schema")
        demand(isinstance(metrics, list) and len(metrics) == 10 and all(type(x) is int for x in metrics), "metric schema")
        demand(isinstance(tokens, list) and all(type(x) is int and x >= 0 for x in tokens), "token schema")
        demand(isinstance(chain, str) and metrics[0] == arm and metrics[1] > 0 and metrics[2] >= 0, "native arm/time")
        chain = chain.strip()
        entry = row.get("entry")
        demand(entry in ("ordinary", "debug") and metrics[9] == (0 if entry == "ordinary" else 1), "actual JNI entry")
        demand(entry != "ordinary" or arm == 0, "ordinary entry cannot select candidate")
        base_role = role.removeprefix("ordinary_") if entry == "ordinary" else role
        demand(metrics[2] <= metrics[1] + 100000, "sampling time exceeds total time")
        requested_cap = integer(row.get("requested_cap"), "requested cap")
        demand(requested_cap > 0, "invalid requested cap")
        if case_id in cases:
            demand(row.get("input_sha256") == cases[case_id]["sha256"] and row.get("user_prompt_sha256") == cases[case_id]["user_prompt_sha256"], "actual input/prompt identity")
        expected_requested = 1 if base_role in ("cap", "context_limit") else cases[case_id]["requested_cap"]
        demand(requested_cap == expected_requested, "frozen requested cap changed")
        demand(end[1] >= 0 and end[2] >= 0, "negative generated/prompt count")
        before_generation = base_role == "abort1"
        refused = base_role in ("context_limit", "unloaded")
        if before_generation or refused:
            demand(metrics[4] == 0 and end[1:3] == [0, 0] and not chain, "pre-generation/refusal accounting")
        else:
            demand(end[2] > 0 and corpus["settings"]["context"] - end[2] - 8 > 0, "invalid decoded prompt/context")
            effective = max(1, min(requested_cap, corpus["settings"]["context"] - end[2] - 8))
            demand(metrics[4] == effective and 0 <= end[1] <= effective, "effective cap/count accounting")
            if end[0] == 0:
                demand(end[1] < effective, "EOS generated count reaches cap")
            elif end[0] == 1:
                demand(end[1] == effective, "length generated count differs from cap")
        demand(metrics[6] > 0 and metrics[6] not in serials, "duplicate/missing generation serial")
        serials.add(metrics[6])
        row_serials.append(metrics[6])
        demand(0 <= metrics[7] <= 4294967295, "seed/sentinel range")
        if not base_role.startswith("abort"):
            demand(metrics[5] == 0, "unexpected abort phase")
        if chain:
            demand(chain.endswith("dist") if arm == 0 else chain.endswith("greedy") and "dist" not in chain, "actual sampler chain")
            demand(metrics[3] == len(tokens) and metrics[8] == (tokens[-1] if tokens else -1), "actual accepted history")
        else:
            demand(not tokens and metrics[3] == 0 and metrics[2] == 0 and metrics[8] == -1, "trace without a sampler")
        if role in ("timing", "warmup"):
            observed_timing.append((role, block, case_id, arm))
        else:
            demand(block == -1, "control block identity")
            actual_controls[(role, case_id, arm)] += 1
        if base_role.startswith("abort"):
            phase_id = int(base_role[5:])
            demand(end[0] == 2 and end[1] == (0 if phase_id <= 2 else 1) and metrics[5] == phase_id, "abort phase/count")
            demand(len(tokens) == end[1], "aborted trace count")
            if phase_id == 2:
                demand(not chain, "sampler cannot precede prompt completion")
        elif base_role in ("context_limit", "unloaded"):
            wanted = "ERROR: S1 prompt exceeds the mobile context limit" if base_role == "context_limit" else "ERROR: S1-mini is not loaded"
            demand(output == wanted and end == [-1, 0, 0], "refusal/ending")
        elif base_role == "cap":
            demand(end[0:2] == [1, 1] and metrics[4] == 1 and len(tokens) == 1, "cap control")
        else:
            demand(case_id in cases and end[0] == 0 and end[1] + 1 == len(tokens) and chain, "healthy EOS result")
            demand(output and not output.startswith("ERROR:"), "healthy output")
            intent(cases[case_id], output)
            signature = (output, tuple(tokens), tuple(end), metrics[4])
            demand(case_id not in signatures or signatures[case_id] == signature, f"output/token/end repetition mismatch: {case_id}")
            signatures[case_id] = signature
        if role == "timing":
            timings[(block, case_id, arm)] = row
    demand(actual_controls == expected_controls, "missing/extra control rows")
    demand(row_serials == sorted(row_serials), "generation rows out of order")
    demand(observed_timing == wanted_timing, "missing/extra/reordered trial or warmup")
    all_loads = result.get("loads")
    demand(isinstance(all_loads, list) and all(isinstance(x, dict) for x in all_loads), "load schema")
    study_loads = [x for x in all_loads if x.get("role") == "study"]
    expected_loads = [("controls", -1, -1), ("reload", -1, -1)] + [("study", block, arm) for block, arms, _ in schedule for arm in arms]
    demand([(x.get("role"), x.get("block"), x.get("arm")) for x in all_loads] == expected_loads, "load census/order")
    wanted_loads = [(block, arm) for block, arms, _ in schedule for arm in arms]
    demand([(x.get("block"), x.get("arm")) for x in study_loads] == wanted_loads, "missing/reordered arm loads")
    for load in study_loads:
        demand(isinstance(load.get("status"), str) and load["status"].endswith("| context 2048 | threads 4"), "actual model context/threads changed")
        demand(integer(load.get("load_ms"), "load time") >= 0, "negative load time")
    control_calls = result.get("control_calls")
    demand(isinstance(control_calls, list), "missing ordinary/runtime calls")
    demand(all(isinstance(x, dict) for x in control_calls), "ordinary call schema")
    controls = {x.get("id"): x for x in control_calls}
    demand(len(controls) == len(control_calls) == 5 and set(controls) == {"ordinary_baseline", "invalid_arm", "invalid_phase", "timer_initial", "timer_successor"}, "ordinary/runtime call census")
    demand([x["id"] for x in control_calls] == ["ordinary_baseline", "invalid_arm", "invalid_phase", "timer_initial", "timer_successor"], "ordinary call order")
    last_unloaded = max(row["sample"]["metrics"][6] for row in rows if row["role"] in ("unloaded", "ordinary_unloaded"))
    for name, control in controls.items():
        output = control.get("output")
        demand(isinstance(output, str) and digest(output.encode()) == control.get("output_sha256"), "ordinary/runtime output identity")
        demand(isinstance(control.get("end"), list) and len(control["end"]) == 3 and all(type(x) is int for x in control["end"]), "ordinary ending schema")
        if name.startswith("invalid_"):
            demand(control.get("native_started") is False and output == "ERROR: Invalid sampler study request" and control.get("end") == [-1, 0, 0], "invalid-request control")
            demand(integer(control.get("serial"), "refused serial") == last_unloaded, "refused request started or reused unrelated metadata")
        else:
            demand(control.get("native_started") is True, "ordinary generation missing")
            value = integer(control.get("serial"), "ordinary serial")
            demand(value > 0 and value not in serials, "duplicate ordinary generation serial")
            serials.add(value)
            case_id = "L" if name == "timer_successor" else "S"
            demand(output == signatures[case_id][0] and tuple(control.get("end", [])) == signatures[case_id][2], "ordinary/runtime compatibility")
    demand(phase[0] == controls["timer_successor"]["serial"] and phase[0] > controls["timer_initial"]["serial"], "phase is not the actual successor")
    demand(controls["ordinary_baseline"]["serial"] < row_serials[0], "ordinary baseline causality")
    timer_reference = next(row["sample"]["metrics"][6] for row in rows if row["role"] == "timer_reference")
    first_warmup = next(row["sample"]["metrics"][6] for row in rows if row["role"] == "warmup")
    demand(timer_reference < controls["timer_initial"]["serial"] < controls["timer_successor"]["serial"] < first_warmup, "timer call causality")
    demand(sorted(serials) == list(range(min(serials), max(serials) + 1)), "unaccounted generation calls")
    charging = set()
    for row in rows:
        if row["role"] not in ("warmup", "timing"):
            continue
        for key in ("before", "after"):
            state = row.get(key)
            if not isinstance(state, dict) or not all(type(state.get(k)) is int for k in ("thermal", "plugged", "battery_status", "temperature_tenths_c")):
                environment_errors.append({"study": "missing benchmark environment"})
                continue
            charging.add((state["plugged"], state["battery_status"]))
            if not (0 <= state["thermal"] <= 1 and state["plugged"] >= 0 and 2 <= state["battery_status"] <= 5):
                environment_errors.append({"study": "benchmark environment"})
    for block, _, ids in schedule:
        for case_id in ids:
            pair = [timings[(block, case_id, arm)] for arm in (0, 1)]
            snapshots = [row[key] for row in pair for key in ("before", "after")]
            try:
                for state in snapshots:
                    demand(all(type(state[k]) is int for k in ("thermal", "plugged", "battery_status", "temperature_tenths_c")), "environment types")
                    charging.add((state["plugged"], state["battery_status"]))
                valid = all(0 <= x["thermal"] <= 1 and x["plugged"] >= 0 and 2 <= x["battery_status"] <= 5 and x["temperature_tenths_c"] >= 0 for x in snapshots)
                valid &= len({(x["plugged"], x["battery_status"]) for x in snapshots}) == 1
                valid &= max(x["temperature_tenths_c"] for x in snapshots) - min(x["temperature_tenths_c"] for x in snapshots) <= 20
            except (KeyError, TypeError):
                valid = False
            if not valid:
                environment_errors.append({"block": block, "case": case_id})
    if len(charging) != 1:
        environment_errors.append({"study": "charging changed"})
    return timings, schedule, environment_errors


def evaluate(result: dict, corpus_bytes: bytes) -> dict:
    timings, schedule, environment_errors = validate(result, corpus_bytes)
    peak = integer(result.get("observed_thermal_peak"), "observed thermal peak")
    if not 0 <= peak <= 1:
        environment_errors.append({"study": "observed thermal peak"})
    if environment_errors:
        return {"decision": "ENVIRONMENT_NOT_QUALIFIED", "invalid_pairs": environment_errors, "exit_code": 11}
    summaries = {}
    rng = random.Random(51020261105)
    for case_id in ("S", "M", "L"):
        pairs = [(timings[(block, case_id, 0)]["sample"]["metrics"][1] / 1e6,
                  timings[(block, case_id, 1)]["sample"]["metrics"][1] / 1e6)
                 for block, _, ids in schedule if case_id in ids]
        fractional = [(a - b) / a for a, b in pairs]
        absolute = [a - b for a, b in pairs]
        row = {"pairs": len(pairs), "median_fractional_reduction": statistics.median(fractional),
               "median_ms_reduction": statistics.median(absolute), "paired_ms": pairs}
        if result["stage"] == "confirm" and case_id in ("M", "L"):
            draws = sorted(statistics.median(rng.choices(fractional, k=len(fractional))) for _ in range(50000))
            row["lower_95_percentile"] = draws[int(0.025 * (len(draws) - 1))]
        summaries[case_id] = row
    passed = all(summaries[k]["median_fractional_reduction"] >= 0.05 and summaries[k]["median_ms_reduction"] >= 100 for k in ("M", "L"))
    if result["stage"] == "pilot":
        passed &= all(a >= b for k in ("M", "L") for a, b in summaries[k]["paired_ms"])
    else:
        passed &= all(summaries[k]["lower_95_percentile"] > 0 for k in ("M", "L"))
        passed &= all((b - a) / a <= 0.05 and b - a <= 50 for a, b in summaries["S"]["paired_ms"])
    return {"decision": ("SCREEN_PASSED_PENDING_MANUAL_REVIEW" if result["stage"] == "pilot" else "CONFIRMATION_PASSED_PENDING_MANUAL_REVIEW") if passed else "REJECTED_PERFORMANCE",
            "exit_code": 0 if passed else 10, "stage": result["stage"], "cases": summaries,
            "limitations": ["Repeated fixtures on one device, not independent phone samples.", "Manual full-text semantic review still required.", "No ordinary-entry/whole-app speed transfer established."]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", required=True, type=Path)
    parser.add_argument("--result", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    result_bytes = None
    try:
        result_bytes = args.result.read_bytes()
        answer = evaluate(json.loads(result_bytes), args.corpus.read_bytes())
    except QualityFailure as error:
        answer = {"decision": "REJECTED_QUALITY", "reason": str(error), "exit_code": 12}
    except (InvalidStudy, ValueError, KeyError, TypeError, IndexError, AttributeError, OSError) as error:
        answer = {"decision": "INVALID_OR_INCOMPLETE", "reason": str(error), "exit_code": 2}
    answer["result_sha256"] = digest(result_bytes) if result_bytes is not None else None
    args.out.write_text(json.dumps(answer, indent=2) + "\n")
    print(json.dumps(answer))
    return answer["exit_code"]


if __name__ == "__main__":
    raise SystemExit(main())
