"""Materialize a frozen human-reference selection without inspecting model outcomes."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import pyarrow.parquet as pq
from protocol import sha_bytes


def prepare(selection, root):
    spec=json.loads(selection.read_text())
    source=Path(spec['source'])
    with source.open('rb') as stream:
        if hashlib.file_digest(stream,'sha256').hexdigest()!=spec['source_sha256']:
            raise ValueError('source dataset identity changed')
    if root.exists():
        raise ValueError('corpus freezes are immutable; choose a new output root')
    audio=root/'audio'; audio.mkdir(parents=True)
    records={r['id']:r for r in pq.read_table(source).to_pylist()}
    pcm_hashes=set(); references=set(); manifests={}
    for group in ('development','acceptance'):
        cases=[]
        for selected in spec['selected'][group]:
            row=records[selected['id']]
            raw=row['audio']['bytes']
            if sha_bytes(raw)!=selected['source_sha256']:
                raise ValueError('selected source recording changed')
            identity='ls-'+row['id']
            flac=audio/(identity+'.flac'); pcm=audio/(identity+'.pcm')
            flac.write_bytes(raw)
            subprocess.run(['ffmpeg','-nostdin','-v','error','-i',str(flac),'-ar','16000','-ac','1','-f','s16le',str(pcm)],check=True)
            data=pcm.read_bytes(); digest=sha_bytes(data)
            if len(data)%2 or abs(len(data)/32-selected['duration_ms'])>1:
                raise ValueError('PCM format/duration mismatch')
            # Exact text dedup is case-insensitive and NFC; do not normalize meaning.
            import unicodedata
            reference=' '.join(unicodedata.normalize('NFC',row['text']).casefold().split())
            if digest in pcm_hashes or reference in references:
                raise ValueError('duplicate recording/reference across frozen groups')
            pcm_hashes.add(digest); references.add(reference)
            lo=selected['stratum'][0]
            cases.append({'id':identity,'cluster':selected['speaker'],'chapter':selected['chapter'],
                          'shape':'short' if lo<5 else 'medium' if lo<15 else 'long',
                          'pcmSha256':digest,'referenceSha256':sha_bytes(reference.encode()),
                          'gold':row['text'],'durationMs':len(data)/32,
                          'source':'LibriSpeech-test-clean-human-read','sourceId':row['id']})
        raw=(''.join(json.dumps(c,ensure_ascii=False)+'\n' for c in cases)).encode()
        (root/(group+'.jsonl')).write_bytes(raw)
        manifests[group]={'sha256':sha_bytes(raw),'cases':len(cases),
                          'representedSpeakers':len({c['cluster'] for c in cases}),
                          'pcmHashes':{c['id']:c['pcmSha256'] for c in cases},
                          'referenceHashes':{c['id']:c['referenceSha256'] for c in cases}}
    (root/'manifest.json').write_text(json.dumps({'sourceSha256':spec['source_sha256'],
        'selectionSha256':sha_bytes(selection.read_bytes()),'groups':manifests,
        'limits':'Human read-speech, not conversational/multilingual or training-independence proof.'},indent=2)+'\n')
    return manifests

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection',required=True,type=Path)
    parser.add_argument('--root',required=True,type=Path)
    args=parser.parse_args()
    result=prepare(args.selection,args.root)
    print({g:{k:v for k,v in r.items() if k in ('cases','representedSpeakers','sha256')} for g,r in result.items()})
