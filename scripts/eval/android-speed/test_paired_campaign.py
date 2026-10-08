"""Harness Contract: adjacent comparisons cannot share unrelated baseline observations."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from paired_campaign import freeze,validate,FAMILIES
from run_bench import wait_for_start

class PairTest(unittest.TestCase):
    def test_complete_candidate_specific_pairs_and_counterbalanced_order(self):
        for family,count in [('asr',112),('s1',130)]:
            with self.subTest(family=family),tempfile.TemporaryDirectory() as folder:
                root=Path(folder);source=root/'development.jsonl'
                source.write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(count)))
                manifest=freeze(root,source,family);spec,cases=validate(manifest)
                for candidate in FAMILIES[family][1:]:
                    for repeat in range(3):
                        blocks=[b for b in spec['blocks'] if b['candidate']==candidate and b['repeat']==repeat]
                        ids=[]
                        for b in blocks:
                            rows=[json.loads(l) for l in Path(b['corpus']).read_text().splitlines()]
                            self.assertLessEqual(len(rows),8);ids.extend(r['id'] for r in rows)
                            self.assertEqual(set(b['arms']),{FAMILIES[family][0],candidate})
                        self.assertEqual(len(ids),count);self.assertEqual(set(ids),{str(i) for i in range(count)})
                    orders=[b['arms'] for b in spec['blocks'] if b['candidate']==candidate and b['number']==0]
                    self.assertEqual(orders[0],list(reversed(orders[1])))
                    self.assertEqual(orders[0],orders[2])
                original=json.loads(manifest.read_text())
                for field,value in [('thermalMax',2),('temperatureToleranceC',5),('blocks',original['blocks'][:-1]),('phase','acceptance')]:
                    bad=dict(original);bad[field]=value;manifest.write_text(json.dumps(bad))
                    with self.assertRaises(ValueError):validate(manifest)
    def test_normal_start_has_no_fixed_pause(self):
        class Phone:
            def shell(self,command):
                if command=='dumpsys thermalservice':return b'Thermal Status: 0\n'
                return b'temperature: 280\nAC powered: true\nUSB powered: false\nWireless powered: false\n'
        with tempfile.TemporaryDirectory() as folder,patch('run_bench.time.sleep') as sleep:
            result=wait_for_start(Phone(),Path(folder))
        self.assertEqual(result['temperatureC'],28.0);sleep.assert_not_called()
    def test_hot_phone_has_bounded_wait(self):
        class Phone:
            def shell(self,command):
                if command=='dumpsys thermalservice':return b'Thermal Status: 2\n'
                return b'temperature: 280\nAC powered: true\nUSB powered: false\nWireless powered: false\n'
        with tempfile.TemporaryDirectory() as folder,patch('run_bench.time.monotonic',side_effect=[0,0,901]),patch('run_bench.time.sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError,'frozen bound'):
                wait_for_start(Phone(),Path(folder))
        sleep.assert_called_once_with(5)

if __name__=='__main__':unittest.main()
