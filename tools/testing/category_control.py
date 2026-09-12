"""Small, bounded controls for expensive local validation (not a pass cache)."""
import json
import os
import re
import subprocess

DEFAULT_MINUTES = 40
IDLE_SECONDS = 600
BROAD_CLASSES = 500


def preflight(root, plan):
    """Check the actual launch environment before starting any test lane."""
    checks = [('Maven with Java 21', ['mvn', '-v'], r'Java version:\s*21(?:\D|$)')]
    if plan['guards']:
        checks += [
            ('Lua 5.4 (set LUA_BIN)', [os.environ.get('LUA_BIN', 'lua'), '-v'], r'Lua 5\.4(?:\D|$)'),
            ('PowerShell on PATH', ['pwsh', '-NoLogo', '-NoProfile', '-Command', 'exit 0'], None),
        ]
    failures = []
    for label, command, expected in checks:
        try:
            result = subprocess.run(command, cwd=root, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, text=True, timeout=20)
            if result.returncode or (expected and not re.search(expected, result.stdout)):
                failures.append(f'{label}: wrong version or unsuccessful launch')
        except (OSError, subprocess.TimeoutExpired) as error:
            failures.append(f'{label}: {error}')
    if failures:
        raise ValueError('Prerequisite check failed before tests: ' + '; '.join(failures))


def is_broad(plan):
    return plan['full'] or len(plan['tests']) >= BROAD_CLASSES


def check_repeat(target, plan, reason):
    """A single receipt survives log rotation and focused runs, never certifies a pass."""
    if not is_broad(plan):
        return
    receipt = target / 'category-tests-last-broad.json'
    if receipt.exists():
        if receipt.stat().st_size > 16384:
            raise ValueError(f'Invalid oversized validation receipt: {receipt}')
        previous = json.loads(receipt.read_text())
        if not isinstance(previous, dict) or not isinstance(previous.get('run'), str):
            raise ValueError(f'Invalid validation receipt: {receipt}')
        if not reason:
            raise ValueError(
                f"Broad validation already attempted in this worktree ({previous['run']}). "
                'Inspect its outcome; use focused checks for fixes/attribution. '
                'A necessary new broad run requires --repeat-reason explaining changed scope '
                'or corrected prerequisites, not merely a red result. No tests were run.')


def record_attempt(target, run, plan, reason):
    if is_broad(plan):
        receipt = target / 'category-tests-last-broad.json'
        temporary = receipt.with_suffix('.tmp')
        temporary.write_text(json.dumps({
            'run': str(run), 'head': plan.get('head'), 'base': plan.get('base'),
            'fingerprint': plan['working_tree_fingerprint'], 'reason': reason,
        }, indent=2) + '\n')
        temporary.replace(receipt)
