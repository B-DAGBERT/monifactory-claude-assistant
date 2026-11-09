"""Échanges atomiques et lecture des relevés du client Minecraft."""

import hashlib
import json
import os
import re
import time

from monifactory_assistant.game.inventory import personal_inventory, recent_state
from monifactory_assistant.game.world_scope import WorldRouting, scope_file, scope_folder
from monifactory_assistant.paths import ROOT as HERE

SCOPED_FILES = {
    "stockage.json",
    "coffres_connus.json",
    "filons.json",
    "stockage.txt",
    "inventaire_details.txt",
    "inventaire_details_contexte.json",
}
SCOPED_INPUTS = {
    "etat.json",
    "monde.json",
    "quetes.json",
    "recherche_emi.json",
    "filons_mod.json",
    "inventaire_joueur.json",
}
SCOPED_OUTPUTS = {"commande.json", "vue_mod.json"}


class Bridge:
    """Échange de fichiers avec le script KubeJS du jeu (dossier kubejs/assistant de l'instance)."""

    def __init__(self, game_dir):
        self.game_dir = game_dir
        self.dir = game_dir / "kubejs" / "assistant"
        self.script_source = HERE / "jeu" / "assistant_ia.js"
        self.script_target = game_dir / "kubejs" / "client_scripts" / "assistant_ia.js"
        version = re.search(
            r"VERSION_SCRIPT = (\d+)", self.script_source.read_text(encoding="utf-8")
        )
        self.script_version = int(version.group(1)) if version else 0
        self.last_state = None
        self.routing = WorldRouting(self.dir, HERE)
        self.scope = None

    def script_outdated(self, state):
        """Le jeu fait tourner une ancienne version du script (F3 + T pas encore fait)."""
        return state is not None and int(state.get("version_script", 0)) != self.script_version

    def install(self):
        """Copie le script dans le jeu. Renvoie True s'il a été ajouté ou modifié."""
        if not (self.game_dir / "kubejs" / "client_scripts").is_dir():
            raise RuntimeError(
                f"Instance Monifactory introuvable : {self.game_dir} (règle MINECRAFT_DIR)"
            )
        self.dir.mkdir(exist_ok=True)
        script = self.script_source.read_bytes()
        if self.script_target.exists() and self.script_target.read_bytes() == script:
            return False
        self.script_target.write_bytes(script)
        return True

    @property
    def isolated(self):
        return self.routing.enabled

    def scope_valid(self, scope):
        return (
            not self.isolated or scope is not None and scope == self.scope == self.routing.current()
        )

    def accepts(self, data):
        scope = self.scope
        return not self.isolated or self.scope_valid(scope) and scope.accepts(data)

    def data_path(self, name, *, create=False):
        if not self.isolated or name not in SCOPED_FILES:
            return self.dir / name
        scope = self.scope
        if not self.scope_valid(scope):
            return None
        return scope_file(scope_folder(self.dir, scope.world, create=create), name)

    @staticmethod
    def read_path(path):
        try:
            return json.loads(path.read_text(encoding="utf-8")) if path is not None else None
        except (OSError, ValueError):
            return None

    def read(self, name):
        data = self.read_path(self.data_path(name))
        if name in SCOPED_INPUTS and not self.accepts(data):
            return None
        return data

    def context_hint(self):
        if not self.isolated:
            return ""
        hint = (
            "Les stocks et détails d'inventaire à la racine sont historiques. "
            "Pour cette réponse, utilise les relevés de partie inclus ci-dessous."
        )
        stock = self.data_path("stockage.txt")
        if stock is None:
            return hint
        if stock.is_file():
            hint += f" Le relevé de stock de ce monde est consultable dans {stock}."
        detail = self.data_path("inventaire_details.txt")
        marker = self.read("inventaire_details_contexte.json")
        try:
            if (
                detail is not None
                and self.accepts(marker)
                and detail.stat().st_size <= 4_000_000
                and hashlib.sha256(detail.read_bytes()).hexdigest() == marker.get("sha256")
            ):
                hint += f" Le dernier détail d'inventaire de cette session est dans {detail}."
        except OSError:
            pass
        return hint

    def write(self, name, data):
        if self.isolated and name in SCOPED_OUTPUTS:
            scope = self.scope
            if not self.scope_valid(scope):
                return
            data = {**data, **scope.fields()}
        self.write_text(name, json.dumps(data, ensure_ascii=False, indent=1))

    def write_text(self, name, text):
        path = self.data_path(name, create=True)
        if path is None:
            return
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        for _ in range(20):  # le jeu peut être en train de lire le fichier
            try:
                os.replace(tmp, path)
                return
            except PermissionError:
                time.sleep(0.05)
        raise RuntimeError(f"Impossible d'écrire {name}")

    def state(self):
        """Dernier état du jeu s'il date de moins de 5 s (jeu lancé, dans un monde), sinon None."""
        now = time.time() * 1000
        isolated = self.isolated
        if isolated:
            scope = self.routing.current(now)
            if scope != self.scope:
                self.scope, self.last_state = scope, None
        state = self.read("etat.json")
        # Une écriture partielle ou une trame invalide ne remplace pas la dernière vue saine.
        if recent_state(state, now) and (
            not isolated or state.get("dimension") == self.scope.dimension
        ):
            self.last_state = state
        if recent_state(self.last_state, now) and (not isolated or self.accepts(self.last_state)):
            return personal_inventory(self.last_state, self.read("inventaire_joueur.json"), now)
        self.scope = None
        return None
