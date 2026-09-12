"""Prevent expensive retries and late prerequisite discovery without running Maven."""
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import category_control as control
import run_categories as runner


class ControlTests(unittest.TestCase):
    def plan(self):
        return dict(full=True, tests=['Example.java'], guards=True,
                    working_tree_fingerprint='state', categories=['common'], inventory_count=1)

    def test_preflight_collects_all_missing_tools_before_tests(self):
        with patch.object(control.subprocess, 'run', side_effect=FileNotFoundError('missing')) as process:
            with self.assertRaises(ValueError) as failure:
                control.preflight(Path('.'), self.plan())
        self.assertEqual(3, process.call_count)
        for name in ('Java 21', 'Lua 5.4', 'PowerShell'):
            self.assertIn(name, str(failure.exception))

    def test_versions_and_guard_environment_are_checked(self):
        outputs = ['Java version: 21.0.10, vendor: test', 'Lua 5.4.8', '']
        with patch.dict(control.os.environ, {'LUA_BIN': '/configured/lua'}), patch.object(
                control.subprocess, 'run', side_effect=[subprocess.CompletedProcess([], 0, o) for o in outputs]) as process:
            control.preflight(Path('.'), self.plan())
        self.assertEqual('/configured/lua', process.call_args_list[1].args[0][0])
        with patch.object(control.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'Java version: 17.0.1')):
            with self.assertRaisesRegex(ValueError, 'Java 21'):
                control.preflight(Path('.'), dict(guards=False))

    def test_focused_preflight_does_not_require_guard_only_tools(self):
        with patch.object(control.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'Java version: 21')) as process:
            control.preflight(Path('.'), dict(guards=False))
        self.assertEqual(1, process.call_count)

    def test_broad_attempt_survives_focused_runs_and_reason_cannot_authorize_repeat(self):
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp)
            plan = self.plan()
            control.check_repeat(target, plan, None)
            control.record_attempt(target, target / 'first', plan, None)
            focused = dict(plan, full=False)
            control.check_repeat(target, focused, None)
            control.record_attempt(target, target / 'focused', focused, None)
            plan['working_tree_fingerprint'] = 'edited'
            with self.assertRaisesRegex(ValueError, 'already attempted'):
                control.check_repeat(target, plan, None)
            with self.assertRaisesRegex(ValueError, 'not authorization'):
                control.check_repeat(target, plan, 'Corrected the missing tool prerequisite')
            receipt = json.loads((target / 'category-tests-last-broad.json').read_text())
            self.assertIsNone(receipt['reason'])
            self.assertLess((target / 'category-tests-last-broad.json').stat().st_size, 16384)

    def test_cli_prerequisite_failure_never_calls_runner(self):
        with patch.object(runner, 'ValidationTask') as task_factory, patch.object(runner, 'preflight', side_effect=ValueError('missing tool')), patch.object(runner, 'run_plan') as run, patch('sys.stdout', new_callable=io.StringIO), patch('sys.stderr', new_callable=io.StringIO):
            task = task_factory.return_value.__enter__.return_value
            task.data = dict(task='test', elapsed_seconds=0)
            task.remaining_minutes.return_value = 40
            self.assertEqual(2, runner.main(['--category', 'all', '--run']))
        run.assert_not_called()

    def test_timeout_keeps_incomplete_status_and_releases_lock_without_starting_guards(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with patch.object(runner, 'tree_state', return_value='state'), patch.object(runner, 'rom_args', return_value=[]), patch.object(runner, 'run_logged', side_effect=TimeoutError('budget')) as process:
                with self.assertRaises(TimeoutError):
                    runner.run_plan(root, self.plan())
            self.assertEqual(1, process.call_count)
            self.assertFalse((root / 'target/category-tests.lock').exists())
            run = next((root / 'target/category-tests').iterdir())
            self.assertEqual('incomplete', json.loads((run / 'status.json').read_text())['status'])
            self.assertFalse((run / 'ordinary-tmp').exists())
            with patch.object(runner, 'run_logged') as process:
                with self.assertRaisesRegex(ValueError, 'already attempted'):
                    runner.run_plan(root, self.plan())
                process.assert_not_called()

    def test_total_budget_is_shared_between_lanes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            summary = dict(reports=1, tests=1, failures=0, errors=0, skipped=0)
            with patch.object(runner, 'tree_state', return_value='state'), patch.object(runner, 'rom_args', return_value=[]), patch.object(runner, 'summarize', side_effect=lambda _: dict(summary)), patch.object(runner, 'run_logged', return_value=0) as process, patch.object(runner.time, 'monotonic', side_effect=[0, 10, 20, 30, 40]):
                self.assertEqual(0, runner.run_plan(root, self.plan(), max_minutes=1))
            self.assertEqual([50, 30], [c.kwargs['timeout'] for c in process.call_args_list])


if __name__ == '__main__':
    unittest.main()
