"""Échanges atomiques et lecture des relevés du client Minecraft."""

import json
import os
import re
import time

from monifactory_assistant.game.inventory import personal_inventory, recent_state
from monifactory_assistant.paths import ROOT as HERE


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

    def read(self, name):
        try:
            return json.loads((self.dir / name).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def write(self, name, data):
        self.write_text(name, json.dumps(data, ensure_ascii=False, indent=1))

    def write_text(self, name, text):
        tmp = self.dir / (name + ".tmp")
        tmp.write_text(text, encoding="utf-8")
        for _ in range(20):  # le jeu peut être en train de lire le fichier
            try:
                os.replace(tmp, self.dir / name)
                return
            except PermissionError:
                time.sleep(0.05)
        raise RuntimeError(f"Impossible d'écrire {name}")

    def state(self):
        """Dernier état du jeu s'il date de moins de 5 s (jeu lancé, dans un monde), sinon None."""
        now = time.time() * 1000
        state = self.read("etat.json")
        # Une écriture partielle ou une trame invalide ne remplace pas la dernière vue saine.
        if recent_state(state, now):
            self.last_state = state
        if recent_state(self.last_state, now):
            return personal_inventory(self.last_state, self.read("inventaire_joueur.json"), now)
        return None
