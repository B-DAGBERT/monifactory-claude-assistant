# Qualification de Monifactory Assistant

Référence `ffd3a49` de `origin/main`, propre et confirmée avant le travail. La suite initiale
comptait 322 tests réussis sur macOS Apple Silicon avec Python 3.12, Node 22 et JDK 17.
Les CLI IA sont simulés dans les tests. Les builds de qualification ne sont pas installés.

| Lot                    | Verdict | Branche                               | Preuve                                                                                                               |
| ---------------------- | ------- | ------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| 1. Fraîcheur           | PASS    | `fiabilite/fraicheur-observations`    | Dates, redémarrage, expiration, changement de dimension, reconnexion et positions multijoueurs dans les deux prompts |
| 2. Scans serveur       | PARTIAL | `performance/mesure-scans-serveur`    | Diagnostic local volontaire et paquets identiques. Mesures réelles à faire                                           |
| 3. Contexte IA         | PASS    | `performance/contexte-ia`             | Sélection déterministe, repli étendu, prompt unique, données de recette inchangées                                   |
| 4. Écritures et caches | PASS    | `performance/snapshots-stock`         | Mesure des écritures, résultats EMI identiques et reprise sur fichiers incomplets                                    |
| 5. Mondes              | PARTIAL | `fiabilite/isolation-mondes`          | Préparation sauvegarde/restauration hors de la version livrée. Routage par monde non activé                          |
| 6. Qualification       | PARTIAL | `qualification/fiabilite-monifactory` | Suite locale, scénarios EMI, formatages, build. Validation en jeu réel et Windows 11 à faire                         |

## Lot 1

Un nouveau paquet serveur pouvait faire présenter d’anciennes machines comme étant « en direct ».
`game/world.py` sépare la dernière trame valide et la mémoire. `application/game.py` invalide le
direct lors d’une sortie ou d’un changement de dimension. `game/context.py`, le contexte de
Discussion, la fiche de bloc et `OngletBase.java` indiquent le dernier relevé. Un historique
reste disponible lorsque le script facultatif manque. Un relevé ancien, incomplet ou d’une autre
dimension ne rend pas une machine historique actuelle.

Tests dans `tests/test_world_freshness.py`, `tests/test_storage_snapshots.py` et les scénarios complexes.
Les tests de parité contrôlent aussi les chemins qui n’ont pas changé. Commit principal `0070693`,
qualification de parité `2ca966c`. Redémarrer l’assistant et Minecraft pour charger le nouvel
affichage. Le script serveur v4 installé reste compatible.

## Lot 2

Le v4 limite déjà la cadence et ne lit que les chunks chargés. Le coût d’un scan individuel
était inconnu. Le diagnostic de `serveur/assistant_ia_serveur.js` mesure les lectures, le temps et
la taille des paquets sur une fenêtre bornée. Le protocole et les résultats synthétiques figurent dans `docs/performance-serveur.md`
sur la branche locale du commit `6c4f101`. La cadence, la protection FTB Chunks, les positions des
joueurs et la couverture des relevés restent identiques dans les scénarios comparés.

Le diagnostic est inactif par défaut. Un relevé serveur n’a besoin d’aucun mod supplémentaire.
L’activer sur une copie de test du script demande une intervention explicite de l’hébergeur.
Les 15 tests Node/Python et les 9 fixtures rejouées avec Rhino réussissent. Sur Node, le P95
du gros relevé tronqué est de 30,32 ms à la référence et 30,58 ms avec le diagnostic inactif.
Les mesures Node et la validation Rhino ne chiffrent pas les ticks d’un serveur réel. Les plages
OFF se chevauchent. La réécriture de l’ordonnancement et la modification du plafond restent écartées
faute de gain réel vérifié. Le commit reste hors de `main`.

## Lot 3

Les questions très précises recevaient les mêmes sections volumineuses que les diagnostics
généraux. Le choix de sections s’effectue localement dans `game/context.py` et
`application/conversation.py`. Les demandes ambiguës, les projets et les diagnostics qui dépendent
du voisinage continuent à recevoir le contexte étendu. Les positions multijoueurs, les actions
JSON et les sessions sont conservées. Les catalogues de recettes restent accessibles en lecture seule.
Commit `03bb988`, 47 tests de contexte, fraîcheur, parité et fournisseurs sur CLI simulés.
Le guide de développement donne le protocole reproductible et les résultats. Les prompts
de recette, recherche et réglage diminuent d’environ 70 à 75 %. Les quatre prompts de repli
du jeu de mesure restent identiques octet par octet. Redémarrer l’assistant après mise à jour.
Les temps de réponse et la qualité des réponses réelles restent à observer.

## Lot 4

Les mises à jour de date réécrivaient un JSON indenté et invalidaient la recherche EMI. Le JSON de
mémoire serveur est désormais compact, avec la même structure, les mêmes dates et la même écriture
atomique. La fusion départage toujours le serveur et le coffre ouvert avec leurs horodatages.
La recherche réutilise ses correspondances quand le contenu fusionné et les coordonnées sont
identiques. Les observations brutes concurrentes au même emplacement restent comparées par date.

Le scénario synthétique utilise 200 relevés, 600 blocs dont 100 machines, 12 cases par inventaire et
une recherche active. `python -m tests.benchmarks.storage_snapshots` le rejoue dans un dossier
temporaire. Tester avant et après avec la même version de Python et les mêmes données.
Le commit `19b19be` donne 399 remplacements atomiques avant et après, 227,69 Mo puis 142,54 Mo
écrits, 200 puis 1 reconstructions EMI, et 600 blocs restaurés avec les dates exactes.
La médiane CPU passe de 6,57 à 3,42 s sur trois répétitions. Les tests utilisent le même
mécanisme atomique et simulent un refus de remplacement comme lors d’un verrou de fichier.
Les tests de refus de remplacement, de reprise et d’inventaire déplacé gardent la protection
des écritures et le choix du relevé le plus récent. Aucun report de sauvegarde ni migration de format.

## Lot 5

Les sources historiques indexent plusieurs observations par dimension et coordonnées. Ces valeurs
peuvent coïncider entre deux mondes. Les fichiers historiques n’établissent pas toujours la provenance
du stock, d’une machine ou d’un projet. L’identifiant GregTech persistant est exploitable pendant
une connexion confirmée. L’ancienne clé des filons dépend d’un chemin local. Une transition nécessite
une correspondance démontrée, sans réattribuer les observations anciennes.

Un paquet KubeJS client déjà en file peut être remis avec le joueur de la connexion courante.
L’identité de ce joueur ne prouve donc pas l’origine du paquet. L’étape suivante exige de qualifier
le contrôle de la connexion réseau originale ou un protocole explicite de liaison de session sur
deux connexions réelles. L’activation de nouveaux espaces et le rattachement des projets restent
en attente de cette preuve et de la restauration vérifiée sur les deux plateformes.

### Sauvegarde et restauration de test

Le commit local `111c8d1` prépare les commandes explicites `inventory`, `backup`, `verify` et
`restore` dans `monifactory_assistant.commands.backup_data`. L’inventaire couvre les fichiers
JSON et JSONL de la racine Python, leurs copies historiques, les configurations, projets, lieux,
sessions, journaux et dossiers `backups`, `captures` et `.runtime`. La racine
`kubejs/assistant` est inventoriée intégralement, notamment les stocks, filons et fichiers
de synchronisation. Les formats inconnus restent opaques et sont copiés octet par octet.

Le dossier d’archive est choisi hors des deux sources. Il contient les racines `python/` et
`kubejs-assistant/`, un `manifest.json` d’empreintes SHA-256 et un `complete.json` qui scelle
le manifeste. La publication se fait par renommage sans remplacement après vérification.
Dans les 22 tests synthétiques sur Mac, les empreintes après restauration sont identiques,
une relance réutilise une archive vérifiée à sources identiques, une coupure conserve les
sources, et une destination existante est refusée. Les tests incluent les sessions, projets,
formats invalides, changements concurrents et liens. La procédure figure dans
`docs/sauvegarde-donnees.md` sur la branche du lot 5.

Le retour à un jeu de fichiers archivé commence par `verify`, puis `restore` vers un parent
neuf. Vérifier les deux racines restaurées avant toute reprise manuelle. Les tests utilisent
des répertoires temporaires. Les données des installations existantes restent à leur emplacement,
sans nouvelle attribution de monde ni migration active.

## Livraison et qualification locale

La version `shared.39` réunit les lots 1, 3 et 4. Le contrôle Python (Ruff), le formatage de la
documentation (Prettier), le contrôle Java Gradle et la compilation avec le JDK 17 sont réalisés
sur le Mac. La suite de livraison compte **342 tests réussis** et le parcours `scenarios tous`
compte **26 cas réussis**. Les scénarios basique et complexe couvrent les parcours EMI et la
reprise de lots.
Les tests de fournisseur utilisent des CLI simulés et préservent la séparation des sessions.
L’installation du JAR partagé demande une relance complète de Minecraft et de l’assistant.
Les branches locales des lots 2 et 5 restent en dehors de la livraison. Le script serveur v4
actuellement utilisé reçoit les mêmes requêtes et renvoie le même format.

## Qualification restante

Vérifier en partie réelle la fiche d’une machine après déconnexion, la recherche EMI et les
transferts existants. Faire un essai Windows 11 avec Claude Code et un essai macOS avec Codex sur
les mêmes questions. Sur un serveur de test autorisé, relever le P95 dans une base GregTech et
vérifier que deux joueurs conservent leurs positions réciproques et leurs droits sur les coffres.
Les simulations ne mesurent ni les FPS ni le MSPT d’une vraie partie.
