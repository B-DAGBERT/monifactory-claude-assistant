"""Explicit native V2 requests; bounded background work and inert cached UI/AI results."""

import copy
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from monifactory_assistant.game.world import machine_label
from monifactory_assistant.game.world_scope import scope_file
from monifactory_assistant.production.acquisition import (
    acquire,
    acquire_native,
    active_scope,
    attest_catalogue,
    capture_stamp,
    read_capture,
)
from monifactory_assistant.production.analysis import analyse_observed
from monifactory_assistant.production.catalog import CatalogueReader
from monifactory_assistant.production.knowledge import Freshness
from monifactory_assistant.production.planner import StockLedger
from monifactory_assistant.production.recipes import Amount, Resource

# Une consultation appartient à un monde, une connexion et une dimension ; rien d'autre.
_CONTEXT = ("monde_actif", "session_monde", "dimension_monde")
_RESOURCE = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
_MAX_AGE = 45_000
_RESULT_MAX_AGE = 180_000
_PROOF_VERSION = 2


def _safe(text, limit=300):
    return " ".join(str(text).split())[:limit]


def _amount(amount):
    return f"{amount.quantity} {amount.resource.unit} {amount.resource.id}"


def _native_lines(native):
    if native is None:
        return ["Preuve native indisponible · stock et suivi non attestés."]
    lines = [
        "Couverture partielle · coffre ouvert et cases du même paquet serveur."
        if native.stock_mode == "menu_serveur"
        else "Couverture partielle · inventaire et sacs synchronisés."
        if native.stock_available
        else "Stock physique inconnu · inventaire non attesté."
    ]
    if not native.lots_available:
        lines.append("Réservations et suivi des lots non exploitables dans cette capture.")
        return lines
    lines.append("Réservations physiques non attestées par le suivi.")
    labels = {
        "en_cours": "En cours",
        "a_recuperer": "Sortie signalée par EMI",
        "termine": "Réceptionné",
        "annule": "Annulé",
    }
    lots = (
        row
        for row in native.observations
        if row.kind == "production_lot"
        and any(proof.freshness == Freshness.LIVE for proof in row.evidence)
    )
    for index, row in enumerate(lots):
        if index >= 12:
            lines.append("Autres lots · affichage limité.")
            break
        state = row.value["state"]
        lines.append(f"Lot {labels[state].lower()} · {_safe(row.value['recipe'])}")
        for field, label in (
            ("remaining", "Attendu déclaré"),
            ("received", "Réception attestée"),
            ("cancelled", "Attente annulée"),
        ):
            for value in row.value[field][:3]:
                resource = value["resource"]
                lines.append(f"{label} : {value['quantity']} {resource['unit']} {resource['id']}")
    return lines


def _allocation_lines(plan):
    labels = {"native:2-coffre": "À récupérer au coffre", "native:1-curseur": "Sur le curseur"}
    return [
        labels.get(entry.source_id, "Disponible attesté sur le joueur")
        + ": "
        + _amount(Amount(entry.resource, entry.quantity))
        for entry in plan.allocations[:40]
        if entry.source_kind == "physical"
    ]


def _historical_lines(snapshot, missing):
    if any(amount.resource.kind == "item" for amount in missing) and any(
        proof.freshness in {Freshness.STALE, Freshness.HISTORICAL}
        for row in snapshot.select("storage")
        for proof in row.evidence
    ):
        return ["Historique de stockage présent · accès et disponibilité non vérifiés."]
    return []


def _compatible_posts(step):
    names = [machine_label(machine) for machine in step.machines[:3]]
    if len(step.machines) > len(names):
        names.append(f"{len(step.machines) - len(names)} autres")
    return "Postes compatibles · " + ", ".join(names)


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
        self._attestation_lock = threading.Lock()
        self._future = None
        self._generation = 0
        self._context = {}
        self._scope = None
        self._result = None
        self._reader = None
        self._reader_source = None
        self._ai_context = ""
        self._finished = []
        self._seen = {}
        self._updated = 0
        self._marker_cache = (None, None)

    def poll(self, app):
        """Small main-loop maintenance only; no catalogue, stock or planning read here."""
        scope = active_scope(self.bridge, int(self.clock() * 1000))
        context = scope.fields() if scope is not None else {}
        changed = False
        with self._lock:
            self._updated = self.clock()
            if context != self._context:
                self._generation += 1
                self._context, self._scope = context, scope
                self._result, self._ai_context = None, ""
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
                and (
                    not self._result_fresh()
                    or not self._catalogue_matches(self._result.get("catalogue"), scope)
                )
            ):
                self._revoke_result()
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
                    "preuves_natives": _PROOF_VERSION,
                    "disponible": available,
                    "message": "Lecture seule : aucun transfert ni modification du jeu."
                    if available
                    else "Entre dans un monde identifié pour consulter la production.",
                    "contexte": self._context if available else {},
                    "resultat": self._result if available else None,
                }
            )

    def context(self, question=None, *, require_current=False):
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
                if require_current and (
                    self._result is not None
                    or re.search(r"\b(v2|plan de production)\b", question.casefold())
                ):
                    raise ValueError(
                        "Ouvre Production dans Minecraft pour obtenir un calcul actuel."
                    )
                return None
            age = max(0, int(self.clock() * 1000 - self._result.get("t", 0)))
            proof = {
                **self._context,
                "generation_service": self._generation,
                "n": self._result["n"],
                "catalogue": self._result.get("catalogue"),
            }
            text = (
                f"Résultat local V2 en lecture seule, calculé il y a {age // 1000} s. Les faits sont datés, pas une autorisation d'action.\n"
                + self._ai_context[:5000]
            )
        if self.context_valid(proof):
            return text, proof
        if require_current:
            raise ValueError(
                "Le calcul de production n’est plus vérifiable. Relance-le dans Minecraft."
            )
        return None

    def context_valid(self, proof):
        with self._lock:
            if not self._bound_context(proof):
                return False
            catalogue, scope = self._result.get("catalogue"), self._scope
        current = self._live_catalogue(catalogue, scope)
        with self._lock:
            if not self._bound_context(proof):
                return False
            if current is False:
                self._revoke_result()
            return current is True

    def _bound_context(self, proof):
        if (
            not isinstance(proof, dict)
            or not self._matches(proof)
            or proof.get("generation_service") != self._generation
            or not self._result
            or self._result.get("etat") != "pret"
            or proof.get("n") != self._result.get("n")
            or proof.get("catalogue") != self._result.get("catalogue")
            or not self._result_fresh()
        ):
            return False
        return active_scope(
            self.bridge, int(self.clock() * 1000)
        ) == self._scope and self._catalogue_matches(self._result.get("catalogue"), self._scope)

    def _revoke_result(self):
        self._result = {
            "n": self._result["n"],
            "etat": "erreur",
            "titre": "Consultation périmée",
            "lignes": ["La preuve du calcul n’est plus courante. Relance la consultation."],
            "limites": [],
            "alternatives": [],
            **self._context,
        }
        self._ai_context = ""

    def _result_fresh(self):
        age = int(self.clock() * 1000) - self._result.get("t", 0)
        return 0 <= age <= _RESULT_MAX_AGE

    def _live_catalogue(self, catalogue, scope, *, wait=False):
        if catalogue is None or scope is None:
            return False  # Every ready consultation is bound to an EMI tree.
        acquired = (
            self._attestation_lock.acquire(timeout=0.5)
            if wait
            else self._attestation_lock.acquire(blocking=False)
        )
        if not acquired:
            return (
                None  # Another consumer owns the mailbox; absence of a verdict is not revocation.
            )
        try:
            return attest_catalogue(self.bridge.dir, scope, catalogue)
        finally:
            self._attestation_lock.release()

    def _catalogue_matches(self, catalogue, scope):
        if catalogue is None:
            return False  # Every ready consultation is bound to an EMI tree.
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
                type(request.get("preuves_natives")) is not int
                or request["preuves_natives"] != _PROOF_VERSION
            ):
                return {
                    "etat": "refusee",
                    "message": "Versions incompatibles. Relance l’assistant et le mod du même candidat.",
                }
            if (
                type(number) is not int
                or not 0 <= number <= 2**53 - 1
                or not -5000 <= now - number <= _MAX_AGE
            ):
                return {"etat": "refusee", "message": "Demande de production expirée."}
            if not self._matches(request) or active_scope(self.bridge, now) != self._scope:
                return {
                    "etat": "refusee",
                    "message": "Monde, connexion ou dimension modifiés ; renouvelle le clic.",
                }
            if number in self._seen:
                return self._seen[number]
            if self._future is not None and not self._future.done():
                return {"etat": "refusee", "message": "Un calcul de production est déjà en cours."}
            try:
                operation = request.get("operation")
                if operation == "memoire":
                    # Onglet Projet d'un ancien mod : les projets n'existent plus.
                    raise ValueError("Les projets ont été retirés : cette lecture n’existe plus.")
                if operation not in {"plan", "diagnostic"}:
                    raise ValueError("Consultation inconnue.")
                if not isinstance(request.get("capture"), str) or not re.fullmatch(
                    r"[a-zA-Z0-9_.:/|\-]{1,128}", request["capture"]
                ):
                    raise ValueError("Identifiant de capture native invalide.")
                if not isinstance(request.get("capture_sha256"), str) or not re.fullmatch(
                    r"[0-9a-f]{64}", request["capture_sha256"]
                ):
                    raise ValueError("Empreinte de capture native invalide.")
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
            now = int(self.clock() * 1000)
            try:
                native = acquire_native(self.bridge, scope, request, now)
            except (OSError, ValueError, TypeError, RecursionError):
                native = None
            snapshot = acquire(self.bridge, self.python_root, scope, request, now)
            analysis = analyse_observed(
                catalogue,
                snapshot,
                target,
                stock_ledger=native.ledger if native else StockLedger(),
                stock_evidence=native.evidence if native else (),
                recipe_choices=choices,
                ingredient_choices=ingredients,
                diagnose_recipe=selected,
                machine_key=request.get("machine_key"),
            )
            lines = [
                "Produit : " + _amount(target),
                "Plan conditionnel : les machines ne sont pas déclarées prêtes par ce calcul.",
            ]
            source_lines = _native_lines(native)
            lines.extend(source_lines)
            allocations = _allocation_lines(analysis.plan)
            if request["operation"] == "plan":
                lines.extend(allocations)
                lines.extend(
                    "À obtenir selon les relevés : " + _amount(amount)
                    for amount in analysis.plan.missing[:50]
                )
                lines.extend(
                    "À attendre : " + _amount(amount) for amount in analysis.plan.waiting[:30]
                )
                lines.extend(_historical_lines(snapshot, analysis.plan.missing))
                for index, step in enumerate(analysis.plan.steps[:64], 1):
                    lines.append(
                        f"{index}. {step.batches} fabrication(s) · {step.recipe_id}"
                        + (f" · circuit {step.circuit}" if step.circuit is not None else "")
                    )
                    if step.machines:
                        lines.append(_compatible_posts(step))
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
                        labels.get(finding.certainty, "INCONNU") + " · " + _safe(finding.message)
                    )
                    if finding.next_check:
                        lines.append("Vérifier : " + _safe(finding.next_check))
                    lines.extend(
                        "Preuve : " + _proof_text(proof) + " · réf. " + _safe(proof.reference, 120)
                        for proof in finding.evidence[:2]
                    )
                    lines.extend("Inconnu : " + _safe(value) for value in finding.missing[:3])
                    lines.extend(
                        "Contradiction : " + _safe(value) for value in finding.contradictions[:2]
                    )
            alternatives = [
                {"id": recipe.id, "texte": recipe.id}
                for recipe in catalogue.catalog.recipes
                if any(output.amount.resource == target.resource for output in recipe.outputs)
            ][:32]
            limits = list(analysis.limits) + [
                "Catalogue limité à l'arbre EMI choisi ; branches extérieures non connues.",
                "Fluides et variantes NBT de l'inventaire ne sont pas comptés comme stock disponible.",
            ]
            if native:
                limits.extend(native.limitations)
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
                    "capture": request["capture"],
                    "capture_sha256": request["capture_sha256"],
                },
                rapport=_bounded_report(analysis.plan),
                preuves={
                    "stock": native.stock_mode if native and native.stock_available else "inconnu",
                    "lots_exploitables": bool(native and native.lots_available),
                    "couverture_complete": False,
                },
            )
            if result["rapport"].get("partial_view"):
                result["limites"].append(
                    "Affichage structuré limité ; le calcul porte sur toutes les étapes de l'arbre fourni."
                )
            summary = f"Produit {target.resource.id}, quantité {target.quantity} {target.resource.unit}. Catalogue génération {catalogue.catalog.generation}; statut du calcul {analysis.plan.status}.\n"
            summary += "\n".join(source_lines[:16] + allocations[:12]) + "\n"
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
            catalogue = result.get("catalogue")
            if not self._catalogue_matches(catalogue, scope) or not self._live_catalogue(
                catalogue, scope, wait=True
            ):
                raise ValueError(
                    "Arbre EMI ou preuve native modifié ou indisponible. Relance la consultation."
                )
            result.update(etat="pret", t=int(self.clock() * 1000))
        except (OSError, ValueError, TypeError, KeyError, AttributeError, RecursionError) as error:
            result.pop("rapport", None)
            summary = ""
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
