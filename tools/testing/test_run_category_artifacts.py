from pathlib import Path
import sys
import tempfile
import unittest

import category_artifacts as artifacts


class ArtifactRetentionTests(unittest.TestCase):
    def test_log_is_bounded_while_writing_and_retains_tail_in_order(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'ordinary.log'
            log = artifacts.RollingLog(path, part_bytes=8)
            log.write(b'0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ')
            log.close()
            previous = path.with_suffix('.log.1')
            self.assertLessEqual(path.stat().st_size + previous.stat().st_size, 16)
            self.assertEqual(b'OPQRSTUVWXYZ', previous.read_bytes() + path.read_bytes())

    def test_success_removes_logs_xml_and_temporary_files_but_keeps_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = Path(tmp)
            for name in ('ordinary-reports', 'guards-reports', 'ordinary-tmp', 'guards-tmp'):
                (run / name).mkdir()
                (run / name / 'large-file').write_text('temporary')
            for name in ('ordinary.log', 'ordinary.log.1', 'guards.log', 'includes.txt'):
                (run / name).write_text('verbose')
            (run / 'results.json').write_text('{"tests": 10}')
            artifacts.compact_run(run, failed=False)
            self.assertEqual(['results.json'], [p.name for p in run.iterdir()])

    def test_failure_keeps_bounded_logs_but_not_temp_or_xml(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = Path(tmp)
            (run / 'ordinary-tmp').mkdir()
            (run / 'ordinary-reports').mkdir()
            (run / 'ordinary.log').write_text('failure tail')
            (run / 'results.json').write_text('{"failures": 1}')
            artifacts.compact_run(run, failed=True)
            self.assertEqual({'ordinary.log', 'results.json'}, {p.name for p in run.iterdir()})

    def test_pruning_obeys_count_and_budget_and_preserves_active_and_foreign_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            runs = []
            for n in range(4):
                path = base / f'20260912T12000{n}Z-00000000'
                path.mkdir()
                (path / 'plan.json').write_text('{}')
                (path / 'payload').write_bytes(b'x' * 20)
                runs.append(path)
            foreign = base / 'user-notes'
            foreign.mkdir()
            artifacts.prune_runs(base, active=runs[0], max_runs=2, max_bytes=30)
            self.assertTrue(runs[0].exists())
            self.assertTrue(runs[3].exists())
            self.assertFalse(runs[1].exists())
            self.assertFalse(runs[2].exists())
            self.assertTrue(foreign.exists())

    def test_cleanup_does_not_follow_temporary_directory_symlink(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            outside = root / 'keep'
            outside.mkdir()
            (outside / 'valuable').write_text('keep')
            run = root / 'run'
            run.mkdir()
            try:
                (run / 'ordinary-tmp').symlink_to(outside, target_is_directory=True)
            except OSError:
                self.skipTest('Symlink creation unavailable')
            artifacts.compact_run(run, failed=True)
            self.assertEqual('keep', (outside / 'valuable').read_text())

    def test_real_child_output_and_exit_status_are_preserved(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            code = artifacts.run_logged([sys.executable, '-c', 'print("diagnostic"); raise SystemExit(7)'], root, root / 'ordinary.log')
            self.assertEqual(7, code)
            self.assertEqual(['diagnostic'], (root / 'ordinary.log').read_text().splitlines())

    def test_compaction_bounds_legacy_unbounded_failure_logs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = root / 'ordinary.log'
            path.write_bytes(b'x' * (5 * artifacts.LOG_PART_BYTES) + b'failure-end')
            artifacts.compact_run(root, failed=True)
            self.assertLessEqual(artifacts.size_bytes(root), 2 * artifacts.LOG_PART_BYTES)
            self.assertTrue(path.read_bytes().endswith(b'failure-end'))

    def test_child_tools_inherit_owned_temporary_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            temporary = root / 'owned-tmp'
            temporary.mkdir()
            command = [sys.executable, '-c', 'import os; print("\\n".join(os.environ[k] for k in ("TMPDIR", "TMP", "TEMP")))']
            self.assertEqual(0, artifacts.run_logged(command, root, root / 'ordinary.log', temporary))
            self.assertEqual([str(temporary)] * 3, (root / 'ordinary.log').read_text().splitlines())


if __name__ == '__main__':
    unittest.main()
