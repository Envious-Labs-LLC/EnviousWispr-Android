"""Harness Contract: empty stdout and file streams are not transport failures."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from run_bench import Phone

class LocalShell(Phone):
    def adb(self,*args,**kwargs):
        return subprocess.run(['sh','-c',args[-1]],stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=True)

class TransportTest(unittest.TestCase):
    def test_zero_output_and_binary_without_final_newline(self):
        p=LocalShell('none')
        self.assertEqual(p.shell('true'),b'')
        self.assertEqual(p.shell("printf 'binary payload'"),b'binary payload')
        self.assertEqual(p.shell("printf 'line\\n'"),b'line\n')
    def test_real_remote_failure_is_not_success(self):
        with self.assertRaises(RuntimeError):LocalShell('none').shell('false')
    def test_file_uses_stdin_not_communicate_bytes(self):
        with tempfile.TemporaryFile() as stream,patch('run_bench.subprocess.run') as run:
            stream.write(b'controlled file');stream.seek(0)
            Phone('none').adb('shell','-T','cat',input=stream)
            self.assertIs(run.call_args.kwargs['stdin'],stream)
            self.assertIsNone(run.call_args.kwargs['input'])

if __name__=='__main__':unittest.main()
