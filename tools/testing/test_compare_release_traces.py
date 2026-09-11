"""Behavioral controls for the release trace comparison (private fixtures only)."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('comparison', Path(__file__).with_name('compare_release_traces.py'))
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class CompareReleaseTracesTest(unittest.TestCase):
    def evidence(self):
        return {
            'schema': 1, 'commit': 'a' * 40, 'fixtures': 'fixtures',
            'test_inventory': ['a.java'],
            'tests': {'pkg.TestReplay#replay': {'status': 'failure', 'type': 'AssertionError',
                      'message': 'frame 20: x mismatch', 'occurrences': 1},
                      'pkg.TestPolicy#policy': {'status': 'passed', 'occurrences': 1}},
            'reports': {'trace/route.json': {'error_count': 3, 'warning_count': 1,
                        'total_frames': 100, 'errors': [{'start_frame': 20, 'field': 'x'}],
                        'warnings': [{'start_frame': 50, 'field': 'animation'}]}},
        }

    def compare(self, mutate=None):
        base = self.evidence()
        candidate = json.loads(json.dumps(base))
        candidate['commit'] = 'b' * 40
        if mutate:
            mutate(candidate)
        return comparison.compare(base, candidate)

    def test_unchanged_red_is_accepted(self):
        self.assertEqual([], self.compare())

    def test_new_failure_is_rejected(self):
        self.assertTrue(self.compare(lambda x: x['tests']['pkg.TestPolicy#policy'].update(status='failure')))

    def test_same_failure_count_with_changed_identity_is_rejected(self):
        def mutate(x):
            x['tests']['pkg.Other#replay'] = x['tests'].pop('pkg.TestReplay#replay')
        self.assertTrue(self.compare(mutate))

    def test_changed_failure_message_is_rejected(self):
        self.assertTrue(self.compare(lambda x: x['tests']['pkg.TestReplay#replay'].update(message='frame 19: y mismatch')))

    def test_missing_or_newly_skipped_test_is_rejected(self):
        for mutate in (lambda x: x['tests'].pop('pkg.TestPolicy#policy'),
                       lambda x: x['tests']['pkg.TestPolicy#policy'].update(status='skipped')):
            with self.subTest(mutate=mutate):
                self.assertTrue(self.compare(mutate))

    def test_same_totals_with_changed_frontier_or_warning_is_rejected(self):
        for field in ('errors', 'warnings'):
            with self.subTest(field=field):
                self.assertTrue(self.compare(lambda x: x['reports']['trace/route.json'][field][0].update(start_frame=1)))

    def test_missing_report_is_rejected(self):
        with self.assertRaises(comparison.EvidenceError):
            self.compare(lambda x: x['reports'].clear())

    def test_fewer_compared_frames_is_rejected(self):
        self.assertTrue(self.compare(lambda x: x['reports']['trace/route.json'].update(total_frames=99)))

    def test_fixture_or_inventory_change_requires_review(self):
        self.assertTrue(self.compare(lambda x: x.update(fixtures='changed')))
        with self.assertRaises(comparison.EvidenceError):
            self.compare(lambda x: x['test_inventory'].clear())

    def test_even_smaller_changed_red_result_requires_review(self):
        self.assertTrue(self.compare(lambda x: x['reports']['trace/route.json'].update(error_count=2)))

    def test_changed_suite_multiplicity_or_auxiliary_gaps_require_review(self):
        self.assertTrue(self.compare(lambda x: x.update(suite_executions={"Test": [[1, 0, 0, 0]]})))
        self.assertTrue(self.compare(lambda x: x['reports']['trace/route.json'].update(
            missing_advertised_aux_schemas=['new-gap'])))

    def test_empty_evidence_cannot_be_accepted(self):
        with self.assertRaises(comparison.EvidenceError):
            comparison.validate_manifest({'schema': 1, 'tests': {}, 'reports': {}})

    def test_report_owner_is_not_discarded(self):
        self.assertTrue(self.compare(lambda x: x['reports'].__setitem__('trace/other.json', x['reports'].pop('trace/route.json'))))

    def test_log_requires_finished_run_and_consistent_exit(self):
        good = '[INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0\n[INFO] BUILD FAILURE\n[INFO] Finished at: 2026-09-06T01:00:00Z\n'
        self.assertEqual((2, 1, 0, 0), comparison.completed_summary(good, 1))
        for log, code in [(good, 0), (good.replace('Finished at:', 'Still running:'), 1),
                          (good + 'The forked VM terminated without properly saying goodbye', 1),
                          (good.replace('Errors: 0', 'Errors: 1'), 1)]:
            with self.subTest(log=log, code=code):
                with self.assertRaises(comparison.EvidenceError):
                    comparison.completed_summary(log, code)


if __name__ == '__main__':
    unittest.main()
