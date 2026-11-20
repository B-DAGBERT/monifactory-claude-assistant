"""Session de Discussion renouvelée, rappel des échanges et chronométrage, sans requête IA."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from queue import Queue
from types import SimpleNamespace
from unittest.mock import Mock, call, patch

import monifactory_assistant.providers as ai_provider
import monifactory_assistant.providers.claude as claude
import monifactory_assistant.providers.codex as codex
from monifactory_assistant.application.conversation import (
    ConversationController,
    RecallExchange,
    previous_exchanges,
)
from monifactory_assistant.game.world_scope import WorldScope
from monifactory_assistant.prompts import ACTIONS_HELP
from monifactory_assistant.providers.session import Conversation

NOW = 1_800_000_000.0
RAPPEL = "Échanges précédents : rappel de test"
FAKE_CLAUDE = r"""import json, sys
args = sys.argv[1:]
session = args[args.index("--resume") + 1] if "--resume" in args else args[args.index("--session-id") + 1]
with open("launches.jsonl", "a", encoding="utf-8") as stream:
    stream.write(json.dumps(args) + "\n")
for line in sys.stdin:
    prompt = json.loads(line)["message"]["content"]
    with open("requests.jsonl", "a", encoding="utf-8") as stream:
        stream.write(json.dumps(prompt) + "\n")
    print(json.dumps({"type": "system", "subtype": "init", "session_id": session}), flush=True)
    print(json.dumps({"type": "result", "is_error": False, "result": "Réponse", "session_id": session}), flush=True)
"""


class CodexSessionTests(unittest.TestCase):
    """La règle de la façade avec le vrai session.json et un service Codex simulé."""

    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.data_dir = self.root / "game"
        self.data_dir.mkdir()
        self.now = NOW
        self.enterContext(patch.object(codex, "HERE", self.root))
        self.enterContext(patch.object(ai_provider, "CONFIG", {"provider": "codex"}))
        self.enterContext(patch("monifactory_assistant.providers.session.log"))
        self.client = self.enterContext(patch.object(codex, "CLIENT"))
        self.client.proc.poll.return_value = None  # Service ouvert : restart se désabonne.
        self.sent, self.threads = [], 0
        self.client.run.side_effect = self.run_turn

    def run_turn(self, prompt, thread, *_args, **options):
        if thread is None:
            self.threads += 1
            thread = f"thread-{self.threads}"
        options["on_thread"](thread)
        self.sent.append((thread, prompt))
        return f"Réponse {len(self.sent)}", thread

    def chat(self):
        return Conversation(self.data_dir, clock=lambda: self.now)

    def saved(self):
        return json.loads((self.root / "session.json").read_text(encoding="utf-8"))

    def test_eleventh_question_opens_a_new_session_once_with_the_recall(self):
        chat = self.chat()
        for number in range(10):
            chat.ask(f"Question {number}", previous=RAPPEL)
            self.now += 60
        self.assertEqual(self.sent, [("thread-1", f"Question {n}") for n in range(10)])
        self.assertEqual(self.saved()["questions"], 10)
        self.client.request.assert_not_called()
        chat.ask("Et la suite ?", previous=RAPPEL)
        self.client.request.assert_called_once_with("thread/unsubscribe", {"threadId": "thread-1"})
        # Renewing sends nothing: only the new question leaves, once, after the recall.
        self.assertEqual(self.sent[10:], [("thread-2", RAPPEL + "\n\nEt la suite ?")])
        self.assertEqual(
            self.saved(),
            {
                "data_dir": str(self.data_dir.resolve()),
                "thread_id": "thread-2",
                "questions": 1,
                "last_question_at": self.now,
            },
        )
        chat.ask("Encore", previous=RAPPEL)
        self.assertEqual(self.sent[-1], ("thread-2", "Encore"))
        self.assertEqual(self.client.run.call_count, 12)

    def test_a_session_idle_for_more_than_four_hours_is_renewed(self):
        chat = self.chat()
        chat.ask("Avant la pause", previous=RAPPEL)
        self.now += chat.MAX_IDLE
        chat.ask("Quatre heures pile", previous=RAPPEL)
        self.assertEqual(self.sent[-1], ("thread-1", "Quatre heures pile"))
        self.now += chat.MAX_IDLE + 1
        self.assertEqual(chat.renewal_reason(), "plus de 4 h sans question")
        chat.ask("Après la pause", previous=RAPPEL)
        self.assertEqual(self.sent[-1], ("thread-2", RAPPEL + "\n\nAprès la pause"))
        self.assertEqual(len(self.sent), 3)

    def test_count_and_last_use_survive_a_new_conversation_object(self):
        chat = self.chat()
        for number in range(9):
            chat.ask(f"Question {number}")
        restored = self.chat()
        self.assertEqual(restored.impl.usage(), ("thread-1", 9, NOW))
        restored.ask("Dixième", previous=RAPPEL)
        self.assertEqual(self.sent[-1], ("thread-1", "Dixième"))
        again = self.chat()
        self.assertEqual(again.impl.usage(), ("thread-1", 10, NOW))
        again.ask("Onzième", previous=RAPPEL)
        self.assertEqual(self.sent[-1], ("thread-2", RAPPEL + "\n\nOnzième"))

    def test_a_saved_thread_without_a_count_is_renewed_and_not_claimed(self):
        legacy = {"data_dir": str(self.data_dir.resolve()), "thread_id": "ancien"}
        (self.root / "session.json").write_text(json.dumps(legacy), encoding="utf-8")
        chat = self.chat()
        self.assertEqual(chat.impl.usage(), ("ancien", None, None))
        self.assertEqual(chat.renewal_reason(), "usage inconnu")
        chat.prepare()
        self.client.load_thread.assert_not_called()
        chat.ask("Question", previous=RAPPEL)
        self.client.request.assert_called_once_with("thread/unsubscribe", {"threadId": "ancien"})
        self.assertEqual(self.sent, [("thread-1", RAPPEL + "\n\nQuestion")])
        self.assertEqual(self.saved()["questions"], 1)

    def test_prepare_still_claims_a_current_thread_without_a_turn(self):
        self.chat().ask("Question")
        restored = self.chat()
        restored.prepare()
        self.client.load_thread.assert_called_once_with(
            "thread-1", self.data_dir, restored.impl.model, restored.impl.instructions
        )
        self.assertEqual(self.client.run.call_count, 1)

    def test_a_failed_question_after_renewal_is_not_replayed(self):
        chat = self.chat()
        for number in range(10):
            chat.ask(f"Question {number}")
        self.client.run.side_effect = RuntimeError("Connexion Codex interrompue.")
        with self.assertRaises(RuntimeError):
            chat.ask("Perdue", previous=RAPPEL)
        self.assertEqual(self.client.run.call_count, 11)
        self.client.run.side_effect = self.run_turn
        chat.ask("Suivante", previous=RAPPEL)
        # The recall waits for the first answered question; the lost one is never resent.
        self.assertEqual(self.sent[-1], ("thread-2", RAPPEL + "\n\nSuivante"))
        self.assertEqual(self.client.run.call_count, 12)
        self.assertFalse(any("Perdue" in prompt for _, prompt in self.sent))

    def test_manual_restart_starts_a_new_conversation_without_recall(self):
        chat = self.chat()
        chat.ask("Question")
        chat.restart()
        self.assertEqual((self.saved()["thread_id"], self.saved()["questions"]), (None, 0))
        self.assertEqual(chat.renewal_reason(), "")
        chat.ask("Nouvelle", previous=RAPPEL)
        self.assertEqual(self.sent[-1], ("thread-2", "Nouvelle"))


class ClaudeSessionTests(unittest.TestCase):
    """Même règle avec session_claude.json et un faux CLI Claude lancé en sous-processus."""

    def setUp(self):
        self.root = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.data_dir = self.root / "game"
        self.data_dir.mkdir()
        script = self.root / "fake_claude.py"
        script.write_text(FAKE_CLAUDE, encoding="utf-8")
        actual_start = claude.start
        self.enterContext(patch.object(claude, "HERE", self.root))
        self.enterContext(patch.object(claude, "find_claude", return_value=str(script)))
        self.enterContext(
            patch.object(
                claude, "start", side_effect=lambda args: actual_start([sys.executable, *args])
            )
        )
        self.enterContext(patch.object(ai_provider, "CONFIG", {"provider": "claude"}))
        self.enterContext(patch("monifactory_assistant.providers.session.log"))

    def chat(self):
        impl = claude.Conversation(self.data_dir, "Read {data_dir}", "sonnet", timeout=30)
        self.addCleanup(impl.stop)
        with patch.object(ai_provider, "conversation", return_value=impl):
            return Conversation(self.data_dir)

    def lines(self, name):
        path = self.root / name
        return [json.loads(row) for row in path.read_text(encoding="utf-8").splitlines()]

    def test_session_without_count_is_renewed_once_and_its_count_persists(self):
        legacy = {"data_dir": str(self.data_dir.resolve()), "session_id": "ancienne"}
        (self.root / "session_claude.json").write_text(json.dumps(legacy), encoding="utf-8")
        chat = self.chat()
        self.assertEqual(chat.renewal_reason(), "usage inconnu")
        self.assertEqual(chat.ask("Et la suite ?", previous=RAPPEL), "Réponse")
        self.assertEqual(chat.ask("Encore une", previous=RAPPEL), "Réponse")
        (launch,) = self.lines("launches.jsonl")
        self.assertNotIn("--resume", launch)
        session = launch[launch.index("--session-id") + 1]
        self.assertNotEqual(session, "ancienne")
        self.assertEqual(self.lines("requests.jsonl"), [RAPPEL + "\n\nEt la suite ?", "Encore une"])
        chat.stop()
        restored = self.chat()
        self.assertEqual(restored.impl.usage()[:2], (session, 2))
        restored.ask("Retour", previous=RAPPEL)
        launches = self.lines("launches.jsonl")
        self.assertEqual(launches[-1][launches[-1].index("--resume") + 1], session)
        self.assertEqual(self.lines("requests.jsonl")[-1], "Retour")


class Controller(ConversationController):
    def __init__(self, history=()):
        self.history = list(history)
        self.events = Queue()
        self.speaker = SimpleNamespace(stop=Mock(), add=Mock())
        self.speak = False
        self.recipe_index = SimpleNamespace(hint=Mock(return_value=""))
        self.context = Mock(return_value="Contexte du joueur")
        self.conversation = SimpleNamespace(ask=Mock(return_value="Réponse."))


class RecallAndTimingTests(unittest.TestCase):
    def test_recall_keeps_two_answered_exchanges_of_the_requested_provenance(self):
        scope = WorldScope("monde", "connexion", "dimension")
        other_scope = WorldScope("autre monde", "autre connexion", "dimension")
        generation = object()
        long_answer = "Il faut " + "du bronze " * 300
        exchanges = [
            RecallExchange(scope, generation, "Première question", "Première réponse."),
            RecallExchange(scope, generation, "Comment faire des plaques ?", long_answer),
            RecallExchange(other_scope, generation, "Autre monde", "Autre réponse."),
            RecallExchange(
                scope, generation, "Où est le coffre ?", "Dans Bronze Crate près de la base."
            ),
            RecallExchange(scope, object(), "Autre génération", "Réponse isolée."),
        ]
        lines = previous_exchanges(exchanges, scope, generation).splitlines()
        self.assertTrue(lines[0].startswith("Échanges précédents"))
        self.assertIn("pas de nouvelles demandes", lines[0])
        self.assertEqual(len(lines), 5)
        self.assertEqual(lines[1], "Question : Comment faire des plaques ?")
        answer = lines[2].removeprefix("Réponse : ")
        self.assertTrue(answer.startswith("Il faut du bronze"))
        self.assertTrue(answer.endswith("…"))
        self.assertLessEqual(len(answer), 1200)
        self.assertEqual(
            lines[3:],
            ["Question : Où est le coffre ?", "Réponse : Dans Bronze Crate près de la base."],
        )
        text = "\n".join(lines)
        for absent in ("Première", "Autre monde", "Autre génération"):
            self.assertNotIn(absent, text)
        self.assertEqual(previous_exchanges([], scope, generation), "")
        self.assertEqual(previous_exchanges(exchanges, None, generation), "")
        self.assertEqual(previous_exchanges(exchanges, scope, None), "")

    @patch("monifactory_assistant.application.conversation.log")
    def test_visible_history_alone_supplies_no_recall(self, _log):
        history = [{"de": "toi", "texte": "Q"}, {"de": "claude", "texte": "R"}]
        app = Controller(history)
        app.work_question("Et ensuite ?", None, None)
        sent = app.conversation.ask.call_args
        self.assertEqual(
            sent.args[0], f"Contexte du joueur\n\n{ACTIONS_HELP}\n\nMa question : Et ensuite ?"
        )
        self.assertEqual(sent.kwargs["previous"], "")

    def test_first_word_is_timed_at_the_first_visible_preview(self):
        clock = [NOW]

        def ask(_prompt, on_text, _on_tool, on_preview, **_options):
            clock[0] = NOW + 1
            on_preview("")
            on_preview("<actions")
            clock[0] = NOW + 2.5
            on_preview("Bonjour")
            clock[0] = NOW + 4
            on_preview("Bonjour, voici la réponse.")
            clock[0] = NOW + 6
            on_text("Bonjour, voici la réponse.")
            return "Bonjour, voici la réponse."

        app = Controller()
        app.conversation.ask.side_effect = ask
        with (
            patch(
                "monifactory_assistant.application.conversation.time.time",
                side_effect=lambda: clock[0],
            ),
            patch("monifactory_assistant.application.conversation.log") as log,
        ):
            app.work_question("Bonjour ?", None, None)
        self.assertIn(call("Temps : premier mot 2.5 s, fin 6.0 s"), log.call_args_list)


if __name__ == "__main__":
    unittest.main()
