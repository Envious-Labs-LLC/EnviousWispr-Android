"""Fail-closed row verification and preregistered, corpus-clustered comparisons."""
import hashlib
import json
import math
import random
import re
import statistics
import unicodedata
from collections import defaultdict

ARMS = {'A0', 'A1', 'A3', 'A4', 'A5', 'B0', 'B1', 'B2'}
ASR = {a for a in ARMS if a.startswith('A')}


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def lexical_tokens(text):
    text = unicodedata.normalize('NFC', text).casefold()
    return re.findall(r"[^\W_]+(?:['\u2019][^\W_]+)*", text, re.UNICODE)


def edits(reference, hypothesis):
    """Literal word-edit distance; no spelling/number normalization or model oracle."""
    old = list(range(len(hypothesis) + 1))
    for i, word in enumerate(reference, 1):
        new = [i]
        for j, candidate in enumerate(hypothesis, 1):
            new.append(min(old[j] + 1, new[-1] + 1, old[j-1] + (word != candidate)))
        old = new
    return old[-1]


def verify_rows(rows, cases, arm, run_id, corpus_hash):
    if arm not in ARMS or not cases:
        raise ValueError('unknown/excluded arm or empty corpus')
    expected_ids = [c['id'] for c in cases]
    if len(set(expected_ids)) != len(expected_ids):
        raise ValueError('duplicate corpus identity')
    if [r.get('id') for r in rows] != expected_ids:
        raise ValueError('missing, duplicated, stale or reordered output')
    for i, (row, case) in enumerate(zip(rows, cases)):
        if (row.get('schema'), row.get('runId'), row.get('arm'), row.get('corpusSha256'), row.get('rowIndex')) != (1, run_id, arm, corpus_hash, i):
            raise ValueError('row identity does not match the requested operation')
        for key in ('ms', 'loadMs'):
            value = row.get(key)
            if type(value) not in (float, int) or not math.isfinite(value) or value <= 0:
                raise ValueError('invalid timing, not a completed inference')
        if row.get('warmups') != 1 or row.get('nativeState') != 'resident-inference-after-one-excluded-warmup':
            raise ValueError('incorrect inference state')
        if not isinstance(row.get('text'), str):
            raise ValueError('missing native output')
        for key in ('thermalBefore', 'thermalAfter'):
            if type(row.get(key)) is not int or not 0 <= row[key] <= 6:
                raise ValueError('missing or invalid thermal observation')
        if arm in ASR:
            if row.get('backend') != 'CPU' or row.get('threads') != 4 or row.get('pcmSha256') != case['pcmSha256']:
                raise ValueError('speech route/input identity mismatch')
            if row.get('modelClass') != ('com.envi.wispr.asr.TdtNoSpinModel' if arm == 'A1' else 'com.envi.wispr.asr.TdtModel'):
                raise ValueError('native model factory did not reach the intended class')
            shapes = row.get('observedInputs')
            if not isinstance(shapes, list) or not shapes:
                raise ValueError('missing real model input dimensions')
            for shape in shapes:
                length = shape.get('realSamples')
                if type(length) is not int or length <= 0:
                    raise ValueError('invalid real input length')
                expected = max(length, 240000) if arm in ('A0','A1') else max(length, 192000) if arm == 'A3' else max(length, 128000) if arm == 'A4' else max(length, min(240000, length + 16000))
                if shape.get('tensorSamples') != expected:
                    raise ValueError('actual model tensor shape does not match the declared arm')
            if row.get('paddingArm') != arm or row.get('spinning') != ('0' if arm == 'A1' else 'runtime-default'):
                raise ValueError('incorrect speech arm')
        else:
            batch = {'B0':512, 'B1':256, 'B2':128}[arm]
            if row.get('backend') != 'gpu' or row.get('batch') != batch or row.get('ubatch') != batch or row.get('stop') != 'eos':
                raise ValueError('polish route/config/completion mismatch')
            if row.get('modelSha256') != '3b41ebe2502cbd03e811d5d16b022f5ab551eda58d62597d152f89535003c634':
                raise ValueError('wrong polish model')
            if any(row.get(key) != case[key] for key in ('styling','structure','context')):
                raise ValueError('incorrect frozen writing controls')
            if type(row.get('generated')) is not int or type(row.get('cap')) is not int or not 0 < row['generated'] < row['cap']:
                raise ValueError('incomplete generation')
    return {'rows':len(rows), 'environment_valid':all(r['thermalBefore'] <= 1 and r['thermalAfter'] <= 1 for r in rows)}


def percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        raise ValueError('empty observation set')
    index = (len(ordered)-1) * fraction
    lo = int(index)
    hi = min(lo+1, len(ordered)-1)
    return ordered[lo] + (ordered[hi]-ordered[lo]) * (index-lo)


def compare(cases, baseline_trials, candidate_trials, *, seed=43320261007, draws=10000):
    """Trial inputs already verified. No failed/invalid trial may reach this authority."""
    if len(baseline_trials) != 3 or len(candidate_trials) != 3:
        raise ValueError('the three preregistered repeat blocks are required')
    ids = [c['id'] for c in cases]
    for trial in baseline_trials + candidate_trials:
        if [r['id'] for r in trial] != ids:
            raise ValueError('pairing mismatch')
        if any(r['thermalBefore'] > 1 or r['thermalAfter'] > 1 for r in trial):
            raise ValueError('environmentally invalid block')
    baseline = [statistics.median(trial[i]['ms'] for trial in baseline_trials) for i in range(len(cases))]
    candidate = [statistics.median(trial[i]['ms'] for trial in candidate_trials) for i in range(len(cases))]
    ratios = [1-c/b for b,c in zip(baseline,candidate)]
    clusters = defaultdict(list)
    for i, case in enumerate(cases):
        clusters[case['cluster']].append(i)
    if len(clusters) < 10:
        raise ValueError('fewer than ten independent represented clusters')
    rng = random.Random(seed)
    keys = sorted(clusters)
    boot = []
    for _ in range(draws):
        indices = [i for _ in keys for i in clusters[rng.choice(keys)]]
        boot.append(statistics.median(ratios[i] for i in indices))
    # Central 97.5% intervals for the two predeclared engine decision families.
    interval = [percentile(boot, .0125), percentile(boot, .9875)]
    groups = {'overall':list(range(len(cases)))}
    for i, case in enumerate(cases):
        groups.setdefault(case['shape'], []).append(i)
    tails = {}
    for name, ix in groups.items():
        base_calls = [t[i]['ms'] for t in baseline_trials for i in ix]
        candidate_calls = [t[i]['ms'] for t in candidate_trials for i in ix]
        local_clusters = defaultdict(list)
        for i in ix: local_clusters[cases[i]['cluster']].append(i)
        local_keys = sorted(local_clusters)
        tail_boot = []
        for _ in range(draws):
            indices = [i for _ in local_keys for i in local_clusters[rng.choice(local_keys)]]
            b95 = percentile([t[i]['ms'] for t in baseline_trials for i in indices], .95)
            c95 = percentile([t[i]['ms'] for t in candidate_trials for i in indices], .95)
            tail_boot.append(c95 / b95)
        tails[name] = {'baseline_p95':percentile(base_calls,.95),
                       'candidate_p95':percentile(candidate_calls,.95),
                       'ratio_interval97_5':[percentile(tail_boot,.0125),percentile(tail_boot,.9875)],
                       'calls':len(base_calls),'clusters':len(local_keys),
                       'metric':'observed fixed-corpus calls including repeats, not input medians'}
    exact = all(all(b[i]['text'] == c[i]['text'] for i in range(len(cases))) for b,c in zip(baseline_trials,candidate_trials))
    within_arm_repeat_exact = all(all(t[i]['text'] == trials[0][i]['text'] for t in trials) for trials in (baseline_trials,candidate_trials) for i in range(len(cases)))
    speed = statistics.median(ratios) >= .20 and interval[0] >= .10
    tail_ok = all(v['candidate_p95'] <= v['baseline_p95'] * 1.05 and v['ratio_interval97_5'][1] <= 1.05 for v in tails.values())
    return {'inputs':len(cases),'clusters':len(clusters),'paired_median_reduction':statistics.median(ratios),
            'interval97_5':interval,'median_absolute_ms':statistics.median(b-c for b,c in zip(baseline,candidate)),
            'fixed_corpus_tails':tails,'exact_outputs':exact,'repeat_deterministic':within_arm_repeat_exact,
            'engine_target_pass':speed and tail_ok,'config_quality_pass':exact and within_arm_repeat_exact,
            'app_speed_proven':False}

def battery(raw):
    text=raw.decode()
    found=re.search(r'^\s*temperature:\s*(\d+)\s*$',text,re.MULTILINE)
    ac=re.search(r'^\s*AC powered:\s*(true|false)\s*$',text,re.MULTILINE)
    usb=re.search(r'^\s*USB powered:\s*(true|false)\s*$',text,re.MULTILINE)
    wireless=re.search(r'^\s*Wireless powered:\s*(true|false)\s*$',text,re.MULTILINE)
    if not all((found,ac,usb,wireless)):raise ValueError('power/temperature unreadable')
    return {'temperatureC':int(found.group(1))/10,'power':tuple(m.group(1) for m in (ac,usb,wireless))}


def thermal(raw):
    match=re.search(r'^Thermal Status:\s*(\d+)\s*$',raw.decode(),re.MULTILINE)
    if not match:raise ValueError('thermal state unreadable')
    return int(match.group(1))

