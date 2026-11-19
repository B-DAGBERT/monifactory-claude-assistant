# Discussion, voix et moteurs IA

## Points d’entrée

- [OngletDiscussion](../../mod/src/main/java/fr/assistantia/OngletDiscussion.java) et [EtatDiscussion](../../mod/src/main/java/fr/assistantia/EtatDiscussion.java) gèrent les messages, le brouillon et le défilement en jeu.
- [Contrôleur de conversation](../../monifactory_assistant/application/conversation.py) reçoit les questions, les fiches d’objets et les événements vocaux. [Audio](../../monifactory_assistant/audio.py) gère la capture, la transcription et la lecture.
- [Construction du contexte](../../monifactory_assistant/application/question_context.py) assemble le texte des observations à partir de lecteurs explicites fournis par le contrôleur.
- [Session](../../monifactory_assistant/providers/session.py) délègue à [Codex](../../monifactory_assistant/providers/codex.py) ou [Claude](../../monifactory_assistant/providers/claude.py). [Discussion en lecture seule](../../monifactory_assistant/ui/discussion.py) sert le suivi local.

## Règles à préserver

- Texte et voix partagent l’état d’admission de Discussion. Un double clic ou un événement répété ne lance pas deux tours.
- Le clic **Parler** ouvre Discussion. La transcription et la réponse appartiennent à la bonne demande. Une réponse tardive reste attachée à son contexte d’origine.
- Le mod pilote la conversation. Le panneau local la suit en lecture seule. Le choix de fournisseur, les préférences et les sessions restent propres à l’installation.
- Pour un chargement bloqué, vérifier l’accusé de la demande, l’état affiché et le journal du fournisseur. Garder distincts un envoi accepté et une réponse reçue.

## Lecture du contexte d’une question

Le contrôleur enregistre la portée à l’admission. Le worker attend la transcription éventuelle, valide cette portée et lit les observations à mesure qu’il compose les sections. La sélection locale par nom ou identifiant d’objet s’appuie sur l’index des recettes. La branche EMI sélectionnée intervient dans la discussion via le résultat courant de Production, lorsqu’il est demandé.

Les lectures successives peuvent provenir de relevés distincts. Le pont et le service Production gardent leurs validations de portée et de preuve avant l’envoi et à la livraison. Une action de Discussion suit la validation du contrôleur avec l’état disponible au moment de son exécution.

## Tests à lancer

```sh
python -m unittest tests.test_discussion_menu tests.test_discussion_viewer tests.test_transport -v
python -m unittest tests.test_question_context tests.test_question_lifecycle tests.test_production_conversation tests.test_production_service -v
```

En jeu, essayer une question texte, un doublon de clic et le retour depuis une fenêtre ouverte par Discussion. Un parcours automatisé avec CLI simulé n’atteste pas un échange réel. [Usage en jeu](../utilisation.md#discussion-dans-minecraft)
