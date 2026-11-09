"""Exchange files are rewritten only when they change, and never make the game thread wait.

Java contracts run the real writer against small stubs.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "mod/src/main/java/fr/assistantia"


def jdk():
    home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
    suffix = ".exe" if os.name == "nt" else ""
    javac = home / f"bin/javac{suffix}"
    java = home / f"bin/java{suffix}"
    if javac.is_file() and java.is_file():
        return str(javac), str(java)
    return shutil.which("javac"), shutil.which("java")


def gson_jar():
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    return next(
        (cache / "caches/modules-2/files-2.1/com.google.code.gson/gson").glob("*/*/gson-*.jar"),
        None,
    )


LOGGER = {
    "com.mojang.logging.LogUtils": """
public class LogUtils {public static Logger getLogger(){return new Logger();}
 public static class Logger {public static int warnings;public void warn(String s,Throwable e){warnings++;}}}
""",
}


class JavaContract(unittest.TestCase):
    """Compiles real mod classes with stubs and runs a contract; holds no test itself."""

    def compile_and_run(self, stubs, sources, main, extra=()):
        javac, java = jdk()
        gson = gson_jar()
        if not javac or not java or gson is None:
            self.skipTest("JDK 17 and the Gson cache of the Forge build are required")
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            files = []
            for name, body in stubs.items():
                path = temp.joinpath(*name.split(".")).with_suffix(".java")
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"package {name.rsplit('.', 1)[0]};\n{body}", encoding="utf-8")
                files.append(str(path))
            for name, body in extra:
                path = temp / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(body, encoding="utf-8")
                files.append(str(path))
            classpath = os.pathsep.join([str(temp), str(gson)])
            compiled = subprocess.run(
                [javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(temp), *files, *sources],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=120,
            )
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            game = temp / "game"
            game.mkdir()
            ran = subprocess.run(
                [java, "-ea", f"-Dtest.game={game}", "-cp", classpath, main],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=60,
            )
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)
            return ran.stdout


class JavaExportTests(JavaContract):
    def test_writer_never_sleeps_and_keeps_requests_in_order(self):
        output = self.compile_and_run(
            LOGGER,
            [
                str(JAVA / "Ecritures.java"),
                str(ROOT / "tests/java/fr/assistantia/EcrituresTest.java"),
            ],
            "fr.assistantia.EcrituresTest",
        )
        self.assertIn("Ecritures OK", output)


if __name__ == "__main__":
    unittest.main()
