"""Valeurs du lanceur et chemins historiques des données locales."""

import re
import subprocess

from monifactory_assistant.config import minecraft_dir
from monifactory_assistant.paths import ROOT as HERE
from monifactory_assistant.paths import WORLD_FILE as WORLD_FILE

MINECRAFT_DIR = minecraft_dir()

HOTKEY_PLAN = "f7"

HOTKEY_TALK = "f8"  # à maintenir pendant qu'on parle

HOTKEY_TYPE = "shift+f8"

HOTKEY_NEW = "ctrl+f8"

HOTKEY_HIDE = "f9"

HOTKEY_VOICE = "f10"

COACH = "normal"  # "calme" : aucune annonce ; "normal" ; "bavard" : il commente tout

SEARCH_MINUTES = 10  # durée de la surbrillance demandée (« trouve-moi mes torches »)

SETTINGS = {
    "voix": (
        "Lecture à voix haute",
        "Les réponses et les messages de l'assistant sont lus à voix haute.",
        False,
    ),
    "panneau": (
        "Panneau en jeu",
        "L'objectif, l'étape en cours et les objets utiles, en haut à gauche.",
        True,
    ),
    "progression": (
        "Annonces de progression",
        "Étapes et quêtes terminées, nouveaux filons, machines retenues.",
        True,
    ),
    "enchainer": (
        "Objectif suivant automatique",
        "Un objectif terminé, le suivant du plan se prépare tout seul.",
        True,
    ),
    "surbrillance": (
        "Surbrillance des besoins",
        "Les objets dont l'objectif a besoin brillent en vert dans les coffres.",
        True,
    ),
    "faisceaux": (
        "Faisceau de guidage",
        "Le faisceau bleu vers la destination (coffre, filon, lieu demandé).",
        True,
    ),
    "remplir": (
        "Remplissage de l'établi",
        "À l'établi, les boutons Remplir et Tout pour la fabrication attendue.",
        True,
    ),
    "coffres": (
        "Retrait depuis les coffres",
        "Dans un coffre, le panneau qui prend les ingrédients de l'arbre EMI en cours.",
        True,
    ),
}

VOICE = "fr-FR-VivienneMultilingualNeural"

VOICE_SPEED = "+10%"  # vitesse de lecture, par exemple "+0%" ou "+25%"

VOICE_SAMPLE_RATE = 24000

WHISPER_MODEL = "small"  # "base" : plus rapide, "small" : comprend mieux

TIMEOUT = 420  # secondes max par réponse

CAPTURES = HERE / "captures"

PLAN_FILE = HERE / "plan_ia.json"

PLACES_FILE = HERE / "lieux.json"

JOURNAL_FILE = HERE / "journal.jsonl"

INFO_FILE = HERE / "fiches.json"  # fiches « à quoi ça sert » déjà rédigées par Codex (touche I)

SETTINGS_FILE = HERE / "reglages.json"

COACH_LEVELS = {"calme": 0, "normal": 1, "bavard": 2}

USEFUL, CHATTY = 1, 2  # niveau minimal du coach pour chaque message

LOG_FILE = HERE / "assistant.log"

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

SAMPLE_RATE = 16000

BG, FG, FG_DIM = "#1e1f22", "#f2f2f2", "#a8a8a8"

FOOTER = "F7 objectif · F8 parler · Maj+F8 écrire · F9 cacher"

MAX_TO_MAKE, MAX_MISSING = 4, 5  # lignes affichées au plus dans « À fabriquer » et « Il te manque »

WHISPER_HINT = (
    "Minecraft, Monifactory, GregTech, Steam, Boiler, Alloy Smelter, Coke Oven, "
    "Bronze, Steel, Bender, Wiremill, Creosote, multibloc, JEI, LV, MV."
)

SENTENCE_END = re.compile(r"[.!?…](?=\s)|\n")

ACTION_TAG = re.compile(r"<actions>(.*?)(?:</actions>|$)", re.S)

ITEM_ID = re.compile(r"\b[a-z_][a-z0-9_]*:([a-z][a-z0-9_/]*)\b")
