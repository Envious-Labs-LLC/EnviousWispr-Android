"""Rows for the review boundary in publish.py (#447). Run: python3 scripts/release/test_publish_review.py

When these fail, a tester publish can cancel a production review in progress, send an unrelated draft
release for review with the tester build, or fail without saying why. The google modules are stubbed, as in
test_publish_retry.py; only the pure helpers and the commit call's source are exercised.
"""
import json
import re
import sys
import types
from pathlib import Path

for name in ('google', 'google.auth', 'google.auth.transport', 'google.auth.transport.requests'):
    sys.modules[name] = types.ModuleType(name)
sys.modules['google.auth.transport.requests'].AuthorizedSession = object
sys.path.insert(0, str(Path(__file__).parent))
import publish  # noqa: E402

passed = 0


def check(label, condition):
    global passed
    print(('ok   ' if condition else 'FAIL ') + label)
    if not condition:
        raise SystemExit(1)
    passed += 1


class Result:
    def __init__(self, status, body):
        self.status_code, self.ok = status, status < 400
        self.content = body if isinstance(body, bytes) else json.dumps(body).encode()

    def json(self):
        return json.loads(self.content)


# Drift guard: the ONE commit call asks Google to refuse while a review is in progress, never to cancel it.
# REVERT: drop the query from the commit URL, or set it to CANCEL_IN_REVIEW_AND_SUBMIT.
source = (Path(__file__).parent / 'publish.py').read_text()
check('the commit query is ERROR_IF_IN_REVIEW', publish.COMMIT_QUERY == 'changesInReviewBehavior=ERROR_IF_IN_REVIEW')
commits = re.findall(r":commit[^'\"]*['\"]", source)
check('the only commit call carries the query', commits == [":commit?{COMMIT_QUERY}'"])

# Another track's draft would be sent for review by the commit; ours, and live releases, are not drafts.
tracks = [
    {'track': 'internal', 'releases': [{'status': 'draft'}]},
    {'track': 'production', 'releases': [{'status': 'completed'}, {'status': 'draft'}]},
    {'track': 'alpha', 'releases': [{'status': 'inProgress'}, {'status': 'halted'}]},
    {'track': 'beta', 'releases': [{'status': 'draft'}]},
]
check('a production and a beta draft are named, sorted', publish.other_track_drafts(tracks) == ['beta', 'production'])
check('our own track and live releases are not drafts', publish.other_track_drafts(tracks[:1] + tracks[2:3]) == [])
check('no tracks, no drafts', publish.other_track_drafts([]) == [])

# A release saved ready in Console but not sent for review reads 'completed' in the edit; only its lifecycle
# state shows that the commit would send it.
summaries = [
    {'track': 'production', 'releaseLifecycleState': 'RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW'},
    {'track': 'production', 'releaseLifecycleState': 'RELEASE_LIFECYCLE_STATE_PUBLISHED'},
    {'track': 'beta', 'releaseLifecycleState': 'RELEASE_LIFECYCLE_STATE_IN_REVIEW'},
    {'track': 'alpha', 'releaseLifecycleState': 'RELEASE_LIFECYCLE_STATE_DRAFT'},
]
check('a ready, unsent production release is named', publish.unsent_releases(summaries) == ['production'])
check('published, in-review and draft releases are not unsent', publish.unsent_releases(summaries[1:]) == [])

# Google's refusal reaches the log in short form.
refusal = Result(400, {'error': {'code': 400, 'status': 'FAILED_PRECONDITION', 'message': 'Changes are currently in review.'}})
check('a JSON refusal gives code, status and message', publish.api_error(refusal) == 'HTTP 400 FAILED_PRECONDITION Changes are currently in review.')
check('a body that is not JSON gives the code alone', publish.api_error(Result(502, b'<html>bad gateway</html>')) == 'HTTP 502')
check('a long message is cut to 300 characters', len(publish.api_error(Result(400, {'error': {'message': 'x' * 900}}))) == len('HTTP 400 ') + 300)


class Session:
    def request(self, method, url, timeout=None, **kwargs):
        return refusal


try:
    publish.api(Session(), 'POST', 'u')
    check('a refused call raises', False)
except RuntimeError as error:
    check('a refused call raises with the reason', str(error) == 'Google API POST failed with HTTP 400 FAILED_PRECONDITION Changes are currently in review.')
print(f'{passed} passed')
