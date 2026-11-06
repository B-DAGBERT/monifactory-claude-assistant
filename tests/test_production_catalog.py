"""Publication and recovery of verified, single-generation recipe snapshots."""

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.persistence import atomic_json
from monifactory_assistant.production.catalog import CatalogueReader, catalogue_document
from monifactory_assistant.production.recipes import Catalog


def payload(generation=1, alternate=False):
    def amount(item):
        return {"resource": {"kind": "item", "id": "test:" + item, "unit": "item"}, "quantity": 1}

    recipe = {
        "id": "test:plate",
        "category": "gtceu:bender",
        "inputs": [{"options": [amount("ingot")]}],
        "outputs": [{"amount": amount("plate")}],
        "machines": ["gtceu:lv_bender"],
    }
    return {
        "schema": "monifactory.production.recipes",
        "version": 1,
        "generation": generation,
        "recipes": [recipe, dict(recipe, id="test:alternate")] if alternate else [recipe],
    }


class CatalogueReaderTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.path = Path(folder.name) / "production.json"
        self.reader = CatalogueReader(self.path, "fixture-pack")

    def publish(self, data, source="fixture-pack"):
        atomic_json(self.path, catalogue_document(source, data), backup=False)

    def test_missing_and_legacy_files_cannot_be_used_for_exact_plan(self):
        self.assertEqual(self.reader.refresh().status, "unavailable")
        atomic_json(self.path, {"version": 2, "parties": 2, "nombre": 500}, backup=False)
        view = self.reader.refresh()
        self.assertEqual(view.status, "unavailable")
        self.assertIsNone(view.catalog)

    def test_verified_update_removes_alternatives_and_rejects_reordered_publications(self):
        self.publish(payload(1, alternate=True))
        first = self.reader.refresh()
        self.assertEqual(first.status, "ready")
        self.assertEqual(len(first.catalog.recipes), 2)
        self.assertIs(self.reader.refresh(), first)
        self.publish(payload(2))
        second = self.reader.refresh()
        self.assertEqual(second.catalog.generation, 2)
        self.assertEqual(len(second.catalog.recipes), 1)
        self.assertNotEqual(second.sha256, first.sha256)
        for out_of_order in (payload(1, alternate=True), payload(2, alternate=True)):
            self.publish(out_of_order)
            stale = self.reader.refresh()
            self.assertEqual(stale.status, "stale")
            self.assertIs(stale.catalog, second.catalog)
        self.publish(payload(3, alternate=True))
        self.assertEqual(self.reader.refresh().catalog.generation, 3)

    def test_partial_unrecognized_mixed_and_foreign_documents_keep_previous_view(self):
        self.publish(payload())
        previous = self.reader.refresh()
        corrupted = catalogue_document("fixture-pack", payload(2))
        corrupted["catalogue"]["recipes"] = payload(3, alternate=True)["recipes"]
        atomic_json(self.path, corrupted, backup=False)
        self.assertEqual(self.reader.refresh().status, "stale")
        self.publish(payload(2), "different-pack")
        self.assertIs(self.reader.refresh().catalog, previous.catalog)
        for raw in ('{"schema":', '{"duplicate":1,"duplicate":2}', '{"value":NaN}'):
            with self.subTest(raw=raw):
                self.path.write_text(raw, encoding="utf-8")
                invalid = self.reader.refresh()
                self.assertEqual(invalid.status, "stale")
                self.assertIs(invalid.catalog, previous.catalog)
                self.assertEqual(invalid.sha256, previous.sha256)
        broken = payload(3)
        broken["recipes"][0]["outputs"][0]["amount"]["quantity"] = True
        self.publish(broken)
        self.assertEqual(self.reader.refresh().status, "stale")
        unknown_envelope = dict(catalogue_document("fixture-pack", payload(3)), incomplete=True)
        atomic_json(self.path, unknown_envelope, backup=False)
        self.assertEqual(self.reader.refresh().status, "stale")

    def test_replacement_during_validation_does_not_publish_the_read_generation(self):
        self.publish(payload(1))
        previous = self.reader.refresh()
        self.publish(payload(2))
        parse = Catalog.from_dict

        def replace_during_parse(data):
            self.publish(payload(3, alternate=True))
            return parse(data)

        with patch.object(Catalog, "from_dict", side_effect=replace_during_parse):
            stale = self.reader.refresh()
        self.assertEqual(stale.status, "stale")
        self.assertIs(stale.catalog, previous.catalog)
        self.assertEqual(self.reader.refresh().catalog.generation, 3)
