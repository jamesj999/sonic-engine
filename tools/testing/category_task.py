"""Task-wide validation accounting shared by all linked worktrees.

This is a cost brake, not authentication of user intent or a test-pass cache.
"""
from contextlib import contextmanager
import json
import math
import os
from pathlib import Path
import re
import subprocess
import time

from category_control import DEFAULT_MINUTES, is_broad


class ValidationTask:
    def __init__(self, root):
        common = subprocess.check_output(
            ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
            cwd=root, text=True).strip()
        self.directory = Path(common) / 'openggf-validation'
        self.receipt = self.directory / 'task.json'
        self.lock = self.directory / 'task.lock'
        self.data = None

    def __enter__(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        try:
            fd = os.open(self.lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            raise ValueError('Another worktree owns the validation task lock; inspect its PID before cleanup')
        with os.fdopen(fd, 'w') as stream:
            stream.write(str(os.getpid()) + '\n')
        try:
            if self.receipt.exists():
                if self.receipt.is_symlink() or self.receipt.stat().st_size > 16384:
                    raise ValueError('Invalid task receipt')
                self.data = json.loads(self.receipt.read_text())
                self.validate()
            return self
        except BaseException:
            self.lock.unlink()
            raise

    def __exit__(self, *args):
        self.lock.unlink()

    def validate(self):
        data = self.data
        if (not isinstance(data, dict) or data.get('schema') != 1
                or not isinstance(data.get('task'), str)
                or not isinstance(data.get('base'), str)
                or data.get('status') not in ('active', 'finished')
                or type(data.get('broad_attempts')) is not int
                or data['broad_attempts'] not in (0, 1)):
            raise ValueError('Invalid task receipt')
        for key in ('budget_seconds', 'elapsed_seconds', 'focused_seconds', 'baseline_seconds'):
            value = data.get(key)
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError('Invalid task accounting')
        if not 0 < data['budget_seconds'] <= DEFAULT_MINUTES * 60:
            raise ValueError('Invalid task budget')

    def save(self):
        temporary = self.receipt.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.data, indent=2) + '\n')
        temporary.replace(self.receipt)

    def start(self, name, base, minutes=DEFAULT_MINUTES):
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}', name):
            raise ValueError('Task name must be 1–100 letters, digits, dots, dashes or underscores')
        if self.data and self.data['status'] == 'active':
            raise ValueError('An active delivery already owns the budget; a new item or commit cannot reset it')
        if self.data and self.data['task'] == name:
            raise ValueError('Do not reuse a finished task identity')
        if not math.isfinite(minutes) or not 0 < minutes <= DEFAULT_MINUTES:
            raise ValueError(f'Task budget must be positive and at most {DEFAULT_MINUTES} minutes')
        self.data = dict(schema=1, task=name, base=base, status='active',
                         budget_seconds=minutes * 60, elapsed_seconds=0,
                         focused_seconds=0, baseline_seconds=0, broad_attempts=0)
        self.save()

    def require_active(self):
        if not self.data or self.data['status'] != 'active':
            raise ValueError('Start the delivery once with --start-task NAME --base <pre-task-commit>; no tests ran')

    def finish(self):
        self.require_active()
        self.data['status'] = 'finished'
        self.save()

    def record(self, minutes, kind):
        self.require_active()
        if not math.isfinite(minutes * 60) or minutes <= 0 or kind not in ('focused', 'baseline'):
            raise ValueError('Record a positive finite duration and focused/baseline kind')
        self.data['elapsed_seconds'] += minutes * 60
        self.data[kind + '_seconds'] += minutes * 60
        self.save()

    def remaining_minutes(self):
        return max(0, (self.data['budget_seconds'] - self.data['elapsed_seconds']) / 60)

    def check(self, plan):
        self.require_active()
        if plan.get('base') and plan['base'] != self.data['base']:
            raise ValueError('Task base is pinned; changing commits or worktrees cannot reset validation scope')
        if is_broad(plan) and self.data['broad_attempts']:
            raise ValueError('This delivery already attempted its one broad selection. '
                             '--repeat-reason is documentation, not authorization. Use focused checks; no tests ran')
        if self.remaining_minutes() <= 0:
            raise ValueError('The task-wide validation budget is exhausted; no tests ran')

    @contextmanager
    def measure(self, plan, requested_minutes):
        self.check(plan)
        if is_broad(plan):
            self.data['broad_attempts'] += 1
        self.data['last_outcome'] = 'incomplete'
        self.save()  # An interrupted attempt still consumes its broad allowance.
        started = time.monotonic()
        try:
            yield min(requested_minutes, self.remaining_minutes())
            self.data['last_outcome'] = 'completed'  # Not a claim of passing tests.
        finally:
            elapsed = max(0, time.monotonic() - started)
            self.data['elapsed_seconds'] += elapsed
            if not is_broad(plan):
                self.data['focused_seconds'] += elapsed
            self.save()
