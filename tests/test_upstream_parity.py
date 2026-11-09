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

from tests.app_source import application_tree

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
        after = methods(ast.unparse(application_tree()))
        tested_bridge_changes = {
            "split_actions",  # Reject scalar action JSON without losing text; test_bridge_resilience.py.
            "App.action_oublier_coffre",  # Coordinates need a dimension; test_bridge_resilience.py.
        }
        compatibility_changes = {
            "find_claude",
            "tool_status",
            "parse_info",
            "App.start_question",
            "App.check_mod_requests",  # question acknowledgements and dedicated AI settings; test_discussion_menu.py/test_ai_settings.py
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
            "describe_state",  # optional exact item selection preserves full mode; test_question_context.py
            "App.work_question",
            "App.on_answer",  # clear the read-only viewer's connection error on success
            "App.on_error",  # retain the writer conflict until a successful reconnect
            "supervise",
            "build_mod_view",
            "Storage.load",  # indexed neighbors preserve legacy newest-half rule; test_storage_memory.py
            "Storage.__init__",  # cache for the immutable export revision
            "Storage.as_text",  # format each distinct minute once; test_storage_snapshots.py
            "World.containers",  # independent adjacent storage and indexed grouping; test_emi_search.py
            "World.merged",  # keep physical chest positions for recipe search, including matching neighbors
            "describe_veins",  # distinguish GT deposits from partial local block observations
            "find_veins",  # local scanner export, tested by test_filons.py
            "Bridge.state",  # backpack inventory snapshot and optional world scope
            "Bridge.__init__",
            "Bridge.read",
            "Bridge.write",
            "Bridge.write_text",
            "App.journal",
            "App.journal_tail",
            "App.on_live_text",  # Reject an in-flight reply from an earlier world/session.
        }
        # User-requested gameplay extensions: scoped rendering, dimension checks, old requests ignored.
        gameplay_changes = {
            "App.refresh",
            "App.check_game",
            "App.machine_at",  # restrict live machine lookup to the requested dimension
            "App.coach",
            "App.flush_coach",
            "App.handle_mod_request",  # old project/objective requests are ignored; test_bridge_resilience.py
        }
        compatibility_changes |= gameplay_changes
        # Retirés à la demande : briefing de reprise, conseils automatiques, alertes de machines.
        compatibility_changes |= {
            "World.alerts",
            "App.check_world_alerts",
            "App.check_coach",
            "App.check_stuck",
            "App.start_briefing",
            "App.work_coach",
            "App.on_coach_message",
            "App.watch_game",
            "App.work_info",  # réflexion réglable à chaud (effort_question)
        }
        compatibility_changes |= {
            "App.on_game_connected",  # no objective is prepared automatically on connection
        }
        # Onglets Objectif et Projet retirés à la demande du joueur : plan, objectifs, projets,
        # pause d'objectif, touche F7 et panneau plan.json n'existent plus.
        compatibility_changes |= {
            "build_view",
            "objective_entry",
            "item_names",
            "has_tool",
            "evaluate",
            "needed_machines",
            "step_count",
            "step_text",
            "step_recipe",
            "craft_suggestions",
            "describe_quests",
            "where_hint",
            "cardinal",
            "nearest_vein",
            "parse_plan",
            "App.load_plan",
            "App.save_plan",
            "App.progress",
            "App.target_totals",
            "App.track_targets",
            "App.current_objective",
            "App.set_step",
            "App.on_plan",
            "App.on_next_objective",
            "App.on_redo_objective",
            "App.on_new_plan",
            "App.start_objective",
            "App.work_plan",
            "App.on_plan_ready",
            "App.action_nouveau_plan",
            "App.action_nouvel_objectif",
            "App.action_effacer_objectif",
            "App.action_effacer_plan",
            "App.action_modifier_plan",
            "App.action_ajouter_objectif",
            "App.action_retirer_objectif",
            "App.action_objectif_suivant",
            "App.action_refaire_objectif",
            "App.action_valider_etape",
            "App.action_decocher_etape",
            "App.action_planifier_usine",
        }
        compatibility_changes |= {
            "log",
            "World.__init__",
            "World.update",
            "World.active",  # validate freshness on the client clock; test_storage_snapshots.py
            # Historic server observations retain dates and are labelled in both providers.
            # The card's status is recalculated when rendering; test_world_freshness.py.
            "describe_world",
            "describe_machines",
            "describe_storage",
            "App.show_info",
            "App.load_places",
            "App.save_places",
            "App.set_setting",
            "App.on_info_ready",
            "App.have",
            "Storage.totals",
            "Storage.placed",
        }  # Atomic backup/recovery and render-scoped caching: test_reliability.py.
        tested_resilience_changes = {
            "main",  # report child startup failure to the supervisor; test_runtime_resilience.py
            "App.check_craftable",  # publish only a complete current catalog; test_recipe_loading.py
            "App.load_recipes",
            "App.describe_journal",  # retain usable records beside a damaged record; test_journal_tail.py
        }
        tested_action_guard_changes = {
            "App.run_actions",  # Closed model-action boundary; test_ai_action_guard.py.
        }
        tested_changes = (
            tested_resilience_changes | tested_bridge_changes | tested_action_guard_changes
        )
        compatibility_changes |= tested_changes
        checked = 0
        for name, code in before.items():
            if name in compatibility_changes:
                continue
            self.assertIn(name, after, name)
            self.assertEqual(code, after[name], name)
            checked += 1
        # About 40 upstream functions belonged to the removed Objectif/Projet features.
        self.assertGreater(checked + len(tested_changes & before.keys()), 70)

    def test_kubejs_gameplay_functions_preserved(self):
        path = "jeu/assistant_ia.js"
        # Only the exchange and persistence entry points gained world validation.
        pattern = re.compile(r"^function (\w+)\([^\n]*\) \{(?:[^\n]*\}$|\n.*?^\})", re.M | re.S)
        # ecrireEtat, ecrireQuetes, suivreConteneur and recevoirMonde also write only on change
        # (or debounced) and hand file work to the mod's writer thread; test_kubejs_exports.py.
        allowed = {
            "ecrireEtat",
            "ecrireQuetes",
            "chargerStockage",
            "chargerMinerais",
            "ecrireMinerais",
            # The mod tracks GregTech veins itself: no KubeJS ore relevé nor filons.json write then.
            "suivreMinerais",
            "suivreConteneur",
            "lirePlan",
            "lireCommande",
            "recevoirMonde",
        }
        # Panneau de secours sans le mod (lignes de plan.json) retiré avec les onglets Objectif
        # et Projet ; ses fonctions d'affichage n'existent plus.
        allowed |= {
            "lireLigne",
            "decouper",
            "decoupe",
            "num",
            "direction",
            "icone",
            "construireLignes",
            "dessinerIcone",
            "dessiner",
        }
        old = {m[1]: m[0] for m in pattern.finditer(upstream(path))}
        new = {m[1]: m[0] for m in pattern.finditer((ROOT / path).read_text())}
        self.assertGreater(len(old), 30)
        for name, function in old.items():
            if name not in allowed:
                self.assertEqual(new.get(name), function, name)

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
