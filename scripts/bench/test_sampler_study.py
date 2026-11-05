"""Harness Contracts: corrupted or incomplete evidence cannot become a sampler speed win."""
import copy
import json
import unittest
from pathlib import Path

import sampler_study as study


CORPUS = Path(__file__).resolve().parents[2] / "app/src/androidTest/assets/sampler-study-v1.json"


def evidence(stage="pilot"):
    raw = CORPUS.read_bytes()
    corpus = json.loads(raw)
    cases = {x["id"]: x for x in corpus["timing"] + corpus["quality"]}
    outputs = {key: value["text"] for key, value in cases.items()}
    outputs["list"] = "1. Bring the blue folder.\n2. Print three copies.\n3. Call Jordan."
    rows, loads = [], [{"role": "controls", "block": -1, "arm": -1, "status": "fixture | context 2048 | threads 4", "load_ms": 1000},
                       {"role": "reload", "block": -1, "arm": -1, "status": "fixture | context 2048 | threads 4", "load_ms": 1000}]
    serial = 1

    def add(role, case, arm, block=-1, ordinary=False):
        nonlocal serial
        serial += 1
        base_role = role.removeprefix("ordinary_") if ordinary else role
        requested = 1 if base_role in ("cap", "context_limit") else cases[case]["requested_cap"]
        cap, end, tokens, chain = requested, [0, 2, 10], [11, 22, 99], "logits -> " + ("dist" if arm == 0 else "greedy")
        output, aborted = outputs.get(case, ""), 0
        if base_role.startswith("abort"):
            aborted = int(base_role[5:])
            count = 0 if aborted <= 2 else 1
            end, tokens, output = [2, count, 10], [11] * count, "ERROR: S1 generation aborted"
            if aborted <= 2:
                chain = ""
            if aborted == 1:
                end[2], cap = 0, 0
        elif base_role in ("context_limit", "unloaded"):
            output = "ERROR: S1 prompt exceeds the mobile context limit" if base_role == "context_limit" else "ERROR: S1-mini is not loaded"
            cap, end, tokens, chain = 0, [-1, 0, 0], [], ""
        elif base_role == "cap":
            cap, end, tokens = 1, [1, 1, 10], [11]
        milliseconds = {"S": (1000, 1000), "M": (4000, 3500), "L": (10000, 9000)}.get(case, (1000, 1000))[arm]
        environment = {"thermal": 0, "plugged": 1, "battery_status": 2, "temperature_tenths_c": 250}
        spec = cases.get(case, {})
        rows.append({"role": role, "case": case, "arm": arm, "block": block,
                     "input_sha256": spec.get("sha256"), "user_prompt_sha256": spec.get("user_prompt_sha256"),
                     "requested_cap": requested, "entry": "ordinary" if ordinary else "debug",
                     "before": copy.deepcopy(environment), "after": copy.deepcopy(environment),
                     "sample": {"output": output, "output_sha256": study.digest(output.encode()), "tokens": tokens,
                                "end": end, "chain": chain,
                                "metrics": [arm, milliseconds * 1000000, 100000 if chain else 0, len(tokens), cap, aborted, serial, 7, tokens[-1] if tokens else -1, 0 if ordinary else 1]}})

    add("ordinary_compatibility", "S", 0)
    add("ordinary_eos", "S", 0, ordinary=True)
    for arm, ordinary in ((0, False), (1, False), (0, True)):
        prefix = "ordinary_" if ordinary else ""
        for phase in range(1, 5):
            add(f"{prefix}abort{phase}", "S", arm, ordinary=ordinary)
            add(f"{prefix}after_abort{phase}", "S", arm, ordinary=ordinary)
        add(prefix + "cap", "S", arm, ordinary=ordinary)
        add(prefix + "context_limit", "context_limit", arm, ordinary=ordinary)
    for arm, ordinary in ((0, False), (1, False), (0, True)):
        add("ordinary_unloaded" if ordinary else "unloaded", "S", arm, ordinary=ordinary)
    last_unloaded = serial
    add("after_reload", "S", 0)
    add("ordinary_after_reload", "S", 0, ordinary=True)
    for case in corpus["quality"]:
        for arm in (0, 1):
            add("quality", case["id"], arm)
    add("timer_reference", "L", 0)
    timer_initial, timer_successor = serial + 1, serial + 2
    serial += 2
    schedule = study.PILOT if stage == "pilot" else study.CONFIRM
    for block, arms, ids in schedule:
        for arm in arms:
            loads.append({"role": "study", "block": block, "arm": arm, "status": "fixture | context 2048 | threads 4", "load_ms": 1000})
            add("warmup", "S", arm, block)
            for case in ids:
                add("timing", case, arm, block)
    contracts = [1, 0]
    wanted = {0: 0, 1: 1, 2: 0, 3: 0, 4: 2, 5: -1, 6: -1, 7: -1, 8: -1, 9: 2, 10: -2, 11: 3, 12: 1}
    for arm in (0, 1):
        for case, actual in wanted.items():
            if arm == 0 and case in (5, 6, 7):
                continue
            contracts += [arm, case, actual, 0 if case in (5, 6, 7, 8, 10) else 1]
    controls = []
    for name, case in (("ordinary_baseline", "S"), ("invalid_arm", None), ("invalid_phase", None), ("timer_initial", "S"), ("timer_successor", "L")):
        output = outputs[case] if case else "ERROR: Invalid sampler study request"
        control_serial = {"ordinary_baseline": 1, "invalid_arm": last_unloaded, "invalid_phase": last_unloaded, "timer_initial": timer_initial, "timer_successor": timer_successor}[name]
        controls.append({"id": name, "output": output, "output_sha256": study.digest(output.encode()),
                         "end": [0, 2, 10] if case else [-1, 0, 0], "serial": control_serial, "native_started": case is not None})
    return {"version": 1, "stage": stage, "complete": True, "status": "COMPLETED", "api": 36, "device": "samsung/SM-A266B",
            "backend": "libggml-cpu-android_fixture.so", "corpus_sha256": study.digest(raw),
            "model_sha256": corpus["model_sha256"], "system_prompt_sha256": corpus["system_prompt_sha256"],
            "settings": corpus["settings"], "rows": rows, "loads": loads, "control_calls": controls,
            "observed_thermal_peak": 0,
            "native_contracts": contracts, "phase_waiter_control": "EXPIRED", "phase_waiter_observation": [0, 2, 0, 1],
            "timer_turnover_control": "PASSED", "timer_phase_observation": [timer_successor, 2, timer_successor, 0]}, raw


class EvidenceTests(unittest.TestCase):
    def test_emulator_or_boolean_schema_cannot_claim_physical_speed(self):
        for field, value in (("device", "Google/sdk_gphone64_arm64"), ("api", 35), ("version", True)):
            result, corpus = evidence()
            result[field] = value
            with self.assertRaises(study.InvalidStudy):
                study.evaluate(result, corpus)

    def test_complete_screen_requires_material_gain(self):
        result, corpus = evidence()
        self.assertEqual(0, study.evaluate(result, corpus)["exit_code"])
        for row in result["rows"]:
            if row["role"] == "timing" and row["case"] in ("M", "L"):
                row["sample"]["metrics"][1] = {"M": 4000, "L": 10000}[row["case"]] * 1000000
        self.assertEqual("REJECTED_PERFORMANCE", study.evaluate(result, corpus)["decision"])

    def test_pinned_chain_printer_trailing_space_is_accepted(self):
        result, corpus = evidence()
        # common_sampler_print appends one space after its final sampler name.
        for row in result["rows"]:
            if row["sample"]["chain"]:
                row["sample"]["chain"] += " "
        self.assertEqual(0, study.evaluate(result, corpus)["exit_code"])

    def test_independent_name_oracle_rejects_equal_corruption(self):
        result, corpus = evidence()
        for row in result["rows"]:
            if row["case"] == "M":
                text = row["sample"]["output"].replace("Maya", "Nora")
                row["sample"].update(output=text, output_sha256=study.digest(text.encode()))
        with self.assertRaises(study.QualityFailure):
            study.evaluate(result, corpus)

    def test_independent_number_and_negation_oracles(self):
        for key, before, after in (("names_numbers", "forty five", "one hundred"), ("negation", "Do not cancel", "Please cancel")):
            result, corpus = evidence()
            for row in result["rows"]:
                if row["case"] == key:
                    text = row["sample"]["output"].replace(before, after)
                    row["sample"].update(output=text, output_sha256=study.digest(text.encode()))
            with self.assertRaises(study.QualityFailure):
                study.evaluate(result, corpus)

    def test_missing_or_reordered_pair_is_not_a_winner(self):
        result, corpus = evidence()
        result["rows"].pop()
        with self.assertRaises(study.InvalidStudy):
            study.evaluate(result, corpus)
        result, corpus = evidence()
        result["rows"][-1], result["rows"][-2] = result["rows"][-2], result["rows"][-1]
        with self.assertRaises(study.InvalidStudy):
            study.evaluate(result, corpus)

    def test_ending_change_and_same_arm_cannot_pass(self):
        for change in ("ending", "chain"):
            result, corpus = evidence()
            row = next(x for x in result["rows"] if x["role"] == "timing" and x["arm"] == 1)
            if change == "ending":
                row["sample"]["end"][1] += 1
            else:
                row["sample"]["chain"] = "logits -> dist"
            with self.assertRaises(study.InvalidStudy):
                study.evaluate(result, corpus)

    def test_hot_or_changed_charging_rejects_stage(self):
        for field, value in (("thermal", 2), ("plugged", 0), ("battery_status", 5), ("temperature_tenths_c", 290)):
            result, corpus = evidence()
            next(x for x in result["rows"] if x["role"] == "timing")["after"][field] = value
            self.assertEqual(11, study.evaluate(result, corpus)["exit_code"])
        result, corpus = evidence()
        result["observed_thermal_peak"] = 2
        self.assertEqual(11, study.evaluate(result, corpus)["exit_code"])

    def test_missing_actual_native_phase_or_input_identity_fails(self):
        result, corpus = evidence()
        result["timer_phase_observation"][2] = 0
        with self.assertRaises(study.InvalidStudy):
            study.evaluate(result, corpus)
        result, corpus = evidence()
        result["rows"][0]["user_prompt_sha256"] = "bad"
        with self.assertRaises(study.InvalidStudy):
            study.evaluate(result, corpus)

    def test_equal_invalid_accounting_in_both_arms_is_rejected(self):
        for change in ("zero_cap", "negative_prompt", "changed_request"):
            result, corpus = evidence()
            for row in result["rows"]:
                if row["case"] != "M":
                    continue
                if change == "zero_cap":
                    row["sample"]["metrics"][4] = 0
                elif change == "negative_prompt":
                    row["sample"]["end"][2] = -7
                else:
                    row["requested_cap"] += 1
            with self.assertRaises(study.InvalidStudy):
                study.evaluate(result, corpus)

    def test_warmup_only_charging_change_rejects_stage(self):
        result, corpus = evidence()
        next(row for row in result["rows"] if row["role"] == "warmup")["after"]["plugged"] = 0
        self.assertEqual(11, study.evaluate(result, corpus)["exit_code"])

    def test_fabricated_phase_serial_and_missing_ordinary_lifecycle_fail(self):
        result, corpus = evidence()
        result["timer_phase_observation"] = [999999, 2, 999999, 0]
        with self.assertRaises(study.InvalidStudy):
            study.evaluate(result, corpus)
        result, corpus = evidence()
        result["rows"] = [row for row in result["rows"] if row["entry"] != "ordinary"]
        with self.assertRaises(study.InvalidStudy):
            study.evaluate(result, corpus)
    def test_confirmation_uses_all_preregistered_pairs(self):
        result, corpus = evidence("confirm")
        answer = study.evaluate(result, corpus)
        self.assertEqual(0, answer["exit_code"])
        self.assertEqual(10, answer["cases"]["M"]["pairs"])
        self.assertEqual(10, answer["cases"]["L"]["pairs"])
        self.assertEqual(3, answer["cases"]["S"]["pairs"])


if __name__ == "__main__":
    unittest.main()
