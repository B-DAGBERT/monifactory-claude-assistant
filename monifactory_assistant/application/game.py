"""Relevés du jeu, journal et actions sur le monde."""

import json
import math
import os
import threading
import time
import traceback
from datetime import datetime

from monifactory_assistant.game.emi_search import RecipeChestSearch
from monifactory_assistant.game.recipes import RecipeIndex, load_craftable
from monifactory_assistant.game.snapshots import file_stamp, frame_value
from monifactory_assistant.game.storage import Storage, StorageMonitor, known_chests
from monifactory_assistant.game.veins import find_veins, vein_files_stamp
from monifactory_assistant.game.world import World, machine_status, observation_date
from monifactory_assistant.game.world_scope import scope_file, scope_folder
from monifactory_assistant.paths import ROOT
from monifactory_assistant.persistence import atomic_json, load_json
from monifactory_assistant.runtime import log
from monifactory_assistant.settings import (
    CHATTY,
    COACH,
    COACH_LEVELS,
    JOURNAL_FILE,
    PLACES_FILE,
    SEARCH_MINUTES,
    USEFUL,
)


class GameController:
    """Relevés du jeu, journal, annonces et actions sur le monde."""

    def load_places(self, path=...):
        self.places_file = PLACES_FILE if path is ... else path
        self.places = load_json(self.places_file, {}) if self.places_file is not None else {}

    def save_places(self):
        path = getattr(self, "places_file", PLACES_FILE)
        if path is not None:
            atomic_json(path, self.places)

    def have(self):
        return frame_value(
            self,
            "inventaire",
            lambda: {k: int(v) for k, v in (self.game or {}).get("inventaire", {}).items()},
        )

    def apply_world_scope(self, scope):
        """Publish no view from the old partition under the newly observed connection."""
        previous = getattr(self, "active_world_scope", None)
        if previous == scope:
            return
        self.active_world_scope = None
        self.game = self.previous_state = None
        self.guide = self.search = self.info = None
        self.message, self.message_until = "", 0
        self.question_ack = {}
        self.mod_request_n = None
        self.last_mod_view = None
        self.quest_titles = self.known_machines = self.known_veins = None
        self.veins_read = self.quests_read = 0
        # Même portée retrouvée après une perte : relire les filons même si le fichier n'a pas changé.
        self.veins, self.veins_stamp = [], None
        self.coach_queue = []
        self.storage_text = self.storage_export_key = None
        self.known_chests_published = None
        self.removed_chests = []
        self.forgotten_keys = set()
        self.storage, self.storage_monitor = Storage([]), StorageMonitor()
        self.emi_search = RecipeChestSearch()
        self.world.invalidate_live()
        if scope is None or previous is None or scope.world != previous.world:
            self.session_open = False
            self.journal_file = None
            self.world = World(enabled=False)
            self.load_places(None)
            if scope is not None:
                folder = scope_folder(ROOT, scope.world, create=True)
                for name in ("monde_memoire.json", "lieux.json"):
                    scope_file(folder, name + ".bak")
                self.world = World(scope_file(folder, "monde_memoire.json"))
                self.load_places(scope_file(folder, "lieux.json"))
                self.journal_file = scope_file(folder, "journal.jsonl")
        self.active_world_scope = scope

    def watch_game(self):
        try:
            state = self.bridge.state()
            if getattr(self.bridge, "isolated", False) is True:
                try:
                    self.apply_world_scope(self.bridge.scope if state else None)
                except (OSError, ValueError):
                    self.bridge.scope = None
                    raise
            if state and not self.game:
                log("Jeu connecté")
                self.on_game_connected()
            elif self.game and not state:
                self.game_lost_at = time.time()
            if not state and self.session_open and time.time() - self.game_lost_at > 60:
                self.session_open = False
                self.journal("session", "Fin de la partie")
            if self.game and (not state or self.game.get("dimension") != state.get("dimension")):
                self.world.invalidate_live()
            self.game = state
            self.check_craftable()
            if state:
                self.check_game(state)
                self.check_death(state)
                self.previous_state = state
            self.flush_coach()
        except Exception:
            log(traceback.format_exc())
        try:
            self.refresh()
        except Exception:
            log(traceback.format_exc())
        finally:
            self.root.after(1000, self.watch_game)

    def check_game(self, state):
        if getattr(self.bridge, "isolated", False) is True and not self.bridge.scope_valid(
            self.active_world_scope
        ):
            return
        self.check_quests()
        for error in state.get("erreurs", []):
            if error not in self.logged_errors:
                self.logged_errors.add(error)
                log("Erreur dans le jeu : " + error)

        if time.time() - self.veins_read > 5:
            # Le résumé du mod ne change qu'à ses sauvegardes : ne le relire que s'il a changé.
            stamp = vein_files_stamp(self.bridge)
            if stamp is None or stamp != getattr(self, "veins_stamp", None):
                self.veins, self.veins_stamp = find_veins(self.bridge), stamp
                self.check_new_veins()
            self.veins_read = time.time()
        if self.world.poll(self.bridge, state.get("dimension")):
            for error in self.world.errors:
                if error not in self.logged_errors:
                    self.logged_errors.add(error)
                    log("Erreur sur le serveur : " + error)
        storage = self.storage_monitor.refresh(self.bridge, self.world)
        self.publish_known_chests(self.world)
        if storage is not self.storage:
            self.check_new_machines(storage)
        export_key = storage.export_key()
        if export_key != self.storage_export_key:
            text = storage.as_text()
            if text != self.storage_text:
                if self.storage_text is not None:
                    self.check_removed_chests(storage)
                    if state.get("coffre_ouvert"):
                        chest = next(
                            (c for c in storage.containers if c["cle"] == state["coffre_ouvert"]),
                            None,
                        )
                        if chest:
                            self.notify(
                                f"Coffre mémorisé : {len(chest['objets'])} sortes d'objets.", 4
                            )
                self.bridge.write_text("stockage.txt", text)
                self.storage_text = text
            self.storage_export_key = export_key
        self.storage = storage

        if (
            self.guide
            and self.guide.get("x") is not None
            and self.guide.get("z") is not None
            and math.dist((self.guide["x"], self.guide["z"]), (state["x"], state["z"])) < 3
        ):
            self.notify(f"Tu es arrivé : {self.guide['label']}.", 8, speak=True)
            self.guide = None

    def publish_known_chests(self, world):
        """Relevés du serveur pour l'onglet Recette du mod (coffres_connus.json), écrits s'ils changent.

        La mémoire du serveur est remplacée à chaque relevé qui la modifie : même objet, rien à faire.
        """
        blocks = getattr(world, "blocks", None)
        if not isinstance(blocks, dict):
            return
        published = getattr(self, "known_chests_published", None)
        if published is not None and published[0] is blocks:
            return
        if self.bridge.data_path("coffres_connus.json") is None:
            return
        text = json.dumps(known_chests(blocks.values()), ensure_ascii=False, separators=(",", ":"))
        if published is None or published[1] != text:
            try:
                self.bridge.write_text("coffres_connus.json", text)
            except (OSError, RuntimeError):
                return  # Le mod garde sa dernière lecture ; nouvel essai au prochain cycle.
        self.known_chests_published = (blocks, text)

    def journal(self, kind, text):
        path = getattr(self, "journal_file", JOURNAL_FILE)
        if path is None:
            return
        with path.open("a", encoding="utf-8") as f:
            f.write(
                json.dumps({"t": time.time(), "type": kind, "texte": text}, ensure_ascii=False)
                + "\n"
            )

    def journal_tail(self, count=40):
        path = getattr(self, "journal_file", JOURNAL_FILE)
        if path is None or count <= 0:
            return []
        try:
            # Read enough physical JSONL records from the end, including any final partial line.
            with path.open("rb") as stream:
                position = stream.seek(0, os.SEEK_END)
                chunks, newlines = [], 0
                while position and newlines <= count:
                    size = min(position, 8192)
                    position -= size
                    stream.seek(position)
                    chunk = stream.read(size)
                    chunks.append(chunk)
                    newlines += chunk.count(b"\n")
        except OSError:
            return []
        events = []
        for line in b"".join(reversed(chunks)).splitlines()[-count:]:
            try:
                event = json.loads(line.decode("utf-8"))
            except ValueError:
                continue
            if isinstance(event, dict):
                events.append(event)
        return events

    def describe_journal(self, count=30):
        lines = []
        for event in self.journal_tail(count):
            try:
                date = datetime.fromtimestamp(event["t"])
                lines.append(f"{date:%d/%m %H:%M} {event['type']} : {event['texte']}")
            except (KeyError, TypeError, ValueError, OverflowError, OSError):
                continue
        return "Journal récent de la partie :\n" + "\n".join(lines) if lines else ""

    def check_craftable(self):
        """Relit les recettes quand le jeu les a réexportées, ou que le mod a écrit quelle machine fait quoi."""
        if self.recipe_loading:
            return
        changed = self.recipe_stamp()
        if changed is not None and changed != self.craftable_read:
            self.recipe_loading = True
            try:
                threading.Thread(target=self.load_recipes, args=(changed,), daemon=True).start()
            except RuntimeError:
                self.recipe_loading = False
                raise

    def recipe_stamp(self):
        folder = self.bridge.dir
        catalog = file_stamp(folder / "recettes_info.json")
        return (catalog, file_stamp(folder / "machines_recettes.txt")) if catalog else None

    def load_recipes(self, stamp):
        folder = self.bridge.dir
        try:
            loaded = load_craftable(folder), RecipeIndex(folder)
        except Exception:
            self.events.put(("recipes_loaded", stamp, None, traceback.format_exc()))
        else:
            self.events.put(("recipes_loaded", stamp, loaded, None))

    def on_recipes_loaded(self, stamp, loaded, error):
        self.recipe_loading = False
        if error is not None:
            if error not in self.logged_errors:
                self.logged_errors.add(error)
                log(error)
        elif self.recipe_stamp() == stamp:
            self.craftable, self.recipe_index = loaded
            self.craftable_read = stamp

    def on_game_connected(self):
        if self.session_open:
            return
        self.session_open = True
        self.journal("session", "Début de la partie")

    def check_quests(self):
        """Quêtes terminées depuis la dernière lecture (quetes.json est réécrit toutes les 10 s)."""
        if time.time() - self.quests_read < 10:
            return
        self.quests_read = time.time()
        quests = self.bridge.read("quetes.json")
        if not quests:
            return
        titles = set(quests.get("terminees_titres") or [])
        if self.quest_titles is not None:
            for title in sorted(titles - self.quest_titles):
                self.journal("quête", title)
                self.last_progress = time.time()
                self.coach(
                    f"Quête terminée : {title.split(' > ')[-1]} !",
                    USEFUL,
                    key="quête " + title,
                    kind="progression",
                )
        self.quest_titles = titles

    def check_new_machines(self, storage):
        keys = {m["cle"]: m for m in storage.machines}
        if self.known_machines is not None:
            for key in keys.keys() - self.known_machines:
                m = keys[key]
                self.journal("machine", f"{m['nom']} en {int(m['x'])} {int(m['y'])} {int(m['z'])}")
                self.coach(f"Machine retenue : {m['nom']}.", CHATTY, kind="progression")
        self.known_machines = set(keys)

    def check_new_veins(self):
        materials = {v["minerai"]: v for v in self.veins}
        if self.known_veins is not None:
            for material in materials.keys() - self.known_veins:
                v = materials[material]
                name = material.replace("_", " ").title()
                self.journal("filon", f"{name} en {int(v['x'])} {int(v['y'])} {int(v['z'])}")
                self.coach(
                    f"Nouveau filon repéré : {name}, en {int(v['x'])} {int(v['y'])} {int(v['z'])}. "
                    "Je le garde en mémoire.",
                    USEFUL,
                    kind="progression",
                )
        self.known_veins = set(materials)

    def coach(self, text, level=USEFUL, cooldown=0, key=None, speak=True, kind="progression"):
        """kind : la famille du message, que le joueur peut couper dans les réglages (progression)."""
        if COACH_LEVELS.get(COACH, 1) < level or not self.settings.get(kind, True):
            return
        key = key or text
        if time.time() - self.coach_times.get(key, 0) < cooldown:
            return
        self.coach_times[key] = time.time()
        self.coach_queue.append((text, speak, kind))
        self.flush_coach()

    def flush_coach(self):
        """Le coach ne coupe jamais la parole : il attend que tu aies fini de parler et que Codex ait répondu."""
        if not self.coach_queue or self.busy or self.recorder.stream is not None or self.popup:
            return
        text, speak, _kind = self.coach_queue.pop(0)
        self.add_history("claude", text)
        self.notify(text, 15, speak=speak)

    def check_death(self, state):
        """Retient l'endroit de la mort, sans message : « ramène-moi à ma dernière mort »."""
        before = self.previous_state
        if not before or not state.get("mort") or before.get("mort"):
            return
        self.places["dernière mort"] = {
            "x": int(state["x"]),
            "y": int(state["y"]),
            "z": int(state["z"]),
            "dimension": state["dimension"],
        }
        self.save_places()
        self.journal("mort", f"en {int(state['x'])} {int(state['y'])} {int(state['z'])}")

    def check_removed_chests(self, storage):
        """Signale les coffres qui ont disparu de la mémoire du jeu (cassés, ou oubliés à la demande)."""
        keys = {c["cle"] for c in storage.raw}
        for chest in self.storage.raw:
            if chest["cle"] in keys:
                continue
            broken = chest["cle"] not in self.forgotten_keys
            self.forgotten_keys.discard(chest["cle"])
            self.removed_chests.append((time.time(), chest, broken))
            if broken and not any(Storage.twins(chest, c) for _, c, _ in self.removed_chests[:-1]):
                self.notify(
                    f"Coffre en {int(chest['x'])} {int(chest['y'])} {int(chest['z'])} oublié : "
                    "il n'est plus là.",
                    8,
                )
        self.removed_chests = [r for r in self.removed_chests if time.time() - r[0] < 1800]

    def machine_at(self, request):
        """État observé pour la machine regardée, avec indication de fraîcheur."""
        if request.get("origine") != "bloc":
            return ""
        position = (request.get("x"), request.get("y"), request.get("z"))
        dimension = request.get("dimension") or (self.game or {}).get("dimension")
        machine = next(
            (
                m
                for m in self.world.machines()
                if m["dimension"] == dimension
                and (int(m["x"]), int(m["y"]), int(m["z"])) == position
            ),
            None,
        )
        if not machine:
            return ""
        status = machine_status(machine)
        if self.world.observation_live(machine):
            return status
        return f"Dernier relevé ({observation_date(machine.get('t'))}) · {status}"

    def send_command(self, action, **data):
        self.bridge.write("commande.json", {"n": str(time.time_ns()), "action": action, **data})

    def action_oublier_coffre(self, action):
        chests = self.storage.containers
        if action.get("proche") and self.game:
            nearby = [c for c in chests if c["dimension"] == self.game["dimension"]]
            target = min(
                nearby,
                key=lambda c: math.dist(
                    (c["x"], c["y"], c["z"]), (self.game["x"], self.game["y"], self.game["z"])
                ),
                default=None,
            )
        else:
            dimension = action.get("dimension") or (self.game or {}).get("dimension")
            position = (int(action.get("x", 0)), int(action.get("y", 0)), int(action.get("z", 0)))
            target = next(
                (
                    c
                    for c in self.storage.raw
                    if dimension
                    and c.get("dimension") == dimension
                    and (int(c["x"]), int(c["y"]), int(c["z"])) == position
                ),
                None,
            )
        if target is None:
            self.notify("Je ne trouve pas ce coffre dans ma mémoire.", 6)
            return
        keys = [
            c["cle"]
            for c in self.storage.raw
            if c["cle"] == target["cle"] or Storage.twins(c, target)
        ]
        self.forgotten_keys |= set(keys)
        self.send_command("oublier_coffres", cles=keys)

    def action_memoriser_lieu(self, action):
        if not self.game:
            return
        name = str(action.get("nom") or "lieu")
        self.places[name] = {
            "x": int(self.game["x"]),
            "y": int(self.game["y"]),
            "z": int(self.game["z"]),
            "dimension": self.game["dimension"],
        }
        self.save_places()
        self.journal(
            "lieu",
            f"{name} en {self.places[name]['x']} {self.places[name]['y']} {self.places[name]['z']}",
        )

    def action_oublier_lieu(self, action):
        self.places.pop(str(action.get("nom")), None)
        self.save_places()

    def action_guider(self, action):
        place = self.places.get(str(action.get("nom")))
        if place:
            self.guide = {
                "label": str(action["nom"]),
                "x": place["x"],
                "y": place["y"],
                "z": place["z"],
            }
        elif action.get("x") is not None or action.get("y") is not None:
            self.guide = {
                "label": str(action.get("label") or "Destination"),
                "x": action.get("x"),
                "y": action.get("y"),
                "z": action.get("z"),
            }

    def action_arreter_guidage(self, _action):
        self.guide = None

    def action_surligner(self, action):
        patterns = [str(p) for p in action.get("objets") or [] if p][:80]
        if patterns:
            self.search = {
                "label": str(action.get("label") or "Recherche"),
                "objets": patterns,
                "fin": time.time() + SEARCH_MINUTES * 60,
            }

    def action_effacer_surbrillance(self, _action):
        self.search = None
