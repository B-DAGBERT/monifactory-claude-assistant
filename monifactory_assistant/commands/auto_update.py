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
import threading
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


class BranchChanged(RuntimeError):
    """The installation journal must be resolved against the original main branch."""


class AutoUpdater:
    def __init__(self, root, instance, *, game_running=minecraft_running):
        self.root, self.instance = Path(root), Path(instance)
        self.game_running = game_running
        self.runtime = self.root / ".runtime"
        self.receipt = self.runtime / "installed-update.json"
        self.checkout_journal = self.runtime / "main-pending.json"
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

    def remember_install(self, revision, *, expected=None):
        jars = list((self.instance / "mods").glob("assistantia-*.jar"))
        if len(jars) != 1:
            raise RuntimeError("Un seul mod assistantia doit être installé.")
        files = dict(
            jar=jars[0].name,
            jar_sha256=digest(jars[0]),
            script_sha256=digest(self.instance / "kubejs/client_scripts/assistant_ia.js"),
        )
        if expected is not None and any(expected.get(key) != value for key, value in files.items()):
            raise RuntimeError("Le JAR ou le script a changé avant la confirmation.")
        atomic_json(
            self.receipt,
            dict(
                revision=revision,
                payload=self.payload(revision),
                instance=str(self.instance.resolve()),
                **files,
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

    def deploy(self, stage, before, target):
        from monifactory_assistant.commands import setup

        return setup.install(
            self.instance,
            jar=setup.artifact(stage),
            client_script=stage / "jeu/assistant_ia.js",
            root=self.root,
            game_running=self.game_running,
            revisions=(before, target),
        )

    def require_main(self):
        if self.git("branch", "--show-current") != "main":
            raise BranchChanged(
                "Reviens sur main puis relance l’assistant pour reprendre la mise à jour."
            )

    def verify_checkout(self, target):
        # A competing switch can update files and the index before Git refuses its
        # HEAD change. Verify both against the release before reporting completion.
        self.git("diff", "--quiet", target, "--")
        self.git("diff", "--cached", "--quiet", target, "--")

    def resume_index(self, before, target):
        # A refused switch may already have populated the index with another branch.
        # Use immutable trees. The two-tree checkout refuses to overwrite edits or
        # obstructing untracked files and never moves that branch's reference.
        index = self.git("write-tree")
        if index == self.git("rev-parse", f"{target}^{{tree}}"):
            return
        branches = self.git("for-each-ref", "--format=%(tree)", "refs/heads/").splitlines()
        if index not in branches or index == self.git("rev-parse", f"{before}^{{tree}}"):
            raise RuntimeError("L’index interrompu ne correspond pas à une autre branche locale.")
        self.git("read-tree", "-m", "-u", index, target)

    def advance_main(self, before, target, *, checkout=True):
        # Git locks main and its symbolic HEAD during prepare. The explicit old value
        # protects the branch tip even if another process committed during installation.
        self.require_main()
        # Bytes, not text: a Windows text pipe writes CRLF for each LF and Git rejects "start\r".
        with subprocess.Popen(
            [self.git_bin, "update-ref", "--stdin"],
            cwd=self.root,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        ) as transaction:
            try:
                transaction.stdin.write(
                    f"start\nupdate refs/heads/main {target} {before}\nprepare\n".encode()
                )
                transaction.stdin.flush()
                # A local reference-transaction hook can block during prepare. Keep
                # the launcher's deadline even while waiting for Git's protocol reply.
                watchdog = threading.Timer(30, transaction.kill)
                watchdog.start()
                try:
                    started = transaction.stdout.readline().strip()
                    prepared = transaction.stdout.readline().strip()
                finally:
                    watchdog.cancel()
                if started != b"start: ok" or prepared != b"prepare: ok":
                    # Closing stdin aborts the transaction. Keep Git's reason for update.log.
                    error = transaction.communicate(timeout=30)[1]
                    raise RuntimeError(
                        "Git ne peut pas verrouiller main à la révision attendue. "
                        + error.decode(errors="replace").strip()
                    )
                self.require_main()
                squash_message = self.root / self.git("rev-parse", "--git-path", "SQUASH_MSG")
                if checkout:
                    if self.git("status", "--porcelain") or squash_message.exists():
                        raise RuntimeError("Le dépôt a été modifié avant l’avancement de main.")
                    if before != target:
                        atomic_json(self.checkout_journal, dict(before=before, target=target))
                        # Keep the native fast-forward checkout and its ignored-file
                        # protection. Only the prepared transaction can advance main.
                        self.git(
                            "merge",
                            "--no-overwrite-ignore",
                            "--ff-only",
                            "--squash",
                            target,
                            timeout=30,
                        )
                else:
                    self.resume_index(before, target)
                self.verify_checkout(target)
                squash_message.unlink(missing_ok=True)
                output, error = transaction.communicate(b"commit\n", timeout=30)
                if transaction.returncode or output.strip() != b"commit: ok":
                    raise RuntimeError(
                        "Git n’a pas confirmé l’avancement de main. "
                        + error.decode(errors="replace").strip()
                    )
            finally:
                if transaction.poll() is None:
                    # Closing stdin without commit aborts the prepared reference transaction.
                    transaction.communicate(timeout=30)

        self.require_main()
        self.verify_checkout(target)
        self.checkout_journal.unlink(missing_ok=True)

    def recover_checkout(self):
        if not self.checkout_journal.exists():
            return
        if not self.git_bin or not (self.root / ".git").exists():
            raise RuntimeError("Git est nécessaire pour reprendre la mise à jour interrompue.")
        self.require_main()
        journal = read_json(self.checkout_journal)
        before, target = journal.get("before"), journal.get("target")
        for revision in (before, target):
            if not isinstance(revision, str) or not re.fullmatch(
                r"(?:[0-9a-f]{40}|[0-9a-f]{64})", revision
            ):
                raise RuntimeError("Révision de reprise Git illisible.")
        self.git("merge-base", "--is-ancestor", before, target)
        current = self.git("rev-parse", "HEAD")
        if current == before and self.git("status", "--porcelain"):
            self.advance_main(before, target, checkout=False)
        elif current == target:
            self.verify_checkout(target)
            self.checkout_journal.unlink()
        elif current == before:
            self.checkout_journal.unlink()
        else:
            raise RuntimeError("main ne correspond plus à la mise à jour interrompue.")

    def recover(self):
        from monifactory_assistant.commands.setup import pending_path, recover_install

        self.recover_checkout()
        if not pending_path(self.root).exists():
            return False
        revision = None
        if self.git_bin and (self.root / ".git").exists():
            if read_json(pending_path(self.root)).get("target"):
                self.require_main()
            revision = self.git("rev-parse", "HEAD")
            if self.git("status", "--porcelain", "--untracked-files=no"):
                raise RuntimeError(
                    "Le dépôt a changé pendant l’installation. La reprise est suspendue."
                )
        return recover_install(
            self.instance,
            self.root,
            self.game_running,
            revision=revision,
            remember=self.remember_install,
        )

    def check(self, *, update=True):
        from monifactory_assistant.commands.setup import pending_path

        self.runtime.mkdir(parents=True, exist_ok=True)
        try:
            self.recover()
            return self._check() if update else self.status("disabled")
        except BranchChanged as error:
            return self.status(
                "repair_pending"
                if pending_path(self.root).exists() or self.checkout_journal.exists()
                else "local_changes",
                str(error),
            )
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError):
            with (self.runtime / "update.log").open("a", encoding="utf-8") as log:
                traceback.print_exc(file=log)
            if pending_path(self.root).exists() or self.checkout_journal.exists():
                return self.status(
                    "repair_pending",
                    "Installation à reprendre · ferme Minecraft et relance l’assistant. Voir .runtime/update.log.",
                )
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
        payload = self.payload(target)
        need_mod = not self.installed(payload)
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
                backup = self.deploy(stage, before, target)
            elif not self.installed(payload):
                return self.status(
                    "local_changes",
                    "Mise à jour reportée : le JAR ou le script a changé pendant la préparation.",
                )
            try:
                self.advance_main(before, target)
            except (OSError, RuntimeError, subprocess.SubprocessError):
                # Leave the interrupted checkout for the next launch once a competing
                # Git operation has touched the index. Clean failures can roll back now.
                if not self.checkout_journal.exists() or not self.git("status", "--porcelain"):
                    self.recover()
                raise
            if backup is not None:
                self.recover()
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
    return updater.check(update=CONFIG.get("auto_update", True) is not False)
