"""Measure offline catalogue, knowledge and planning on a reproducible large fixture."""

import json
import statistics
import tempfile
import time
import tracemalloc
from pathlib import Path

from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.production.analysis import analyse_observed
from monifactory_assistant.production.catalog import CatalogueReader, catalogue_document
from monifactory_assistant.production.knowledge import SourceSnapshot, build_knowledge
from monifactory_assistant.production.recipes import (
    Amount,
    Catalog,
    Ingredient,
    Output,
    Recipe,
    Resource,
)

DEPTH, UNRELATED, CHESTS, TRIALS = 24, 2_000, 600, 21
NOW = 1_790_000_000_000
SCOPE = WorldScope("m1_" + "f" * 64, "f" * 32, "minecraft:overworld")


def fixture():
    chain = tuple(Resource("item", f"fixture:material_{i}", "item") for i in range(DEPTH + 1))
    recipes = [
        Recipe(
            f"fixture:step_{i}",
            "fixture:category",
            (Ingredient((Amount(source, 1),)),),
            (Output(Amount(result, 1)),),
        )
        for i, (source, result) in enumerate(zip(chain, chain[1:]))
    ]
    recipes.extend(
        Recipe(
            f"fixture:unrelated_{i}",
            "fixture:category",
            (),
            (Output(Amount(Resource("item", f"fixture:other_{i}", "item"), 1)),),
        )
        for i in range(UNRELATED)
    )
    source = SourceSnapshot(
        "monde.json",
        {
            **SCOPE.fields(),
            "dimension": SCOPE.dimension,
            "t": NOW - 200,
            "zone": [0, 0, CHESTS - 1, 0],
            "blocs": [
                {"p": [i * 16, 64, 0], "b": "minecraft:barrel", "o": [[chain[0].id, 1]]}
                for i in range(CHESTS)
            ],
        },
        received_at_ms=NOW - 100,
    )
    return Catalog(1, tuple(recipes)), source, Amount(chain[-1], CHESTS), chain[0]


def measure(call):
    timings, cpu_timings = [], []
    for _ in range(TRIALS):
        wall, cpu = time.perf_counter_ns(), time.process_time_ns()
        call()
        cpu_timings.append((time.process_time_ns() - cpu) / 1_000_000)
        timings.append((time.perf_counter_ns() - wall) / 1_000_000)
    tracemalloc.start()
    try:
        result = call()
        peak = tracemalloc.get_traced_memory()[1]
    finally:
        tracemalloc.stop()
    return result, {
        "median_ms": round(statistics.median(timings), 3),
        "p95_ms": round(sorted(timings)[19], 3),
        "cpu_median_ms": round(statistics.median(cpu_timings), 3),
        "peak_alloc_mib": round(peak / 1_048_576, 3),
    }


def main():
    catalog, source, target, material = fixture()
    with tempfile.TemporaryDirectory() as temporary:
        path = Path(temporary) / "catalogue.json"
        atomic_json(
            path, catalogue_document("benchmark", catalog.to_dict()), backup=False, compact=True
        )
        view, catalogue_stats = measure(lambda: CatalogueReader(path, "benchmark").refresh())
    knowledge, knowledge_stats = measure(lambda: build_knowledge(SCOPE, NOW, (source,)))
    outcome, analysis_stats = measure(
        lambda: analyse_observed(view, knowledge, target, id_countable=(material,))
    )
    assert view.catalog == catalog and outcome.plan.status == "complete"
    assert len(outcome.plan.steps) == DEPTH and knowledge.availability()[material.id] == CHESTS
    print(
        json.dumps(
            {
                "recipes": len(catalog.recipes),
                "chests": CHESTS,
                "steps": DEPTH,
                "trials": TRIALS,
                "catalogue": catalogue_stats,
                "knowledge": knowledge_stats,
                "analysis": analysis_stats,
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
