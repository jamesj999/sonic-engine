"""Exercise the website notification shell step without contacting GitHub."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]


class WebsiteNotificationTests(unittest.TestCase):
    def run_notification(self, token="test-token", api_exit=0):
        workflow = (ROOT / ".github/workflows/release.yml").read_text()
        marker = "      - name: Notify website\n"
        self.assertIn(marker, workflow, "Published releases must notify the website")
        step = workflow.split(marker, 1)[1].split("      - name:", 1)[0]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1])
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            client = directory / "gh"
            client.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, pathlib, sys\n"
                "pathlib.Path(os.environ['CALL_FILE']).write_text(json.dumps(sys.argv[1:]))\n"
                "sys.exit(int(os.environ['API_EXIT']))\n"
            )
            client.chmod(0o755)
            call_file = directory / "call.json"
            result = subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", script],
                env={**os.environ, "PATH": f"{directory}:{os.environ['PATH']}",
                     "GH_TOKEN": token, "CALL_FILE": str(call_file),
                     "API_EXIT": str(api_exit)},
                capture_output=True, text=True,
            )
            call = json.loads(call_file.read_text()) if call_file.exists() else None
        return result, call

    def test_dispatches_engine_release_to_website(self):
        result, call = self.run_notification()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("repos/OpenGGF/OpenGGF-WebZone/dispatches", call)
        self.assertIn("POST", call)
        self.assertIn("event_type=engine-release", call)
        self.assertNotIn("test-token", result.stdout + result.stderr)

    def test_missing_secret_fails_without_calling_api(self):
        result, call = self.run_notification(token="")
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(call)
        self.assertIn("WEBZONE_DISPATCH_PAT", result.stdout + result.stderr)

    def test_api_failure_is_not_reported_as_success(self):
        result, call = self.run_notification(api_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNotNone(call)


if __name__ == "__main__":
    unittest.main()
