#!/usr/bin/env python3
"""Audit every entry of a release snapshot without replaying its Git history.

New commit content (including newly introduced local paths) is still checked
by the normal policy. This audit checks the complete delivered tree's asset,
size, scratch-file, and symlink invariants, including inherited entries.
"""
import fnmatch
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE)


def audit(tip):
    canonical = git('rev-parse', '--verify', tip + '^{commit}').decode().strip()
    entries = git('ls-tree', '-r', '-l', '-z', canonical).split(b'\0')
    errors = []
    count = 0
    for entry in entries:
        if not entry:
            continue
        metadata, path_bytes = entry.split(b'\t', 1)
        mode, kind, oid, size = metadata.decode('ascii').split()
        path = path_bytes.decode('utf-8', errors='surrogateescape')
        count += 1
        if not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', oid):
            raise ValueError('malformed object id')
        if (mode, kind) not in {('100644', 'blob'), ('100755', 'blob'),
                                ('120000', 'blob'), ('160000', 'commit')}:
            errors.append(f'{path!r}: unsupported tree entry {mode}:{kind}')
            continue
        lower = path.lower()
        if lower.endswith(('.gen', '.smd', '.bin', '.sms', '.gg', '.32x')):
            errors.append(f'{path!r}: ROM/binary asset must remain untracked')
        if '/' not in path and (fnmatch.fnmatchcase(path, 'MERGE-STATUS*.md')
                                or fnmatch.fnmatchcase(path, 'HANDOVER*.md')):
            errors.append(f'{path!r}: root-level merge/handover scratch artifact')
        if kind == 'blob':
            length = int(size)
            if length >= 100_000_000:
                errors.append(f'{path!r}: exceeds GitHub file-size limit')
            name = path.rsplit('/', 1)[-1]
            if length >= 1_048_576 and (fnmatch.fnmatchcase(name, 'aux_state*.jsonl')
                                       or fnmatch.fnmatchcase(name, 'physics*.csv')):
                errors.append(f'{path!r}: uncompressed trace payload')
        elif size != '-':
            raise ValueError('unexpected gitlink size')
        if mode == '120000':
            if path == 'config.yaml' or path.endswith('.gen') or path in {
                    'docs/s1disasm', 'docs/s2disasm', 'docs/kis2disasm',
                    'docs/scddisasm', 'docs/skdisasm'}:
                errors.append(f'{path!r}: protected worktree-resource symlink')
            target = git('cat-file', 'blob', oid).decode('utf-8', errors='surrogateescape')
            if target.startswith(('/', '\\\\')) or re.match(r'^[A-Za-z]:[\\/]', target):
                errors.append(f'{path!r}: absolute symlink target')
    if errors:
        raise ValueError('\n'.join(errors))
    print(f'Release tree audit: {count} entries checked at {canonical}')


if __name__ == '__main__':
    try:
        if len(sys.argv) != 2:
            raise ValueError('usage: audit-release-tree.py <commit>')
        audit(sys.argv[1])
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'policy: release tree audit failed: {error}', file=sys.stderr)
        sys.exit(1)
