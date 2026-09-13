"""Fail closed on missing or failed test results; report actual executed counts."""
from pathlib import Path
import xml.etree.ElementTree as ET
files = sorted(Path('app/build/test-results/testReleaseUnitTest').glob('TEST-*.xml'))
if not files:
    raise SystemExit('Missing release test reports')
rows = [ET.parse(path).getroot() for path in files]
counts = {key: sum(int(row.get(key, '0')) for row in rows) for key in ('tests', 'failures', 'errors', 'skipped')}
print(f'suites={len(rows)} ' + ' '.join(f'{key}={value}' for key, value in counts.items()))
if counts['tests'] == 0 or any(counts[key] for key in ('failures', 'errors', 'skipped')):
    raise SystemExit('Release test gate failed')
