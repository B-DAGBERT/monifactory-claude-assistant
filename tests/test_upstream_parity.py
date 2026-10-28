"""Compare gameplay logic to the exact upstream revision used for this port.
Update UPSTREAM_REVISION only when deliberately integrating and reviewing upstream.
"""

import ast
import os
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_REVISION = "1a6a514160d772e4846e453fe3755bab8dbcc133"


def upstream(path):
    git = os.environ.get("GIT_BIN") or next(
        (
            p
            for p in ("/opt/homebrew/bin/git", "/usr/local/bin/git")
            if Path(p).is_file()
        ),
        shutil.which("git"),
    )
    return subprocess.check_output(
        [git, "show", f"{UPSTREAM_REVISION}:{path}"], cwd=ROOT, text=True
    )


def normalize(text):
    return text.replace("Claude Code", "Codex").replace("Claude", "Codex")


def methods(tree):
    result = {}
    for node in ast.parse(normalize(tree)).body:
        if isinstance(node, ast.FunctionDef):
            result[node.name] = ast.dump(node)
        elif isinstance(node, ast.ClassDef):
            for child in node.body:
                if isinstance(child, ast.FunctionDef):
                    result[f"{node.name}.{child.name}"] = ast.dump(child)
    return result


class ParityTests(unittest.TestCase):
    def test_gameplay_functions_preserved(self):
        before = methods(upstream("assistant_mc.py"))
        after = methods((ROOT / "assistant_mc.py").read_text())
        compatibility_changes = {
            "find_claude",
            "clean_env",
            "claude_command",
            "start_claude",
            "read_reply",
            "ask_claude",
            "Conversation.__init__",
            "Conversation.restart",
            "Conversation.stop",
            "Conversation.ask",
            "active_monitor",
            "bring_to_front",
            "make_non_activating",
            "Speaker._windows_voice",
            "App.__init__",
            "App.on_type",
            "App.context",
            "App.work_question",
            "supervise",
            "build_mod_view",
        }
        checked = 0
        for name, code in before.items():
            if name in compatibility_changes:
                continue
            self.assertIn(name, after, name)
            self.assertEqual(code, after[name], name)
            checked += 1
        self.assertGreater(checked, 100)

    def test_kubejs_scripts_identical(self):
        for path in ("jeu/assistant_ia.js", "serveur/assistant_ia_serveur.js"):
            self.assertEqual(upstream(path), (ROOT / path).read_text())

    def test_original_screens_only_change_branding(self):
        for name in ("EcranAssistant", "EcranInfo", "AideEcran"):
            path = f"mod/src/main/java/fr/assistantia/{name}.java"
            before = normalize(upstream(path)).replace(
                "Lance « Lancer assistant.bat »",
                "Lance le lanceur de l’assistant (Mac.command ou .bat)",
            )
            self.assertEqual(before, (ROOT / path).read_text(), name)


if __name__ == "__main__":
    unittest.main()
