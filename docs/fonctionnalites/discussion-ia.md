# Discussion, voix et moteurs IA

## Points d’entrée

- [OngletDiscussion](../../mod/src/main/java/fr/assistantia/OngletDiscussion.java) et [EtatDiscussion](../../mod/src/main/java/fr/assistantia/EtatDiscussion.java) gèrent les messages, le brouillon et le défilement en jeu.
- [Contrôleur de conversation](../../monifactory_assistant/application/conversation.py) reçoit les questions, les fiches d’objets et les événements vocaux. [Audio](../../monifactory_assistant/audio.py) gère la capture, la transcription et la lecture.
- [Construction du contexte](../../monifactory_assistant/application/question_context.py) assemble le texte des observations à partir de lecteurs explicites fournis par le contrôleur.
- [Index des recettes](../../monifactory_assistant/game/recipes.py) relie les noms cités aux objets du pack et joint leurs recettes à la question. Le [contrôleur de jeu](../../monifactory_assistant/application/game.py) le recharge après chaque export.
- [Session](../../monifactory_assistant/providers/session.py) délègue à [Codex](../../monifactory_assistant/providers/codex.py) ou [Claude](../../monifactory_assistant/providers/claude.py) et décide du renouvellement de leur session. [Session enregistrée](../../monifactory_assistant/providers/saved_session.py) tient l’identifiant, le nombre de questions et l’heure de la dernière question dans `session.json` ou `session_claude.json`. [Discussion en lecture seule](../../monifactory_assistant/ui/discussion.py) sert le suivi local.

## Règles à préserver

- Texte et voix partagent l’état d’admission de Discussion. Un double clic ou un événement répété ne lance pas deux tours.
- Le clic **Parler** ouvre Discussion. La transcription et la réponse appartiennent à la bonne demande. Une réponse tardive reste attachée à son contexte d’origine.
- Le mod pilote la conversation. Le panneau local la suit en lecture seule. Le choix de fournisseur, les préférences et les sessions restent propres à l’installation.
- Pour un chargement bloqué, vérifier l’accusé de la demande, l’état affiché et le journal du fournisseur. Garder distincts un envoi accepté et une réponse reçue.
- Le journal note `Temps : premier mot X s, fin Y s`. Le premier mot correspond au premier aperçu non vide affiché, la fin à la remise de la réponse.

## Session du fournisseur

Chaque question joint environ 40 Ko de contexte du monde. Comme l’assistant d’origine, une session garde au plus 10 questions. Avant une question, la façade [Session](../../monifactory_assistant/providers/session.py) ouvre une session neuve quand la session enregistrée :

- compte déjà 10 questions ;
- a reçu sa dernière question il y a plus de 4 h ;
- vient d’un ancien fichier sans compteur.

Le compteur et l’heure de la dernière question survivent au redémarrage de l’assistant. La règle est commune à Claude et Codex.

Le renouvellement n’envoie rien. La question en cours part une seule fois, précédée d’au plus deux échanges livrés dans la même portée vérifiée (monde, connexion et dimension) et la même génération de conversation. Ce rappel est étiqueté « Échanges précédents ». Il conserve le texte des réponses sans leurs actions et borne chaque texte à environ 1 200 caractères. Une question restée sans réponse n’y figure pas et n’est jamais renvoyée. Si la première question de la session neuve échoue, la suivante porte encore le rappel admissible.

La provenance du rappel reste en mémoire. Les messages restaurés depuis la vue, les échanges sans portée vérifiée et les réponses livrées après un changement de contexte peuvent rester visibles dans Discussion. Le rappel exclut ces messages.

**Nouvelle conversation** change la génération immédiatement. Une réponse déjà en cours peut arriver et rester visible avec la mention « Réponse de la conversation précédente ». Ses actions sont écartées. Le fournisseur termine la remise à zéro avant d’accepter le premier tour de la nouvelle génération. Au démarrage, Codex réclame la session enregistrée seulement si elle ne doit pas être renouvelée.

## Lecture du contexte d’une question

Le contrôleur enregistre la portée à l’admission. Le worker attend la transcription éventuelle, valide cette portée et lit les observations à mesure qu’il compose les sections. La sélection locale par nom ou identifiant d’objet s’appuie sur l’index des recettes. La branche EMI sélectionnée intervient dans la discussion via le résultat courant de Production, lorsqu’il est demandé.

Les lectures successives peuvent provenir de relevés distincts. Le pont et le service Production gardent leurs validations de portée et de preuve avant l’envoi et à la livraison. Une action de Discussion suit la validation du contrôleur avec l’état disponible au moment de son exécution.

Pour une explication Production, le thread Tk n’atteste jamais la preuve auprès du client natif. Les aperçus, dans le worker comme dans Tk, vérifient seulement la liaison du résultat avec `context_bound`. Le worker atteste la preuve avant l’envoi, puis à la livraison avec `delivery_verdict`, qui attend jusqu’à 2 s la boîte et la réponse du mod. Il transmet ce verdict avec la réponse. Tk le combine avec la liaison courante, car la portée ou le monde peut changer entre-temps. Sans verdict, parce qu’un autre consommateur tient la boîte, le calcul n’est pas révoqué et la réponse n’est pas livrée comme courante.

`inventaire_details.txt`, lu seulement par l’IA, n’est relevé que pendant que l’assistant tourne (`Pont.assistantActif()`). Sans lui, rien n’est calculé. À son retour, le fichier est réécrit même inchangé. Seules les piles nouvelles ou modifiées (objet, quantité, usure, NBT) recalculent leur ligne, et le texte reste identique octet pour octet. Le fil d’écriture le remplace, puis écrit en mode isolé son marqueur `inventaire_details_contexte.json` une fois le texte écrit. [Stats](../../mod/src/main/java/fr/assistantia/Stats.java) · [Pont](../../mod/src/main/java/fr/assistantia/Pont.java)

## Recettes jointes à une question

L’index joint les recettes de trois objets cités au plus, les noms longs d’abord. Un nom d’objet exact reste prioritaire : « chromium plate », ou « glass » pour `minecraft:glass`. Un nom de matériau seul (« chromium », « stainless steel », « tungstensteel ») désigne son lingot, sinon sa poudre, sinon sa gemme. La poudre accompagne le lingot quand il reste une place. Le fluide homonyme du matériau (`gtceu:chromium`) est écarté, sauf s’il est cité par son id : ses recettes ne font que recycler des pièces. Les recettes de recyclage passent après les autres.

Le contexte de chaque question nomme le groupe actif du catalogue, `recettes_a_*.json` ou `recettes_b_*.json`. Les instructions des deux moteurs y renvoient, ou à `recettes_info.json` tant qu’aucun index n’est chargé. Une fois un groupe a ou b publié, l’ancien catalogue sans groupe (`recettes_<n>.json`) est supprimé du dossier de données. Avec un manifeste version 1 ou 2, ces fichiers restent le catalogue actif.

## Tests à lancer

```sh
python -m unittest tests.test_discussion_menu tests.test_discussion_viewer tests.test_discussion_session tests.test_transport -v
python -m unittest tests.test_question_context tests.test_question_lifecycle tests.test_production_conversation tests.test_production_service -v
python -m unittest tests.test_recipe_loading tests.test_upstream_parity -v
python -m unittest tests.test_providers tests.test_integration tests.test_ai_settings -v
python -m unittest tests.test_stats_publication tests.test_bridge_world_scope -v
```

En jeu, essayer une question texte, un doublon de clic et le retour depuis une fenêtre ouverte par Discussion. Un parcours automatisé avec CLI simulé n’atteste pas un échange réel. [Usage en jeu](../utilisation.md#discussion-dans-minecraft)
