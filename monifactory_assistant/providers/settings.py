"""Local model preferences, explicitly applied from Minecraft without an AI request."""

import copy
import hashlib
import json
import os
import re
import stat
import tempfile
import time
from pathlib import Path

from monifactory_assistant import providers
from monifactory_assistant.providers.catalog import ModelCatalog

FIELDS = {"model_question", "effort_question", "model_plan", "effort_plan"}
EFFORT_DEFAULTS = {"question": "low", "plan": "medium"}
MODEL_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:/+\-]*(?:\[[A-Za-z0-9]+\])?\Z")
FILES = ("codex.local.json", "assistant.local.json")


class ConfigurationChanged(ValueError):
    pass


class AISettings:
    def __init__(self, root, config=None, *, clock=time.time):
        self.root = Path(root)
        self.config = providers.CONFIG if config is None else config
        self.clock = clock
        self.provider = providers.provider(self.config)
        self.catalog = ModelCatalog()
        self.reply = {}
        self.seen = set()
        self.request_signature = None
        self.initial_error = ""
        try:
            self.baseline = self._read_files()
            if self._merged(self.baseline) != self.config:
                raise ConfigurationChanged()
        except (OSError, ValueError):
            self.baseline = None
            self.initial_error = "Configuration modifiée ou illisible : relance l’assistant."
        self.revision = self._revision(self.baseline)

    def _read_files(self):
        rows = []
        for name in FILES:
            try:
                rows.append((self.root / name).read_bytes())
            except FileNotFoundError:
                rows.append(None)
        return tuple(rows)

    @staticmethod
    def _object(raw):
        value = {} if raw is None else json.loads(raw)
        if not isinstance(value, dict):
            raise ValueError("La configuration doit contenir un objet JSON.")
        return value

    @classmethod
    def _merged(cls, rows):
        config = {}
        for raw in rows:
            config.update(cls._object(raw))
        return config

    @staticmethod
    def _revision(rows):
        digest = hashlib.sha256()
        for raw in rows or ():
            digest.update(b"missing" if raw is None else str(len(raw)).encode() + b":" + raw)
        return digest.hexdigest()

    def values(self):
        result = {}
        for kind in EFFORT_DEFAULTS:
            result["model_" + kind] = providers.model(kind, self.config)
            result["effort_" + kind] = providers.effort(kind, EFFORT_DEFAULTS[kind], self.config)
        return result

    @staticmethod
    def _busy_reason(app):
        if getattr(app, "busy", False) or getattr(app, "info_pending", ()):
            return "Attends la fin de la demande IA ou de la fiche en cours."
        if getattr(getattr(app, "recorder", None), "stream", None) is not None:
            return "Termine l’enregistrement vocal avant de modifier les modèles."
        return ""

    def unavailable(self, app):
        if self.initial_error:
            return self.initial_error
        try:
            if self._read_files() != self.baseline:
                return "Configuration modifiée en dehors du jeu : relance l’assistant."
        except OSError:
            return "Configuration inaccessible : vérifie le fichier puis relance l’assistant."
        return self._busy_reason(app)

    def view(self, app):
        message = self.unavailable(app)
        defaults = {
            "model_" + kind: providers.DEFAULTS[self.provider][kind] for kind in EFFORT_DEFAULTS
        }
        defaults.update({"effort_" + kind: value for kind, value in EFFORT_DEFAULTS.items()})
        return {
            "moteur": self.provider,
            "nom": {"codex": "Codex", "claude": "Claude"}[self.provider],
            "revision": self.revision,
            "modifiable": not message,
            "message": message,
            "valeurs": self.values(),
            "defauts": defaults,
            "efforts": list(providers.EFFORTS_BY_PROVIDER[self.provider]),
            "reponse": dict(self.reply),
            **self.catalog.view(self.provider, self.values(), defaults),
        }

    def _validate(self, values):
        if not isinstance(values, dict) or set(values) != FIELDS:
            raise ValueError("Les deux modèles et leurs niveaux de réflexion sont nécessaires.")
        for kind in EFFORT_DEFAULTS:
            model = values["model_" + kind]
            if not isinstance(model, str) or len(model) > 128 or not MODEL_ID.fullmatch(model):
                raise ValueError(
                    "Nom de modèle invalide : 1 à 128 caractères, sans espace ni option."
                )
            if values["effort_" + kind] not in providers.EFFORTS_BY_PROVIDER[self.provider]:
                raise ValueError("Ce niveau de réflexion n’est pas proposé pour ce moteur.")
        return dict(values)

    def _write_local(self, value):
        """Atomic replacement, with a last conflict check before committing the preferences."""
        path = self.root / FILES[-1]
        raw = json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")
        mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(
                prefix=".assistant-local-", suffix=".tmp", dir=self.root, delete=False
            ) as handle:
                temporary = Path(handle.name)
                os.chmod(temporary, mode)
                handle.write(raw)
                handle.flush()
                os.fsync(handle.fileno())
            if self._read_files() != self.baseline:
                raise ConfigurationChanged(
                    "Configuration modifiée en dehors du jeu : relance l’assistant."
                )
            os.replace(temporary, path)
            temporary = None  # Commit succeeded; cleanup cannot turn it into a false refusal.
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
        return (*self.baseline[:-1], raw)

    def _apply(self, request, app):
        now = self.clock() * 1000
        if not 0 <= now - request["n"] <= 45_000:
            raise ValueError(
                "Demande expirée ou datée dans le futur : applique à nouveau tes réglages."
            )
        if request.get("type") != "configuration_ia" or request.get("moteur") != self.provider:
            raise ValueError("Le moteur actif ne peut pas être changé depuis ce menu.")
        values = self._validate(request.get("valeurs"))
        if request.get("revision") != self.revision:
            # Restart after the disk commit but before Minecraft received the acknowledgement:
            # the desired values are already effective. Acknowledge without another write.
            if (
                not self.initial_error
                and values == self.values()
                and self._read_files() == self.baseline
            ):
                return
            raise ValueError("Ces réglages ont changé : actualise le panneau avant d’appliquer.")
        reason = self.unavailable(app)
        if reason:
            raise ValueError(reason)
        local = self._object(self.baseline[-1])
        # Copy effective providers as well: writing a local providers object must not
        # erase another engine inherited from the legacy configuration's shallow merge.
        configured = copy.deepcopy(self.config.get("providers", {}))
        if not isinstance(configured, dict) or not isinstance(
            configured.get(self.provider, {}), dict
        ):
            raise ValueError(
                "Configuration des moteurs illisible : relance l’assistant après correction."
            )
        configured.setdefault(self.provider, {}).update(values)
        local["providers"] = configured
        baseline = self._write_local(local)
        # One assignment publishes all four values only after the atomic disk commit.
        self.config["providers"] = configured
        self.baseline = baseline
        self.revision = self._revision(baseline)

    def poll(self, bridge, app):
        path = bridge.dir / "configuration_ia.json"
        try:
            file_stat = path.stat()
            signature = (
                file_stat.st_dev,
                file_stat.st_ino,
                file_stat.st_mtime_ns,
                file_stat.st_size,
            )
            if signature == self.request_signature:
                return False
            request = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False  # Retry a partial write; never acknowledge an unreadable request.
        self.request_signature = signature
        if not isinstance(request, dict):
            return False
        ident = request.get("n")
        if isinstance(ident, bool) or not isinstance(ident, int) or ident in self.seen:
            return False
        self.seen.add(ident)
        try:
            self._apply(request, app)
        except (ValueError, TypeError) as error:
            self.reply = {"n": ident, "etat": "refusee", "message": str(error)}
        except OSError:
            self.reply = {
                "n": ident,
                "etat": "refusee",
                "message": "Enregistrement impossible ; les réglages précédents sont conservés.",
            }
        else:
            self.reply = {
                "n": ident,
                "etat": "appliquee",
                "message": "Réglages appliqués aux prochaines demandes.",
            }
        return True
