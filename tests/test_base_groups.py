"""Base machine grouping preserves positions, tier identity and expansion state."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class BaseGroupsTests(unittest.TestCase):
    def test_grouped_machine_rows_keep_each_location_and_ignore_duplicate_clicks(self):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        if not (home / "bin/javac").exists():
            home = (
                Path.home()
                / "Documents/curseforge/minecraft/Install/runtime/java-runtime-gamma/mac-os-arm64/java-runtime-gamma/jre.bundle/Contents/Home"
            )
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        if not javac or not java:
            self.skipTest("JDK 17 required")
        with tempfile.TemporaryDirectory() as directory:
            sources = [
                ROOT / "mod/src/main/java/fr/assistantia/GroupesMachines.java",
                ROOT / "tests/java/fr/assistantia/GroupesMachinesTest.java",
            ]
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *map(str, sources)],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            tested = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.GroupesMachinesTest"],
                capture_output=True,
                text=True,
                timeout=15,
            )
            self.assertEqual(tested.returncode, 0, tested.stdout + tested.stderr)
