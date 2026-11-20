"""Façade de conversation partagée entre Claude et Codex."""

import time
from pathlib import Path

import monifactory_assistant.providers as ai_provider
from monifactory_assistant.prompts import SYSTEM_PROMPT
from monifactory_assistant.runtime import log
from monifactory_assistant.settings import TIMEOUT


def ask_claude(prompt, data_dir, model, on_text=None, on_tool=None, effort=None):
    return ai_provider.ask(
        prompt, data_dir, model, SYSTEM_PROMPT, on_text, on_tool, effort, TIMEOUT
    )


class Conversation:
    """Session du moteur choisi, renouvelée comme dans l’assistant d’origine.

    Avant une question, la session enregistrée repart à neuf quand elle compte déjà
    MAX_QUESTIONS questions, quand sa dernière question date de plus de MAX_IDLE secondes ou
    quand son fichier ne donne pas son usage. Le renouvellement n’envoie rien : la question
    en cours part une seule fois, précédée du rappel des derniers échanges fourni par l’appelant.
    Ce rappel accompagne les questions suivantes tant qu’aucune n’a reçu de réponse.
    """

    MAX_QUESTIONS = 10
    MAX_IDLE = 4 * 3600

    def __init__(self, data_dir, clock=None):
        self.clock = clock or time.time
        self.renewed = False
        self.impl = ai_provider.conversation(
            data_dir,
            SYSTEM_PROMPT,
            ai_provider.model("question"),
            ai_provider.effort("question", "low"),
            TIMEOUT,
        )

    def renewal_reason(self):
        """Motif de renouvellement de la session enregistrée, sinon une chaîne vide."""
        session, questions, last = self.impl.usage()
        if not session:
            return ""  # La prochaine question ouvre déjà une session neuve.
        if questions is None:
            return "usage inconnu"
        if questions >= self.MAX_QUESTIONS:
            return f"{questions} questions"
        if last is None or self.clock() - last > self.MAX_IDLE:
            return "plus de 4 h sans question"
        return ""

    def ask(self, prompt, on_text=None, on_tool=None, on_preview=None, *, previous=""):
        self.impl.configure(ai_provider.model("question"), ai_provider.effort("question", "low"))
        reason = self.renewal_reason()
        if reason:
            self.impl.restart()
            self.renewed = True
            log(f"Discussion : nouvelle session {ai_provider.label()} ({reason}).")
        if self.renewed and previous:
            prompt = previous + "\n\n" + prompt
        self.impl.count_question(self.clock())
        answer = self.impl.ask(prompt, on_text, on_tool, on_preview)
        self.renewed = False
        return answer

    def restart(self):
        self.renewed = False  # « Nouvelle conversation » ne reprend aucun échange.
        return self.impl.restart()

    def stop(self):
        return self.impl.stop()

    def prepare(self):
        prepare = getattr(self.impl, "prepare", None)
        # Une session à renouveler n’est pas réclamée : la prochaine question en ouvre une neuve.
        if prepare and not self.renewal_reason():
            prepare()


def tool_status(name, data):
    detail = (
        data.get("pattern")
        or data.get("query")
        or data.get("url")
        or Path(data.get("file_path") or data.get("path") or "").name
    )
    return (
        f"{ai_provider.label()} cherche : {detail}"
        if detail
        else f"{ai_provider.label()} utilise {name}…"
    )[:90]
