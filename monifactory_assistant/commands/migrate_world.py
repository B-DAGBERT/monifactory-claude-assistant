"""Explicit identification, preparation, confirmation and opt-in world isolation."""

import argparse
import json
import sys

from monifactory_assistant import world_migration as migration


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Migration des mondes sur copie, jamais au démarrage."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("identify", "prepare", "deactivate"):
        cmd = commands.add_parser(name)
        cmd.add_argument("--python-root", required=True)
        cmd.add_argument("--game-data", required=True)
        if name != "deactivate":
            cmd.add_argument("--receipt", required=True)
        if name == "prepare":
            cmd.add_argument("--destination", required=True)
    cmd = commands.add_parser("confirm")
    cmd.add_argument("preparation")
    cmd.add_argument("--world", required=True)
    cmd.add_argument("--single-server", action="store_true")
    for name in ("activate", "verify"):
        cmd = commands.add_parser(name)
        cmd.add_argument("preparation")
    args = vars(parser.parse_args(argv))
    command = args.pop("command")
    try:
        if command == "verify":
            _, result, _, _ = migration._load(args["preparation"])
        else:
            result = getattr(migration, command)(**args)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (OSError, ValueError, KeyError, TypeError, migration.backup.BackupError) as error:
        print(f"Migration refusée : {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
