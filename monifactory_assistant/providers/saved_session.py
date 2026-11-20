"""Session enregistrée d’un moteur IA : identifiant, questions posées et dernier usage."""

import math
from pathlib import Path

from monifactory_assistant.config import write_json
from monifactory_assistant.persistence import load_json


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
        try:
            saved = load_json(self.path, {})
            if saved.get("data_dir") != self.session_dir:
                return
            self.session_id = saved.get(self.SESSION_KEY)
            questions, last = saved.get("questions"), saved.get("last_question_at")
        except (ValueError, OSError, AttributeError):
            return
        if type(questions) is int and questions >= 0:
            self.questions = questions
        if type(last) in (int, float) and math.isfinite(last):
            self.last_question_at = last

    def usage(self):
        """Identifiant, nombre de questions (None : inconnu) et heure de la dernière question."""
        with self.lock:
            return self.session_id, self.questions, self.last_question_at

    def count_question(self, now):
        """Une question part dans cette session. Un enregistrement impossible ne la bloque pas."""
        with self.lock:
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
        with self.lock:
            self.session_id, self.questions, self.last_question_at = None, 0, None
            self._write_session()

    def _write_session(self):
        write_json(
            self.path,
            {
                "data_dir": self.session_dir,
                self.SESSION_KEY: self.session_id,
                "questions": self.questions,
                "last_question_at": self.last_question_at,
            },
        )
