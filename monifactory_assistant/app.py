"""Composition de l’assistant et supervision de son processus."""

import ctypes
import faulthandler
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
import traceback
from contextlib import nullcontext
from tkinter import messagebox

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.application.conversation import ConversationController
from monifactory_assistant.application.game import GameController
from monifactory_assistant.application.interface import InterfaceController
from monifactory_assistant.application.projects import ProjectsController
from monifactory_assistant.audio import Recorder, Speaker, Transcriber
from monifactory_assistant.commands.auto_update import notice as update_notice
from monifactory_assistant.commands.auto_update import notice_key as update_notice_key
from monifactory_assistant.diagnostics import revision
from monifactory_assistant.game.bridge import Bridge
from monifactory_assistant.game.emi_search import RecipeChestSearch
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.requests import CommandQueue
from monifactory_assistant.game.storage import Storage, StorageMonitor
from monifactory_assistant.game.world import World
from monifactory_assistant.paths import ROOT as HERE
from monifactory_assistant.persistence import load_json
from monifactory_assistant.platform.hooks import keyboard
from monifactory_assistant.platform.support import SingleInstance
from monifactory_assistant.providers.session import Conversation
from monifactory_assistant.providers.settings import AISettings
from monifactory_assistant.runtime import log
from monifactory_assistant.settings import (
    HOTKEY_HIDE,
    HOTKEY_NEW,
    HOTKEY_PLAN,
    HOTKEY_TALK,
    HOTKEY_TYPE,
    HOTKEY_VOICE,
    INFO_FILE,
    LOG_FILE,
    MINECRAFT_DIR,
    SETTINGS,
    SETTINGS_FILE,
)
from monifactory_assistant.ui.discussion import DiscussionViewer, recent_history


class App(ProjectsController, GameController, ConversationController, InterfaceController):
    """Compose les contrôleurs autour du même état et de la même boucle Tk."""

    def __init__(self):
        self.root = tk.Tk()
        self.root.withdraw()
        self.events = queue.Queue()
        self.bridge = Bridge(MINECRAFT_DIR)
        self.request_queue = CommandQueue(self.bridge.dir)
        self.recorder = Recorder()
        self.transcriber = Transcriber() if self.recorder.available else None
        self.speaker = Speaker()
        self.conversation = Conversation(self.bridge.dir)  # Codex gardé ouvert pour tes questions
        saved = load_json(SETTINGS_FILE, {})
        self.settings = {
            key: bool(saved.get(key, default)) for key, (_, _, default) in SETTINGS.items()
        }
        self.diagnostic_revision = revision()
        self.busy = False
        self.popup = self.entry = None
        self.overlay = self.overlay_text = None
        self.overlay_token = 0
        self.previous_window = None
        self.shot = None
        self.talk_key_held = False
        self.talk_cancelled = False
        self.game = None  # dernier état du jeu, None si le jeu n'est pas connecté
        self.storage = Storage([])
        self.storage_text = None
        self.storage_export_key = None
        self.logged_errors = set()
        self.status = ""
        self.connection_problem = ""
        self.message, self.message_until = "", 0
        self.last_view = None
        self.last_write = 0
        self.refresh_pending = False
        self.guide = None  # flèche demandée à la voix vers un lieu
        self.veins = []  # filons de minerai repérés
        self.veins_read = 0
        self.craftable, self.craftable_read = set(), 0  # objets qu'une recette du pack produit
        self.recipe_index = RecipeIndex()  # recettes par objet, jointes aux questions
        self.info, self.info_pending = (
            None,
            set(),
        )  # fiche « à quoi ça sert » affichée, fiches en rédaction
        self.emi_search = RecipeChestSearch()
        self.search = None  # objets à mettre en surbrillance (« trouve-moi mes torches »)
        self.infos = load_json(INFO_FILE, {})
        self.world = World(enabled=not self.bridge.isolated)  # relevés du script serveur
        self.storage_monitor = StorageMonitor()
        # mod Minecraft : écran de l'assistant, surbrillance, faisceaux
        self.history = recent_history(self.bridge.dir, ai_provider.label())
        self.last_mod_view = None
        self.mod_request_time, self.mod_request_n = None, None
        self.question_ack = {}
        self.update_notice = update_notice()
        self.update_notice_id = update_notice_key()
        self.ai_settings = AISettings(HERE)
        # journal et coach
        self.session_open = False
        self.game_lost_at = 0
        self.previous_state = None
        self.coach_times, self.coach_queue = {}, []
        self.last_progress = time.time()
        self.quests_read, self.quest_titles = 0, None
        self.known_machines = self.known_veins = None
        self.removed_chests = []  # coffres retirés de la mémoire : (heure, coffre, cassé tout seul ?)
        self.forgotten_keys = set()  # coffres que l'on vient de demander au jeu d'oublier
        if self.bridge.isolated:
            self.journal_file = None
            self.load_plan(None)
            self.load_places(None)
        else:
            self.load_plan()
            self.load_places()
        # A committed acknowledgement remains visible even if the UI missed the last frame.
        for row in sorted(self.request_queue.rows.values(), key=lambda item: item["n"]):
            if "question" in row:
                self.question_ack = row["question"]
            if "projet" in row:
                self.project_feedback = row["projet"]
                self.project_reply_n = row["n"]

        self.discussion_viewer = None
        try:
            self.discussion_viewer = DiscussionViewer(self.bridge.dir, HERE / ".runtime")
            log("Suivi de Discussion en lecture seule disponible (.runtime/suivi.json)")
        except OSError:
            log("Suivi de Discussion indisponible : " + traceback.format_exc())

        try:
            script_changed = self.bridge.install()
        except Exception as e:
            log(traceback.format_exc())
            script_changed = False
            self.show_overlay(f"Lien avec le jeu impossible : {e}", hide_after=15000)

        for key, event in (
            (HOTKEY_PLAN, "plan"),
            (HOTKEY_TYPE, "type"),
            (HOTKEY_NEW, "new"),
            (HOTKEY_HIDE, "hide"),
            (HOTKEY_VOICE, "voice"),
        ):
            keyboard.add_hotkey(key, self.events.put, args=((event,),))
        keyboard.on_press_key(HOTKEY_TALK, self.talk_key_down)
        keyboard.on_release_key(HOTKEY_TALK, self.talk_key_up)
        if sys.platform == "darwin":
            keyboard.start(self.bridge.dir)
        self.root.after(50, self.poll)
        self.root.after(1000, self.watch_game)
        self.root.after(1000, self.check_mod_requests)

        hint = (
            "\n\nScript du jeu mis à jour : dans Minecraft, appuie sur F3 + T (rechargement, environ "
            "1 min) ou relance le jeu."
            if script_changed
            else ""
        )
        self.show_overlay(
            f"Assistant Minecraft prêt.\n{HOTKEY_PLAN.upper()} : objectif suivant\n"
            f"{HOTKEY_TALK.upper()} (tenir) : parler   Maj+{HOTKEY_TALK.upper()} : écrire\n"
            f"{HOTKEY_HIDE.upper()} : cacher   {HOTKEY_VOICE.upper()} : voix on/off\n"
            f"{HOTKEY_NEW.title()} : nouvelle conversation{hint}",
            hide_after=25000 if script_changed else 8000,
        )
        log(
            f"Démarrage, {ai_provider.label()} : {ai_provider.executable()}, script du jeu {'mis à jour' if script_changed else 'à jour'}"
        )
        threading.Thread(target=self.prepare_conversation, daemon=True).start()

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


def supervise(already_locked=False):
    """Lance l'assistant dans un processus à part et le relance s'il plante : un module en C (micro, voix,
    reconnaissance vocale) peut faire tomber tout le processus sans prévenir. Une seule instance à la fois."""
    with nullcontext(True) if already_locked else SingleInstance() as acquired:
        if not acquired:
            messagebox.showinfo("Assistant Minecraft", "L'assistant est déjà lancé.")
            return
        crashes = []
        while True:
            code = subprocess.call([sys.executable, str(HERE / "assistant_mc.py"), "--enfant"])
            if code == 0:
                return
            crashes = [t for t in crashes if time.time() - t < 300] + [time.time()]
            if len(crashes) >= 3:
                log(f"L'assistant a planté 3 fois en 5 minutes (code {code:#x})")
                messagebox.showerror(
                    "Assistant Minecraft", "L'assistant plante, détails dans assistant.log."
                )
                return
            log(f"L'assistant a planté (code {code:#x}) : relance automatique")
            time.sleep(2)


def main():
    if sys.stderr is None:  # lancé avec pythonw : les messages des modules vont dans le journal
        sys.stdout = sys.stderr = LOG_FILE.open("a", encoding="utf-8", buffering=1)
    faulthandler.enable(
        sys.stderr
    )  # un plantage natif (module en C) laisse sa pile Python dans le journal
    if "--enfant" not in sys.argv:
        supervise()
        return
    os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass
    try:
        App().run()
    except Exception:
        log(traceback.format_exc())
        # The parent limits retries and shows the error if all attempts fail.
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
