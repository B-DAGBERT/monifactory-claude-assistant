"""Measure the same recent-context read with short and long synthetic journals."""

import json
import statistics
import tempfile
import time
import tracemalloc
from pathlib import Path

from monifactory_assistant.application.game import GameController

START = 1_780_000_000
COUNT = 25


def run():
    results = []
    with tempfile.TemporaryDirectory(prefix="monifactory-journal-") as work:
        game = GameController()
        game.journal_file = Path(work) / "journal.jsonl"
        for records in (40, 1000, 100_000):
            with game.journal_file.open("w", encoding="utf-8") as stream:
                for index in range(records):
                    row = {
                        "t": START + index,
                        "type": "coffre",
                        "texte": "Inventaire observé " + "A" * 125,
                    }
                    stream.write(json.dumps(row, ensure_ascii=False) + "\n")
            expected = list(range(START + records - COUNT, START + records))
            times = []
            for _ in range(21):
                before = time.perf_counter_ns()
                tail = game.journal_tail(COUNT)
                times.append((time.perf_counter_ns() - before) / 1_000_000)
                assert [row["t"] for row in tail] == expected
            tracemalloc.start()
            game.journal_tail(COUNT)
            _, peak = tracemalloc.get_traced_memory()
            tracemalloc.stop()
            results.append(
                {
                    "records": records,
                    "bytes": game.journal_file.stat().st_size,
                    "median_ms": statistics.median(times),
                    "peak_bytes": peak,
                }
            )
    return results


if __name__ == "__main__":
    print(json.dumps(run(), indent=2))
