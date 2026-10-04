#!/usr/bin/env python3
"""Fail if a universal APK or AAB omits an intended MP3 encoder ABI."""
import sys
import hashlib
import zipfile
from pathlib import Path

source_root = Path(__file__).resolve().parent.parent / 'app/src/main/cpp/third_party'
for line in (source_root / 'lame-3.100.sha256').read_text().splitlines():
    digest, name = line.split('  ', 1)
    if hashlib.sha256((source_root / 'lame' / name).read_bytes()).hexdigest() != digest:
        raise SystemExit(f'LAME vendored source changed: {name}')

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
for argument in sys.argv[1:]:
    path = Path(argument)
    prefix = 'base/lib' if path.suffix == '.aab' else 'lib'
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        for abi in ABIS:
            for library in ('libsmd_mp3.so', 'libmp3lame.so'):
                required = f'{prefix}/{abi}/{library}'
                if required not in names:
                    raise SystemExit(f'{path}: missing {required}')
                if archive.getinfo(required).file_size == 0:
                    raise SystemExit(f'{path}: empty {required}')
    print(f'{path}: all four JNI/LAME ABIs present')
if len(sys.argv) < 2:
    raise SystemExit('Usage: verify_native_packaging.py APK_OR_AAB...')
