"""Result consumption deletes artifacts without deleting retry evidence or foreign data."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import category_artifacts as artifacts
import run_categories as runner


class CleanupTests(unittest.TestCase):
    def make_run(self, root, index=0, keep=False):
        run = root / 'target/category-tests' / f'20260912T12000{index}Z-00000000'
        run.mkdir(parents=True)
        (run / 'plan.json').write_text('{}')
        (run / 'ordinary.log').write_text('diagnostic')
        if keep:
            (run / 'keep-diagnostics').touch()
        return run

    def test_acknowledgment_removes_entire_directory_and_preserves_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = self.make_run(root)
            receipt = root / 'target/category-tests-last-broad.json'
            receipt.write_text('{"status":"failed"}')
            artifacts.acknowledge_run(root, run.name)
            artifacts.acknowledge_run(root, run.name)
            self.assertFalse(run.parent.exists())
            self.assertEqual('{"status":"failed"}', receipt.read_text())
            self.assertFalse((root / 'target/category-tests.lock').exists())

    def test_active_lock_and_path_traversal_prevent_deletion(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = self.make_run(root)
            with self.assertRaises(ValueError):
                artifacts.acknowledge_run(root, '../category-tests')
            lock = root / 'target/category-tests.lock'
            lock.write_text('active')
            with self.assertRaisesRegex(ValueError, 'active'):
                artifacts.acknowledge_run(root, run.name)
            self.assertTrue(run.exists())
            self.assertEqual('active', lock.read_text())

    def test_startup_deletes_unacknowledged_and_legacy_runs_but_bounds_opt_in_retention(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            ordinary = self.make_run(root)
            kept = [self.make_run(root, i, keep=True) for i in (1, 2, 3)]
            foreign = ordinary.parent / 'user-notes'
            foreign.mkdir()
            artifacts.prune_runs(ordinary.parent)
            self.assertFalse(ordinary.exists())
            self.assertFalse(kept[0].exists())
            self.assertTrue(all(p.exists() for p in kept[1:]))
            self.assertTrue(foreign.exists())
            artifacts.acknowledge_run(root, kept[2].name)
            self.assertFalse(kept[2].exists())

    def test_acknowledgment_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = self.make_run(root)
            alias = run.parent / '20260912T120009Z-00000000'
            try:
                alias.symlink_to(run, target_is_directory=True)
            except OSError:
                self.skipTest('Symlinks unavailable')
            with self.assertRaises(ValueError):
                artifacts.acknowledge_run(root, alias.name)
            self.assertTrue(run.exists())

    def test_opt_in_keeps_success_logs_across_startup_until_acknowledged(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            plan = dict(full=False, tests=['Example.java'], guards=False,
                        categories=['common'], inventory_count=1)
            summary = dict(reports=1, tests=1, failures=0, errors=0, skipped=0)
            def process(command, cwd, log_path, *args, **kwargs):
                log_path.write_text('requested diagnostics')
                return 0
            with patch.object(runner, 'tree_state', return_value='state'), patch.object(runner, 'rom_args', return_value=[]), patch.object(runner, 'summarize', return_value=summary), patch.object(runner, 'run_logged', side_effect=process):
                self.assertEqual(0, runner.run_plan(root, plan, keep_diagnostics=True))
            run = next((root / 'target/category-tests').iterdir())
            artifacts.prune_runs(run.parent)
            self.assertEqual('requested diagnostics', (run / 'ordinary.log').read_text())
            artifacts.acknowledge_run(root, run.name)
            self.assertFalse(run.exists())

    def test_cli_acknowledgment_never_preflights_or_runs_maven(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run = self.make_run(root)
            with patch.object(runner, 'ROOT', root), patch.object(runner, 'preflight') as preflight, patch.object(runner, 'run_logged') as process, patch('sys.stdout', new_callable=io.StringIO):
                self.assertEqual(0, runner.main(['--acknowledge', run.name]))
            preflight.assert_not_called()
            process.assert_not_called()
            self.assertFalse(run.exists())

    def test_completed_result_is_consumable_then_deleted_without_losing_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            old = self.make_run(root)
            plan = dict(full=True, tests=['Example.java'], guards=False,
                        categories=['common'], inventory_count=1)
            summary = dict(reports=1, tests=2, failures=0, errors=0, skipped=1,
                           skipped_cases=[dict(reason='optional')])
            with patch.object(runner, 'tree_state', return_value='state'), patch.object(runner, 'rom_args', return_value=[]), patch.object(runner, 'summarize', return_value=summary), patch.object(runner, 'run_logged', return_value=0):
                self.assertEqual(0, runner.run_plan(root, plan))
            self.assertFalse(old.exists())
            run = next((root / 'target/category-tests').iterdir())
            result = json.loads((run / 'results.json').read_text())
            self.assertEqual('optional', result[0]['skipped_cases'][0]['reason'])
            artifacts.acknowledge_run(root, run.name)
            receipt = json.loads((root / 'target/category-tests-last-broad.json').read_text())
            self.assertEqual('passed', receipt['status'])
            self.assertEqual(1, receipt['results'][0]['skipped'])
            self.assertNotIn('skipped_cases', receipt['results'][0])
            with self.assertRaisesRegex(ValueError, 'already attempted'):
                runner.run_plan(root, plan)


if __name__ == '__main__':
    unittest.main()
