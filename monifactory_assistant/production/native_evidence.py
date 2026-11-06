"""Decode explicit native stock/lot captures; no IO, replay or gameplay inference."""

import re
from dataclasses import dataclass, replace

from monifactory_assistant.game.world_scope import (
    _DIMENSION,
    _SESSION_ID,
    _WORLD_ID,
    WorldScope,
    matches,
)

from .knowledge import Completeness, Evidence, Freshness, KnowledgeStatus, Observation
from .planner import ProductionLot, StockLedger, StockSource
from .recipes import Amount, Resource

SOURCE = "production_v2_inventaire.json"
MAX_AGE_MS = 2500
MAX_QUANTITY = 2**31 - 1
MAX_LOTS = 512
MAX_AMOUNTS = 4096
MAX_TOTAL_AMOUNTS = 16384
MAX_VARIANT = 16384
_IDENTIFIER = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
_TOKEN = re.compile(r"[A-Za-z0-9_.:/|-]{1,256}")
_DIGEST = re.compile(r"[0-9a-f]{64}")


def _integer(value, label, *, maximum=2**53 - 1, minimum=0):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError(f"{label} invalide.")
    return value


def _text(value, label, *, maximum=256, empty=False):
    if not isinstance(value, str) or len(value) > maximum or (not empty and not value.strip()):
        raise ValueError(f"{label} invalide.")
    return value


def _list(value, label, maximum):
    if not isinstance(value, list) or len(value) > maximum:
        raise ValueError(f"{label} absent ou trop volumineux.")
    return value


def _boolean(value, label):
    if type(value) is not bool:
        raise ValueError(f"{label} non explicite.")
    return value


def _object(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} incomplet.")
    return value


def _limits(data):
    return tuple(
        _text(value, "Limite native", maximum=512)
        for value in _list(data.get("limites"), "Limites natives", 64)
    )


def _amounts(data):
    values = []
    seen = set()
    for entry in _list(data, "Quantités natives", MAX_AMOUNTS):
        entry = _object(entry, "Quantité native")
        resource = _object(entry.get("resource"), "Ressource native")
        _text(resource.get("id"), "Identifiant de ressource", maximum=256)
        _text(resource.get("variant", ""), "Variante native", maximum=MAX_VARIANT, empty=True)
        value = Amount.from_dict(entry)
        _integer(value.quantity, "Quantité native", maximum=MAX_QUANTITY, minimum=1)
        if value.resource in seen:
            raise ValueError("Quantité native répétée pour la même ressource.")
        seen.add(value.resource)
        values.append(value)
    return tuple(sorted(values, key=lambda value: value.resource))


def _counts(values):
    return {value.resource: value.quantity for value in values}


@dataclass(frozen=True)
class NativeContext:
    scope: WorldScope
    capture: str
    project: str
    project_revision: str
    catalogue_source: str
    catalogue_generation: int
    catalogue_sha256: str
    captured_at_ms: int
    received_at_ms: int | None

    def proof(self, reference, *, available=True, complete=False, status=KnowledgeStatus.OBSERVED):
        return Evidence(
            SOURCE,
            f"{SOURCE}#{reference}",
            status,
            Freshness.LIVE if available else Freshness.UNAVAILABLE,
            (Completeness.COMPLETE if complete else Completeness.PARTIAL)
            if available
            else Completeness.UNAVAILABLE,
            self.scope.world,
            self.scope.session,
            self.scope.dimension,
            self.captured_at_ms,
            self.received_at_ms,
            2,
            self.capture,
        )


@dataclass(frozen=True)
class NativeStock:
    context: NativeContext
    available: bool
    sources: tuple[StockSource, ...]
    observations: tuple[Observation, ...]
    evidence: tuple[Evidence, ...]
    limitations: tuple[str, ...]
    mode: str
    revision: str


@dataclass(frozen=True)
class NativeLots:
    context: NativeContext
    available: bool
    expected: tuple[ProductionLot, ...]
    confirmed: tuple[ProductionLot, ...]
    observations: tuple[Observation, ...]
    evidence: tuple[Evidence, ...]
    limitations: tuple[str, ...]
    fingerprint: str


@dataclass(frozen=True)
class NativeEvidence:
    ledger: StockLedger
    observations: tuple[Observation, ...]
    evidence: tuple[Evidence, ...]
    limitations: tuple[str, ...]
    stock_available: bool
    lots_available: bool
    captured_at_ms: int
    stock_mode: str
    stock_revision: str
    lots_fingerprint: str
    stock_complete: bool = False


def _context(data, scope, request, now_ms, received_at_ms):
    _object(data, "Capture native")
    _object(request, "Demande native")
    if not isinstance(scope, WorldScope) or not all(
        matches(pattern, value)
        for pattern, value in (
            (_WORLD_ID, scope.world),
            (_SESSION_ID, scope.session),
            (_DIMENSION, scope.dimension),
        )
    ):
        raise ValueError("Monde natif non identifié.")
    if type(data.get("v")) is not int or data["v"] != 2:
        raise ValueError("Version de preuve native non prise en charge.")
    if type(request.get("preuves_natives")) is not int or request["preuves_natives"] != 2:
        raise ValueError("Le clic ne demande pas une preuve native v2.")
    if not scope.accepts(data) or not scope.accepts(request):
        raise ValueError("La capture appartient à un autre monde ou une autre connexion.")
    stamp = _integer(data.get("t"), "Date de capture")
    _integer(now_ms, "Date de lecture")
    if not 0 <= now_ms - stamp <= MAX_AGE_MS:
        raise ValueError("Capture native périmée ou datée dans le futur.")
    if received_at_ms is not None:
        _integer(received_at_ms, "Date de réception")
        if not 0 <= now_ms - received_at_ms <= MAX_AGE_MS:
            raise ValueError("Réception native périmée ou datée dans le futur.")
    fields = (
        "capture",
        "projet",
        "revision_projet",
        "catalogue_source",
        "catalogue_generation",
        "catalogue_sha256",
    )
    for key in fields:
        if (
            key not in data
            or data[key] != request.get(key)
            or type(data[key]) is not type(request.get(key))
        ):
            raise ValueError("La capture ne correspond plus au clic, au projet ou au catalogue.")
    capture = _text(data["capture"], "Identifiant de capture", maximum=128)
    if not _TOKEN.fullmatch(capture):
        raise ValueError("Identifiant de capture invalide.")
    project = _text(data["projet"], "Projet", maximum=128, empty=True)
    revision = _text(data["revision_projet"], "Révision de projet", maximum=128)
    source = _text(data["catalogue_source"], "Source du catalogue", maximum=512)
    digest = _text(data["catalogue_sha256"], "Empreinte du catalogue", maximum=64)
    if not source.startswith("emi/") or not _DIGEST.fullmatch(digest):
        raise ValueError("Empreinte ou source du catalogue invalide.")
    generation = _integer(data["catalogue_generation"], "Génération du catalogue", minimum=1)
    return NativeContext(
        scope,
        capture,
        project,
        revision,
        source,
        generation,
        data["catalogue_sha256"],
        stamp,
        received_at_ms,
    )


def _item_amounts(data):
    _object(data, "Objets natifs")
    if len(data) > MAX_AMOUNTS:
        raise ValueError("Trop d’objets dans la source native.")
    values = []
    for item, quantity in data.items():
        _text(item, "Identifiant d’objet")
        _integer(quantity, "Quantité d’objet", minimum=1, maximum=MAX_QUANTITY)
        values.append(Amount(Resource("item", item, "item"), quantity))
    return tuple(sorted(values, key=lambda amount: amount.resource))


def _stock_row(context, ident, nature, quantities, *, observed_at=None, revision=""):
    proof = context.proof("stock/" + ident)
    if observed_at is not None:
        proof = replace(proof, observed_at_ms=observed_at)
    # The ledger orders sources by ID: use carried stock before proposing a withdrawal.
    source = {"inventaire": "0-inventaire", "curseur": "1-curseur", "coffre": "2-coffre"}.get(
        ident, ident
    )
    key = "native:" + source
    row = Observation(
        key,
        "native_stock",
        key,
        {
            "source_id": key,
            "nature": nature,
            "amounts": [a.to_dict() for a in quantities],
            "revision": revision,
        },
        (proof,),
    )
    return StockSource(key, quantities), row


def decode_stock(data, context, *, personal):
    """Choose one coherent menu packet OR consolidated personal stock, never their sum."""
    _object(data, "Stock natif")
    if type(data.get("v")) is not int or data["v"] != 1 or data.get("mode") != "menu_serveur":
        raise ValueError("Version ou mode du stock natif inconnu.")
    available = _boolean(data.get("disponible"), "Disponibilité du stock")
    limits = _limits(data)
    sources, observations = [], []
    if available:
        if data.get("coherence") != "paquet_complet_sans_mutation":
            raise ValueError("Le stock ne porte pas de preuve de cohérence commune.")
        observed = _integer(data.get("observe_ms"), "Date du paquet de stock")
        captured = _integer(data.get("capture_ms"), "Date de capture du stock")
        if captured != context.captured_at_ms or not 0 <= captured - observed <= 60_000:
            raise ValueError("Paquet de stock périmé ou étranger à la capture.")
        revision = _text(data.get("revision"), "Révision du stock", maximum=128)
        if not _TOKEN.fullmatch(revision):
            raise ValueError("Révision du stock invalide.")
        _integer(data.get("container_id"), "Identifiant du menu", maximum=2**31 - 1)
        _integer(data.get("state_id"), "Révision du menu", maximum=2**31 - 1)
        expected = {"coffre": "coffre_ouvert", "inventaire": "inventaire_36", "curseur": "curseur"}
        seen = set()
        for source in _list(data.get("sources"), "Sources du stock", 3):
            source = _object(source, "Source du stock")
            ident = source.get("id")
            if (
                not isinstance(ident, str)
                or ident not in expected
                or ident in seen
                or source.get("nature") != expected[ident]
            ):
                raise ValueError("Sources de stock répétées, inconnues ou non disjointes.")
            seen.add(ident)
            quantities = _item_amounts(source.get("items"))
            stock, observation = _stock_row(
                context, ident, expected[ident], quantities, observed_at=observed, revision=revision
            )
            sources.append(stock)
            observations.append(observation)
        if seen != set(expected):
            raise ValueError("Paquet de stock incomplet.")
        limits = (
            *limits,
            "Stock du coffre ouvert, de ses 36 cases joueur et du curseur uniquement ; sacs, armure, seconde main et variantes NBT exclus.",
        )
        mode = "menu_serveur"
    else:
        _object(personal, "Inventaire personnel")
        active = _boolean(personal.get("actif"), "Présence du joueur")
        synchronized = _boolean(personal.get("synchronise"), "Synchronisation de l’inventaire")
        available = active and synchronized
        mode, revision = "personnel", context.capture
        if available:
            quantities = _item_amounts(personal.get("inventaire"))
            stock, observation = _stock_row(
                context, "personnel", "inventaire_et_sacs", quantities, revision=revision
            )
            sources.append(stock)
            observations.append(observation)
        limits = (
            *limits,
            "Stock personnel et sacs synchronisés uniquement ; coffres et variantes NBT exclus.",
        )
        if not available:
            limits = (*limits, "Inventaire personnel non synchronisé ; aucun stock garanti.")
    proof = context.proof("stock", available=available)
    return NativeStock(
        context, available, tuple(sources), tuple(observations), (proof,), limits, mode, revision
    )


def decode_lots(data, context):
    """A lot receipt settles its promise only; it is never an inventory source."""
    _object(data, "Suivi natif")
    if type(data.get("v")) is not int or data["v"] != 3:
        raise ValueError("Version du suivi natif inconnue.")
    available = _boolean(data.get("disponible"), "Disponibilité du suivi")
    revision = _integer(data.get("revision"), "Révision du suivi")
    fingerprint = data.get("empreinte")
    if not isinstance(fingerprint, str) or not _DIGEST.fullmatch(fingerprint):
        raise ValueError("Empreinte du suivi natif invalide.")
    limits = _limits(data)
    rows, expected, confirmed = {}, [], []
    entries = 0
    for entry in _list(data.get("lots"), "Lots natifs", MAX_LOTS):
        entry = _object(entry, "Lot natif")
        ident = _text(entry.get("id"), "Identifiant du lot")
        if not re.fullmatch(r"emi-lot/" + context.scope.session + r"/[1-9][0-9]{0,9}", ident):
            raise ValueError("Identifiant de lot étranger à la connexion.")
        recipe = _text(entry.get("recette"), "Recette du lot")
        if not _IDENTIFIER.fullmatch(recipe):
            raise ValueError("Recette du lot invalide.")
        origin, state = entry.get("origine"), entry.get("etat")
        if origin not in ("declaration_joueur", "envoi_machine") or state not in (
            "en_cours",
            "a_recuperer",
            "termine",
            "annule",
        ):
            raise ValueError("Origine ou état du lot inconnu.")
        total, received, remaining, cancelled = (
            _amounts(entry.get(key)) for key in ("total", "recus", "restants", "annules")
        )
        entries += sum(len(values) for values in (total, received, remaining, cancelled))
        if entries > MAX_TOTAL_AMOUNTS:
            raise ValueError("Trop de quantités dans le suivi natif.")
        if not total:
            raise ValueError("Un lot exige une sortie garantie déclarée.")
        totals = _counts(total)
        parts = [_counts(values) for values in (received, remaining, cancelled)]
        resources = set(totals).union(*parts)
        if any(
            totals.get(resource, 0) != sum(part.get(resource, 0) for part in parts)
            for resource in resources
        ):
            raise ValueError("Réception, attente ou annulation incohérente avec le total du lot.")
        if (
            state == "annule"
            and remaining
            or state != "annule"
            and cancelled
            or state == "termine"
            and remaining
            or state in ("en_cours", "a_recuperer")
            and not remaining
        ):
            raise ValueError("Les quantités ne correspondent pas à l’état du lot.")
        machine = entry.get("machine_key")
        if (origin == "envoi_machine") != (machine is not None):
            raise ValueError("L’origine du lot ne correspond pas à sa machine.")
        if machine is not None:
            if not isinstance(machine, str) or not re.fullmatch(
                re.escape(context.scope.dimension) + r"\|-?[0-9]{1,9}\|-?[0-9]{1,9}\|-?[0-9]{1,9}",
                machine,
            ):
                raise ValueError("Machine du lot hors de la dimension active.")
        value = {
            "recipe": recipe,
            "origin": origin,
            "state": state,
            "total": [a.to_dict() for a in total],
            "received": [a.to_dict() for a in received],
            "remaining": [a.to_dict() for a in remaining],
            "cancelled": [a.to_dict() for a in cancelled],
            "revision": revision,
            "machine_key": machine,
        }
        proof = context.proof("lots/" + ident, available=available, complete=True)
        observation = Observation("native-lot:" + ident, "production_lot", ident, value, (proof,))
        if ident in rows:
            if rows[ident].value != observation.value:
                raise ValueError("Deux états contradictoires pour le même lot natif.")
            continue
        rows[ident] = observation
        if available:
            if state != "annule":
                expected.append(ProductionLot(ident, total))
            if received:
                confirmed.append(ProductionLot(ident, received))
    proof = context.proof("suivi", available=available)
    if not available:
        limits = (*limits, "Suivi des lots non synchronisé ; aucune sortie attendue créditée.")
    return NativeLots(
        context,
        available,
        tuple(expected),
        tuple(confirmed),
        tuple(rows.values()),
        (proof,),
        limits,
        fingerprint,
    )


def combine(stock, lots):
    """Combine only the same native capture, never two independently refreshed files."""
    if (
        not isinstance(stock, NativeStock)
        or not isinstance(lots, NativeLots)
        or stock.context != lots.context
    ):
        raise ValueError("Stocks et lots ne proviennent pas de la même capture native.")
    # The lot owner proves coherence with the personal snapshot only. A complete menu
    # packet may predate a receipt; its stock cannot also pay a still-pending promise.
    if lots.available and (stock.mode != "personnel" or not stock.available):

        def unavailable(proof):
            return replace(
                proof,
                status=KnowledgeStatus.UNKNOWN,
                freshness=Freshness.UNAVAILABLE,
                completeness=Completeness.UNAVAILABLE,
            )

        lots = replace(
            lots,
            available=False,
            expected=(),
            confirmed=(),
            observations=tuple(
                replace(row, evidence=tuple(unavailable(proof) for proof in row.evidence))
                for row in lots.observations
            ),
            evidence=tuple(unavailable(proof) for proof in lots.evidence),
            limitations=(
                *lots.limitations,
                "Lots non crédités : aucune preuve commune entre ce stock et les réceptions du suivi.",
            ),
        )
    ledger = StockLedger(sources=stock.sources, expected=lots.expected, confirmed=lots.confirmed)
    return NativeEvidence(
        ledger,
        (*stock.observations, *lots.observations),
        (*stock.evidence, *lots.evidence),
        tuple(dict.fromkeys((*stock.limitations, *lots.limitations))),
        stock.available,
        lots.available,
        stock.context.captured_at_ms,
        stock.mode,
        stock.revision,
        lots.fingerprint,
    )


def decode_native_evidence(data, *, scope, request, now_ms, received_at_ms=None):
    """Reject foreign envelopes; each unavailable stock/lot projection degrades separately."""
    context = _context(data, scope, request, now_ms, received_at_ms)
    try:
        projection = data.get("stock")
        if projection is None and data.get("coffre_demande") is True:
            raise ValueError("Relevé du menu de coffre absent.")
        stock = decode_stock(projection, context, personal=data)
    except (ValueError, TypeError, RecursionError):
        message = "Relevé du coffre invalide ou indisponible ; seul l’inventaire personnel attesté peut être utilisé."
        try:
            stock = decode_stock(
                {"v": 1, "disponible": False, "mode": "menu_serveur", "limites": [message]},
                context,
                personal=data,
            )
        except (ValueError, TypeError, RecursionError):
            stock = NativeStock(
                context,
                False,
                (),
                (),
                (context.proof("stock", available=False),),
                ("Stocks natifs invalides ou indisponibles ; aucun stock garanti.",),
                "personnel",
                context.capture,
            )
    try:
        lots = decode_lots(data.get("suivi"), context)
    except (ValueError, TypeError, RecursionError):
        lots = NativeLots(
            context,
            False,
            (),
            (),
            (),
            (context.proof("suivi", available=False),),
            ("Suivi natif invalide ou indisponible ; aucune sortie attendue créditée.",),
            "",
        )
    return combine(stock, lots)
