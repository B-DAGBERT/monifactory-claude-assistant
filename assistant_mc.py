"""
Assistant Minecraft (Monifactory) avec Claude, via ton abonnement Claude (pas de clé API).

Dans le jeu (script KubeJS installé automatiquement) : objectif, étape en cours, objets à fabriquer,
matériaux manquants et coffres où les trouver, mis à jour en direct.
  F7          objectif suivant : Claude regarde ta progression et prépare un plan
  F8 (tenir)  parle tant que tu tiens F8, relâche pour envoyer
  Maj+F8      question écrite
  F9          cacher / réafficher l'affichage (coupe aussi la voix)
  F10         lecture à voix haute on/off
  Ctrl+F8     nouvelle conversation
"""

import ctypes
import fnmatch
import glob
import json
import math
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import traceback
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from tkinter import messagebox

import keyboard
import mss
from PIL import Image

# ---------------- Réglages ----------------
MINECRAFT_DIR = Path.home() / "curseforge" / "minecraft" / "Instances" / "Monifactory"
HOTKEY_PLAN = "f7"
HOTKEY_TALK = "f8"          # à maintenir pendant qu'on parle
HOTKEY_TYPE = "shift+f8"
HOTKEY_NEW = "ctrl+f8"
HOTKEY_HIDE = "f9"
HOTKEY_VOICE = "f10"
MODEL_PLAN = "sonnet"       # "opus" : plus fort, mais plus lent et consomme plus ton quota
MODEL_QUESTION = "sonnet"   # questions qui demandent de réfléchir (recettes, progression...)
MODEL_QUICK = "haiku"       # questions simples (« j'ai des torches ? », « guide-moi à la base »)
AUTO_NEXT = True            # prépare tout seul l'objectif suivant quand le précédent est terminé
COACH = "normal"            # "calme" : seulement les dangers ; "normal" ; "bavard" : il commente tout
BRIEFING = True             # un point rapide à voix haute quand tu reprends la partie
BRIEFING_PAUSE = 20         # ... seulement après au moins 20 minutes sans jouer
STUCK_MINUTES = 15          # au bout de combien de minutes sans avancer le coach propose son aide
SEARCH_MINUTES = 10         # durée de la surbrillance demandée (« trouve-moi mes torches »)
SPEAK = True                # lire les réponses à voix haute
# Voix : fr-FR-VivienneMultilingualNeural (femme) ou fr-FR-RemyMultilingualNeural (homme) prononcent
# aussi bien les noms anglais du jeu ; autres : fr-FR-DeniseNeural, fr-FR-HenriNeural, fr-FR-EloiseNeural
VOICE = "fr-FR-VivienneMultilingualNeural"
VOICE_SPEED = "+10%"        # vitesse de lecture, par exemple "+0%" ou "+25%"
VOICE_SAMPLE_RATE = 24000
WHISPER_MODEL = "small"     # "base" : plus rapide, "small" : comprend mieux
TIMEOUT = 420               # secondes max par réponse
# ------------------------------------------

HERE = Path(__file__).resolve().parent
CAPTURES = HERE / "captures"
SESSION_FILE = HERE / "session.json"
PLAN_FILE = HERE / "plan_ia.json"
PLACES_FILE = HERE / "lieux.json"
JOURNAL_FILE = HERE / "journal.jsonl"
WORLD_FILE = HERE / "monde_memoire.json"   # ce que le serveur a vu (coffres, machines), gardé entre deux parties
INFO_FILE = HERE / "fiches.json"           # fiches « à quoi ça sert » déjà rédigées par Claude (touche I)
COACH_LEVELS = {"calme": 0, "normal": 1, "bavard": 2}
DANGER, USEFUL, CHATTY = 0, 1, 2  # niveau minimal du coach pour chaque message
LOG_FILE = HERE / "assistant.log"
NO_WINDOW = subprocess.CREATE_NO_WINDOW
SAMPLE_RATE = 16000
user32 = ctypes.windll.user32

BG, FG, FG_DIM = "#1e1f22", "#f2f2f2", "#a8a8a8"

FOOTER = "F7 objectif · F8 parler · Maj+F8 écrire · F9 cacher"
MAX_TO_MAKE, MAX_MISSING = 4, 5   # lignes affichées au plus dans « À fabriquer » et « Il te manque »
WHISPER_HINT = ("Minecraft, Monifactory, GregTech, Steam, Boiler, Alloy Smelter, Coke Oven, "
                "Bronze, Steel, Bender, Wiremill, Creosote, multibloc, JEI, LV, MV.")
SENTENCE_END = re.compile(r"[.!?…](?=\s)|\n")
ACTION_TAG = re.compile(r"<actions>(.*?)(?:</actions>|$)", re.S)
ITEM_ID = re.compile(r"\b[a-z_][a-z0-9_]*:([a-z][a-z0-9_/]*)\b")

ACTIONS_HELP = """Tu peux aussi agir sur l'assistant. Seulement si le joueur le demande (ou si c'est clairement \
ce qu'il veut), termine ta réponse par une ligne <actions>[...]</actions> contenant une liste JSON d'actions :
- {"type": "oublier_coffre", "x": 0, "y": 0, "z": 0} : retirer un coffre de la mémoire (cassé, vidé, déplacé) ; \
{"type": "oublier_coffre", "proche": true} pour le coffre mémorisé le plus proche du joueur. Un coffre cassé \
près du joueur est retiré automatiquement après quelques secondes : s'il figure dans « coffres retirés \
automatiquement », ne retire rien d'autre.
- {"type": "nouveau_plan", "demande": "ce que veut le joueur, par exemple : la quête Steam Grinder"} : remplacer \
l'objectif affiché par un nouveau plan.
- {"type": "effacer_plan"} : retirer l'objectif affiché.
- {"type": "feuille_de_route", "objectifs": [{"titre": "titre court", "demande": "ce qu'il faut faire"}]} : \
remplacer la feuille de route, la suite d'objectifs qui viendront après l'objectif affiché (par exemple tout \
ce qu'il faut pour passer en LV, dans l'ordre, 3 à 10 objectifs). Elle remplace tout : ne l'accompagne pas \
d'ajouter_objectif.
- {"type": "ajouter_objectif", "titre": "...", "demande": "...", "position": 1} (position 1 = juste après \
l'objectif affiché ; sans position, à la fin) et {"type": "retirer_objectif", "titre": "..."}.
- {"type": "objectif_suivant"} : passer tout de suite au prochain objectif de la feuille de route.
- {"type": "planifier_usine", "demande": "2 Steel Ingot par minute"} : calculer une petite usine pour un débit \
voulu (nombre de machines, Steam ou énergie, boilers, ordre de construction) et l'afficher comme objectif.
- {"type": "memoriser_lieu", "nom": "base"} : retenir la position actuelle du joueur sous ce nom.
- {"type": "oublier_lieu", "nom": "base"}
- {"type": "guider", "nom": "base"}, ou {"type": "guider", "label": "Coffre de Redstone", "x": 0, "y": null, \
"z": 0} : afficher une flèche vers un lieu, un coffre, une machine ou un filon (coordonnées fournies) ; \
{"type": "arreter_guidage"}.
- {"type": "surligner", "label": "Torches", "objets": ["minecraft:torch"]} : mettre des objets en surbrillance \
pendant 10 minutes, dans les interfaces (coffres, inventaire, machines) et dans le monde (contour, visible à \
travers les murs, des coffres qui en contiennent). Fais-le dès que le joueur cherche un objet (« trouve-moi mes \
torches ») ou demande une surbrillance. "objets" : des id exacts, ou avec * (« gtceu:*_iron_ore ») ; attention, \
* attrape tout (« gtceu:*_magnetite_ore » prend aussi la Vanadium Magnetite) : liste les id exacts si besoin. Pour une \
famille d'objets (« les minerais qui donnent du Iron Ingot »), cherche d'abord dans recettes_*.json ce qui donne \
ce résultat (four, Blast Furnace, broyage...) et liste tous les id trouvés. {"type": "effacer_surbrillance"} \
quand il a fini (« c'est bon », « enlève la surbrillance »).
- {"type": "affichage", "visible": false} et {"type": "voix", "active": false} (true pour réactiver).
Quand tu fais une action, dis-le en une phrase courte."""

SYSTEM_PROMPT = """Tu es un assistant intégré au jeu Minecraft du joueur. Il joue au modpack Monifactory \
(Minecraft 1.20.1, GregTech CEu Modern) en mode EXPERT, sur un serveur avec un ami.
Données du jeu en temps réel, en lecture seule, dans {data_dir} :
- etat.json : position, inventaire, écran ouvert.
- quetes.json : progression des quêtes FTB (chapitres, quêtes terminées, quêtes disponibles avec leurs tâches \
et leur description).
- recettes_*.json : TOUTES les recettes du pack, une par ligne "catégorie | entrées => sorties | id". \
Cherche-les avec Grep, ce sont les vraies recettes du mode Expert.
- stockage.txt : le contenu des coffres que le joueur a ouverts, une ligne par objet \
"id | quantité | x y z | dimension | bloc | date". Cherche dedans avec Grep pour dire où aller chercher un objet.
Règles :
- Réponds en français, mais écris tous les noms d'objets, de machines et de fluides en anglais, \
exactement comme dans le jeu.
- Réponses de 1 à 4 phrases courtes et concrètes, sans markdown (ni **, ni #, ni tableaux) : elles sont \
affichées dans le jeu et lues à voix haute. Si le joueur demande une liste, une ligne courte par élément \
(10 au plus) commençant par un tiret. Commence directement par la réponse. N'écris jamais d'identifiant \
(minecraft:flint) : écris le nom du jeu (Flint).
- Quand on te demande si le joueur a un objet ou où le trouver, regarde son inventaire ET ses coffres (liste \
fournie avec la question) et donne les coordonnées du coffre.
- Vérifie une recette dans recettes_*.json avant de la donner. Si tu n'es pas sûr, dis-le."""

PLAN_REQUEST = """Prépare mon prochain objectif de progression.
Choisis l'objectif le plus utile maintenant (en priorité une quête principale disponible). Vérifie les \
recettes avec Grep dans recettes_*.json (exemple de motif : "=> [0-9x ]*gtceu:steel_ingot") et tiens compte \
de mon inventaire et de mes coffres.
Réponds UNIQUEMENT avec ce JSON, sans texte autour ni bloc de code :
{{"objectif": "titre court (max 50 caractères)",
 "pourquoi": "1 phrase : pourquoi cet objectif maintenant, ce qu'il débloque pour la suite",
 "etapes": [{{"texte": "étape courte", "item": "id de l'objet que l'étape produit, ou null", "quantite": 3}}],
 "cibles": [{{"item": "id", "nom": "Nom anglais", "quantite": 1}}],
 "recettes": [{{"item": "id", "nom": "Nom anglais", "sortie": 1, "machine": "Crafting Table",
   "id": "id de la recette (après le dernier | de la ligne trouvée)",
   "entrees": [{{"item": "id", "nom": "Nom anglais", "quantite": 2}}],
   "outils": [{{"item": "id", "nom": "Nom anglais"}}]}}],
 "cible": null,
 "resume": "1 ou 2 phrases à lire à voix haute : quoi faire et pourquoi",
 "feuille_de_route": [{{"titre": "titre court", "demande": "ce qu'il faudra faire"}}]}}
Règles :
- "cibles" : les 1 à 4 objets à avoir à la fin de l'objectif (si je dois construire un multibloc, ses blocs). \
Ne me fais pas refabriquer une machine que j'ai déjà posée (liste fournie).
- "recettes" : une recette pour CHAQUE objet à fabriquer (cibles et intermédiaires), avec les quantités exactes \
du pack ("sortie" = nombre d'objets produits par une fabrication). Les objets sans recette dans la liste sont les \
matières premières : arrête-toi aux matériaux simples (lingots, minerais, poussières, bois, argile…).
- "outils" : les outils non consommés (Hammer, File, Screwdriver, Saw, Wrench, Mortar…), jamais dans "entrees". \
Si j'en ai un dans mon inventaire, donne son id exact.
- "etapes" : 3 à 6 étapes dans l'ordre, de moins de 70 caractères, rédigées en français avec les noms d'objets \
en anglais comme dans le jeu (« Fabrique 3 Iron Rods »), jamais les ids. \
"item" et "quantite" servent à cocher l'étape automatiquement quand je l'ai faite.
- "cible" : {{"label": "...", "x": 0, "y": 0, "z": 0}} seulement si je dois aller à un endroit précis \
(x et z à null si seule la hauteur compte, par exemple pour miner à la bonne couche), sinon null.
- "feuille_de_route" : seulement si je n'en ai pas encore (voir le contexte) : les 4 à 8 objectifs qui suivront \
celui-ci, dans l'ordre de la progression du pack. Sinon, une liste vide.

{context}"""


FACTORY_REQUEST = """Planifie une petite usine : {demande}.
Calcule avec les vraies recettes : les lignes GregTech finissent par « Nt XEU/t » (durée en ticks, 20 ticks = \
1 seconde, et consommation).
- Machines nécessaires = débit voulu ÷ (objets produits par recette ÷ durée de la recette en secondes), arrondi \
au-dessus.
- Machines à vapeur en Bronze (LP) : durée ×2, et EU/t × 20 mB de Steam par seconde. En Steel (HP) : durée \
normale, EU/t × 40 mB/s.
- Boilers une fois chauds : Small Steam Solid Boiler 120 mB/s, High Pressure 300 ; Liquid 240 et 600 ; Solar \
120 et 360.
- Machines électriques : chaque palier au-dessus de celui de la recette divise la durée par 2 et multiplie \
l'EU/t par 4.
Mets dans les cibles les machines et les boilers à fabriquer, en déduisant ceux que j'ai déjà posés ; dans les \
étapes, l'ordre de construction et de branchement (Steam, eau, entrées et sorties) ; et dans le résumé les \
chiffres clés (débit obtenu, Steam nécessaire, nombre de boilers)."""

INFO_REQUEST = """Fiche « à quoi ça sert » pour : {nom} (id {item}).
Dis à quoi sert cet objet, ce bloc ou cette machine dans Monifactory en mode Expert, et comment s'en servir. \
Regarde au besoin dans recettes_*.json ce qu'il fabrique ou à quoi il sert (2 recherches au plus : la fiche \
doit arriver vite).
Réponds UNIQUEMENT avec ce JSON, sans texte autour :
{{"sert": "1 ou 2 phrases : son rôle, et à quel moment de la progression il compte",
 "utilise": ["2 à 4 étapes courtes et concrètes : le poser, l'alimenter (Steam, EU, eau...), brancher ses \
entrées et sorties, s'en servir"],
 "schema": [[{{"item": "id exact", "texte": "rôle en 1 à 3 mots"}}]],
 "astuce": "le piège classique ou le bon réflexe, en 1 phrase (ou chaîne vide)"}}
schema : une ou deux chaînes de 2 à 6 blocs, de gauche à droite, qui montrent comment le brancher (ex. eau -> \
Primitive Pump -> pipe -> Boiler -> machine), ou d'où il vient et où il va s'il sert surtout d'ingrédient ; \
[] pour un objet simple (outil, bloc de construction). Dans le schéma, des id d'objets qui existent (vérifie-les \
dans recettes_*.json), y compris celui de la fiche ; pour un fluide, l'id de son seau ou de son conteneur. \
Dans les textes, jamais d'id : les noms du jeu en anglais."""


def log(message):
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {message}\n")


def drain(q):
    while True:
        try:
            q.get_nowait()
        except queue.Empty:
            return


# ---------------- Lien avec le jeu ----------------

class Bridge:
    """Échange de fichiers avec le script KubeJS du jeu (dossier kubejs/assistant de l'instance)."""

    def __init__(self, game_dir):
        self.game_dir = game_dir
        self.dir = game_dir / "kubejs" / "assistant"
        self.script_source = HERE / "jeu" / "assistant_ia.js"
        self.script_target = game_dir / "kubejs" / "client_scripts" / "assistant_ia.js"
        version = re.search(r"VERSION_SCRIPT = (\d+)", self.script_source.read_text(encoding="utf-8"))
        self.script_version = int(version.group(1)) if version else 0
        self.last_state = None

    def script_outdated(self, state):
        """Le jeu fait tourner une ancienne version du script (F3 + T pas encore fait)."""
        return state is not None and int(state.get("version_script", 0)) != self.script_version

    def install(self):
        """Copie le script dans le jeu. Renvoie True s'il a été ajouté ou modifié."""
        if not (self.game_dir / "kubejs" / "client_scripts").is_dir():
            raise RuntimeError(f"Instance Monifactory introuvable : {self.game_dir} (règle MINECRAFT_DIR)")
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
        state = self.read("etat.json")
        if state:  # le jeu écrit sans précaution : une lecture ratée garde l'état précédent
            self.last_state = state
        state = self.last_state
        if state and time.time() * 1000 - state.get("t", 0) < 5000:
            return state
        return None


class Storage:
    """Coffres mémorisés par le jeu (stockage.json) : ce qu'il y a dedans et où ils sont."""

    def __init__(self, containers, raw=None, machines=None):
        self.containers = containers
        self.raw = containers if raw is None else raw   # avec les deux moitiés des grands coffres
        self.machines = machines or []                  # blocs à interface ouverts au moins une fois

    @staticmethod
    def load(bridge):
        """Les coffres mémorisés, ou None si le fichier est illisible pour l'instant (le jeu est en train de
        l'écrire) : il ne faut surtout pas croire que tous les coffres ont disparu."""
        data = bridge.read("stockage.json")
        if data is None:
            return None if (bridge.dir / "stockage.json").exists() else Storage([])
        # Les coffres mémorisés par une version plus ancienne du script du jeu sont faussés : ignorés
        containers = [c for c in data.get("conteneurs") or [] if c.get("v") == 2]
        # Un grand coffre occupe deux blocs côte à côte : selon le côté ouvert, le jeu le mémorise à l'une
        # ou l'autre position. On ne garde que la plus récente des deux.
        kept = []
        for c in sorted(containers, key=lambda c: -c["t"]):
            if any(Storage.twins(c, k) for k in kept):
                continue
            kept.append(c)
        return Storage(kept, containers, [m for m in data.get("machines") or [] if m.get("v") == 2])

    def placed(self):
        """Nombre de machines posées par id de bloc (l'id du bloc est celui de l'objet à poser)."""
        counts = defaultdict(int)
        for m in self.machines:
            counts[m["bloc"]] += 1
        return counts

    @staticmethod
    def twins(a, b):
        """Les deux moitiés d'un même grand coffre."""
        return (str(a.get("nom", "")).startswith("Large") and a["bloc"] == b["bloc"] and a["dimension"] == b["dimension"]
                and a["y"] == b["y"] and abs(a["x"] - b["x"]) + abs(a["z"] - b["z"]) == 1)

    def totals(self):
        totals = defaultdict(int)
        for c in self.containers:
            for o in c["objets"]:
                totals[o["item"]] += int(o["n"])
        return totals

    def find(self, item, state):
        """Quantité dans les coffres de la dimension du joueur, et le coffre le plus proche qui en contient."""
        total, nearest, nearest_distance = 0, None, None
        for c in self.containers:
            count = sum(int(o["n"]) for o in c["objets"] if o["item"] == item)
            if not count or c["dimension"] != state["dimension"]:
                continue
            total += count
            distance = math.dist((c["x"], c["z"]), (state["x"], state["z"]))
            if nearest is None or distance < nearest_distance:
                nearest, nearest_distance = c, distance
        return total, nearest

    def as_text(self):
        lines = []
        for c in self.containers:
            date = datetime.fromtimestamp(c["t"] / 1000).strftime("%d/%m %H:%M")
            where = f"{int(c['x'])} {int(c['y'])} {int(c['z'])} | {c['dimension']} | {c['bloc']} ({c['nom']})"
            lines += [f"{o['item']} | {int(o['n'])} | {where} | {date}" for o in c["objets"]]
        return "\n".join(lines) + "\n"


def load_craftable(recipes_dir):
    """Les objets qu'on fabrique à l'établi ou au four (d'après recettes_*.json), pour dire « à fabriquer »
    plutôt que « à récolter » quand le plan n'a pas la recette. On ignore les recettes qui ne font que
    transformer une matière qu'on récolte : un bloc vanilla au marteau (stone -> cobblestone) ou un bloc
    qu'on vide (clay -> 4 clay_ball)."""
    items = set()
    for path in recipes_dir.glob("recettes_*.json"):
        if path.stem == "recettes_info":
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            category, _, rest = line.strip().strip('",').partition(" | ")
            if category not in ("minecraft:crafting", "minecraft:smelting"):
                continue
            inputs, _, outputs = rest.split(" | ")[0].partition(" => ")
            inputs = inputs.split(" + ")
            output = outputs.split(" + ")[0].split(" ")
            materials = [i for i in inputs if not i.startswith("#")]
            hammered = any("hammers" in i for i in inputs) and len(materials) == 1 and materials[0].startswith("minecraft:")
            if hammered or len(inputs) == 1 and len(output) == 2 and output[0] in ("4x", "9x"):
                continue
            items.add(output[-1])
    return items


def block_name(block_id):
    """« gtceu:lp_steam_alloy_smelter » -> « Lp Steam Alloy Smelter » (le serveur n'envoie que les ids)."""
    return block_id.split(":")[-1].replace("_", " ").title()


class World:
    """Ce que le script serveur voit autour du joueur : tous les coffres (même jamais ouverts) et l'état des
    machines GregTech. Gardé en mémoire : on sait encore ce qu'il y a dans la base quand on s'en éloigne."""

    def __init__(self):
        try:
            saved = json.loads(WORLD_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        self.blocks = saved.get("blocs", {})   # clé "dimension|x|y|z" -> bloc vu par le serveur
        self.players = []
        self.errors = []
        self.last_t = 0                         # heure de la dernière vue du serveur (ms)

    @property
    def active(self):
        return time.time() * 1000 - self.last_t < 15000

    def update(self, snapshot):
        """Intègre une vue du serveur. Renvoie True si elle est nouvelle."""
        if not snapshot or snapshot.get("t", 0) <= self.last_t:
            return False
        self.last_t = snapshot["t"]
        dimension = snapshot["dimension"]
        cx0, cz0, cx1, cz1 = snapshot["zone"]
        seen = {}
        for b in snapshot.get("blocs", []):
            x, y, z = (int(v) for v in b["p"])
            key = f"{dimension}|{x}|{y}|{z}"
            seen[key] = {"cle": key, "bloc": b["b"], "nom": block_name(b["b"]), "dimension": dimension,
                         "x": x, "y": y, "z": z, "t": snapshot["t"], "v": 2,
                         "objets": [{"item": i, "n": int(n)} for i, n in b.get("o", [])], "etat": b.get("m")}
        # Ce qui était dans la zone regardée et n'y est plus a été cassé ou déplacé
        for key, b in list(self.blocks.items()):
            if (b["dimension"] == dimension and cx0 <= b["x"] // 16 <= cx1 and cz0 <= b["z"] // 16 <= cz1
                    and key not in seen):
                del self.blocks[key]
        self.blocks.update(seen)
        self.players = snapshot.get("joueurs", [])
        self.errors = snapshot.get("erreurs", [])
        WORLD_FILE.write_text(json.dumps({"blocs": self.blocks}, ensure_ascii=False), encoding="utf-8")
        return True

    def containers(self):
        """Les coffres, sans compter deux fois un grand coffre (ses deux moitiés montrent le même contenu)."""
        kept = []
        for b in self.blocks.values():
            if not b["objets"] or b.get("etat") is not None:
                continue
            if any(k["bloc"] == b["bloc"] and k["dimension"] == b["dimension"] and k["y"] == b["y"]
                   and abs(k["x"] - b["x"]) + abs(k["z"] - b["z"]) == 1 and k["objets"] == b["objets"] for k in kept):
                continue
            kept.append(b)
        return kept

    def alerts(self):
        """Lignes d'alerte permanentes pour le panneau tant que le problème dure."""
        lines = []
        for m in self.machines():
            state = m["etat"]
            if "eau" in state and state.get("se") and state["t"][0] >= 100:
                lines.append(f"⚠ {m['nom']} à sec en {m['x']} {m['y']} {m['z']} : NE REMETS PAS D'EAU")
        waiting = [m for m in self.machines() if m["etat"].get("s") == "WAITING"]
        if waiting:
            lines.append(f"⚙ {len(waiting)} machine{'s' if len(waiting) > 1 else ''} en attente : "
                         + ", ".join(m["nom"] for m in waiting[:3]))
        return lines

    def machines(self):
        return [b for b in self.blocks.values() if b.get("etat") is not None]

    def merged(self, storage):
        """Les coffres et machines vus par le serveur, complétés par ceux que le joueur a ouverts ailleurs."""
        keys = set(self.blocks)
        containers = self.containers() + [c for c in storage.containers if c["cle"] not in keys]
        machines = self.machines() + [m for m in storage.machines if m["cle"] not in keys]
        return Storage(containers, containers + [c for c in storage.raw if c["cle"] not in keys], machines)


def machine_status(m):
    """État lisible d'une machine vue par le serveur."""
    state = m.get("etat") or {}
    parts = []
    if "eau" in state:  # KubeJS envoie les nombres en décimal (16000.0) : on les remet en entiers
        water, capacity = (int(v) for v in state["eau"])
        temperature, maximum = (int(v) for v in state["t"])
        parts.append(f"eau {water}/{capacity} mB, {temperature}/{maximum} °C" + (", À SEC" if state.get("se") else ""))
    if "vap" in state:
        parts.append(f"steam {int(state['vap'][0])}/{int(state['vap'][1])} mB")
    status = state.get("s")
    if status == "WORKING" and state.get("du"):
        parts.append(f"en marche {round(100 * state['pr'] / state['du'])} %")
    elif status == "WAITING":
        parts.append("en attente" + (f" ({state['r']})" if state.get("r") else ""))
    elif status == "SUSPEND":
        parts.append("en pause")
    elif status == "IDLE":
        parts.append("à l'arrêt")
    if state.get("f") is False:
        parts.append("multibloc NON formé")
    return ", ".join(parts) or "posée"


def machine_color(status):
    return ("rouge" if "À SEC" in status or "NON formé" in status else "jaune" if "attente" in status
            else "vert" if "en marche" in status else "gris")


def describe_world(world, limit=60):
    if not world.blocks:
        return ""
    # Les pièces de multibloc (Hatch, Bus) n'apportent rien : on garde les machines et les contrôleurs
    machines = [m for m in world.machines() if not re.search(r"hatch|_bus$", m["bloc"])]
    lines = ["Machines vues par le serveur (état en direct) : " + " ; ".join(
        f"{m['nom']} en {m['x']} {m['y']} {m['z']} : {machine_status(m)}" for m in machines[:limit])]
    if world.players:
        lines.append("Autres joueurs connectés : " + " ; ".join(
            f"{p['nom']} en {p['x']} {p['y']} {p['z']} ({p['dimension']})" for p in world.players))
    return "\n".join(lines)


STONE_PREFIXES = ("deepslate_", "tuff_", "andesite_", "diorite_", "granite_", "red_granite_", "marble_", "basalt_",
                  "netherrack_", "endstone_", "sand_", "red_sand_", "gravel_", "blackstone_", "calcite_", "stone_")


def ore_material(block):
    """« gtceu:deepslate_galena_ore » -> « galena » : le minerai, quelle que soit la pierre qui l'entoure."""
    name = block.split(":")[-1].removesuffix("_ore")
    for prefix in STONE_PREFIXES:
        name = name.removeprefix(prefix)
    return name


def find_veins(bridge, radius=24):
    """Regroupe les minerais vus ou minés par le joueur en filons (même minerai, à moins de radius blocs)."""
    blocks = (bridge.read("filons.json") or {}).get("blocs") or []
    veins = []
    for b in sorted(blocks, key=lambda b: b["t"]):
        material = ore_material(b["bloc"])
        vein = next((v for v in veins if v["minerai"] == material and v["dimension"] == b["dimension"]
                     and math.dist((v["x"], v["y"], v["z"]), (b["x"], b["y"], b["z"])) <= radius), None)
        if vein is None:
            vein = {"minerai": material, "dimension": b["dimension"], "x": b["x"], "y": b["y"], "z": b["z"],
                    "vus": 0, "mines": 0, "t": b["t"]}
            veins.append(vein)
        vein["mines" if b.get("mine") else "vus"] += 1
        vein["t"] = max(vein["t"], b["t"])
    return veins


def describe_veins(veins, state, limit=25):
    if not veins:
        return "Filons repérés : aucun pour l'instant."
    def distance(v):
        return math.dist((v["x"], v["z"]), (state["x"], state["z"])) if state and v["dimension"] == state["dimension"] else 1e9
    parts = [f"{v['minerai']} en {int(v['x'])} {int(v['y'])} {int(v['z'])} ({v['dimension']}, {v['vus']} blocs encore là, "
             f"{v['mines']} minés)" for v in sorted(veins, key=distance)[:limit]]
    return "Filons repérés (les plus proches d'abord) : " + " ; ".join(parts)


def describe_machines(storage, limit=60):
    if not storage.machines:
        return "Machines posées : aucune repérée (elles le sont quand le joueur ouvre leur interface)."
    return "Machines déjà posées (repérées quand le joueur a ouvert leur interface) : " + " ; ".join(
        f"{m['nom']} [{m['bloc']}] en {int(m['x'])} {int(m['y'])} {int(m['z'])}" for m in storage.machines[:limit])


def split_actions(text):
    """Sépare la réponse (affichée et lue) des actions demandées par Claude dans <actions>[...]</actions>."""
    match = ACTION_TAG.search(text)
    if not match:
        return text.strip(), []
    try:
        actions = json.loads(match.group(1))
    except ValueError:
        log(f"Actions illisibles : {match.group(1)}")
        actions = []
    if isinstance(actions, dict):
        actions = [actions]
    return text[:match.start()].strip(), [a for a in actions if isinstance(a, dict)]


def visible_text(partial):
    """Le texte à afficher et à lire pendant que la réponse arrive : sans la ligne d'actions, même entamée."""
    cut = partial.find("<actions")
    if cut >= 0:
        return partial[:cut]
    for n in range(min(len(partial), len("<actions")), 0, -1):
        if partial.endswith("<actions"[:n]):
            return partial[:-n]
    return partial


def readable(text):
    """Remplace les identifiants qui échappent à Claude (minecraft:torch) par le nom du jeu (Torch)."""
    return ITEM_ID.sub(lambda m: m.group(1).split("/")[-1].replace("_", " ").title(), text)


def cardinal(dx, dz):
    """Direction d'un déplacement (N, NE, E...), le nord étant vers -Z comme dans Minecraft."""
    angle = math.degrees(math.atan2(dx, -dz)) % 360
    return ["N", "NE", "E", "SE", "S", "SO", "O", "NO"][round(angle / 45) % 8]


def describe_state(state, inventory_limit=60):
    # KubeJS écrit tous les nombres en décimal (64.0) : on les remet en entiers
    items = sorted(state.get("inventaire", {}).items(), key=lambda kv: -kv[1])
    return "\n".join([
        f"Position : x={int(state['x'])} y={int(state['y'])} z={int(state['z'])} ({state['dimension']})",
        f"Écran ouvert : {state.get('ecran') or 'aucun, en jeu'} ; en main : {state.get('en_main')}",
        "Inventaire : " + (", ".join(f"{int(n)}x {i}" for i, n in items[:inventory_limit]) or "vide"),
    ])


def describe_storage(storage, limit=150):
    """Contenu des coffres avec leurs coordonnées, pour que Claude puisse dire où aller chercher un objet."""
    where = defaultdict(list)
    for c in storage.containers:
        for o in c["objets"]:
            where[o["item"]].append((int(o["n"]), f"{int(c['x'])} {int(c['y'])} {int(c['z'])}"))
    if not where:
        return "Coffres : aucun mémorisé pour l'instant."
    items = sorted(where.items(), key=lambda kv: -sum(n for n, _ in kv[1]))
    parts = [f"{sum(n for n, _ in places)}x {item} (" + ", ".join(f"{n} en {pos}" for n, pos in places[:3]) + ")"
             for item, places in items[:limit]]
    return (f"Dans mes coffres ({len(storage.containers)} mémorisés, quantité et coordonnées x y z) : "
            + " ; ".join(parts))


def describe_quests(quests, limit=25, per_chapter=8):
    if not quests:
        return "Quêtes : pas encore lues."
    lines = [f"Quêtes : {int(quests['terminees'])}/{int(quests['total'])} terminées.",
             "Chapitres : " + " ; ".join(f"{c['titre']} {int(c['terminees'])}/{int(c['total'])}"
                                         for c in quests["chapitres"] if c["total"])]
    # Quêtes principales d'abord, dans l'ordre des chapitres, sans qu'un gros chapitre prenne toute la place
    available, per_chapter_count = [], {}
    for q in sorted(quests["disponibles"], key=lambda q: q["optionnelle"]):
        if per_chapter_count.get(q["chapitre"], 0) < per_chapter:
            per_chapter_count[q["chapitre"]] = per_chapter_count.get(q["chapitre"], 0) + 1
            available.append(q)
    lines.append(f"Quêtes disponibles maintenant (non terminées, {len(quests['disponibles'])} au total) :")
    for q in available[:limit]:
        tasks = ", ".join(f"{t['titre']} {int(t['progres'])}/{int(t['max'])}" + (f" [{t['item']}]" if t.get("item") else "")
                          for t in q["taches"])
        optional = " (optionnelle)" if q["optionnelle"] else ""
        lines.append(f"- [{q['chapitre']}] {q['titre']}{optional} : {tasks}. {q['description'][:250]}")
    return "\n".join(lines)


# ---------------- Plan : arbre de fabrication ----------------

def parse_info(text):
    """La fiche « à quoi ça sert » renvoyée par Claude, nettoyée et bornée pour tenir dans l'écran du jeu."""
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise RuntimeError("Claude n'a pas renvoyé de fiche lisible : " + text[:200])
    data = json.loads(text[start:end + 1])
    chains = []
    for chain in data.get("schema") or []:
        nodes = [{"item": str(n["item"]), "texte": readable(str(n.get("texte") or ""))[:40]}
                 for n in chain if isinstance(n, dict) and n.get("item")] if isinstance(chain, list) else []
        if len(nodes) >= 2:
            chains.append(nodes[:6])
    return {"sert": readable(str(data.get("sert") or "")),
            "utilise": [readable(str(s)) for s in data.get("utilise") or []][:5],
            "schema": chains[:2], "astuce": readable(str(data.get("astuce") or ""))}


def parse_plan(text):
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        raise RuntimeError("Claude n'a pas renvoyé de plan lisible : " + text[:200])
    data = json.loads(text[start:end + 1])

    def entry(e):
        return {"item": str(e["item"]), "nom": str(e.get("nom") or e["item"]),
                "quantite": max(1, int(e.get("quantite") or 1))}

    recipes = [{"item": str(r["item"]), "nom": str(r.get("nom") or r["item"]),
                "sortie": max(1, int(r.get("sortie") or 1)), "machine": str(r.get("machine") or ""),
                "id": str(r["id"]) if r.get("id") and ":" in str(r["id"]) else None,  # pour remplir l'établi via EMI
                "entrees": [entry(e) for e in r.get("entrees") or [] if e.get("item")],
                "outils": [{"item": str(o["item"]), "nom": str(o.get("nom") or o["item"])}
                           for o in r.get("outils") or [] if o.get("item")]}
               for r in data.get("recettes") or [] if r.get("item")]
    steps = []
    for s in data.get("etapes") or []:
        if isinstance(s, str):
            steps.append({"texte": s, "item": None, "quantite": None})
        else:
            steps.append({"texte": str(s.get("texte", "")), "item": s.get("item") or None,
                          "quantite": int(s["quantite"]) if s.get("quantite") else None})
    target = data.get("cible")
    if not isinstance(target, dict) or not target.get("label"):
        target = None
    roadmap = [{"titre": str(r.get("titre") or r.get("demande"))[:60], "demande": str(r.get("demande") or r.get("titre"))}
               for r in data.get("feuille_de_route") or [] if isinstance(r, dict) and (r.get("titre") or r.get("demande"))]
    return {"objectif": str(data.get("objectif", "")), "pourquoi": str(data.get("pourquoi", "")), "etapes": steps,
            "cibles": [entry(c) for c in data.get("cibles") or [] if c.get("item")],
            "recettes": recipes, "cible": target, "resume": str(data.get("resume", "")), "feuille_de_route": roadmap}


def item_names(plan):
    names = {}
    for r in plan["recettes"]:
        names[r["item"]] = r["nom"]
        for e in r["entrees"] + r["outils"]:
            names.setdefault(e["item"], e["nom"])
    for t in plan["cibles"]:
        names[t["item"]] = t["nom"]
    return names


TOOL_TYPES = ["wire_cutter", "screwdriver", "hammer", "file", "saw", "wrench", "mortar", "knife", "crowbar",
              "mallet", "plunger"]


def has_tool(tool, have):
    """Vrai si le joueur a cet outil, ou le même type d'outil dans un autre matériau (Diamond Hammer pour Iron Hammer)."""
    if have.get(tool, 0) >= 1:
        return True
    kind = next((k for k in TOOL_TYPES if tool.endswith("_" + k)), None)
    namespace = tool.split(":")[0]
    return kind is not None and any(i.endswith("_" + kind) and i.split(":")[0] == namespace and n > 0
                                    for i, n in have.items())


def evaluate(plan, have, reached, done_steps, stored=None, placed=None):
    """Où en est le plan : objets encore à fabriquer, à récupérer dans les coffres, matières premières qui
    manquent, étapes faites.

    reached (cibles déjà obtenues) et done_steps (étapes déjà cochées) sont mémorisés : une cible posée
    dans le monde, ou un objet intermédiaire consommé, ne fait pas revenir en arrière. stored : objets dans
    les coffres ; placed : machines déjà posées (une cible posée compte comme obtenue)."""
    recipes = {r["item"]: r for r in plan["recettes"]}
    placed = placed or {}
    reached = set(reached) | {t["item"] for t in plan["cibles"]
                              if max(have.get(t["item"], 0), placed.get(t["item"], 0)) >= t["quantite"]}
    stock, chests = dict(have), dict(stored or {})
    to_make, from_chests, missing, tools = defaultdict(int), defaultdict(int), defaultdict(int), {}

    def need(item, quantity, depth=0):
        used = min(stock.get(item, 0), quantity)  # d'abord ce que le joueur a sur lui
        stock[item] = stock.get(item, 0) - used
        quantity -= used
        used = min(chests.get(item, 0), quantity)  # puis ce qu'il a dans ses coffres
        if used:
            chests[item] -= used
            from_chests[item] += used
            quantity -= used
        if quantity <= 0:
            return
        recipe = recipes.get(item)
        if recipe is None or depth > 15:
            missing[item] += quantity
            return
        to_make[item] += quantity
        crafts = math.ceil(quantity / recipe["sortie"])
        for tool in recipe["outils"]:
            tools.setdefault(tool["item"], tool)
        for e in recipe["entrees"]:
            need(e["item"], crafts * e["quantite"], depth + 1)

    for target in plan["cibles"]:
        if target["item"] not in reached:
            need(target["item"], target["quantite"])
    for tool in list(tools):  # un outil sert à toutes les fabrications : il en faut un seul
        if not has_tool(tool, have):
            need(tool, 1)

    complete = bool(plan["cibles"]) and all(t["item"] in reached for t in plan["cibles"])
    tree = set(recipes) | {t["item"] for t in plan["cibles"]} | {
        e["item"] for r in plan["recettes"] for e in r["entrees"]}
    steps = []
    for i, step in enumerate(plan["etapes"]):
        item = step.get("item")
        done = complete or i in done_steps or bool(item) and (
            have.get(item, 0) >= (step.get("quantite") or 1)
            or (item in tree and item not in to_make and item not in missing))
        steps.append(done)
    # Une étape qui ne produit rien est faite dès qu'une étape suivante l'est
    later_done = False
    for i in range(len(steps) - 1, -1, -1):
        if steps[i]:
            later_done = True
        elif later_done and not plan["etapes"][i].get("item"):
            steps[i] = True
    return {"a_fabriquer": dict(to_make), "a_recuperer": dict(from_chests), "manque": dict(missing),
            "atteints": reached, "etapes": steps, "termine": complete}


def step_recipe(plan, progress):
    """La recette à faire maintenant : celle de l'étape en cours, sinon du premier objet à fabriquer."""
    recipes = {r["item"]: r for r in plan["recettes"]}
    current = next((s for s, done in zip(plan["etapes"], progress["etapes"]) if not done), None)
    if current and current.get("item") in recipes:
        return recipes[current["item"]]
    return next((recipes[i] for i in progress["a_fabriquer"] if i in recipes), None)


def craftable_now(plan, progress, have):
    """La fabrication du plan que le joueur peut faire tout de suite avec ce qu'il a sur lui (les plus simples
    d'abord : les objets intermédiaires avant ce qu'ils servent à fabriquer)."""
    recipes = {r["item"]: r for r in plan["recettes"]}
    for item in reversed(list(progress["a_fabriquer"])):
        recipe = recipes.get(item)
        if recipe and recipe["entrees"] and all(have.get(e["item"], 0) >= e["quantite"] for e in recipe["entrees"]):
            return recipe
    return None


def nearest_vein(item, veins, state):
    """Le filon connu le plus proche dont le minerai figure dans l'id de l'objet (galena -> raw_galena)."""
    if not state:
        return None
    path = item.split(":")[-1]
    candidates = [v for v in veins if v["vus"] and v["minerai"] in path and v["dimension"] == state["dimension"]]
    return min(candidates, key=lambda v: math.dist((v["x"], v["z"]), (state["x"], state["z"])), default=None)


def where_hint(target, state):
    dx, dz = target["x"] - state["x"], target["z"] - state["z"]
    return f"{round(math.hypot(dx, dz))} blocs {cardinal(dx, dz)}"


def build_view(plan, progress, state, storage, have, status, message, guide=None, outdated=False,
               roadmap=(), veins=(), alerts=(), server=False):
    """Les lignes affichées dans le jeu (le script KubeJS les dessine telles quelles)."""
    lines = []

    def add(text, color="blanc", item="", direction=None, indent=0, icons=()):
        lines.append({"t": text, "c": color, "item": item, "dir": direction, "indent": indent, "icones": list(icons)})

    if outdated:
        add("⚠ Script du jeu à recharger : appuie sur F3 + T", "rouge")
    for alert in alerts:
        add(alert, "rouge" if alert.startswith("⚠") else "jaune")

    if plan:
        names = item_names(plan)
        recipes = {r["item"]: r for r in plan["recettes"]}
        add("✦ " + plan["objectif"], "or")
        if roadmap:
            add("Ensuite : " + " · ".join(r["titre"] for r in roadmap[:2])
                + (f" (+{len(roadmap) - 2})" if len(roadmap) > 2 else ""), "gris", indent=8)
        steps, states = plan["etapes"], progress["etapes"]
        current = next((i for i, done in enumerate(states) if not done), None)
        if steps and current is None:
            add(f"✔ Les {len(steps)} étapes sont faites", "vert")
        elif steps:
            if any(states):
                add(f"✔ {sum(states)}/{len(steps)} étapes faites", "vert")
            add("▶ " + steps[current]["texte"], "jaune", indent=8)
            for i in range(current + 1, min(current + 3, len(steps))):
                if not states[i]:
                    add("   " + steps[i]["texte"], "gris", indent=8)
        for t in plan["cibles"]:
            ok = t["item"] in progress["atteints"]
            count = t["quantite"] if ok else min(int(have.get(t["item"], 0)), t["quantite"])
            add(f"{'✔ ' if ok else ''}{t['nom']}  {count}/{t['quantite']}", "vert" if ok else "blanc", t["item"])
        recipe = step_recipe(plan, progress)
        if recipe and recipe["entrees"]:
            machine = recipe["machine"] if recipe["machine"].lower() != "crafting table" else ""
            add(f"Recette{' (' + machine + ')' if machine else ''} :", "gris",
                icons=[{"item": e["item"], "n": e["quantite"]} for e in recipe["entrees"][:9]])
        targets = {t["item"] for t in plan["cibles"]}
        intermediates = [(i, n) for i, n in progress["a_fabriquer"].items() if i not in targets]
        if intermediates:
            add("À fabriquer :", "gris")
            for item, count in intermediates[:MAX_TO_MAKE]:
                machine = recipes[item]["machine"]
                where = f" · {machine}" if machine and machine.lower() != "crafting table" else ""
                add(f"{names.get(item, item)} ×{count}{where}", "blanc", item)
            if len(intermediates) > MAX_TO_MAKE:
                add(f"   +{len(intermediates) - MAX_TO_MAKE} autres", "gris")
        go_to = None  # le prochain endroit utile : un coffre où récupérer, sinon un filon connu
        if progress["a_recuperer"] and state:
            add("À récupérer dans tes coffres :", "jaune")
            pickups = sorted(progress["a_recuperer"].items(), key=lambda kv: -kv[1])
            for item, count in pickups[:MAX_MISSING]:
                _, chest = storage.find(item, state)
                hint = f" · {where_hint(chest, state)}" if chest else ""
                if chest and go_to is None:
                    go_to = (f"Coffre ({names.get(item, item)})", chest)
                add(f"{names.get(item, item)} ×{count}{hint}", "jaune", item)
            if len(pickups) > MAX_MISSING:
                add(f"   +{len(pickups) - MAX_MISSING} autres", "gris")
        if progress["manque"]:
            add("À récolter :", "rouge")
            missing = sorted(progress["manque"].items(), key=lambda kv: -kv[1])
            for item, count in missing[:MAX_MISSING]:
                vein = nearest_vein(item, veins, state)
                hint = f" · filon à {where_hint(vein, state)}" if vein else ""
                if vein and go_to is None:
                    go_to = (f"Filon de {vein['minerai'].replace('_', ' ').title()}", vein)
                add(f"{names.get(item, item)} ×{count}{hint}", "rouge", item)
            if len(missing) > MAX_MISSING:
                add(f"   +{len(missing) - MAX_MISSING} autres", "gris")
        if plan.get("cible"):
            add("➜ " + plan["cible"]["label"], "bleu", direction=plan["cible"])
        elif go_to:
            label, where = go_to
            # y à None : pas de « monte de … » dans le panneau ; yb : hauteur du faisceau dessiné par le mod
            add("➜ " + label, "bleu", direction={"x": where["x"], "y": None, "z": where["z"], "yb": where["y"],
                                               "dimension": where.get("dimension")})
    elif state:
        add(f"Appuie sur {HOTKEY_PLAN.upper()} pour ton prochain objectif.", "gris")
    if guide:
        add("➜ " + guide["label"], "bleu", direction=guide)
    if status:
        add(status, "gris")
    if message:
        add("", "gris")
        for part in message.split("\n"):
            if part.strip():
                add(part.strip(), "blanc")
    chests, machines = len(storage.containers), len(storage.machines)
    add(f"{FOOTER} · {chests} coffre{'s' if chests > 1 else ''} · {machines} machine{'s' if machines > 1 else ''}"
        + (" · serveur ✔" if server else ""), "gris")
    return lines


def find_search(search, storage, have, state):
    """Les coffres connus qui contiennent les objets cherchés (les plus proches d'abord), avec ce qu'il y a dedans,
    et le résumé pour le panneau : « Torches : 34 dans 2 coffres · 12 sur toi »."""
    patterns = search["objets"]

    def wanted(item):
        return any(fnmatch.fnmatchcase(item, p) for p in patterns)

    chests = []
    for c in storage.containers:
        hits = defaultdict(int)
        for o in c["objets"]:
            if wanted(o["item"]):
                hits[o["item"]] += int(o["n"])
        if hits:
            best = sorted(hits.items(), key=lambda kv: -kv[1])
            chests.append({"x": c["x"], "y": c["y"], "z": c["z"], "dimension": c["dimension"], "n": sum(hits.values()),
                           "texte": ", ".join(f"{block_name(i)} ×{n}" for i, n in best[:2]) + (" …" if len(best) > 2 else "")})
    if state:
        chests.sort(key=lambda c: (c["dimension"] != state.get("dimension"),
                                   math.dist((c["x"], c["y"], c["z"]), (state["x"], state["y"], state["z"]))))
    on_me = sum(n for i, n in have.items() if wanted(i))
    total = sum(c["n"] for c in chests)
    summary = (f"{total} dans {len(chests)} coffre{'s' if len(chests) > 1 else ''}" if chests
               else "dans aucun coffre connu") + (f" · {on_me} sur toi" if on_me else "")
    return {"label": search["label"], "objets": patterns, "coffres": chests[:16],
            "resume": f"{search['label']} : {summary}"}


def build_mod_view(app):
    """Tout ce que le mod Minecraft affiche, sous forme de données (le mod s'occupe de la mise en forme) :
    - hud : le panneau compact (objectif, progression, étape en cours, 3 prochains objets, direction, alertes) ;
    - bulle : la dernière réponse de Claude ou du coach, qui s'efface toute seule ;
    - besoins, guidage, marqueurs : surbrillance dans les interfaces et faisceaux dans le monde ;
    - ecran : le tableau de bord de la touche Y ; recette : la fabrication en cours, pour le bouton « Remplir »."""
    plan, progress, state, storage = app.plan, app.progress(), app.game, app.storage
    have, now = app.have(), time.time()
    names = item_names(plan) if plan else {}
    recipes = {r["item"]: r for r in plan["recettes"]} if plan else {}
    dimension = (state or {}).get("dimension")

    def where(item):
        """Où trouver un objet qui manque : coffre le plus proche, ou filon connu."""
        if not state:
            return "", None
        _, chest = storage.find(item, state)
        if chest:
            return f"coffre · {where_hint(chest, state)}", chest
        vein = nearest_vein(item, app.veins, state)
        if vein:
            return f"filon · {where_hint(vein, state)}", vein
        return "", None

    hud = {"visible": app.visible, "recharger": app.bridge.script_outdated(state), "statut": app.status,
           "alertes": (app.world.alerts() if app.world.active else [])[:2], "prochains": [], "direction": None}
    sections, needed, beams, recipe_now = [], set(), [], None
    search = find_search(app.search, storage, have, state) if app.search and now < app.search["fin"] else None
    if search:
        hud["recherche"] = search.pop("resume")
    if plan and progress:
        steps, states = plan["etapes"], progress["etapes"]
        current = next((i for i, done in enumerate(states) if not done), None)
        targets = plan["cibles"]
        target_share = sum(min(1, have.get(t["item"], 0) / t["quantite"]) if t["item"] not in progress["atteints"]
                           else 1 for t in targets) / max(1, len(targets))

        def step_share(i):
            """Une étape pas encore faite compte pour ce que tu as déjà de son objet (159/250 Bronze Ingots)."""
            item = steps[i].get("item")
            if states[i] or not item:
                return float(states[i])
            return min(1, have.get(item, 0) / (steps[i].get("quantite") or 1))

        def how(item):
            return "à fabriquer" if item in recipes or item in app.craftable else "à récolter"

        hud.update({"objectif": plan["objectif"],
                    "progres": round(sum(map(step_share, range(len(states)))) / len(states), 3) if states
                    else round(target_share, 3),
                    "etape": steps[current]["texte"] if current is not None else "Toutes les étapes sont faites",
                    "etape_num": f"{(current if current is not None else len(steps)) + (1 if current is not None else 0)}"
                                 f"/{len(steps)}"})
        # Les 3 prochains objets utiles : à récupérer d'abord (le plus simple), puis l'objet de l'étape, puis à récolter
        upcoming = []
        for item, count in sorted(progress["a_recuperer"].items(), key=lambda kv: -kv[1]):
            hint, _ = where(item)
            upcoming.append({"item": item, "nom": names.get(item, item), "a": have.get(item, 0),
                             "besoin": have.get(item, 0) + count, "ou": hint or "dans tes coffres", "c": "jaune"})
        step_item = steps[current].get("item") if current is not None else None
        if step_item and step_item not in {u["item"] for u in upcoming}:
            upcoming.append({"item": step_item, "nom": names.get(step_item, block_name(step_item)),
                             "a": have.get(step_item, 0), "besoin": steps[current].get("quantite") or 1,
                             "ou": how(step_item), "c": "blanc"})
        for item, count in sorted(progress["manque"].items(), key=lambda kv: -kv[1]):
            if item in {u["item"] for u in upcoming}:
                continue
            hint, _ = where(item)
            upcoming.append({"item": item, "nom": names.get(item, item), "a": have.get(item, 0),
                             "besoin": have.get(item, 0) + count, "ou": hint or how(item), "c": "rouge"})
        hud["prochains"] = upcoming[:3]
        needed |= {t["item"] for t in targets if t["item"] not in progress["atteints"]}
        needed |= set(progress["a_fabriquer"]) | set(progress["a_recuperer"]) | set(progress["manque"])

        def section(title, color, entries):
            if entries:
                sections.append({"titre": title, "couleur": color, "objets": entries})

        section("Objectif", "or", [{"item": t["item"], "nom": t["nom"], "texte": (
            "✔ " if t["item"] in progress["atteints"] else "") + f"{min(have.get(t['item'], 0), t['quantite'])}/{t['quantite']}"}
            for t in targets])
        section("À fabriquer", "blanc", [{"item": i, "nom": names.get(i, i), "texte": f"×{n}" + (
            f" · {recipes[i]['machine']}" if recipes[i]["machine"] and recipes[i]["machine"].lower() != "crafting table"
            else "")} for i, n in progress["a_fabriquer"].items() if i not in {t["item"] for t in targets}]
            + [{"item": i, "nom": names.get(i, i), "texte": f"×{n}"}  # fabricables, mais hors des recettes du plan
               for i, n in progress["manque"].items() if i in app.craftable])
        section("À récupérer dans tes coffres", "jaune", [{"item": i, "nom": names.get(i, i), "texte": f"×{n} · "
                                                         + (where(i)[0] or "coffre")}
                                                        for i, n in progress["a_recuperer"].items()])
        section("À récolter", "rouge", [{"item": i, "nom": names.get(i, i), "texte": f"×{n}" + (
            f" · {where(i)[0]}" if where(i)[0] else "")} for i, n in progress["manque"].items()
            if i not in app.craftable])
        recipe = craftable_now(plan, progress, have) or step_recipe(plan, progress)
        if recipe:
            remaining = progress["a_fabriquer"].get(recipe["item"], recipe["sortie"])
            recipe_now = {"item": recipe["item"], "nom": recipe["nom"], "id": recipe.get("id"),
                          "fois": max(1, math.ceil(remaining / recipe["sortie"])), "machine": recipe["machine"]}
    # La destination principale : celle que montre aussi le panneau du script du jeu (lignes « ➜ »)
    for line in build_view(plan, progress, state, storage, have, "", "", app.guide, False, app.roadmap,
                           app.veins) if state else []:
        d = line.get("dir")
        if d and d.get("x") is not None and d.get("z") is not None:
            target = {"x": d["x"], "y": d.get("yb", d.get("y")), "z": d["z"], "couleur": "bleu",
                      "label": line["t"].removeprefix("➜ "), "dimension": d.get("dimension") or dimension}
            beams.append(target)
            hud["direction"] = hud["direction"] or target
    markers = []
    if app.world.active:
        for m in app.world.machines():
            st = m["etat"]
            color = "rouge" if "eau" in st and st.get("se") and st["t"][0] >= 100 else \
                "jaune" if st.get("s") == "WAITING" else None
            if color:
                markers.append({"x": m["x"], "y": m["y"], "z": m["z"], "dimension": m["dimension"],
                                "label": m["nom"], "couleur": color})
    live = app.message if app.message and not app.message_until else ""
    bubble = None
    if app.message and (not app.message_until or now < app.message_until):
        bubble = {"texte": app.message, "en_cours": bool(live), "fin": int(app.message_until * 1000)}
    machines = [{"nom": m["nom"], "etat": machine_status(m), "x": m["x"], "y": m["y"], "z": m["z"],
                 "couleur": machine_color(machine_status(m))}
                for m in app.world.machines() if not re.search(r"hatch|_bus$", m["bloc"])][:30]
    screen = {"statut": app.status, "feuille_de_route": [r["titre"] for r in app.roadmap],
              "conversation": app.history[-30:] + ([{"de": "claude", "texte": live}] if live else []),
              "sections": sections, "machines": machines, "coffres": len(storage.containers),
              "joueurs": [f"{p['nom']} en {p['x']} {p['y']} {p['z']}" for p in app.world.players],
              "serveur": app.world.active}
    if plan and progress:
        current = next((i for i, done in enumerate(progress["etapes"]) if not done), None)
        screen["objectif"] = plan["objectif"]
        screen["pourquoi"] = plan.get("pourquoi", "")
        screen["progres"] = hud.get("progres", 0)
        screen["etapes"] = [{"texte": s["texte"], "fait": done, "courant": i == current}
                            for i, (s, done) in enumerate(zip(plan["etapes"], progress["etapes"]))]
        main = next((recipes[t["item"]] for t in plan["cibles"] if t["item"] in recipes), None)
        screen["arbre"] = {"item": main["item"], "id": main.get("id")} if main else None
    return {"v": 2, "hud": hud, "bulle": bubble, "besoins": sorted(needed), "guidage": beams,
            "marqueurs": markers, "ecran": screen, "recette": recipe_now, "recherche": search,
            "info": dict(app.info) if app.info else None}  # une copie : sinon la fiche complétée semble inchangée


# ---------------- Claude Code ----------------

def find_claude():
    """Le CLI `claude` s'il est installé, sinon celui fourni avec l'app Claude (version la plus récente)."""
    on_path = shutil.which("claude")
    if on_path:
        return on_path
    pattern = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Claude-Data", "*",
                           "claude-code", "*", "*", "claude.exe")
    candidates = glob.glob(pattern)
    return max(candidates, key=os.path.getmtime) if candidates else None


def clean_env():
    # Sans ANTHROPIC_API_KEY, Claude Code utilise ton compte claude.ai, donc ton abonnement.
    return {k: v for k, v in os.environ.items() if not k.startswith(("ANTHROPIC", "CLAUDE"))}


def run_claude(prompt, session_id, data_dir, model, on_text=None, on_tool=None):
    """Lance Claude Code et suit sa réponse au fil de l'eau (texte et recherches en cours)."""
    exe = find_claude()
    if not exe:
        raise RuntimeError("Claude Code introuvable : ouvre l'app Claude une fois, ou installe Claude Code.")
    cmd = [exe, "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
           "--model", model, "--append-system-prompt", SYSTEM_PROMPT.format(data_dir=data_dir),
           "--add-dir", str(data_dir),
           "--allowedTools", "Read,Grep,Glob,WebSearch,WebFetch"]
    if session_id:
        cmd += ["--resume", session_id]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            text=True, encoding="utf-8", errors="replace", cwd=HERE, env=clean_env(),
                            creationflags=NO_WINDOW)
    errors = []
    threading.Thread(target=lambda: errors.extend(proc.stderr), daemon=True).start()
    timer = threading.Timer(TIMEOUT, proc.kill)
    timer.start()
    text, result = "", None
    try:
        proc.stdin.write(prompt)
        proc.stdin.close()
        for line in proc.stdout:
            try:
                event = json.loads(line)
            except ValueError:
                continue
            kind = event.get("type")
            if kind == "stream_event":
                inner = event.get("event", {})
                if inner.get("type") == "message_start":
                    text = ""  # nouveau message de Claude (après une recherche, par exemple)
                delta = inner.get("delta", {})
                if delta.get("type") == "text_delta":
                    text += delta.get("text", "")
                    if on_text:
                        on_text(text)
            elif kind == "assistant" and on_tool:
                for block in event.get("message", {}).get("content", []):
                    if block.get("type") == "tool_use":
                        on_tool(block.get("name", ""), block.get("input") or {})
            elif kind == "result":
                result = event
        proc.wait()
    finally:
        timer.cancel()
    if result is None:
        raise RuntimeError(("".join(errors) or "pas de réponse de Claude (délai dépassé ?)").strip()[-600:])
    if result.get("is_error"):
        raise RuntimeError(str(result.get("result") or result.get("subtype")))
    return (result.get("result") or "").strip(), result.get("session_id")


def ask_claude(prompt, session_id, data_dir, model, on_text=None, on_tool=None):
    try:
        return run_claude(prompt, session_id, data_dir, model, on_text, on_tool)
    except RuntimeError:
        if not session_id:
            raise
        log("Reprise de la conversation impossible, nouvelle conversation")
        return run_claude(prompt, None, data_dir, model, on_text, on_tool)


QUICK_QUESTION = re.compile(
    r"\b(est-ce que j'ai|j'ai (des?|du|un|une)|combien|o[uù] (est|sont|j'ai|se trouve|trouver)|trouve|retiens|m[ée]morise|"
    r"oublie|guide|ram[eè]ne|emm[eè]ne|cache|affiche|coupe|remets|active|d[ée]sactive|enl[eè]ve|retire|ajoute)\b",
    re.IGNORECASE)


def pick_model(question):
    """Haiku pour les questions simples (inventaire, coffres, lieux, réglages), Sonnet pour le reste."""
    if len(question) <= 140 and QUICK_QUESTION.search(question):
        return MODEL_QUICK
    return MODEL_QUESTION


def tool_status(name, data):
    detail = (data.get("pattern") or data.get("query") or data.get("url")
              or Path(data.get("file_path") or data.get("path") or "").name)
    return (f"Claude cherche : {detail}" if detail else f"Claude utilise {name}…")[:90]


def load_session():
    try:
        return json.loads(SESSION_FILE.read_text(encoding="utf-8")).get("session_id")
    except (OSError, ValueError):
        return None


def save_session(session_id):
    SESSION_FILE.write_text(json.dumps({"session_id": session_id}), encoding="utf-8")


# ---------------- Écran ----------------

class Rect(ctypes.Structure):
    _fields_ = [("left", ctypes.c_long), ("top", ctypes.c_long), ("right", ctypes.c_long), ("bottom", ctypes.c_long)]


def active_monitor(monitors):
    """L'écran de la fenêtre active (le jeu, quand on appuie sur une touche en jouant), sinon l'écran principal."""
    rect = Rect()
    if user32.GetWindowRect(user32.GetForegroundWindow(), ctypes.byref(rect)):
        cx, cy = (rect.left + rect.right) // 2, (rect.top + rect.bottom) // 2
        for m in monitors[1:]:
            if m["left"] <= cx < m["left"] + m["width"] and m["top"] <= cy < m["top"] + m["height"]:
                return m
    return next((m for m in monitors[1:] if m.get("is_primary")), monitors[1])


def take_screenshot():
    CAPTURES.mkdir(exist_ok=True)
    with mss.mss() as sct:
        raw = sct.grab(active_monitor(sct.monitors))
    image = Image.frombytes("RGB", raw.size, raw.rgb)
    image.thumbnail((1600, 1600))
    path = CAPTURES / f"ecran-{datetime.now():%Y%m%d-%H%M%S}.jpg"
    image.save(path, quality=85)
    for old in sorted(CAPTURES.glob("ecran-*.jpg"))[:-30]:
        old.unlink(missing_ok=True)
    return path


def bring_to_front(window):
    window.update_idletasks()
    hwnd = user32.GetParent(window.winfo_id())
    # Un appui sur Alt autorise Windows à nous donner le focus pendant que le jeu est au premier plan.
    user32.keybd_event(0x12, 0, 0, 0)
    user32.keybd_event(0x12, 0, 2, 0)
    user32.SetForegroundWindow(hwnd)
    window.focus_force()


def make_non_activating(window):
    window.update_idletasks()
    hwnd = user32.GetParent(window.winfo_id())
    gwl_exstyle, ws_ex_noactivate, ws_ex_toolwindow = -20, 0x08000000, 0x80
    style = user32.GetWindowLongW(hwnd, gwl_exstyle)
    user32.SetWindowLongW(hwnd, gwl_exstyle, style | ws_ex_noactivate | ws_ex_toolwindow)


# ---------------- Voix ----------------

class Recorder:
    def __init__(self):
        try:
            import numpy
            import sounddevice
            self.np, self.sd = numpy, sounddevice
        except ImportError:
            self.np = self.sd = None
        self.stream = None
        self.frames = []

    @property
    def available(self):
        return self.sd is not None

    def start(self):
        if not self.available:
            return
        self.frames = []
        try:
            self.stream = self.sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                                              callback=lambda data, *_: self.frames.append(data.copy()))
            self.stream.start()
        except Exception:
            log("Micro indisponible :\n" + traceback.format_exc())
            self.stream = None

    def stop(self):
        if self.stream is None:
            return None
        self.stream.stop()
        self.stream.close()
        self.stream = None
        return self.np.concatenate(self.frames)[:, 0] if self.frames else None


class Transcriber:
    def __init__(self):
        self.model = None
        self.error = None
        self.ready = threading.Event()
        threading.Thread(target=self._load, daemon=True).start()

    def _load(self):
        try:
            from faster_whisper import WhisperModel
            self.model = WhisperModel(WHISPER_MODEL, device="cpu", compute_type="int8")
        except Exception as e:
            self.error = e
            log("Reconnaissance vocale indisponible :\n" + traceback.format_exc())
        self.ready.set()

    def transcribe(self, audio):
        self.ready.wait()
        if self.model is None:
            raise RuntimeError(f"Reconnaissance vocale indisponible ({self.error}). Tape ta question.")
        segments, _ = self.model.transcribe(audio, language="fr", vad_filter=True,
                                            initial_prompt=WHISPER_HINT)
        return " ".join(s.text.strip() for s in segments).strip()


def neural_voice(text):
    """Synthèse avec une voix neuronale Microsoft (service de lecture à voix haute d'Edge), en échantillons mono."""
    import asyncio
    import io

    import av
    import edge_tts
    import numpy as np

    async def synthesize():
        mp3 = bytearray()
        async for chunk in edge_tts.Communicate(text, VOICE, rate=VOICE_SPEED).stream():
            if chunk["type"] == "audio":
                mp3.extend(chunk["data"])
        return bytes(mp3)

    container = av.open(io.BytesIO(asyncio.run(synthesize())))
    resampler = av.AudioResampler(format="flt", layout="mono", rate=VOICE_SAMPLE_RATE)
    samples = []
    for frame in container.decode(audio=0):
        samples.extend(f.to_ndarray()[0] for f in resampler.resample(frame))
    samples.extend(f.to_ndarray()[0] for f in resampler.resample(None))
    return np.concatenate(samples)


class Speaker:
    """Lit les phrases à la suite : la suivante est synthétisée pendant que la précédente est lue.
    Voix neuronale gratuite (il faut Internet), voix Windows en secours."""

    def __init__(self):
        self.proc = None        # voix Windows de secours
        self.generation = 0     # chaque stop() annule les phrases en attente
        self.texts = queue.Queue()
        self.sounds = queue.Queue()
        threading.Thread(target=self._synthesize_loop, daemon=True).start()
        threading.Thread(target=self._play_loop, daemon=True).start()

    def say(self, text):
        self.stop()
        self.add(text)

    def add(self, text):
        text = re.sub(r"[*#`_>|]", "", text).strip()
        if text:
            self.texts.put((self.generation, text))

    def stop(self):
        self.generation += 1
        drain(self.texts)
        drain(self.sounds)
        try:
            import sounddevice
            sounddevice.stop()
        except Exception:
            pass
        if self.proc and self.proc.poll() is None:
            self.proc.kill()

    def _synthesize_loop(self):
        while True:
            generation, text = self.texts.get()
            if generation != self.generation:
                continue
            try:
                self.sounds.put((generation, neural_voice(text), text))
            except Exception:
                log("Voix neuronale indisponible, voix Windows à la place :\n" + traceback.format_exc())
                self.sounds.put((generation, None, text))

    def _play_loop(self):
        while True:
            generation, audio, text = self.sounds.get()
            if generation != self.generation:
                continue
            try:
                if audio is not None:
                    import sounddevice
                    sounddevice.play(audio, VOICE_SAMPLE_RATE)
                    sounddevice.wait()
                else:
                    self._windows_voice(text)
            except Exception:
                log(traceback.format_exc())

    def _windows_voice(self, text):
        text_file = Path(tempfile.gettempdir()) / "assistant_mc_parole.txt"
        text_file.write_text(text, encoding="utf-8")
        self.proc = subprocess.Popen(
            ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
             "-File", str(HERE / "parler.ps1"), str(text_file)],
            creationflags=NO_WINDOW)
        self.proc.wait()


# ---------------- Interface ----------------

class App:
    def __init__(self):
        self.root = tk.Tk()
        self.root.withdraw()
        self.events = queue.Queue()
        self.bridge = Bridge(MINECRAFT_DIR)
        self.recorder = Recorder()
        self.transcriber = Transcriber() if self.recorder.available else None
        self.speaker = Speaker()
        self.session_id = load_session()
        self.speak = SPEAK
        self.busy = False
        self.popup = self.entry = None
        self.overlay = self.overlay_text = None
        self.overlay_token = 0
        self.previous_window = None
        self.shot = None
        self.talk_key_held = False
        self.talk_cancelled = False
        self.game = None             # dernier état du jeu, None si le jeu n'est pas connecté
        self.storage = Storage([])
        self.storage_text = None
        self.logged_errors = set()
        self.visible = True
        self.status = ""
        self.message, self.message_until = "", 0
        self.last_view = None
        self.last_write = 0
        self.refresh_pending = False
        self.guide = None            # flèche demandée à la voix vers un lieu
        self.veins = []              # filons de minerai repérés
        self.veins_read = 0
        self.craftable, self.craftable_read = set(), 0  # objets qu'une recette du pack produit
        self.info, self.info_pending = None, set()      # fiche « à quoi ça sert » affichée, fiches en rédaction
        self.search = None           # objets à mettre en surbrillance (« trouve-moi mes torches »)
        try:
            self.infos = json.loads(INFO_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.infos = {}
        self.world = World()         # ce que voit le script serveur
        self.client_storage = Storage([])
        # mod Minecraft : écran de l'assistant, surbrillance, faisceaux
        self.history = []            # conversation affichée dans l'écran de l'assistant
        self.last_mod_view = None
        self.mod_request_time, self.mod_request_n = None, None
        # journal et coach
        self.session_open = False
        self.game_lost_at = 0
        self.previous_state = None
        self.coach_times, self.coach_queue, self.coach_busy = {}, [], False
        self.last_progress = self.last_moved = time.time()
        self.quests_read, self.quest_titles = 0, None
        self.known_machines = self.known_veins = None
        self.removed_chests = []     # coffres retirés de la mémoire : (heure, coffre, cassé tout seul ?)
        self.forgotten_keys = set()  # coffres que l'on vient de demander au jeu d'oublier
        self.load_plan()
        self.load_places()

        try:
            script_changed = self.bridge.install()
        except Exception as e:
            log(traceback.format_exc())
            script_changed = False
            self.show_overlay(f"Lien avec le jeu impossible : {e}", hide_after=15000)

        for key, event in ((HOTKEY_PLAN, "plan"), (HOTKEY_TYPE, "type"), (HOTKEY_NEW, "new"),
                           (HOTKEY_HIDE, "hide"), (HOTKEY_VOICE, "voice")):
            keyboard.add_hotkey(key, self.events.put, args=((event,),))
        keyboard.on_press_key(HOTKEY_TALK, self.talk_key_down)
        keyboard.on_release_key(HOTKEY_TALK, self.talk_key_up)
        self.root.after(50, self.poll)
        self.root.after(1000, self.watch_game)
        self.root.after(1000, self.check_mod_requests)

        hint = ("\n\nScript du jeu mis à jour : dans Minecraft, appuie sur F3 + T (rechargement, environ "
                "1 min) ou relance le jeu." if script_changed else "")
        self.show_overlay(f"Assistant Minecraft prêt.\n{HOTKEY_PLAN.upper()} : objectif suivant\n"
                          f"{HOTKEY_TALK.upper()} (tenir) : parler   Maj+{HOTKEY_TALK.upper()} : écrire\n"
                          f"{HOTKEY_HIDE.upper()} : cacher   {HOTKEY_VOICE.upper()} : voix on/off\n"
                          f"{HOTKEY_NEW.title()} : nouvelle conversation{hint}",
                          hide_after=25000 if script_changed else 8000)
        log(f"Démarrage, Claude Code : {find_claude()}, script du jeu {'mis à jour' if script_changed else 'à jour'}")

    def run(self):
        self.root.mainloop()

    def poll(self):
        try:
            while not self.events.empty():
                event, *data = self.events.get()
                getattr(self, "on_" + event)(*data)
        except Exception:
            log(traceback.format_exc())
        self.root.after(50, self.poll)

    # --- plan mémorisé ---

    def load_plan(self):
        try:
            saved = json.loads(PLAN_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            saved = {}
        self.plan = saved.get("plan")
        self.plan_version = saved.get("version", 0)
        self.reached = set(saved.get("atteints", []))
        self.done_steps = set(saved.get("etapes_faites", []))
        self.roadmap = saved.get("feuille_de_route", [])   # objectifs suivants : [{titre, demande}]
        self.plan_done_announced = True  # pas d'enchaînement automatique sur un ancien plan au démarrage

    def save_plan(self):
        PLAN_FILE.write_text(json.dumps({"plan": self.plan, "version": self.plan_version,
                                         "atteints": sorted(self.reached), "etapes_faites": sorted(self.done_steps),
                                         "feuille_de_route": self.roadmap},
                                        ensure_ascii=False, indent=1), encoding="utf-8")

    def load_places(self):
        try:
            self.places = json.loads(PLACES_FILE.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            self.places = {}

    def save_places(self):
        PLACES_FILE.write_text(json.dumps(self.places, ensure_ascii=False, indent=1), encoding="utf-8")

    def have(self):
        return {k: int(v) for k, v in (self.game or {}).get("inventaire", {}).items()}

    def progress(self):
        if not self.plan:
            return None
        return evaluate(self.plan, self.have(), self.reached, self.done_steps,
                        stored=self.storage.totals(), placed=self.storage.placed())

    # --- suivi du jeu ---

    def watch_game(self):
        try:
            state = self.bridge.state()
            if state and not self.game:
                log("Jeu connecté")
                self.on_game_connected()
            elif self.game and not state:
                self.game_lost_at = time.time()
            if not state and self.session_open and time.time() - self.game_lost_at > 60:
                self.session_open = False
                self.journal("session", "Fin de la partie")
            self.game = state
            self.check_craftable()
            if state:
                self.check_game(state)
                self.check_coach(state)
                self.previous_state = state
            self.flush_coach()
        except Exception:
            log(traceback.format_exc())
        self.refresh()
        self.root.after(1000, self.watch_game)

    def check_game(self, state):
        self.check_quests()
        for error in state.get("erreurs", []):
            if error not in self.logged_errors:
                self.logged_errors.add(error)
                log("Erreur dans le jeu : " + error)

        if time.time() - self.veins_read > 5:
            self.veins, self.veins_read = find_veins(self.bridge), time.time()
            self.check_new_veins()
        client = Storage.load(self.bridge) or self.client_storage  # coffres et machines ouverts par le joueur
        self.client_storage = client
        snapshot = self.bridge.read("monde.json")                 # ce que voit le script serveur
        if snapshot and snapshot.get("t", 0) > self.world.last_t:
            before = {k: dict(v) for k, v in self.world.blocks.items()}
            if self.world.update(snapshot):
                self.check_world_alerts(before)
                for error in self.world.errors:
                    if error not in self.logged_errors:
                        self.logged_errors.add(error)
                        log("Erreur sur le serveur : " + error)
        storage = self.world.merged(client) if self.world.blocks else client
        self.check_new_machines(storage)
        text = storage.as_text()
        if text != self.storage_text:
            if self.storage_text is not None:
                self.check_removed_chests(storage)
                if state.get("coffre_ouvert"):
                    chest = next((c for c in storage.containers if c["cle"] == state["coffre_ouvert"]), None)
                    if chest:
                        self.notify(f"Coffre mémorisé : {len(chest['objets'])} sortes d'objets.", 4)
            self.bridge.write_text("stockage.txt", text)
            self.storage_text = text
        self.storage = storage

        if self.guide and self.guide.get("x") is not None and self.guide.get("z") is not None and math.dist(
                (self.guide["x"], self.guide["z"]), (state["x"], state["z"])) < 3:
            self.notify(f"Tu es arrivé : {self.guide['label']}.", 8, speak=True)
            self.guide = None

        if self.plan:
            progress = self.progress()
            new_steps = {i for i, done in enumerate(progress["etapes"]) if done} - self.done_steps
            if progress["atteints"] != self.reached or new_steps:
                self.reached = progress["atteints"]
                self.done_steps |= new_steps
                self.save_plan()
                self.last_progress = time.time()
            if new_steps and not progress["termine"]:
                steps = self.plan["etapes"]
                for i in sorted(new_steps):
                    self.journal("étape", steps[i]["texte"])
                upcoming = next((s["texte"] for i, s in enumerate(steps) if i not in self.done_steps), None)
                self.coach(f"Étape faite : {steps[max(new_steps)]['texte']}."
                           + (f" Ensuite : {upcoming}." if upcoming else ""), USEFUL)
            if progress["termine"] and not self.plan_done_announced:
                self.plan_done_announced = True
                self.journal("objectif", f"Objectif atteint : {self.plan['objectif']}")
                follow = " Je prépare la suite." if AUTO_NEXT else f" Appuie sur {HOTKEY_PLAN.upper()} pour la suite."
                self.notify("Objectif terminé !" + follow, 30, speak=True)
                if AUTO_NEXT:
                    self.root.after(6000, lambda: self.on_plan(auto=True))

    # --- journal de la partie ---

    def journal(self, kind, text):
        with JOURNAL_FILE.open("a", encoding="utf-8") as f:
            f.write(json.dumps({"t": time.time(), "type": kind, "texte": text}, ensure_ascii=False) + "\n")

    def journal_tail(self, count=40):
        try:
            lines = JOURNAL_FILE.read_text(encoding="utf-8").splitlines()[-count:]
        except OSError:
            return []
        events = []
        for line in lines:
            try:
                events.append(json.loads(line))
            except ValueError:
                pass
        return events

    def describe_journal(self, count=30):
        events = self.journal_tail(count)
        if not events:
            return ""
        return "Journal récent de la partie :\n" + "\n".join(
            f"{datetime.fromtimestamp(e['t']):%d/%m %H:%M} {e['type']} : {e['texte']}" for e in events)

    def check_craftable(self):
        """Relit la liste des objets fabricables quand le jeu a réexporté ses recettes."""
        try:
            changed = (self.bridge.dir / "recettes_info.json").stat().st_mtime
        except OSError:
            return
        if changed != self.craftable_read:
            self.craftable_read = changed
            threading.Thread(target=lambda: setattr(self, "craftable", load_craftable(self.bridge.dir)),
                             daemon=True).start()

    def on_game_connected(self):
        if self.session_open:
            return
        self.session_open = True
        last = self.journal_tail(1)  # un retour après une courte pause (ou un redémarrage de l'assistant) : pas de briefing
        away = bool(last) and time.time() - last[-1]["t"] > BRIEFING_PAUSE * 60
        self.journal("session", "Début de la partie")
        if BRIEFING and away:
            self.root.after(15000, self.start_briefing)
        if not self.plan:
            # Pas d'objectif en arrivant : l'assistant le prépare tout seul (avec la feuille de route s'il n'y en a pas)
            self.root.after(25000, lambda: self.on_plan(auto=True))

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
                self.coach(f"Quête terminée : {title.split(' > ')[-1]} !", USEFUL, key="quête " + title)
        self.quest_titles = titles

    def check_new_machines(self, storage):
        keys = {m["cle"]: m for m in storage.machines}
        if self.known_machines is not None:
            for key in keys.keys() - self.known_machines:
                m = keys[key]
                self.journal("machine", f"{m['nom']} en {int(m['x'])} {int(m['y'])} {int(m['z'])}")
                self.coach(f"Machine retenue : {m['nom']}.", CHATTY)
        self.known_machines = set(keys)

    def check_new_veins(self):
        materials = {v["minerai"]: v for v in self.veins}
        if self.known_veins is not None:
            for material in materials.keys() - self.known_veins:
                v = materials[material]
                name = material.replace("_", " ").title()
                self.journal("filon", f"{name} en {int(v['x'])} {int(v['y'])} {int(v['z'])}")
                self.coach(f"Nouveau filon repéré : {name}, en {int(v['x'])} {int(v['y'])} {int(v['z'])}. "
                           "Je le garde en mémoire.", USEFUL)
        self.known_veins = set(materials)

    def check_world_alerts(self, before):
        """Alertes sur les machines vues par le serveur : boiler à sec, machine bloquée, multibloc cassé."""
        machines = self.world.machines()
        boilers = [m for m in machines if "eau" in m["etat"]]
        # Les machines à vapeur tournent sur la réserve alors que tous les boilers sont éteints : prévenir avant la panne
        if boilers and all(b["etat"]["t"][0] < 100 for b in boilers):
            for m in machines:
                state = m["etat"]
                if "eau" not in state and "vap" in state and state.get("s") == "WORKING" and state["vap"][1] \
                        and state["vap"][0] / state["vap"][1] < 0.6:
                    self.coach(f"Tes boilers sont éteints : ton {m['nom']} tourne sur sa réserve de Steam "
                               f"({round(100 * state['vap'][0] / state['vap'][1])} %) et va bientôt s'arrêter. "
                               "Remets du combustible.", USEFUL, cooldown=600, key="boilers éteints")
                    break
        for m in machines:
            state, key = m["etat"], m["cle"]
            old = (before.get(key) or {}).get("etat") or {}
            where = f"{m['nom']} en {m['x']} {m['y']} {m['z']}"
            if "eau" in state:
                water, capacity = state["eau"]
                hot = state["t"][0] >= 100
                if state.get("se") and hot:
                    self.coach(f"Danger : le {where} est à sec et chaud. Ne lui remets surtout pas d'eau, il "
                               "exploserait : retire le combustible et laisse-le refroidir.", DANGER, cooldown=120,
                               key="à sec " + key)
                elif capacity and hot and water / capacity < 0.15:
                    self.coach(f"Le {where} va manquer d'eau ({round(100 * water / capacity)} %).", USEFUL,
                               cooldown=300, key="eau " + key)
            if state.get("s") == "WAITING" and old.get("s") == "WAITING" and state.get("r"):
                self.coach(f"{where} est en attente : {state['r']}.", USEFUL, cooldown=600, key="attente " + key)
            if old.get("s") == "WORKING" and state.get("s") == "IDLE" and not re.search(r"boiler", m["bloc"]):
                self.coach(f"{where} a fini : plus rien à faire.", CHATTY, cooldown=300, key="fini " + key)
            if old.get("f") is True and state.get("f") is False:
                self.coach(f"{where} n'est plus formé : il lui manque un bloc.", USEFUL, cooldown=600,
                           key="formé " + key)

    # --- coach : il parle de lui-même au bon moment ---

    def coach(self, text, level=USEFUL, cooldown=0, key=None, speak=True):
        if COACH_LEVELS.get(COACH, 1) < level:
            return
        key = key or text
        if time.time() - self.coach_times.get(key, 0) < cooldown:
            return
        self.coach_times[key] = time.time()
        self.coach_queue.append((text, speak))
        self.flush_coach()

    def flush_coach(self):
        """Le coach ne coupe jamais la parole : il attend que tu aies fini de parler et que Claude ait répondu."""
        if not self.coach_queue or self.busy or self.recorder.stream is not None or self.popup:
            return
        text, speak = self.coach_queue.pop(0)
        self.add_history("claude", text)
        self.notify(text, 15, speak=speak)

    def add_history(self, who, text):
        self.history = (self.history + [{"de": who, "texte": text}])[-60:]

    def on_history(self, who, text):
        self.add_history(who, text)

    def check_coach(self, state):
        before = self.previous_state
        now = time.time()
        if before and math.dist((before["x"], before["z"]), (state["x"], state["z"])) > 2:
            self.last_moved = now
        if not before:
            return
        health, health_before = state.get("sante"), before.get("sante")
        if state.get("mort") and not before.get("mort"):
            self.places["dernière mort"] = {"x": int(state["x"]), "y": int(state["y"]), "z": int(state["z"]),
                                            "dimension": state["dimension"]}
            self.save_places()
            self.journal("mort", f"en {int(state['x'])} {int(state['y'])} {int(state['z'])}")
            self.coach(f"Tu es mort en {int(state['x'])} {int(state['y'])} {int(state['z'])}. J'ai noté l'endroit : "
                       "dis « ramène-moi à ma dernière mort » pour récupérer tes affaires.", DANGER)
        elif health is not None and health_before is not None and health <= 6 < health_before:
            self.coach(f"Attention, plus que {health / 2:g} cœurs !", DANGER, cooldown=30, key="santé")
        food, food_before = state.get("faim"), before.get("faim")
        if food is not None and food_before is not None and food <= 6 < food_before:
            self.coach("Tu as faim, pense à manger.", USEFUL, cooldown=300, key="faim")
        hour, hour_before = state.get("heure"), before.get("heure")
        if hour is not None and hour_before is not None and hour_before < 11800 <= hour:
            base = self.places.get("base")
            if base and base["dimension"] == state["dimension"]:
                far = math.dist((base["x"], base["z"]), (state["x"], state["z"]))
                if far > 48:
                    self.coach(f"La nuit tombe dans une minute et tu es à {round(far)} blocs de ta base.", DANGER,
                               cooldown=600, key="nuit")
            else:
                self.coach("La nuit tombe dans une minute.", USEFUL, cooldown=600, key="nuit")
        free, free_before = state.get("cases_libres"), before.get("cases_libres")
        if free == 0 and free_before:
            self.coach("Ton inventaire est plein.", USEFUL, cooldown=300, key="inventaire")
        self.check_stuck(state, now)

    def check_stuck(self, state, now):
        """Pas d'avancée sur le plan depuis un moment alors que le joueur joue : le coach propose son aide."""
        if (not self.plan or self.busy or self.coach_busy or self.progress()["termine"]
                or now - self.last_progress < STUCK_MINUTES * 60 or now - self.last_moved > 120
                or now - self.coach_times.get("bloqué", 0) < 20 * 60 or COACH_LEVELS.get(COACH, 1) < USEFUL):
            return
        self.coach_times["bloqué"] = now
        self.coach_busy = True
        threading.Thread(target=self.work_coach, args=(
            f"Le joueur n'a pas avancé sur son objectif depuis {STUCK_MINUTES} minutes. En 2 phrases au plus, "
            "donne-lui un conseil concret pour se débloquer : quoi fabriquer, où aller, quelle machine utiliser.",),
            daemon=True).start()

    def start_briefing(self):
        if self.busy or self.coach_busy or not self.game:
            return
        self.coach_busy = True
        threading.Thread(target=self.work_coach, args=(
            "Le joueur reprend sa partie. Fais-lui un briefing de reprise en 2 ou 3 phrases, sans formule de "
            "politesse : où il en était la dernière fois (d'après le journal), ce qui a changé, et quoi faire "
            "maintenant.",), daemon=True).start()

    def work_coach(self, request):
        try:
            prompt = f"{self.context(None, with_storage=True)}\n{self.describe_journal(60)}\n\n{request}"
            answer, _ = ask_claude(prompt, None, self.bridge.dir, MODEL_QUICK)
            self.events.put(("coach_message", readable(split_actions(answer)[0])))
        except Exception:
            log(traceback.format_exc())
        finally:
            self.coach_busy = False

    def on_coach_message(self, text):
        self.coach(text, USEFUL)

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
                self.notify(f"Coffre en {int(chest['x'])} {int(chest['y'])} {int(chest['z'])} oublié : "
                            "il n'est plus là.", 8)
        self.removed_chests = [r for r in self.removed_chests if time.time() - r[0] < 1800]

    def refresh(self, force=False):
        """Recalcule l'affichage du jeu et l'écrit s'il a changé (au plus ~3 fois par seconde)."""
        now = time.time()
        if not force and now - self.last_write < 0.3:
            if not self.refresh_pending:
                self.refresh_pending = True
                self.root.after(300, self.delayed_refresh)
            return
        message = self.message if (not self.message_until or now < self.message_until) else ""
        try:
            lines = build_view(self.plan, self.progress(), self.game, self.storage, self.have(),
                               self.status, message, self.guide, self.bridge.script_outdated(self.game),
                               self.roadmap, self.veins, self.world.alerts() if self.world.active else [],
                               self.world.active)
        except Exception:
            log(traceback.format_exc())
            return
        view = {"version": self.plan_version, "visible": self.visible, "lignes": lines}
        if view != self.last_view:
            try:
                self.bridge.write("plan.json", view)
                self.last_view = view
                self.last_write = now
            except Exception:
                log(traceback.format_exc())
        try:
            mod_view = build_mod_view(self)
            if mod_view != self.last_mod_view:
                self.bridge.write("vue_mod.json", mod_view)
                self.last_mod_view = mod_view
        except Exception:
            log(traceback.format_exc())

    # --- demandes venues de l'écran de l'assistant dans le jeu (mod) ---

    def check_mod_requests(self):
        try:
            path = self.bridge.dir / "demande_mod.json"
            if path.exists() and path.stat().st_mtime != self.mod_request_time:
                self.mod_request_time = path.stat().st_mtime
                request = json.loads(path.read_text(encoding="utf-8"))
                if self.mod_request_n is not None and request.get("n") != self.mod_request_n:
                    self.handle_mod_request(request)
                self.mod_request_n = request.get("n")  # la première, d'avant le démarrage, est ignorée
        except (OSError, ValueError):
            pass  # fichier en cours d'écriture : on relira au prochain passage
        except Exception:
            log(traceback.format_exc())
        self.root.after(300, self.check_mod_requests)

    def handle_mod_request(self, request):
        kind = request.get("type")
        if kind == "question" and request.get("texte"):
            if self.busy:
                self.notify("Patience, je réfléchis encore…", 4)
            else:
                self.shot = None
                self.start_question(str(request["texte"]), None)
        elif kind == "info" and request.get("item"):
            self.show_info(request)
        elif kind == "action":
            action = request.get("action")
            if action in ("objectif_suivant", "plan"):
                self.on_plan()
            elif action == "affichage":
                self.on_hide()
            elif action == "voix":
                self.on_voice()

    # --- fiches « à quoi ça sert » (touche I du mod) ---

    def show_info(self, request):
        """Fiche d'un objet : tout de suite si Claude l'a déjà rédigée, sinon il la rédige (une fois pour toutes,
        ou de nouveau quand le joueur demande « Refaire »)."""
        item = str(request["item"])
        if request.get("refaire"):
            self.infos.pop(item, None)
        status = self.machine_at(request)
        self.info = {"item": item, "nom": str(request.get("nom") or item), "machine": status,
                     "machine_couleur": machine_color(status)}
        if item in self.infos:
            self.info.update(self.infos[item], etat="pret")
        else:
            self.info["etat"] = "en_cours"
            if item not in self.info_pending:
                self.info_pending.add(item)
                threading.Thread(target=self.work_info, args=(item, self.info["nom"]), daemon=True).start()
        self.refresh(force=True)

    def machine_at(self, request):
        """L'état en direct de la machine regardée, vu par le serveur (vide pour un objet ou un bloc ordinaire)."""
        if request.get("origine") != "bloc" or not self.world.active:
            return ""
        position = (request.get("x"), request.get("y"), request.get("z"))
        machine = next((m for m in self.world.machines()
                        if (int(m["x"]), int(m["y"]), int(m["z"])) == position), None)
        return machine_status(machine) if machine else ""

    def work_info(self, item, name):
        try:
            answer, _ = ask_claude(INFO_REQUEST.format(nom=name, item=item), None, self.bridge.dir, MODEL_QUESTION)
            self.events.put(("info_ready", item, parse_info(answer), None))
        except Exception as e:
            log(traceback.format_exc())
            self.events.put(("info_ready", item, None, str(e)[:120]))

    def on_info_ready(self, item, card, error):
        self.info_pending.discard(item)
        if card:
            self.infos[item] = card
            INFO_FILE.write_text(json.dumps(self.infos, ensure_ascii=False, indent=1), encoding="utf-8")
        if self.info and self.info["item"] == item:
            if card:
                self.info.update(card, etat="pret")
            else:
                self.info.update(etat="erreur", erreur=error)
            self.refresh(force=True)

    def delayed_refresh(self):
        self.refresh_pending = False
        self.refresh()

    def notify(self, text, seconds=60, speak=False):
        """Affiche un message dans le jeu si possible, sinon dans la fenêtre de l'assistant."""
        self.message, self.message_until = text, time.time() + seconds
        self.refresh(force=True)
        if not self.game:
            self.show_overlay(text, hide_after=seconds * 1000)
        if speak and self.speak:
            self.speaker.say(text)

    def set_status(self, text):
        self.status = text
        self.refresh(force=True)
        if not self.game and text:
            self.show_overlay(text)

    # --- raccourcis ---

    def talk_key_down(self, _event):  # appelé par le hook clavier : rester très court
        if self.talk_key_held or keyboard.is_pressed("ctrl") or keyboard.is_pressed("shift"):
            return
        self.talk_key_held = True
        self.events.put(("talk_start",))

    def talk_key_up(self, _event):
        if self.talk_key_held:
            self.talk_key_held = False
            self.events.put(("talk_end",))

    def on_talk_start(self):
        self.talk_cancelled = self.busy or self.popup is not None
        if self.busy:
            self.notify("Patience, je réfléchis encore…", 4)
            return
        if self.talk_cancelled:
            return
        self.speaker.stop()
        self.shot = self.safe_screenshot()
        self.recorder.start()
        if self.recorder.stream is None:
            self.talk_cancelled = True
            self.notify(f"Micro indisponible : Maj+{HOTKEY_TALK.upper()} pour écrire ta question.", 8)
            return
        self.set_status(f"● Je t'écoute… relâche {HOTKEY_TALK.upper()} pour envoyer")

    def on_talk_end(self):
        if self.talk_cancelled:
            return
        audio = self.recorder.stop()
        self.set_status("")
        if audio is None or len(audio) < SAMPLE_RATE * 0.5:
            self.notify(f"Maintiens {HOTKEY_TALK.upper()} pendant que tu parles.", 4)
            return
        self.start_question(None, audio)

    def on_type(self):
        if self.busy:
            self.notify("Patience, je réfléchis encore…", 4)
            return
        if self.popup:
            return
        self.speaker.stop()
        self.previous_window = user32.GetForegroundWindow()
        self.shot = self.safe_screenshot()
        self.open_popup()

    def on_plan(self, auto=False, request=""):
        """Nouveau plan : avec F7 ou tout seul (Claude choisit), ou à la voix pour une demande précise."""
        if self.busy:
            if not auto:
                self.notify("Patience, je réfléchis encore…", 4)
            return
        if not self.game:
            if not auto:
                self.show_overlay("Le jeu n'est pas connecté : entre dans ton monde (le script KubeJS doit être "
                                  "chargé, voir assistant.log).", hide_after=10000)
            return
        if not auto:
            self.speaker.stop()
        from_roadmap = False
        if not request and self.roadmap:  # la feuille de route décide de l'objectif suivant
            request, from_roadmap = self.roadmap[0]["demande"], True
        self.busy = True
        self.plan_done_announced = True
        self.set_status("Claude prépare ton objectif…")
        shot = None if auto or request else self.safe_screenshot()
        threading.Thread(target=self.work_plan, args=(shot, request, from_roadmap), daemon=True).start()

    def on_new(self):
        self.session_id = None
        save_session(None)
        self.notify("Nouvelle conversation.", 4)

    def on_hide(self):
        self.speaker.stop()
        if self.game:
            self.visible = not self.visible
            self.refresh(force=True)
        elif self.overlay and self.overlay.winfo_viewable():
            self.overlay.withdraw()
        elif self.message:
            self.show_overlay(self.message)

    def on_voice(self):
        self.speak = not self.speak
        if not self.speak:
            self.speaker.stop()
        self.notify("Voix activée." if self.speak else "Voix coupée.", 3)

    def safe_screenshot(self):
        try:
            return take_screenshot()
        except Exception as e:
            log(traceback.format_exc())
            self.notify(f"Impossible de capturer l'écran : {e}", 6)
            return None

    # --- travail en arrière-plan ---

    def context(self, shot, with_storage=False):
        parts = []
        if self.game:
            parts.append(describe_state(self.game))
        if with_storage:
            parts.append(describe_storage(self.storage))
            parts.append(describe_machines(self.storage))
            parts.append(describe_veins(self.veins, self.game))
            world = describe_world(self.world)
            if world:
                parts.append(world)
        if self.roadmap:
            parts.append("Feuille de route (objectifs suivants, dans l'ordre) : "
                         + " ; ".join(f"{i + 1}. {r['titre']}" for i, r in enumerate(self.roadmap)))
        if with_storage:
            journal = self.describe_journal(25)
            if journal:
                parts.append(journal)
        if self.plan:
            progress = self.progress()
            names = item_names(self.plan)
            current = next((s["texte"] for s, done in zip(self.plan["etapes"], progress["etapes"]) if not done), None)
            state = "terminé" if progress["termine"] else f"étape en cours : {current or 'aucune'}"
            missing = ", ".join(f"{n}x {names.get(i, i)}" for i, n in progress["manque"].items())
            parts.append(f"Objectif affiché : {self.plan['objectif']} ({state}) ; il me manque : {missing or 'rien'}")
        if self.places:
            parts.append("Lieux mémorisés : " + " ; ".join(
                f"{name} ({p['x']} {p['y']} {p['z']}, {p['dimension']})" for name, p in self.places.items()))
        if self.guide:
            parts.append(f"Guidage en cours vers : {self.guide['label']}")
        removed = [f"{int(c['x'])} {int(c['y'])} {int(c['z'])} (il y a {int((time.time() - t) / 60)} min)"
                   for t, c, broken in self.removed_chests if broken]
        if removed:
            parts.append("Coffres retirés automatiquement de la mémoire (cassés) : " + ", ".join(removed))
        if shot:
            parts.append(f"Capture de mon écran (lis-la avec Read) : {shot}")
        return "\n".join(parts)

    def work_plan(self, shot, request="", from_roadmap=False):
        try:
            context = self.context(shot, with_storage=True) + "\n" + describe_quests(self.bridge.read("quetes.json"))
            prompt = PLAN_REQUEST.format(context=context)
            if request:
                prompt = (f"Le joueur demande précisément : « {request} ». Construis l'objectif pour répondre à "
                          f"cette demande (par exemple la quête qu'il cite), même si ce n'est pas la plus urgente.\n"
                          + prompt)
            answer, session_id = ask_claude(
                prompt, self.session_id, self.bridge.dir, MODEL_PLAN,
                on_tool=lambda name, data: self.events.put(("status", tool_status(name, data))))
            self.events.put(("plan_ready", parse_plan(answer), session_id, from_roadmap))
        except Exception as e:
            log(traceback.format_exc())
            self.events.put(("error", str(e)))

    def start_question(self, text, audio):
        self.busy = True
        self.set_status("Je transcris ta question…" if audio is not None else "Claude réfléchit…")
        threading.Thread(target=self.work_question, args=(text, audio, self.shot), daemon=True).start()

    def work_question(self, text, audio, shot):
        try:
            if audio is not None:
                text = self.transcriber.transcribe(audio)
                if not text:
                    raise RuntimeError("Je n'ai rien entendu : parle plus près du micro en tenant F8.")
            self.events.put(("status", f"« {text} » : Claude réfléchit…"))
            self.events.put(("history", "toi", text))
            spoken = [0]

            def on_text(partial):
                partial = readable(visible_text(partial))  # la ligne d'actions n'est ni affichée ni lue
                self.events.put(("live_text", partial))
                if len(partial) < spoken[0]:  # nouveau message de Claude
                    spoken[0] = 0
                cut = spoken[0]
                for match in SENTENCE_END.finditer(partial, spoken[0]):
                    cut = match.end()
                if cut > spoken[0] and self.speak:
                    self.speaker.add(partial[spoken[0]:cut])
                spoken[0] = cut

            self.speaker.stop()
            prompt = f"{self.context(shot, with_storage=True)}\n\n{ACTIONS_HELP}\n\nMa question : {text}"
            answer, session_id = ask_claude(
                prompt, self.session_id, self.bridge.dir, pick_model(text), on_text=on_text,
                on_tool=lambda name, data: self.events.put(("status", tool_status(name, data))))
            shown = readable(split_actions(answer)[0])
            if self.speak and spoken[0] < len(shown):
                self.speaker.add(shown[spoken[0]:])
            self.events.put(("answer", text, answer, session_id))
        except Exception as e:
            log(traceback.format_exc())
            self.events.put(("error", str(e)))

    # --- résultats ---

    def on_status(self, text):
        self.set_status(text)

    def on_live_text(self, text):
        self.message, self.message_until = text, 0
        self.refresh()

    def on_plan_ready(self, plan, session_id, from_roadmap=False):
        self.busy = False
        self.session_id = session_id
        save_session(session_id)
        if from_roadmap and self.roadmap:
            self.roadmap.pop(0)  # cet objectif de la feuille de route est maintenant le plan affiché
        if not self.roadmap and plan.get("feuille_de_route"):
            self.roadmap = plan["feuille_de_route"]  # première fois : l'ordre de la suite, sans rien demander
        self.plan, self.plan_version = plan, int(time.time())
        self.reached, self.done_steps = set(), set()
        # Les étapes déjà faites au départ sont cochées sans être annoncées
        self.done_steps = {i for i, done in enumerate(self.progress()["etapes"]) if done}
        self.last_progress = time.time()
        self.journal("plan", f"Nouvel objectif : {plan['objectif']}")
        # Un plan déjà rempli d'avance ne doit pas relancer l'enchaînement automatique en boucle
        self.plan_done_announced = self.progress()["termine"]
        self.save_plan()
        log(f"Plan : {json.dumps(plan, ensure_ascii=False)}")
        self.status = ""
        self.notify(readable(plan["resume"]), 45, speak=bool(plan["resume"]))

    def on_answer(self, question, answer, session_id):
        self.busy = False
        self.session_id = session_id
        save_session(session_id)
        log(f"Q : {question}\nR : {answer}")
        answer, actions = split_actions(answer)
        answer = readable(answer)
        self.add_history("claude", answer)
        self.status = ""
        self.message, self.message_until = answer, time.time() + 180
        self.refresh(force=True)
        if not self.game:
            self.show_overlay(f"Toi : {question}\n\n{answer}")
        self.run_actions(actions)

    # --- actions demandées à la voix ---

    def run_actions(self, actions):
        for action in actions:
            handler = getattr(self, "action_" + str(action.get("type")), None)
            if handler is None:
                log(f"Action inconnue : {action}")
                continue
            try:
                handler(action)
                log(f"Action : {json.dumps(action, ensure_ascii=False)}")
            except Exception:
                log(f"Action ratée : {action}\n" + traceback.format_exc())
        self.refresh(force=True)

    def send_command(self, action, **data):
        self.bridge.write("commande.json", {"n": str(time.time_ns()), "action": action, **data})

    def action_oublier_coffre(self, action):
        chests = self.storage.containers
        if action.get("proche") and self.game:
            nearby = [c for c in chests if c["dimension"] == self.game["dimension"]]
            target = min(nearby, key=lambda c: math.dist((c["x"], c["y"], c["z"]),
                                                         (self.game["x"], self.game["y"], self.game["z"])), default=None)
        else:
            position = (int(action.get("x", 0)), int(action.get("y", 0)), int(action.get("z", 0)))
            target = next((c for c in self.storage.raw if (int(c["x"]), int(c["y"]), int(c["z"])) == position), None)
        if target is None:
            self.notify("Je ne trouve pas ce coffre dans ma mémoire.", 6)
            return
        keys = [c["cle"] for c in self.storage.raw if c["cle"] == target["cle"] or Storage.twins(c, target)]
        self.forgotten_keys |= set(keys)
        self.send_command("oublier_coffres", cles=keys)

    def action_nouveau_plan(self, action):
        self.root.after(300, lambda: self.on_plan(request=str(action.get("demande") or "")))

    def action_effacer_plan(self, _action):
        self.plan, self.reached, self.done_steps = None, set(), set()
        self.save_plan()

    @staticmethod
    def roadmap_entry(data):
        title = str(data.get("titre") or data.get("demande") or "Objectif")
        return {"titre": title[:60], "demande": str(data.get("demande") or title)}

    def action_feuille_de_route(self, action):
        self.roadmap = [self.roadmap_entry(o) for o in action.get("objectifs") or [] if isinstance(o, dict)]
        self.save_plan()
        if not self.plan and self.roadmap:  # aucun objectif affiché : on attaque le premier tout de suite
            self.root.after(300, self.on_plan)

    def action_ajouter_objectif(self, action):
        entry = self.roadmap_entry(action)
        if any(r["titre"].lower() == entry["titre"].lower() for r in self.roadmap):
            return  # déjà dans la feuille de route
        position = action.get("position")
        index = len(self.roadmap) if not isinstance(position, int) else max(0, min(position - 1, len(self.roadmap)))
        self.roadmap.insert(index, entry)
        self.save_plan()

    def action_retirer_objectif(self, action):
        title = str(action.get("titre", "")).lower()
        self.roadmap = [r for r in self.roadmap if title not in r["titre"].lower()]
        self.save_plan()

    def action_objectif_suivant(self, _action):
        self.root.after(300, self.on_plan)

    def action_planifier_usine(self, action):
        request = FACTORY_REQUEST.format(demande=str(action.get("demande") or "une usine"))
        self.root.after(300, lambda: self.on_plan(request=request))

    def action_memoriser_lieu(self, action):
        if not self.game:
            return
        name = str(action.get("nom") or "lieu")
        self.places[name] = {"x": int(self.game["x"]), "y": int(self.game["y"]), "z": int(self.game["z"]),
                             "dimension": self.game["dimension"]}
        self.save_places()
        self.journal("lieu", f"{name} en {self.places[name]['x']} {self.places[name]['y']} {self.places[name]['z']}")

    def action_oublier_lieu(self, action):
        self.places.pop(str(action.get("nom")), None)
        self.save_places()

    def action_guider(self, action):
        place = self.places.get(str(action.get("nom")))
        if place:
            self.guide = {"label": str(action["nom"]), "x": place["x"], "y": place["y"], "z": place["z"]}
        elif action.get("x") is not None or action.get("y") is not None:
            self.guide = {"label": str(action.get("label") or "Destination"),
                          "x": action.get("x"), "y": action.get("y"), "z": action.get("z")}

    def action_arreter_guidage(self, _action):
        self.guide = None

    def action_surligner(self, action):
        patterns = [str(p) for p in action.get("objets") or [] if p][:80]
        if patterns:
            self.search = {"label": str(action.get("label") or "Recherche"), "objets": patterns,
                           "fin": time.time() + SEARCH_MINUTES * 60}

    def action_effacer_surbrillance(self, _action):
        self.search = None

    def action_affichage(self, action):
        self.visible = bool(action.get("visible", True))

    def action_voix(self, action):
        self.speak = bool(action.get("active", True))
        if not self.speak:
            self.speaker.stop()

    def on_error(self, message):
        self.busy = False
        self.status = ""
        self.notify(f"Erreur : {message[:300]}", 30)

    # --- fenêtre de question écrite ---

    def open_popup(self):
        self.popup = popup = tk.Toplevel(self.root, bg=BG, padx=14, pady=10)
        popup.title("Question pour Claude")
        popup.attributes("-topmost", True)
        popup.resizable(False, False)
        tk.Label(popup, text="Tape ta question puis Entrée. Échap : annuler.", bg=BG, fg=FG_DIM,
                 font=("Segoe UI", 10)).pack(anchor="w")
        self.entry = tk.Entry(popup, width=64, font=("Segoe UI", 12), bg="#2b2d31", fg=FG,
                              insertbackground=FG, relief="flat")
        self.entry.pack(fill="x", pady=(6, 0), ipady=4)
        self.entry.bind("<Return>", lambda _: self.submit())
        popup.bind("<Escape>", lambda _: self.close_popup())
        popup.protocol("WM_DELETE_WINDOW", self.close_popup)
        popup.update_idletasks()
        x = (popup.winfo_screenwidth() - popup.winfo_width()) // 2
        popup.geometry(f"+{x}+80")
        bring_to_front(popup)
        self.entry.focus_set()

    def close_popup(self):
        if self.popup:
            self.popup.destroy()
        self.popup = self.entry = None
        if self.previous_window:
            user32.SetForegroundWindow(self.previous_window)

    def submit(self):
        text = self.entry.get().strip()
        self.close_popup()
        if text:
            self.start_question(text, None)

    # --- fenêtre de secours (si le jeu n'est pas connecté) ---

    def build_overlay(self):
        self.overlay = overlay = tk.Toplevel(self.root, bg=BG)
        overlay.overrideredirect(True)
        overlay.attributes("-topmost", True)
        overlay.attributes("-alpha", 0.92)
        self.overlay_text = tk.Text(overlay, width=52, wrap="word", bg=BG, fg=FG, relief="flat",
                                    font=("Segoe UI", 12), padx=14, pady=12, cursor="arrow")
        self.overlay_text.pack(fill="both", expand=True)
        make_non_activating(overlay)

    def show_overlay(self, text, hide_after=None):
        if self.overlay is None:
            self.build_overlay()
        box = self.overlay_text
        box.config(state="normal")
        box.delete("1.0", "end")
        box.insert("1.0", text)
        box.config(state="disabled")
        lines = sum(max(1, -(-len(line) // 52)) for line in text.split("\n"))
        box.config(height=min(lines, 24))
        self.overlay.deiconify()
        self.overlay.update_idletasks()
        x = self.overlay.winfo_screenwidth() - self.overlay.winfo_reqwidth() - 24
        self.overlay.geometry(f"+{x}+24")
        self.overlay.lift()
        self.overlay_token += 1
        if hide_after:
            token = self.overlay_token
            self.root.after(hide_after, lambda: self.overlay_token == token and self.overlay.withdraw())


def main():
    if sys.stderr is None:  # lancé avec pythonw : les messages des modules vont dans le journal
        sys.stdout = sys.stderr = LOG_FILE.open("a", encoding="utf-8")
    os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    # Une seule instance à la fois
    mutex = ctypes.windll.kernel32.CreateMutexW(None, False, "AssistantMinecraftClaude")
    if ctypes.windll.kernel32.GetLastError() == 183:
        messagebox.showinfo("Assistant Minecraft", "L'assistant est déjà lancé.")
        return
    try:
        App().run()
    except Exception:
        log(traceback.format_exc())
        messagebox.showerror("Assistant Minecraft", "L'assistant a planté, détails dans assistant.log.")
    finally:
        ctypes.windll.kernel32.CloseHandle(mutex)


if __name__ == "__main__":
    main()
