"""Behavioral tests for the release skip classifier and the checked-in policy."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/testing/classify_surefire_skips.py"
POLICY = ROOT / "tools/testing/release-skip-policy.json"
EVIDENCE = Path(os.environ.get("OPENGGF_SKIP_EVIDENCE_REPORTS", ROOT / "target/audio-parity-delivery-postmerge-evidence/ordinary-reports"))

sys.path.insert(0, str(SCRIPT.parent))
import classify_surefire_skips as csk  # noqa: E402


def write_report(directory, classname, cases):
    """cases: list of (name, skipped_message_or_None)."""
    body = "".join(
        f'<testcase classname="{classname}" name="{name}" time="0.1">'
        + (f'<skipped message="{msg}"/>' if msg is not None else "") + "</testcase>"
        for name, msg in cases)
    Path(directory, f"TEST-{classname}.xml").write_text(
        f'<?xml version="1.0"?><testsuite name="{classname}" tests="{len(cases)}">{body}</testsuite>')


def policy(rules, inputs=None):
    return {"version": 1, "inputs": inputs or {"gl": "GL", "bk2": "recording"}, "rules": rules}


def rule(match, category="gl-context", absent="gl", evidence="src/test/java/x/Y.java:1"):
    return {"match": match, "category": category, "evidence": evidence, "allowed_when_absent": absent}


class ClassifierTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.reports = Path(self.tmp.name, "reports"); self.reports.mkdir()
        self.policy_path = Path(self.tmp.name, "policy.json")

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, caps="", extra=()):
        proc = subprocess.run([sys.executable, str(SCRIPT), "--reports", str(self.reports), "--policy",
                               str(self.policy_path), "--capabilities", caps, *extra],
                              capture_output=True, text=True)
        return proc.returncode, proc.stdout, proc.stderr

    def write_policy(self, rules, inputs=None):
        self.policy_path.write_text(json.dumps(policy(rules, inputs)))

    def test_unclassified_skip_fails(self):
        write_report(self.reports, "a.B", [("one", ""), ("two", None)])
        self.write_policy([])
        rc, out, err = self.run_cli()
        self.assertEqual(rc, 1); self.assertIn("UNCLASSIFIED skip", err); self.assertIn("a.B#one", err)

    def test_opt_in_rule_is_always_allowed(self):
        write_report(self.reports, "a.B", [("bench", "property missing")])
        self.write_policy([rule("a.B#bench", "opt-in-benchmark", None)])
        rc, out, _ = self.run_cli()
        self.assertEqual(rc, 0); self.assertIn("allowed=1", out)

    def test_input_declared_absent_allows_skip(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl")])
        self.assertEqual(self.run_cli("gl=false")[0], 0)

    def test_input_declared_present_makes_skip_a_failure(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl")])
        rc, _, err = self.run_cli("gl=true")
        self.assertEqual(rc, 1); self.assertIn("REQUIRED test skipped", err); self.assertIn("[gl]", err)

    def test_undeclared_input_fails_closed(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl")])
        rc, _, err = self.run_cli("")
        self.assertEqual(rc, 1); self.assertIn("did not declare", err)

    def test_wildcard_rules_are_rejected(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#*")])
        rc, _, err = self.run_cli("gl=false")
        self.assertEqual(rc, 2); self.assertIn("exact class#method", err)

    def test_rule_naming_undeclared_input_is_a_policy_error(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl", absent="display")])
        rc, _, err = self.run_cli("display=false")
        self.assertEqual(rc, 2); self.assertIn("undeclared input", err)

    def test_duplicate_and_unknown_category_rules_are_policy_errors(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl"), rule("a.B#gl")])
        self.assertEqual(self.run_cli("gl=false")[0], 2)
        self.write_policy([rule("a.B#gl", category="whatever")])
        self.assertEqual(self.run_cli("gl=false")[0], 2)

    def test_rule_without_explicit_allowed_when_absent_is_a_policy_error(self):
        write_report(self.reports, "a.B", [("gl", "")])
        r = rule("a.B#gl"); del r["allowed_when_absent"]
        self.write_policy([r])
        rc, _, err = self.run_cli("gl=false")
        self.assertEqual(rc, 2); self.assertIn("explicitly", err)

    def test_gl_context_rule_may_not_be_optional(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl", "gl-context", None)])
        rc, _, err = self.run_cli("gl=false")
        self.assertEqual(rc, 2); self.assertIn("never null", err)

    def test_empty_and_duplicate_capabilities_are_rejected(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl")])
        self.assertEqual(self.run_cli("=true")[0], 2)
        self.assertEqual(self.run_cli("gl=true,gl=false")[0], 2)

    def test_parameterized_identities_are_exact(self):
        write_report(self.reports, "a.B", [("p(int)[1]", ""), ("p(int)[2]", "")])
        self.write_policy([rule("a.B#p(int)[1]")])
        rc, _, err = self.run_cli("gl=false")
        self.assertEqual(rc, 1); self.assertIn("a.B#p(int)[2]", err); self.assertNotIn("a.B#p(int)[1]", err)

    def test_bad_capability_syntax_and_missing_reports_exit_2(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl")])
        self.assertEqual(self.run_cli("gl=maybe")[0], 2)
        empty = Path(self.tmp.name, "empty"); empty.mkdir()
        proc = subprocess.run([sys.executable, str(SCRIPT), "--reports", str(empty), "--policy", str(self.policy_path)],
                              capture_output=True, text=True)
        self.assertEqual(proc.returncode, 2)

    def test_stack_trace_skip_messages_are_reduced_to_one_line(self):
        Path(self.reports, "TEST-a.C.xml").write_text(
            '<?xml version="1.0"?><testsuite name="a.C" tests="1"><testcase classname="a.C" name="t" time="0">'
            '<skipped>org.opentest4j.TestAbortedException: GL missing\n\tat a.C.t(C.java:1)\n</skipped></testcase></testsuite>')
        self.write_policy([])
        rc, _, err = self.run_cli()
        self.assertEqual(rc, 1); self.assertIn("GL missing", err); self.assertNotIn("\tat a.C", err)

    def test_json_output_and_empty_message(self):
        write_report(self.reports, "a.B", [("gl", ""), ("bench", None)])
        self.write_policy([rule("a.B#gl")])
        out_json = Path(self.tmp.name, "out.json")
        rc, _, _ = self.run_cli("gl=false", ["--json", str(out_json)])
        self.assertEqual(rc, 0)
        data = json.loads(out_json.read_text())
        self.assertEqual(data["counts"]["skipped"], 1); self.assertEqual(data["allowed"][0]["identity"], "a.B#gl")
        self.assertEqual(data["allowed"][0]["message"], ""); self.assertEqual(data["capabilities"], {"gl": False})

    def test_check_evidence_flags_missing_sources(self):
        write_report(self.reports, "a.B", [("gl", "")])
        self.write_policy([rule("a.B#gl", evidence="src/test/java/does/NotExist.java:1")])
        rc, _, err = self.run_cli("gl=false", ["--check-evidence", "--root", self.tmp.name])
        self.assertEqual(rc, 1); self.assertIn("STALE policy rule", err)


class CheckedInPolicyTests(unittest.TestCase):
    def test_policy_loads_and_every_rule_is_exact(self):
        pol = csk.load_policy(POLICY)
        self.assertGreaterEqual(len(pol["rules"]), 45)
        for r in pol["rules"]:
            self.assertNotIn("*", r["match"])

    def test_release_runner_never_treats_gl_tests_as_optional(self):
        pol = csk.load_policy(POLICY)
        gl = [r for r in pol["rules"] if r["category"] == "gl-context"]
        self.assertGreaterEqual(len(gl), 19)
        self.assertTrue(all(r["allowed_when_absent"] == "gl" for r in gl))

    def test_policy_evidence_sources_all_exist(self):
        pol = csk.load_policy(POLICY)
        self.assertEqual(csk.stale_rules(pol, ROOT), [])
        self.assertEqual(len(pol["rules"]), 45)

    @unittest.skipUnless(EVIDENCE.is_dir(), "preserved f56d4fae1 ordinary reports not present")
    def test_preserved_f56d4fae1_reports(self):
        pol = csk.load_policy(POLICY)
        skipped = csk.read_skips(EVIDENCE)
        self.assertEqual(len(skipped), 43)
        headless = csk.classify(skipped, pol, {"gl": False, "s2_bk2": False, "s3k_observations": False,
                                               "s1_bizhawk_reference": False, "audio_reference_files": False})
        self.assertEqual(headless["unclassified"], []); self.assertEqual(headless["required_skipped"], [])
        self.assertEqual(headless["undeclared_input"], []); self.assertEqual(len(headless["allowed"]), 43)
        with_gl = csk.classify(skipped, pol, {"gl": True, "s2_bk2": False, "s3k_observations": False,
                                              "s1_bizhawk_reference": False, "audio_reference_files": False})
        self.assertEqual(len(with_gl["required_skipped"]), 20)   # 2 GlReadPixels + 15 shader smoke + 2 CNZ capture + 1 data-select capture
        self.assertTrue(all(e["input"] == "gl" for e in with_gl["required_skipped"]))
        legacy_only = {"version": 1, "inputs": pol["inputs"],
                       "rules": [r for r in pol["rules"] if r["category"] in ("opt-in-benchmark", "opt-in-gate", "opt-in-diagnostic", "opt-in-capture", "scenario-assumption")
                                 or r["match"].startswith("com.openggf.audio.AudioRegressionTest#")
                                 or r["match"].startswith("com.openggf.tools.audio.parity.TestS1OpenGgfAudioCapture#")]}
        legacy_only["rules"] = [r for r in legacy_only["rules"] if r["match"] != "com.openggf.game.rewind.TestLiveRewindCheckpointCost#compareCheckpointCadencesOnTheSameRecordedRoute"]
        rejected = csk.classify(skipped, legacy_only, {"s1_bizhawk_reference": False, "audio_reference_files": False})
        self.assertEqual(len(rejected["unclassified"]), 27)


if __name__ == "__main__":
    unittest.main()
