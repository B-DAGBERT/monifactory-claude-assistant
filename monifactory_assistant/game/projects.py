"""Local project snapshots, atomically stored alongside the legacy active-plan fields."""

import copy
import json
import os
import shutil
from pathlib import Path

FIELDS = (
    "objectif_en_pause",
    "plan",
    "version",
    "atteints",
    "etapes_faites",
    "etapes_decochees",
    "obtenus",
    "derniers_totaux",
    "plan_principal",
    "feuille_de_route",
)


class ProjectStore:
    def __init__(self, path):
        self.path = Path(path)
        data = json.loads(self.path.read_text(encoding="utf-8")) if self.path.exists() else {}
        if not isinstance(data, dict):
            raise ValueError("Sauvegarde des projets illisible : fichier conservé.")
        self.legacy = "projets" not in data
        state = {k: copy.deepcopy(data[k]) for k in FIELDS if k in data}
        self.active = data.get("projet_actif", "principal")
        self.projects = copy.deepcopy(data.get("projets", []))
        if self.legacy:
            name = (
                (state.get("plan_principal") or {}).get("titre")
                or (state.get("plan") or {}).get("objectif")
                or "Mon projet"
            )
            self.projects = [{"id": self.active, "nom": name, "etat": state}]
        if (
            not self.projects
            or any(
                not isinstance(p, dict)
                or not isinstance(p.get("id"), str)
                or not isinstance(p.get("nom"), str)
                or not isinstance(p.get("etat"), dict)
                for p in self.projects
            )
            or len({p["id"] for p in self.projects}) != len(self.projects)
            or not any(p["id"] == self.active for p in self.projects)
        ):
            raise ValueError("Catalogue de projets invalide : sauvegarde conservée.")
        # Active fields remain readable by older versions and existing local workflows.
        self.current()["etat"] = state

    def current(self):
        return next(p for p in self.projects if p["id"] == self.active)

    def snapshot(self):
        return copy.deepcopy(self.current()["etat"])

    def save(self, state):
        self.current()["etat"] = copy.deepcopy(state)
        data = dict(state, schema_projets=1, projet_actif=self.active, projets=self.projects)
        if self.legacy and self.path.exists():
            backup = self.path.with_name(self.path.name + ".avant-projets")
            if not backup.exists():
                shutil.copy2(self.path, backup)
        temporary = self.path.with_name(self.path.name + ".tmp")
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=1)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.replace(self.path)
        self.legacy = False

    def create(self, ident, name):
        if any(p["id"] == ident for p in self.projects):
            return False
        if not ident or len(ident) > 80 or not name.strip():
            raise ValueError("Donne un nom au projet.")
        self.projects.append({"id": ident, "nom": name.strip()[:100], "etat": {}})
        self.active = ident
        return True

    def select(self, ident):
        if not any(p["id"] == ident for p in self.projects):
            raise ValueError("Ce projet n'existe plus.")
        self.active = ident

    def catalog(self, current_state):
        entries = []
        for p in self.projects:
            state = current_state if p["id"] == self.active else p["etat"]
            objectives = (state.get("plan_principal") or {}).get("objectifs", [])
            entries.append(
                {
                    "id": p["id"],
                    "nom": p["nom"],
                    "actif": p["id"] == self.active,
                    "pause": bool(state.get("objectif_en_pause")),
                    "objectif": (state.get("plan") or {}).get("objectif", "À préparer"),
                    "faits": sum(o.get("etat") == "fait" for o in objectives),
                    "total": len(objectives),
                }
            )
        return entries
