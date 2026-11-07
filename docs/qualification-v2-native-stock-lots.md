# Qualification V2 des preuves natives de stock et de fabrication

**Publication shared.45 autorisée sur main le 7 novembre 2026. Qualification partielle.** Damien a demandé l'intégration après le compte rendu des 664 tests, des essais Mac et des limites des preuves natives. Windows 11, Claude, NTFS et FTB Chunks en multijoueur restent à qualifier. Le calculateur conserve le Recipe Tree EMI et le planificateur existants. Le nouveau pont permet d'utiliser les quantités dont la provenance peut être contrôlée, puis sépare la collecte, la production attendue et la réception.

## A — État Git et artefacts

| Référence                                            | Commit ou empreinte                                                        |
| ---------------------------------------------------- | -------------------------------------------------------------------------- |
| `origin/main` au préflight                           | `5d280c3a949f4a6521fb1a4eed0303550dd25ec9`                                 |
| Candidat de code shared.42                           | `a6a2d68dd56fa117d1d70a492733b4a709016ccd`                                 |
| Branche publique shared.42                           | `c5bc64753beceb836aa6bea5c1b7561dc545d7e2`                                 |
| Code shared.45, branche `codex/v2-native-stock-lots` | `5aa2a5496a9f143823df3c322da1ef00d8a8a732`                                 |
| Source Java du JAR                                   | `89090ed01434ab2447964ce8420f20cfa7dd7f80`                                 |
| JAR `assistantia-1.0.0-shared.45.jar`                | SHA-256 `0ff18ecf0acf02bd6fdaa93fe8d9f7efed7d68b62f70ccfb4d0f49af9aa9bf08` |
| Script client KubeJS 12                              | SHA-256 `a77b218d8778fd3dc123444a58b1e194d226080db0121e6c81d8e87175387f1e` |
| R5, référence hors campagne                          | `5105320384d5e9e7f56eb58e2662831f07f1e329`                                 |

La branche de travail part du commit public shared.42. R5 est une référence Git locale. Le contrôle distant a retrouvé main et la branche publique shared.42 à leurs commits annoncés. L'historique de cette campagne reste consultable dans [l'ancien rapport V2](qualification-v2.md). Le JAR shared.45 et le Python de ce candidat parlent la version 2 du contrat de preuves. Le calcul refuse explicitement un couple incompatible. Le suivi de lots utilise son sous-contrat 3. Une ancienne projection de suivi est refusée indépendamment d’une capture de stock valide. La version du script client KubeJS reste 12. Les fichiers de progression, la file durable et les formats historiques gardent leurs rôles existants.

## B — Acquisition de stock

| Donnée                                          | Source native et preuve                                                                                                                                                                       | Consommateur et couverture                                                                                                        |
| ----------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Inventaire personnel                            | `InventaireJoueur` vérifie la révision courante. La capture personnelle regroupe le stock natif et les sacs synchronisés.                                                                     | Production alloue les variantes sans NBT attestées. Le stock physique hors relevé reste inconnu.                                  |
| Backpack porté                                  | `SacsPortes` conserve l'identité et la synchronisation natives.                                                                                                                               | Compté avec le stock personnel quand synchronisé, séparé de la capture du menu du coffre.                                         |
| Coffre simple ou shulker                        | `StockProduction` observe le paquet complet reçu par le menu effectivement ouvert côté serveur. La connexion, le menu et sa révision doivent correspondre.                                    | 36 cases du joueur, cases du coffre et curseur forment trois sources disjointes. Les ressources portées sont allouées en premier. |
| Coffre double vanilla                           | Le même menu contient les deux moitiés et un seul ensemble de cases joueur.                                                                                                                   | Les cases de stockage sont comptées une fois. Les relevés de menus successifs ne sont jamais additionnés.                         |
| Coffre Sophisticated Storage                    | Paquet complet spécifique, confirmé par les informations natives sur les cases.                                                                                                               | Les cases de filtre, infinies, interdites ou inactives sont exclues. Les quantités étendues sont conservées.                      |
| Drawer, coffre fermé, plusieurs menus distincts | Le scan EMI, les relevés de stockage et la fusion de `game/storage.py` restent les sources existantes. Il n'existe pas de révision commune avec les 36 cases du joueur pour ces observations. | Production signale l'historique sans détailler son contenu si l'accès actuel est inconnu. Quantité actuelle non allouable.        |
| Variante NBT ou liquide en stockage             | La capture physique ne démontre pas de manière générale l'équivalence exacte avec le besoin de la recette.                                                                                    | La variante ou le fluide reste inconnu pour cette allocation. Les fluides de la recette gardent leur unité mB.                    |

Le paquet complet du menu fournit la cohérence. L'heure d'observation limite la durée d'utilisation à 60 secondes et n'établit aucune cohérence à elle seule. Un clic, une variation reçue, une modification détectée des cases ou du curseur, une fermeture ou un changement de contexte révoque ce relevé. Pour reprendre une mesure après un transfert, fermer puis rouvrir le coffre. Dans ce mode, les sacs, l'armure et la seconde main ne proviennent pas de la même trame et restent hors couverture. L'ouverture réussie du menu serveur est le préalable de lecture. Production ne lit pas directement une entité de bloc ni un coffre refusé. La vérification en conditions FTB Chunks à deux joueurs figure dans la procédure de livraison.

Le bouton de calcul crée un relevé portant la session, le monde, la dimension, le projet, la révision du projet et la génération du catalogue EMI. Le fichier temporaire est publié par remplacement atomique, avec une empreinte liée à la demande. Le calcul et la relecture du résultat utilisent le défi natif existant, étendu à cette empreinte. Une réponse dont la preuve ne peut plus être renouvelée sort de la vue courante, même si son ancien fichier reste lisible. Les relevés historiques et les projets sont préservés.

## C — Lots et réceptions

| Transition           | Objet propriétaire et preuve                                                                                                                                                            | Limite                                                                                                                                                                            |
| -------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Réservation          | `MachinesEmi`, `SuiviLotsEmi` et les références de lots natifs possèdent l'état de suivi EMI.                                                                                           | Le relevé n'établit pas les quantités physiques réservées, par ingrédient et par emplacement. Elles restent inconnues.                                                            |
| Déclaration ou envoi | `LotMachine` et `LotFabrication` fournissent l'identifiant natif, la recette et l'origine. Un envoi ne comporte une clé de machine que si cette preuve existe déjà.                     | La déclaration du joueur signifie une attente. L'envoi ne prouve ni un cycle de machine terminé ni toutes les quantités d'entrées effectivement reçues.                           |
| Attente              | `SuiviLotsEmi` expose les quantités de sorties suivies.                                                                                                                                 | La projection exclut une quantité garantie si la recette ne prouve pas ce rendement. Le fluide attendu garde l'unité mB.                                                          |
| Réception            | `ReceptionLots`, `ReceptionSortie` et `ReceptionManuelle` rapprochent les gains selon les règles natives. L'export expose les cumuls du lot, avec les bornes de conservation vérifiées. | Le stock physique provient toujours du relevé de stock. Le pont client actuel ne fournit pas de transaction de réception qui permette à Production de confirmer le gain à ce lot. |
| Annulation           | `SuiviLotsEmi` conserve la part reçue et sépare la quantité annulée de la quantité restante.                                                                                            | Aucune restitution ou écriture n'est déclenchée par Production.                                                                                                                   |
| Crédits historiques  | `fabrication_emi.json` contient des cumuls de progression.                                                                                                                              | Aucun lot, aucune entrée de machine et aucune acquisition courante n'en sont déduits.                                                                                             |

L'identifiant de projection reprend la clé du lot natif et le jeton de session. La relecture vérifie l'arbre, la recette, le projet, sa révision et la révision du suivi. Une hausse de l'inventaire peut être attribuée par le suivi historique à une fabrication sans prouver sa source. Même la baisse d'une sortie GregTech corrélée à un gain personnel ne prouve pas une transaction commune. Un convoyeur peut vider la sortie au même moment qu’un autre gain de cet objet. Le pont client ne possède pas d'identifiant de transaction de retrait autorisant cette attribution. Production exclut donc le lot ainsi attribué des attentes et des réceptions calculables. Le suivi et les crédits EMI continuent de fonctionner selon leurs règles existantes. Les lots n'acquièrent aucune persistance supplémentaire au changement de monde ou à la reconnexion. Le même cumul relu ne crée aucun crédit. Les attentes exploitables peuvent apparaître dans le plan, séparées des objets physiques. Une capture du coffre n'a pas de preuve commune avec le relevé des réceptions personnelles. Elle les laisse non exploitables pour ce calcul. Le chemin Production consulte les données sans appeler les fonctions d'action EMI ou le fournisseur IA.

## D — Calculs de référence

| Cas                                                      | Besoin brut                        | Porté                                | Coffre alloué             | À récupérer                             | À produire selon la preuve                                   |
| -------------------------------------------------------- | ---------------------------------- | ------------------------------------ | ------------------------- | --------------------------------------- | ------------------------------------------------------------ |
| Fixture unitaire cohérente                               | 8 objets                           | 3                                    | 5                         | 5                                       | 0                                                            |
| Les mêmes objets viennent d'être déplacés, relevé ancien | 8 objets                           | Jusqu'à 8 si confirmés sur le joueur | 0                         | Inconnu hors capture                    | Recalcul sur les preuves restantes                           |
| Cintreuse, huit plaques                                  | 6 cuivre et 2 étain pour l'alliage | Selon le relevé                      | Selon le relevé           | Selon l'allocation                      | 8 plaques si aucune plaque n'est déjà disponible             |
| Marteau, huit plaques                                    | 9 cuivre et 3 étain pour l'alliage | Selon le relevé                      | Selon le relevé           | Selon l'allocation                      | 8 plaques si aucune plaque n'est déjà disponible             |
| Fixture du calcul avec une preuve de réception injectée  | 8 objets suivis, 3 reçus           | Quantité de la capture physique      | Aucun coffre dans ce mode | Selon la quantité physique et l'attente | 5 encore attendus, 3 réceptionnés, sans stock supplémentaire |

Les références bronze viennent de la campagne shared.42. Les observations de chaque JAR exécuté sont consignées séparément ci-dessous. Une quantité à fabriquer calculée sur une preuve partielle reste conditionnelle aux ressources non couvertes.

## E — Matrice des tests

| Couverture demandée                    | Preuve et statut                                                                                                                                                             |
| -------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| STOCK-N01 à N04, N10 à N12, N16 et N17 | **AUTOMATED**. Poches et sac, 3 + 5, trame incohérente, déplacement, fraîcheur, sources inconnues, NBT et cases tronquées.                                                   |
| STOCK-N05, N08 et N09                  | **PARTIAL**. Les stockages restent distincts dans les règles et scénarios existants. Production refuse d'additionner des menus distincts ou un drawer sans révision commune. |
| STOCK-N06 et N07                       | **AUTOMATED**. Contrats de menu double et de stock Sophisticated à quantités étendues.                                                                                       |
| STOCK-N13, N14, N18 à N20              | **AUTOMATED**. Isolation de contexte, changement de session, rejet de preuve hors ordre et erreurs d'écriture injectées. Le verrou NTFS réel reste à tester.                 |
| STOCK-N15                              | **NOT_TESTED** en FTB Chunks réel. Le code exige un paquet complet de l'ouverture serveur et ignore les cases non accessibles.                                               |
| LOT-N01 à N04 et N23                   | **AUTOMATED / PARTIAL**. Origine manuelle ou envoyée, attentes, catalyseurs. Réservations physiques non attestées.                                                           |
| LOT-N05 à N13, N20, N22 et N24         | **AUTOMATED**. Cumuls bornés, réceptions partielles et complètes, deux lots, doublons, annulations, variantes et provenance manquante.                                       |
| LOT-N14 à N19 et N25                   | **AUTOMATED / PARTIAL**. Pauses, changement de recette et de portée, nouvelle connexion et relevé interrompu. Les reprises réelles sont qualifiées séparément.               |
| LOT-N21                                | **AUTOMATED / PARTIAL**. Unités mB et quantités attendues dans la projection. Aucun encaissement physique de fluide attesté par cette campagne.                              |

Les résultats de réception des fixtures qualifient le décodeur et les règles de rapprochement.
Ils ne qualifient pas une transaction de retrait GregTech. Shared.45 retire les quantités
ambiguës du calcul avant qu'elles puissent réduire un manque.

Les contrats `StockProductionTest` et `ProjectionLotsEmiTest` exécutent respectivement 7 et 33 cas Java. Les tests Python du décodeur et du service vérifient l'indépendance entre sources, la conservation des quantités, le refus d'une empreinte étrangère et le rejet d'une ancienne preuve. La suite complète sur macOS a réussi **664 tests**, sans échec ni test ignoré. La référence shared.42 était de 627 tests. La commande dédiée de scénarios EMI a réussi **26 cas**. Ruff, Prettier, le formatage Java, la compilation Forge avec JDK 17, les vérifications de syntaxe Node du client et du serveur et les deux relectures ciblées du client sous Rhino ont également réussi. Le script serveur et le script client ne diffèrent pas de shared.42.

## F — Essais réels

Cible de test macOS 27.2 arm64, Minecraft 1.20.1, Monifactory Expert 0.13.8, Forge 47.4.13, JDK 17.0.20.1, KubeJS Forge 2001.6.5-build.16, EMI 1.1.22 et GTCEu 7.5.3. L'instance et le projet sont des copies jetables. La campagne utilise un assistant de test sans appel à Codex ou Claude.

La première compilation native shared.43 (`34f3fccda98a6d0dea9c307f3c45a378564b45db`,
SHA-256 `0fcf3a417d14d0c430bd922582b02eb0259736381102c51cba8e8c4b23570081`) a chargé
Forge et KubeJS normalement. Le coffre simple a attesté 3 cuivre et 1 étain des poches
et le même complément dans le coffre. Le transfert natif a invalidé la preuve. La
réouverture a produit un nouveau relevé avec 6 cuivre portés et 0 cuivre dans le coffre.
Les cases 0 et 27 d'un double ont donné 5 et 3 fer dans le même menu à 90 cases, soit une
seule source de 8 fer. Le projet fictif en pause est resté identique pendant les essais
de stockage.

Le suivi shared.43 a ensuite reproduit une anomalie réelle sur un projet fictif actif.
Après la déclaration d'un lot manuel de 16 lingots de bronze dans EMI, l'ajout d'un seul
lingot par la commande de préparation du test, sans sortie de machine, donnait
`reçus=1, restants=15` dans Production. Le commit shared.44 distingue les gains
personnels non attribuables des sorties rapprochées par le mécanisme natif.
Il lie aussi chaque lot à la révision du projet et à la révision structurelle de son
arbre. Le changement ne modifie pas les crédits de suivi EMI existants. Shared.45 exclut également les réceptions issues d'une simple corrélation entre sorties et inventaire. Il refuse les défis et aperçus qui ne portent pas l'identifiant et l'empreinte de capture.

Le candidat intermédiaire shared.44 (`1fbdc7e4ae16f236ae975c5fd9fa530e9bf786d8`,
SHA-256 `5758554acec445727f25364c389fb46bb542f8c70e2d211d2c990720f05b359f`) a rejoué
le coffre simple, le déplacement puis la réouverture, et le coffre double. Un lot
intermédiaire déclaré par le bouton natif a donné huit lingots attendus et aucun
lingot physique. Son annulation a retiré les huit attendus. Une seconde déclaration
suivie de la même injection de test a donné un lingot physique, alloué une fois,
et aucune réception ni attente attribuée au lot de provenance ambiguë. Le
défi du nouveau calcul correspondait au relevé exact. Un sac fermé a fourni 6 cuivre et 2 étain au stock personnel. Avec 6 cuivre et
1 étain dans les poches, la capture donnait 12 cuivre et 3 étain. Un baril
Sophisticated vide a fourni un paquet complet et un calcul attesté. Le baril rempli et
son transfert restent à tester. La déconnexion a révoqué le relevé du coffre en mémoire.
Dans la même JVM, la reconnexion au même monde a produit une nouvelle session et
un nouveau relevé avec son défi validé. Le rendu final à la reconnexion n'a pas été
capturé par la sonde. Ces résultats sont **IN_GAME** sur shared.44.

Sur le candidat final shared.45 et le Python `5aa2a54`, Forge et KubeJS ont chargé la copie
normalement avec le seul JAR assistant indiqué dans la section A. Un appui natif sur **Y** depuis
le coffre a ouvert Production. Le Recipe Tree des huit plaques indiquait **6 cuivre et 2 étain**
et le calcul a alloué **3 cuivre et 1 étain** dans chaque source. Le transfert de test a
invalidé le relevé et la réouverture a produit une nouvelle preuve. Dans le même contexte
immobile, le défi v1 sans capture n'a reçu aucune réponse en 1,5 s, puis le contrôle v2 a
reçu exactement le défi attendu avec un nouveau nonce. Ces résultats sont **IN_GAME**.

Le lot intermédiaire a également été rejoué sur le contrat final v3. Déclaration native de
huit lingots attendus et aucun stock physique, annulation avec zéro attente restante, puis
nouveau lot et gain externe d'un lingot. Production a conservé un lingot physique, alloué
une seule fois, avec zéro réception et zéro attente calculable pour le lot de provenance
indéterminée. Le défi final a été vérifié. Niveau **IN_GAME** sur le JAR et le Python de la
section A. Les cas de retrait réel de sortie de machine et de fluide restent sans preuve de
transaction au niveau Production.

Windows 11, Claude, FTB Chunks et les deux clients multijoueurs restent **NOT_TESTED**.

## G — Mesures hors jeu

Mac arm64, Python 3.12.14. Même fixture de 2 024 recettes, 600 coffres et 24 étapes. Empreinte fixture `66cddfbf1a3828758196c0082ad2fff51720456e0203ba991f1a2283528fff22`. 21 mesures après chauffe. Les chiffres du service utilisent un acquittement simulé et un cache disque chaud.

| Opération                             | shared.42 `c5bc647` médiane / p95 | shared.45 médiane / p95 |
| ------------------------------------- | --------------------------------: | ----------------------: |
| Lecture et validation du catalogue    |                31,002 / 37,629 ms |      30,587 / 38,039 ms |
| Agrégation de 600 stockages           |                25,328 / 31,649 ms |      24,950 / 30,283 ms |
| Plan pur de 24 étapes                 |                  2,214 / 2,282 ms |        2,223 / 2,290 ms |
| Plan à partir des observations        |                  6,105 / 6,265 ms |        6,155 / 7,716 ms |
| Diagnostic du même plan               |                  0,145 / 0,149 ms |        0,149 / 0,150 ms |
| Lecture, plan, publication du service |                56,401 / 62,018 ms |      48,239 / 57,076 ms |
| Préparation du contexte IA local      |                  0,840 / 0,864 ms |        0,854 / 0,974 ms |
| Invalidation du catalogue             |                  0,193 / 0,221 ms |        0,152 / 0,162 ms |

Deux trames synthétiques à 12 lots, 51 mesures après cinq passages de chauffe. La capture personnelle comporte 32 entrées et la capture menu trois sources de 32 entrées. Lecture, SHA-256 et normalisation prennent **0,794 / 1,137 ms** pour **7 058 octets** côté personnel et **1,440 / 1,674 ms** pour **9 371 octets** côté menu. Pics d'allocation Python de 76 168 et 145 557 octets. Le pic du service complet était de 4,80 Mio pour les deux versions.

Les mesures ont été répétées après l'arrêt du jeu. Le plan pur est resté proche de 2,2 ms. Le p95 du plan à partir des observations passe de 6,27 à 7,72 ms sur ce banc, alors que les médianes sont proches. Le service complet passe de 56,40 à 48,24 ms en médiane dans ces conditions. Le pont réutilise le fichier de relevé et le défi, observe les paquets du seul menu actif et n'écrit aucun journal permanent de lots.

Le parcours réel coffre → Production a donné un résultat en 0,92 s sur un essai du lab. Cette mesure englobe l'interface, l'acquisition, le calcul et la réponse. Elle ne permet pas d'isoler le coût de la capture Forge. Aucun FPS ni MSPT n'a été mesuré.

## H — Livraison et prochaine qualification

### Préparer le candidat

Utiliser un checkout jetable du commit de code indiqué dans la section A. Tant que le candidat est local, on peut transmettre uniquement son historique Git avec un bundle. Dans le worktree de qualification et dans le checkout Windows de test, respectivement

```sh
git bundle create /tmp/monifactory-v2-native-stock-lots.bundle codex/v2-native-stock-lots
git bundle verify /tmp/monifactory-v2-native-stock-lots.bundle
```

```powershell
$Bundle = (Resolve-Path (Read-Host 'Bundle Git reçu pour shared.45')).Path
git clone --branch codex/v2-native-stock-lots $Bundle monifactory-v2-native-stock-lots-test
Set-Location .\monifactory-v2-native-stock-lots-test
$SourceCommit = '5aa2a5496a9f143823df3c322da1ef00d8a8a732'
git merge-base --is-ancestor $SourceCommit HEAD
if ($LASTEXITCODE -ne 0) { throw 'Commit shared.45 absent' }
git diff --exit-code $SourceCommit HEAD -- . ':(exclude,glob)docs/**'
if ($LASTEXITCODE -ne 0 -or (git status --porcelain)) { throw 'Code de qualification différent ou checkout modifié' }
```

### Windows et retour arrière

Préparer la copie CurseForge et les outils selon [la procédure Windows 11 / Claude](recette-v2-windows.md).
Utiliser le checkout de test obtenu ci-dessus et les commandes de cette section. Ne pas exécuter
les commandes de récupération de shared.42 du guide précédent.

```powershell
$env:MINECRAFT_DIR = (Resolve-Path (Read-Host 'Dossier de la copie Monifactory jetable')).Path
$env:JAVA_HOME = (Resolve-Path (Read-Host 'Dossier du JDK 17')).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
py -3.12 -m venv .venv
$Py = (Resolve-Path '.venv\Scripts\python.exe').Path
& $Py --version
java -version
node --version
claude --version
& $Py -m pip install -r requirements.txt -r requirements-dev.txt
& $Py choose_ai.py claude
& $Py codex_setup.py check
& $Py -m ruff format --check .
& $Py -m ruff check .
npm ci
npm run format:check
& .\mod\gradlew.bat -p mod checkJavaFormat
& $Py codex_setup.py build
& $Py -m unittest discover -s tests -v
& $Py -m monifactory_assistant.commands.scenarios tous
node --check jeu/assistant_ia.js
node --check serveur/assistant_ia_serveur.js
git diff --check
& $Py codex_setup.py install --dry-run
```

Vérifier que la simulation vise la copie et le JAR `1.0.0-shared.45` avant d'installer.
Une compilation locale peut donner une autre empreinte d'archive. Comparer le build au
seul JAR installé et vérifier le chargement de la version dans Forge. Le script reste à 12.

```powershell
$Jar = (Resolve-Path 'mod\build\libs\assistantia-1.0.0-shared.45.jar').Path
& $Py codex_setup.py install
$Installed = @(Get-ChildItem (Join-Path $env:MINECRAFT_DIR 'mods') -Filter 'assistantia-*.jar')
if ($Installed.Count -ne 1) { throw 'Il faut un seul JAR assistant dans la copie' }
if ((Get-FileHash $Jar).Hash -ne (Get-FileHash $Installed[0].FullName).Hash) { throw 'JAR installé différent du build' }
$Client = Join-Path $env:MINECRAFT_DIR 'kubejs\client_scripts\assistant_ia.js'
if ((Get-FileHash $Client).Hash -ne (Get-FileHash 'jeu\assistant_ia.js').Hash) { throw 'Script installé différent du candidat' }
Get-FileHash $Installed[0].FullName, $Client -Algorithm SHA256
& $Py assistant_mc.py
```

Pour vérifier l'incompatibilité, mélanger les versions uniquement dans la copie, puis restaurer
le couple exact. Pour le retour arrière, restaurer ensemble le checkout, le JAR et le script
précédents de la copie, assistant et Minecraft fermés. Refaire le contrôle d'identité au démarrage.

1. En jeu, choisir huit plaques de bronze et la branche cuivre-étain dans EMI. Préparer **3 cuivre et 1 étain** dans les poches, le même complément dans un coffre de test. Ouvrir le coffre, appuyer sur **Y** hors d'un champ de saisie puis calculer dans **Production**. Comparer les quatre quantités portées et les quatre à récupérer.
2. Fermer Production, déplacer un lingot via le menu natif puis rouvrir le coffre. Recalculer. Le nouveau total physique doit rester 6 cuivre et 2 étain. Essayer un coffre double en plaçant du stock de part et d'autre puis deux coffres simples voisins séparément. Noter la couverture de chaque clic.
3. Rejouer avec un coffre Sophisticated Storage, un drawer et un backpack fermé synchronisé. Le sac se vérifie depuis Production ouvert hors coffre. Relever le support ou le refus et la portée de chaque source.
4. Déclarer un lot fictif depuis les commandes EMI. Comparer Suivi et Production avant le premier gain, après une sortie partielle, après la réception complète ou l'annulation du reste. Vérifier qu'un déplacement vers le coffre suivi d'un retour ne crédite pas une deuxième réception. Faire ensuite l'essai distinct d'un envoi machine confirmé.
5. Rejouer les cas de verrouillage NTFS, changement d'arbre, déconnexion puis reconnexion décrits dans le guide. Maintenir un projet fictif en pause et relever sa révision, l'état des preuves et les quantités avant et après.

### Multijoueur et seuil de fusion

Reprendre [la procédure à deux joueurs](recette-v2-multijoueur.md) sur le même code shared.45. Ajouter le calcul depuis un coffre autorisé et le refus de preuve côté joueur sans accès, sur une protection FTB existante. Comparer les deux sessions, les positions et dimensions déjà visibles et l'absence de partage des mémoires. Le serveur et sa politique de scan restent dans leur état initial.

**Verdict PARTIAL.** Le candidat fournit une allocation native à partir d'un coffre ouvert
et les attentes des lots EMI dont la portée et l'état sont vérifiables. Les réservations
physiques et les réceptions GregTech restent indéterminées en l'absence de preuve de
transaction. Le 7 novembre 2026, Damien a autorisé la publication de ce périmètre sur `main` après présentation de ces limites. Le code publié est celui des essais Mac et des 664 tests. Le bundle Git vérifié permet aussi de rejouer la qualification du candidat initial. Les essais Windows/FTB et les parcours GregTech sans preuve restent à consigner. Leur absence conserve le verdict **PARTIAL**. Une validation sur la branche publique shared.42 reste une preuve historique.
