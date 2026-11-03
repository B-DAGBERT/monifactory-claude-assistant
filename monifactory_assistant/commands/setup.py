"""Build/install the shared assistant. Never downloads or replaces the modpack itself."""

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.config import CONFIG, HERE, minecraft_dir, write_json


def check_instance(instance):
    if not (instance / "mods").is_dir() or not (instance / "kubejs").is_dir():
        raise RuntimeError(
            f"Instance Monifactory introuvable : {instance}. Renseigne minecraft_dir dans codex.local.json."
        )


def java_home(instance):
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    if sys.platform == "darwin":
        result = subprocess.run(
            ["/usr/libexec/java_home", "-v", "17"], capture_output=True, text=True
        )
        if result.returncode == 0:
            candidates.append(Path(result.stdout.strip()))
    # CurseForge ships the Java runtime alongside Instances, on both Intel and Apple Silicon.
    candidates.extend(
        sorted(
            (instance.parent.parent / "Install/runtime").glob("**/Contents/Home"),
            key=lambda path: platform.machine() == "arm64" and "arm64" not in str(path),
        )
    )
    if os.name == "nt":
        candidates.extend(
            p.parent.parent
            for p in (instance.parent.parent / "Install/runtime").glob("**/bin/java.exe")
        )
        on_path = shutil.which("java")
        if on_path:
            candidates.append(Path(on_path).resolve().parent.parent)
    for candidate in candidates:
        exe = candidate / ("bin/java.exe" if os.name == "nt" else "bin/java")
        if not exe.is_file():
            continue
        result = subprocess.run([str(exe), "-version"], capture_output=True, text=True)
        if result.returncode == 0 and 'version "17.' in result.stderr + result.stdout:
            return candidate
    raise RuntimeError(
        "Java 17 introuvable. Lance une fois Monifactory dans CurseForge, ou définis JAVA_HOME."
    )


def build(instance):
    env = dict(os.environ, JAVA_HOME=str(java_home(instance)))
    env.setdefault(
        "GRADLE_USER_HOME",
        str(
            HERE / ".runtime/gradle"
            if (HERE / ".runtime/gradle").is_dir()
            else Path.home() / ".gradle"
        ),
    )
    subprocess.run(
        (["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["bash", "gradlew"])
        + ["build", "--console=plain"],
        cwd=HERE / "mod",
        env=env,
        check=True,
    )


def artifact(root=None):
    root = HERE if root is None else Path(root)
    version = next(
        line.split("=", 1)[1]
        for line in (root / "mod/gradle.properties").read_text().splitlines()
        if line.startswith("mod_version=")
    )
    path = root / "mod/build/libs" / f"assistantia-{version}.jar"
    if not path.is_file():
        raise RuntimeError("Mod non compilé : relance Installer Mac.command.")
    return path


def restore_install(instance, backup):
    """Restore only this mod and its client script from a completed installation backup."""
    instance, backup = Path(instance), Path(backup)
    manifest = json.loads((backup / "installation.json").read_text())
    for name in manifest["mods"]:
        target = instance / "mods" / name
        temporary = target.with_suffix(".jar.tmp")
        shutil.copy2(backup / name, temporary)
        temporary.replace(target)
    for target in (instance / "mods").glob("assistantia-*.jar"):
        if target.name not in manifest["mods"]:
            target.unlink()
    script = instance / "kubejs/client_scripts/assistant_ia.js"
    if manifest["script"]:
        temporary = script.with_suffix(".js.tmp")
        shutil.copy2(backup / script.name, temporary)
        temporary.replace(script)
    else:
        script.unlink(missing_ok=True)


def install(instance, dry_run=False, *, jar=None, client_script=None):
    check_instance(instance)
    jar = artifact() if jar is None else Path(jar)
    client_script = HERE / "jeu/assistant_ia.js" if client_script is None else Path(client_script)
    if not jar.is_file() or not client_script.is_file():
        raise RuntimeError("Compilation ou script client manquant : aucun fichier installé.")
    target = instance / "mods" / jar.name
    script = instance / "kubejs/client_scripts/assistant_ia.js"
    replaced = sorted((instance / "mods").glob("assistantia-*.jar"))
    print(f"Instance : {instance}\nMod : {jar.name}\nScript client : {script}")
    if dry_run:
        print("Simulation : aucun fichier modifié.")
        return
    backup = HERE / "backups" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup.mkdir(parents=True)
    for path in [*replaced, script]:
        if path.is_file():
            shutil.copy2(path, backup / path.name)
    write_json(
        backup / "installation.json",
        {"mods": [p.name for p in replaced], "script": script.is_file()},
    )
    try:
        temporary = target.with_suffix(".jar.tmp")
        shutil.copy2(jar, temporary)
        temporary.replace(target)
        for path in replaced:
            if path != target:
                path.unlink()  # already backed up, avoid duplicate mod IDs in Forge
        script.parent.mkdir(parents=True, exist_ok=True)
        temporary = script.with_suffix(".js.tmp")
        shutil.copy2(client_script, temporary)
        temporary.replace(script)
    except Exception:
        restore_install(instance, backup)
        raise
    print(
        f"Installé. Sauvegarde de la version précédente : {backup}\nRedémarre Minecraft pour charger le nouveau .jar."
    )
    return backup


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "build", "install", "setup", "import-veins"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    instance = minecraft_dir()
    check_instance(instance)
    if args.command == "import-veins":
        from monifactory_assistant.game.veins import prepare_import

        result = prepare_import(instance, dry_run=args.dry_run)
        print(
            f"{result['blocs']} anciens blocs, dont {result['restants_historiques']} encore présents au dernier relevé."
        )
        print(
            "Simulation : aucun fichier modifié."
            if args.dry_run
            else "Import préparé pour ce monde. Redémarre Minecraft avec le nouveau mod pour les intégrer."
        )
        return
    if args.command == "check":
        print(
            f"Instance : {instance}\n{ai_provider.label()} : {ai_provider.executable() or 'introuvable'}\nJava : {java_home(instance)}"
        )
        return
    if args.dry_run:
        if args.command not in ("setup", "install"):
            raise RuntimeError("--dry-run s’utilise avec setup ou install.")
        install(instance, dry_run=True)
        return
    if args.command == "setup":
        if not ai_provider.executable():
            raise RuntimeError(
                f"{ai_provider.label()} introuvable. Installe son CLI et connecte ton compte, ou ouvre Choisir IA."
            )
        write_json(
            HERE / "assistant.local.json",
            dict(CONFIG, provider=ai_provider.provider(), minecraft_dir=str(instance)),
        )
    if args.command in ("build", "setup"):
        build(instance)
    if args.command in ("install", "setup"):
        install(instance)


def cli():
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Erreur : {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    cli()
