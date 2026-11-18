"""Build/install the shared assistant. Never downloads or replaces the modpack itself."""

import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.config import CONFIG, HERE, minecraft_dir, write_json
from monifactory_assistant.persistence import atomic_json


def check_instance(instance):
    if not (instance / "mods").is_dir() or not (instance / "kubejs").is_dir():
        raise RuntimeError(
            f"Instance Monifactory introuvable : {instance}. Renseigne minecraft_dir dans codex.local.json."
        )


def java_home(instance):
    candidates = []
    for configured in (os.environ.get("JAVA_HOME"), CONFIG.get("java_home")):
        if configured:
            candidates.append(Path(configured).expanduser())
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
    suffix = ".exe" if os.name == "nt" else ""
    for candidate in candidates:
        exe = candidate / f"bin/java{suffix}"
        # Gradle compiles with this Java: a runtime without javac (CurseForge on Windows) fails.
        if not exe.is_file() or not (candidate / f"bin/javac{suffix}").is_file():
            continue
        result = subprocess.run([str(exe), "-version"], capture_output=True, text=True)
        if result.returncode == 0 and 'version "17.' in result.stderr + result.stdout:
            return candidate
    raise RuntimeError(
        "JDK 17 introuvable. Indique son dossier dans java_home de assistant.local.json, "
        "ou définis JAVA_HOME."
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


def backup_manifest(backup):
    manifest = json.loads((backup / "installation.json").read_text())
    if (
        not isinstance(manifest, dict)
        or not isinstance(manifest.get("mods"), list)
        or not all(assistant_jar(name) for name in manifest["mods"])
        or type(manifest.get("script")) is not bool
    ):
        raise RuntimeError("Manifeste de sauvegarde du mod invalide.")
    return manifest


def restore_install(instance, backup):
    """Restore only this mod and its client script from a completed installation backup."""
    instance, backup = Path(instance), Path(backup)
    manifest = backup_manifest(backup)
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


def assistant_jar(name):
    return (
        isinstance(name, str)
        and name == Path(name).name
        and "\\" not in name
        and name.startswith("assistantia-")
        and name.endswith(".jar")
    )


def pending_path(root):
    return Path(root) / ".runtime/install-pending.json"


def require_closed(game_running):
    if game_running() is not False:
        raise RuntimeError("Ferme Minecraft pour installer ou reprendre la mise à jour.")


def stage_pair(instance, jar, script):
    target = instance / "mods" / jar.name
    client = instance / "kubejs/client_scripts/assistant_ia.js"
    client.parent.mkdir(parents=True, exist_ok=True)
    staged_jar, staged_script = target.with_suffix(".jar.tmp"), client.with_suffix(".js.tmp")
    shutil.copy2(jar, staged_jar)
    shutil.copy2(script, staged_script)
    return target, client, staged_jar, staged_script


def replace_pair(target, client, staged_jar, staged_script, replaced):
    staged_jar.replace(target)
    staged_script.replace(client)
    for path in replaced:
        if path != target:
            path.unlink(missing_ok=True)


def recover_install(instance, root, game_running, *, revision=None, remember=None):
    """Restore the old pair or finish the target pair using only the durable local copies."""
    from monifactory_assistant.commands.auto_update import digest

    pending = pending_path(root)
    try:
        data = json.loads(pending.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return False
    if not isinstance(data, dict) or data.get("v") != 1:
        raise RuntimeError("Journal de mise à jour invalide.")
    if data.get("instance") != str(instance.resolve()) or not assistant_jar(data.get("jar")):
        raise RuntimeError("La reprise concerne une autre instance Minecraft.")
    ident = data.get("backup")
    if not isinstance(ident, str) or not re.fullmatch(r"\d{8}-\d{6}-\d{6}", ident):
        raise RuntimeError("Sauvegarde de la mise à jour non identifiable.")
    backup = Path(root) / "backups" / ident
    manifest = backup_manifest(backup)
    before, target = data.get("before"), data.get("target")
    if target is not None and (revision is None or revision not in (before, target)):
        raise RuntimeError("La révision Git ne correspond plus à la mise à jour interrompue.")
    jars = list((instance / "mods").glob("assistantia-*.jar"))
    if {jar.name for jar in jars} - set(manifest["mods"]) - {data["jar"]}:
        raise RuntimeError("Un autre JAR a été ajouté depuis l’interruption.")
    script = instance / "kubejs/client_scripts/assistant_ia.js"
    installed = (
        len(jars) == 1
        and jars[0].name == data["jar"]
        and script.is_file()
        and digest(jars[0]) == data.get("jar_sha256")
        and digest(script) == data.get("script_sha256")
    )
    if target is not None and revision == target and (before != target or installed):
        if not installed:
            jar = backup / "nouvelle-version" / data["jar"]
            client = jar.with_name("assistant_ia.js")
            if digest(jar) != data.get("jar_sha256") or digest(client) != data.get("script_sha256"):
                raise RuntimeError("Copie de la nouvelle version invalide.")
            staged = stage_pair(instance, jar, client)
            require_closed(game_running)
            replace_pair(*staged, jars)
        if remember is None:
            raise RuntimeError("La mise à jour doit reprendre depuis son lanceur.")
        remember(target, expected=data)
    elif target is not None or not installed:
        require_closed(game_running)
        restore_install(instance, backup)
    pending.unlink()
    return True


def install(
    instance,
    dry_run=False,
    *,
    jar=None,
    client_script=None,
    root=None,
    game_running=None,
    revisions=None,
):
    from monifactory_assistant.commands.auto_update import digest, minecraft_running

    root = HERE if root is None else Path(root)
    game_running = minecraft_running if game_running is None else game_running
    check_instance(instance)
    jar = artifact() if jar is None else Path(jar)
    client_script = HERE / "jeu/assistant_ia.js" if client_script is None else Path(client_script)
    if not assistant_jar(jar.name) or not jar.is_file() or not client_script.is_file():
        raise RuntimeError("Compilation ou script client manquant : aucun fichier installé.")
    script = instance / "kubejs/client_scripts/assistant_ia.js"
    replaced = sorted((instance / "mods").glob("assistantia-*.jar"))
    print(f"Instance : {instance}\nMod : {jar.name}\nScript client : {script}")
    if dry_run:
        print("Simulation : aucun fichier modifié.")
        return
    require_closed(game_running)
    if revisions is None:
        recover_install(instance, root, game_running)
        replaced = sorted((instance / "mods").glob("assistantia-*.jar"))
    pending = pending_path(root)
    if pending.exists():
        raise RuntimeError("Une mise à jour précédente est encore en attente de reprise.")
    backup = root / "backups" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    backup.mkdir(parents=True)
    for path in [*replaced, script]:
        if path.is_file():
            shutil.copy2(path, backup / path.name)
    write_json(
        backup / "installation.json",
        {"mods": [p.name for p in replaced], "script": script.is_file()},
    )
    # Préparer les deux membres et une copie durable avant de modifier l'installation.
    staged = stage_pair(instance, jar, client_script)
    incoming = backup / "nouvelle-version"
    incoming.mkdir()
    shutil.copy2(staged[2], incoming / jar.name)
    shutil.copy2(staged[3], incoming / script.name)
    require_closed(game_running)
    before, revision = revisions or (None, None)
    pending.parent.mkdir(parents=True, exist_ok=True)
    atomic_json(
        pending,
        dict(
            v=1,
            instance=str(instance.resolve()),
            backup=backup.name,
            before=before,
            target=revision,
            jar=jar.name,
            jar_sha256=digest(staged[2]),
            script_sha256=digest(staged[3]),
        ),
        backup=False,
    )
    require_closed(game_running)
    try:
        replace_pair(*staged, replaced)
    except (Exception, KeyboardInterrupt, SystemExit):
        # Catchable cancellation also restores the pair, without swallowing the exit.
        # SIGKILL/power loss cannot run this rollback; the backup remains available.
        restore_install(instance, backup)
        pending.unlink()
        raise
    if revisions is None:
        pending.unlink()
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
    if args.command == "build":
        build(instance)
        return
    from monifactory_assistant.commands.auto_update import minecraft_running
    from monifactory_assistant.platform.support import SingleInstance

    with SingleInstance() as acquired:
        if not acquired:
            raise RuntimeError("Arrête l’assistant avant d’installer le mod.")
        require_closed(minecraft_running)
        if args.command == "setup":
            if not ai_provider.executable():
                raise RuntimeError(
                    f"{ai_provider.label()} introuvable. Installe son CLI et connecte ton compte, ou ouvre Choisir IA."
                )
            build(instance)
        install(instance, game_running=minecraft_running)
        if args.command == "setup":
            write_json(
                HERE / "assistant.local.json",
                dict(CONFIG, provider=ai_provider.provider(), minecraft_dir=str(instance)),
            )


def cli():
    try:
        main()
    except (RuntimeError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"Erreur : {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    cli()
