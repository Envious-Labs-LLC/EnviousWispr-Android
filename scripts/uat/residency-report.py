#!/usr/bin/env python3
"""Summarise a #72 residency campaign: one row per condition × shape.

    scripts/uat/residency-report.py [.validation/uat/residency]

Per run it reads timeline.txt (logcat -v time lines), mem.csv (RSS samples), apps-before/after.txt and the
thermal files. Latencies come from the log timestamps: stop→handoff is the user's wait; S1 placement is the
S1 load line's time relative to result_received (negative = loaded before the transcript arrived, so hidden
from the user; positive = inside the wait). A run with no handoff line is reported as failed, never averaged.
"""
import csv, os, re, statistics, sys
from datetime import datetime

ROOT = sys.argv[1] if len(sys.argv) > 1 else ".validation/uat/residency"
TS = re.compile(r"^(\d\d-\d\d \d\d:\d\d:\d\d\.\d+)")

def stamp(line):
    m = TS.match(line)
    return datetime.strptime("2026-" + m.group(1), "%Y-%m-%d %H:%M:%S.%f").timestamp() if m else None

def first(lines, needle):
    for l in lines:
        if needle in l:
            return stamp(l), l
    return None, None

def run_metrics(rd):
    # run.log is the full filtered logcat; timeline.txt is the short human view and lacks the warm-up line.
    src = os.path.join(rd, "run.log") if os.path.exists(os.path.join(rd, "run.log")) else os.path.join(rd, "timeline.txt")
    lines = open(src).read().splitlines()
    # The session owner logs this line the moment the stop lands; the recording_stop mark is the capture
    # process's own and is not in the filtered logcat.
    t_stop, _ = first(lines, "Stopping recording and starting transcription")
    t_req, _ = first(lines, "asr_request")
    t_res, _ = first(lines, "result_received")
    t_s1, s1_line = first(lines, "S1-mini loaded")
    t_done, _ = first(lines, "polish_done")
    t_hand, hand = first(lines, "Auto-insert handed")
    if t_hand is None:
        t_hand, hand = first(lines, "kept on clipboard")
    polish_ms = None
    for l in lines:
        m = re.search(r"Polish result received \(.*?, (\d+)ms", l)
        if m: polish_ms = int(m.group(1))
    s1_load_ms = None
    if s1_line:
        m = re.search(r"in (\d+)ms", s1_line); s1_load_ms = int(m.group(1)) if m else None
    mem = list(csv.DictReader(open(os.path.join(rd, "mem.csv")))) if os.path.exists(os.path.join(rd, "mem.csv")) else []
    pss = list(csv.DictReader(open(os.path.join(rd, "pss.csv")))) if os.path.exists(os.path.join(rd, "pss.csv")) else []
    def peak(k): return max((int(r[k]) for r in pss if r.get(k)), default=0) // 1024
    def fmax(k): return max((float(r[k]) for r in mem if r.get(k)), default=0.0)
    def ffirst(k): return float(mem[0][k]) if mem and mem[0].get(k) else 0.0
    pss_rate = (len(pss) - 1) / ((int(pss[-1]["epoch_ms"]) - int(pss[0]["epoch_ms"])) / 1000) if len(pss) > 2 else 0.0
    kills = 0
    if os.path.exists(os.path.join(rd, "apps-after.txt")):
        def parse(path): return dict(l.split("=", 1) for l in open(path).read().split() if "=" in l)
        before, after = parse(os.path.join(rd, "apps-before.txt")), parse(os.path.join(rd, "apps-after.txt"))
        # pid:starttime:pss — a vanished pid or a changed start time is a kill, a reused pid included
        kills = sum(1 for a, v in before.items() if v and (not after.get(a) or after[a].split(":")[:2] != v.split(":")[:2]))
    # lmkd "Reclaim" lines are the low-memory killer evicting CACHED processes during the run; the product's
    # own processes are named com.envi.wispr[:x], the test APK is com.envi.wispr.test and does not count.
    reclaims = [l for l in lines if "lmkd" in l and "Reclaim '" in l]
    ours_killed = any(re.search(r"Reclaim 'com\.envi\.wispr(:[a-z]+)?'", l) for l in reclaims) or any("Killing" in l and "com.envi.wispr/" in l for l in lines)
    thermal = [int(re.search(r"(\d+)", open(os.path.join(rd, f)).read()).group(1))
               for f in ("thermal-before.txt", "thermal-after.txt") if os.path.exists(os.path.join(rd, f))]
    chars = int(open(os.path.join(rd, "transcript-chars.txt")).read().strip() or 0) if os.path.exists(os.path.join(rd, "transcript-chars.txt")) else None
    requested = open(os.path.join(rd, "stratum-requested.txt")).read().strip() if os.path.exists(os.path.join(rd, "stratum-requested.txt")) else "cached"
    # The stratum is DERIVED from the observed engine pid before the run, never from the label requested.
    pid_before = open(os.path.join(rd, "polish-pid-before.txt")).read().strip() if os.path.exists(os.path.join(rd, "polish-pid-before.txt")) else ""
    stratum = ("cold" if not pid_before else "cached") + ("-long" if requested.endswith("long") else "")
    long_take = stratum.endswith("long")
    discarded = os.path.exists(os.path.join(rd, "discarded.txt"))
    band_ok = chars is not None and ((long_take and chars >= 400) or (not long_take and 120 <= chars <= 260))
    warmups = sum(1 for l in lines if "Polish service connected" in l)  # one connect per session is the one warm-up
    loads = sum(1 for l in lines if "S1-mini loaded" in l)
    return {
        "stratum": stratum,
        "ok": (not discarded and t_hand is not None and t_res is not None and band_ok and bool(mem) and bool(pss)
               and warmups == 1 and loads >= 1 and bool(thermal)),
        "why": "discarded" if discarded else "no handoff" if t_hand is None else "no transcript" if t_res is None else "chars out of band" if not band_ok else "no samples" if not (mem and pss) else f"{warmups} warm-ups" if warmups != 1 else "no load line" if loads < 1 else "no thermal" if not thermal else "",
        "pss_rate": pss_rate,
        "chars": chars,
        "psi_full_max": fmax("psi_full_avg10"),
        "psi_full_delta": fmax("psi_full_avg10") - ffirst("psi_full_avg10"),
        "wait_ms": (t_hand - t_stop) * 1000 if (t_hand and t_stop) else None,
        "asr_ms": (t_res - t_req) * 1000 if (t_res and t_req) else None,
        "polish_ms": polish_ms,
        "s1_load_ms": s1_load_ms,
        "s1_placement_ms": (t_s1 - t_res) * 1000 if (t_s1 and t_res) else None,
        "peak_asr": peak("asr_pss_kb"), "peak_polish": peak("polish_pss_kb"),
        "min_avail": min((int(r["memavail_kb"]) for r in mem), default=0) // 1024,
        "app_kills": kills, "ours_killed": ours_killed, "reclaims": len(reclaims), "thermal_max": max(thermal, default=0),
    }

def pct(v, p):
    v = sorted(x for x in v if x is not None)
    if not v: return None
    k = (len(v) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)

def fmt(x): return "-" if x is None else f"{x:.0f}"

print("| condition | shape | stratum | runs ok | wait ms median (min-max) | asr med | polish med | S1 load med | S1 placement med ms | peak PSS asr/polish MB | min free MB | PSI full max (delta) | app kills | ours killed | cached-process reclaims (sum) | thermal max | PSS samples/s | failed runs (why) |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
def rng(v):
    v = [x for x in v if x is not None]
    return "-" if not v else f"{statistics.median(v):.0f} ({min(v):.0f}-{max(v):.0f})"
for cond in ("rested", "loaded", "loaded-long"):
    if not os.path.isdir(os.path.join(ROOT, cond)): continue
    for shape in sorted(os.listdir(os.path.join(ROOT, cond))):
        cell = os.path.join(ROOT, cond, shape)
        if not os.path.isdir(cell): continue
        runs = {d: run_metrics(os.path.join(cell, d)) for d in sorted(os.listdir(cell), key=lambda x: int(x) if x.isdigit() else 0) if os.path.isdir(os.path.join(cell, d))}
        for stratum in sorted({r["stratum"] for r in runs.values()}):
            sel = {d: r for d, r in runs.items() if r["stratum"] == stratum}
            ok = [r for r in sel.values() if r["ok"]]
            failed = [f"{d}:{r['why']}" for d, r in sel.items() if not r["ok"]]
            col = lambda k: [r[k] for r in ok]
            print(f"| {cond} | {shape} | {stratum} | {len(ok)}/{len(sel)} | {rng(col('wait_ms'))} | {fmt(pct(col('asr_ms'),.5))} | {fmt(pct(col('polish_ms'),.5))} | {fmt(pct(col('s1_load_ms'),.5))} | {fmt(pct(col('s1_placement_ms'),.5))} | {max(col('peak_asr') or [0])}/{max(col('peak_polish') or [0])} | {min(col('min_avail') or [0])} | {max(col('psi_full_max') or [0]):.2f} ({max(col('psi_full_delta') or [0]):+.2f}) | {sum(col('app_kills'))} | {sum(1 for r in sel.values() if r['ours_killed'])} | {sum(col('reclaims'))} | {max(col('thermal_max') or [0])} | {statistics.median(col('pss_rate')) if ok else 0:.1f} | {', '.join(failed) or '-'} |")
