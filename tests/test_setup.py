"""Java 17 detection for the Gradle build, without a real Java installation."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.commands import setup

SUFFIX = ".exe" if os.name == "nt" else ""


class JavaHomeTests(unittest.TestCase):
    def test_skips_runtime_without_compiler_and_uses_configured_jdk(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp) / "curseforge/minecraft/Instances/Monifactory"
            instance.mkdir(parents=True)
            jre, jdk = Path(tmp) / "java-runtime-gamma", Path(tmp) / "jdk-17"
            for home, tools in ((jre, ["java"]), (jdk, ["java", "javac"])):
                (home / "bin").mkdir(parents=True)
                for tool in tools:
                    (home / f"bin/{tool}{SUFFIX}").touch()

            def run(command, **kwargs):
                # Both report Java 17; only the JDK can compile the mod.
                if command[0].endswith(f"java{SUFFIX}"):
                    return subprocess.CompletedProcess(command, 0, "", 'openjdk version "17.0.8"')
                return subprocess.CompletedProcess(command, 1, "", "")

            with (
                patch.dict(os.environ, {"JAVA_HOME": str(jre)}),
                patch.dict(setup.CONFIG, {"java_home": str(jdk)}),
                patch.object(setup.shutil, "which", return_value=None),
                patch.object(setup.subprocess, "run", side_effect=run),
            ):
                self.assertEqual(setup.java_home(instance), jdk)
                setup.CONFIG.pop("java_home")
                with self.assertRaisesRegex(RuntimeError, "java_home"):
                    setup.java_home(instance)


if __name__ == "__main__":
    unittest.main()
