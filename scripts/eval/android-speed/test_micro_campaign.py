"""Harness Contract: duty-cycle changes preserve full paired coverage and strict limits."""
import json
from pathlib import Path
import tempfile
import unittest
from micro_campaign import freeze,validate,execute
from run_bench import wait_for_start
from unittest.mock import patch

class MicroTest(unittest.TestCase):
    def test_partition_has_every_case_once_per_repeat_and_all_arms(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'development.jsonl';source.write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(112)))
            manifest=freeze(root,source,'asr');spec,cases=validate(manifest)
            self.assertEqual(len(spec['blocks']),42)
            for repeat in range(3):
                ids=[]
                for block in spec['blocks']:
                    if block['repeat']==repeat:
                        subset=[json.loads(l) for l in Path(block['corpus']).read_text().splitlines()]
                        self.assertLessEqual(len(subset),8);ids += [r['id'] for r in subset]
                        self.assertEqual(set(block['arms']),{'A0','A1','A3','A4','A5'})
                self.assertEqual(len(ids),112);self.assertEqual(set(ids),{str(i) for i in range(112)})
    def test_weakened_limits_and_missing_block_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'development.jsonl';source.write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(112)))
            manifest=freeze(root,source,'asr');original=json.loads(manifest.read_text())
            for key,value in [('thermalMax',2),('launchThermalMax',1),('temperatureToleranceC',5),('startTemperatureToleranceC',2),('blocks',original['blocks'][:-1]),('phase','acceptance')]:
                bad=dict(original);bad[key]=value;manifest.write_text(json.dumps(bad))
                with self.subTest(key=key),self.assertRaises(ValueError):validate(manifest)

    def test_normal_status_still_waits_for_matched_temperature(self):
        class Device:
            def __init__(self):self.samples=iter([31.0,29.4]);self.reads=0
            def shell(self,command):
                if command=='dumpsys thermalservice':return b'Thermal Status: 0\n'
                self.reads+=1
                return ('temperature: %d\nAC powered: true\nUSB powered: false\nWireless powered: false\n'%round(next(self.samples)*10)).encode()
        phone=Device()
        with patch('run_bench.time.sleep') as sleeping:
            with tempfile.TemporaryDirectory() as folder:
                result=wait_for_start(phone,Path(folder),{'temperatureC':29.0,'power':('true','false','false')})
                self.assertIn('294', (Path(folder)/'battery-before.txt').read_text())
        self.assertEqual(phone.reads,2);sleeping.assert_called_once_with(5)
        self.assertEqual(result['temperatureC'],29.4)
    def test_charging_change_refuses_before_launch(self):
        class Device:
            def shell(self,command):
                if command=='dumpsys thermalservice':return b'Thermal Status: 0\n'
                return b'temperature: 290\nAC powered: false\nUSB powered: false\nWireless powered: false\n'
        with self.assertRaisesRegex(RuntimeError,'charging changed'):
            with tempfile.TemporaryDirectory() as folder:
                wait_for_start(Device(),Path(folder),{'temperatureC':29.0,'power':('true','false','false')})

    def test_recorded_start_drift_rejects_whole_group_before_next_arm(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'development.jsonl'
            source.write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(112)))
            manifest=freeze(root,source,'asr');calls=[]
            def fake_run(phone,bench,corpus,arm,start_anchor=None):
                calls.append(arm);out=root/str(len(calls));out.mkdir()
                (out/'request.json').write_text(json.dumps({'runId':'controlled'}))
                (out/'raw.jsonl').write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(8)))
                for endpoint in ('before','after'):
                    (out/f'thermal-{endpoint}.txt').write_text('Thermal Status: 0\n')
                    # Outside the start half-degree rule, inside whole-block two-degree rule.
                    value=300 if arm=='A0' else 310
                    (out/f'battery-{endpoint}.txt').write_text(f'temperature: {value}\nAC powered: true\nUSB powered: false\nWireless powered: false\n')
                return out
            with patch('micro_campaign.run',side_effect=fake_run),patch('micro_campaign.verify_rows',return_value={'environment_valid':True}),patch('micro_campaign.time.sleep'):
                with self.assertRaisesRegex(RuntimeError,'exhausted'):
                    execute(None,None,manifest)
            self.assertEqual(calls,['A0','A1','A0','A1','A0','A1'])
            attempts=json.loads(manifest.with_suffix('.attempts.json').read_text())
            self.assertTrue(all(not group['valid'] for group in attempts))
            self.assertTrue(all(group['invalidReason']=='recorded start temperature/power mismatch' for group in attempts))
            self.assertFalse(manifest.with_suffix('.summary.json').exists())

if __name__=='__main__':unittest.main()
