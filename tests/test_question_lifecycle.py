"""Characterize prompts and delayed turns through the Python application entry points."""

import json
import tempfile
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, call, patch

from monifactory_assistant.app import App
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.world import OBSERVATION_MAX_AGE_MS, observation_date
from monifactory_assistant.prompts import ACTIONS_HELP
from tests.benchmarks.question_context import START
from tests.test_world_routing import (
    NETHER,
    OVERWORLD,
    SESSION_A,
    SESSION_B,
    WORLD_A,
    WORLD_B,
    dump,
    enter,
    setup_bridge,
)

EMPTY_OBSERVATIONS = (
    "Coffres : aucun mémorisé pour l'instant.\n"
    "Machines posées : aucune repérée.\n"
    "Filons repérés : aucun pour l'instant."
)
ISOLATED_HINT = (
    "Les stocks et détails d'inventaire à la racine sont historiques. "
    "Pour cette réponse, utilise les relevés de partie inclus ci-dessous."
)
QUESTION = "Que vois-tu ?"
REPLY = 'Voici le lieu.<actions>[{"type":"memoriser_lieu","nom":"Mine"}]</actions>'


def expected_prompt(question, context):
    return f"{context}\n\n{ACTIONS_HELP}\n\nMa question : {question}"


class QuestionLifecycleTests(TestCase):
    def setUp(self):
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.clock = self.enterContext(patch("time.time", return_value=START / 1000))
        self.enterContext(patch("monifactory_assistant.application.game.ROOT", self.directory))
        self.enterContext(patch("monifactory_assistant.application.conversation.log"))
        self.enterContext(
            patch(
                "monifactory_assistant.application.conversation.ai_provider.label",
                return_value="Codex",
            )
        )
        self.thread = self.enterContext(
            patch("monifactory_assistant.application.conversation.threading.Thread")
        )
        app = self.app = App.__new__(App)
        app.root, app.speaker = Mock(), Mock()
        app.notify, app.refresh, app.show_overlay = Mock(), Mock(), Mock()
        app.events = Queue()
        app.bridge = setup_bridge(self.directory)
        app.world = SimpleNamespace(invalidate_live=Mock())
        app.active_world_scope = "initialisation"
        app.apply_world_scope(None)
        app.recipe_index = RecipeIndex()
        app.conversation = SimpleNamespace(ask=Mock(return_value="Réponse."))
        app.settings = {"voix": False}
        app.busy, app.popup = False, None
        app.history = [{"de": "claude", "texte": "Tour précédent."}]
        self.addCleanup(app.bridge.close_publications)

    def enter_world(self, world=WORLD_A, session=SESSION_A, dimension=OVERWORLD):
        game = enter(self.app.bridge, world, session, dimension)
        self.app.apply_world_scope(self.app.bridge.scope)
        self.app.game = game
        return self.app.bridge.scope

    def disconnect(self):
        self.clock.return_value += 6
        self.assertIsNone(self.app.bridge.state())
        self.app.apply_world_scope(None)

    def request(self, n=1):
        scope = self.app.bridge.scope
        return {
            "type": "question",
            "texte": QUESTION,
            "n": n,
            "dimension_envoi": scope.dimension,
            **scope.fields(),
        }

    def run_worker(self):
        call = self.thread.call_args.kwargs
        call["target"](*call["args"])
        return list(self.app.events.queue)

    def test_offline_question_has_exact_prompt_and_history(self):
        app = self.app
        self.assertTrue(app.start_question(QUESTION, None))
        self.assertIsNone(app.question_scope)
        self.assertEqual(
            self.run_worker(),
            [
                ("status", f"« {QUESTION} » : Codex réfléchit…"),
                ("history", "toi", QUESTION),
                ("answer", QUESTION, "Réponse."),
            ],
        )
        app.conversation.ask.assert_called_once()
        self.assertEqual(
            app.conversation.ask.call_args.args[0],
            expected_prompt(QUESTION, f"{ISOLATED_HINT}\n{EMPTY_OBSERVATIONS}"),
        )
        app.poll()
        self.assertEqual(
            app.history,
            [
                {"de": "claude", "texte": "Tour précédent."},
                {"de": "toi", "texte": QUESTION},
                {"de": "claude", "texte": "Réponse."},
            ],
        )
        self.assertFalse(app.busy)
        self.assertEqual(
            app.show_overlay.call_args_list,
            [
                call("Codex réfléchit…"),
                call(f"« {QUESTION} » : Codex réfléchit…"),
                call(f"Toi : {QUESTION}\n\nRéponse."),
            ],
        )

    def test_dimension_world_and_reconnection_cancel_an_admitted_turn(self):
        app = self.app
        transitions = (
            lambda: self.enter_world(dimension=NETHER),
            lambda: self.enter_world(WORLD_B, SESSION_B),
            self.disconnect,
            lambda: self.enter_world(WORLD_A, SESSION_B),
        )
        for change in transitions:
            with self.subTest(change=change):
                original = self.enter_world()
                request = self.request()
                self.assertEqual(
                    app.dispatch_queued_request(request),
                    {
                        "etat": "traitee",
                        "message": "Demande prise en compte.",
                        "question": {"n": 1, "etat": "acceptee"},
                    },
                )
                self.assertEqual(app.question_scope, original)
                change()
                self.assertNotEqual(app.bridge.scope, original)
                self.assertEqual(
                    self.run_worker(), [("error", "Le monde a changé pendant la demande.")]
                )
                app.poll()
                app.conversation.ask.assert_not_called()
                self.assertFalse(app.busy)
                self.assertEqual(app.history, [{"de": "claude", "texte": "Tour précédent."}])
                self.assertEqual(app.dispatch_queued_request(request)["etat"], "refusee")

    def test_scope_is_rechecked_after_context_preparation(self):
        app = self.app
        original = self.enter_world()
        hint = app.bridge.context_hint

        def hint_then_change_dimension():
            text = hint()
            self.enter_world(dimension=NETHER)
            return text

        app.bridge.context_hint = hint_then_change_dimension
        app.dispatch_queued_request(self.request())
        self.assertEqual(
            self.run_worker(),
            [
                ("status", f"« {QUESTION} » : Codex réfléchit…"),
                ("history", "toi", QUESTION),
                ("error", "Le monde a changé pendant la préparation de la demande."),
            ],
        )
        self.assertEqual(app.question_scope, original)
        app.conversation.ask.assert_not_called()

    def test_voice_reads_live_observations_after_transcription(self):
        app = self.app
        original = self.enter_world()
        audio, shot = [0.5] * 16_000, Path("/tmp/capture-lot5.jpg")
        app.talk_cancelled, app.recording_scope, app.shot = False, original, shot
        app.recorder = SimpleNamespace(stream=None, stop=Mock(return_value=audio))

        def transcribe(samples):
            self.assertIs(samples, audio)
            app.game = {**app.game, "x": 12, "inventaire": {"test:apres": 2}}
            return QUESTION

        app.transcriber = SimpleNamespace(transcribe=Mock(side_effect=transcribe))
        app.on_talk_end()
        self.assertEqual(app.question_scope, original)
        app.transcriber.transcribe.assert_not_called()
        self.run_worker()
        self.assertEqual(
            app.conversation.ask.call_args.args[0],
            expected_prompt(
                QUESTION,
                f"{ISOLATED_HINT}\n"
                f"Position : x=12 y=64 z=0 ({OVERWORLD})\n"
                "Écran ouvert : aucun, en jeu ; en main : None\n"
                "Inventaire : 2x test:apres\n"
                f"{EMPTY_OBSERVATIONS}\n"
                f"Capture de mon écran : {shot} (lis-la avec view_image SEULEMENT si ma question porte "
                "sur ce que je vois : « ça », « ce bloc », « cet écran »… ; sinon ne l'ouvre pas, ça ralentit)",
            ),
        )
        app.poll()
        self.assertEqual(
            app.history[-2:],
            [{"de": "toi", "texte": QUESTION}, {"de": "claude", "texte": "Réponse."}],
        )

    def test_missing_current_identity_blocks_a_late_action_and_keeps_history(self):
        app = self.app
        app.settings["voix"] = True
        original = self.enter_world()
        path = app.places_file
        dump(path, {"Base": {"x": 5, "y": 64, "z": 6, "dimension": OVERWORLD}})
        app.load_places(path)
        initial = path.read_bytes()

        def answer(_prompt, on_text, on_tool, on_preview, **_options):
            # The bridge will still carry the admitted scope until the next game tick.
            (app.bridge.dir / "identite_monde.json").unlink()
            self.assertEqual(app.bridge.scope, original)
            self.assertFalse(app.bridge.scope_valid(original))
            on_text("Aperçu vocal.")
            on_tool("unknown", {})
            on_preview("Aperçu visuel.")
            return REPLY

        app.conversation.ask.side_effect = answer
        app.dispatch_queued_request(self.request())
        self.assertEqual(
            self.run_worker(),
            [
                ("status", f"« {QUESTION} » : Codex réfléchit…"),
                ("history", "toi", QUESTION),
                ("answer", QUESTION, REPLY),
            ],
        )
        app.poll()
        app.speaker.add.assert_not_called()
        self.assertEqual(path.read_bytes(), initial)
        self.assertEqual(
            app.history[-1]["texte"], "Réponse de la connexion précédente · Voici le lieu."
        )
        self.assertEqual(app.message, "")

    def test_current_action_uses_live_state_at_delivery(self):
        app = self.app
        self.enter_world()
        app.conversation.ask.return_value = REPLY
        app.dispatch_queued_request(self.request())
        self.run_worker()
        self.assertIn("Position : x=0", app.conversation.ask.call_args.args[0])
        app.game = {**app.game, "x": 17}
        app.poll()
        expected = {"Mine": {"x": 17, "y": 64, "z": 0, "dimension": OVERWORLD}}
        self.assertEqual(json.loads(app.places_file.read_text(encoding="utf-8")), expected)
        self.assertEqual(app.history[-1]["texte"], "Voici le lieu.")

    def test_answer_already_in_queue_stays_in_history_after_reconnection(self):
        app = self.app
        old_scope = self.enter_world()
        app.conversation.ask.return_value = REPLY
        request = self.request()
        app.dispatch_queued_request(request)
        self.assertEqual(self.run_worker()[-1], ("answer", QUESTION, REPLY))
        self.disconnect()
        scope = self.enter_world(session=SESSION_B)
        self.assertEqual(app.question_scope, old_scope)
        app.poll()
        self.assertEqual(
            app.history[-2:],
            [
                {"de": "toi", "texte": QUESTION},
                {"de": "claude", "texte": "Réponse de la connexion précédente · Voici le lieu."},
            ],
        )
        self.assertFalse(app.places_file.exists())
        self.assertEqual(app.dispatch_queued_request(request)["etat"], "refusee")
        app.conversation.ask.return_value = "Nouvelle réponse."
        accepted = app.dispatch_queued_request(self.request(n=2))
        self.assertEqual(accepted["question"], {"n": 2, "etat": "acceptee"})
        self.assertEqual(app.question_scope, scope)
        self.run_worker()
        app.poll()
        self.assertEqual(app.history[-1]["texte"], "Nouvelle réponse.")

    def test_partial_observation_keeps_its_coverage_and_expired_players_are_historical(self):
        app = self.app
        self.enter_world()
        app.veins = [
            {
                "gisement": "Cuivre",
                "minerai": "Chalcopyrite",
                "x": 4,
                "y": 18,
                "z": 7,
                "dimension": OVERWORLD,
                "partiel": True,
                "vus": 2,
            }
        ]
        app.world.players = [{"nom": "Alex", "x": 5, "y": 64, "z": 6, "dimension": OVERWORLD}]
        app.world.last_t, app.world.live_dimension = START, OVERWORLD
        prefix = (
            f"{ISOLATED_HINT}\n"
            f"Position : x=0 y=64 z=0 ({OVERWORLD})\n"
            "Écran ouvert : aucun, en jeu ; en main : None\n"
            "Inventaire : 1x test:plate\n"
            "Coffres : aucun mémorisé pour l'instant.\n"
            "Machines posées : aucune repérée.\n"
            f"Filons repérés (les plus proches d'abord) : Cuivre : Chalcopyrite en 4 18 7 ({OVERWORLD}), "
            "quantité totale restante inconnue, 2 blocs compatibles observés à proximité "
            "(limites du gisement non vérifiées)\n"
        )
        player = f"Alex en 5 64 6 ({OVERWORLD})"
        self.assertEqual(
            app.context(None, with_storage=True, question=QUESTION),
            prefix + f"Autres joueurs connectés : {player}",
        )
        self.clock.return_value += OBSERVATION_MAX_AGE_MS / 1000
        self.assertEqual(
            app.context(None, with_storage=True, question=QUESTION),
            prefix
            + f"Dernières positions connues des autres joueurs ({observation_date(START)}) : {player}",
        )

    def test_storage_and_machine_sections_keep_their_separate_reads(self):
        app = self.app
        self.enter_world()
        old_stock = Storage([])
        machine = {
            "nom": "Bender reçu",
            "bloc": "gtceu:lv_bender",
            "x": 3,
            "y": 64,
            "z": 2,
            "dimension": OVERWORLD,
        }
        new_stock = Storage([], machines=[machine])

        class Containers(list):
            def __iter__(self):
                app.storage = new_stock
                return super().__iter__()

        old_stock.containers = Containers()
        app.storage = old_stock
        self.assertEqual(
            app.context(None, with_storage=True, question=QUESTION),
            f"{ISOLATED_HINT}\n"
            f"Position : x=0 y=64 z=0 ({OVERWORLD})\n"
            "Écran ouvert : aucun, en jeu ; en main : None\n"
            "Inventaire : 1x test:plate\n"
            "Coffres : aucun mémorisé pour l'instant.\n"
            "Machines repérées en partie (derniers emplacements observés) : "
            f"Bender reçu [gtceu:lv_bender] en 3 64 2 ({OVERWORLD}, date inconnue)\n"
            "Filons repérés : aucun pour l'instant.",
        )
