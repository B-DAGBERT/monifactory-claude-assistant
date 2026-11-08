"""Pure ordering/allocation contract; no native-menu, connection, or rendering qualification."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class MachineOrderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
        java = str(home / "bin/java") if (home / "bin/java").exists() else shutil.which("java")
        javac = str(home / "bin/javac") if (home / "bin/javac").exists() else shutil.which("javac")
        if not java or not javac:
            raise unittest.SkipTest("JDK 17 requis")
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        sources = [
            ROOT / "mod/src/main/java/fr/assistantia" / f"{name}.java"
            for name in ("PlanRetrait", "PlanMachines", "OrdreMachines")
        ]
        sources.append(ROOT / "tests/java/fr/assistantia/OrdreMachinesTest.java")
        result = subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", cls.temp.name, *map(str, sources)],
            capture_output=True,
            text=True,
            timeout=60,
        )
        if result.returncode:
            raise AssertionError(result.stderr)
        cls.command = [java, "-ea", "-cp", cls.temp.name, "fr.assistantia.OrdreMachinesTest"]

    def run_case(self, case):
        result = subprocess.run([*self.command, case], capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        if result.stdout:
            print(result.stdout.strip())

    def test_order_01_to_20_pure_boundaries(self):
        for number in range(1, 21):
            with self.subTest(order=f"ORDER{number:02d}"):
                self.run_case(f"{number:02d}")

    def test_exact_variants_and_pocket_allocations_cannot_move_to_backpack(self):
        self.run_case("variants")

    def test_unlocking_priority_and_ready_before_preparation(self):
        self.run_case("priority")

    def test_current_machine_input_still_requires_native_preconditions(self):
        self.run_case("machine")

    def test_unverifiable_tree_fails_closed(self):
        self.run_case("limits")

    def test_pathological_alternatives_have_bounded_work_and_no_partial_positive(self):
        self.run_case("pathological")
