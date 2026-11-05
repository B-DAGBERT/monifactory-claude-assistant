"""Projets, objectifs et progression de l’application."""

import copy
import json
import threading
import time
import traceback

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.game.context import describe_quests
from monifactory_assistant.game.objectives import evaluate, objective_entry
from monifactory_assistant.game.projects import ProjectStore
from monifactory_assistant.prompts import FACTORY_REQUEST, NEW_PLAN_EXTRA, PLAN_REQUEST
from monifactory_assistant.providers.responses import parse_plan, readable
from monifactory_assistant.providers.session import ask_claude, tool_status
from monifactory_assistant.runtime import log
from monifactory_assistant.settings import HOTKEY_PLAN, PLAN_FILE, USEFUL


class ProjectsController:
    """Projets, pause, progression et préparation de leurs objectifs."""

    def load_plan(self):
        self.projects = ProjectStore(PLAN_FILE)
        self.project_feedback = {}
        self.restore_project(self.projects.snapshot())

    def restore_project(self, saved):
        self.plan = saved.get("plan")
        self.objective_paused = bool(self.plan and saved.get("objectif_en_pause", False))
        self.objective_epoch = (
            getattr(self, "objective_epoch", -1) + 1
        )  # invalide les anciennes actions programmées
        self.plan_version = saved.get("version", 0)
        self.reached = set(saved.get("atteints", []))
        self.done_steps = set(saved.get("etapes_faites", []))
        self.undone_steps = set(saved.get("etapes_decochees", []))
        self.obtained = saved.get("obtenus", {})  # cible -> combien il en a obtenu depuis le début
        self.last_totals = saved.get(
            "derniers_totaux", {}
        )  # cible -> sur lui + coffres + posé, au dernier passage
        main = saved.get("plan_principal")
        if main:
            self.plan_title, self.plan_why = main.get("titre", ""), main.get("pourquoi", "")
            self.objectives = [objective_entry(o) for o in main.get("objectifs", [])]
        else:  # ancienne « feuille de route » : les objectifs qui suivaient l'objectif affiché
            self.plan_title, self.plan_why = "", ""
            self.objectives = [objective_entry(o) for o in saved.get("feuille_de_route", [])]
            if self.plan:
                self.objectives.insert(
                    0, objective_entry({"titre": self.plan["objectif"]}, "en_cours")
                )
        self.plan_done_announced = (
            True  # pas d'enchaînement automatique sur un ancien objectif au démarrage
        )

    def project_snapshot(self):
        return {
            "objectif_en_pause": self.objective_paused,
            "plan": self.plan,
            "version": self.plan_version,
            "atteints": sorted(self.reached),
            "etapes_faites": sorted(self.done_steps),
            "etapes_decochees": sorted(self.undone_steps),
            "obtenus": self.obtained,
            "derniers_totaux": self.last_totals,
            "plan_principal": {
                "titre": self.plan_title,
                "pourquoi": self.plan_why,
                "objectifs": self.objectives,
            },
        }

    def save_plan(self):
        self.projects.save(self.project_snapshot())

    def project_view(self):
        return {
            "actif": self.projects.active,
            "nom": self.projects.current()["nom"],
            "liste": self.projects.catalog(self.project_snapshot()),
            "retour": self.project_feedback,
        }

    def handle_project_request(self, request):
        token = str(request.get("token") or "")
        if token and token == self.project_feedback.get("token"):
            self.refresh(force=True)
            return
        previous_active = self.projects.active
        previous_projects = copy.deepcopy(self.projects.projects)
        previous_state = self.project_snapshot()
        try:
            if self.busy:
                raise ValueError("Attends la fin de la réponse avant de changer de projet.")
            if request.get("origine") != self.projects.active:
                raise ValueError("Le projet actif a changé. Rouvre le sélecteur.")
            action = request.get("action")
            ident = str(request.get("id") or "")
            if action not in ("choisir", "creer", "renommer", "supprimer"):
                raise ValueError("Action de projet inconnue.")
            name = str(request.get("nom") or "").strip()[:100]
            mode = request.get("mode")
            if action == "creer" and (mode not in ("objectif", "plan") or not self.game):
                raise ValueError("Entre dans ta partie pour préparer un projet.")
            if action in ("creer", "renommer") and not name:
                raise ValueError("Donne un nom au projet.")
            if action != "creer":
                self.projects.find(ident)
            if action == "creer" and (not ident or len(ident) > 80):
                raise ValueError("Identifiant de projet invalide.")
            self.save_plan()  # sauvegarder entièrement celui qu'on quitte, y compris sa pause
            if action == "renommer":
                self.projects.find(ident)["nom"] = name
            elif action == "supprimer":
                self.projects.delete(ident, request.get("confirmation"))
            elif action == "creer":
                if not self.projects.create(ident, name):
                    raise ValueError("Ce projet a déjà été créé.")
            else:
                self.projects.select(ident)
            if self.projects.active != previous_active:
                self.restore_project(self.projects.snapshot())
                self.plan_version = time.time_ns() // 1000
                # Aucun craft de l'autre projet ne doit être attribué rétroactivement à celui-ci.
                self.last_totals = self.target_totals()
                self.coach_queue = []
                self.guide = None
                self.last_progress = time.time()
            self.save_plan()
        except (ValueError, OSError) as error:
            self.projects.active = previous_active
            self.projects.projects = previous_projects
            # Ne toucher à l'objectif que si une bascule a réellement commencé.
            if self.project_snapshot() != previous_state:
                self.restore_project(previous_state)
            self.project_feedback = {"token": token, "ok": False, "message": str(error)}
            self.refresh(force=True)
            return
        self.project_feedback = {"token": token, "ok": True, "action": action}
        self.refresh(force=True)
        if action == "creer":
            self.start_objective(name, new_plan=mode == "plan")
        else:
            self.notify(
                "Projet : "
                + self.projects.current()["nom"]
                + (" · objectif en pause" if self.objective_paused else ""),
                5,
            )

    def schedule_project_action(self, callback):
        epoch = self.objective_epoch
        self.root.after(300, lambda: callback() if epoch == self.objective_epoch else None)

    def set_objective_paused(self, paused, version=None):
        """Commande explicite et idempotente : un double clic ne reprend jamais l'objectif."""
        if not self.plan or (version is not None and str(version) != str(self.plan_version)):
            return
        if self.objective_paused == paused:
            return
        if self.busy:
            self.notify("Attends la fin de la réponse, puis réessaie.", 4)
            return
        self.objective_paused = paused
        self.objective_epoch += 1
        self.last_progress = time.time()
        if not paused:
            self.plan_done_announced = False
        self.coach_queue = [
            (text, speak, kind) for text, speak, kind in self.coach_queue if kind != "progression"
        ]
        self.save_plan()
        self.notify(
            "Objectif en pause. Tes étapes sont conservées."
            if paused
            else "Objectif repris. On continue avec ta progression actuelle.",
            6,
        )
        self.refresh(force=True)

    def auto_next_objective(self, epoch):
        if epoch == self.objective_epoch and not self.objective_paused:
            self.on_next_objective(auto=True)

    def current_objective(self):
        """L'index de l'objectif du plan en cours, ou None."""
        return next((i for i, o in enumerate(self.objectives) if o["etat"] == "en_cours"), None)

    def reopen_objective(self):
        """L'objectif annoncé fini ne l'est plus (étape décochée) : annuler l'enchaînement prévu
        et le remettre en cours dans le plan."""
        self.objective_epoch += 1
        version, index = getattr(self, "finished_objective", (None, None))
        if (
            version == self.plan_version
            and index is not None
            and index < len(self.objectives)
            and self.objectives[index]["etat"] == "fait"
            and self.current_objective() is None
        ):
            self.objectives[index]["etat"] = "en_cours"

    def have(self):
        return {k: int(v) for k, v in (self.game or {}).get("inventaire", {}).items()}

    def progress(self):
        if not self.plan:
            return None
        return evaluate(
            self.plan,
            self.have(),
            self.reached,
            self.done_steps,
            stored=self.storage.totals(),
            placed=self.storage.placed(),
            undone=self.undone_steps,
            obtained=self.obtained,
        )

    def target_totals(self):
        """Combien il a de chaque cible de l'objectif : sur lui, dans ses coffres et posé."""
        have, stored, placed = self.have(), self.storage.totals(), self.storage.placed()
        return (
            {
                t["item"]: have.get(t["item"], 0)
                + stored.get(t["item"], 0)
                + placed.get(t["item"], 0)
                for t in self.plan["cibles"]
            }
            if self.plan
            else {}
        )

    def track_targets(self):
        """Conserver les acquis sans additionner chaque retour du même objet dans les stocks."""
        changed = False
        for item, total in self.target_totals().items():
            before = self.last_totals.get(item)
            acquired = max(self.obtained.get(item, 0), total)
            if self.obtained.get(item, 0) != acquired:
                self.obtained[item] = acquired
                changed = True
            if before != total:
                self.last_totals[item] = total
                changed = True
        bridge = getattr(self, "bridge", None)
        proof = bridge.read("fabrication_emi.json") if bridge is not None else None
        if (
            isinstance(proof, dict)
            and not self.objective_paused
            and str(proof.get("projet", "")) == self.projects.active
            and str(proof.get("version", "")) == str(self.plan_version)
            and isinstance(proof.get("obtenus"), dict)
        ):
            for target in (self.plan or {}).get("cibles", []):
                item = target["item"]
                count = proof["obtenus"].get(item)
                if isinstance(count, int) and not isinstance(count, bool) and count >= 0:
                    count = min(count, target["quantite"] + target.get("deja", 0))
                    if count > self.obtained.get(item, 0):
                        self.obtained[item] = count
                        changed = True
        return changed

    def update_objective_progress(self):
        if self.plan:
            tracked = self.track_targets()
            if self.objective_paused:
                if tracked:
                    self.save_plan()  # les crafts restent comptés, même utilisés avant la reprise
                return
            progress = self.progress()
            new_steps = {i for i, done in enumerate(progress["etapes"]) if done} - self.done_steps
            if progress["atteints"] != self.reached or new_steps or tracked:
                self.reached = progress["atteints"]
                self.done_steps |= new_steps
                self.save_plan()
                self.last_progress = time.time()
            if new_steps and not progress["termine"]:
                steps = self.plan["etapes"]
                for i in sorted(new_steps):
                    self.journal("étape", steps[i]["texte"])
                upcoming = next(
                    (s["texte"] for i, s in enumerate(steps) if i not in self.done_steps), None
                )
                self.coach(
                    f"Étape faite : {steps[max(new_steps)]['texte']}."
                    + (f" Ensuite : {upcoming}." if upcoming else ""),
                    USEFUL,
                    kind="progression",
                )
            if progress["termine"] and not self.plan_done_announced:
                self.plan_done_announced = True
                self.journal("objectif", f"Objectif atteint : {self.plan['objectif']}")
                current = self.current_objective()
                if current is not None:
                    self.objectives[current]["etat"] = "fait"
                    self.finished_objective = (self.plan_version, current)
                    self.save_plan()
                remaining = any(o["etat"] == "a_faire" for o in self.objectives)
                if remaining:
                    done = "Objectif terminé !"
                else:
                    done = (
                        f"Plan « {self.plan_title} » terminé !"
                        if self.plan_title
                        else "Objectif terminé !"
                    )
                    self.journal("plan", done)
                auto = self.settings["enchainer"]
                follow = (
                    " Je prépare la suite."
                    if auto
                    else f" Objectif suivant (écran Y ou {HOTKEY_PLAN.upper()}) pour continuer."
                    if remaining
                    else " Nouveau plan (écran Y) pour la suite."
                )
                self.notify(done + follow, 30, speak=True)
                if auto:
                    epoch = self.objective_epoch
                    self.root.after(6000, lambda: self.auto_next_objective(epoch))

    def on_plan(self):  # touche F7
        self.on_next_objective()

    def on_next_objective(self, auto=False):
        """Objectif suivant : le prochain objectif du plan, détaillé par Codex (celui en cours est alors marqué
        fait). Sans plan, ou à la fin du plan, un nouveau plan."""
        if self.objective_paused:
            if not auto:
                self.notify(
                    "Objectif en pause : clique sur Reprendre avant de passer à la suite.", 5
                )
            return
        following = next((i for i, o in enumerate(self.objectives) if o["etat"] == "a_faire"), None)
        if following is None:
            self.on_new_plan(auto=auto)
        else:
            self.start_objective(self.objectives[following]["demande"], index=following, auto=auto)

    def on_redo_objective(self):
        """Refait le détail de l'objectif en cours, à la même place dans le plan (étapes plus précises, recettes et
        machines revérifiées)."""
        current = self.current_objective()
        if current is not None:
            self.start_objective(self.objectives[current]["demande"], index=current)
        elif self.plan:
            self.start_objective(self.plan["objectif"])
        else:
            self.on_next_objective()

    def on_new_plan(self, request="", auto=False):
        """Nouveau plan : Codex choisit l'objectif principal (ou celui que le joueur demande), le découpe en
        objectifs et détaille le premier."""
        self.start_objective(request, new_plan=True, auto=auto)

    def start_objective(self, request="", index=None, new_plan=False, auto=False):
        """Fait détailler un objectif par Codex. index : sa place dans le plan ; sans index ni nouveau plan, c'est
        un objectif demandé à la voix, qui passe avant celui en cours (repris juste après)."""
        if self.objective_paused:
            if not auto:
                self.notify("Objectif en pause : clique sur Reprendre avant de le remplacer.", 5)
            return
        if self.busy:
            if not auto:
                self.notify("Patience, je réfléchis encore…", 4)
            return
        if not self.game:
            if not auto:
                self.show_overlay(
                    "Le jeu n'est pas connecté : entre dans ton monde (le script KubeJS doit être "
                    "chargé, voir assistant.log).",
                    hide_after=10000,
                )
            return
        if not auto:
            self.speaker.stop()
        self.busy = True
        self.objective_epoch += 1
        self.plan_done_announced = True
        self.set_status(
            f"{ai_provider.label()} prépare ton nouveau plan…"
            if new_plan
            else f"{ai_provider.label()} prépare ton objectif…"
        )
        shot = None if auto or request else self.safe_screenshot()
        threading.Thread(
            target=self.work_plan, args=(shot, request, index, new_plan), daemon=True
        ).start()

    def work_plan(self, shot, request="", index=None, new_plan=False):
        try:
            context = (
                self.context(shot, with_storage=True)
                + "\n"
                + describe_quests(self.bridge.read("quetes.json"))
            )
            prompt = PLAN_REQUEST.format(context=context) + (NEW_PLAN_EXTRA if new_plan else "")
            if request:
                what = "le plan" if new_plan else "l'objectif"
                prompt = (
                    f"Le joueur demande précisément : « {request} ». Construis {what} pour répondre à cette "
                    f"demande (par exemple la quête qu'il cite), même si ce n'est pas la plus urgente.\n"
                    + prompt
                )
            answer = ask_claude(
                prompt,
                self.bridge.dir,
                ai_provider.model("plan"),
                effort=ai_provider.effort("plan", "medium"),
                on_tool=lambda name, data: self.events.put(("status", tool_status(name, data))),
            )
            self.events.put(("plan_ready", parse_plan(answer), request, index, new_plan))
        except Exception as e:
            log(traceback.format_exc())
            self.events.put(("error", str(e)))

    def on_plan_ready(self, plan, request="", index=None, new_plan=False):
        self.busy = False
        current = self.current_objective()
        if new_plan and plan.get("plan"):
            main = plan["plan"]
            self.plan_title = main["titre"] or plan["objectif"]
            self.plan_why = main["pourquoi"]
            self.objectives = main["objectifs"]
            self.objectives[0]["etat"] = "en_cours"
            self.journal("plan", f"Nouveau plan : {self.plan_title}")
        elif index is not None and index < len(self.objectives):  # l'objectif suivant du plan
            if current is not None and current != index:
                self.objectives[current]["etat"] = "fait"
            self.objectives[index]["etat"] = "en_cours"
        else:  # objectif demandé à la voix : il passe avant celui en cours, qui reprendra juste après
            entry = objective_entry(
                {"titre": plan["objectif"], "demande": request or plan["objectif"]}, "en_cours"
            )
            if current is None:
                self.objectives.insert(
                    next(
                        (i for i, o in enumerate(self.objectives) if o["etat"] == "a_faire"),
                        len(self.objectives),
                    ),
                    entry,
                )
            else:
                self.objectives[current]["etat"] = "a_faire"
                self.objectives.insert(current, entry)
        self.objective_paused = False
        self.objective_epoch += 1
        self.plan, self.plan_version = plan, time.time_ns() // 1000
        self.reached, self.done_steps, self.undone_steps = set(), set(), set()
        # Ce qu'il a au départ est le point de départ de ce qu'il obtiendra ensuite. Une cible qu'il a déjà sur lui ou
        # posée (un Solid Boiler quand deux tournent) : il en veut une de plus, pas la valider tout de suite. Ce qui
        # dort dans ses coffres, en revanche, compte comme déjà là (des casings en réserve pour un multibloc).
        self.last_totals = self.target_totals()
        self.obtained = dict(self.last_totals)
        have, placed = self.have(), self.storage.placed()
        for target in plan["cibles"]:
            on_him = have.get(target["item"], 0)
            already = on_him + placed.get(target["item"], 0)
            if already >= target["quantite"]:
                target["deja"] = already
                # Le mod et EMI ne voient que l'inventaire : seule cette part s'ajoute à l'arbre EMI
                target["deja_inventaire"] = on_him
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

    def action_nouveau_plan(self, action):
        self.schedule_project_action(
            lambda: self.on_new_plan(request=str(action.get("demande") or ""))
        )

    def action_nouvel_objectif(self, action):
        self.schedule_project_action(lambda: self.start_objective(str(action.get("demande") or "")))

    def action_pause_objectif(self, _action):
        self.set_objective_paused(True)

    def action_reprendre_objectif(self, _action):
        self.set_objective_paused(False)

    def action_effacer_objectif(self, _action):
        self.objective_paused = False
        self.objective_epoch += 1
        current = self.current_objective()
        if current is not None:
            self.objectives[current]["etat"] = "a_faire"
        self.plan, self.reached, self.done_steps, self.undone_steps = None, set(), set(), set()
        self.save_plan()

    def action_effacer_plan(self, action):
        self.action_effacer_objectif(action)
        self.objectives, self.plan_title, self.plan_why = [], "", ""
        self.save_plan()

    def action_modifier_plan(self, action):
        """Remplace les objectifs qui restent à faire (ceux faits et celui en cours ne bougent pas)."""
        kept = [o for o in self.objectives if o["etat"] != "a_faire"]
        self.objectives = kept + [
            objective_entry(o) for o in action.get("objectifs") or [] if isinstance(o, dict)
        ]
        if action.get("titre"):
            self.plan_title = str(action["titre"])
        self.save_plan()
        if (
            not self.plan and self.objectives
        ):  # aucun objectif en cours : on attaque le premier tout de suite
            self.schedule_project_action(self.on_next_objective)

    action_feuille_de_route = action_modifier_plan  # ancien nom

    def action_ajouter_objectif(self, action):
        entry = objective_entry(action, "a_faire")
        if any(
            o["titre"].lower() == entry["titre"].lower() and o["etat"] != "fait"
            for o in self.objectives
        ):
            return  # déjà dans le plan
        position = action.get("position")  # 1 = juste après l'objectif en cours
        start = next((i + 1 for i, o in enumerate(self.objectives) if o["etat"] == "en_cours"), 0)
        start = max(start, sum(1 for o in self.objectives if o["etat"] == "fait"))
        index = (
            len(self.objectives)
            if not isinstance(position, int)
            else min(start + max(0, position - 1), len(self.objectives))
        )
        self.objectives.insert(index, entry)
        self.save_plan()

    def action_retirer_objectif(self, action):
        title = str(action.get("titre", "")).lower()
        self.objectives = [
            o for o in self.objectives if o["etat"] != "a_faire" or title not in o["titre"].lower()
        ]
        self.save_plan()

    def action_objectif_suivant(self, _action):
        self.schedule_project_action(self.on_next_objective)

    def action_refaire_objectif(self, _action):
        self.schedule_project_action(self.on_redo_objective)

    def action_valider_etape(self, action):
        self.set_step(action.get("numero"), True)

    def action_decocher_etape(self, action):
        self.set_step(action.get("numero"), False)

    def set_step(self, number=None, done=True):
        """Coche ou décoche une étape de l'objectif à la main (number : 1, 2... ; sans numéro, l'étape en cours)."""
        if not self.plan:
            return
        states = self.progress()["etapes"]
        if isinstance(number, int) and 1 <= number <= len(states):
            index = number - 1
        else:
            index = (
                next((i for i, d in enumerate(states) if not d), None)
                if done
                else max((i for i, d in enumerate(states) if d), default=None)
            )
        if index is None:
            return
        finished = self.progress()["termine"]
        if done:
            self.done_steps.add(index)
            self.undone_steps.discard(index)
        else:
            self.done_steps.discard(index)
            self.undone_steps.add(index)
            # Fini par ses cibles, il le reste : ne pas l'annoncer ni l'enchaîner une seconde fois
            if not self.progress()["termine"]:
                self.plan_done_announced = False
                if finished:
                    self.reopen_objective()
        self.last_progress = time.time()
        self.save_plan()
        self.refresh(force=True)

    def action_planifier_usine(self, action):
        request = FACTORY_REQUEST.format(demande=str(action.get("demande") or "une usine"))
        self.schedule_project_action(lambda: self.start_objective(request))
