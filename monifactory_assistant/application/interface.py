"""Interface locale et protocole des menus Minecraft."""

import json
import threading
import time
import tkinter as tk
import traceback

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.diagnostics import view as diagnostic_view
from monifactory_assistant.game.snapshots import render_frame
from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.platform.capture import (
    bring_to_front,
    make_non_activating,
    take_screenshot,
    user32,
)
from monifactory_assistant.runtime import log
from monifactory_assistant.settings import BG, FG, FG_DIM, SETTINGS, SETTINGS_FILE
from monifactory_assistant.ui.views import build_mod_view, build_view


class InterfaceController:
    """Interface locale, réglages et protocole du menu Minecraft."""

    @property
    def speak(self):
        return self.settings["voix"]

    @property
    def visible(self):
        return self.settings["panneau"]

    def set_setting(self, key, value):
        if key not in SETTINGS:
            return
        values = dict(self.settings, **{key: bool(value)})
        atomic_json(SETTINGS_FILE, values)
        self.settings = values
        if key == "voix" and not value:
            self.speaker.stop()
        self.refresh(force=True)

    def refresh(self, force=False):
        """Recalcule l'affichage du jeu et l'écrit s'il a changé (au plus ~3 fois par seconde)."""
        now = time.monotonic()
        if not force and now - getattr(self, "last_render", 0) < 0.3:
            if not self.refresh_pending:
                self.refresh_pending = True
                self.root.after(300, self.delayed_refresh)
            return
        self.last_render = now
        started = time.perf_counter()
        try:
            with render_frame(self):
                self.refresh_views()
        finally:
            self.last_render_ms = (time.perf_counter() - started) * 1000

    def refresh_views(self):
        now = time.time()
        message = self.message if (not self.message_until or now < self.message_until) else ""
        try:
            lines = build_view(
                None if self.objective_paused else self.plan,
                None if self.objective_paused else self.progress(),
                None if self.objective_paused else self.game,
                self.storage,
                self.have(),
                self.status,
                message,
                self.guide,
                self.bridge.script_outdated(self.game),
                self.objectives,
                self.veins,
                (),
                self.world.active,
            )
            if self.objective_paused and self.plan:
                lines.insert(
                    0,
                    {
                        "t": self.plan["objectif"] + " · En pause (Y pour reprendre)",
                        "c": "jaune",
                        "item": "",
                        "dir": None,
                        "indent": 0,
                        "icones": [],
                    },
                )
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
            mod_view["ecran"]["diagnostic"] = diagnostic_view(self)
            if mod_view != self.last_mod_view:
                self.bridge.write("vue_mod.json", mod_view)
                self.last_mod_view = mod_view
        except Exception:
            log(traceback.format_exc())

    def check_mod_requests(self):
        if getattr(
            getattr(self, "bridge", None), "isolated", False
        ) is True and not self.bridge.scope_valid(self.bridge.scope):
            self.root.after(300, self.check_mod_requests)
            return
        try:
            ai_settings = getattr(self, "ai_settings", None)
            if ai_settings is not None and ai_settings.poll(self.bridge, self):
                self.refresh(force=True)
            requests = getattr(self, "request_queue", None)
            if requests is not None and requests.poll(
                self.dispatch_queued_request, self.restore_request_reply
            ):
                self.refresh(force=True)
            path = self.bridge.dir / "demande_mod.json"
            if path.exists() and path.stat().st_mtime != self.mod_request_time:
                modified = path.stat().st_mtime
                request = json.loads(path.read_text(encoding="utf-8"))
                self.mod_request_time = modified
                # Celle d'avant le démarrage est ignorée, sauf si elle est toute récente : posée pendant que
                # l'assistant était arrêté, juste avant que le joueur le relance depuis le jeu
                age = time.time() * 1000 - float(request.get("n") or 0)
                recent = -5000 <= age < 45000
                if getattr(
                    getattr(self, "bridge", None), "isolated", False
                ) is True and not self.bridge.accepts(request):
                    self.root.after(300, self.check_mod_requests)
                    return
                legacy_n = getattr(self, "legacy_request_n", None)
                if request.get("n") != legacy_n and recent:
                    self.handle_mod_request(request)
                elif (
                    legacy_n is None
                    and not recent
                    and request.get("type") == "question"
                    and (request.get("n") or 0) >= self.question_ack.get("n", 0)
                ):
                    self.question_ack = {"n": request.get("n"), "etat": "expiree"}
                self.legacy_request_n = request.get("n")
                if isinstance(request.get("n"), (int, float)):
                    self.mod_request_n = max(self.mod_request_n or 0, request["n"])
        except (OSError, ValueError):
            pass  # fichier en cours d'écriture : on relira au prochain passage
        except Exception:
            log(traceback.format_exc())
        self.root.after(300, self.check_mod_requests)

    def restore_request_reply(self, request, result):
        if getattr(
            getattr(self, "bridge", None), "isolated", False
        ) is True and not self.bridge.accepts(request):
            return
        n = request.get("n") or 0
        self.mod_request_n = max(self.mod_request_n or 0, n)
        if request.get("type") == "question" and n < self.question_ack.get("n", 0):
            return
        if request.get("type") == "projet" and n < getattr(self, "project_reply_n", 0):
            return
        if request.get("type") == "projet":
            self.project_reply_n = n
        if "question" in result:
            self.question_ack = result["question"]
        elif request.get("type") == "question":
            self.question_ack = {
                "n": request.get("n"),
                "etat": "expiree" if result.get("etat") == "expiree" else "refusee",
            }
        if "projet" in result:
            self.project_feedback = result["projet"]
        elif request.get("type") == "projet":
            self.project_feedback = {
                "token": request.get("token"),
                "ok": False,
                "message": result.get("message", "Action interrompue."),
            }

    def dispatch_queued_request(self, request):
        if getattr(
            getattr(self, "bridge", None), "isolated", False
        ) is True and not self.bridge.accepts(request):
            return {"etat": "refusee", "message": "Demande d’une autre connexion ou dimension."}
        self.mod_request_n = request.get("n")
        reason = ""
        if request.get("projet") is not None and request["projet"] != self.projects.active:
            reason = "Le projet a changé depuis ce clic ; renouvelle ton action."
        dimension = request.get("dimension_envoi")
        if dimension and dimension != (self.game or {}).get("dimension"):
            reason = "Le monde ou la dimension a changé ; renouvelle ton action."
        if reason:
            result = {"etat": "refusee", "message": reason}
            self.restore_request_reply(request, result)
            return result
        self.handle_mod_request(request)
        result = {"etat": "traitee", "message": "Demande prise en compte."}
        if request.get("type") == "question":
            result["question"] = dict(self.question_ack)
            if self.question_ack.get("etat") != "acceptee":
                result.update(etat="refusee", message="Demande refusée : l’assistant est occupé.")
        elif request.get("type") == "projet":
            self.project_reply_n = request.get("n") or 0
            result["projet"] = dict(self.project_feedback)
            if not self.project_feedback.get("ok"):
                result.update(
                    etat="refusee",
                    message=self.project_feedback.get("message", "Projet non modifié."),
                )
        return result

    def handle_mod_request(self, request):
        if getattr(
            getattr(self, "bridge", None), "isolated", False
        ) is True and not self.bridge.accepts(request):
            return
        kind = request.get("type")
        if request.get("projet") is not None and request["projet"] != self.projects.active:
            return  # action d'un ancien écran : ne pas la reporter sur le nouveau projet
        if kind == "projet":
            self.handle_project_request(request)
        elif kind == "question" and request.get("texte"):
            self.question_ack = {
                "n": request.get("n"),
                "etat": "refusee" if self.busy else "acceptee",
            }
            if self.busy:
                self.notify("Patience, je réfléchis encore…", 4)
            else:
                self.shot = None
                self.start_question(str(request["texte"]), None)
        elif kind == "info" and request.get("item"):
            self.show_info(request)
        elif kind == "reglage":
            self.set_setting(str(request.get("cle")), bool(request.get("valeur")))
        elif kind == "action":
            action = request.get("action")
            if action in ("pause_objectif", "reprendre_objectif"):
                self.set_objective_paused(action == "pause_objectif", request.get("version"))
            elif action == "objectif_suivant":
                self.on_next_objective()
            elif action in ("nouveau_plan", "plan"):
                self.on_new_plan()
            elif action == "refaire_objectif":
                self.on_redo_objective()
            elif action == "valider_etape":
                self.set_step(None, True)
            elif action == "etape" and isinstance(request.get("index"), int) and self.plan:
                states = self.progress()["etapes"]
                index = request["index"]
                if 0 <= index < len(states):
                    self.set_step(
                        index + 1, not states[index]
                    )  # clic sur une étape : la cocher ou la décocher
            elif action == "affichage":
                self.on_hide()
            elif action == "voix":
                self.on_voice()

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

    def on_type(self):
        if self.busy:
            self.notify("Patience, je réfléchis encore…", 4)
            return
        if self.popup:
            return
        self.speaker.stop()
        self.previous_window = user32.GetForegroundWindow() if user32 else None
        self.shot = self.safe_screenshot()
        self.open_popup()

    def on_new(self):
        threading.Thread(target=self.conversation.restart, daemon=True).start()
        self.notify("Nouvelle conversation.", 4)

    def on_hide(self):
        self.speaker.stop()
        if self.game:
            self.set_setting("panneau", not self.visible)
        elif self.overlay and self.overlay.winfo_viewable():
            self.overlay.withdraw()
        elif self.message:
            self.show_overlay(self.message)

    def on_voice(self):
        self.set_setting("voix", not self.speak)
        self.notify("Voix activée." if self.speak else "Voix coupée.", 3)

    def safe_screenshot(self):
        try:
            return take_screenshot()
        except Exception as e:
            log(traceback.format_exc())
            self.notify(f"Impossible de capturer l'écran : {e}", 6)
            return None

    def action_reglage(self, action):
        self.set_setting(str(action.get("cle")), bool(action.get("valeur", True)))

    def action_affichage(self, action):
        self.set_setting("panneau", action.get("visible", True))

    def action_voix(self, action):
        self.set_setting("voix", action.get("active", True))

    def on_error(self, message):
        self.busy = False
        if "panneau de suivi en lecture seule" in message:
            self.connection_problem = message
        self.status = ""
        self.notify(f"Erreur : {message[:300]}", 30)

    def open_popup(self):
        self.popup = popup = tk.Toplevel(self.root, bg=BG, padx=14, pady=10)
        popup.title("Question pour " + ai_provider.label())
        popup.attributes("-topmost", True)
        popup.resizable(False, False)
        tk.Label(
            popup,
            text="Tape ta question puis Entrée. Échap : annuler.",
            bg=BG,
            fg=FG_DIM,
            font=("Segoe UI", 10),
        ).pack(anchor="w")
        self.entry = tk.Entry(
            popup,
            width=64,
            font=("Segoe UI", 12),
            bg="#2b2d31",
            fg=FG,
            insertbackground=FG,
            relief="flat",
        )
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

    def build_overlay(self):
        self.overlay = overlay = tk.Toplevel(self.root, bg=BG)
        overlay.overrideredirect(True)
        overlay.attributes("-topmost", True)
        overlay.attributes("-alpha", 0.92)
        self.overlay_text = tk.Text(
            overlay,
            width=52,
            wrap="word",
            bg=BG,
            fg=FG,
            relief="flat",
            font=("Segoe UI", 12),
            padx=14,
            pady=12,
            cursor="arrow",
        )
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
            self.root.after(
                hide_after, lambda: self.overlay_token == token and self.overlay.withdraw()
            )
