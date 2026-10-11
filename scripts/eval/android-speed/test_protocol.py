"""Harness Contract: a wrong/stale/incomplete trial must never become speed proof."""
import copy
import unittest
from protocol import verify_rows, lexical_tokens, edits, compare

class ProtocolTest(unittest.TestCase):
    def fixture(self):
        case={'id':'clip-a','pcmSha256':'a'*64,'shape':'short','cluster':'speaker-a'}
        row={'schema':1,'id':'clip-a','runId':'operation','arm':'A0','corpusSha256':'b'*64,'rowIndex':0,'ms':400.0,'loadMs':1000.0,'warmups':1,'nativeState':'resident-inference-after-one-excluded-warmup','text':'The final word survives.','thermalBefore':0,'thermalAfter':0,'backend':'CPU','modelClass':'com.envi.wispr.asr.TdtModel','threads':4,'pcmSha256':'a'*64,'observedInputs':[{'realSamples':64000,'tensorSamples':240000}],'paddingArm':'A0','spinning':'runtime-default'}
        return case,row
    def test_wrong_or_incomplete_trial_refused(self):
        case,row=self.fixture()
        for key,value in [('runId','stale'),('arm','A1'),('corpusSha256','c'*64),('rowIndex',1),('ms',float('nan')),('backend','GPU'),('modelClass','wrong.factory.Model'),('pcmSha256','c'*64),('threads',6),('warmups',0),('observedInputs',[{'realSamples':64000,'tensorSamples':64000}]),('thermalAfter',None),('text',None)]:
            broken=copy.deepcopy(row);broken[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError): verify_rows([broken],[case],'A0','operation','b'*64)
        with self.assertRaises(ValueError): verify_rows([], [case], 'A0','operation','b'*64)
        with self.assertRaises(ValueError): verify_rows([row,row],[case],'A0','operation','b'*64)
    def test_heat_is_invalid_environment_not_a_passing_block(self):
        case,row=self.fixture(); row['thermalAfter']=2
        self.assertFalse(verify_rows([row],[case],'A0','operation','b'*64)['environment_valid'])
    def test_reference_does_not_conflate_numbers_or_spelling(self):
        self.assertEqual(lexical_tokens('CAFÉ, colour and 1500.'),['café','colour','and','1500'])
        self.assertEqual(edits(['one','two','three'],['one','two','four']),1)
        self.assertEqual(edits(['colour'],['color']),1)
        self.assertEqual(edits(['one'],['1']),1)
        self.assertEqual(edits(['keep','the','ending'],['keep','the']),1)
    def trials(self, time):
        return [[{'id':str(i),'ms':float(time),'text':'Exact fixed text.','thermalBefore':0,'thermalAfter':0} for i in range(12)] for _ in range(3)]
    def test_large_consistent_gain_is_engine_only(self):
        cases=[{'id':str(i),'cluster':str(i),'shape':'short'} for i in range(12)]
        result=compare(cases,self.trials(400),self.trials(200),draws=200)
        self.assertEqual(result['paired_median_reduction'],.5)
        self.assertTrue(result['engine_target_pass'] and result['config_quality_pass'])
        self.assertFalse(result['app_speed_proven'])
    def test_fast_wrong_text_cannot_pass_quality(self):
        cases=[{'id':str(i),'cluster':str(i),'shape':'short'} for i in range(12)]
        candidate=self.trials(200); candidate[1][0]['text']='Wrong final word.'
        self.assertFalse(compare(cases,self.trials(400),candidate,draws=200)['config_quality_pass'])
    def test_repeated_latency_spikes_are_not_hidden_by_input_medians(self):
        cases=[{'id':str(i),'cluster':str(i),'shape':'short'} for i in range(12)]
        candidate=self.trials(200)
        for row in candidate[0]:row['ms']=5000.0
        self.assertFalse(compare(cases,self.trials(400),candidate,draws=200)['engine_target_pass'])
    def test_precision_cannot_be_faked_with_repeated_one_input(self):
        cases=[{'id':str(i),'cluster':'same-speaker','shape':'short'} for i in range(12)]
        with self.assertRaises(ValueError):compare(cases,self.trials(400),self.trials(200),draws=200)

if __name__=='__main__':unittest.main()
