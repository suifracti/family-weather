#!/usr/bin/env python3
"""Restore exact manifest assets from their official sources for an ephemeral CI runner.

No credentials, alternative versions, inference, or uploads. Existing different files are kept.
"""
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile
import urllib.request
import uuid

from restore_assets import ROOT, MANIFEST, destination, matches


def download(url, target):
    if not url.startswith('https://'):
        raise ValueError('Only HTTPS sources are accepted')
    with urllib.request.urlopen(url, timeout=90) as source, target.open('xb') as output:
        shutil.copyfileobj(source, output)


def restore():
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    entries = manifest['files']
    for entry in entries:
        target = destination(entry['path'])
        if target.exists() and not matches(target, entry):
            raise ValueError('Existing file differs; kept unchanged: ' + entry['path'])
    with tempfile.TemporaryDirectory(prefix='breezy-assets-') as scratch:
        scratch = Path(scratch)
        staged = []
        archive = None
        for index, entry in enumerate(entries):
            if matches(destination(entry['path']), entry):
                continue
            candidate = scratch / str(index)
            if entry['path'].startswith('app/src/main/assets/sensevoice/'):
                if '/licenses/' in entry['path']:
                    raise ValueError('Tracked license is missing: ' + entry['path'])
                if archive is None:
                    archive = scratch / 'sensevoice.tar.bz2'
                    download(entry['originalSource'], archive)
                    if not matches(archive, manifest['sensevoiceArchive']):
                        raise ValueError('SenseVoice archive size/SHA256 mismatch')
                member_key = 'tokensMember' if entry['path'].endswith('tokens.txt') else 'modelMember'
                with tarfile.open(archive, 'r:bz2') as source:
                    member = source.getmember(manifest['sensevoiceArchive'][member_key])
                    if not member.isfile() or member.size != entry['bytes']:
                        raise ValueError('Unexpected archive member')
                    with source.extractfile(member) as stream, candidate.open('xb') as output:
                        shutil.copyfileobj(stream, output)
            else:
                download(entry['originalSource'], candidate)
            if not matches(candidate, entry):
                raise ValueError('Downloaded size/SHA256 mismatch: ' + entry['path'])
            staged.append((entry, candidate))
        # Validate everything before writing fixed manifest paths; never extract arbitrary paths.
        for entry, candidate in staged:
            target = destination(entry['path'])
            target.parent.mkdir(parents=True, exist_ok=True)
            partial = target.with_name(target.name + '.ci-partial-' + uuid.uuid4().hex)
            try:
                with partial.open('xb') as output, candidate.open('rb') as source:
                    shutil.copyfileobj(source, output)
                os.chmod(partial, 0o600)
                os.link(partial, target)
            finally:
                partial.unlink(missing_ok=True)
        if any(not matches(destination(entry['path']), entry) for entry in entries):
            raise ValueError('Final asset verification failed')
    print('Verified all fixed model/runtime/license assets; no inference or upload')


if __name__ == '__main__':
    restore()
