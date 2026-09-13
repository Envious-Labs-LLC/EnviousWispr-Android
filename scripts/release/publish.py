"""Sign one verified GitHub build and publish only to this app's internal track."""
import base64
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import google.auth
from google.auth.transport.requests import AuthorizedSession

PACKAGE = 'com.envi.wispr'
TRACK = 'internal'
PROJECT = 'ageless-domain-493017-j8'
CERTIFICATE = 'effeb59b8fbd6a4295bb732a58d43918e12c843bf57fa72a5ae258fd56d01184'
ROOT = f'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/edits'
DIST = Path('dist')


def command(args, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, **kwargs)
    if result.returncode:
        raise RuntimeError(f'{args[0]} failed; output withheld to protect signing material')
    return result.stdout


def api(session, method, url, **kwargs):
    result = session.request(method, url, timeout=240, **kwargs)
    if not result.ok:
        raise RuntimeError(f'Google API {method} failed with HTTP {result.status_code}')
    return result.json() if result.content else {}


def require_bundle(source, version):
    tool = str(Path(os.environ['RUNNER_TEMP']) / 'bundletool.jar')
    command(['java', '-jar', tool, 'validate', '--bundle=' + str(source)])
    root = ET.fromstring(command(['java', '-jar', tool, 'dump', 'manifest', '--bundle=' + str(source), '--module=base']))
    android = '{http://schemas.android.com/apk/res/android}'
    app = root.find('application')
    if root.get('package') != PACKAGE or root.get(android + 'versionCode') != str(version):
        raise RuntimeError('Bundle identity does not match the pushed build')
    if app is None or app.get(android + 'debuggable', 'false') != 'false':
        raise RuntimeError('A debug bundle cannot be published')
    hosts = 0
    with zipfile.ZipFile(source) as bundle:
        for name in bundle.namelist():
            if not name.endswith('.so'):
                continue
            data = bundle.read(name)
            if data[:4] != b'\x7fELF':
                continue
            machine = struct.unpack_from('<H', data, 18)[0]
            if data[4] == 1 and machine == 164:  # Hexagon DSP data, not an Android host library.
                continue
            if data[4] != 2 or machine != 183 or data[5] != 1:
                raise RuntimeError('Unexpected native library architecture')
            hosts += 1
            offset = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            for index in range(count):
                position = offset + index * size
                if struct.unpack_from('<I', data, position)[0] == 1:
                    alignment = struct.unpack_from('<Q', data, position + 48)[0]
                    if alignment < 16384:
                        raise RuntimeError('Native library fails 16 KB alignment')
    if hosts == 0:
        raise RuntimeError('No Android native libraries found')


def sign(source, output, material):
    with tempfile.TemporaryDirectory(prefix='play-signing-', dir=os.environ['RUNNER_TEMP']) as directory:
        key = Path(directory) / 'upload.p12'
        key.write_bytes(base64.b64decode(material['keystore_base64'], validate=True))
        key.chmod(0o600)
        env = dict(os.environ, EW_UPLOAD_PASSWORD=material['password'])
        alias = material['alias']
        cert = command(['keytool', '-exportcert', '-keystore', str(key), '-storepass:env', 'EW_UPLOAD_PASSWORD', '-alias', alias], env=env)
        if hashlib.sha256(cert).hexdigest() != CERTIFICATE:
            raise RuntimeError('Wrong upload key')
        if output.exists():
            raise RuntimeError('Signed output already exists')
        command(['jarsigner', '-keystore', str(key), '-storepass:env', 'EW_UPLOAD_PASSWORD', '-keypass:env', 'EW_UPLOAD_PASSWORD', '-sigalg', 'SHA256withRSA', '-digestalg', 'SHA-256', '-signedjar', str(output), str(source), alias], env=env)
        if b'jar verified.' not in command(['jarsigner', '-verify', str(output)]):
            raise RuntimeError('Signature verification failed')
        with zipfile.ZipFile(source) as before, zipfile.ZipFile(output) as after:
            if any(before.read(name) != after.read(name) for name in before.namelist()):
                raise RuntimeError('Signing changed the bundle payload')


def main():
    os.umask(0o077)
    if os.environ.get('GITHUB_REPOSITORY') != 'Envious-Labs-LLC/EnviousWispr-Android' or os.environ.get('GITHUB_REF') != 'refs/heads/internal-testing':
        raise RuntimeError('Publishing is restricted to the internal-testing branch')
    receipt = json.loads((DIST / 'build.json').read_text())
    source, signed = DIST / 'unsigned.aab', DIST / 'signed.aab'
    version = int(receipt['version_code'])
    if receipt['commit'] != os.environ['GITHUB_SHA'] or version != int(os.environ['GITHUB_RUN_NUMBER']) + 100:
        raise RuntimeError('Artifact does not belong to this workflow run')
    if hashlib.sha256(source.read_bytes()).hexdigest() != receipt['sha256']:
        raise RuntimeError('Unsigned artifact changed')
    require_bundle(source, version)
    credentials, _ = google.auth.default(scopes=['https://www.googleapis.com/auth/cloud-platform', 'https://www.googleapis.com/auth/androidpublisher'])
    session = AuthorizedSession(credentials)
    edit = api(session, 'POST', ROOT, json={})['id']
    committed = False
    try:
        tracks = api(session, 'GET', f'{ROOT}/{edit}/tracks').get('tracks', [])
        codes = [int(code) for track in tracks for release in track.get('releases', []) for code in release.get('versionCodes', [])]
        if codes and version <= max(codes):
            raise RuntimeError('Stale version code; push a new run, do not rerun an old publication')
        secret = api(session, 'GET', f'https://secretmanager.googleapis.com/v1/projects/{PROJECT}/secrets/enviouswispr-android-upload-signing/versions/latest:access')
        material = json.loads(base64.b64decode(secret['payload']['data'], validate=True))
        sign(source, signed, material)
        del material, secret
        signed_hash = hashlib.sha256(signed.read_bytes()).hexdigest()
        upload = f'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}/edits/{edit}/bundles?uploadType=media'
        with signed.open('rb') as stream:
            result = api(session, 'POST', upload, data=stream, headers={'Content-Type': 'application/octet-stream'})
        if int(result['versionCode']) != version or result['sha256'].lower() != signed_hash:
            raise RuntimeError('Uploaded bundle differs from the signed artifact')
        release = {'name': f'Internal build {version} ({receipt["commit"][:7]})', 'versionCodes': [str(version)], 'status': 'completed'}
        api(session, 'PUT', f'{ROOT}/{edit}/tracks/{TRACK}', json={'track': TRACK, 'releases': [release]})
        api(session, 'POST', f'{ROOT}/{edit}:validate')
        # A lost response may still have committed. Reconcile through a new edit, never retry blindly.
        try:
            api(session, 'POST', f'{ROOT}/{edit}:commit')
        except Exception:
            pass
        verification = api(session, 'POST', ROOT, json={})['id']
        try:
            state = api(session, 'GET', f'{ROOT}/{verification}/tracks/{TRACK}')
            committed = any(str(version) in item.get('versionCodes', []) and item.get('status') == 'completed' for item in state.get('releases', []))
        finally:
            api(session, 'DELETE', f'{ROOT}/{verification}')
        if not committed:
            raise RuntimeError('Publication not confirmed; inspect Play before retrying')
        published = dict(receipt, package=PACKAGE, track=TRACK, signed_sha256=signed_hash, status='completed')
        (DIST / 'published.json').write_text(json.dumps(published, indent=2) + '\n')
        print(f'Confirmed {PACKAGE} version {version} on Google Play internal testing')
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write(f'Published internal test build **{version}** from `{receipt["commit"]}`.\n\nSigned SHA256: `{signed_hash}`\n')
    finally:
        if not committed:
            # Deleting an uncommitted edit is cleanup. A committed edit no longer exists.
            try:
                api(session, 'DELETE', f'{ROOT}/{edit}')
            except Exception:
                pass


if __name__ == '__main__':
    main()
