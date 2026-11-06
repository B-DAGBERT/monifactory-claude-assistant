"""Explicit native V2 requests; bounded background work and inert cached UI/AI results."""

import copy
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from monifactory_assistant.game.world_scope import scope_file
from monifactory_assistant.production.acquisition import (
    acquire,
    active_scope,
    capture_stamp,
    read_capture,
)
from monifactory_assistant.production.analysis import analyse_observed
from monifactory_assistant.production.catalog import CatalogueReader
from monifactory_assistant.production.knowledge import Freshness, KnowledgeStatus
from monifactory_assistant.production.memory import read_projects
from monifactory_assistant.production.recipes import Amount, Resource

_CONTEXT = ("monde_actif", "session_monde", "dimension_monde", "projet", "revision_projet")
_RESOURCE = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
_MAX_AGE = 45_000


def _project_context(app):
    project = getattr(getattr(app, "projects", None), "active", "") or ""
    revision = f"{getattr(app, 'plan_version', 0)}:{getattr(app, 'objective_epoch', 0)}:{int(bool(getattr(app, 'objective_paused', False)))}"
    return project, revision


def _safe(text, limit=300):
    return " ".join(str(text).split())[:limit]


def _amount(amount):
    return f"{amount.quantity} {amount.resource.unit} {amount.resource.id}"


def _proof_text(proof):
    source = {
        "monde.json": "relevé serveur",
        "inventaire_joueur.json": "inventaire natif",
        "stockage.json": "coffres mémorisés",
        "monde_memoire.json": "mémoire du monde",
    }.get(proof.source, "relevé local")
    stamp = proof.received_at_ms or proof.observed_at_ms
    date = (
        time.strftime("%H:%M:%S", time.localtime(stamp / 1000))
        if stamp is not None and 0 <= stamp < 4_000_000_000_000
        else "date inconnue"
    )
    return f"{source} · {proof.freshness.value} · {date} · couverture {proof.completeness.value}"


def _diagnostic_summary(finding):
    states = {
        "machine_working": "Machine observée en marche ; recette exécutée non identifiée.",
        "machine_waiting": "Machine observée en attente ; sa cause libre doit être lue dans le jeu.",
        "machine_idle": "Machine observée à l'arrêt.",
        "machine_suspend": "Machine observée en pause.",
        "machine_historical": "Machine seulement connue historiquement.",
        "machine_status_unknown": "État de la machine non observé.",
        "machine_not_observed": "Aucune machine compatible n'est attestée par les relevés fournis.",
        "machine_not_in_authorized_survey": "Machine absente du relevé autorisé ; disparition physique non prouvée.",
    }
    static = {
        "multiblock_unformed",
        "boiler_dry",
        "eau_empty",
        "vap_empty",
        "machine_compatible",
        "machine_compatibility_unknown",
        "unobserved_connections",
    }
    text = states.get(
        finding.code,
        finding.message
        if finding.code in static
        else "Vérification requise, voir le diagnostic local.",
    )
    sources = "; ".join(_proof_text(proof) for proof in finding.evidence[:2])
    return f"Diagnostic {finding.code} ; certitude {finding.certainty} : {text}" + (
        " Preuve : " + sources if sources else " Preuve courante absente."
    )


def _bounded_report(plan):
    report = plan.to_dict()
    truncated = {}
    for key, limit in (
        ("steps", 64),
        ("allocations", 64),
        ("missing", 128),
        ("waiting", 64),
        ("issues", 64),
        ("surplus", 64),
    ):
        if len(report[key]) > limit:
            truncated[key] = len(report[key])
            report[key] = report[key][:limit]
    if truncated:
        report["partial_view"] = truncated
    return report


class ProductionService:
    def __init__(self, bridge, python_root, *, clock=time.time, executor=None):
        self.bridge, self.python_root, self.clock = bridge, Path(python_root), clock
        self.executor = executor or ThreadPoolExecutor(
            max_workers=1, thread_name_prefix="production-read"
        )
        self._lock = threading.RLock()
        self._future = None
        self._generation = 0
        self._context = {}
        self._scope = None
        self._target = {"item": "", "quantite": 1, "kind": "item", "unit": "item", "variant": ""}
        self._result = None
        self._reader = None
        self._reader_source = None
        self._ai_context = ""
        self._finished = []
        self._seen = {}
        self._updated = 0
        self._app = None
        self._marker_cache = (None, None)

    def poll(self, app):
        """Small main-loop maintenance only; no catalogue, stock or planning read here."""
        scope = active_scope(self.bridge, int(self.clock() * 1000))
        project, revision = _project_context(app)
        context = (
            {**scope.fields(), "projet": project, "revision_projet": revision}
            if scope is not None
            else {}
        )
        target = self._target
        plan = getattr(app, "plan", None)
        if isinstance(plan, dict):
            candidates = plan.get("cibles") or ()
            if (
                isinstance(candidates, (tuple, list))
                and candidates
                and isinstance(candidates[0], dict)
            ):
                item, quantity = candidates[0].get("item"), candidates[0].get("quantite", 1)
                if (
                    isinstance(item, str)
                    and _RESOURCE.fullmatch(item)
                    and type(quantity) is int
                    and 0 < quantity <= 1_000_000
                ):
                    target = {
                        "item": item,
                        "quantite": quantity,
                        "kind": "item",
                        "unit": "item",
                        "variant": "",
                    }
        changed = False
        with self._lock:
            self._app = app
            self._updated = self.clock()
            if context != self._context:
                self._generation += 1
                self._context, self._scope = context, scope
                self._result, self._ai_context = None, ""
                self._target = target
                changed = True
            while self._finished:
                generation, result, summary = self._finished.pop(0)
                if (
                    generation == self._generation
                    and self._matches(result)
                    and (self._result is None or result.get("n", -1) >= self._result.get("n", -1))
                ):
                    self._result, self._ai_context = result, summary
                    changed = True
            if (
                self._result
                and self._result.get("etat") == "pret"
                and not self._catalogue_matches(self._result.get("catalogue"), scope)
            ):
                self._result = {
                    "n": self._result["n"],
                    "etat": "erreur",
                    "titre": "Arbre EMI modifié",
                    "lignes": ["L'arbre ou son catalogue a changé. Relance la consultation."],
                    "limites": [],
                    "alternatives": [],
                    **self._context,
                }
                self._ai_context = ""
                changed = True
        return changed

    def _matches(self, request):
        return bool(self._context) and all(
            request.get(key) == self._context.get(key) for key in _CONTEXT
        )

    def view(self):
        with self._lock:
            available = bool(self._context) and 0 <= self.clock() - self._updated < 4
            return copy.deepcopy(
                {
                    "v": 1,
                    "disponible": available,
                    "message": "Lecture seule : aucun transfert ni modification d'objectif."
                    if available
                    else "Entre dans un monde identifié pour consulter la production.",
                    "contexte": self._context if available else {},
                    "cible": self._target,
                    "resultat": self._result if available else None,
                }
            )

    def context(self, question=None):
        """Only a previously requested result, never a new AI-triggered computation."""
        with self._lock:
            if (
                not isinstance(question, str)
                or re.search(
                    r"\b(v2|production|diagnostic|ce plan|cette machine|cette recette)\b",
                    question.casefold(),
                )
                is None
            ):
                return None
            if (
                not self._context
                or self.clock() - self._updated >= 4
                or not self._result
                or self._result.get("etat") != "pret"
            ):
                return None
            age = max(0, int(self.clock() * 1000 - self._result.get("t", 0)))
            proof = {
                **self._context,
                "generation_service": self._generation,
                "n": self._result["n"],
            }
            if not self.context_valid(proof):
                return None
            text = (
                f"Résultat local V2 en lecture seule, calculé il y a {age // 1000} s. Les faits sont datés, pas une autorisation d'action.\n"
                + self._ai_context[:5000]
            )
            return text, proof

    def context_valid(self, proof):
        with self._lock:
            if (
                not isinstance(proof, dict)
                or not self._matches(proof)
                or proof.get("generation_service") != self._generation
                or not self._result
                or self._result.get("etat") != "pret"
                or proof.get("n") != self._result.get("n")
            ):
                return False
            if self._app is None or _project_context(self._app) != (
                self._context["projet"],
                self._context["revision_projet"],
            ):
                return False
            return active_scope(
                self.bridge, int(self.clock() * 1000)
            ) == self._scope and self._catalogue_matches(self._result.get("catalogue"), self._scope)

    def _catalogue_matches(self, catalogue, scope):
        if catalogue is None:
            return True  # Project-memory reads do not depend on an EMI tree.
        try:
            path = scope_file(self.bridge.dir, "catalogue_production_etat.json")
            stamp = capture_stamp(path)
            cached_stamp, data = self._marker_cache
            if stamp is None:
                return False
            if stamp != cached_stamp:
                data, stamp = read_capture(path, 1024 * 1024)
                self._marker_cache = (stamp, data)
            return (
                scope is not None
                and scope.accepts(data)
                and data.get("etat") == "pret"
                and data.get("portee") == "arbre_emi"
                and all(
                    data.get(key) == catalogue.get(key)
                    for key in ("source", "generation", "sha256")
                )
            )
        except (OSError, ValueError, TypeError, AttributeError, RecursionError):
            return False

    def submit(self, request):
        """Called only by native dispatch, after the durable queue has reserved this click."""
        now = int(self.clock() * 1000)
        with self._lock:
            number = request.get("n")
            if (
                type(number) is not int
                or not 0 <= number <= 2**53 - 1
                or not -5000 <= now - number <= _MAX_AGE
            ):
                return {"etat": "refusee", "message": "Demande de production expirée."}
            if (
                not self._matches(request)
                or active_scope(self.bridge, now) != self._scope
                or self._app is None
                or _project_context(self._app)
                != (self._context["projet"], self._context["revision_projet"])
            ):
                return {
                    "etat": "refusee",
                    "message": "Monde, connexion ou projet modifié ; renouvelle le clic.",
                }
            if number in self._seen:
                return self._seen[number]
            if self._future is not None and not self._future.done():
                return {"etat": "refusee", "message": "Un calcul de production est déjà en cours."}
            try:
                operation = request.get("operation")
                if operation not in {"plan", "diagnostic", "memoire"}:
                    raise ValueError("Consultation inconnue.")
                target = None
                if operation != "memoire":
                    kind = request.get("kind", "item")
                    resource = Resource(
                        kind,
                        request.get("item"),
                        request.get("unit", "mB" if kind == "fluid" else "item"),
                        request.get("variant", ""),
                    )
                    quantity = request.get("quantite")
                    if (
                        type(quantity) is not int
                        or not 0 < quantity <= 1_000_000
                        or len(resource.variant) > 8192
                    ):
                        raise ValueError("Quantité ou variante hors limite.")
                    target = Amount(resource, quantity)
                if request.get("recette") is not None and (
                    not isinstance(request["recette"], str)
                    or not _RESOURCE.fullmatch(request["recette"])
                ):
                    raise ValueError("Identifiant de recette invalide.")
                if request.get("machine_key") is not None and (
                    not isinstance(request["machine_key"], str) or len(request["machine_key"]) > 180
                ):
                    raise ValueError("Position de machine invalide.")
            except (ValueError, TypeError) as error:
                return {"etat": "refusee", "message": _safe(error)}
            self._result = {
                "n": number,
                "etat": "calcul",
                "titre": "Lecture et calcul…",
                "lignes": [],
                "limites": [],
                "alternatives": [],
                **self._context,
            }
            self._ai_context = ""
            generation, scope = self._generation, self._scope
            frozen = copy.deepcopy(request)
            self._future = self.executor.submit(self._work, frozen, target, scope, generation)
            receipt = {
                "etat": "traitee",
                "message": "Consultation lancée.",
                "production_v2": {"n": number, **self._context},
            }
            self._seen[number] = receipt
            self._seen = dict(list(self._seen.items())[-64:])
            return receipt

    def restore(self, request, receipt):
        """Replayed receipts never relaunch a job after a process restart."""
        with self._lock:
            if (
                not self._matches(request)
                or self._result
                and self._result.get("n", -1) >= request.get("n", -1)
            ):
                return
            message = (
                receipt.get("message")
                if receipt.get("etat") != "traitee"
                else "Résultat précédent non conservé. Relance la consultation."
            )
            self._result = {
                "n": request.get("n"),
                "etat": "erreur",
                "titre": "Consultation à renouveler",
                "lignes": [_safe(message or "Relance la consultation.")],
                "limites": [],
                "alternatives": [],
                **self._context,
            }

    def _catalogue(self, scope):
        path = self.bridge.dir / "catalogue_production_etat.json"
        state, stamp = read_capture(path, 1024 * 1024)
        if (
            type(state.get("v")) is not int
            or state["v"] != 1
            or state.get("etat") != "pret"
            or state.get("portee") != "arbre_emi"
            or not scope.accepts(state)
        ):
            raise ValueError("Sélectionne un arbre EMI et publie son catalogue depuis Production.")
        source = state.get("source")
        if not isinstance(source, str) or not source.startswith("emi/") or len(source) > 512:
            raise ValueError("Source EMI non identifiée.")
        if self._reader_source != source:
            self._reader = CatalogueReader(self.bridge.dir / "catalogue_production.json", source)
            self._reader_source = source
        catalogue = self._reader.refresh()
        if (
            catalogue.status != "ready"
            or catalogue.catalog is None
            or catalogue.sha256 != state.get("sha256")
            or catalogue.catalog.generation != state.get("generation")
        ):
            raise ValueError("Catalogue EMI incomplet ou remplacé ; relance sa publication.")
        checked, after = read_capture(path, 1024 * 1024)
        if after != stamp or checked != state:
            raise ValueError("L'arbre EMI a changé pendant la lecture.")
        return catalogue, state

    def _work(self, request, target, scope, generation):
        result = {
            "n": request["n"],
            **{key: request[key] for key in _CONTEXT},
            "alternatives": [],
            "limites": [],
        }
        summary = ""
        try:
            if active_scope(self.bridge) != scope:
                raise ValueError("Connexion modifiée avant le calcul.")
            if request["operation"] == "memoire":
                isolated = getattr(self.bridge.routing, "mode", None) == "isolated"
                projects = read_projects(
                    self.python_root, self.bridge.dir, scope if isolated else None
                )
                project = next(
                    (row for row in projects if row.ref.project == request["projet"]), None
                )
                if project is None:
                    raise ValueError("Aucun projet sauvegardé correspondant.")
                lines = [
                    "Projet en pause" if project.paused else "Projet actif",
                    _safe(project.objective),
                ]
                lines.extend(
                    ("Fait : " if step.completed else "À faire : ") + _safe(step.text)
                    for step in project.steps[:100]
                )
                result.update(
                    titre="Mémoire du projet",
                    lignes=lines,
                    limites=["Lecture du projet sauvegardé ; progression inchangée."],
                )
                summary = "Mémoire du projet consultée sans modifier sa progression."
            else:
                catalogue, metadata = self._catalogue(scope)
                if any(
                    request.get("catalogue_" + key) != metadata.get(key)
                    for key in ("source", "generation", "sha256")
                ):
                    raise ValueError(
                        "Le catalogue EMI ne correspond plus au clic ; renouvelle la consultation."
                    )
                root_target = Amount.from_dict(metadata.get("cible"))
                if root_target.resource != target.resource:
                    raise ValueError("Le produit ne correspond pas à l'arbre EMI sélectionné.")
                choices, ingredients = {}, {}
                for entry in metadata.get("choix_recettes", []):
                    resource = Resource.from_dict(entry["resource"])
                    if resource in choices and choices[resource] != entry["recipe"]:
                        raise ValueError("Branches EMI contradictoires pour la même ressource.")
                    choices[resource] = entry["recipe"]
                for entry in metadata.get("choix_ingredients", []):
                    key = f"{entry['recipe']}#{entry['index']}"
                    resource = Resource.from_dict(entry["resource"])
                    if key in ingredients and ingredients[key] != resource:
                        raise ValueError("Alternatives EMI contradictoires.")
                    ingredients[key] = resource
                selected = request.get("recette") or metadata.get("recette_racine")
                if selected:
                    choices[target.resource] = selected
                snapshot = acquire(
                    self.bridge, self.python_root, scope, request, int(self.clock() * 1000)
                )
                # Only the native click's tag-free inventory proves an exact variant-empty item.
                if not any(
                    any(
                        proof.reference == "production_v2_inventaire.json"
                        and proof.freshness == Freshness.LIVE
                        and proof.status == KnowledgeStatus.OBSERVED
                        for proof in row.evidence
                    )
                    for row in snapshot.select("inventory")
                ):
                    snapshot = replace_stock_unknown(snapshot)
                eligible = {
                    amount.resource
                    for recipe in catalogue.catalog.recipes
                    for ingredient in recipe.inputs
                    for amount in ingredient.options
                    if amount.resource.kind == "item" and not amount.resource.variant
                }
                eligible.update(
                    output.amount.resource
                    for recipe in catalogue.catalog.recipes
                    for output in recipe.outputs
                    if output.amount.resource.kind == "item" and not output.amount.resource.variant
                )
                analysis = analyse_observed(
                    catalogue,
                    snapshot,
                    target,
                    id_countable=eligible,
                    recipe_choices=choices,
                    ingredient_choices=ingredients,
                    diagnose_recipe=selected,
                    machine_key=request.get("machine_key"),
                )
                lines = [
                    "Produit : " + _amount(target),
                    "Plan conditionnel : les machines ne sont pas déclarées prêtes par ce calcul.",
                ]
                if request["operation"] == "plan":
                    lines.extend(
                        f"{index + 1}. {step.batches} fabrication(s) · {step.recipe_id}"
                        + (" · circuit " + str(step.circuit) if step.circuit is not None else "")
                        for index, step in enumerate(analysis.plan.steps[:100])
                    )
                    lines.extend(
                        "Machine requise : " + machine
                        for machine in sorted(
                            {machine for step in analysis.plan.steps for machine in step.machines}
                        )[:40]
                    )
                    lines.extend(
                        "Disponible attesté : " + _amount(Amount(entry.resource, entry.quantity))
                        for entry in analysis.plan.allocations[:40]
                        if entry.source_kind == "physical"
                    )
                    lines.extend(
                        "À obtenir selon les relevés : " + _amount(amount)
                        for amount in analysis.plan.missing[:50]
                    )
                    lines.extend(
                        "À attendre : " + _amount(amount) for amount in analysis.plan.waiting[:30]
                    )
                    lines.extend(
                        "Limite : " + _safe(issue.message) for issue in analysis.plan.issues[:25]
                    )
                if analysis.diagnosis is not None:
                    labels = {
                        "observed": "CONFIRMÉ",
                        "calculated": "DÉDUIT",
                        "hypothesis": "HYPOTHÈSE",
                        "unknown": "INCONNU",
                    }
                    for finding in (*analysis.diagnosis.findings, *analysis.diagnosis.hypotheses):
                        lines.append(
                            labels.get(finding.certainty, "INCONNU")
                            + " · "
                            + _safe(finding.message)
                        )
                        if finding.next_check:
                            lines.append("Vérifier : " + _safe(finding.next_check))
                        lines.extend(
                            "Preuve : "
                            + _proof_text(proof)
                            + " · réf. "
                            + _safe(proof.reference, 120)
                            for proof in finding.evidence[:2]
                        )
                        lines.extend("Inconnu : " + _safe(value) for value in finding.missing[:3])
                        lines.extend(
                            "Contradiction : " + _safe(value)
                            for value in finding.contradictions[:2]
                        )
                alternatives = [
                    {"id": recipe.id, "texte": recipe.id}
                    for recipe in catalogue.catalog.recipes
                    if any(output.amount.resource == target.resource for output in recipe.outputs)
                ][:32]
                limits = list(analysis.limits) + [
                    "Catalogue limité à l'arbre EMI choisi ; branches extérieures non connues.",
                    "Réservations EMI et productions en cours non attestées par les exports actuels.",
                    "Fluides et variantes NBT de l'inventaire ne sont pas comptés comme stock disponible.",
                ]
                limits.extend(
                    _safe(value) for value in metadata.get("limites", ()) if isinstance(value, str)
                )
                result.update(
                    titre="Diagnostic de production"
                    if request["operation"] == "diagnostic"
                    else "Plan de production",
                    lignes=lines[:220],
                    alternatives=alternatives,
                    limites=list(dict.fromkeys(limits))[:35],
                    catalogue={
                        "source": catalogue.source,
                        "generation": catalogue.catalog.generation,
                        "sha256": catalogue.sha256,
                    },
                    rapport=_bounded_report(analysis.plan),
                )
                if result["rapport"].get("partial_view"):
                    result["limites"].append(
                        "Affichage structuré limité ; le calcul porte sur toutes les étapes de l'arbre fourni."
                    )
                summary = f"Produit {target.resource.id}, quantité {target.quantity} {target.resource.unit}. Catalogue génération {catalogue.catalog.generation}; statut du calcul {analysis.plan.status}. Stocks incomplets, inventaire sans NBT seulement.\n"
                summary += "\n".join(
                    "Besoin calculé : " + _amount(amount) for amount in analysis.plan.missing[:12]
                )
                summary += "\n" + "\n".join(
                    "Recette choisie : " + step.recipe_id + ", fabrications " + str(step.batches)
                    for step in analysis.plan.steps[:8]
                )
                if analysis.diagnosis is not None:
                    summary += "\n" + "\n".join(
                        _diagnostic_summary(finding)
                        for finding in (
                            *analysis.diagnosis.findings,
                            *analysis.diagnosis.hypotheses,
                        )[:8]
                    )
                    summary += "\nVérifications : contrôler la recette sélectionnée, l'état de la machine et ses entrées ; aucune connexion ou alimentation n'est présumée."
            if active_scope(self.bridge) != scope:
                raise ValueError("Connexion modifiée pendant le calcul.")
            if not self._catalogue_matches(result.get("catalogue"), scope):
                raise ValueError("Arbre EMI modifié pendant le calcul. Relance la consultation.")
            result.update(etat="pret", t=int(self.clock() * 1000))
        except (OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError) as error:
            message = (
                _safe(error)
                if isinstance(error, ValueError)
                else "Catalogue ou relevé local indisponible ou incomplet. Relance la consultation."
            )
            result.update(
                etat="erreur",
                titre="Consultation indisponible",
                lignes=[message],
                t=int(self.clock() * 1000),
            )
        except Exception:
            result.update(
                etat="erreur",
                titre="Consultation indisponible",
                lignes=["Erreur de lecture ou de calcul. Aucun état du jeu n'a été modifié."],
                t=int(self.clock() * 1000),
            )
        with self._lock:
            self._finished.append((generation, result, summary))


def replace_stock_unknown(snapshot):
    """Legacy aggregates have no item-variant proof: retain evidence without allocating them."""
    from dataclasses import replace

    return replace(
        snapshot,
        observations=tuple(
            row for row in snapshot.observations if row.kind not in {"inventory", "storage"}
        ),
        limitations=(
            *snapshot.limitations,
            "Identité exacte des objets disponibles non attestée ; besoins calculés sans stock garanti.",
        ),
    )
