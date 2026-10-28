"""One local Codex app-server per assistant process. Persisted thread IDs, no idle inference.
No transparent replay of submitted turns: connection failures cannot duplicate a request.
"""

import atexit
import json
import os
import queue
import subprocess
import threading
import time
from pathlib import Path

from codex_config import find_codex


class RpcError(RuntimeError):
    pass


class PersistentCodex:
    def __init__(self):
        self.lifecycle = threading.RLock()
        self.lock = threading.RLock()
        self.writes = threading.Lock()
        self.proc = None
        self.pending = {}
        self.listeners = {}
        self.thread_locks = {}
        self.loaded = {}
        self.counter = 0
        self.generation = 0
        self.starts = 0
        self.errors = []
        self.metrics = []

    def _send(self, value):
        with self.writes:
            if not self.proc or self.proc.poll() is not None:
                raise RuntimeError(
                    "Connexion Codex interrompue. Ta demande ne sera pas renvoyée automatiquement."
                )
            try:
                self.proc.stdin.write(json.dumps(value, ensure_ascii=False) + "\n")
                self.proc.stdin.flush()
            except (OSError, ValueError) as e:
                raise RuntimeError("Connexion Codex interrompue.") from e

    def request(self, method, params, timeout=20):
        with self.lock:
            self.counter += 1
            ident = self.counter
            mailbox = queue.Queue()
            self.pending[ident] = mailbox
        try:
            self._send({"id": ident, "method": method, "params": params})
            try:
                reply = mailbox.get(timeout=max(0.01, timeout))
            except queue.Empty as e:
                raise TimeoutError("Délai de réponse du service Codex dépassé.") from e
            if isinstance(reply, Exception):
                raise reply
            if "error" in reply:
                raise RpcError(str(reply["error"].get("message", reply["error"]))[:800])
            return reply.get("result", {})
        finally:
            with self.lock:
                self.pending.pop(ident, None)

    def _read(self, proc, generation):
        try:
            for raw in proc.stdout:
                if generation != self.generation:
                    break
                try:
                    message = json.loads(raw)
                except ValueError:
                    continue
                if not isinstance(message, dict):
                    continue
                if "id" in message and "method" in message:
                    # This integration grants neither approvals nor external interactive tools.
                    self._send(
                        {
                            "id": message["id"],
                            "error": {
                                "code": -32601,
                                "message": "Interaction non disponible ; lecture seule sans approbation.",
                            },
                        }
                    )
                    continue
                with self.lock:
                    if "id" in message:
                        target = self.pending.get(message["id"])
                    else:
                        target = self.listeners.get(
                            message.get("params", {}).get("threadId")
                        )
                if target is not None:
                    target.put(message)
        except (OSError, ValueError, RuntimeError):
            pass
        finally:
            with self.lock:
                if generation == self.generation:
                    failure = RuntimeError(
                        "Connexion Codex interrompue. Session conservée ; aucune requête relancée automatiquement."
                    )
                    for target in list(self.pending.values()) + list(
                        self.listeners.values()
                    ):
                        target.put(failure)
                    self.loaded.clear()

    def _errors(self, proc):
        for line in proc.stderr:
            with self.lock:
                self.errors.append(line[-1000:])
                self.errors = self.errors[-10:]

    def ensure_started(self):
        with self.lifecycle:
            if self.proc and self.proc.poll() is None:
                return
            if self.proc:
                self.close()
            exe = find_codex()
            if not exe:
                raise RuntimeError("Codex CLI introuvable.")
            cmd = [
                exe,
                "app-server",
                "--listen",
                "stdio://",
                "-c",
                'sandbox_mode="read-only"',
                "-c",
                'approval_policy="never"',
                "-c",
                'web_search="live"',
                "-c",
                "project_doc_max_bytes=0",
                "-c",
                "notify=[]",
            ]
            for feature in (
                "apps",
                "plugins",
                "hooks",
                "memories",
                "multi_agent",
                "remote_plugin",
                "skill_mcp_dependency_install",
            ):
                cmd += ["-c", f"features.{feature}=false"]
            env = {
                k: v
                for k, v in os.environ.items()
                if k not in ("OPENAI_API_KEY", "CODEX_API_KEY")
            }
            self.proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                errors="replace",
                env=env,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            self.generation += 1
            self.starts += 1
            proc = self.proc
            threading.Thread(
                target=self._read, args=(proc, self.generation), daemon=True
            ).start()
            threading.Thread(target=self._errors, args=(proc,), daemon=True).start()
            try:
                self.request(
                    "initialize",
                    {
                        "clientInfo": {
                            "name": "monifactory_assistant",
                            "title": "Monifactory Assistant",
                            "version": "1.0.0-codex.1",
                        }
                    },
                )
                self._send({"method": "initialized", "params": {}})
            except Exception:
                self.close()
                raise

    def close(self):
        with self.lifecycle:
            proc = self.proc
            if not proc:
                return
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    proc.kill()
                    proc.wait(timeout=2)
            with self.lock:
                self.proc = None
                self.loaded.clear()
                failure = RuntimeError("Service Codex arrêté. Session conservée.")
                for target in list(self.pending.values()) + list(
                    self.listeners.values()
                ):
                    target.put(failure)
            for stream in (proc.stdin, proc.stdout, proc.stderr):
                try:
                    stream.close()
                except (OSError, ValueError):
                    pass

    def load_thread(self, session_id, data_dir, model, instructions, ephemeral=False):
        key = (str(Path(data_dir).resolve()), instructions)
        with self.lifecycle:
            self.ensure_started()
            if session_id and self.loaded.get(session_id) == key:
                return session_id
            # Effective project settings are read without disclosing any configured credentials.
            config = self.request(
                "config/read", {"includeLayers": False, "cwd": key[0]}
            ).get("config", {})
            overrides = {"project_doc_max_bytes": 0, "notify": [], "web_search": "live"}
            for name in config.get("mcp_servers") or {}:
                overrides[f"mcp_servers.{name}.enabled"] = False
            params = {
                "cwd": key[0],
                "model": model or None,
                "approvalPolicy": "never",
                "sandbox": "read-only",
                "baseInstructions": instructions,
                "developerInstructions": "Consulte les données locales du jeu en priorité. Le web peut compléter les informations absentes. Réponds à la demande sans travail autonome après la réponse.",
                "config": overrides,
            }
            if not session_id:
                params["ephemeral"] = ephemeral
            if session_id:
                params.update(threadId=session_id, excludeTurns=True)
            result = self.request(
                "thread/resume" if session_id else "thread/start", params
            )
            if (
                result.get("approvalPolicy") != "never"
                or result.get("sandbox", {}).get("type") != "readOnly"
            ):
                raise RuntimeError(
                    "Codex n’a pas confirmé les réglages de lecture seule ; aucune demande envoyée."
                )
            ident = result["thread"]["id"]
            self.loaded[ident] = key
            return ident

    def run(
        self,
        prompt,
        session_id,
        data_dir,
        model="",
        on_text=None,
        on_tool=None,
        *,
        instructions="",
        timeout=420,
        reasoning_effort="low",
        on_preview=None,
        ephemeral=False,
        metrics_path=None,
        on_thread=None,
    ):
        if reasoning_effort not in ("low", "medium", "high", "xhigh", "max"):
            raise ValueError("Niveau de raisonnement Codex invalide")
        started = time.monotonic()
        deadline = started + timeout
        metric = {
            "model": model,
            "prompt_chars": len(prompt),
            "status": "failed",
            "first_text_s": None,
        }
        ident = None
        mailbox = None
        acquired = False
        turn_id = None
        turn_lock = None
        try:
            ident = self.load_thread(
                session_id, data_dir, model, instructions, ephemeral
            )
            if on_thread:
                on_thread(ident)
            metric["setup_s"] = round(time.monotonic() - started, 3)
            with self.lock:
                turn_lock = self.thread_locks.setdefault(ident, threading.Lock())
            acquired = turn_lock.acquire(timeout=max(0.01, deadline - time.monotonic()))
            if not acquired:
                raise TimeoutError(
                    "Une réponse est déjà en cours dans cette conversation."
                )
            mailbox = queue.Queue()
            with self.lock:
                self.listeners[ident] = mailbox
            response = self.request(
                "turn/start",
                {
                    "threadId": ident,
                    "input": [{"type": "text", "text": prompt}],
                    "model": model or None,
                    "effort": reasoning_effort,
                    "approvalPolicy": "never",
                    "sandboxPolicy": {"type": "readOnly"},
                },
                timeout=max(0.01, deadline - time.monotonic()),
            )
            turn_id = response["turn"]["id"]
            messages = {}
            phases = {}
            answer = ""
            last_preview = 0
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(
                        "Codex a dépassé le délai. La demande n’a pas été dupliquée."
                    )
                try:
                    event = mailbox.get(timeout=remaining)
                except queue.Empty as e:
                    raise TimeoutError("Codex a dépassé le délai.") from e
                if isinstance(event, Exception):
                    raise event
                kind = event.get("method")
                params = event.get("params", {})
                if params.get("turnId") not in (None, turn_id):
                    continue
                if kind in ("item/started", "item/completed"):
                    item = params.get("item", {})
                    item_id = item.get("id", "")
                    if item.get("type") == "agentMessage":
                        phases[item_id] = item.get("phase")
                        if (
                            kind == "item/completed"
                            and item.get("phase") != "commentary"
                        ):
                            answer = item.get("text", "")
                            messages[item_id] = answer
                    elif (
                        kind == "item/started"
                        and on_tool
                        and item.get("type")
                        in ("commandExecution", "mcpToolCall", "webSearch")
                    ):
                        on_tool(
                            "recherche web"
                            if item.get("type") == "webSearch"
                            else "recherche locale",
                            {},
                        )
                elif kind == "item/agentMessage/delta":
                    item_id = params.get("itemId", "")
                    messages[item_id] = messages.get(item_id, "") + params.get(
                        "delta", ""
                    )
                    if phases.get(item_id) == "final_answer":
                        if metric["first_text_s"] is None:
                            metric["first_text_s"] = round(
                                time.monotonic() - started, 3
                            )
                        if on_preview and time.monotonic() - last_preview >= 0.12:
                            on_preview(messages[item_id])
                            last_preview = time.monotonic()
                elif kind == "turn/completed":
                    turn = params.get("turn", {})
                    if turn.get("id") != turn_id:
                        continue
                    if turn.get("status") != "completed":
                        raise RuntimeError(
                            str(
                                (turn.get("error") or {}).get("message")
                                or "Réponse Codex interrompue ou échouée."
                            )[:800]
                        )
                    if not answer.strip():
                        raise RuntimeError("Réponse Codex incomplète.")
                    metric["status"] = "completed"
                    if on_preview:
                        on_preview(answer)
                    # The voice callback only sees a successfully completed answer.
                    if on_text:
                        on_text(answer)
                    return answer.strip(), ident
        except TimeoutError:
            if ident and turn_id:
                try:
                    self.request(
                        "turn/interrupt",
                        {"threadId": ident, "turnId": turn_id},
                        timeout=2,
                    )
                except (RuntimeError, TimeoutError):
                    pass
            self.close()  # never steer a possibly still-running turn on the next question
            raise
        finally:
            metric["total_s"] = round(time.monotonic() - started, 3)
            metric["at"] = time.time()
            with self.lock:
                self.metrics = (self.metrics + [metric])[-20:]
                if ident and self.listeners.get(ident) is mailbox:
                    self.listeners.pop(ident, None)
            if acquired:
                turn_lock.release()
            if ephemeral and ident:
                try:
                    self.request("thread/unsubscribe", {"threadId": ident}, timeout=2)
                except (RuntimeError, TimeoutError):
                    pass
                with self.lock:
                    self.loaded.pop(ident, None)
                    self.thread_locks.pop(ident, None)
            if metrics_path:
                try:
                    from codex_config import write_json

                    with self.lock:
                        write_json(Path(metrics_path), self.metrics)
                except OSError:
                    pass

    def status(self):
        with self.lock:
            alive = bool(self.proc and self.proc.poll() is None)
            rows = [m for m in self.metrics if m["status"] == "completed"]
            text = (
                "Connexion Codex persistante : "
                + ("ouverte" if alive else "ouverte à la prochaine demande IA")
                + "."
            )
            if rows:
                last = rows[-1]
                text += f"\nDernière réponse : {last['total_s']:.1f} s, dont préparation {last.get('setup_s', 0):.2f} s."
                if last["first_text_s"] is not None:
                    text += f" Premier texte après {last['first_text_s']:.1f} s."
                text += f"\nMoyenne des {len(rows)} dernières réponses réussies : {sum(m['total_s'] for m in rows) / len(rows):.1f} s."
            else:
                text += "\nPas encore de mesure IA depuis ce démarrage."
            return (
                text
                + "\nLa connexion au repos ne lance aucune requête IA. Les outils locaux ne sollicitent pas Codex."
            )


CLIENT = PersistentCodex()
atexit.register(CLIENT.close)
run_persistent = CLIENT.run
