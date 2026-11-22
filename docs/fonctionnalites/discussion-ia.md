# Discussion, voix et moteurs IA

## Points d’entrée

- [OngletDiscussion](../../mod/src/main/java/fr/assistantia/OngletDiscussion.java) et [EtatDiscussion](../../mod/src/main/java/fr/assistantia/EtatDiscussion.java) gèrent les messages, le brouillon et le défilement en jeu.
- [Contrôleur de conversation](../../monifactory_assistant/application/conversation.py) reçoit les questions, les fiches d’objets et les événements vocaux. [Audio](../../monifactory_assistant/audio.py) gère la capture, la transcription et la lecture.
- [Construction du contexte](../../monifactory_assistant/application/question_context.py) assemble le texte des observations à partir de lecteurs explicites fournis par le contrôleur.
- [Index des recettes](../../monifactory_assistant/game/recipes.py) relie les noms cités aux objets du pack et joint leurs recettes à la question. Le [contrôleur de jeu](../../monifactory_assistant/application/game.py) le recharge après chaque export.
- [Session](../../monifactory_assistant/providers/session.py) délègue à [Codex](../../monifactory_assistant/providers/codex.py) ou [Claude](../../monifactory_assistant/providers/claude.py) et décide du renouvellement de leur session. [Session enregistrée](../../monifactory_assistant/providers/saved_session.py) tient l’identifiant, sa portée, la génération manuelle, le nombre de questions et l’heure de la dernière question dans `session.json` ou `session_claude.json`. [Discussion en lecture seule](../../monifactory_assistant/ui/discussion.py) sert le suivi local.

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

La reprise d’un fil exige aussi la même portée vérifiée (monde, connexion et dimension) et la même génération manuelle. La portée est fixée avant l’envoi et enregistrée avec l’identifiant dès son adoption, y compris si le tour échoue ensuite. Au redémarrage de l’assistant, seul le fichier principal peut autoriser une reprise. Une copie `.bak` peut contenir un fil déjà abandonné. Chaque question sans portée vérifiable démarre un fil neuf.

Le renouvellement n’envoie rien. La question en cours part une seule fois, précédée d’au plus deux échanges livrés dans la même portée vérifiée (monde, connexion et dimension) et la même génération de conversation. Ce rappel est étiqueté « Échanges précédents ». Il conserve le texte des réponses sans leurs actions et borne chaque texte à environ 1 200 caractères. Une question restée sans réponse n’y figure pas et n’est jamais renvoyée. Si la première question de la session neuve échoue, la suivante porte encore le rappel admissible.

La provenance du rappel reste en mémoire. Les messages restaurés depuis la vue, les échanges sans portée vérifiée et les réponses livrées après un changement de contexte peuvent rester visibles dans Discussion. Le rappel exclut ces messages.

**Nouvelle conversation** change immédiatement la génération et tente d’effacer l’identifiant du fichier principal. Une adoption tardive dans l’ancienne génération ne peut plus le réinscrire. Une réponse déjà en cours peut arriver et rester visible avec la mention « Réponse de la conversation précédente ». Ses actions sont écartées. Le fournisseur termine la remise à zéro avant d’accepter le premier tour de la nouvelle génération. Au démarrage, Codex réclame le fil enregistré seulement si la portée courante est vérifiée et si le fil reste réutilisable.

Si l’écriture d’invalidation échoue, le worker réessaie. Si l’assistant s’arrête avant qu’une écriture réussisse, l’ancien identifiant peut rester dans le fichier principal et être repris au redémarrage dans la même portée vérifiée.

## Lecture du contexte d’une question

Le contrôleur enregistre la portée à l’admission. Le worker attend la transcription éventuelle, valide cette portée et lit les observations à mesure qu’il compose les sections. La sélection locale par nom ou identifiant d’objet s’appuie sur l’index des recettes. La branche EMI sélectionnée intervient dans la discussion via le résultat courant de Production, lorsqu’il est demandé.

Les lectures successives peuvent provenir de relevés distincts. Le pont et le service Production gardent leurs validations de portée et de preuve avant l’envoi et à la livraison. Une action de Discussion suit la validation du contrôleur avec l’état disponible au moment de son exécution.

Pour une explication Production, le worker atteste la preuve avant l’envoi, puis à la fin de la réponse avec `delivery_verdict`. L’attente de la boîte et celle de la réponse du mod sont chacune bornées à 2 s pour cette dernière attestation. Un refus natif révoque le résultat encore lié. Si un autre consommateur tient la boîte et empêche l’attestation, le calcul reste disponible et Tk refuse la réponse.

Une fois l’événement sorti de la file, Tk vérifie la liaison et lit une seule fois l’acquittement natif (4 Kio au plus). Cette lecture s’applique aussi aux aperçus, après l’attestation qui précède l’envoi. Elle exige le catalogue, la portée, l’identifiant et l’empreinte de la capture. Un client Forge qui annonce `preuve_revocable` retire son acquittement dès que le tick constate que le validateur natif refuse la capture. Une réponse dont le reçu a disparu, a été remplacé par celui d’une autre capture ou est illisible est écartée avant l’historique et la voix. Un ancien JAR sans cette garantie entraîne le même refus. Tk n’écrit aucune demande et n’attend aucun acquittement. Le contrôle porte sur le dernier état publié par le client.

Les files de synthèse et de lecture conservent la preuve des réponses vocales Production. Le lecteur revérifie le résultat et l’acquittement local juste avant `sounddevice.play` ou le lancement de la voix de secours macOS ou Windows. Une nouvelle conversation manuelle rend ses anciennes réponses Production inadmissibles à la lecture. Si la preuve n’est plus courante, la réponse déjà livrée reste dans l’historique et le lecteur passe au message suivant. Une révocation après cette dernière lecture locale peut laisser démarrer ou continuer le son. Les réponses ordinaires gardent leur lecture progressive et ne consultent aucun acquittement Production.

`inventaire_details.txt`, lu seulement par l’IA, n’est relevé que pendant que l’assistant tourne (`Pont.assistantActif()`). Sans lui, rien n’est calculé. À son retour, le fichier est réécrit même inchangé. Seules les piles nouvelles ou modifiées (objet, quantité, usure, NBT) recalculent leur ligne, et le texte reste identique octet pour octet. Le fil d’écriture le remplace, puis écrit en mode isolé son marqueur `inventaire_details_contexte.json` une fois le texte écrit. [Stats](../../mod/src/main/java/fr/assistantia/Stats.java) · [Pont](../../mod/src/main/java/fr/assistantia/Pont.java)

## Recettes jointes à une question

L’index joint les recettes de trois objets cités au plus. Il traite d’abord les identifiants explicites dans leur ordre d’apparition, puis les noms longs. Il essaie l’identifiant entier avant de retirer les points finaux un par un, jusqu’au premier identifiant connu. Cette règle s’applique aussi à la sélection du contexte. Un nom d’objet exact garde la priorité sur le matériau. Par exemple, « glass » désigne `minecraft:glass` et « chromium » désigne le lingot, puis la poudre s’il reste une place. Quand le lingot manque, l’index choisit la poudre ou, à défaut, la gemme. Le fluide homonyme `gtceu:chromium` est choisi sur citation explicite. Les recettes de recyclage passent après les autres.

Python valide le manifeste `recettes_info.json`, le nombre de recettes et la génération des parties annoncées. L’index garde la liste exacte des parties validées. Il produit un fichier temporaire de recherche avec leurs lignes et le relevé des catégories de machines utilisé lors du même chargement. Si un nouveau manifeste est en cours de validation ou incomplet, le dernier index publié reste utilisable. Le nettoyage historique de l’ancien catalogue sans groupe intervient après publication d’un groupe a ou b. Avec un manifeste version 1 ou 2, les parties sans groupe restent utilisées.

Une question ou une fiche garde le même index jusqu’au retour du moteur. Les extraits, la sélection locale du contexte et le chemin temporaire correspondent à cet index, même si KubeJS republie le groupe initial. Le contexte indique le groupe et la génération retenus pour le tour. Les instructions de Claude et de Codex désignent ce fichier comme seule source locale de recettes pour le tour et traitent l’absence de référence comme une indisponibilité du catalogue. Leurs outils conservent leurs droits de lecture généraux sur le dossier du jeu.

## Tests à lancer

```sh
python -m unittest tests.test_discussion_menu tests.test_discussion_viewer tests.test_discussion_session tests.test_transport -v
python -m unittest tests.test_question_context tests.test_question_lifecycle tests.test_production_conversation tests.test_production_service -v
python -m unittest tests.test_recipe_loading tests.test_upstream_parity -v
python -m unittest tests.test_providers tests.test_integration tests.test_ai_settings -v
python -m unittest tests.test_stats_publication tests.test_bridge_world_scope -v
```

En jeu, essayer une question texte, un doublon de clic et le retour depuis une fenêtre ouverte par Discussion. Un parcours automatisé avec CLI simulé n’atteste pas un échange réel. [Usage en jeu](../utilisation.md#discussion-dans-minecraft)

`test_production_service` exerce les deux files audio avec des barrières de test et des sorties sonores simulées. Le test `test_revoked_production_voice_and_provider_recall_across_worlds` retire le reçu pendant la synthèse dans A, passe dans B et démarre une nouvelle conversation. Il contrôle les phrases jouées et les prompts reçus par un CLI Claude simulé, y compris le rappel après renouvellement dans B.

Dans une copie du pack, rejouer une explication vocale Production avec la capture valide puis révoquée pendant l’attente, avec la voix neuronale et le secours natif de la plateforme.
