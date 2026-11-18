# Discussion, voix et moteurs IA

## Points d’entrée

- [OngletDiscussion](../../mod/src/main/java/fr/assistantia/OngletDiscussion.java) et [EtatDiscussion](../../mod/src/main/java/fr/assistantia/EtatDiscussion.java) gèrent les messages, le brouillon et le défilement en jeu.
- [Contrôleur de conversation](../../monifactory_assistant/application/conversation.py) reçoit les questions, les fiches d’objets et les événements vocaux. [Audio](../../monifactory_assistant/audio.py) gère la capture, la transcription et la lecture.
- [Session](../../monifactory_assistant/providers/session.py) délègue à [Codex](../../monifactory_assistant/providers/codex.py) ou [Claude](../../monifactory_assistant/providers/claude.py). [Discussion en lecture seule](../../monifactory_assistant/ui/discussion.py) sert le suivi local.

## Règles à préserver

- Texte et voix partagent l’état d’admission de Discussion. Un double clic ou un événement répété ne lance pas deux tours.
- Le clic **Parler** ouvre Discussion. La transcription et la réponse appartiennent à la bonne demande. Une réponse tardive reste attachée à son contexte d’origine.
- Le mod pilote la conversation. Le panneau local la suit en lecture seule. Le choix de fournisseur, les préférences et les sessions restent propres à l’installation.
- Pour un chargement bloqué, vérifier l’accusé de la demande, l’état affiché et le journal du fournisseur. Garder distincts un envoi accepté et une réponse reçue.

## Tests à lancer

```sh
python -m unittest tests.test_discussion_menu tests.test_discussion_viewer tests.test_transport -v
```

En jeu, essayer une question texte, un doublon de clic et le retour depuis une fenêtre ouverte par Discussion. Un parcours automatisé avec CLI simulé n’atteste pas un échange réel. [Usage en jeu](../utilisation.md#discussion-dans-minecraft)
