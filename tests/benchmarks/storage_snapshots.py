"""Reproduce ten minutes of observations and EMI lookups with synthetic data."""

import json
import statistics
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import monifactory_assistant.persistence as persistence
from monifactory_assistant.game.emi_search import RecipeChestSearch, _matching_chests
from monifactory_assistant.game.storage import StorageMonitor
from monifactory_assistant.game.world import World


def percentile(xs, p):
    return sorted(xs)[max(0, int(len(xs) * p) - 1)]


def block(i, machine=False):
    return {
        "p": [i * 3, 64, 0],
        "b": "gtceu:lv_bender" if machine else "minecraft:barrel",
        "o": [[f"test:item_{(i * 12 + j) % 300}", 64] for j in range(12)],
        **({"m": {"s": "IDLE"}} if machine else {}),
    }


def run_world():
    with tempfile.TemporaryDirectory(prefix="monifactory-lot4-") as directory:
        p = Path(directory)
        req = {
            "actif": True,
            "n": 1,
            "fin": 600,
            "monde": "synthetic",
            "dimension": "minecraft:overworld",
            "objets": ["test:item_10"],
        }
        (p / "recherche_emi.json").write_text(json.dumps(req))

        def read(name):
            try:
                return json.loads((p / name).read_text())
            except (OSError, ValueError):
                return None

        bridge = SimpleNamespace(dir=p, read=read)
        state = {"x": 0, "y": 64, "z": 0, "dimension": "minecraft:overworld"}
        data = {
            "t": 1_800_000_000_000,
            "dimension": state["dimension"],
            "zone": [0, 0, 200, 1],
            "blocs": [block(i, i >= 500) for i in range(600)],
            "chunks_complets": [],
            "joueurs": [
                {"nom": "Alice", "x": 15, "y": 65, "z": 10, "dimension": state["dimension"]}
            ],
            "erreurs": [],
        }
        written = []
        original = persistence._replace_bytes

        def write(path, raw, mode):
            written.append((len(raw), path.suffix))
            return original(path, raw, mode)

        with (
            patch("monifactory_assistant.game.world.WORLD_FILE", p / "monde_memoire.json"),
            patch.object(persistence, "_replace_bytes", side_effect=write),
        ):
            world = World()
            monitor = StorageMonitor()
            search = RecipeChestSearch()
            times = []
            export_changes = 0
            export_before = None
            with (
                patch.object(world, "merged", wraps=world.merged) as merge,
                patch(
                    "monifactory_assistant.game.emi_search._matching_chests", wraps=_matching_chests
                ) as matches,
            ):
                cpu0 = time.process_time()
                wall0 = time.perf_counter()
                for i in range(200):
                    data["t"] = 1_800_000_000_000 + i * 3000
                    t0 = time.perf_counter()
                    assert world.update(data)
                    storage = monitor.refresh(bridge, world)
                    assert search.view(bridge, storage, state, i * 3)
                    key = storage.export_key()
                    if key != export_before:
                        export_changes += 1
                        export_before = key
                    times.append((time.perf_counter() - t0) * 1000)
                metrics = {
                    "scenario": "600 blocks,500 containers,100 machines,12 slots,200 snapshots/10 simulated minutes",
                    "logical_snapshots": 200,
                    "atomic_replacements": len(written),
                    "bytes_written": sum(x[0] for x in written),
                    "world_merges": merge.call_count,
                    "search_rebuilds": matches.call_count,
                    "text_export_revisions": export_changes,
                    "wall_ms": (time.perf_counter() - wall0) * 1000,
                    "cpu_ms": (time.process_time() - cpu0) * 1000,
                    "frame_mean_ms": statistics.mean(times),
                    "frame_p95_ms": percentile(times, 0.95),
                    "frame_max_ms": max(times),
                }
            read_started = time.perf_counter()
            reboot = World()
            metrics["restart_read_ms"] = (time.perf_counter() - read_started) * 1000
            assert len(reboot.blocks) == 600 and all(
                b["t"] == data["t"] for b in reboot.blocks.values()
            )
            metrics["restart_records"] = len(reboot.blocks)
            metrics["restart_dates_exact"] = True
            return metrics


if __name__ == "__main__":
    print(json.dumps(run_world(), indent=2))
