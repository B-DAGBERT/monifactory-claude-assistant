"""Local engine selector. Does not start an AI request or replace gameplay data."""

import argparse
import json

from monifactory_assistant.config import HERE, load_config, write_json
from monifactory_assistant.providers import provider


def select(name):
    provider({"provider": name})
    path = HERE / "assistant.local.json"
    settings = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
    if not isinstance(settings, dict):
        raise ValueError("assistant.local.json doit contenir un objet JSON.")
    settings["provider"] = name
    write_json(path, settings)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("provider", nargs="?", choices=("claude", "codex"))
    args = parser.parse_args()
    name = args.provider
    if not name:
        current = provider(load_config())
        value = (
            input(
                f"Assistant actuel : {current}. Choisis 1 = Claude, 2 = Codex (Entrée = conserver) : "
            )
            .strip()
            .lower()
        )
        name = {"1": "claude", "2": "codex", "claude": "claude", "codex": "codex", "": current}.get(
            value
        )
        if name is None:
            raise ValueError("Choix invalide : configuration conservée.")
    select(name)
    print(
        f"Assistant choisi : {name}. Relance l'assistant pour l'appliquer. Projets et coffres conservés."
    )


if __name__ == "__main__":
    main()
