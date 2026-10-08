"""One explicitly identified native block; no production APK/data/permission changes."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid

from build_bench import PACKAGE, REPO
from protocol import ARMS, ASR, sha_bytes, verify_rows, battery, thermal
sys.path.insert(0,str(REPO/'scripts/uat'))
import wispr_eyes as eyes

class Phone:
    def __init__(self, serial): self.serial=serial
    def adb(self,*args,timeout=30,input=None):
        stream = input if hasattr(input, 'read') else None
        payload = None if stream is not None else input
        return subprocess.run([eyes.ADB,'-s',self.serial,*args],input=payload,stdin=stream,stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True,timeout=timeout)
    def shell(self,command,timeout=30):
        marker='\nBENCH_RC='
        result=self.adb('shell','-T',command+'; printf \"\\nBENCH_RC=%s\\n\" \"$?\"',timeout=timeout).stdout
        body,sep,code=result.rpartition(marker.encode())
        if not sep or code.strip()!=b'0':raise RuntimeError('remote operation failed: '+repr(result))
        return body
    def owned(self,command,input=None,timeout=30):
        # Payload bytes go through stdin, never through a shell string.
        wrapped=shlex.join(['run-as',PACKAGE,'sh','-c',command+'; printf \"\\nBENCH_RC=%s\\n\" \"$?\"'])
        result=self.adb('shell','-T',wrapped,input=input,timeout=timeout).stdout
        body,sep,code=result.rpartition(b'\nBENCH_RC=')
        if not sep or code.strip()!=b'0':raise RuntimeError('owned bench operation failed')
        return body
    def copy(self,local,target):
        if not target.startswith('files/') or '..' in Path(target).parts:raise ValueError('invalid owned target')
        with local.open('rb') as stream:
            self.owned('cat > '+shlex.quote(target),input=stream,timeout=180)
        actual=self.owned('sha256sum '+shlex.quote(target)).decode().split()[0]
        with local.open('rb') as stream:expected=hashlib.file_digest(stream,'sha256').hexdigest()
        if actual!=expected:raise RuntimeError('staged byte identity mismatch')


def preflight(phone):
    eyes.device(phone.serial)
    if not eyes.ready():raise RuntimeError('physical phone not ready')
    # Bench has no recording path. Refuse an existing visible recorder before stealing focus.
    visible=eyes.overlay()
    if visible is not None:
        raise RuntimeError('recorder window exists; bench refuses to steal focus')
    services=phone.shell('dumpsys activity services com.envi.wispr').decode()
    if any('ServiceRecord{' in line and 'DictationSessionService' in line for line in services.splitlines()):
        raise RuntimeError('production session service is live; bench refuses an overlapping take')
    production=phone.shell('dumpsys package com.envi.wispr').decode()
    return {'productionPackageSnapshot':production,'accessibilityBound':eyes.bound()}


def production_identity(snapshot):
    code=re.search(r"versionCode=(\d+)",snapshot)
    updated=re.search(r"lastUpdateTime=([^\n]+)",snapshot)
    if not code or not updated:raise ValueError('production installation identity unreadable')
    return {'versionCode':int(code.group(1)), 'lastUpdateTime':updated.group(1).strip()}


def stage(phone,root,apk,models,corpus):
    if (root/'installed.json').exists():raise ValueError('staging receipt already exists; do not overwrite')
    before=preflight(phone)
    if production_identity(before['productionPackageSnapshot'])['versionCode']!=270:
        raise ValueError('expected frozen Play270 baseline before bench staging')
    # Actual merged APK permissions, never inferred from the source manifest.
    aapt=Path('/Users/m4pro_sv/Android/sdk/build-tools/34.0.0/aapt2')
    permission_dump=subprocess.check_output([str(aapt),'dump','permissions',str(apk)],text=True)
    if PACKAGE not in permission_dump or any(p in permission_dump for p in ('android.permission.INTERNET','android.permission.RECORD_AUDIO','android.permission.MODIFY_AUDIO_SETTINGS')):
        raise ValueError('merged bench APK identity/permissions violate the isolated contract')
    phone.adb('install','-r',str(apk),timeout=90)
    paths=phone.shell('pm path '+PACKAGE).decode().splitlines()
    if len(paths)!=1 or not paths[0].startswith('package:'):raise RuntimeError('unexpected bench APK topology')
    actual=phone.shell('sha256sum '+shlex.quote(paths[0][8:])).decode().split()[0]
    with apk.open('rb') as stream: expected=hashlib.file_digest(stream,'sha256').hexdigest()
    if actual!=expected:raise RuntimeError('installed APK differs from built artifact')
    phone.owned('mkdir -p files/models files/audio files/results')
    for name,local in models.items():
        if name not in ('encoder-model.int8.onnx','decoder_joint-model.int8.onnx','vocab.txt','s1-mini-q4_k_m.gguf'):raise ValueError('unreviewed model')
        phone.copy(Path(local),'files/models/'+name)
    for group in ('development','acceptance'):
        phone.copy(corpus/(group+'.jsonl'),'files/'+group+'.jsonl')
    for pcm in sorted((corpus/'audio').glob('*.pcm')):
        phone.copy(pcm,'files/audio/'+pcm.name)
    receipt={'apkSha256':expected,'installedApkSha256':actual,'package':PACKAGE,'before':before,
             'productionPackageSnapshotAfter':phone.shell('dumpsys package com.envi.wispr').decode()}
    (root/'installed.json').write_text(json.dumps(receipt,indent=2)+'\n')
    if production_identity(receipt['before']['productionPackageSnapshot'])!=production_identity(receipt['productionPackageSnapshotAfter']):
        raise RuntimeError('production installation changed during staging')
    print('Bench staged; installed APK and every input verified.',flush=True)


def wait_for_start(phone,target,anchor=None):
    # Final preparation boundary: no input transfer or APK check follows this wait.
    deadline=time.monotonic()+900
    while time.monotonic()<deadline:
        thermal_raw=phone.shell('dumpsys thermalservice')
        battery_raw=phone.shell('dumpsys battery')
        (target/'thermal-before.txt').write_bytes(thermal_raw)
        (target/'battery-before.txt').write_bytes(battery_raw)
        state=thermal(thermal_raw);reading=battery(battery_raw)
        if anchor is not None and reading['power']!=anchor['power']:
            raise RuntimeError('charging changed while matching start temperature')
        matched=anchor is None or abs(reading['temperatureC']-anchor['temperatureC'])<=0.5
        if state==0 and matched:return reading
        print('Cooling before native launch to normal status and matched temperature.',flush=True)
        time.sleep(5)
    raise RuntimeError('phone did not cool to matched start within frozen bound')


def run(phone,root,corpus,arm,start_anchor=None):
    if arm not in ARMS:raise ValueError('unknown/excluded arm')
    before=preflight(phone)
    installed=json.loads((root/'installed.json').read_text())
    if production_identity(before['productionPackageSnapshot'])!=production_identity(installed['before']['productionPackageSnapshot']):
        raise RuntimeError('production baseline installation changed')
    paths=phone.shell('pm path '+PACKAGE).decode().splitlines()
    if len(paths)!=1 or not paths[0].startswith('package:'):
        raise RuntimeError('installed bench topology changed')
    if phone.shell('sha256sum '+shlex.quote(paths[0][8:])).decode().split()[0]!=installed['apkSha256']:
        raise RuntimeError('executed bench APK changed')
    run_id=str(uuid.uuid4())
    target=root/'runs'/run_id; target.mkdir(parents=True,exist_ok=False)
    raw=corpus.read_bytes(); cases=[json.loads(x) for x in raw.decode().splitlines() if x.strip()]
    corpus_hash=sha_bytes(raw)
    if not cases:raise ValueError('empty corpus')
    snapshot={'runId':run_id,'arm':arm,'corpusSha256':corpus_hash,'sourceCases':str(corpus),
              'before':before,'startedHostMonotonic':time.monotonic()}
    (target/'request.json').write_text(json.dumps(snapshot,indent=2)+'\n')
    phone.shell('am force-stop '+PACKAGE)
    phone.copy(corpus,'files/block-'+run_id+'.jsonl')
    (target/'battery-before.txt').write_bytes(phone.shell('dumpsys battery'))
    (target/'thermal-before.txt').write_bytes(phone.shell('dumpsys thermalservice'))
    command=shlex.join(['am','start','-n',PACKAGE+'/.BenchActivity','--es','run_id',run_id,'--es','arm',arm,
                        '--es','corpus','block-'+run_id+'.jsonl','--es','corpus_sha256',corpus_hash,'--ei','warmups','1'])
    try:
        wait_for_start(phone,target,start_anchor)
        phone.shell(command)
        deadline=time.monotonic()+330
        status='NOT_STARTED'
        # Only the subject's UUID completion signal permits an output comparison.
        while time.monotonic()<deadline:
            status=phone.owned('if [ -f files/status-'+run_id+'.txt ]; then cat files/status-'+run_id+'.txt; else echo NOT_STARTED; fi').decode().strip()
            if status==f'COMPLETED:{run_id}:{arm}:{corpus_hash}':break
            if status.startswith('FAILED:'):raise RuntimeError(status)
            time.sleep(1)
        else:raise TimeoutError('observer deadline waiting for exact subject completion; last signal='+status)
        payload=phone.owned('cat files/results/'+run_id+'.jsonl')
        (target/'raw.jsonl').write_bytes(payload)
        warmup=phone.owned('cat files/results/'+run_id+'.warmup.json')
        (target/'warmup.json').write_bytes(warmup)
        rows=[json.loads(x) for x in payload.decode().splitlines() if x.strip()]
        verdict=verify_rows(rows,cases,arm,run_id,corpus_hash)
        (target/'battery-after.txt').write_bytes(phone.shell('dumpsys battery'))
        (target/'thermal-after.txt').write_bytes(phone.shell('dumpsys thermalservice'))
        (target/'verified.json').write_text(json.dumps(verdict,indent=2)+'\n')
        print('Completed',arm,'rows',len(rows),'environment_valid',verdict['environment_valid'],'run',run_id,flush=True)
        return target
    except Exception as failure:
        try:
            partial=phone.owned('if [ -f files/results/'+run_id+'.jsonl ]; then cat files/results/'+run_id+'.jsonl; fi')
            (target/'partial.jsonl').write_bytes(partial)
        except Exception as export_failure:
            (target/'partial-export-failure.txt').write_text(type(export_failure).__name__)
        (target/'failure.json').write_text(json.dumps({'kind':type(failure).__name__,'status':str(failure)},indent=2)+'\n')
        raise
    finally:
        try:
            phone.shell('am force-stop '+PACKAGE)
        finally:
            try:
                eyes.open_app('com.envi.wispr')
            finally:
                eyes.restore()

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--root',required=True,type=Path)
    subs=parser.add_subparsers(dest='action',required=True)
    s=subs.add_parser('stage');s.add_argument('--apk',required=True,type=Path);s.add_argument('--models',required=True,type=Path);s.add_argument('--corpus-root',required=True,type=Path)
    r=subs.add_parser('run');r.add_argument('--corpus',required=True,type=Path);r.add_argument('--arm',choices=sorted(ARMS),required=True)
    args=parser.parse_args();phone=Phone(args.serial)
    if args.action=='stage':stage(phone,args.root,args.apk,json.loads(args.models.read_text()),args.corpus_root)
    else:run(phone,args.root,args.corpus,args.arm)
