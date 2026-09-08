"""Executable coverage for the local Maven artifact launchers."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class LauncherTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="openggf launcher project ")
        self.project = Path(self.tmp.name)
        self.target = self.project / "target"
        self.bin = self.project / "stub-bin"
        self.target.mkdir()
        self.bin.mkdir()
        shutil.copy2(ROOT / "run.sh", self.project / "run.sh")
        self.java_args = self.project / "java-args.txt"
        self.maven_args = self.project / "maven-args.txt"
        self._write_stub(
            self.bin / "mvn",
            """#!/bin/sh
set -eu
printf '%s\\n' "$*" >> "$MAVEN_ARGS"
case " $* " in
  *' package '*)
    if [ "${WRITE_MANIFEST:-1}" = 1 ]; then
      printf '%s\\n' "${MANIFEST_CONTENT:-finalName=$FINAL_NAME}" > target/openggf-artifact.properties
    fi
    ;;
esac
exit "${MAVEN_STATUS:-0}"
""",
        )
        self._write_stub(
            self.bin / "java",
            """#!/bin/sh
set -eu
printf '%s\\n' "$@" > "$JAVA_ARGS"
exit "${JAVA_STATUS:-0}"
""",
        )

    def tearDown(self):
        self.tmp.cleanup()

    def _write_stub(self, path, contents):
        path.write_text(contents, encoding="utf-8")
        path.chmod(0o755)

    def _run(self, final_name="OpenGGF-0.6.prerelease", artifacts=(), **extra_env):
        for artifact in artifacts:
            (self.target / artifact).write_text("stub", encoding="utf-8")
        env = os.environ.copy()
        env.update(
            PATH=f"{self.bin}{os.pathsep}{env['PATH']}",
            FINAL_NAME=final_name,
            JAVA_ARGS=str(self.java_args),
            MAVEN_ARGS=str(self.maven_args),
            **extra_env,
        )
        outside = self.project / "caller outside project"
        outside.mkdir()
        return subprocess.run(
            ["bash", str(self.project / "run.sh")],
            cwd=outside,
            env=env,
            capture_output=True,
            text=True,
        )

    def _java_jar_argument(self):
        args = self.java_args.read_text(encoding="utf-8").splitlines()
        return args[args.index("-jar") + 1]

    def test_selects_effective_current_artifact_when_stale_higher_version_exists(self):
        current = "OpenGGF-0.6.prerelease-jar-with-dependencies.jar"
        stale = "OpenGGF-0.7.prerelease-jar-with-dependencies.jar"

        result = self._run(artifacts=(current, stale))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self._java_jar_argument(), str(self.target / current))
        self.assertNotIn("help:evaluate", self.maven_args.read_text(encoding="utf-8"))

    def test_honors_custom_final_name_and_quotes_project_path(self):
        current = "OpenGGF local build-jar-with-dependencies.jar"
        stale = "OpenGGF-9.9-jar-with-dependencies.jar"

        result = self._run(final_name="OpenGGF local build", artifacts=(current, stale))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self._java_jar_argument(), str(self.target / current))
        self.assertIn(" ", self._java_jar_argument())

    def test_missing_current_artifact_fails_even_when_stale_artifact_exists(self):
        stale = "OpenGGF-0.7.prerelease-jar-with-dependencies.jar"

        result = self._run(artifacts=(stale,))

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Expected packaged jar not found", result.stderr)
        self.assertIn("OpenGGF-0.6.prerelease-jar-with-dependencies.jar", result.stderr)
        self.assertFalse(self.java_args.exists())

    def test_missing_artifact_manifest_stops_launch(self):
        result = self._run(WRITE_MANIFEST="0")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("did not produce", result.stderr)
        self.assertFalse(self.java_args.exists())

    def test_malformed_artifact_manifest_stops_launch(self):
        result = self._run(MANIFEST_CONTENT="finalName=one\nfinalName=two")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("empty or multiline", result.stderr)
        self.assertFalse(self.java_args.exists())

    def test_maven_package_failure_stops_before_artifact_lookup(self):
        result = self._run(MAVEN_STATUS="23")

        self.assertEqual(result.returncode, 23)
        self.assertFalse(self.java_args.exists())

    def test_java_failure_is_preserved(self):
        current = "OpenGGF-0.6.prerelease-jar-with-dependencies.jar"

        result = self._run(artifacts=(current,), JAVA_STATUS="37")

        self.assertEqual(result.returncode, 37)

    def test_bash_launcher_has_valid_syntax(self):
        result = subprocess.run(
            ["bash", "-n", str(ROOT / "run.sh")],
            capture_output=True,
            text=True,
        )

        self.assertEqual(result.returncode, 0, result.stderr)


class WindowsLauncherContractTests(unittest.TestCase):
    def test_windows_launcher_is_script_directory_based_and_fail_closed(self):
        launcher = (ROOT / "run.cmd").read_text(encoding="utf-8")

        self.assertIn('pushd "%~dp0"', launcher)
        self.assertIn("openggf-artifact.properties", launcher)
        self.assertIn("finalName", launcher)
        self.assertIn("if errorlevel 1", launcher)
        self.assertIn("if not exist \"%JAR%\"", launcher)
        self.assertIn('java --add-exports', launcher)


if __name__ == "__main__":
    unittest.main()
