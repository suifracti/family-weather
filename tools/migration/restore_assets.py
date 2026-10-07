#!/usr/bin/env python3
"""Verify/restore the separately transferred model/runtime bundle. No downloads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'docs/migration/required-assets.json'


def checksum(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def matches(path, expected):
    return (path.is_file() and path.stat().st_size == expected['bytes']
            and checksum(path) == expected['sha256'])


def destination(relative):
    # Only the manifest's fixed paths are restored; never extract archive paths.
    path = (ROOT / relative).resolve()
    if not path.is_relative_to(ROOT) or Path(relative).is_absolute():
        raise ValueError('Destination outside checkout')
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, help='Path to required-assets.zip; omit to check installed files')
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    entries = manifest['files']
    if args.bundle:
        if not matches(args.bundle, manifest['bundle']):
            raise ValueError('Bundle size/SHA256 mismatch; nothing restored')
        with zipfile.ZipFile(args.bundle) as archive:
            for entry in entries:
                target = destination(entry['path'])
                if target.exists() and not matches(target, entry):
                    raise ValueError('Existing file differs; kept unchanged: ' + entry['path'])
                info = archive.getinfo(entry['path'])
                if info.file_size != entry['bytes']:
                    raise ValueError('Archive member size mismatch: ' + entry['path'])
            for entry in entries:
                target = destination(entry['path'])
                if matches(target, entry):
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                partial = target.with_name(target.name + '.migration-' + uuid.uuid4().hex + '.partial')
                try:
                    with archive.open(entry['path']) as source, partial.open('xb') as output:
                        shutil.copyfileobj(source, output)
                    if not matches(partial, entry):
                        raise ValueError('Extracted SHA256 mismatch: ' + entry['path'])
                    # Atomic no-overwrite link: another writer cannot be overwritten.
                    os.link(partial, target)
                finally:
                    if partial.exists():
                        partial.unlink()
    missing = [entry['path'] for entry in entries if not matches(destination(entry['path']), entry)]
    if missing:
        print('Missing/mismatched required assets:\n' + '\n'.join(missing), file=sys.stderr)
        return 1
    print('Verified ' + str(len(entries)) + ' required model/runtime/license files; no inference/build run.')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
