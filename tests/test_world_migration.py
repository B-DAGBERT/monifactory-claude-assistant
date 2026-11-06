"""World migration qualification with independent synthetic installations only."""

import contextlib
import io
import json
import shutil
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant import data_backup as backup
from monifactory_assistant import world_migration as m
from monifactory_assistant.commands.migrate_world import main
from monifactory_assistant.game.world_scope import read_activation

A = "m1_" + "a" * 64
B = "m1_" + "b" * 64


class WorldMigrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name).resolve()
        self.python = self.root / "python"
        self.game = self.root / "game"
        self.python.mkdir()
        self.game.mkdir()
        self.receipt = self.root / "receipt.json"
        self.preparation = self.root / "prepared"
        self.identity = {
            "v": 1,
            "monde": A,
            "session": "1" * 32,
            "dimension": "minecraft:overworld",
            "t": time.time() * 1000,
        }
        self.put(self.game, "identite_monde.json", self.identity)
        self.put(
            self.game,
            "stockage.json",
            {
                "conteneurs": [
                    {
                        "cle": "minecraft:overworld|1|2|3",
                        "dimension": "minecraft:overworld",
                        "x": 1,
                        "y": 2,
                        "z": 3,
                        "t": 100,
                        "objets": [{"item": "gtceu:iron_ingot", "n": 17}],
                    },
                    {
                        "cle": "minecraft:the_nether|1|2|3",
                        "dimension": "minecraft:the_nether",
                        "x": 1,
                        "y": 2,
                        "z": 3,
                        "t": 200,
                        "objets": [],
                    },
                ],
                "machines": [{"bloc": "gtceu:bender"}],
            },
        )
        self.put(self.game, "filons.json", {"blocs": [{"bloc": "gtceu:coal_ore", "mine": True}]})
        self.put(
            self.game,
            "filons_gregtech/old-cache.json",
            {"monde": "1" * 24, "gisements": [{"id": "vein"}]},
        )
        self.put(self.game, "monde.json", {"joueurs": [{"nom": "Other", "x": 25, "z": 10}]})
        self.put(self.game, "commande.json", {"action": "old command"})
        self.put(self.game, "vue_mod.json", {"conversation": [{"texte": "history"}]})
        self.put(self.game, "unknown.bin", b"opaque\x00\xff")
        self.put(
            self.python,
            "plan_ia.json",
            {
                "objectif_en_pause": True,
                "projet_actif": "p",
                "projets": [{"id": "p", "nom": "Personal", "etat": {"objectif_en_pause": True}}],
                "plan": {"objectif": "Preserve", "besoins": {"gtceu:iron_ingot": 10}},
                "obtenus": {"gtceu:iron_ingot": 3},
            },
        )
        self.put(self.python, "plan_ia.json.avant-projets", {"plan": {"objectif": "Old"}})
        self.put(
            self.python,
            "monde_memoire.json",
            {"blocs": {"overworld|1|2|3": {"objets": [], "etat": {"s": "WORKING"}}}},
        )
        self.put(self.python, "lieux.json", {"home": {"dimension": "minecraft:overworld", "x": 1}})
        self.put(self.python, "journal.jsonl", b'{"texte":"history"}\n{"incomplete":')
        self.put(self.python, "session.json", {"thread_id": "codex-unique"})
        self.put(self.python, "session_claude.json", {"session_id": "claude-unique"})
        self.put(self.python, "assistant.local.json", {"ai_provider": "codex"})
        m.identify(self.python, self.game, self.receipt)

    def tearDown(self):
        self.tmp.cleanup()

    def put(self, root, name, value):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value if isinstance(value, bytes) else json.dumps(value).encode())
        return path

    def prepare(self, **kwargs):
        return m.prepare(
            self.python, self.game, self.receipt, self.preparation, guard=lambda: None, **kwargs
        )

    def confirm(self):
        return m.confirm(self.preparation, A, single_server=True, guard=lambda: None)

    def activate(self, **kwargs):
        return m.activate(self.preparation, guard=lambda: None, **kwargs)

    def test_complete_copy_confirmation_activation_and_rollback(self):
        originals, _ = backup.inventory(self.python, self.game)
        plan = self.prepare()
        self.assertEqual([], plan["contradictions"])
        self.assertEqual(2, plan["comptages"]["kubejs-assistant/stockage.json"]["conteneurs"])
        self.assertFalse((self.game / m.MARKER).exists())
        self.assertEqual(plan, self.prepare(), "preparation idempotent")
        confirmation = self.confirm()
        self.assertEqual(confirmation, self.confirm())
        marker = self.activate()
        self.assertTrue(marker["actif"])
        self.assertEqual("isolated", read_activation(self.game, self.python))
        self.assertTrue(self.activate()["deja_active"])
        for entry in originals["entries"]:
            if entry["kind"] != "file":
                continue
            root = self.python if entry["root"] == "python" else self.game
            self.assertEqual(
                (root / entry["path"]).read_bytes(),
                (self.preparation / "restauration" / entry["root"] / entry["path"]).read_bytes(),
            )
        for root in (self.python, self.game):
            for entry in plan["copies"]:
                expected_root = self.python if entry["root"] == "python" else self.game
                if expected_root != root:
                    continue
                self.assertEqual(
                    (root / entry["path"]).read_bytes(),
                    (root / "mondes" / A / entry["path"]).read_bytes(),
                )
        self.assertFalse((self.python / "mondes" / A / "session.json").exists())
        self.assertFalse((self.game / "mondes" / A / "commande.json").exists())
        self.assertFalse((self.game / "mondes" / A / "monde.json").exists())
        self.assertFalse((self.game / "mondes" / A / "filons_gregtech").exists())
        inactive = m.deactivate(self.python, self.game, guard=lambda: None)
        self.assertFalse(inactive["actif"])
        self.assertEqual(inactive, m.deactivate(self.python, self.game, guard=lambda: None))
        self.assertTrue((self.game / "mondes" / A / "stockage.json").exists())
        with self.assertRaises(m.MigrationError):
            self.activate()

    def test_identify_requires_recent_technical_identity(self):
        for invalid in (
            {"t": 0},
            {"session": "missing"},
            {"monde": "minecraft:overworld"},
            {"actif": False},
            {"t": time.time() * 1000 + 60000},
        ):
            with self.subTest(invalid=invalid):
                self.put(self.game, "identite_monde.json", {**self.identity, **invalid})
                with self.assertRaises(m.MigrationError):
                    m.identify(self.python, self.game, self.root / "invalid.json")
        self.assertFalse((self.root / "invalid.json").exists())

    def test_confirmation_is_local_and_explicit(self):
        self.prepare()
        with self.assertRaises(m.MigrationError):
            m.confirm(self.preparation, A, guard=lambda: None)
        with self.assertRaises(m.MigrationError):
            m.confirm(self.preparation, B, single_server=True, guard=lambda: None)
        with self.assertRaises(OSError):
            self.activate()
        self.assertFalse((self.game / m.MARKER).exists())
        second = self.root / "second"
        second.mkdir()
        with self.assertRaises(m.MigrationError):
            m.prepare(second, self.game, self.receipt, self.root / "other", guard=lambda: None)

    def test_other_server_detected_since_receipt(self):
        self.put(self.game, "identite_monde.json", {**self.identity, "monde": B})
        with self.assertRaises(m.MigrationError):
            self.prepare()
        self.assertFalse(self.preparation.exists())

    def test_contradictory_observations_are_archived_not_assigned(self):
        self.put(self.game, "stockage.json", {"conteneurs": [{"monde": B, "x": 1}]})
        original = (self.game / "stockage.json").read_bytes()
        plan = self.prepare()
        self.assertTrue(plan["contradictions"])
        self.assertFalse((self.preparation / "copie/kubejs-assistant/stockage.json").exists())
        self.assertEqual(
            original,
            (self.preparation / "restauration/kubejs-assistant/stockage.json").read_bytes(),
        )
        with self.assertRaises(m.MigrationError):
            self.confirm()
        self.assertFalse((self.game / m.MARKER).exists())

    def test_recovery_copy_from_another_world_is_detected(self):
        self.put(self.python, "monde_memoire.json.bak", {"monde": B, "blocs": {}})
        self.assertTrue(self.prepare()["contradictions"])
        with self.assertRaises(m.MigrationError):
            self.confirm()

    def test_foreign_jsonl_records_block_assignment_without_rewriting_log(self):
        for n, name in enumerate(("journal.jsonl", "journal.jsonl.bak", ".journal.jsonl-test.tmp")):
            with self.subTest(name=name):
                self.preparation = self.root / f"foreign-log-{n}"
                raw = (
                    b'{"texte":"same world"}\n'
                    + json.dumps({"monde_actif": B, "texte": "other server"}).encode()
                    + b'\n{"incomplete":'
                )
                self.put(self.python, name, raw)
                plan = self.prepare()
                self.assertTrue(
                    any(e["path"] == name and e["mondes"] == [B] for e in plan["contradictions"])
                )
                self.assertFalse((self.preparation / "copie/python" / name).exists())
                self.assertEqual(
                    raw, (self.preparation / "restauration/python" / name).read_bytes()
                )
                with self.assertRaises(m.MigrationError):
                    self.confirm()
                self.assertFalse((self.game / m.MARKER).exists())

    def test_multiple_gregtech_worlds_are_not_guessed(self):
        self.put(self.game, "filons_gregtech/other.json", {"monde": "2" * 24, "gisements": []})
        self.assertTrue(self.prepare()["contradictions"])
        with self.assertRaises(m.MigrationError):
            self.confirm()
        self.assertTrue(
            (self.preparation / "archive/kubejs-assistant/filons_gregtech/other.json").exists()
        )

    def test_invalid_empty_missing_and_unknown_files_preserved(self):
        (self.python / "lieux.json").unlink()
        self.put(self.python, "monde_memoire.json", b"")
        self.put(self.python, "monde_memoire.json.bak", {"blocs": {}})
        self.put(self.python, "unknown.json", b"not json\x00")
        plan = self.prepare()
        self.assertTrue(plan["historique"])
        self.assertEqual(
            b"", (self.preparation / "restauration/python/monde_memoire.json").read_bytes()
        )
        self.assertEqual(
            b"not json\x00", (self.preparation / "restauration/python/unknown.json").read_bytes()
        )
        self.assertTrue((self.preparation / "copie/python/monde_memoire.json.bak").exists())
        self.assertTrue(plan["contradictions"])
        with self.assertRaisesRegex(m.MigrationError, "Migration incomplète"):
            self.confirm()
        self.assertFalse((self.game / m.MARKER).exists())
        self.assertEqual(b"", (self.python / "monde_memoire.json").read_bytes())

    def test_unreadable_or_unknown_storage_never_silently_activates_empty_partition(self):
        for n, invalid in enumerate((b"{broken", b"[]", b"null", b'"unknown"')):
            with self.subTest(invalid=invalid):
                self.preparation = self.root / f"invalid-storage-{n}"
                self.put(self.game, "stockage.json", invalid)
                self.put(self.game, "stockage.json.bak", {"conteneurs": [{"x": 1}]})
                plan = self.prepare()
                problems = [e for e in plan["contradictions"] if e["path"] == "stockage.json"]
                self.assertEqual(1, len(problems))
                self.assertIn(problems[0], plan["historique"])
                self.assertTrue(
                    (self.preparation / "copie/kubejs-assistant/stockage.json.bak").exists()
                )
                self.assertEqual(
                    invalid,
                    (self.preparation / "restauration/kubejs-assistant/stockage.json").read_bytes(),
                )
                with self.assertRaisesRegex(m.MigrationError, "Migration incomplète"):
                    self.confirm()
                self.assertFalse((self.game / m.MARKER).exists())
                self.assertEqual(invalid, (self.game / "stockage.json").read_bytes())

    def test_restore_or_prepared_copy_tampered_blocks_activation(self):
        self.prepare()
        self.confirm()
        for relative in (
            "restauration/python/session.json",
            "copie/kubejs-assistant/stockage.json",
        ):
            path = self.preparation / relative
            raw = path.read_bytes()
            path.write_bytes(b"changed")
            with self.assertRaises(m.MigrationError):
                self.activate()
            self.assertFalse((self.game / m.MARKER).exists())
            path.write_bytes(raw)

    def test_missing_restore_proof_blocks_activation(self):
        self.prepare()
        self.confirm()
        (self.preparation / "preuve_restauration.json").unlink()
        with self.assertRaises(OSError):
            self.activate()
        self.assertFalse((self.game / m.MARKER).exists())

    def test_sources_changed_after_preparation_are_not_overwritten(self):
        self.prepare()
        self.confirm()
        self.put(self.game, "stockage.json", {"conteneurs": [{"item": "new"}]})
        raw = (self.game / "stockage.json").read_bytes()
        with self.assertRaises(m.MigrationError):
            self.activate()
        self.assertEqual(raw, (self.game / "stockage.json").read_bytes())
        self.assertFalse((self.game / m.MARKER).exists())

    def test_interrupted_prepare_has_no_effect_and_retries(self):
        before, _ = backup.inventory(self.python, self.game)
        for phase in ("backed_up", "restored", "prepared"):
            target = self.root / phase

            def stop(current):
                if current == phase:
                    raise RuntimeError("crash")

            with self.assertRaises(RuntimeError):
                m.prepare(
                    self.python,
                    self.game,
                    self.receipt,
                    target,
                    guard=lambda: None,
                    checkpoint=stop,
                )
            self.assertFalse(target.exists())
            self.assertFalse((self.game / m.MARKER).exists())
            self.assertEqual(before, backup.inventory(self.python, self.game)[0])
            m.prepare(self.python, self.game, self.receipt, target, guard=lambda: None)

    def test_interrupted_activation_resumes_without_duplication(self):
        self.prepare()
        self.confirm()

        def stop(phase):
            if phase == "python_published":
                raise RuntimeError("crash")

        with self.assertRaises(RuntimeError):
            self.activate(checkpoint=stop)
        self.assertTrue((self.python / "mondes" / A / "plan_ia.json").exists())
        self.assertFalse((self.game / m.MARKER).exists())
        self.activate()
        self.assertTrue(self.activate()["deja_active"])

    def test_collision_or_unknown_marker_is_not_overwritten(self):
        self.prepare()
        self.confirm()
        self.put(self.game, "mondes/" + A + "/stockage.json", b"unrelated data")
        with self.assertRaises(m.MigrationError):
            self.activate()
        self.assertEqual(
            b"unrelated data", (self.game / "mondes" / A / "stockage.json").read_bytes()
        )
        self.assertFalse((self.game / m.MARKER).exists())
        self.put(self.game, m.MARKER, {"v": 999})
        with self.assertRaises(m.MigrationError):
            m.deactivate(self.python, self.game, guard=lambda: None)
        self.assertEqual({"v": 999}, json.loads((self.game / m.MARKER).read_text()))

    def test_symlink_target_never_followed(self):
        self.prepare()
        self.confirm()
        outside = self.root / "outside"
        outside.mkdir()
        try:
            (self.game / "mondes").symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("symlinks unavailable")
        with self.assertRaises(backup.BackupError):
            self.activate()
        self.assertEqual([], list(outside.iterdir()))

    def test_concurrent_source_change_during_preparation_rejected(self):
        def modify(phase):
            if phase == "restored":
                self.put(self.python, "session.json", {"thread_id": "newer"})

        with self.assertRaises(m.MigrationError):
            self.prepare(checkpoint=modify)
        self.assertFalse(self.preparation.exists())
        self.assertEqual(
            {"thread_id": "newer"}, json.loads((self.python / "session.json").read_text())
        )

    def test_change_during_activation_blocks_marker(self):
        self.prepare()
        self.confirm()

        def modify(phase):
            if phase == "before_activation":
                self.put(self.python, "mondes/" + A + "/plan_ia.json", {"unexpected": True})

        with self.assertRaises(m.MigrationError):
            self.activate(checkpoint=modify)
        self.assertFalse((self.game / m.MARKER).exists())
        self.assertEqual(
            True, json.loads((self.python / "plan_ia.json").read_text())["objectif_en_pause"]
        )

    def test_failed_activation_write_keeps_legacy_mode(self):
        self.prepare()
        self.confirm()
        real = m._once

        def fail(path, data):
            if path.name == m.MARKER:
                raise OSError("disk full")
            return real(path, data)

        with patch.object(m, "_once", side_effect=fail):
            with self.assertRaises(OSError):
                self.activate()
        self.assertFalse((self.game / m.MARKER).exists())
        self.activate()

    def test_partitioned_data_in_future_backups(self):
        self.put(self.python, "mondes/" + B + "/plan_ia.json", {"plan": "separate"})
        data, _ = backup.inventory(self.python, self.game)
        self.assertIn("mondes/" + B + "/plan_ia.json", [e["path"] for e in data["entries"]])
        self.prepare()
        self.confirm()
        self.activate()
        self.assertEqual(
            {"plan": "separate"},
            json.loads((self.python / "mondes" / B / "plan_ia.json").read_text()),
        )

    def test_two_users_keep_independent_histories_on_same_server(self):
        self.prepare()
        self.confirm()
        other_python, other_game = self.root / "python-windows", self.root / "game-windows"
        shutil.copytree(self.python, other_python)
        shutil.copytree(self.game, other_game)
        self.put(other_python, "session.json", {"thread_id": "other-account"})
        self.put(other_python, "session_claude.json", {"session_id": "colleague"})
        self.put(other_python, "plan_ia.json", {"plan": {"objectif": "Colleague"}})
        self.put(other_game, "identite_monde.json", {**self.identity, "t": time.time() * 1000})
        receipt, prepared = self.root / "second-receipt.json", self.root / "second-prepared"
        m.identify(other_python, other_game, receipt)
        m.prepare(other_python, other_game, receipt, prepared, guard=lambda: None)
        # Copying another user's confirmation cannot authorize this installation.
        (prepared / "confirmation.json").write_bytes(
            (self.preparation / "confirmation.json").read_bytes()
        )
        with self.assertRaises(m.MigrationError):
            m.activate(prepared, guard=lambda: None)
        (prepared / "confirmation.json").unlink()
        m.confirm(prepared, A, single_server=True, guard=lambda: None)
        theirs = m.activate(prepared, guard=lambda: None)
        ours = self.activate()
        self.assertNotEqual(ours["installation"], theirs["installation"])
        self.assertEqual(A, ours["monde"])
        self.assertEqual(A, theirs["monde"])
        self.assertEqual(
            {"thread_id": "other-account"}, json.loads((other_python / "session.json").read_text())
        )
        self.assertEqual(
            "Colleague",
            json.loads((other_python / "mondes" / A / "plan_ia.json").read_text())["plan"][
                "objectif"
            ],
        )
        self.assertEqual(
            "Preserve",
            json.loads((self.python / "mondes" / A / "plan_ia.json").read_text())["plan"][
                "objectif"
            ],
        )

    def test_receipt_limit_matches_four_second_client_contract(self):
        self.put(self.game, "identite_monde.json", {**self.identity, "t": 96000})
        with self.assertRaises(m.MigrationError):
            m.identify(self.python, self.game, self.root / "expired.json", now=lambda: 100)
        self.put(
            self.game,
            "identite_monde.json",
            {**self.identity, "t": 96001, "adresse": "private-address"},
        )
        value = m.identify(self.python, self.game, self.root / "recent.json", now=lambda: 100)
        self.assertNotIn("private-address", json.dumps(value))

    def test_cli_requires_explicit_steps(self):
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                main(["activate"])
            self.assertEqual(1, main(["verify", str(self.root / "missing")]))
        self.assertFalse((self.game / m.MARKER).exists())


if __name__ == "__main__":
    unittest.main()
