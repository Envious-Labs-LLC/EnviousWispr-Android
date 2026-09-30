"""Rows for the post-commit read retry in publish.py (#390). Run: python3 scripts/release/test_publish_retry.py

publish.py imports google.auth at load and runs only under __main__, so the google modules are stubbed;
only the pure retry helper is exercised.
"""
import sys
import types
from pathlib import Path

for name in ('google', 'google.auth', 'google.auth.transport', 'google.auth.transport.requests'):
    sys.modules[name] = types.ModuleType(name)
sys.modules['google.auth.transport.requests'].AuthorizedSession = object
sys.path.insert(0, str(Path(__file__).parent))
import publish  # noqa: E402

slept = []
publish.time.sleep = slept.append


class Result:
    def __init__(self, status, body=b'{"id": "e1"}'):
        self.status_code, self.ok, self.content = status, status < 400, body

    def json(self):
        return {'id': 'e1'}


class Session:
    def __init__(self, statuses):
        self.statuses, self.calls = list(statuses), 0

    def request(self, method, url, timeout=None, **kwargs):
        self.calls += 1
        return Result(self.statuses.pop(0))


def check(label, condition):
    print(('ok   ' if condition else 'FAIL ') + label)
    if not condition:
        raise SystemExit(1)


session = Session([200])
check('a healthy read is made once', publish.read_api(session, 'GET', 'u') == {'id': 'e1'} and session.calls == 1)

session = Session([503, 200])
slept.clear()
check('one 503 is retried and the answer returned', publish.read_api(session, 'GET', 'u') == {'id': 'e1'} and session.calls == 2 and slept == [5])

session = Session([503, 503, 200])
slept.clear()
check('two 503s are retried with a growing pause', publish.read_api(session, 'GET', 'u') == {'id': 'e1'} and session.calls == 3 and slept == [5, 10])

session = Session([503, 503, 503])
try:
    publish.read_api(session, 'GET', 'u')
    check('three 503s fail closed', False)
except RuntimeError as error:
    check('three 503s fail closed with the last error', 'HTTP 503' in str(error) and session.calls == 3)

session = Session([503, 200])
try:
    publish.api(session, 'POST', 'u')
    check('the plain api call does not retry', False)
except RuntimeError:
    check('the plain api call (the commit and the upload) makes one request and raises, never retries', session.calls == 1)
print('5 passed')
