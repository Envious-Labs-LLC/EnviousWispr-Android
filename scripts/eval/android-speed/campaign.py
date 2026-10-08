"""Frozen development-only schedule; holds acceptance data closed until winner freeze."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import re
import time

from run_bench import Phone, run
from protocol import compare, sha_bytes, verify_rows, battery, thermal

SEED=43320261007
FAMILIES={'asr':['A0','A1','A3','A4','A5'],'s1':['B0','B1','B2']}


def wait_ready(phone,seconds=900):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if thermal(phone.shell('dumpsys thermalservice'))<=1:return
        print('Thermal status above matched-block limit; cooling.',flush=True)
        time.sleep(30)
    raise RuntimeError('environment did not become eligible within declared15minute cooling bound')


def freeze(root,corpus,family):
    raw=corpus.read_bytes();cases=[json.loads(x) for x in raw.decode().splitlines() if x.strip()]
    if family not in FAMILIES:raise ValueError('unknown family')
    # Development paths only: acceptance may never be run by this command.
    if 'acceptance' in corpus.name or not 'development' in corpus.name:raise ValueError('development-only campaign')
    expected=112 if family=='asr' else 130
    if len(cases)!=expected or len({r['id'] for r in cases})!=expected:raise ValueError('incomplete frozen development corpus')
    target=root/'development-campaign.json'
    if target.exists():raise ValueError('campaign already frozen; resume it, never overwrite')
    schedule=[]
    for repeat in range(3):
        ordered=list(cases);random.Random(SEED+repeat).shuffle(ordered)
        payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in ordered).encode()
        block=root/f'development-block-{repeat}.jsonl';block.write_bytes(payload)
        arms=list(FAMILIES[family])
        if repeat%2:arms.reverse()
        schedule.append({'repeat':repeat,'arms':arms,'corpus':str(block.resolve()),'sha256':sha_bytes(payload)})
    spec={'schema':1,'family':family,'sourceCorpusSha256':sha_bytes(raw),'sourceCorpus':str(corpus.resolve()),
          'seed':SEED,'schedule':schedule,'maxRerunsPerMatchedBlock':2,'thermalMax':1,'temperatureToleranceC':2,
          'phase':'development-only, not acceptance or app speed proof'}
    target.write_text(json.dumps(spec,indent=2)+'\n')
    return target


def execute(phone,bench,manifest):
    spec=json.loads(manifest.read_text())
    family=spec.get('family')
    if (spec.get('schema')!=1 or family not in FAMILIES or spec.get('seed')!=SEED
            or spec.get('phase')!='development-only, not acceptance or app speed proof'):
        raise ValueError('not the frozen development protocol')
    source=Path(spec['sourceCorpus'])
    if 'acceptance' in source.name or 'development' not in source.name:
        raise ValueError('development-only campaign')
    raw=source.read_bytes()
    if sha_bytes(raw)!=spec['sourceCorpusSha256']:raise ValueError('frozen corpus changed')
    canonical=[json.loads(x) for x in raw.decode().splitlines() if x.strip()]
    expected=112 if family=='asr' else 130
    if len(canonical)!=expected or len({r['id'] for r in canonical})!=expected:
        raise ValueError('incomplete frozen development corpus')
    settings={'maxRerunsPerMatchedBlock':2,'thermalMax':1,'temperatureToleranceC':2}
    if any(spec.get(k)!=v for k,v in settings.items()):raise ValueError('development environment rules changed')
    schedule=spec.get('schedule')
    if not isinstance(schedule,list) or len(schedule)!=3:raise ValueError('complete three-repeat schedule required')
    for repeat,block in enumerate(schedule):
        ordered=list(canonical);random.Random(SEED+repeat).shuffle(ordered)
        payload=''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in ordered).encode()
        arms=list(FAMILIES[family])
        if repeat%2:arms.reverse()
        corpus=Path(block['corpus'])
        if (block.get('repeat')!=repeat or block.get('arms')!=arms
                or corpus.name!=f'development-block-{repeat}.jsonl'
                or block.get('sha256')!=sha_bytes(payload) or sha_bytes(corpus.read_bytes())!=sha_bytes(payload)):
            raise ValueError('schedule is not the frozen development permutation')
    trial_sets={a:[] for a in FAMILIES[family]}
    index=manifest.with_suffix('.attempts.json')
    if index.exists():raise ValueError('explicit resume/import required; never replay old trials silently')
    attempts=[]
    for block in spec['schedule']:
        corpus=Path(block['corpus'])
        if sha_bytes(corpus.read_bytes())!=block['sha256']:raise ValueError('frozen block changed')
        cases=[json.loads(x) for x in corpus.read_text().splitlines() if x.strip()]
        valid_block=None
        for attempt in range(spec['maxRerunsPerMatchedBlock']+1):
            wait_ready(phone)
            group={'repeat':block['repeat'],'attempt':attempt,'runs':{},'valid':False}
            attempts.append(group);index.write_text(json.dumps(attempts,indent=2)+'\n')
            readings=[]
            collected={}
            for arm in block['arms']:
                path=run(phone,bench,corpus,arm)
                request=json.loads((path/'request.json').read_text());rows=[json.loads(x) for x in (path/'raw.jsonl').read_text().splitlines()]
                verdict=verify_rows(rows,cases,arm,request['runId'],block['sha256'])
                if not verdict['environment_valid']:group.setdefault('invalidReasons',[]).append('within-run thermal limit exceeded')
                readings += [battery((path/'battery-before.txt').read_bytes()),battery((path/'battery-after.txt').read_bytes())]
                group['runs'][arm]=str(path.resolve());collected[arm]=rows
                index.write_text(json.dumps(attempts,indent=2)+'\n')
            power={r['power'] for r in readings};temps=[r['temperatureC'] for r in readings]
            if len(power)!=1:group.setdefault('invalidReasons',[]).append('power changed')
            if max(temps)-min(temps)>spec['temperatureToleranceC']:group.setdefault('invalidReasons',[]).append('temperature mismatch')
            group['temperatureRangeC']=[min(temps),max(temps)];group['powerStates']=[list(p) for p in power]
            group['valid']=not group.get('invalidReasons')
            index.write_text(json.dumps(attempts,indent=2)+'\n')
            if group['valid']:
                valid_block=collected;break
            print('Whole matched block rejected; all attempts retained.',flush=True)
            time.sleep(30)
        if valid_block is None:raise RuntimeError('matched block exhausted its predeclared rerun allowance')
        for arm,rows in valid_block.items():
            by_id={r['id']:r for r in rows};trial_sets[arm].append([by_id[c['id']] for c in canonical])
    # Controls are quality guards, not extra independent timing samples.
    ix=[i for i,c in enumerate(canonical) if not c.get('control')]
    timing_cases=[canonical[i] for i in ix]
    baseline=FAMILIES[spec['family']][0]
    summary={}
    for arm in FAMILIES[spec['family']][1:]:
        b=[[r[i] for i in ix] for r in trial_sets[baseline]];c=[[r[i] for i in ix] for r in trial_sets[arm]]
        result=compare(timing_cases,b,c)
        controls=[i for i,row in enumerate(canonical) if 'expectedText' in row]
        result['noise_guard_pass']=all(trial[i]['text']==canonical[i].get('expectedText','') for trial in trial_sets[arm] for i in controls)
        result['all_outputs_equal']=all(base[i]['text']==candidate[i]['text'] for base,candidate in zip(trial_sets[baseline],trial_sets[arm]) for i in range(len(canonical)))
        result['padding_screening_only']=arm in ('A3','A4','A5')
        result['campaign_acceptance']=False
        summary[arm]=result
    (manifest.with_suffix('.summary.json')).write_text(json.dumps(summary,indent=2)+'\n')
    print('Development comparison completed; app_speed_proven=false.',flush=True)
    return summary

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);subs=p.add_subparsers(dest='action',required=True)
    f=subs.add_parser('freeze');f.add_argument('--root',required=True,type=Path);f.add_argument('--corpus',required=True,type=Path);f.add_argument('--family',choices=FAMILIES,required=True)
    e=subs.add_parser('execute');e.add_argument('--serial',required=True);e.add_argument('--bench',required=True,type=Path);e.add_argument('--manifest',required=True,type=Path)
    a=p.parse_args()
    if a.action=='freeze':print(freeze(a.root,a.corpus,a.family))
    else:execute(Phone(a.serial),a.bench,a.manifest)
