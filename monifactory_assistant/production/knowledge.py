"""Version 1 read-only views over caller-captured game snapshots.

This module performs no file IO, scan, action or persistence. A caller must capture the
WorldScope and each source coherently; ``SourceSnapshot.world`` may associate a historical
file only after resolving its existing per-world partition. Legacy files stay unscoped.
"""

import hashlib
import json
import re
from collections import defaultdict
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from enum import Enum
from types import MappingProxyType

from monifactory_assistant.game.storage import (
    SHARED_CHESTS,
    Storage,
    grouped_containers,
    inventory_key,
    latest_remembered_containers,
)
from monifactory_assistant.game.world import _block_observation
from monifactory_assistant.game.world_scope import WorldScope

VERSION = 1
_MAX_INTEGER = 2**53 - 1
_ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+")
_WORLD = re.compile(r"m1_[0-9a-f]{64}")
_SESSION = re.compile(r"[0-9a-f]{32}")
_LIVE_FOR = {"etat.json": 5000, "inventaire_joueur.json": 2500, "monde.json": 15000}
_HISTORIES = {"stockage.json", "monde_memoire.json", "filons_mod.json"}
_WOODS = "oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|crimson|warped|framed"
_PHYSICAL_STORAGE = re.compile(
    r"minecraft:(?:chest|trapped_chest|barrel|(?:(?:white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black)_)?shulker_box)"
    r"|sophisticatedstorage:(?:(?:copper|iron|gold|diamond|netherite)_)?(?:chest|barrel|limited_[1-4]_barrel)"
    rf"|functionalstorage:(?:(?:{_WOODS})_[124]|(?:framed_)?(?:simple_)?compacting_drawer)"
    rf"|storagedrawers:(?:(?:{_WOODS})_(?:full|half)_drawers_[124]|comp_drawers_3)"
)


class KnowledgeStatus(str, Enum):
    OBSERVED = "OBSERVED"
    USER_CONFIRMED = "USER_CONFIRMED"
    DERIVED = "DERIVED"
    UNKNOWN = "UNKNOWN"


class Freshness(str, Enum):
    LIVE = "LIVE"
    STALE = "STALE"
    HISTORICAL = "HISTORICAL"
    UNAVAILABLE = "UNAVAILABLE"


class Completeness(str, Enum):
    COMPLETE = "COMPLETE"
    PARTIAL = "PARTIAL"
    UNKNOWN = "UNKNOWN"
    UNAVAILABLE = "UNAVAILABLE"


def _freeze(value):
    if isinstance(value, Mapping):
        return MappingProxyType({key: _freeze(item) for key, item in value.items()})
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    if value is None or type(value) in (bool, int, float, str):
        return value
    raise ValueError("Valeur de connaissance non JSON.")


def _plain(value):
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [_plain(item) for item in value]
    return value


def _number(value):
    return type(value) is int and 0 <= value <= _MAX_INTEGER


def _timestamp(value):
    if type(value) in (int, float) and 0 <= value <= _MAX_INTEGER and int(value) == value:
        return int(value)
    return None


def _resource(value):
    return isinstance(value, str) and _ID.fullmatch(value) is not None


def _scope_valid(scope):
    return isinstance(scope, WorldScope) and all(
        isinstance(value, str) and regex.fullmatch(value)
        for value, regex in (
            (scope.world, _WORLD),
            (scope.session, _SESSION),
            (scope.dimension, _ID),
        )
    )


@dataclass(frozen=True)
class Evidence:
    source: str
    reference: str
    status: KnowledgeStatus
    freshness: Freshness
    completeness: Completeness
    world: str | None = None
    session: str | None = None
    dimension: str | None = None
    observed_at_ms: int | None = None
    received_at_ms: int | None = None
    schema_version: str | int | None = None
    capture_id: str | None = None

    def __post_init__(self):
        object.__setattr__(self, "status", KnowledgeStatus(self.status))
        object.__setattr__(self, "freshness", Freshness(self.freshness))
        object.__setattr__(self, "completeness", Completeness(self.completeness))
        if not self.source or not self.reference:
            raise ValueError("Source et référence de preuve requises.")
        if any(
            value is not None and not _number(value)
            for value in (self.observed_at_ms, self.received_at_ms)
        ):
            raise ValueError("Date de preuve invalide.")


@dataclass(frozen=True)
class Observation:
    id: str
    kind: str
    key: str
    value: Mapping
    evidence: tuple[Evidence, ...]
    position: tuple[int, int, int] | None = None

    def __post_init__(self):
        if not self.id or not self.kind or not self.key or not isinstance(self.value, Mapping):
            raise ValueError("Observation sans identité ou valeur valide.")
        object.__setattr__(self, "value", _freeze(self.value))
        object.__setattr__(self, "evidence", tuple(self.evidence))
        if not self.evidence or any(not isinstance(item, Evidence) for item in self.evidence):
            raise ValueError("Observation sans preuve.")
        if self.position is not None:
            coords = tuple(self.position)
            if len(coords) != 3 or any(type(n) is not int or abs(n) > _MAX_INTEGER for n in coords):
                raise ValueError("Position invalide.")
            object.__setattr__(self, "position", coords)


@dataclass(frozen=True)
class SourceSnapshot:
    """A captured source, not a path to read; permission/coherence failures stay explicit."""

    name: str
    data: object
    reference: str = ""
    world: str | None = None
    received_at_ms: int | None = None
    authorized: bool = True
    coherent: bool = True

    def __post_init__(self):
        object.__setattr__(self, "data", _freeze(self.data))
        if not self.reference:
            object.__setattr__(self, "reference", self.name)
        if self.received_at_ms is not None and not _number(self.received_at_ms):
            raise ValueError("Date de capture invalide.")


@dataclass(frozen=True)
class KnowledgeSnapshot:
    scope: WorldScope | None
    captured_at_ms: int
    observations: tuple[Observation, ...]
    limitations: tuple[str, ...] = ()
    history: tuple[Observation, ...] = ()
    sources: tuple[Evidence, ...] = ()
    stock_frame_coherent: bool = False
    version: int = field(default=VERSION, init=False)

    def __post_init__(self):
        if (
            not _number(self.captured_at_ms)
            or self.scope is not None
            and not _scope_valid(self.scope)
            or type(self.stock_frame_coherent) is not bool
        ):
            raise ValueError("Date ou portée de connaissance invalide.")
        for key in ("observations", "limitations", "history", "sources"):
            object.__setattr__(self, key, tuple(getattr(self, key)))

    def select(self, kind):
        return tuple(item for item in self.observations if item.kind == kind)

    @property
    def stock_universe_complete(self):
        """No existing producer proves that every storage in a world has been observed."""
        return False

    def availability_observations(self):
        """The exact canonical sources counted by availability(), with inspectable proofs."""
        if self.scope is None:
            return ()
        result = []
        seen = set()
        for observation in self.observations:
            if (
                observation.kind not in {"inventory", "storage"}
                or "items" not in observation.value
                or observation.value.get("identity_ambiguous") is True
            ):
                continue
            if not any(
                proof.status in {KnowledgeStatus.OBSERVED, KnowledgeStatus.DERIVED}
                and proof.freshness == Freshness.LIVE
                and proof.world == self.scope.world
                and proof.session == self.scope.session
                and proof.dimension == self.scope.dimension
                for proof in observation.evidence
            ):
                continue
            key = (observation.kind, observation.key)
            if key in seen:
                continue
            seen.add(key)
            result.append(observation)
        if not self.stock_frame_coherent:
            personal = [row for row in result if row.kind == "inventory"]
            if personal:
                return tuple(personal)
            if result:
                # Even direct offline constructors must not combine successive server surveys.
                latest_row = max(result, key=_priority)
                latest = latest_row.evidence[0]
                if latest.capture_id is None:
                    return (latest_row,)
                result = [
                    row for row in result if _generation(row.evidence[0]) == _generation(latest)
                ]
        return tuple(result)

    def availability(self):
        """Current item-ID lower bounds only; no fluids, variants, machine contents or history.

        A missing key is not evidence of zero stock. Inspect ``sources`` and each observation's
        completeness before deriving a shortage. Backpacks are already inside personal stock.
        """
        totals = defaultdict(int)
        for observation in self.availability_observations():
            for item, count in observation.value.get("items", {}).items():
                totals[item] += count
        return dict(totals)


def _counts(value, *, pairs=False):
    if isinstance(value, Mapping) and not pairs:
        entries = value.items()
    elif isinstance(value, (list, tuple)):
        entries = []
        for item in value:
            if pairs and isinstance(item, (list, tuple)) and len(item) == 2:
                entries.append(item)
            elif not pairs and isinstance(item, Mapping):
                entries.append((item.get("item"), item.get("n")))
            else:
                raise ValueError("Inventaire partiel ou mal formé.")
    else:
        raise ValueError("Inventaire indisponible.")
    counts = defaultdict(int)
    for item, count in entries:
        # Rhino JSON numbers may be integral floats; booleans and non-finite values never count.
        if (
            not _resource(item)
            or type(count) not in (int, float)
            or not 0 <= count <= 1_000_000_000
            or int(count) != count
        ):
            raise ValueError("Quantité ou identifiant d'objet invalide.")
        if count:
            counts[item] += int(count)
    return dict(counts)


def _location(record):
    dimension = record.get("dimension")
    coords = tuple(record.get(key) for key in ("x", "y", "z"))
    if not _resource(dimension) or any(
        type(v) not in (int, float) or not -_MAX_INTEGER <= v <= _MAX_INTEGER or int(v) != v
        for v in coords
    ):
        raise ValueError("Position de relevé invalide.")
    coords = tuple(map(int, coords))
    return dimension, coords, "|".join(map(str, (dimension, *coords)))


def _observation(kind, key, value, proof, position=None):
    content = json.dumps(
        [kind, key, _plain(value), proof.__dict__],
        sort_keys=True,
        ensure_ascii=True,
        allow_nan=False,
    )
    identity = hashlib.sha256(content.encode("ascii")).hexdigest()
    return Observation(identity, kind, key, value, (proof,), position)


class _Builder:
    def __init__(self, scope, now, history_world):
        self.scope, self.now = scope, now
        self.world_identity = scope.world if scope is not None else history_world
        self.rows, self.sources, self.limits, self.history = [], [], [], []

    def evidence(self, source):
        data = source.data if isinstance(source.data, Mapping) else {}
        world = data.get("monde_actif", source.world)
        session = data.get("session_monde")
        dimension = data.get("dimension_monde", data.get("dimension"))
        stamp = _timestamp(data.get("t"))
        capture_id = None
        if source.name == "monde.json" and isinstance(source.data, Mapping):
            content = json.dumps(_plain(data), ensure_ascii=True, sort_keys=True)
            capture_id = hashlib.sha256(content.encode("ascii")).hexdigest()
        known = source.name in _LIVE_FOR or source.name in _HISTORIES
        available = (
            source.authorized is True
            and source.coherent is True
            and isinstance(source.data, Mapping)
            and known
        )
        if world is not None and (not isinstance(world, str) or not _WORLD.fullmatch(world)):
            available = False
        if source.world is not None and source.world != world:
            available = False
        available = available and world == self.world_identity
        current = self.scope is not None and self.scope.accepts(_plain(data))
        freshness = Freshness.HISTORICAL
        if not available:
            freshness = Freshness.UNAVAILABLE
        elif source.name in _LIVE_FOR and current:
            date = source.received_at_ms if source.name == "monde.json" else stamp
            freshness = (
                Freshness.LIVE
                if date is not None and 0 <= self.now - date < _LIVE_FOR[source.name]
                else Freshness.STALE
            )
        return Evidence(
            source.name,
            source.reference,
            KnowledgeStatus.OBSERVED if available else KnowledgeStatus.UNKNOWN,
            freshness,
            Completeness.PARTIAL if available else Completeness.UNAVAILABLE,
            world,
            session if isinstance(session, str) else None,
            dimension if _resource(dimension) else None,
            stamp,
            source.received_at_ms,
            data.get("v", data.get("version_script")),
            capture_id,
        )

    def read(self, source):
        try:
            proof = self.evidence(source)
        except (ValueError, TypeError, RecursionError, OverflowError):
            proof = Evidence(
                source.name,
                source.reference,
                KnowledgeStatus.UNKNOWN,
                Freshness.UNAVAILABLE,
                Completeness.UNAVAILABLE,
            )
        if proof.freshness == Freshness.UNAVAILABLE:
            self.sources.append(proof)
            self.limits.append(
                f"{source.reference}: source indisponible, incohérente, non autorisée ou hors monde."
            )
            return
        start = len(self.rows)
        try:
            getattr(
                self,
                {
                    "etat.json": "state",
                    "inventaire_joueur.json": "inventory",
                    "monde.json": "world",
                    "stockage.json": "storage",
                    "monde_memoire.json": "memory",
                    "filons_mod.json": "veins",
                }[source.name],
            )(source.data, proof)
        except (ValueError, TypeError, KeyError, OverflowError):
            # A malformed frame cannot contribute valid-looking absences or partial totals.
            del self.rows[start:]
            proof = replace(
                proof,
                status=KnowledgeStatus.UNKNOWN,
                freshness=Freshness.UNAVAILABLE,
                completeness=Completeness.UNAVAILABLE,
            )
            self.limits.append(
                f"{source.reference}: relevé invalide, aucune absence ni quantité déduite."
            )
        if source.name == "inventaire_joueur.json" and proof.status == KnowledgeStatus.OBSERVED:
            proof = replace(proof, completeness=Completeness.COMPLETE)
        self.sources.append(proof)

    def state(self, data, proof):
        name = data.get("joueur")
        if not isinstance(name, str) or not name or data.get("dimension") != proof.dimension:
            raise ValueError("Joueur ou dimension invalide.")
        self.rows.append(
            _observation(
                "world",
                "state",
                {
                    key: data[key]
                    for key in ("joueur", "dimension", "x", "y", "z", "serveur_recu")
                    if key in data
                },
                proof,
            )
        )
        if "inventaire" in data:
            self.rows.append(
                _observation(
                    "inventory",
                    "personal",
                    {
                        "player": name,
                        "items": _counts(data["inventaire"]),
                        "includes_backpacks": False,
                        "variant_details": False,
                    },
                    replace(proof, completeness=Completeness.PARTIAL),
                )
            )

    def inventory(self, data, proof):
        name = data.get("joueur")
        if (
            data.get("actif") is not True
            or data.get("synchronise") is not True
            or not isinstance(name, str)
            or not name
            or data.get("dimension") != proof.dimension
        ):
            raise ValueError("Inventaire personnel non synchronisé.")
        self.rows.append(
            _observation(
                "inventory",
                "personal",
                {
                    "player": name,
                    "items": _counts(data.get("inventaire")),
                    "includes_backpacks": True,
                    "variant_details": False,
                },
                replace(proof, completeness=Completeness.COMPLETE),
            )
        )

    def stored_block(self, record, proof, *, machine=False, memory=False):
        if (
            not isinstance(record, Mapping)
            or type(record.get("v")) is not int
            or record["v"] != 2
            or not _resource(record.get("bloc"))
        ):
            raise ValueError("Version ou bloc de mémoire invalide.")
        dimension, coords, key = _location(record)
        date = _timestamp(record.get("t"))
        evidence = replace(
            proof,
            dimension=dimension,
            session=None,
            freshness=Freshness.HISTORICAL,
            observed_at_ms=None if memory else date,
            received_at_ms=date if memory else proof.received_at_ms,
            reference=proof.reference + "#" + key,
            schema_version=2,
        )
        value = {"block": record["bloc"], "name": record.get("nom", record["bloc"])}
        if record.get("absent") is True:
            kind = "absence"
            value["absence_scope"] = "authorized_survey"
            evidence = replace(evidence, completeness=Completeness.COMPLETE)
        elif machine or isinstance(record.get("etat"), Mapping):
            kind = "machine"
            if isinstance(record.get("etat"), Mapping):
                value["state"] = record["etat"]
            # Normalized World memory cannot distinguish an omitted `o` from an empty inventory.
            if record.get("objets"):
                value["items"] = _counts(record["objets"])
        else:
            kind = "storage"
            if "objets" in record and (not memory or record["objets"]):
                value["items"] = _counts(record["objets"])
                evidence = replace(evidence, completeness=Completeness.COMPLETE)
        return _observation(kind, key, value, evidence, coords)

    def storage(self, data, proof):
        containers, machines = data.get("conteneurs"), data.get("machines", ())
        if not isinstance(containers, (list, tuple)) or not isinstance(machines, (list, tuple)):
            raise ValueError("Mémoire des coffres invalide.")
        # Reuse the asymmetric legacy Large-chest replacement before geometric server grouping.
        raw = [_plain(record) for record in containers]
        validated = [(record, self.stored_block(record, proof)) for record in raw]
        for record, _ in validated:
            if _timestamp(record.get("t")) is None:
                raise ValueError("Date de coffre invalide.")
        kept = {id(record) for record in latest_remembered_containers(raw, Storage.twins)}
        for record, observation in validated:
            if id(record) in kept:
                self.rows.append(observation)
            else:
                self.history.append(observation)
        self.rows.extend(self.stored_block(record, proof, machine=True) for record in machines)

    def memory(self, data, proof):
        blocks = data.get("blocs")
        if not isinstance(blocks, Mapping):
            raise ValueError("Mémoire du monde invalide.")
        self.rows.extend(
            self.stored_block(record, proof, memory=True) for record in blocks.values()
        )

    def world(self, data, proof):
        dimension, zone, blocks = data.get("dimension"), data.get("zone"), data.get("blocs")
        if (
            proof.observed_at_ms is None
            or dimension != proof.dimension
            or not _resource(dimension)
            or not isinstance(zone, (list, tuple))
            or len(zone) != 4
            or any(
                type(n) not in (int, float) or not -_MAX_INTEGER <= n <= _MAX_INTEGER or int(n) != n
                for n in zone
            )
            or zone[0] > zone[2]
            or zone[1] > zone[3]
            or not isinstance(blocks, (list, tuple))
        ):
            raise ValueError("Zone serveur invalide.")
        seen = set()
        for index, block in enumerate(blocks):
            normalized = _block_observation(
                _plain(block), dimension, zone, proof.received_at_ms or 0
            )
            if normalized is None or normalized["cle"] in seen:
                raise ValueError("Bloc serveur invalide ou répété.")
            seen.add(normalized["cle"])
            coords = tuple(normalized[key] for key in ("x", "y", "z"))
            value = {"block": normalized["bloc"]}
            if "o" in block:
                value["items"] = _counts(block["o"], pairs=True)
            if "m" in block:
                value["state"] = block["m"]
            kind = (
                "machine"
                if isinstance(block.get("m"), Mapping)
                else "storage"
                if _PHYSICAL_STORAGE.fullmatch(normalized["bloc"])
                else "block"
            )
            completeness = (
                Completeness.COMPLETE
                if kind == "storage" and "o" in block
                else Completeness.PARTIAL
            )
            self.rows.append(
                _observation(
                    kind,
                    normalized["cle"],
                    value,
                    replace(
                        proof,
                        reference=f"{proof.reference}#/blocs/{index}",
                        completeness=completeness,
                    ),
                    coords,
                )
            )
        coverage = data.get("chunks_complets", ())
        if not isinstance(coverage, (list, tuple)) or any(
            not isinstance(chunk, (list, tuple))
            or len(chunk) != 2
            or any(
                type(n) not in (int, float) or not -_MAX_INTEGER <= n <= _MAX_INTEGER or int(n) != n
                for n in chunk
            )
            or not zone[0] <= chunk[0] <= zone[2]
            or not zone[1] <= chunk[1] <= zone[3]
            for chunk in coverage
        ):
            raise ValueError("Couverture serveur invalide.")
        self.rows.append(
            _observation(
                "coverage",
                "server",
                {"complete_chunks": coverage, "zone": zone, "seen": tuple(sorted(seen))},
                replace(proof, completeness=Completeness.PARTIAL),
            )
        )
        players = data.get("joueurs", ())
        if not isinstance(players, (list, tuple)):
            raise ValueError("Positions des joueurs invalides.")
        for index, player in enumerate(players):
            dimension, coords, _ = _location(player)
            if not isinstance(player.get("nom"), str) or not player["nom"]:
                raise ValueError("Nom de joueur invalide.")
            self.rows.append(
                _observation(
                    "player",
                    player["nom"],
                    {"name": player["nom"]},
                    replace(
                        proof, dimension=dimension, reference=f"{proof.reference}#/joueurs/{index}"
                    ),
                    coords,
                )
            )

    def veins(self, data, proof):
        if (
            type(data.get("v")) is not int
            or data["v"] != 1
            or data.get("source") != "gregtech"
            or not isinstance(data.get("gisements"), (list, tuple))
            or not isinstance(data.get("blocs"), (list, tuple))
        ):
            raise ValueError("Journal GregTech invalide.")
        # The export timestamp is not a new observation of each previously remembered ore.
        proof = replace(proof, freshness=Freshness.HISTORICAL, observed_at_ms=None, session=None)
        counts, dates, positions = defaultdict(lambda: [0, 0]), defaultdict(list), set()
        for block in data["blocs"]:
            dimension, coords, key = _location(block)
            if (
                key in positions
                or not _resource(block.get("bloc"))
                or not isinstance(block.get("filon"), str)
                or type(block.get("mine")) is not bool
            ):
                raise ValueError("Bloc de filon invalide ou répété.")
            positions.add(key)
            counts[(block["filon"], dimension)][int(block.get("mine") is True)] += 1
            date = _timestamp(block.get("t"))
            if date is not None:
                dates[(block["filon"], dimension)].append(date)
        deposits = set()
        for index, vein in enumerate(data["gisements"]):
            dimension, coords, _ = _location(vein)
            key = vein.get("id")
            if (
                not isinstance(key, str)
                or not key
                or key in deposits
                or not isinstance(vein.get("composition"), (list, tuple))
                or any(not _resource(item) for item in vein["composition"])
            ):
                raise ValueError("Gisement GregTech invalide.")
            deposits.add(key)
            observed, mined = counts[(key, dimension)]
            value = {
                "definition": vein.get("definition"),
                "composition": vein["composition"],
                "observed_blocks": observed,
                "mined_blocks": mined,
                "remaining_total": None,
            }
            if dates[(key, dimension)]:
                value["latest_block_observed_at_ms"] = max(dates[(key, dimension)])
            self.rows.append(
                _observation(
                    "vein",
                    key,
                    value,
                    replace(
                        proof,
                        status=KnowledgeStatus.DERIVED,
                        dimension=dimension,
                        reference=f"{proof.reference}#/gisements/{index}",
                    ),
                    coords,
                )
            )
            if vein.get("epuise_manuel") is True:
                self.rows.append(
                    _observation(
                        "vein_exhaustion",
                        key,
                        {"exhausted": True},
                        replace(
                            proof,
                            status=KnowledgeStatus.USER_CONFIRMED,
                            dimension=dimension,
                            reference=f"{proof.reference}#/gisements/{index}/epuise_manuel",
                        ),
                        coords,
                    )
                )


def _priority(observation):
    proof = observation.evidence[0]
    rank = {
        Freshness.UNAVAILABLE: 0,
        Freshness.HISTORICAL: 1,
        Freshness.STALE: 2,
        Freshness.LIVE: 3,
    }[proof.freshness]
    native = observation.kind == "inventory" and proof.source == "inventaire_joueur.json"
    date = (
        proof.received_at_ms
        if proof.source in {"monde.json", "monde_memoire.json"}
        else proof.observed_at_ms
    )
    return rank, native, date if date is not None else -1, proof.source == "monde.json"


def _generation(proof):
    # One authorized server packet may also describe players in other dimensions.
    return (
        proof.source,
        proof.world,
        proof.session,
        proof.observed_at_ms,
        proof.received_at_ms,
        proof.capture_id,
    )


def _latest_server_frame(rows, limitations, sources):
    """Successive complete files are separate surveys, never simultaneous stock locations."""
    current = [
        row
        for row in rows
        if row.kind == "coverage" and row.evidence[0].freshness == Freshness.LIVE
    ]
    if not current:
        return rows

    def clock(proof):
        return (
            proof.received_at_ms if proof.received_at_ms is not None else -1,
            proof.observed_at_ms if proof.observed_at_ms is not None else -1,
        )

    latest = max((row.evidence[0] for row in current), key=clock)
    tied = [row.evidence[0] for row in current if clock(row.evidence[0]) == clock(latest)]
    conflict = len({proof.capture_id for proof in tied}) > 1
    if conflict:
        limitations.append(
            "Captures serveur différentes aux mêmes dates : stock et état courant inconnus."
        )

    def contextualize(proof):
        if proof.source != "monde.json" or proof.freshness != Freshness.LIVE:
            return proof
        if clock(proof) == clock(latest) and conflict:
            return replace(
                proof,
                status=KnowledgeStatus.UNKNOWN,
                freshness=Freshness.STALE,
                completeness=Completeness.UNKNOWN,
            )
        if _generation(proof) != _generation(latest):
            return replace(proof, freshness=Freshness.HISTORICAL)
        return proof

    sources[:] = [contextualize(proof) for proof in sources]
    return [
        replace(row, evidence=tuple(contextualize(proof) for proof in row.evidence)) for row in rows
    ]


def _merge(rows, limitations):
    groups = defaultdict(dict)
    for observation in rows:
        group = (
            "physical"
            if observation.kind in {"machine", "storage", "block", "absence"}
            else observation.kind
        )
        groups[(group, observation.key)][observation.id] = observation
    selected, history = [], []
    for group in groups.values():
        candidates = list(group.values())
        best = max(map(_priority, candidates))
        winners = [row for row in candidates if _priority(row) == best]
        first = winners[0]
        if any(row.value != first.value or row.kind != first.kind for row in winners[1:]):
            history.extend(candidates)
            limitations.append(
                f"{first.key}: relevés contradictoires de même fraîcheur et date ; quantité courante inconnue."
            )
            selected.append(
                replace(
                    first,
                    evidence=tuple(
                        replace(
                            proof,
                            status=KnowledgeStatus.UNKNOWN,
                            freshness=Freshness.STALE,
                            completeness=Completeness.UNKNOWN,
                        )
                        for row in winners
                        for proof in row.evidence
                    ),
                )
            )
        else:
            selected.append(
                replace(first, evidence=tuple(proof for row in winners for proof in row.evidence))
            )
            history.extend(row for row in candidates if _priority(row) != best)
    return selected, history


def _covered_absences(rows):
    """Missing from a completed authorized survey; not proof a physical block was removed.

    Locked/FTB-protected blocks may be omitted while their chunk is marked complete.
    """
    covered = {}
    for row in rows:
        proof = row.evidence[0]
        if row.kind != "coverage" or proof.freshness != Freshness.LIVE:
            continue
        for x, z in row.value["complete_chunks"]:
            key = proof.dimension, x, z
            previous = covered.get(key)
            if previous is None or _priority(row) > _priority(previous):
                covered[key] = row
    result = list(rows)
    for row in rows:
        if row.kind not in {"storage", "machine", "block"} or row.position is None:
            continue
        proof = row.evidence[0]
        coverage = covered.get((proof.dimension, row.position[0] // 16, row.position[2] // 16))
        if coverage is None or row.key in coverage.value["seen"]:
            continue
        stamp = (
            proof.received_at_ms
            if proof.source in {"monde.json", "monde_memoire.json"}
            else proof.observed_at_ms
        )
        received = coverage.evidence[0].received_at_ms
        if stamp is not None and received is not None and stamp > received:
            continue
        evidence = replace(
            coverage.evidence[0],
            completeness=Completeness.COMPLETE,
            reference=coverage.evidence[0].reference + "#absence/" + row.key,
        )
        result.append(
            _observation(
                "absence",
                row.key,
                {"block": row.value["block"], "absence_scope": "authorized_survey"},
                evidence,
                row.position,
            )
        )
    return result


def _deduplicate_storage(rows):
    raw, by_key = [], {}
    for observation in rows:
        if observation.kind != "storage" or "items" not in observation.value:
            continue
        proof = observation.evidence[0]
        raw.append(
            {
                "bloc": observation.value["block"],
                "dimension": proof.dimension,
                **dict(zip(("x", "y", "z"), observation.position)),
                "objets": [
                    {"item": item, "n": count} for item, count in observation.value["items"].items()
                ],
                "key": observation.key,
            }
        )
        by_key[observation.key] = observation
    # A chain/grid of identical neighboring chests has several possible pairings. The
    # legacy greedy grouping depends on scan order there, so V2 makes no stock allocation.
    located = {(chest["dimension"], chest["x"], chest["y"], chest["z"]): chest for chest in raw}
    neighbors = defaultdict(set)
    for here, chest in located.items():
        if chest["bloc"] not in SHARED_CHESTS:
            continue
        dim, x, y, z = here
        for dx, dz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            other = (dim, x + dx, y, z + dz)
            candidate = located.get(other)
            if (
                candidate is not None
                and candidate["bloc"] == chest["bloc"]
                and inventory_key(candidate) == inventory_key(chest)
            ):
                neighbors[here].add(other)
    ambiguous = set()
    pending = [here for here, adjacent in neighbors.items() if len(adjacent) > 1]
    while pending:
        here = pending.pop()
        if here not in ambiguous:
            ambiguous.add(here)
            pending.extend(neighbors[here] - ambiguous)
    groups = defaultdict(list)
    simple = [chest for here, chest in sorted(located.items()) if here not in ambiguous]
    for chest, key in grouped_containers(simple):
        groups[key].append(by_key[chest["key"]])
    result = [row for row in rows if row.key not in by_key or row.kind != "storage"]
    for here in sorted(ambiguous):
        row = by_key[located[here]["key"]]
        result.append(replace(row, value={**row.value, "identity_ambiguous": True}))
    for key, group in groups.items():
        first = group[0]
        if len(group) == 1:
            result.append(first)
            continue
        # Physical positions remain inspectable; shared identity is an inference, not blockstate.
        evidence = tuple(proof for row in group for proof in row.evidence)
        inferred = replace(
            first.evidence[0], status=KnowledgeStatus.DERIVED, reference="grouped_containers:" + key
        )
        identity = hashlib.sha256("|".join(row.id for row in group).encode("ascii")).hexdigest()
        result.append(
            Observation(
                identity,
                "storage",
                key,
                {
                    **first.value,
                    "positions": tuple(row.position for row in group),
                    "shared_identity_inferred": True,
                },
                (*evidence, inferred),
                first.position,
            )
        )
    return result


def build_knowledge(
    scope, captured_at_ms, sources, *, history_world=None, stock_frame_coherent=False
):
    """Adapt existing files captured by the caller. No source absence implies world absence."""
    if (
        scope is not None
        and not _scope_valid(scope)
        or not _number(captured_at_ms)
        or history_world is not None
        and (not isinstance(history_world, str) or not _WORLD.fullmatch(history_world))
        or type(stock_frame_coherent) is not bool
    ):
        raise ValueError("Portée ou date de capture invalide.")
    builder = _Builder(scope, captured_at_ms, history_world)
    for source in sources:
        if not isinstance(source, SourceSnapshot):
            raise ValueError("Source capturée requise.")
        builder.read(source)
    states = [
        row
        for row in builder.rows
        if row.kind == "world" and row.evidence[0].freshness == Freshness.LIVE
    ]
    if states:
        name = max(states, key=_priority).value["joueur"]
        incompatible = [
            row for row in builder.rows if row.kind == "inventory" and row.value["player"] != name
        ]
        if incompatible:
            builder.limits.append(
                "Inventaire d'un autre joueur exclu de la disponibilité personnelle."
            )
            builder.rows = [row for row in builder.rows if row not in incompatible]
    rows, history = _merge(
        _covered_absences(_latest_server_frame(builder.rows, builder.limits, builder.sources)),
        builder.limits,
    )
    rows = _deduplicate_storage(rows)
    limits = [
        *builder.limits,
        "Quantités par identifiant d'objet : variantes NBT et fluides non attestés.",
        "La couverture du monde reste partielle ; absence de relevé ne signifie pas stock nul.",
    ]
    if scope is None:
        limits.append(
            "Session active non attestée : données historiques uniquement, disponibilité courante inconnue."
        )
    if not stock_frame_coherent:
        limits.append(
            "Pas de révision commune des transferts : inventaire personnel prioritaire, sinon seul le dernier relevé serveur est compté."
        )
    if any(row.value.get("identity_ambiguous") is True for row in rows):
        limits.append(
            "Coffres adjacents avec plusieurs jumelages possibles : positions conservées, quantités exclues de l'allocation."
        )
    return KnowledgeSnapshot(
        scope,
        captured_at_ms,
        tuple(rows),
        tuple(dict.fromkeys(limits)),
        tuple([*builder.history, *history]),
        tuple(builder.sources),
        stock_frame_coherent,
    )
