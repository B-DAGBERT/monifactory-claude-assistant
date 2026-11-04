"""Compare gameplay logic to the exact upstream revision used for this port.
Update UPSTREAM_REVISION only when deliberately integrating and reviewing upstream.
"""

import ast
import os
import re
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_REVISION = "1a6a514160d772e4846e453fe3755bab8dbcc133"


def upstream(path):
    git = os.environ.get("GIT_BIN") or next(
        (p for p in ("/opt/homebrew/bin/git", "/usr/local/bin/git") if Path(p).is_file()),
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


def java_tokens(source):
    """Compare Java tokens, preserving literals and operators while allowing formatting."""
    pattern = r"\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'|//[^\n]*|/\*.*?\*/|[\w$]+|>>>=|>>>|>>=|<<=|>>|<<|==|!=|<=|>=|\+\+|--|&&|\|\||\+=|-=|\*=|/=|%=|&=|\|=|\^=|->|::|\S"
    return [
        token
        for token in re.findall(pattern, source, re.DOTALL)
        if not token.startswith(("//", "/*"))
    ]


class ParityTests(unittest.TestCase):
    def test_java_comparison_ignores_formatting_but_preserves_literals_and_operators(self):
        self.assertEqual(java_tokens("x++; // comment"), java_tokens("x ++ ;"))
        self.assertNotEqual(java_tokens("x++;"), java_tokens("x + + y;"))
        self.assertNotEqual(java_tokens('"a b"'), java_tokens('"ab"'))

    def test_gameplay_functions_preserved(self):
        before = methods(upstream("assistant_mc.py"))
        after = methods((ROOT / "monifactory_assistant/app.py").read_text())
        compatibility_changes = {
            "find_claude",
            "tool_status",
            "parse_info",
            "parse_plan",
            "App.start_question",
            "App.check_mod_requests",  # question acknowledgements, tested by test_discussion_menu.py
            "App.open_popup",
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
            "App.on_answer",  # clear the read-only viewer's connection error on success
            "App.on_error",  # retain the writer conflict until a successful reconnect
            "supervise",
            "build_mod_view",
            "World.merged",  # keep physical chest positions for recipe search, including matching neighbors
            "describe_veins",  # distinguish GT deposits from partial local block observations
            "find_veins",  # local scanner export, tested by test_filons.py
            "nearest_vein",  # skip exhausted veins, like the mod does
            "App.set_step",  # unchecking a step only reopens an objective that is no longer done
            "App.update_objective_progress",  # remember which objective was marked done
        }
        # User-requested gameplay extension, covered by test_objective_pause.py.
        pause_changes = {
            "App.load_plan",
            "App.save_plan",
            "App.refresh",
            "App.check_game",
            "App.coach",
            "App.flush_coach",
            "App.handle_mod_request",
            "App.on_next_objective",
            "App.start_objective",
            "App.on_plan_ready",
            "App.action_effacer_objectif",
        }
        compatibility_changes |= pause_changes
        # Retirés à la demande : briefing de reprise, conseils automatiques, alertes de machines.
        compatibility_changes |= {
            "World.alerts",
            "App.check_world_alerts",
            "App.check_coach",
            "App.check_stuck",
            "App.start_briefing",
            "App.work_coach",
            "App.on_coach_message",
            "App.set_objective_paused",
            "App.handle_project_request",
            "App.watch_game",
        }
        compatibility_changes |= {
            "App.on_game_connected",
            "App.action_nouveau_plan",
            "App.action_nouvel_objectif",
            "App.action_modifier_plan",
            "App.action_objectif_suivant",
            "App.action_refaire_objectif",
            "App.action_planifier_usine",
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

    def test_original_info_screen_only_changes_branding(self):
        # AideEcran now intentionally withdraws chest ingredients at the user's request.
        # Its allocation and click engine are covered by test_emi / test_chest_transfers.
        for name in ("EcranInfo",):
            path = f"mod/src/main/java/fr/assistantia/{name}.java"
            before = normalize(upstream(path)).replace(
                "Lance « Lancer assistant.bat »",
                "Lance le lanceur de l’assistant (Mac.command ou .bat)",
            )
            before = before.replace('"Codex prépare la fiche"', '"L’assistant prépare la fiche"')
            self.assertEqual(java_tokens(before), java_tokens((ROOT / path).read_text()), name)


if __name__ == "__main__":
    unittest.main()
