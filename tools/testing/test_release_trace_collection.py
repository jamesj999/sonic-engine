"""Behavioral tests for compare_release_traces.py begin/collect: every evidence defect must be rejected.

Fixtures build a minimal fake checkout (git repo, src/main, src/test with a TraceReplay test, pom.xml,
trace fixtures) plus Surefire XML, owned trace JSON and a Maven log, then mutate one thing at a time.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/testing"))
import compare_release_traces as crt  # noqa: E402

MAVEN_LOG = """[INFO] Running com.openggf.tests.trace.TestFakeTraceReplay
[INFO] Tests run: {tests}, Failures: {failures}, Errors: {errors}, Skipped: {skips}, Time elapsed: 1.0 s -- in com.openggf.tests.trace.TestFakeTraceReplay
[INFO] Results:
[INFO] Tests run: {tests}, Failures: {failures}, Errors: {errors}, Skipped: {skips}
[INFO] ------------------------------------------------------------------------
[INFO] BUILD {conclusion}
[INFO] Finished at: 2026-09-06T00:00:00+01:00
"""


def git(tree, *args):
    subprocess.run(["git", "-C", str(tree), *args], check=True, capture_output=True,
                   env={**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@x", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@x"})


class Fixture:
    """A fake release checkout with a completed trace run."""

    def __init__(self, base):
        self.tree = Path(base, "checkout"); self.tree.mkdir()
        (self.tree / "src/main/java/com/openggf").mkdir(parents=True)
        (self.tree / "src/main/java/com/openggf/Engine.java").write_text("class Engine {}\n")
        (self.tree / "src/test/java/com/openggf/tests/trace").mkdir(parents=True)
        (self.tree / "src/test/java/com/openggf/tests/trace/TestFakeTraceReplay.java").write_text("class TestFakeTraceReplay {}\n")
        (self.tree / "src/test/resources/traces/s1").mkdir(parents=True)
        (self.tree / "src/test/resources/traces/s1/physics.csv").write_text("frame,x\n1,2\n")
        (self.tree / "pom.xml").write_text("<project/>\n")
        (self.tree / ".gitignore").write_text("target/\n")
        git(self.tree, "init", "-q"); git(self.tree, "add", "-A"); git(self.tree, "commit", "-q", "-m", "base")
        self.start = Path(base, "start.json")
        self.log = Path(base, "maven.log")
        self.reports = self.tree / "target/surefire-reports"
        self.traces = self.tree / "target/trace-reports"

    def begin(self):
        crt.begin(self.tree.resolve(), self.start)

    def write_run(self, cases=(("replayGhz1", "passed"), ("replayMz1", "failure")), tests=None):
        """cases: (name, status) with status passed|failure|error|skipped."""
        self.reports.mkdir(parents=True, exist_ok=True); self.traces.mkdir(parents=True, exist_ok=True)
        body = ""
        counts = [0, 0, 0, 0]
        for name, status in cases:
            counts[0] += 1
            inner = ""
            if status == "failure":
                counts[1] += 1; inner = f'<failure type="AssertionError" message="frame 12 differs at {self.tree}/x"/>'
            elif status == "error":
                counts[2] += 1; inner = '<error type="RuntimeException" message="boom"/>'
            elif status == "skipped":
                counts[3] += 1; inner = '<skipped message="no rom"/>'
            body += f'<testcase classname="com.openggf.tests.trace.TestFakeTraceReplay" name="{name}" time="1">{inner}</testcase>'
        declared = counts[0] if tests is None else tests
        (self.reports / "TEST-com.openggf.tests.trace.TestFakeTraceReplay.xml").write_text(
            f'<?xml version="1.0"?><testsuite name="com.openggf.tests.trace.TestFakeTraceReplay" tests="{declared}">{body}</testsuite>')
        report = self.traces / "ghz1.json"
        report.write_text(json.dumps({"frontier": 12, "warnings": 0, "path": f"{self.tree}/src/test/resources/traces/s1"}))
        owner_key = hashlib.sha256(b"logical:ghz1").hexdigest()
        (self.traces / "ghz1.json.owner.json").write_text(json.dumps({"logical_key": "ghz1", "owner_key": owner_key, "physical_path": str(report)}))
        failures, errors, skips = counts[1], counts[2], counts[3]
        self.exit_code = 1 if failures else 0
        self.log.write_text(MAVEN_LOG.format(tests=counts[0], failures=failures, errors=errors, skips=skips,
                                             conclusion="FAILURE" if failures else "SUCCESS"))
        return counts

    def collect(self):
        return crt.collect(self.tree.resolve(), self.start, self.log, self.exit_code)


class BeginTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.fx = Fixture(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_begin_records_commit_digests_inventory_and_start(self):
        self.fx.begin()
        marker = json.loads(self.fx.start.read_text())
        self.assertEqual(len(marker["commit"]), 40)
        self.assertEqual(marker["test_inventory"], ["src/test/java/com/openggf/tests/trace/TestFakeTraceReplay.java"])
        self.assertTrue(marker["source_digest"] and marker["fixtures"] and marker["started_ns"] > 0)

    def test_begin_refuses_existing_marker_and_prior_reports(self):
        self.fx.begin()
        with self.assertRaises(crt.EvidenceError):
            self.fx.begin()
        self.fx.start.unlink()
        self.fx.reports.mkdir(parents=True); (self.fx.reports / "TEST-old.xml").write_text("<testsuite/>")
        with self.assertRaises(crt.EvidenceError):
            self.fx.begin()

    def test_source_digest_covers_working_tree_content_not_just_head(self):
        before = crt.source_state(self.fx.tree)["source_digest"]
        (self.fx.tree / "src/main/java/com/openggf/Engine.java").write_text("class Engine { int dirty; }\n")
        after = crt.source_state(self.fx.tree)
        self.assertNotEqual(before, after["source_digest"])
        self.assertEqual(after["commit"], crt.git(self.fx.tree, "rev-parse", "HEAD"))


class CollectTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.fx = Fixture(self.tmp.name); self.fx.begin()

    def tearDown(self):
        self.tmp.cleanup()

    def test_valid_completed_run_is_collected_with_known_red_retained(self):
        self.fx.write_run()
        result = self.fx.collect()
        self.assertEqual(result["summary"], [2, 1, 0, 0])
        self.assertEqual(result["tests"]["com.openggf.tests.trace.TestFakeTraceReplay#replayMz1"]["status"], "failure")
        self.assertEqual(sorted(result["reports"]), ["ghz1.json"])

    def test_failure_messages_and_report_payloads_have_checkout_paths_normalized(self):
        self.fx.write_run()
        result = self.fx.collect()
        msg = result["tests"]["com.openggf.tests.trace.TestFakeTraceReplay#replayMz1"]["message"]
        self.assertIn("<CHECKOUT>/x", msg); self.assertNotIn(str(self.fx.tree), msg)
        self.assertEqual(result["reports"]["ghz1.json"]["payload"]["path"], "<CHECKOUT>/src/test/resources/traces/s1")
        self.assertNotIn(str(self.fx.tree), json.dumps(result["reports"]))

    def test_stale_report_from_before_begin_is_rejected(self):
        self.fx.write_run()
        old = time.time_ns() // 1_000_000_000 - 3600
        report = next(self.fx.reports.glob("TEST-*.xml"))
        os.utime(report, (old, old))
        with self.assertRaisesRegex(crt.EvidenceError, "stale evidence"):
            self.fx.collect()

    def test_missing_reports_or_trace_json_are_rejected(self):
        self.fx.write_run()
        shutil.rmtree(self.fx.traces)
        with self.assertRaises(crt.EvidenceError):
            self.fx.collect()
        self.fx.write_run()
        for report in self.fx.reports.glob("TEST-*.xml"):
            report.unlink()
        with self.assertRaisesRegex(crt.EvidenceError, "no Surefire reports"):
            self.fx.collect()

    def test_repeated_singleton_passes_reconcile_but_changed_or_red_repeats_fail(self):
        self.fx.write_run(cases=(("replayGhz1", "passed"),))
        log = self.fx.log.read_text()
        suite_line = next(line for line in log.splitlines() if "Time elapsed:" in line)
        log = log.replace("[INFO] Results:", suite_line + "\n" + suite_line + "\n[INFO] Results:")
        log = log.replace("[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n",
                          "[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0\n")
        self.fx.log.write_text(log)
        result = self.fx.collect()
        self.assertEqual(result["summary"], [3, 0, 0, 0])
        self.assertEqual(len(next(iter(result["suite_executions"].values()))), 3)
        self.fx.log.write_text(log.replace(suite_line, suite_line.replace("Failures: 0", "Failures: 1"), 1))
        with self.assertRaises(crt.EvidenceError):
            self.fx.collect()

    def retained_singleton_run(self, cases=None, declared=1, repeats=3):
        cases = cases or (("replayGhz1", "passed"),) * repeats
        self.fx.write_run(cases=cases, tests=declared)
        log = MAVEN_LOG.format(tests=repeats, failures=0, errors=0, skips=0,
                               conclusion="SUCCESS")
        line = next(line for line in log.splitlines() if "Time elapsed:" in line)
        singleton = line.replace(f"Tests run: {repeats},", "Tests run: 1,")
        self.fx.log.write_text(log.replace(line, "\n".join([singleton] * repeats)))
        self.fx.exit_code = 0

    def test_retained_identical_singleton_rows_match_overwritten_evidence(self):
        self.retained_singleton_run(cases=(("replayGhz1", "passed"),))
        overwritten = self.fx.collect()
        for declared in (1, 3):
            with self.subTest(declared=declared):
                self.retained_singleton_run(declared=declared)
                retained = self.fx.collect()
                self.assertEqual(overwritten, retained)

    def test_retained_rows_need_complete_matching_passing_singleton_verdicts(self):
        invalid = (
            (("replayGhz1", "passed"),) * 2,
            (("replayGhz1", "passed"), ("replayMz1", "passed"), ("replayGhz1", "passed")),
            (("replayGhz1", "passed"), ("replayGhz1", "failure"), ("replayGhz1", "passed")),
            (("replayGhz1", "passed"), ("replayGhz1", "skipped"), ("replayGhz1", "passed")),
        )
        for cases in invalid:
            with self.subTest(cases=cases):
                self.retained_singleton_run(cases=cases)
                with self.assertRaises(crt.EvidenceError):
                    self.fx.collect()
        self.retained_singleton_run()
        self.fx.log.write_text(self.fx.log.read_text().replace(
            "Tests run: 1, Failures: 0", "Tests run: 1, Failures: 1", 1))
        with self.assertRaises(crt.EvidenceError):
            self.fx.collect()

    def test_retained_rows_do_not_hide_declared_failure_counters(self):
        self.retained_singleton_run()
        report = next(self.fx.reports.glob("TEST-*.xml"))
        report.write_text(report.read_text().replace('tests="1"', 'tests="1" failures="1"'))
        with self.assertRaises(crt.EvidenceError):
            self.fx.collect()

    def test_missing_suite_summary_fails_even_when_aggregate_totals_match(self):
        self.fx.write_run()
        self.fx.log.write_text("\n".join(line for line in self.fx.log.read_text().splitlines()
                                         if "Time elapsed:" not in line))
        with self.assertRaisesRegex(crt.EvidenceError, "suite inventory"):
            self.fx.collect()

    def test_maven_totals_must_match_xml(self):
        counts = self.fx.write_run()
        self.fx.log.write_text(MAVEN_LOG.format(tests=counts[0] + 1, failures=1, errors=0, skips=0, conclusion="FAILURE"))
        with self.assertRaisesRegex(crt.EvidenceError, "Maven/XML"):
            self.fx.collect()

    def test_incomplete_xml_declared_count_is_rejected(self):
        self.fx.write_run(tests=5)
        with self.assertRaisesRegex(crt.EvidenceError, "incomplete testcase XML"):
            self.fx.collect()

    def test_maven_exit_must_agree_with_failures_and_conclusion(self):
        self.fx.write_run()
        self.fx.exit_code = 0
        with self.assertRaisesRegex(crt.EvidenceError, "exit disagrees"):
            self.fx.collect()
        self.fx.exit_code = 137
        with self.assertRaisesRegex(crt.EvidenceError, "not a completed test verdict"):
            self.fx.collect()

    def test_fork_crash_or_unfinished_log_is_rejected(self):
        self.fx.write_run()
        self.fx.log.write_text(self.fx.log.read_text().replace("[INFO] Finished at", "The forked VM terminated\n[INFO] Finished at"))
        with self.assertRaisesRegex(crt.EvidenceError, "fork/selection failure"):
            self.fx.collect()
        self.fx.log.write_text("\n".join(line for line in MAVEN_LOG.format(tests=2, failures=1, errors=0, skips=0, conclusion="FAILURE").splitlines() if "Finished at" not in line))
        with self.assertRaisesRegex(crt.EvidenceError, "did not finish"):
            self.fx.collect()

    def test_test_error_and_skipped_replay_are_rejected(self):
        self.fx.write_run(cases=(("replayGhz1", "passed"), ("replayMz1", "error")))
        with self.assertRaisesRegex(crt.EvidenceError, "invalid release trace summary|test error"):   # summary check fires first
            self.fx.collect()
        self.fx.write_run(cases=(("replayGhz1", "passed"), ("replayMz1", "skipped")))
        with self.assertRaisesRegex(crt.EvidenceError, "required replay skipped"):
            self.fx.collect()

    def test_owner_mismatch_orphan_and_invalid_owner_are_rejected(self):
        self.fx.write_run()
        owner = self.fx.traces / "ghz1.json.owner.json"
        data = json.loads(owner.read_text())
        data["physical_path"] = str(self.fx.traces / "other.json")
        owner.write_text(json.dumps(data))
        with self.assertRaisesRegex(crt.EvidenceError, "points to another report"):
            self.fx.collect()
        data["physical_path"] = str(self.fx.traces / "ghz1.json"); data["owner_key"] = "notahash"
        owner.write_text(json.dumps(data))
        with self.assertRaisesRegex(crt.EvidenceError, "invalid trace owner"):
            self.fx.collect()
        owner.unlink()
        with self.assertRaisesRegex(crt.EvidenceError, "missing/nonregular evidence"):
            self.fx.collect()
        self.fx.write_run()
        (self.fx.traces / "lonely.json.owner.json").write_text("{}")
        with self.assertRaisesRegex(crt.EvidenceError, "orphan owner metadata"):
            self.fx.collect()

    def test_source_mutation_during_the_run_is_rejected(self):
        self.fx.write_run()
        (self.fx.tree / "src/main/java/com/openggf/Engine.java").write_text("class Engine { int mutated; }\n")
        with self.assertRaisesRegex(crt.EvidenceError, "clean checkout|source_digest changed"):   # dirty tree is rejected first
            self.fx.collect()

    def test_fixture_or_inventory_mutation_during_the_run_is_rejected(self):
        self.fx.write_run()
        (self.fx.tree / "src/test/resources/traces/s1/physics.csv").write_text("frame,x\n1,3\n")
        with self.assertRaisesRegex(crt.EvidenceError, "clean checkout|changed while the trace run"):
            self.fx.collect()
        self.fx.write_run()
        git(self.fx.tree, "checkout", "-q", "--", "src/test/resources/traces/s1/physics.csv")
        (self.fx.tree / "src/test/java/com/openggf/tests/trace/TestExtraTraceReplay.java").write_text("class X {}\n")
        git(self.fx.tree, "add", "-A"); git(self.fx.tree, "commit", "-q", "-m", "mid-run commit")   # clean again, but inventory/digest moved
        with self.assertRaisesRegex(crt.EvidenceError, "changed while the trace run"):
            self.fx.collect()

    def test_conflicting_duplicate_testcase_is_rejected_but_identical_duplicates_counted(self):
        self.fx.write_run(cases=(("replayGhz1", "passed"), ("replayGhz1", "passed")))
        result = self.fx.collect()
        self.assertEqual(result["tests"]["com.openggf.tests.trace.TestFakeTraceReplay#replayGhz1"]["occurrences"], 2)
        self.fx.write_run(cases=(("replayGhz1", "passed"), ("replayGhz1", "failure")))
        with self.assertRaisesRegex(crt.EvidenceError, "conflicting duplicate"):
            self.fx.collect()


class CleanCheckoutTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.fx = Fixture(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_dirty_checkout_is_rejected_at_begin_and_at_collect(self):
        (self.fx.tree / "src/main/java/com/openggf/Engine.java").write_text("class Engine { int local; }\n")
        with self.assertRaisesRegex(crt.EvidenceError, "clean checkout"):
            self.fx.begin()
        git(self.fx.tree, "checkout", "-q", "--", "src/main/java/com/openggf/Engine.java")
        self.fx.begin(); self.fx.write_run()
        (self.fx.tree / "untracked.txt").write_text("x")
        with self.assertRaisesRegex(crt.EvidenceError, "clean checkout"):
            self.fx.collect()

    def test_ignored_build_output_does_not_count_as_dirty(self):
        self.fx.begin(); self.fx.write_run()
        self.assertTrue((self.fx.tree / "target").exists())
        self.fx.collect()   # target/ is ignored: still a clean checkout


class CompareCliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.fx = Fixture(self.tmp.name); self.fx.begin(); self.fx.write_run()
        self.manifest = Path(self.tmp.name, "candidate.json")
        self.manifest.write_text(json.dumps(self.fx.collect(), sort_keys=True))
        self.commit = json.loads(self.manifest.read_text())["commit"]
        base = json.loads(self.manifest.read_text()); base["commit"] = "0" * 40
        self.baseline = Path(self.tmp.name, "baseline.json"); self.baseline.write_text(json.dumps(base))

    def tearDown(self):
        self.tmp.cleanup()

    def cli(self, expected_baseline, expected_candidate):
        return subprocess.run([sys.executable, str(ROOT / "tools/testing/compare_release_traces.py"), "compare",
                               "--baseline", str(self.baseline), "--candidate", str(self.manifest),
                               "--expected-baseline", expected_baseline, "--expected-candidate", expected_candidate],
                              capture_output=True, text=True)

    def test_candidate_must_match_the_release_checkout(self):
        self.assertEqual(self.cli("0" * 40, self.commit).returncode, 0)
        wrong = self.cli("0" * 40, "1" * 40)
        self.assertEqual(wrong.returncode, 2); self.assertIn("candidate revision does not match", wrong.stderr)

    def test_baseline_pin_and_distinct_commits_are_enforced(self):
        self.assertEqual(self.cli("f" * 40, self.commit).returncode, 2)
        same = json.loads(self.baseline.read_text()); same["commit"] = self.commit; self.baseline.write_text(json.dumps(same))
        self.assertEqual(self.cli(self.commit, self.commit).returncode, 2)


class CompareTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.fx = Fixture(self.tmp.name); self.fx.begin(); self.fx.write_run()
        self.base = self.fx.collect()

    def tearDown(self):
        self.tmp.cleanup()

    def test_identical_evidence_has_no_violations(self):
        self.assertEqual(crt.compare(self.base, json.loads(json.dumps(self.base))), [])

    def test_any_changed_new_or_missing_identity_or_report_fails(self):
        cand = json.loads(json.dumps(self.base))
        cand["tests"]["com.openggf.tests.trace.TestFakeTraceReplay#replayMz1"]["status"] = "passed"
        self.assertTrue(any("changed tests" in v for v in crt.compare(self.base, cand)))
        cand = json.loads(json.dumps(self.base)); del cand["tests"]["com.openggf.tests.trace.TestFakeTraceReplay#replayGhz1"]
        self.assertTrue(any("missing tests" in v for v in crt.compare(self.base, cand)))
        cand = json.loads(json.dumps(self.base)); cand["reports"]["new.json"] = cand["reports"]["ghz1.json"]
        self.assertTrue(any("new reports" in v for v in crt.compare(self.base, cand)))
        cand = json.loads(json.dumps(self.base)); cand["reports"]["ghz1.json"]["payload"]["warnings"] = 1
        self.assertTrue(any("changed reports" in v for v in crt.compare(self.base, cand)))


if __name__ == "__main__":
    unittest.main()
