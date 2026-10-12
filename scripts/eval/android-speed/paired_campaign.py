"""Development-only adjacent baseline/candidate pairs; no pooled distant baseline."""
import argparse
import json
from pathlib import Path
import random
import time

from campaign import SEED
FAMILIES={'asr':['A0','A1'],'s1':['B0','B1','B2']}
from protocol import battery,thermal
from protocol import sha_bytes,verify_rows,compare
from run_bench import Phone,run

PHASE='development adjacent pairs; no acceptance or app-speed proof'
BLOCK_SIZE=8


def payload(rows):return ''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in rows).encode()


def schedule(cases,family):
    result=[]
    for repeat in range(3):
        ordered=list(cases);random.Random(SEED+repeat).shuffle(ordered)
        baseline=FAMILIES[family][0]
        for number,start in enumerate(range(0,len(ordered),BLOCK_SIZE)):
            for candidate_index,candidate in enumerate(FAMILIES[family][1:]):
                arms=[baseline,candidate]
                if (repeat+number+candidate_index)%2:arms.reverse()
                result.append((repeat,number,candidate,arms,ordered[start:start+BLOCK_SIZE]))
    return result


def freeze(root,corpus,family):
    if family not in FAMILIES or 'acceptance' in corpus.name or 'development' not in corpus.name:
        raise ValueError('development only')
    raw=corpus.read_bytes();cases=[json.loads(l) for l in raw.decode().splitlines()]
    required=112 if family=='asr' else 130
    if len(cases)!=required or len({r['id'] for r in cases})!=required:raise ValueError('incomplete development corpus')
    manifest=root/'paired-campaign.json'
    if manifest.exists():raise ValueError('do not overwrite a freeze')
    blocks=[]
    for repeat,number,candidate,arms,subset in schedule(cases,family):
        data=payload(subset);path=root/f'development-{repeat}-{number}-{candidate}.jsonl';path.write_bytes(data)
        blocks.append({'repeat':repeat,'number':number,'candidate':candidate,'arms':arms,'corpus':str(path.resolve()),'sha256':sha_bytes(data)})
    spec={'schema':4,'phase':PHASE,'family':family,'seed':SEED,'blockSize':BLOCK_SIZE,'source':str(corpus.resolve()),
          'sourceSha256':sha_bytes(raw),'thermalMax':1,'launchThermalMax':0,'temperatureToleranceC':2,'maxReruns':2,
          'coolingBoundSeconds':900,'blocks':blocks}
    manifest.write_text(json.dumps(spec,indent=2)+'\n');return manifest


def validate(manifest):
    spec=json.loads(manifest.read_text());family=spec.get('family')
    fixed={'schema':4,'phase':PHASE,'seed':SEED,'blockSize':8,'thermalMax':1,'launchThermalMax':0,
           'temperatureToleranceC':2,'maxReruns':2,'coolingBoundSeconds':900}
    if family not in FAMILIES or any(spec.get(k)!=v for k,v in fixed.items()):raise ValueError('protocol changed')
    source=Path(spec['source'])
    if 'acceptance' in source.name or 'development' not in source.name:raise ValueError('wrong phase')
    raw=source.read_bytes()
    if sha_bytes(raw)!=spec['sourceSha256']:raise ValueError('source changed')
    cases=[json.loads(l) for l in raw.decode().splitlines()]
    required=112 if family=='asr' else 130
    if len(cases)!=required or len({r['id'] for r in cases})!=required:raise ValueError('incomplete source')
    expected=schedule(cases,family)
    if len(spec.get('blocks',[]))!=len(expected):raise ValueError('missing matched blocks')
    for block,(repeat,number,candidate,arms,subset) in zip(spec['blocks'],expected):
        path=Path(block['corpus']);data=payload(subset)
        if (block.get('repeat')!=repeat or block.get('number')!=number or block.get('candidate')!=candidate or block.get('arms')!=arms
                or path.name!=f'development-{repeat}-{number}-{candidate}.jsonl'
                or block.get('sha256')!=sha_bytes(data) or path.read_bytes()!=data):raise ValueError('schedule/permutation changed')
    return spec,cases


def execute(phone,bench,manifest):
    spec,cases=validate(manifest)
    index=manifest.with_suffix('.attempts.json')
    if index.exists():raise ValueError('explicit resume required, never replay silently')
    baseline=FAMILIES[spec['family']][0]
    attempts=[];trials={candidate:{arm:[{}, {}, {}] for arm in (baseline,candidate)} for candidate in FAMILIES[spec['family']][1:]}
    for block in spec['blocks']:
        subset=[json.loads(l) for l in Path(block['corpus']).read_text().splitlines()]
        accepted=None
        for attempt in range(3):
            group={'repeat':block['repeat'],'block':block['number'],'candidate':block['candidate'],'attempt':attempt,'runs':{},'valid':False}
            attempts.append(group);index.write_text(json.dumps(attempts,indent=2)+'\n')
            collected={};readings=[]
            for arm in block['arms']:
                path=run(phone,bench,Path(block['corpus']),arm)
                request=json.loads((path/'request.json').read_text());rows=[json.loads(l) for l in (path/'raw.jsonl').read_text().splitlines()]
                result=verify_rows(rows,subset,arm,request['runId'],block['sha256'])
                group['runs'][arm]=str(path.resolve());collected[arm]=rows
                launch=thermal((path/'thermal-before.txt').read_bytes())
                ended=thermal((path/'thermal-after.txt').read_bytes())
                group.setdefault('thermalEndpoints',{})[arm]=[launch,ended]
                if launch!=0 or ended>1:group['invalidReason']='launch/end thermal limit'
                started=battery((path/'battery-before.txt').read_bytes())
                finished=battery((path/'battery-after.txt').read_bytes())
                group.setdefault('startReadings',{})[arm]=started
                if readings and (
                    started['power']!=readings[0]['power']
                    or abs(started['temperatureC']-readings[0]['temperatureC'])>spec['temperatureToleranceC']
                ):
                    group['invalidReason']='recorded start temperature/power mismatch'
                readings += [started,finished]
                index.write_text(json.dumps(attempts,indent=2)+'\n')
                if group.get('invalidReason') or not result['environment_valid']:
                    group.setdefault('invalidReason','within-run thermal limit');break
                temps=[r['temperatureC'] for r in readings]
                if len({r['power'] for r in readings})!=1 or max(temps)-min(temps)>2:
                    group['invalidReason']='matched power/temperature mismatch';break
            group['valid']=not group.get('invalidReason') and len(collected)==len(block['arms'])
            index.write_text(json.dumps(attempts,indent=2)+'\n')
            if group['valid']:accepted=collected;break
            print('Rejected short matched block; no remaining hot arms run.',flush=True)
            time.sleep(5)
        if accepted is None:raise RuntimeError('matched microblock exhausted frozen allowance')
        for arm,rows in accepted.items():
            target=trials[block['candidate']][arm][block['repeat']]
            for row in rows:
                if row['id'] in target:raise ValueError('duplicate accepted observation')
                target[row['id']]=row
        print('Accepted adjacent pair',block['candidate'],block['repeat'],block['number'],flush=True)
    ids={c['id'] for c in cases}
    if any(set(t)!=ids for pair in trials.values() for arm in pair.values() for t in arm):raise ValueError('incomplete repeat')
    ix=[i for i,c in enumerate(cases) if not c.get('control')];timing=[cases[i] for i in ix]
    summary={}
    for candidate,pair in trials.items():
        ordered={arm:[[t[c['id']] for c in cases] for t in trial] for arm,trial in pair.items()}
        result=compare(timing,[[t[i] for i in ix] for t in ordered[baseline]],[[t[i] for i in ix] for t in ordered[candidate]])
        result['all_outputs_equal']=all(b[i]['text']==c[i]['text'] for b,c in zip(ordered[baseline],ordered[candidate]) for i in range(len(cases)))
        control=[i for i,c in enumerate(cases) if 'expectedText' in c]
        result['noise_guard_pass']=all(t[i]['text']==cases[i]['expectedText'] for t in ordered[candidate] for i in control)
        result['baselinePairing']='candidate-specific adjacent; no baseline pooling'
        result['campaign_acceptance']=False
        summary[candidate]=result
    manifest.with_suffix('.summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    print('Completed development only; app speed not proven.',flush=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);subs=parser.add_subparsers(dest='action',required=True)
    f=subs.add_parser('freeze');f.add_argument('--root',type=Path,required=True);f.add_argument('--corpus',type=Path,required=True);f.add_argument('--family',choices=FAMILIES,required=True)
    e=subs.add_parser('execute');e.add_argument('--serial',required=True);e.add_argument('--bench',type=Path,required=True);e.add_argument('--manifest',type=Path,required=True)
    args=parser.parse_args()
    if args.action=='freeze':print(freeze(args.root,args.corpus,args.family))
    else:execute(Phone(args.serial),args.bench,args.manifest)
