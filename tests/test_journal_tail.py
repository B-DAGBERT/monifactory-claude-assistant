"""Recent context tolerates incomplete records without rewriting history."""

import json
import tempfile
import unittest
from pathlib import Path

from monifactory_assistant.application.game import GameController


class JournalTailTests(unittest.TestCase):
    def setUp(self):
        work = tempfile.TemporaryDirectory()
        self.addCleanup(work.cleanup)
        self.game = GameController()
        self.game.journal_file = Path(work.name) / "journal.jsonl"

    @staticmethod
    def record(index):
        return {"t": 1_780_000_000 + index, "type": "stock", "texte": f"Minerai {index}"}

    def test_returns_last_records_in_order_with_crlf_and_without_final_newline(self):
        records = [self.record(i) for i in range(400)]
        raw = "\r\n".join(json.dumps(row, ensure_ascii=False) for row in records).encode("utf-8")
        self.assertGreater(len(raw), 8192)
        self.game.journal_file.write_bytes(raw)
        self.assertEqual(self.game.journal_tail(25), records[-25:])
        self.assertEqual(self.game.journal_tail(1000), records)
        self.assertEqual(self.game.journal_file.read_bytes(), raw)

    def test_handles_utf8_and_a_single_record_longer_than_a_read_block(self):
        row = self.record(1)
        row["texte"] = "é" * 7000 + "\u2028fin"
        raw = json.dumps(row, ensure_ascii=False).encode("utf-8") + b"\n"
        self.game.journal_file.write_bytes(raw)
        self.assertEqual(self.game.journal_tail(1), [row])
        self.assertIn(row["texte"], self.game.describe_journal(1))

    def test_recovers_valid_context_near_corrupt_or_interrupted_rows(self):
        good = json.dumps(self.record(1)).encode()
        raw = good + b'\nnull\n{}\n{"t":"date invalide"}\n\xff\n{"t":'
        self.game.journal_file.write_bytes(raw)
        self.assertIn("Minerai 1", self.game.describe_journal())
        self.assertNotIn("date invalide", self.game.describe_journal())
        self.assertEqual(self.game.journal_file.read_bytes(), raw)

    def test_handles_missing_and_empty_history(self):
        self.assertEqual(self.game.journal_tail(), [])
        self.game.journal_file.write_bytes(b"")
        self.assertEqual(self.game.describe_journal(), "")
