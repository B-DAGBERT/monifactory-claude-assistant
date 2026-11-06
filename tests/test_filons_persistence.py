"""The native vein snapshot publisher with controlled I/O and synthetic files only."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class FilonsPersistenceTests(unittest.TestCase):
    def test_pending_snapshots_survive_reconnect_and_failed_publication(self):
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
                ROOT / "mod/src/main/java/fr/assistantia/SauvegardeFilons.java",
                ROOT / "tests/java/fr/assistantia/SauvegardeFilonsTest.java",
            ]
            result = subprocess.run(
                [javac, "-encoding", "UTF-8", "-d", directory, *map(str, sources)],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(
                [java, "-ea", "-cp", directory, "fr.assistantia.SauvegardeFilonsTest", directory],
                capture_output=True,
                text=True,
                timeout=30,
            )
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
