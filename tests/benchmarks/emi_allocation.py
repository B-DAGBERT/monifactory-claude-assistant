"""Compare the real EMI cache keys and planner on an offline fixture (JDK 17)."""

import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BEFORE = "f6dc235"
JAVA_SOURCES = Path("mod/src/main/java/fr/assistantia")
OWNER = JAVA_SOURCES / "compat/emi/MachinesEmi.java"


def snippet(source, pattern):
    match = re.search(pattern, source, re.DOTALL)
    if match is None:
        raise ValueError("La définition du cache EMI a changé. Actualiser le banc.")
    return match[0].removeprefix("private ")


def main():
    home = Path(os.environ.get("JAVA_HOME", "/nonexistent"))
    suffix = ".exe" if os.name == "nt" else ""
    javac = (
        str(home / f"bin/javac{suffix}")
        if (home / f"bin/javac{suffix}").is_file()
        else shutil.which("javac")
    )
    java = (
        str(home / f"bin/java{suffix}")
        if (home / f"bin/java{suffix}").is_file()
        else shutil.which("java")
    )
    if not (javac and java):
        raise SystemExit("JDK 17 requis")
    before = subprocess.check_output(
        ["git", "show", f"{BEFORE}:{OWNER.as_posix()}"],
        cwd=ROOT,
        text=True,
        timeout=10,
    )
    after = (ROOT / OWNER).read_text(encoding="utf-8")
    old_key = snippet(before, r"private static String cleStock\([^\n]+\n.*?\n    }")
    new_key = snippet(after, r"private record RevisionCouverture\(.*?\)\s*\{\s*}")
    with tempfile.TemporaryDirectory() as temporary:
        fragments = Path(temporary) / "ClesEmiMesure.java"
        fragments.write_text(
            f"""package fr.assistantia.compat.emi;
import fr.assistantia.PlanRetrait;
import java.util.List;
final class ClesEmiMesure {{
    record EmiStack(String id, String nbt) {{
        String getId() {{ return id; }}
        String getNbt() {{ return nbt; }}
    }}
    record ItemStack(String id) {{}}
    {old_key}
    {new_key}
}}
""",
            encoding="utf-8",
        )
        sources = [ROOT / JAVA_SOURCES / f"{name}.java" for name in ("PlanRetrait", "PlanMachines")]
        harness = ROOT / "tests/java/fr/assistantia/compat/emi/RevisionEmiBenchmark.java"
        subprocess.run(
            [javac, "-encoding", "UTF-8", "-d", temporary, *sources, fragments, harness],
            check=True,
            timeout=45,
        )
        subprocess.run(
            [
                java,
                "-ea",
                "-cp",
                temporary,
                "fr.assistantia.compat.emi.RevisionEmiBenchmark",
                BEFORE,
            ],
            check=True,
            timeout=60,
        )


if __name__ == "__main__":
    main()
