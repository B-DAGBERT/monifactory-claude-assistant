"""Characterize prompts and delayed turns through the Python application entry points."""

import json
import sys
import tempfile
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, call, patch

import monifactory_assistant.providers as ai_provider
import monifactory_assistant.providers.claude as claude
import monifactory_assistant.providers.codex as codex
from monifactory_assistant.app import App
from monifactory_assistant.game.recipes import RecipeIndex
from monifactory_assistant.game.storage import Storage
from monifactory_assistant.game.world import OBSERVATION_MAX_AGE_MS, observation_date
from monifactory_assistant.prompts import ACTIONS_HELP
from monifactory_assistant.providers.session import Conversation
from monifactory_assistant.ui.discussion import recent_history
from tests.benchmarks.question_context import START
from tests.test_discussion_session import FAKE_CLAUDE
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
        self.thread = Mock()
        for module in ("conversation", "interface"):
            self.enterContext(
                patch(
                    f"monifactory_assistant.application.{module}.threading",
                    SimpleNamespace(Thread=self.thread),
                )
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
        app.conversation = SimpleNamespace(ask=Mock(return_value="Réponse."), generation=None)
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
        call["target"](*call.get("args", ()))
        return list(self.app.events.queue)

    def use_codex(self):
        self.enterContext(patch.object(codex, "HERE", self.directory))
        self.enterContext(patch.object(ai_provider, "CONFIG", {"provider": "codex"}))
        client = self.enterContext(patch.object(codex, "CLIENT"))
        sent = []

        def run(prompt, thread, *_args, **options):
            thread = thread or f"thread-{len(sent)}"
            options["on_thread"](thread)
            sent.append((thread, prompt))
            return self.reply, thread

        client.run.side_effect = run
        self.reply = "Réponse du moteur."
        self.app.conversation = Conversation(self.app.bridge.dir)
        return sent

    def exchange(self, text, *, deliver=True):
        self.assertTrue(self.app.start_question(text, None))
        self.run_worker()
        if deliver:
            self.app.poll()

    def after_idle(self, text, world=WORLD_A, session=SESSION_A, dimension=OVERWORLD):
        self.clock.return_value += Conversation.MAX_IDLE + 1
        self.enter_world(world, session, dimension)
        self.exchange(text)

    def test_renewal_in_another_world_does_not_send_the_previous_world(self):
        sent = self.use_codex()
        self.enter_world()
        self.exchange("Première question du monde A")
        self.exchange("Deuxième question du monde A")
        self.after_idle("Question du monde B", WORLD_B, SESSION_B)
        self.assertEqual(len(sent), 3)
        self.assertNotEqual(sent[1][0], sent[2][0])
        self.assertNotIn("Échanges précédents", sent[2][1])
        self.assertIn("Ma question : Question du monde B", sent[2][1])
        self.assertIn("Deuxième question du monde A", [row["texte"] for row in self.app.history])

    def test_renewal_does_not_recall_exchanges_before_new_conversation(self):
        sent = self.use_codex()
        self.enter_world()
        self.exchange("Première question avant remise à zéro")
        self.exchange("Deuxième question avant remise à zéro")
        self.app.on_new()
        self.app.notify.assert_called_once_with("Nouvelle conversation.", 4)
        self.run_worker()
        self.exchange("Question depuis la remise à zéro")
        self.assertNotIn("Échanges précédents", sent[2][1])
        self.after_idle("Quelle suite ?")
        self.assertEqual(len(sent), 4)
        self.assertNotEqual(sent[2][0], sent[3][0])
        self.assertIn("Question : Question depuis la remise à zéro", sent[3][1])
        self.assertNotIn("avant remise à zéro", sent[3][1])

    def test_late_answer_is_visible_but_never_recalled_after_reconnection(self):
        sent = self.use_codex()
        app = self.app
        self.enter_world()
        self.reply = REPLY
        self.exchange("Question dont la réponse attend dans la file", deliver=False)
        self.disconnect()
        self.enter_world(session=SESSION_B)
        app.poll()
        self.assertFalse(app.places_file.exists())
        self.assertEqual(
            app.history[-1]["texte"], "Réponse de la connexion précédente · Voici le lieu."
        )
        self.reply = "Réponse de la nouvelle connexion."
        self.exchange("Question de la nouvelle connexion")
        self.assertNotEqual(sent[0][0], sent[1][0])
        self.after_idle("Quelle suite ?", session=SESSION_B)
        self.assertIn("Question : Question de la nouvelle connexion", sent[-1][1])
        self.assertNotIn("attend dans la file", sent[-1][1])
        self.assertNotIn("Voici le lieu", sent[-1][1])

    def test_visible_history_restored_from_disk_is_not_sent_to_the_provider(self):
        sent = self.use_codex()
        app = self.app
        self.enter_world()
        self.exchange("Question originale")
        rows = [
            {"de": "toi", "texte": "Question historique sans provenance"},
            {"de": "claude", "texte": "Réponse historique sans provenance"},
        ]
        dump(app.bridge.dir / "vue_mod.json", {"moteur": "Codex", "ecran": {"conversation": rows}})
        app.history = recent_history(app.bridge.dir, "Codex")
        app.conversation = Conversation(app.bridge.dir)
        self.assertEqual(app.history, rows)
        self.after_idle("Question après restauration")
        self.assertEqual(len(sent), 2)
        self.assertNotEqual(sent[0][0], sent[1][0])
        self.assertNotIn("historique sans provenance", sent[1][1])
        self.assertEqual(app.history[:2], rows)

    def test_new_exchanges_without_a_verified_world_remain_visible_only(self):
        sent = self.use_codex()
        self.assertIsNone(self.app.bridge.scope)
        self.exchange("Question hors partie")
        self.exchange("Suite hors partie")
        self.assertEqual(len(sent), 2)
        self.assertNotEqual(sent[0][0], sent[1][0])
        self.assertNotIn("Échanges précédents", sent[1][1])
        self.assertIn({"de": "toi", "texte": "Question hors partie"}, self.app.history)

    def test_display_notification_cannot_answer_a_failed_question_in_recall(self):
        sent = self.use_codex()
        self.enter_world()
        self.exchange("Question livrée")
        run = codex.CLIENT.run.side_effect
        codex.CLIENT.run.side_effect = RuntimeError("Inférence interrompue")
        self.exchange("Question sans réponse")
        self.app.add_history("claude", "Conseil automatique hors échange.")
        codex.CLIENT.run.side_effect = run
        self.after_idle("Quelle suite ?")
        self.assertEqual(codex.CLIENT.run.call_count, 3)
        self.assertIn("Question : Question livrée", sent[-1][1])
        self.assertNotIn("Question sans réponse", sent[-1][1])
        self.assertNotIn("Conseil automatique hors échange", sent[-1][1])

    def test_same_scope_renewal_sends_two_clean_bounded_exchanges_once(self):
        sent = self.use_codex()
        self.enter_world()
        self.exchange("Premier échange")
        action = '<actions>[{"type":"memoriser_lieu","nom":"Mine"}]</actions>'
        long_answer = "Il faut " + "du bronze " * 300
        self.reply = long_answer + action
        self.exchange("Q" * 1400)
        self.reply = "Dans gtceu:bronze_crate." + action
        self.exchange("Où est le coffre ?")
        self.after_idle("Quelle suite ?")
        self.assertEqual(len(sent), 4)
        self.assertNotEqual(sent[2][0], sent[3][0])
        prompt = sent[3][1]
        recall = prompt.split("\n\n", 1)[0]
        lines = recall.splitlines()
        self.assertEqual(len(lines), 5)
        self.assertEqual(lines[1], "Question : " + "Q" * 1199 + "…")
        self.assertEqual(lines[2], "Réponse : " + long_answer[:1199].rstrip() + "…")
        self.assertEqual(
            lines[3:], ["Question : Où est le coffre ?", "Réponse : Dans Bronze Crate."]
        )
        self.assertEqual(prompt.count("Ma question : Quelle suite ?"), 1)
        self.assertNotIn("Premier échange", recall)
        self.assertNotIn("<actions>", recall)

    def test_codex_does_not_reuse_the_thread_of_a_failed_first_turn_in_another_world(self):
        sent = self.use_codex()
        app = self.app
        self.enter_world()
        self.exchange("Question livrée dans A")
        client = codex.CLIENT
        run = client.run.side_effect

        def interrupted(prompt, thread, *args, **options):
            run(prompt, thread, *args, **options)  # on_thread, puis acceptation de l'envoi
            options["on_preview"](REPLY)
            raise TimeoutError("Inférence interrompue")

        client.run.side_effect = interrupted
        self.after_idle("Tour interrompu avec action")
        failed_thread = sent[-1][0]
        self.assertNotEqual(sent[0][0], failed_thread)
        saved = json.loads((self.directory / "session.json").read_text(encoding="utf-8"))
        self.assertEqual(saved["thread_id"], failed_thread)
        self.assertEqual(saved["questions"], 1)
        self.assertEqual(app.conversation.renewal_reason(), "")
        client.run.side_effect = run
        self.enter_world(WORLD_B, SESSION_B)
        self.exchange("Question dans B")
        self.assertEqual(len(sent), 3)
        self.assertIn("Ma question : Question dans B", sent[-1][1])
        self.assertNotIn("Tour interrompu", sent[-1][1])
        self.assertNotIn("Échanges précédents", sent[-1][1])
        self.assertFalse(app.places_file.exists())
        self.assertIsNone(client.run.call_args.args[1])
        self.assertNotEqual(sent[-1][0], failed_thread)

    def use_claude(self):
        script = self.directory / "fake_claude.py"
        script.write_text(FAKE_CLAUDE, encoding="utf-8")
        actual_start = claude.start
        self.enterContext(patch.object(claude, "HERE", self.directory))
        self.enterContext(patch.object(claude, "find_claude", return_value=str(script)))
        self.enterContext(
            patch.object(
                claude, "start", side_effect=lambda args: actual_start([sys.executable, *args])
            )
        )
        self.enterContext(patch.object(ai_provider, "CONFIG", {"provider": "claude"}))

    def claude_trace(self, name):
        return [
            json.loads(line)
            for line in (self.directory / (name + ".jsonl"))
            .read_text(encoding="utf-8")
            .splitlines()
        ]

    def test_claude_does_not_resume_the_session_of_a_failed_first_turn_in_another_world(self):
        self.use_claude()
        app = self.app
        app.conversation = Conversation(app.bridge.dir)
        self.addCleanup(app.conversation.stop)
        self.enter_world()
        self.exchange("Question livrée dans A")
        self.after_idle("Tour interrompu avec action")
        saved = json.loads((self.directory / "session_claude.json").read_text(encoding="utf-8"))
        _, failed_launch = self.claude_trace("launches")
        failed_id = failed_launch[failed_launch.index("--session-id") + 1]
        self.assertEqual(saved["session_id"], failed_id)
        self.assertEqual(saved["questions"], 1)
        self.assertEqual(app.conversation.renewal_reason(), "")
        self.enter_world(WORLD_B, SESSION_B)
        self.exchange("Question dans B")
        requests = self.claude_trace("requests")
        self.assertEqual(len(requests), 3)
        self.assertIn("Ma question : Question dans B", requests[-1])
        self.assertNotIn("Tour interrompu", requests[-1])
        self.assertNotIn("Échanges précédents", requests[-1])
        self.assertFalse(app.places_file.exists())
        launch = self.claude_trace("launches")[-1]
        self.assertNotIn("--resume", launch)
        self.assertNotEqual(launch[launch.index("--session-id") + 1], failed_id)

    def test_switch_to_claude_preserves_world_and_manual_boundaries(self):
        self.use_codex()
        app = self.app
        self.enter_world()
        self.exchange("Échange Codex dans le monde A")
        self.app.on_new()
        self.run_worker()
        self.enter_world(WORLD_B, SESSION_B)
        self.use_claude()
        dump(
            self.directory / "session_claude.json",
            {"data_dir": str(app.bridge.dir.resolve()), "session_id": "ancienne-session-claude"},
        )
        app.conversation = Conversation(app.bridge.dir)
        self.addCleanup(app.conversation.stop)
        self.exchange("Première question Claude dans B")
        app.on_new()
        self.run_worker()
        self.exchange("Question Claude depuis la remise à zéro")
        self.after_idle("Suite avec Claude", WORLD_B, SESSION_B)
        requests = [
            json.loads(line)
            for line in (self.directory / "requests.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        self.assertEqual(len(requests), 3)
        self.assertNotIn("Échanges précédents", requests[0])
        self.assertNotIn("Échanges précédents", requests[1])
        self.assertIn("Question : Question Claude depuis la remise à zéro", requests[2])
        self.assertNotIn("Première question Claude dans B", requests[2])

    def test_restart_during_inference_discards_old_actions_and_orders_the_next_turn(self):
        sent = self.use_codex()
        app = self.app
        self.enter_world()
        app.settings["voix"] = True
        client = codex.CLIENT
        run = client.run.side_effect

        def reset_during_turn(*args, **options):
            app.on_new()
            options["on_preview"]("Aperçu de la conversation précédente.")
            return run(*args, **options)

        self.reply = REPLY
        client.run.side_effect = reset_during_turn
        self.exchange("Question en cours lors de la remise à zéro")
        self.assertFalse(app.places_file.exists())
        self.assertEqual(app.message, "")
        self.assertEqual(
            app.history[-1]["texte"], "Réponse de la conversation précédente · Voici le lieu."
        )
        app.speaker.add.assert_not_called()
        self.assertFalse(app.busy)
        reset_worker = self.thread.call_args.kwargs["target"]
        client.run.side_effect = run
        self.reply = "Nouvelle réponse."
        # The question worker may run before the deferred restart worker.
        self.exchange("Première question de la nouvelle conversation")
        self.assertNotEqual(sent[0][0], sent[1][0])
        self.assertNotIn("Échanges précédents", sent[1][1])
        reset_worker()
        self.exchange("Deuxième question de la nouvelle conversation")
        self.assertEqual(sent[1][0], sent[2][0])
        self.after_idle("Rappel de la nouvelle conversation")
        self.assertEqual(len(sent), 4)
        self.assertNotIn("Question en cours lors de la remise à zéro", sent[3][1])

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
