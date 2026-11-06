"""Durable inbox: reserve before dispatch, acknowledge after, never replay uncertain actions."""

import json
import time
from pathlib import Path

from monifactory_assistant.persistence import atomic_json


class CommandQueue:
    LIMIT = 128
    MAX_AGE_MS = 45_000

    def __init__(self, directory, *, clock=time.time):
        self.directory = Path(directory)
        self.inbox = self.directory / "demandes"
        self.receipt = self.directory / "reponses_demandes.json"
        self.clock = clock
        self.valid = True
        self.error = ""
        try:
            self.rows = (
                json.loads(self.receipt.read_text(encoding="utf-8"))
                if self.receipt.exists()
                else {}
            )
            if not isinstance(self.rows, dict):
                raise ValueError("Historique invalide")
            if any(
                not isinstance(v, dict)
                or isinstance(v.get("n"), bool)
                or not isinstance(v.get("n"), int)
                for v in self.rows.values()
            ):
                raise ValueError("Confirmations invalides")
        except (OSError, ValueError, UnicodeError):
            self.rows = {}
            self.valid = False
            self.error = (
                "Historique des confirmations illisible ; demandes conservées sans réexécution."
            )
        self.last = max(self.rows.values(), key=lambda row: row["n"], default={})
        self.pending = 0

    def _save(self):
        # Entries older than the accepted request window can never execute again.
        cutoff = self.clock() * 1000 - 120_000
        self.rows = {
            k: v for k, v in self.rows.items() if isinstance(v, dict) and v.get("n", 0) >= cutoff
        }
        atomic_json(self.receipt, self.rows, backup=False)

    def _process(self, request, dispatch, restore):
        if not isinstance(request, dict):
            raise ValueError("Demande invalide")
        ident, n = request.get("requete"), request.get("n")
        if (
            not isinstance(ident, str)
            or not ident
            or len(ident) > 80
            or isinstance(n, bool)
            or not isinstance(n, int)
        ):
            raise ValueError("Identifiant invalide")
        age = self.clock() * 1000 - n
        row = self.rows.get(ident)
        if row is not None:
            if row.get("etat") == "traitement":
                row = {
                    "n": n,
                    "etat": "interrompue",
                    "message": "Résultat incertain après interruption. Vérifie l’action avant de la redemander.",
                }
                self.rows[ident] = row
                self._save()
        elif not -5000 <= age <= self.MAX_AGE_MS:
            row = {
                "n": n,
                "etat": "expiree",
                "message": "Demande expirée ; renouvelle ton action.",
            }
            self.rows[ident] = row
            self._save()
        else:
            self.rows[ident] = {"n": n, "etat": "traitement"}
            self._save()  # No dispatch without a durable reservation, on either channel.
            try:
                row = {"n": n, **dispatch(request)}
            except Exception:
                row = {
                    "n": n,
                    "etat": "interrompue",
                    "message": "Action interrompue. Vérifie son résultat avant de réessayer.",
                }
            self.rows[ident] = row
            try:
                self._save()
            except OSError:
                self.rows[ident] = {"n": n, "etat": "traitement"}
                raise
        restore(request, row)
        self.last = {"id": ident, **row}

    def accept_legacy(self, request, dispatch, restore=lambda request, result: None):
        """Use the same receipt when a client has not yet discovered protocol 2."""
        if not self.valid:
            return False
        if not isinstance(request, dict):
            raise ValueError("Demande invalide")
        # Current clients already carry their UUID on the old channel. Keep it if a retry
        # later arrives through the durable inbox; older clients identify clicks by n.
        request = {"requete": "legacy-" + str(request.get("n")), **request}
        try:
            self._process(request, dispatch, restore)
        except OSError:
            self.error = "File momentanément inaccessible ; aucune action renvoyée."
            raise
        self.error = ""
        return True

    def poll(self, dispatch, restore=lambda request, result: None):
        if not self.valid:
            return False
        if not self.inbox.exists():
            self.pending = 0
            return False
        files = sorted(self.inbox.glob("*.json"))[: self.LIMIT + 1]
        self.pending = len(files)
        changed = False
        for path in files[:8]:
            try:
                if path.stat().st_size > 262_144:
                    raise ValueError("Demande trop volumineuse")
                request = json.loads(path.read_text(encoding="utf-8"))
                self._process(request, dispatch, restore)
                path.unlink(missing_ok=True)
                self.pending -= 1
                changed = True
                self.error = ""
            except (ValueError, UnicodeError):
                self.error = "Demande illisible dans la file ; fichier conservé."
                # A complete but malformed file cannot block later valid requests.
                try:
                    path.replace(path.with_suffix(".invalide"))
                except OSError:
                    pass
            except OSError:
                self.error = "File momentanément inaccessible ; aucune action renvoyée."
                break
        return changed

    def view(self):
        return {
            "protocole": 2,
            "en_attente": self.pending,
            "derniere": self.last,
            "erreur": self.error,
        }
