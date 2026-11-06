"""Explicit inventory/backup/verify/restore CLI; never activates world isolation."""

import argparse
import json
import sys

from monifactory_assistant.data_backup import BackupError, backup, inventory, restore, verify


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Sauvegarder les données, sans migration de monde."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("inventory", "backup"):
        cmd = commands.add_parser(name)
        cmd.add_argument("--python-root", required=True)
        cmd.add_argument("--game-data", required=True, help="Dossier kubejs/assistant exact")
        if name == "backup":
            cmd.add_argument("--destination", required=True)
        cmd.add_argument(
            "--dry-run", action="store_true", help="Inventaire seulement, aucune écriture"
        )
    cmd = commands.add_parser("verify")
    cmd.add_argument("archive")
    cmd = commands.add_parser("restore")
    cmd.add_argument("archive")
    cmd.add_argument(
        "--destination", required=True, help="Parent neuf pour les deux racines restaurées"
    )
    args = parser.parse_args(argv)
    try:
        if args.command == "inventory" or getattr(args, "dry_run", False):
            result, _ = inventory(args.python_root, args.game_data)
            result["dry_run"] = True
        elif args.command == "backup":
            result = backup(args.python_root, args.game_data, args.destination)
        elif args.command == "verify":
            result = verify(args.archive)
            result["verified"] = True
        else:
            result = restore(args.archive, args.destination)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (BackupError, OSError, ValueError) as error:
        print(f"Opération refusée : {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
