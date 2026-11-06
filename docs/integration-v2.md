# Intégration V2 dans Monifactory Expert

## Préflight

`origin/main` a été relu à `5d280c3a949f4a6521fb1a4eed0303550dd25ec9`. Le candidat
`codex/v2-limited` était propre à `384ed24a8b6b4aa6d67495ec46ddff4921047e0f`. Les huit
commits du candidat descendent de la référence publiée. `codex/v2-integration` repart de
ce candidat. `codex/r5-consent` reste indépendant à `5105320384d5e9e7f56eb58e2662831f07f1e329`.
La comparaison entre les deux candidats donne huit commits propres à V2 et cinq à R5.

| Composant à l’entrée          | Implémenté et testé                                                | Raccordé au jeu au préflight      | Validation restante                                        |
| ----------------------------- | ------------------------------------------------------------------ | --------------------------------- | ---------------------------------------------------------- |
| `Catalog`, `CatalogueReader`  | Contrats v1 immuables, générations, empreintes, repli sur fixtures | Export historique indépendant     | Normalisation et preuve sur des recettes natives chargées  |
| `plan`, `analyse_observed`    | Quantités, choix, stocks séparés et erreurs sur fixtures           | Commande hors jeu                 | Choix EMI réels, comparaison des lots et limites           |
| `build_knowledge`, `diagnose` | Fusion bornée, preuves, états, hypothèses, historique              | Formats réels reproduits en tests | Captures de connexion réelle et affichage                  |
| `read_projects`               | Lecture de copies et sauvegardes, provenance                       | Service local indépendant         | Présentation dans le menu natif                            |
| Discussion                    | Garde-fou R5 publié, transports simulés testés                     | Interface V1.5                    | Projection bornée des résultats V2 pour les deux moteurs   |
| R5 natif                      | Parcours testé sur fixtures et compilé séparément                  | Candidat local indépendant        | Essais natifs et contrôle de sauvegarde sur projet jetable |

## Sources du catalogue

Le manifeste local identifie Monifactory **0.13.8**, Minecraft **1.20.1** et Forge **47.4.13**.
Les JAR locaux consultés sont EMI **1.1.22** et GTCEu **7.5.3**. Le marqueur historique v2
déclare 134 522 recettes réparties en neuf fichiers. `jeu/assistant_ia.js` écrit des lignes
de texte et publie le marqueur à la fin, tandis que `Stats.java` publie les postes de travail
séparément. `game/recipes.py` reste consommateur de ces fichiers pour la V1.5.

| Information              | Source native examinée                                                 | Traitement V2                                                                                        |
| ------------------------ | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| Identifiant et catégorie | `EmiRecipe.getId`, `getCategory`                                       | Identité exacte dans la génération chargée                                                           |
| Objets et quantités      | `EmiIngredient`, `EmiStack`, `GTRecipe.inputs/outputs`                 | Identifiant et entier. Un prédicat spécial non représentable est signalé                             |
| Fluides                  | `FluidIngredient.getStacks`, `FluidStack`                              | mB. Aucune conversion implicite d’un seau en fluide                                                  |
| Catalyseurs et outils    | `EmiRecipe.getCatalysts`, contenu GT de consommation nulle, restes EMI | Catalyseur non consommé si attesté. Usure ou reste dynamique hors contrat exact                      |
| Circuit                  | `IntCircuitIngredient.configuration`                                   | Réglage virtuel de machine séparé du stock physique                                                  |
| Capacités machine        | Postes EMI et définitions GregTech                                     | Compatibilité observée par l’adaptateur. Disponibilité réelle traitée séparément                     |
| EU/t et durée            | `GTRecipe.getInputEUt`, durée native                                   | EU/t nominal et ticks. Aucune mesure d’énergie réellement fournie                                    |
| Alternatives             | Options EMI ou natives et résolutions de `MaterialTree`                | Choix conservés. Aucun remplissage depuis les trois alternatives du texte historique                 |
| Sorties et probabilités  | Sorties EMI et `Content.chance/maxChance` GT                           | Sous-produits et probabilités explicites. Un rendement dépendant du niveau ou non exact reste limité |

La V2 prend l’arbre réellement sélectionné dans EMI. Un export global à chaque demande
aurait inclus plus de cent mille recettes et des catégories sans plan de production. La portée
du catalogue est donc explicite. Elle comprend l’arbre et ses résolutions sélectionnées, et
laisse inconnues les branches extérieures. Les recettes dynamiques sont lues dans le gestionnaire
chargé en jeu après application des mods, datapacks et scripts. Les changements de connexion,
de dimension ou de génération EMI invalident la publication active.

## Contrats et compatibilité

`CatalogueEmi` lit la sélection sur le thread client avec des limites de nœuds, d’options
et de temps. La conversion des recettes avance par petits lots entre les ticks. L’écriture
canonique, l’empreinte SHA-256 et la préparation des fichiers sont effectuées hors du thread
client. `catalogue_production.json` contient l’enveloppe v1 du catalogue. Le marqueur
`catalogue_production_etat.json` est publié en dernier, avec la cible, la génération, les choix
et la portée. Un échec laisse la génération incomplète indisponible. Python vérifie le marqueur,
le fichier canonique et la portée de la connexion. Le rapport en attente est révoqué si le
marqueur est remplacé avant ou après le calcul.

La fenêtre **Production** lit les résultats de la file v2 existante. **Plan** utilise l’arbre EMI
sélectionné et la quantité de l’arbre par défaut. **Diagnostic** présente preuves, hypothèses,
contradictions et vérifications. **Projet** affiche l’objectif et ses étapes sauvegardées. Le
clic capture l’inventaire natif consolidé lorsque le sac est synchronisé. Les variantes NBT et
les fluides d’inventaire restent inconnus dans cette capture. Les anciens stocks restent datés.
Les réservations et sorties EMI ne sont pas créditées depuis `fabrication_emi.json`, car ce
fichier contient des crédits cumulés et ne prouve pas une réservation datée ou sa réception.

Les identifiants et dates de la preuve native sont vérifiés avant de publier le résultat.
Le calcul s’effectue sur un worker borné. La fenêtre conserve son onglet parent et rejette
les réponses d’un autre projet ou d’une autre connexion. Le texte fourni à Codex ou Claude
reste borné et dépourvu d’identifiants de session, de chemins personnels et de raisons de
machine libres. L’explication utilise le tour de Discussion existant. Ses actions JSON
sont ignorées, et un résultat périmé n’est ni affiché ni lu à voix haute.

Le candidat Java porte la version `1.0.0-shared.41` et le script KubeJS client conserve sa
version 12. Les formats historiques d’EMI et des plans, les règles du transfert natif et le
script serveur facultatif sont conservés. L’interface V2 n’exécute aucun calcul sans clic.

## Résultats dans Monifactory Expert sur Mac

Le monde Expert était jetable et le profil fictif. L’essai a utilisé les mods du pack installé
et les vrais boutons du client via une sonde réservée au laboratoire. L’initialisation des
recettes bloquait entre KubeJS et Rhino dans cette copie. Un adaptateur temporaire a exécuté
en série les deux appels concurrents du chargeur. Le candidat et les scripts de recettes
gardent leur code d’origine sur ce point. La qualification couvre ce démarrage isolé.

| Cas                                   | Source et comparaison                                               | Résultat                                                                                                                                                                                     |
| ------------------------------------- | ------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Huit plaques de bronze à la cintreuse | Coût du Recipe Tree EMI confronté à Production                      | 6 cuivre et 2 étain. Deux lots d’alliage, huit à la cintreuse. Le circuit virtuel 1 est lu dans GregTech.                                                                                    |
| Huit plaques au marteau de forge      | Autre branche du Recipe Tree                                        | 9 cuivre et 3 étain. Trois lots d’alliage et quatre au marteau. La branche choisie change bien le résultat.                                                                                  |
| Découpe avec fluide                   | Recette native du Cutter                                            | 57 mB d’eau, un bloc de bronze parmi huit variantes, neuf plaques en sortie. Le calcul signale le choix requis lorsque la variante n’est pas résolue. Le choix explicite est rejoué en test. |
| Solidification                        | Recipe Tree EMI et contenu GregTech                                 | Huit plaques avec 1 152 mB de bronze et un moule réutilisable. Le moule apparaît séparément des consommables.                                                                                |
| Filage du cuivre                      | Recipe Tree EMI et circuit GregTech                                 | 4 lingots pour 4 fils doubles, circuit virtuel 2. Aucun circuit physique à collecter.                                                                                                        |
| Sous-produit du macérateur            | EMI et probabilité GregTech                                         | 1 poudre de roche noire garantie. La poudre d’or reste un sous-produit à 25 %.                                                                                                               |
| Stock physique                        | Capture native après ajout des ingrédients au joueur fictif         | Les 6 lingots de cuivre et 2 d’étain sont affectés une seule fois. Le plan n’affiche aucun manque.                                                                                           |
| Diagnostic et projet                  | Boutons natifs, source machine absente, projet fictif déjà en pause | L’état machine et l’alimentation restent inconnus. Le diagnostic propose une vérification en jeu. La lecture conserve la sauvegarde byte pour byte.                                          |

L’essai de reconnexion confirme qu’une nouvelle session de jeu n’hérite pas du catalogue
précédent. Sur la recette d’établi du cuivre brut, l’arbre EMI avait choisi une compression
et une décompression qui se renvoyaient l’une à l’autre. V2 a signalé ce cycle sans stock
initial. Un bloc de cuivre brut ajouté au joueur fictif suffit ensuite pour un seul craft
de neuf cuivres bruts. La lecture a conservé la sauvegarde du projet octet pour octet.

Les sept recettes normalisées sont rejouées par `tests/test_expert_production_recipes.py`.
Les différences du catalogue historique, dont le moule et le circuit, ont été vérifiées
contre les données natives. La capacité installée d’une machine, les stocks répartis entre
plusieurs coffres et la réception d’un lot restent à qualifier en session réelle. Les tests
simulent la fraîcheur des relevés, la séparation des réservations et les preuves manquantes.

Un changement d’arbre en jeu a révélé que deux événements EMI rapprochés pouvaient
supplanter l’écriture du marqueur de révocation. Le correctif `7e3c05f` conserve cette
écriture. Le test de régression bloque volontairement le worker jusqu’après les deux
événements et vérifie le marqueur sur disque. Dans Minecraft avec le JAR corrigé, le
changement de branche retire l’ancien rapport. Le calcul au marteau réutilise ensuite
l’inventaire courant et demande seulement 3 cuivre et 1 étain. Le marqueur devient
également indisponible quand Minecraft émet l’événement de déconnexion.

Un deuxième essai interrompt l’accès au dossier de publication. Le client réessaie
la révocation au bout d’une seconde tant qu’elle correspond à la génération active.
Le contrôle en jeu masque immédiatement l’ancien rapport en mémoire. Le processus
Python peut encore lire l’ancien marqueur tant que son remplacement est impossible.
Le verrouillage natif NTFS reste à vérifier.

## Consentement natif R5

La branche `codex/r5-consent` a été compilée et chargée séparément. Elle conserve le JAR
`1.0.0-shared.40`, d’empreinte SHA-256
`c7713c20615c13f1feb4bcceeffdc8f6b1e6d0dfaf0c9667ddd921a1edccb025`, et le script client 12.
Le test sur Mac a constaté l’accusé de réception des demandes natives, le refus d’une
question informative et d’une action injectée dans une réponse de l’IA simulée, l’affichage
de la cible et des conséquences par **Examiner**, puis l’annulation avec **Annuler** et
**Échap**. **Confirmer** a effacé le plan fictif et la sauvegarde précédente a conservé
le plan et sa pause. Le contrôle de la demande et de la réponse a eu lieu à travers le pont
réel avec le client Minecraft.

Les 52 tests séparés passent. Ils couvrent aussi le double clic, les changements de projet
et de connexion, l’expiration et les échecs d’écriture ou de sauvegarde. Ces derniers cas
restent à rejouer en jeu ou avec une coupure d’écriture contrôlée selon le cas. La qualification
R5 est partielle et la branche reste indépendante.

## Mesures et portée de qualification

Sur le Mac du laboratoire, la durée du clic au résultat visible par le pont était de
0,77 à 1,08 s, médiane 0,925 s sur sept consultations. Elle inclut les ticks, la file et
la lecture du résultat. Le petit catalogue ci-dessous est une fixture de 13 188 octets
issue des recettes réellement chargées. Les opérations Python sont mesurées séparément
sur 41 répétitions après cinq échauffements.

| Mesure Python                             |  Médiane |      P95 |
| ----------------------------------------- | -------: | -------: |
| Lecture du catalogue de sept recettes     | 0,410 ms | 0,864 ms |
| Lecture avec le lecteur conservé          | 0,163 ms | 0,172 ms |
| Calcul de huit plaques avec stock         | 0,040 ms | 0,044 ms |
| Diagnostic seul                           | 0,014 ms | 0,016 ms |
| Calcul, worker et publication             | 2,789 ms | 5,820 ms |
| Préparation du contexte IA avec sa preuve | 0,082 ms | 0,197 ms |
| Lecture de 50 projets et 1 000 étapes     | 2,713 ms | 5,159 ms |
| Recherche dans 1 000 notes                | 1,948 ms | 3,128 ms |

Le scénario synthétique de 2 024 recettes, 600 coffres et 24 étapes a été rejoué avec
`python -m tests.benchmarks.production_v2`. Les deux séries dédiées donnent des médianes
de 34 à 43 ms pour le catalogue, 30 à 31 ms pour les observations et 6,7 à 6,9 ms pour
le calcul. Le premier passage concurrent donnait 9,8 ms pour le calcul. Le pic
d’allocations Python est respectivement de 4,79, 1,50 et 0,76 Mio. La référence précédente
du calcul synthétique était 6,1 ms. La variation de charge entre passages empêche
d’attribuer l’écart au moteur.
Les écritures natives sont déclenchées à la demande. Une nouvelle capture écrit le marqueur
de chargement, le catalogue et le marqueur final. Une même sélection valide réutilise son
export. Une révocation écrit uniquement le marqueur. Les mesures ne couvrent ni les images
par seconde, ni les temps serveur, ni la latence de réponse du fournisseur IA.

| Environnement                          | Qualification                                                                                                                  |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| macOS, client seul, Monifactory Expert | Plan, diagnostic et projet vus dans Minecraft avec l’assistant Python local.                                                   |
| Codex et Claude                        | Parcours commun, tour unique et protection contre les actions vérifiés sur transports simulés. Les CLI réels restent à tester. |
| Windows 11 et multijoueur              | Procédure ci-dessous. Exécution réelle, permissions FTB et verrouillage NTFS restent à qualifier.                              |

La suite complète du candidat a réussi 604 tests. Les 26 scénarios EMI de base et complexes
passent également. Le build Forge avec JDK 17, le formatage Java, les deux contrôles avec
Rhino et KubeJS, Ruff, `pip check`, Prettier et `git diff --check` passent. Les tests de
transport et de mise à jour utilisent des connexions locales et des dossiers jetables.

## État Git et installation du candidat de test

| Lot                                                   | Commits de `codex/v2-integration` |
| ----------------------------------------------------- | --------------------------------- |
| Catalogue natif et révocations                        | `5fb6e3b`, `7e3c05f`, `fc8f25d`   |
| Plan, diagnostic et service à portée vérifiée         | `0ddd98a`                         |
| Fenêtre native                                        | `ca809fb`                         |
| Dialogue IA en lecture seule                          | `6550413`                         |
| Qualification des branches Expert et libellés machine | `842e55c`                         |

Le checkout contient le candidat. `origin/main` reste à
`5d280c3a949f4a6521fb1a4eed0303550dd25ec9`. La publication attend les validations
restantes. Le candidat peut être rejoué dans une autre copie en compilant avec JDK 17,
en relançant l’assistant Python sur ce checkout puis en redémarrant Minecraft avec son
JAR et le script client 12. Le script serveur facultatif et la CI sont inchangés.

Le JAR `assistantia-1.0.0-shared.41.jar` chargé pour la révocation finale a pour SHA-256
`fa6d2855bc4f542d9686b0d8db3dc4e1146027356d9e0cf0d96f05cad9229c38`.

## Rejouer sur macOS et Windows

Pour chacun des deux postes, utiliser un dossier de test avec les mods du pack, un monde
Expert jetable et un checkout du candidat. Garder les réglages, sessions, mondes et sauvegardes
de l’installation habituelle à part. Installer le JAR candidat uniquement dans cette copie
fermée, puis contrôler son nom et son empreinte au démarrage. Utiliser un nouveau projet de
test en pause. Distinguer dans le relevé **simulation**, **intégration locale** et **Minecraft**.

| Essai                 | Opérations et résultat attendu                                                                                                                                                                                                                      |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Version et démarrage  | Relever SHA Git, SHA-256 du JAR chargé, version du script 12 et mode Expert. Ouvrir **Y**, puis **Production** depuis Objectif ou Base.                                                                                                             |
| Arbre réel            | Ouvrir EMI, choisir la recette de plaque à la cintreuse, puis le procédé d’alliage cuivre/étain pour le lingot. Demander huit plaques. Relever les quantités et le nombre de lots dans Recipe Tree et Production. Refaire avec le marteau de forge. |
| Fluides et circuits   | Ouvrir une recette de coupe au fluide et une recette GregTech à circuit. Comparer les mB, le circuit virtuel et la liste des objets physiques.                                                                                                      |
| Inventaire et machine | Déplacer des ingrédients entre un coffre et le joueur, puis rouvrir Production. Vérifier la date et les limites des stocks. Consulter une machine connue mais fermée, puis un diagnostic avec un relevé courant autorisé s’il existe.               |
| Réception et projet   | Déclarer un lot par le parcours EMI actuel, puis vérifier que le résultat V2 signale l’absence de preuve de réservation. Relire le projet en pause et comparer sa sauvegarde avant/après.                                                           |
| Interface et contexte | Tester une fenêtre réduite, le retour à l’onglet parent, Échap pendant le calcul, un changement d’arbre et la reconnexion. Un ancien rapport doit disparaître.                                                                                      |
| Dialogue              | Avec le CLI du poste, demander « Explique ce plan de production » après le calcul. Vérifier une seule réponse et les mêmes quantités. Une question générale conserve le parcours habituel.                                                          |
| P4 et R5              | Rejouer les coupures d’écriture sur des fichiers jetables et le parcours natif R5 avec le candidat indépendant. Sur Windows, vérifier aussi un fichier verrouillé NTFS.                                                                             |

Pour chaque résultat, noter plateforme, mode du pack, versions, génération du catalogue,
résultat attendu et obtenu, différences et court extrait de journal. Omettre les identifiants
de session, les identifiants du monde et les données personnelles du joueur.

### Valider le dialogue avec le fournisseur local

1. Dans le checkout de test, sélectionner Codex sur Mac avec `Choisir IA.command` et Claude
   sur Windows avec `Choisir IA.bat`. Démarrer l’assistant du même checkout. Utiliser une
   conversation de test et un projet fictif en pause.
2. Calculer les huit plaques à la cintreuse depuis **Production**. Ouvrir **Discussion** et
   demander « Explique ce plan de production et comment vérifier la machine ». Le dialogue doit
   reprendre la quantité et la branche, distinguer les inconnues et garder la pause.
3. Enchaîner avec une question générale, puis changer d’arbre avant une nouvelle explication.
   Vérifier le retour au comportement habituel et l’absence de réutilisation de l’ancien rapport.
   Relever le nombre de tours et les horodatages dans le journal local du test.
4. Sur les deux postes, quitter le monde, arrêter puis relancer l’assistant et rouvrir le monde.
   La nouvelle connexion exige un nouveau calcul. Sur le serveur de test, vérifier séparément
   la visibilité des positions et les protections de coffre FTB pour deux profils fictifs.
