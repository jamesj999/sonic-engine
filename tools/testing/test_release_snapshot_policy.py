"""Fast snapshot audit and integration regressions for release history admission."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('release_tree_audit', ROOT / '.githooks/audit-release-tree.py')
audit = importlib.util.module_from_spec(spec)
# Keep imported test helpers from creating binary files in the LF-only hook tree.
with patch.object(sys, 'dont_write_bytecode', True):
    spec.loader.exec_module(audit)
BASELINE = '45cecf566825aa50612f5e687b2682fc9681aed1'
NEXT_BASELINE = '218b8fff1a829c7a509331c453d2f3be7e51533b'
PUBLISHED_BASELINE = '37aeb6b84d6634457616c942187cdd40dd93c24f'
OID = 'a' * 40


class TreeAuditTests(unittest.TestCase):
    def check_entry(self, path='README.md', mode='100644', kind='blob', size='10', target=b'../relative'):
        entry = f'{mode} {kind} {OID} {size}\t{path}'.encode() + b'\0'
        def git(*args):
            if args[0] == 'rev-parse': return (OID + '\n').encode()
            if args[0] == 'ls-tree': return entry
            if args[0] == 'cat-file': return target
            raise AssertionError(args)
        with patch.object(audit, 'git', side_effect=git):
            audit.audit('HEAD')

    def test_regular_files_and_gitlinks_are_valid(self):
        self.check_entry()
        self.check_entry('tools/reference', '160000', 'commit', '-')

    def test_all_rom_extensions_are_rejected_case_insensitively(self):
        for extension in ['GEN', 'smd', 'bin', 'sms', 'gg', '32x']:
            with self.subTest(extension=extension), self.assertRaisesRegex(ValueError, 'ROM/binary'):
                self.check_entry('nested/game.' + extension)

    def test_github_size_boundary(self):
        self.check_entry(size='99999999')
        with self.assertRaisesRegex(ValueError, 'file-size'):
            self.check_entry(size='100000000')

    def test_trace_size_boundary_and_compressed_payload(self):
        self.check_entry('traces/physics.csv', size='1048575')
        self.check_entry('traces/physics.csv.gz', size='1048576')
        for name in ['physics.csv', 'aux_state-tail.jsonl']:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'uncompressed trace'):
                self.check_entry('traces/' + name, size='1048576')

    def test_scratch_and_protected_links(self):
        with self.assertRaisesRegex(ValueError, 'scratch'):
            self.check_entry('HANDOVER-release.md')
        self.check_entry('docs/architecture/HANDOVER-release.md')
        with self.assertRaisesRegex(ValueError, 'protected'):
            self.check_entry('config.yaml', '120000')

    def test_absolute_links_rejected_and_relative_links_preserved(self):
        self.check_entry('reference', '120000')
        for target in [b'/tmp/reference', b'C:\\reference', b'\\\\server\\share']:
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, 'absolute symlink'):
                self.check_entry('reference', '120000', target=target)

    def test_unusual_names_do_not_escape_the_audit(self):
        with self.assertRaisesRegex(ValueError, 'ROM/binary'):
            self.check_entry('nested/tab\tnewline\nname.bin')

    def test_malformed_tree_entries_fail_closed(self):
        with self.assertRaisesRegex(ValueError, 'unsupported'):
            self.check_entry(mode='100600')
        with self.assertRaises(ValueError):
            self.check_entry(size='not-a-size')


class SnapshotPolicyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'; self.repo.mkdir()
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Policy Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.write('README.md', 'initial\n'); self.base = self.commit('initial history')

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.repo, stderr=subprocess.STDOUT, text=True).strip()

    def write(self, path, text):
        target = self.repo / path; target.parent.mkdir(parents=True, exist_ok=True); target.write_text(text)
        self.git('add', path)

    def commit(self, message):
        self.git('commit', '-m', message)
        return self.git('rev-parse', 'HEAD')

    def policies(self, baseline):
        directory = self.root / 'policy'; directory.mkdir(exist_ok=True)
        for name in ['validate-policy.sh', 'validate-policy.ps1', 'audit-release-tree.py',
                     'machine-local-path-grandfather.sha256']:
            text = (ROOT / '.githooks' / name).read_text().replace(BASELINE, baseline)
            text = text.replace(NEXT_BASELINE, getattr(self, 'next_baseline', NEXT_BASELINE))
            text = text.replace(PUBLISHED_BASELINE, getattr(self, 'published_baseline', PUBLISHED_BASELINE))
            (directory / name).write_text(text)
        yield ['sh', str(directory / 'validate-policy.sh')]
        if shutil.which('pwsh'):
            yield ['pwsh', '-NoLogo', '-NoProfile', '-File', str(directory / 'validate-policy.ps1')]

    def assert_policy(self, baseline, before, after, branch, expected):
        for command in self.policies(baseline):
            with self.subTest(implementation=command[0]):
                result = subprocess.run(command + ['ci-push', before, after, branch], cwd=self.repo,
                                        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                if expected is None:
                    self.assertEqual(0, result.returncode, result.stdout)
                    self.assertIn('Release tree audit:', result.stdout)
                else:
                    self.assertNotEqual(0, result.returncode, result.stdout)
                    self.assertIn(expected, result.stdout)

    def test_old_removed_violation_is_not_replayed_after_reviewed_snapshot(self):
        self.write('traces/physics.csv', 'x' * 1048576); self.commit('old oversized trace')
        self.git('rm', 'traces/physics.csv'); baseline = self.commit('reviewed clean snapshot')
        self.write('later.txt', 'later\n'); head = self.commit('new work')
        self.assert_policy(baseline, self.base, head, 'feature/release', None)

    def test_inherited_bad_snapshot_entry_is_rejected(self):
        self.write('inherited.bin', 'bad asset'); baseline = self.commit('bad snapshot')
        self.assert_policy(baseline, self.base, baseline, 'feature/release', 'ROM/binary')

    def test_new_violation_removed_from_tip_still_fails(self):
        baseline = self.base
        self.write('traces/physics.csv', 'x' * 1048576); self.commit('new oversized trace')
        self.git('rm', 'traces/physics.csv'); head = self.commit('remove trace')
        self.assert_policy(baseline, self.base, head, 'feature/release', 'uncompressed trace payload')

    def test_next_rollover_audits_snapshot_without_replaying_removed_history(self):
        self.write('traces/physics.csv', 'x' * 1048576); self.commit('historical oversized trace')
        self.git('rm', 'traces/physics.csv'); self.next_baseline = self.commit('reviewed next snapshot')
        self.assert_policy(BASELINE, self.base, self.next_baseline, 'feature/rollover', None)

    def test_next_rollover_does_not_hide_inherited_bad_tree_entries(self):
        self.write('inherited.bin', 'bad asset'); self.next_baseline = self.commit('bad next snapshot')
        self.assert_policy(BASELINE, self.base, self.next_baseline, 'feature/rollover', 'ROM/binary')

    def test_next_rollover_does_not_hide_later_removed_violations(self):
        self.next_baseline = self.base
        self.write('traces/physics.csv', 'x' * 1048576); self.commit('new oversized trace')
        self.git('rm', 'traces/physics.csv'); head = self.commit('remove new violation')
        self.assert_policy(BASELINE, self.base, head, 'feature/rollover', 'uncompressed trace payload')

    def test_next_rollover_admits_frozen_historical_trailer_debt(self):
        self.write('notes.txt', 'history still needs trailers'); self.next_baseline = self.commit('no trailers')
        self.assert_policy(BASELINE, self.base, self.next_baseline, 'develop', None)

    def test_next_rollover_rejects_postbaseline_missing_trailers(self):
        self.next_baseline = self.base
        self.write('later.txt', 'new work'); head = self.commit('missing trailers')
        self.assert_policy(BASELINE, self.base, head, 'develop', 'documentation policy')

    def test_next_rollover_rejects_postbaseline_mismapped_trailers(self):
        self.next_baseline = self.base
        self.write('later.txt', 'new work')
        head = self.commit('chore: new work\n\n' + '\n'.join(
            key + ': ' + ('updated' if key == 'Known-Discrepancies' else 'n/a')
            for key in ['Changelog', 'Guide', 'Known-Discrepancies', 'S3K-Known-Discrepancies',
                        'Agent-Docs', 'Configuration-Docs', 'Skills']))
        self.assert_policy(BASELINE, self.base, head, 'develop', 'Known-Discrepancies')

    def test_next_rollover_rejects_postbaseline_uncoupled_api_changes(self):
        self.next_baseline = self.base
        self.write('src/main/java/example/NewApi.java', '@ModApi\npublic interface NewApi {}\n')
        head = self.commit('chore: add API\n\n' + '\n'.join(
            key + ': n/a' for key in ['Changelog', 'Guide', 'Known-Discrepancies',
                                     'S3K-Known-Discrepancies', 'Agent-Docs',
                                     'Configuration-Docs', 'Skills']))
        self.assert_policy(BASELINE, self.base, head, 'develop', 'candidate signature pin')

    def test_published_release_can_promote_to_next_but_later_debt_is_rejected(self):
        self.write('released.txt', 'published master work')
        self.published_baseline = self.commit('published history without trailers')
        self.assert_policy(self.base, self.base, self.published_baseline, 'next', None)
        self.write('later.txt', 'new promotion work'); head = self.commit('new missing trailers')
        self.assert_policy(self.base, self.base, head, 'next', 'documentation policy')

    def test_published_release_trailer_boundary_does_not_admit_new_resource_violations(self):
        self.published_baseline = self.base
        self.write('traces/physics.csv', 'x' * 1048576); self.commit('new oversized trace')
        self.git('rm', 'traces/physics.csv'); head = self.commit('remove new violation')
        self.assert_policy(self.base, self.base, head, 'next', 'uncompressed trace payload')

    def test_release_with_diverged_master_checks_only_new_trailers(self):
        self.git('switch', '-c', 'old-master')
        self.write('target.txt', 'existing target history\n'); target = self.commit('old target without trailers')
        self.git('switch', 'main')
        self.write('snapshot.txt', 'reviewed history\n'); baseline = self.commit('snapshot without trailers')
        self.git('merge', '--no-ff', 'old-master', '-m', 'merge target history')
        head = self.git('rev-parse', 'HEAD')
        self.assert_policy(baseline, target, head, 'master', None)
        self.write('new.txt', 'must carry trailers\n'); head = self.commit('missing new trailers')
        self.assert_policy(baseline, target, head, 'master', 'documentation policy')


if __name__ == '__main__':
    unittest.main()
