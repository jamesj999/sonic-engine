"""Exercise delivery-wide limits without launching Maven or waiting on clocks."""
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import category_task as accounting
import run_categories as runner


class TaskTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.common = self.root / 'shared-git'
        mock = patch.object(accounting.subprocess, 'check_output', return_value=str(self.common))
        mock.start()
        self.addCleanup(mock.stop)
        self.plan = dict(base='base', head='first', full=True, tests=['Example.java'])

    def start(self):
        with accounting.ValidationTask(self.root) as task:
            task.start('delivery', 'base')

    def test_no_implicit_task_or_reset_for_another_item(self):
        with accounting.ValidationTask(self.root) as task:
            with self.assertRaisesRegex(ValueError, 'Start the delivery'):
                task.check(self.plan)
            task.start('delivery', 'base')
            with self.assertRaisesRegex(ValueError, 'cannot reset'):
                task.start('next-plan-item', 'new-base')
            with self.assertRaisesRegex(ValueError, 'base is pinned'):
                task.check(dict(self.plan, base='new-base'))

    def test_broad_allowance_survives_commits_other_worktrees_and_focused_runs(self):
        self.start()
        with accounting.ValidationTask(self.root) as task:
            with patch.object(accounting.time, 'monotonic', side_effect=[0, 120]):
                with task.measure(self.plan, 40):
                    pass
            focused = dict(self.plan, base=None, full=False)
            with patch.object(accounting.time, 'monotonic', side_effect=[0, 60]):
                with task.measure(focused, 40):
                    pass
        with accounting.ValidationTask(self.root / 'other-worktree') as task:
            self.assertEqual(180, task.data['elapsed_seconds'])
            self.assertEqual(60, task.data['focused_seconds'])
            with self.assertRaisesRegex(ValueError, 'one broad selection'):
                task.check(dict(self.plan, head='another-commit', repeat_reason='New candidate'))

    def test_external_focused_and_baseline_time_reduce_remaining_lane_budget(self):
        self.start()
        with accounting.ValidationTask(self.root) as task:
            task.record(4, 'focused')
            task.record(6, 'baseline')
            with patch.object(accounting.time, 'monotonic', side_effect=[0, 30]):
                with task.measure(self.plan, 500) as minutes:
                    self.assertEqual(30, minutes)
            self.assertEqual(630, task.data['elapsed_seconds'])
            task.record(31, 'focused')  # Record an overrun honestly, then refuse further work.
            with self.assertRaisesRegex(ValueError, 'exhausted'):
                task.check(dict(self.plan, full=False))

    def test_interruption_charges_elapsed_time_and_does_not_restore_broad_allowance(self):
        self.start()
        with self.assertRaises(KeyboardInterrupt):
            with accounting.ValidationTask(self.root) as task:
                with patch.object(accounting.time, 'monotonic', side_effect=[0, 90]):
                    with task.measure(self.plan, 40):
                        raise KeyboardInterrupt()
        with accounting.ValidationTask(self.root) as task:
            self.assertEqual(90, task.data['elapsed_seconds'])
            self.assertEqual('incomplete', task.data['last_outcome'])
            with self.assertRaisesRegex(ValueError, 'one broad selection'):
                task.check(self.plan)

    def test_shared_lock_prevents_concurrent_budget_spending(self):
        with accounting.ValidationTask(self.root):
            with self.assertRaisesRegex(ValueError, 'Another worktree'):
                with accounting.ValidationTask(self.root / 'other'):
                    self.fail('Must not acquire shared lock')

    def test_finished_tasks_require_an_explicit_distinct_delivery(self):
        self.start()
        with accounting.ValidationTask(self.root) as task:
            task.finish()
            with self.assertRaisesRegex(ValueError, 'Start the delivery'):
                task.check(self.plan)
            with self.assertRaisesRegex(ValueError, 'reuse'):
                task.start('delivery', 'base')
            task.start('next-user-request', 'next-base')
            self.assertEqual(0, task.data['elapsed_seconds'])

    def test_corrupt_accounting_is_fail_closed_and_releases_lock(self):
        self.start()
        receipt = self.common / 'openggf-validation/task.json'
        data = json.loads(receipt.read_text())
        data['elapsed_seconds'] = -1
        receipt.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'Invalid task accounting'):
            with accounting.ValidationTask(self.root):
                pass
        self.assertFalse(receipt.with_name('task.lock').exists())

    def test_cli_reason_cannot_authorize_a_second_broad_run(self):
        self.start()
        with accounting.ValidationTask(self.root) as task:
            with patch.object(accounting.time, 'monotonic', side_effect=[0, 1]):
                with task.measure(self.plan, 40):
                    pass
        plan = dict(self.plan, guards=True)
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'make_plan', return_value=plan), \
                patch.object(runner, 'preflight') as preflight, patch.object(runner, 'run_plan') as run, \
                patch('sys.stdout', new_callable=io.StringIO), patch('sys.stderr', new_callable=io.StringIO):
            self.assertEqual(2, runner.main(['--base', 'base', '--run', '--repeat-reason', 'Next plan candidate']))
        preflight.assert_not_called()
        run.assert_not_called()

    def test_cli_passes_only_remaining_task_time_to_maven_lanes(self):
        self.start()
        with accounting.ValidationTask(self.root) as task:
            task.record(10, 'focused')
        plan = dict(self.plan, guards=False, full=False)
        with patch.object(runner, 'ROOT', self.root), patch.object(runner, 'make_plan', return_value=plan), \
                patch.object(runner, 'preflight'), patch.object(runner, 'run_plan', return_value=0) as run, \
                patch.object(accounting.time, 'monotonic', side_effect=[0, 30]), \
                patch('sys.stdout', new_callable=io.StringIO):
            self.assertEqual(0, runner.main(['--base', 'base', '--run', '--max-minutes', '500']))
        self.assertEqual(30, run.call_args.args[2])
        self.assertTrue(run.call_args.kwargs['task_managed'])
        with accounting.ValidationTask(self.root) as task:
            self.assertEqual(630, task.data['elapsed_seconds'])


class LinkedWorktreeTest(unittest.TestCase):
    def test_real_linked_worktree_resolves_the_same_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / 'main'
            linked = Path(tmp) / 'linked'
            root.mkdir()
            def git(*args):
                subprocess.run(['git', *args], cwd=root, check=True,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            git('init', '--quiet')
            git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                'commit', '--allow-empty', '--quiet', '-m', 'seed')
            git('worktree', 'add', '--detach', str(linked))
            self.assertEqual(accounting.ValidationTask(root).receipt,
                             accounting.ValidationTask(linked).receipt)


if __name__ == '__main__':
    unittest.main()
