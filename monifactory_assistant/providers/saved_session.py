"""Identifiant, provenance et dernier usage de la session d’un moteur IA."""

import json
import math
import threading
import uuid
from pathlib import Path

from monifactory_assistant.config import write_json


class SavedSession:
    """Fichier de session commun à Claude et Codex, à côté de l’identifiant du moteur.

    La façade de session décide du renouvellement à partir de cet usage. Le compteur repart
    de zéro seulement quand l’assistant ouvre une nouvelle session : un autre identifiant
    renvoyé pendant une reprise continue la même discussion. Un ancien fichier sans compteur
    garde son identifiant, avec un usage inconnu.
    """

    SESSION_KEY = "session_id"

    def load_session(self, path, data_dir):
        self.path = Path(path)
        self.session_dir = str(Path(data_dir).resolve())
        self.session_id = self.questions = self.last_question_at = None
        self.scope = None
        self.generation = self.session_generation = uuid.uuid4()
        # La remise à zéro ne doit pas attendre le verrou tenu pendant l’inférence.
        self._session_file_lock = threading.Lock()
        try:
            # Une .bak peut précéder une remise à zéro ou un changement de portée.
            saved = json.loads(self.path.read_text(encoding="utf-8"))
            if saved.get("data_dir") != self.session_dir:
                return
            ident = saved.get(self.SESSION_KEY)
            if isinstance(ident, str) and ident:
                self.session_id = ident
            questions, last = saved.get("questions"), saved.get("last_question_at")
        except (ValueError, OSError, AttributeError):
            return
        if type(questions) is int and questions >= 0:
            self.questions = questions
        if type(last) in (int, float) and math.isfinite(last):
            self.last_question_at = last
        token = saved.get("generation")
        if isinstance(token, str):
            try:
                self.generation = self.session_generation = uuid.UUID(hex=token)
            except ValueError:
                return
            self.scope = saved.get("scope")

    def matches_scope(self, scope):
        return (
            scope is not None
            and self.session_generation is self.generation
            and self.scope == scope.fields()
        )

    def usage(self):
        """Identifiant, nombre de questions (None : inconnu) et heure de la dernière question."""
        with self.lock:
            return self.session_id, self.questions, self.last_question_at

    def count_question(self, now, scope):
        """Une question part dans cette session. Un enregistrement impossible ne la bloque pas."""
        with self.lock:
            self.scope = scope.fields() if scope is not None else None
            self.questions = (self.questions or 0) + 1
            self.last_question_at = now
            try:
                self._write_session()
            except OSError:
                pass  # atomic_json a signalé le fichier ; le compte reste valable en mémoire.

    def adopt_session(self, ident):
        with self.lock:
            if ident != self.session_id:
                self.session_id = ident
                self._write_session()

    def forget_session(self):
        with self.lock, self._session_file_lock:
            self.session_id, self.questions, self.last_question_at = None, 0, None
            self.scope = None
            self._write_snapshot(None, 0, None, None)
            self.session_generation = self.generation

    def invalidate_generation(self):
        with self._session_file_lock:
            self.generation = uuid.uuid4()
            try:
                self._write_snapshot(None, 0, None, None)
            except OSError:
                pass  # Le worker de remise à zéro réessaiera l’écriture.

    def _write_session(self):
        with self._session_file_lock:
            if self.session_generation is self.generation:
                self._write_snapshot(
                    self.session_id, self.questions, self.last_question_at, self.scope
                )

    def _write_snapshot(self, ident, questions, last, scope):
        write_json(
            self.path,
            {
                "data_dir": self.session_dir,
                self.SESSION_KEY: ident,
                "questions": questions,
                "last_question_at": last,
                "scope": scope,
                "generation": self.generation.hex,
            },
        )
