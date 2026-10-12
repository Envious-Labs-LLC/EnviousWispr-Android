"""Harness Contract: frozen scheduling and environment are independent authorities."""
import json
from pathlib import Path
import tempfile
import unittest
from campaign import battery,thermal,freeze,execute
from unittest.mock import patch

class CampaignTest(unittest.TestCase):
    def test_live_header_not_old_history(self):
        raw=b'  AC powered: true\n  USB powered: false\n  Wireless powered: false\n  temperature: 350\nold history temperature:410\n'
        self.assertEqual(battery(raw),{'temperatureC':35.0,'power':('true','false','false')})
        self.assertEqual(thermal(b'Thermal Status: 1\n'),1)
        with self.assertRaises(ValueError):thermal(b'no thermal response')
        with self.assertRaises(ValueError):battery(b'no live power fields')
    def test_frozen_schedule_complete_and_no_heldout_execution(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);corpus=root/'development.jsonl'
            corpus.write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(112)))
            result=freeze(root,corpus,'asr');spec=json.loads(result.read_text())
            self.assertEqual(len(spec['schedule']),3)
            self.assertEqual(spec['schedule'][0]['arms'],['A0','A1','A3','A4','A5'])
            self.assertEqual(spec['schedule'][1]['arms'],['A5','A4','A3','A1','A0'])
            with self.assertRaises(ValueError):freeze(root,corpus,'asr')
            held=root/'acceptance.jsonl';held.write_bytes(corpus.read_bytes())
            with self.assertRaises(ValueError):freeze(root,held,'asr')

    def test_bad_manifest_is_rejected_before_phone_action(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);corpus=root/'development.jsonl'
            corpus.write_text(''.join(json.dumps({'id':str(i)})+'\n' for i in range(112)))
            manifest=freeze(root,corpus,'asr');original=json.loads(manifest.read_text())
            for key,value in [('phase','acceptance'),('maxRerunsPerMatchedBlock',50),('schedule',[])]:
                broken=dict(original);broken[key]=value;manifest.write_text(json.dumps(broken))
                with self.subTest(key=key),patch('campaign.run') as run:
                    with self.assertRaises(ValueError):execute(object(),root,manifest)
                    run.assert_not_called()
            manifest.write_text(json.dumps(original))
            original['schedule'][0]['arms']=['A0','A1'];manifest.write_text(json.dumps(original))
            with patch('campaign.run') as run:
                with self.assertRaises(ValueError):execute(object(),root,manifest)
                run.assert_not_called()

if __name__=='__main__':unittest.main()
