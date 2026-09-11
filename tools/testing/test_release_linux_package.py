"""Exercise the release workflow's Linux packaging with a native build fixture."""

import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]


class LinuxPackageTests(unittest.TestCase):
    def test_packages_native_build_without_overwriting_executable(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        step = workflow.split("      - name: Package (Linux)\n", 1)[1].split(
            "      - name:", 1)[0]
        settings, script = step.split("        run: |\n", 1)
        env = dict(re.findall(r"^          (\w+): (.+)$", settings, re.MULTILINE))

        with tempfile.TemporaryDirectory(prefix="openggf linux package ") as tmp:
            project = Path(tmp)
            build = project / env["BUILD_ROOT"]
            (build / "native-libs").mkdir(parents=True)
            executable = build / "OpenGGF"
            payload = b"#!/bin/sh\nexit 0\n"
            executable.write_bytes(payload)
            executable.chmod(0o755)
            (build / "config.yaml").write_text("{}\n", encoding="utf-8")
            (build / "native-libs/liblwjgl.so").write_bytes(b"native fixture")
            for name in ("LICENSE", "NOTICE.md", "CREDITS.md"):
                shutil.copy2(ROOT / name, project / name)
            shutil.copytree(ROOT / "LICENSES", project / "LICENSES")

            result = subprocess.run(
                ["bash", "-e", "-c", textwrap.dedent(script)],
                cwd=project, env={**os.environ, **env}, capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertEqual(executable.read_bytes(), payload)
            self.assertTrue(os.access(executable, os.X_OK))
            # This is the location consumed by both artifact validation and upload.
            with tarfile.open(project / "target/OpenGGF-linux.tar.gz", "r:gz") as archive:
                required = {
                    "OpenGGF/OpenGGF", "OpenGGF/config.yaml", "OpenGGF/liblwjgl.so",
                    "OpenGGF/LICENSE", "OpenGGF/NOTICE.md", "OpenGGF/CREDITS.md",
                    "OpenGGF/LICENSES/LGPL-2.1.txt",
                }
                self.assertTrue(required.issubset(archive.getnames()))
                self.assertEqual(archive.extractfile("OpenGGF/OpenGGF").read(), payload)
                self.assertNotEqual(archive.getmember("OpenGGF/OpenGGF").mode & 0o111, 0)


if __name__ == "__main__":
    unittest.main()
