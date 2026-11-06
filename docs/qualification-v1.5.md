# Qualification V1.5

Référence `b8e1b92` sur `origin/main`. Voir le [rapport d’audit](audit-architecture-v1.5.md)
pour les scénarios de reproduction et l’ordre de remédiation. Qualification sur copies de
dépôt et de données. Les essais n’utilisent aucun compte Codex ou Claude, aucune instance
personnelle et aucun serveur Minecraft.

## Référence et environnement

| Contrôle                                   | Référence macOS Apple Silicon                                                                                                                        |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Python 3.12, Ruff, Prettier et dépendances | Format et lint Python, format Markdown/web, `npm ci --offline`, `pip check` et `git diff --check` passent                                            |
| Java 17.0.20.1, Forge 1.20.1               | `checkJavaFormat` et `python codex_setup.py build` passent. Artefact `assistantia-1.0.0-shared.40.jar`                                               |
| Suite automatisée                          | 403 tests passent avec les sockets HTTP locaux autorisés. Le premier lancement les bloquait et a échoué sur dix tests du miroir Discussion           |
| Scénarios EMI                              | 26 scénarios basiques et complexes passent                                                                                                           |
| Versions et permissions                    | `serveur/assistant_ia_serveur.js` et les workflows GitHub restent hors du périmètre. Les commandes de migration et le format du lot 5 sont conservés |

## Corrections qualifiées localement

Les identifiants B, J, P et R renvoient aux constats de l’audit. Les validations Python
et Git utilisent des entrées de production, des horloges ou des processus contrôlés et
des dépôts temporaires. Chaque correction reste réversible par le retrait de son commit.
Le périmètre Python et Git ci-dessous est **PASS** pour ses contrôles automatisés.
La révision testée `11efdc4` totalise **451 tests** et **26 scénarios EMI** sur macOS.

| ID      | Résultat avant/après                                                                                                                                                                                                                                                                                     | Tests et compatibilité                                                                                                                     | Commit                          |
| ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------- |
| R1      | Une exception pouvait terminer l’enfant avec succès ou désarmer la surveillance. L’enfant rend un échec au superviseur, la boucle se réinscrit et le journal indisponible n’arrête plus le traitement                                                                                                    | `test_runtime_resilience.py`, `test_startup.py`. Supervision Python commune                                                                | `1c7177b`                       |
| R2      | Deux chargements pouvaient se chevaucher. Un échec validait prématurément la version. Un seul chargement est lancé, les deux index sont publiés sur Tk si la version est encore courante et une erreur préserve l’index précédent                                                                        | `test_recipe_loading.py`. Remplacement de même date et taille, erreur de lecture et nouveau catalogue machine                              | `3ccb7e1`                       |
| R3      | La lecture récente chargeait tout le journal. La lecture par blocs garde les 25 derniers relevés et ignore la ligne endommagée. Le fichier et ses formats valides restent identiques                                                                                                                     | `test_journal_tail.py`, `test_question_context.py`, benchmark ci-dessous. Cas UTF-8 et CRLF                                                | `ea9d01e`                       |
| P1      | Une fusion pouvait écraser un plan ignoré. Les fusions et transitions historiques passent le garde Git qui refuse d’écraser ces fichiers                                                                                                                                                                 | `test_updates.py`, `test_auto_updates.py`. Vérification dans de vrais dépôts temporaires et comparaison du plan local                      | `67451e9`                       |
| P2      | Une trame de touches invalide arrêtait le lecteur Mac. Le lecteur libère les touches et reprend sans consommer ni rejouer les transitions invalides                                                                                                                                                      | `test_mac_keys_resilience.py`, vrai thread de lecture, trames tronquées ou périmées et reconnexion                                         | `62ae0c1`                       |
| B1 à B4 | Le canal historique réexécutait une demande. Il utilise maintenant la réservation et le reçu existants. Les reçus projet, actions JSON invalides et coordonnées de coffres interdimensions sont pris en charge par les contrôles ciblés. Les numéros invalides sont écartés avant le calcul de fraîcheur | `test_bridge_resilience.py`. Redémarrage, refus de réservation, confirmation perdue, anciens clients et réponses des deux moteurs simulées | `c13537c`, `22c70ef`, `3460a6b` |
| R4      | L’effacement écrit un seul état final. La copie précédente contient de nouveau l’objectif, les étapes et les données du plan. Un échec laisse également le projet affiché intact                                                                                                                         | `test_project_clear.py`, régression des pauses et projets, comparaison de la sauvegarde octet par octet                                    | `c50c373`                       |

**B6, PASS.** Un ancien fichier ne remplace plus le reçu déjà enregistré pour son numéro.
Les cas accepté et refusé passent à +46 secondes, sans nouveau tour, dans
`test_bridge_resilience.py`. Commit `11efdc4`.

## Clôture de l’intégration V1.5

La branche d’intégration part de `origin/main` à `6e4b684`. Le candidat local est exactement
`1a56fe6`, sans commit ultérieur sur sa branche. `6e4b684` en est un ancêtre. L’avance rapide
reprend les 21 fichiers des six correctifs du candidat et conserve les correctifs Python et Git
déjà publiés. L’arbre de code est identique à celui qui avait passé les tests à `d2dd64d`.

Le JAR conserve le nom `assistantia-1.0.0-shared.40.jar`. Le script client passe de 11 à 12.
La file durable et le catalogue restent en version 2. Le serveur facultatif, les formats de
migration, les fournisseurs IA et le mode d’activation du lot 5 restent hors du diff.

Les deux utilisateurs déclarent leurs essais réels satisfaisants et acceptent la clôture sur ce
périmètre. Cette déclaration est distincte des tests automatisés et ne fournit ni journal de test
ni empreinte du JAR chargé sur leurs postes. Les validations détaillées de la migration personnelle
et des reprises non interceptables restent distinctes.

Les statuts du tableau décrivent les limites des preuves techniques détaillées. L’acceptation
utilisateur permet d’intégrer ce périmètre sans transformer ces limites en preuves automatisées.

Le code destiné à la publication passe **466 tests** et **26 scénarios EMI** sur macOS. Ruff,
Prettier, `pip check`, le format Java, la compilation Forge avec JDK 17, la syntaxe Node et
`git diff --check` passent. Rhino `2001.2.3-build.10` rejoue les 13 cas de commande et le
changement de monde. Le JAR de cette compilation locale a l’empreinte SHA-256
`2ae88ba9c126a1a193d7d5fa35cb64cdf11c5206f8e7c80538e6ffd1f4d64df6`. Il n’a pas été installé.

| ID  | Avant → après et preuve                                                                                                                                                                                                                                                      | Statut                                                                       | Commit    |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | --------- |
| J1  | Une réponse d’une ancienne connexion pouvait modifier le cache natif du backpack. Le hook capture la connexion Forge avant mise en file et rejette un ancien paquet avant le cache. Signatures du pack inspectées, tests ancien/actuel/vide/déconnecté verts                 | **PARTIAL**. Tissage et deux connexions réelles à vérifier                   | `716e4b9` |
| J2  | Une reconnexion au même monde pouvait relire un relevé antérieur. Le dernier instantané reste disponible jusqu’à l’écriture, avec reprise et invalidation de l’export détaché. Tests sur exécuteur suspendu, échec, reconnexion, logout et reprise verts                     | **PARTIAL**. Reconnexion Forge et fichier verrouillé sous Windows à vérifier | `0620c65` |
| J3  | L’export était marqué fait avant l’écriture. Il est acquitté après publication, y compris texte et empreinte. Le catalogue calculé est conservé pendant une attente d’écriture. Tests d’échec de chaque fichier et reprise verts                                             | **PARTIAL**. Vérifier les blocages de fichiers sous Windows                  | `4f81c66` |
| J4  | Le filtre des événements de craft précède les comparaisons d’inventaire. À 1 000 types, 2 499 500 → 0 comparaisons sans craft et 2 511 512 → 4 012 avec un craft épars, pour ce parcours de preuve. Tests de preuve, machine et réception verts                              | **PARTIAL**. Mesure de complexité sur fixture. Vérifier EMI en jeu           | `12dfa9e` |
| B5  | Un premier fichier apparu après initialisation était perdu. Le numéro présent au changement de contexte est amorcé, les anciens fichiers restent inertes et le premier nouvel envoi est lu. 13 cas Node et Rhino exact du pack, changement de monde et parité passent        | **PARTIAL**. Rechargement et connexion Forge réels à vérifier                | `2022ed8` |
| P3  | Une interruption interceptée entre JAR et script laissait une installation mixte. Les deux fichiers sont préparés avant publication. Une exception ou interruption interceptable remet les deux versions précédentes. Les comparaisons binaires et sept cas injectés passent | **PARTIAL**. Remplacement et verrouillage NTFS à vérifier                    | `a215ed1` |

## Points ouverts

- **P4, PARTIAL.** L’installation conserve une sauvegarde lors d’une interruption brutale. Les
  tests de reprise couvrent les interruptions interceptables. L’activation du venv, la fusion
  avant installation dans l’ancien lanceur, la coupure entre déploiement et fusion et un échec
  de la restauration elle-même exigent une qualification de la transaction complète.
- **R2, limite de couverture.** Le marqueur identifie la révision publiée du catalogue. Il
  ne rend pas transactionnelle la réécriture de plusieurs fichiers de recettes par KubeJS.
- **R5, BLOCKED.** Le modèle peut proposer l’effacement d’un plan. La sauvegarde immédiate est
  protégée par R4. L’autorisation explicite en jeu reste à définir pour conserver les commandes
  de suppression légitimes dans la discussion et à la voix.
- **NO CHANGE NEEDED.** La composition des contrôleurs, les adaptateurs des deux CLI, les
  règles de transferts natifs et le protocole de migration restent adaptés aux constats de l’audit.
- **REJECTED.** Refaire l’architecture dans ce lot ne répond pas aux mesures locales.
- **Lot 2 reporté.** Le planificateur des scans serveur suit le périmètre déjà en production.

## Mesures locales

`python -m tests.benchmarks.journal_tail` crée trois journaux synthétiques. Pour chacun,
21 mesures lisent les mêmes 25 dernières lignes et vérifient les dates retournées. Le pic
vient de `tracemalloc` sur une lecture distincte. La mesure de référence utilise le même
script et le code `b8e1b92`.

| Lignes  |  Taille | Médiane avant → après | Pic d’allocations Python avant → après |
| ------- | ------: | --------------------: | -------------------------------------: |
| 40      |  7,8 ko |      0,057 → 0,060 ms |                         28,5 → 25,0 ko |
| 1 000   |  194 ko |      0,230 → 0,061 ms |                          587 → 25,5 ko |
| 100 000 | 19,4 Mo |      25,91 → 0,066 ms |                     58,21 Mo → 25,5 ko |

La différence sur 40 lignes est de l’ordre de quelques microsecondes. Sur le scénario
long, la lecture a coûté environ 390 fois moins de temps dans cet essai. Le pic mesuré
concerne les allocations de cette fonction, et non la mémoire totale de l’assistant.
Le benchmark `tests.benchmarks.question_context` produit les mêmes sept empreintes avant et
après, avec les positions multijoueurs et la pause. Le benchmark `tests.benchmarks.storage_snapshots`
retrouve les mêmes dates et observations sur 600 blocs, avec 200 fusions et une reconstruction
de recherche dans les deux versions. Ces mesures portent sur les traitements locaux.

Sur sept processus neufs par révision, l’import Python de `monifactory_assistant.app`
mesure 79,9 → 80,8 ms en médiane, avec environ 46,1 Mo de mémoire résidente maximale
et un seul thread après import des deux côtés. Cet essai s’arrête avant Tk, la voix et
le chargement d’une partie.

## Rejouer la qualification sur Windows 11 et en jeu

Sur un checkout de qualification, avec Git et Node.js disponibles, depuis PowerShell.

```powershell
py -3.12 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe -m ruff format --check .
.\.venv\Scripts\python.exe -m ruff check .
npm ci
npm run format:check
$env:JAVA_HOME = 'C:\chemin\vers\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\mod\gradlew.bat -p mod checkJavaFormat --console=plain
.\.venv\Scripts\python.exe codex_setup.py build
.\.venv\Scripts\python.exe -m monifactory_assistant.commands.scenarios tous
git diff --check
```

Puis utiliser des copies d’instance et des connexions de test pour valider le chargement
du JAR, la provenance d’un paquet de backpack retardé, les filons après reconnexion et
le remplacement des fichiers avec un verrou temporaire NTFS. Pour la lecture de données,
comparer les mêmes coordonnées dans deux dimensions, les coffres protégés par FTB,
les positions des autres joueurs et un objectif en pause. Vérifier les parcours EMI
de retrait, circuit virtuel, fluide, catalyseur, réception et backpack selon
[les scénarios](scenarios.md). Les vérifications de détail qui ne sont pas décrites par les
utilisateurs gardent leur statut de preuve précédent.
