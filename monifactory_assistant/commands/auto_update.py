"""One startup check, staged build, and guarded installation from origin/main.

No AI calls, background polling, resets, or modification of personal configuration.
"""

import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import traceback
from pathlib import Path

from monifactory_assistant.paths import ROOT

MARKERS = (
    "net.minecraft.client.main.main",
    "cpw.mods.bootstraplauncher.bootstraplauncher",
    "cpw.mods.modlauncher.launcher",
    "--gamedir",
    "--launchtarget forgeclient",
    "net.fabricmc.loader",
    "org.multimc.entrypoint",
    "org.prismlauncher.entrypoint",
)


def minecraft_command(command, executable=None):
    # Restrict markers to Java processes; a shell running a grep must not look like Minecraft.
    first = (
        executable
        if executable is not None
        else (command.strip().split(None, 1)[0].strip('"') if command.strip() else "")
    )
    if not re.search(r"(?:^|[/\\])javaw?(?:\.exe)?$", first, re.I):
        return False
    return any(marker in command.lower() for marker in MARKERS)


def minecraft_running():
    """True/False, or None when process detection is unavailable (installation deferred)."""
    try:
        if os.name == "nt":
            script = (
                "$p=Get-CimInstance Win32_Process -ErrorAction Stop | "
                "Where-Object {$_.Name -match '^javaw?\\.exe$'}; "
                "$found=@($p | Where-Object {$_.CommandLine -match "
                "'(?i)net\\.minecraft|cpw\\.mods\\.(bootstraplauncher|modlauncher)|--gameDir|net\\.fabricmc|org\\.(multimc|prismlauncher)'}).Count -gt 0; "
                "if($found){'running'}elseif(@($p | Where-Object {-not $_.CommandLine}).Count -gt 0){'unknown'}else{'closed'}"
            )
            out = subprocess.run(
                ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                capture_output=True,
                text=True,
                timeout=8,
                check=True,
            ).stdout.strip()
            return True if out == "running" else False if out == "closed" else None
        result = subprocess.run(
            ["ps", "-axo", "pid=,args="], capture_output=True, text=True, timeout=5, check=True
        )
        for line in result.stdout.splitlines():
            parts = line.strip().split(None, 1)
            if len(parts) != 2 or not any(marker in parts[1].lower() for marker in MARKERS):
                continue
            command = subprocess.run(
                ["ps", "-p", parts[0], "-o", "comm="], capture_output=True, text=True, timeout=3
            )
            if command.returncode != 0:
                return None  # Exit or denied access: do not guess that Minecraft is closed.
            if minecraft_command(parts[1], command.stdout.strip()):
                return True
        return False
    except (OSError, subprocess.SubprocessError):
        return None


def read_json(path):
    try:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def atomic_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(path)


def digest(path):
    with Path(path).open("rb") as stream:
        return (
            hashlib.file_digest(stream, "sha256").hexdigest()
            if hasattr(hashlib, "file_digest")
            else hashlib.sha256(stream.read()).hexdigest()
        )


def notice_key():
    """Stable across checks, different for a new release; never use checked_at."""
    data = read_json(ROOT / ".runtime/update-status.json")
    return str(data.get("target") or data.get("revision") or data.get("message") or "")


def notice():
    return read_json(ROOT / ".runtime/update-status.json").get("message", "")


class AutoUpdater:
    def __init__(self, root, instance, *, game_running=minecraft_running):
        self.root, self.instance = Path(root), Path(instance)
        self.game_running = game_running
        self.runtime = self.root / ".runtime"
        self.receipt = self.runtime / "installed-update.json"
        self.git_bin = next(
            (p for p in ("/opt/homebrew/bin/git", "/usr/local/bin/git") if Path(p).is_file()),
            shutil.which("git"),
        )

    def status(self, state, message="", **extra):
        result = dict(state=state, message=message, checked_at=time.time(), **extra)
        atomic_json(self.runtime / "update-status.json", result)
        if message:
            print(message, flush=True)
        return result

    def git(self, *args, timeout=15):
        return subprocess.run(
            [self.git_bin, *args],
            cwd=self.root,
            env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never"),
            capture_output=True,
            text=True,
            timeout=timeout,
            check=True,
        ).stdout.strip()

    def payload(self, revision):
        return self.git("rev-parse", f"{revision}:mod", f"{revision}:jeu/assistant_ia.js")

    def installed(self, payload):
        data = read_json(self.receipt)
        if data.get("payload") != payload or data.get("instance") != str(self.instance.resolve()):
            return False
        jars = list((self.instance / "mods").glob("assistantia-*.jar"))
        script = self.instance / "kubejs/client_scripts/assistant_ia.js"
        try:
            return (
                len(jars) == 1
                and jars[0].name == data.get("jar")
                and digest(jars[0]) == data.get("jar_sha256")
                and digest(script) == data.get("script_sha256")
            )
        except OSError:
            return False

    def remember_install(self, revision):
        jars = list((self.instance / "mods").glob("assistantia-*.jar"))
        if len(jars) != 1:
            raise RuntimeError("Un seul mod assistantia doit être installé.")
        atomic_json(
            self.receipt,
            dict(
                revision=revision,
                payload=self.payload(revision),
                instance=str(self.instance.resolve()),
                jar=jars[0].name,
                jar_sha256=digest(jars[0]),
                script_sha256=digest(self.instance / "kubejs/client_scripts/assistant_ia.js"),
            ),
        )

    def run_logged(self, command, cwd, timeout, **env):
        self.runtime.mkdir(parents=True, exist_ok=True)
        with (self.runtime / "update.log").open("a", encoding="utf-8") as log:
            process = subprocess.Popen(
                command,
                cwd=cwd,
                env=dict(os.environ, MINECRAFT_DIR=str(self.instance), **env),
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=os.name != "nt",
            )
            try:
                code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    subprocess.run(
                        ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                        stdout=log,
                        stderr=subprocess.STDOUT,
                        timeout=10,
                    )
                else:
                    try:
                        os.killpg(process.pid, signal.SIGTERM)
                        process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.wait(timeout=5)
                raise
            if code != 0:
                raise subprocess.CalledProcessError(code, command)

    def build(self, stage):
        from monifactory_assistant.commands.setup import java_home

        # The staged checkout has no assistant.local.json: resolve java_home with the local one.
        self.run_logged(
            [sys.executable, str(stage / "codex_setup.py"), "build"],
            stage,
            600,
            JAVA_HOME=str(java_home(self.instance)),
        )

    def dependencies(self, stage):
        self.run_logged(
            [
                sys.executable,
                "-m",
                "pip",
                "install",
                "--disable-pip-version-check",
                "--retries",
                "0",
                "--timeout",
                "10",
                "-r",
                str(stage / "requirements.txt"),
            ],
            stage,
            180,
        )

    def deploy(self, stage):
        from monifactory_assistant.commands import setup

        return setup.install(
            self.instance, jar=setup.artifact(stage), client_script=stage / "jeu/assistant_ia.js"
        )

    def restore(self, backup):
        from monifactory_assistant.commands.setup import restore_install

        restore_install(self.instance, backup)

    def check(self):
        self.runtime.mkdir(parents=True, exist_ok=True)
        try:
            return self._check()
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
            with (self.runtime / "update.log").open("a", encoding="utf-8") as log:
                traceback.print_exc(file=log)
            return self.status(
                "failed",
                "Mise à jour reportée : vérifie .runtime/update.log. La version disponible démarre.",
            )

    def _check(self):
        if not self.git_bin or not (self.root / ".git").exists():
            return self.status(
                "unavailable", "Mise à jour automatique indisponible : installation Git nécessaire."
            )
        if self.git("branch", "--show-current") != "main" or self.git("status", "--porcelain"):
            return self.status(
                "local_changes",
                "Mise à jour suspendue : branche de développement ou modifications locales.",
            )
        before = self.git("rev-parse", "HEAD")
        try:
            self.git("fetch", "origin", "refs/heads/main:refs/remotes/origin/main", timeout=12)
        except (OSError, subprocess.SubprocessError):
            return self.status(
                "offline", "Vérification des mises à jour indisponible · version locale conservée."
            )
        target = self.git("rev-parse", "origin/main")
        try:
            self.git("merge-base", "--is-ancestor", before, target)
        except subprocess.CalledProcessError:
            return self.status(
                "local_commits", "Mise à jour suspendue : commits locaux à publier sur main."
            )
        need_mod = not self.installed(self.payload(target))
        if before == target and not need_mod:
            return self.status("current")
        if self.game_running() is not False:
            return self.status(
                "pending",
                "Mise à jour disponible — ferme Minecraft puis relance l'assistant.",
                target=target,
            )
        stage = Path(tempfile.mkdtemp(prefix="update-", dir=self.runtime))
        stage.rmdir()  # git worktree creates this owned, isolated checkout.
        backup = None
        try:
            self.git("worktree", "add", "--detach", str(stage), target, timeout=30)
            if need_mod:
                print("Nouvelle version : compilation du mod…", flush=True)
                self.build(stage)
            changed_dependencies = self.git("rev-parse", f"{before}:requirements.txt") != self.git(
                "rev-parse", f"{target}:requirements.txt"
            )
            # Recheck after compilation: the user may have launched the game or edited code.
            if self.game_running() is not False:
                return self.status(
                    "pending",
                    "Mise à jour prête — ferme Minecraft puis relance l'assistant.",
                    target=target,
                )
            if (
                self.git("rev-parse", "HEAD") != before
                or self.git("branch", "--show-current") != "main"
                or self.git("status", "--porcelain")
            ):
                return self.status(
                    "local_changes",
                    "Mise à jour reportée : le dossier a changé pendant la préparation.",
                )
            if changed_dependencies:
                self.dependencies(stage)
            if self.game_running() is not False:
                return self.status(
                    "pending",
                    "Mise à jour prête — ferme Minecraft puis relance l'assistant.",
                    target=target,
                )
            if (
                self.git("rev-parse", "HEAD") != before
                or self.git("branch", "--show-current") != "main"
                or self.git("status", "--porcelain")
            ):
                return self.status(
                    "local_changes",
                    "Mise à jour reportée : le dossier a changé pendant la préparation.",
                )
            if need_mod:
                backup = self.deploy(stage)
            try:
                self.git("merge", "--ff-only", target, timeout=30)
            except (OSError, subprocess.SubprocessError):
                if backup is not None:
                    self.restore(backup)
                raise
            self.remember_install(target)
            return self.status(
                "updated", "Mise à jour installée · tu peux lancer Minecraft.", revision=target
            )
        finally:
            if stage.exists():
                try:
                    self.git("worktree", "remove", "--force", str(stage), timeout=30)
                except (OSError, subprocess.SubprocessError):
                    pass  # Keep the owned staging directory for diagnosis; never touch user files.


def check_at_startup():
    from monifactory_assistant.config import CONFIG, minecraft_dir

    updater = AutoUpdater(ROOT, minecraft_dir())
    if CONFIG.get("auto_update", True) is False:
        return updater.status("disabled")
    return updater.check()
