"""Offline model selection never exposes account data or loses configured profiles."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from monifactory_assistant.providers.catalog import ModelCatalog


class ModelCatalogTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.path = Path(tmp.name) / "models_cache.json"
        self.now = 1.0
        self.catalog = ModelCatalog(self.path, clock=lambda: self.now)
        self.values = {"model_question": "my-custom", "model_plan": "gpt-local"}
        self.defaults = {"model_question": "my-custom", "model_plan": "default-plan"}

    def view(self):
        return self.catalog.view("codex", self.values, self.defaults)

    def test_cache_order_names_efforts_visibility_and_private_data(self):
        self.path.write_text(
            json.dumps(
                {
                    "identity": "account-private",
                    "models": [
                        {
                            "slug": "hidden",
                            "visibility": "hide",
                            "supported_reasoning_levels": [{"effort": "low"}],
                        },
                        {
                            "slug": "gpt-local",
                            "display_name": "Modèle lisible",
                            "visibility": "list",
                            "supported_reasoning_levels": [
                                {"effort": "low"},
                                {"effort": "max"},
                                {"effort": "ultra"},
                                {"effort": "low"},
                            ],
                            "internal_prompt": "secret",
                        },
                        {"slug": "gpt-local", "visibility": "list"},
                        None,
                        {
                            "slug": "unsupported",
                            "visibility": "list",
                            "supported_reasoning_levels": [{"effort": "none"}],
                        },
                    ],
                }
            )
        )
        view = self.view()
        rows = view["modeles"]
        self.assertEqual([m["id"] for m in rows], ["gpt-local", "my-custom", "default-plan"])
        self.assertEqual(rows[0]["nom"], "Modèle lisible")
        self.assertEqual(rows[0]["efforts"], ["low", "max"])
        for value in ("account-private", "secret", "hidden", "unsupported"):
            self.assertNotIn(value, json.dumps(view))

    def test_missing_partial_removed_and_replaced_cache_preserve_configured_models(self):
        for raw in (None, "{partial", "[]", '{"models": null}'):
            if raw is None:
                self.path.unlink(missing_ok=True)
            else:
                self.path.write_text(raw)
            self.now += 6
            self.assertEqual(
                [m["id"] for m in self.view()["modeles"]],
                ["my-custom", "gpt-local", "default-plan"],
            )
        self.path.write_text(
            json.dumps(
                {
                    "models": [
                        {
                            "slug": "new",
                            "display_name": "New",
                            "visibility": "list",
                            "supported_reasoning_levels": [{"effort": "high"}],
                        }
                    ]
                }
            )
        )
        self.now += 6
        self.assertEqual(self.view()["modeles"][0]["id"], "new")
        with patch.object(Path, "read_text", side_effect=AssertionError("Repeated disk read")):
            self.view()
            self.now += 6
            self.view()  # unchanged stat is also cached
        self.path.unlink()
        self.now += 6
        self.assertNotIn("new", [m["id"] for m in self.view()["modeles"]])

    def test_claude_aliases_and_custom_model_do_not_read_codex(self):
        with patch.object(Path, "stat", side_effect=AssertionError("Unexpected Codex access")):
            view = self.catalog.view(
                "claude", {"model_question": "sonnet", "model_plan": "custom-claude"}, {}
            )
        self.assertEqual(
            [m["id"] for m in view["modeles"]], ["sonnet", "opus", "haiku", "custom-claude"]
        )
