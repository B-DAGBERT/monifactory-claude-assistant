"""Read production definitions across modules without starting Tk, audio or a provider.

The focused protocol tests inject a small environment and compile only the methods
under test. Resolve App's real controller bases here so moving a method cannot
silently remove it from those tests.
"""

import ast
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "monifactory_assistant"
MODULES = (
    "settings",
    "prompts",
    "runtime",
    "audio",
    "platform.capture",
    "game.bridge",
    "game.storage",
    "game.recipes",
    "game.world",
    "game.veins",
    "game.context",
    "game.objectives",
    "ui.views",
    "providers.responses",
    "providers.session",
    "application.projects",
    "application.game",
    "application.conversation",
    "application.interface",
    "app",
)


def application_tree():
    nodes = []
    for module in MODULES:
        source = ROOT.joinpath(*module.split(".")).with_suffix(".py")
        nodes.extend(ast.parse(source.read_text(encoding="utf-8")).body)
    classes = {n.name: n for n in nodes if isinstance(n, ast.ClassDef)}
    app = classes["App"]
    inherited = []
    for base in app.bases:
        # Only the controller methods/aliases belong to App, not their class docstrings.
        inherited.extend(n for n in classes[base.id].body if not isinstance(n, ast.Expr))
    app.body.extend(inherited)
    app.bases = []
    return ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[]))
