# Qualification de Monifactory Assistant V2

## Transition V1.5

| Prérequis                   | Résultat vérifié                                                                                                                                                                                                          |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Référence `origin/main`     | `5d280c3a949f4a6521fb1a4eed0303550dd25ec9` après récupération Git.                                                                                                                                                        |
| Candidat V1.5               | `1a56fe6f820332847ca80e4a9d5694a46e25b495`, ancêtre exact de `main`. Aucun commit V1.5 candidat manquant.                                                                                                                 |
| Différence avec le candidat | Rapport d’intégration, garde-fou R5 Python, instructions des deux moteurs et tests de non-régression.                                                                                                                     |
| V1.5 Python et Git          | Reprise sur copie figée de la référence, **479 tests et 26 scénarios EMI réussis** sur macOS. Le premier essai sur la copie de travail a rencontré une course de fixture de mise à jour pendant la création du paquet V2. |
| V1.5 Java et Forge          | Compilation JDK 17, contrats Java, Node et Rhino documentés dans la [qualification V1.5](qualification-v1.5.md).                                                                                                          |
| Windows 11 et lot 5         | Essais utilisateurs déclarés satisfaisants pour la V1.5. Détail des tests natifs et empreintes des JAR utilisés non établis. Migration personnelle non activée.                                                           |
| R5                          | Garde-fou publié. `codex/r5-consent` à `5105320384d5e9e7f56eb58e2662831f07f1e329` contient 5 commits locaux et 20 fichiers différents de `main`. Confirmation native non qualifiée en jeu.                                |
| P4                          | Reprise partielle. Une qualification distincte reste nécessaire pour les changements d’installation et les coupures brutales.                                                                                             |
| Catalogue                   | Le texte V1.5 et ses parties ne portent pas de preuve commune de génération avec les capacités. Leur exploitation exacte reste indisponible.                                                                              |
| Lot 2                       | **DEFERRED**. Relevé, permissions et scans serveur hors du chantier.                                                                                                                                                      |
| Démarrage V2                | **GO LIMITÉ** pour les contrats et moteurs isolés. Mutations du dialogue `BLOCKED_BY_R5`.                                                                                                                                 |

Le préflight ne lit pas les données personnelles du joueur. Les contrôles Forge et Windows
mentionnés dans les documents de référence conservent leur niveau de preuve d’origine.

## Chantiers V2

| Chantier             | Statut sur le périmètre V2 et preuve                                                                                                                                                                                                                                      |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| V2.1 · Connaissance  | **PARTIAL**. Vues isolées avec preuves, dates, historique et couverture. L’inventaire consolidé remplace le relevé KubeJS. Les coffres ambigus et les inventaires de type inconnu restent hors des allocations. L’intégration des acquisitions natives reste à qualifier. |
| V2.2 · Usines        | **PARTIAL**. Les groupes déclarés et les liens de recette sont testés. Un membre non revu reste dans le groupe. Une omission d’un relevé autorisé laisse la présence physique inconnue. Les données actuelles ne démontrent ni câblage ni réseau.                         |
| V2.3 · Diagnostics   | **PARTIAL**. Causes, preuves, contradictions et vérifications sont calculées sur fixtures. L’état GregTech actuel, la compatibilité et les stocks sont séparés. Une machine fermée, le circuit ou la tension peuvent rester inconnus.                                     |
| V2.4 · Planification | **PARTIAL**. Contrat exact et commande hors jeu validés. Lots entiers, variantes explicites, fluides en mB, catalyseurs, circuits, coproduits, réceptions, cycles et limites. La preuve structurée du catalogue du pack réel reste à produire et à qualifier.             |
| V2.5 · Mémoire       | **PARTIAL**. Lecture des sauvegardes et repli `.bak` sur copies. La pause et les étapes décochées sont conservées. Notes revues et corrections sont interrogeables sans écriture. La persistance des nouvelles notes reste à qualifier.                                   |
| V2.6 · Conversation  | **BLOCKED_BY_R5** pour les mutations. Les résultats calculés sont disponibles sous forme de contrats locaux. Le raccordement en lecture au dialogue et à l’interface dépend de la qualification de ses données et parcours natifs.                                        |
| V2.7 · Multijoueur   | **PARTIAL**. Positions datées et dimensions préservées dans les fixtures. Un serveur commun ne fusionne ni projets ni sessions des installations. Essais simultanés sur les deux postes à réaliser.                                                                       |
| V2.8 · Qualification | **PARTIAL**. Contrats et scénarios automatisés sur macOS. Les parcours Windows, NTFS et Minecraft réel restent à vérifier.                                                                                                                                                |

## Nature des preuves

- **Simulation.** Recettes et observations générées. Le scénario de calcul et de diagnostic teste
  un inventaire consolidé, deux lots d’alliage, un circuit déclaré et un état machine en attente.
  La machine peut signaler une attente sans que la cause du blocage soit établie.
- **Intégration locale.** La commande hors jeu lit un catalogue et une demande versionnés depuis
  des fichiers isolés. Les tests rejouent une publication remplacée en lecture, une génération
  incomplète, un changement de monde et la récupération d’une copie de sauvegarde. Les fixtures
  de mise à jour utilisent des dépôts temporaires. Les fournisseurs IA restent simulés.
- **Jeu réel.** R5 natif, provenance des paquets de backpack, reconnexions, filons, transferts EMI,
  présence multijoueur et fichiers verrouillés ont une campagne restante. Les preuves de Forge
  et Rhino établissent la compilation et les contrats locaux. Le lot 5 n’est pas activé ici.

La revue indépendante a reproduit la double allocation entre relevés successifs, la classification
erronée d’un inventaire de machine, le jumelage ambigu de coffres et la confusion entre les horloges
du serveur et du client. Les tests de régression couvrent ces cas. Sans révision commune prouvée,
la vue de calcul privilégie l’inventaire personnel, puis le dernier relevé serveur autorisé s’il est
la seule source de stock fraîche. Les observations plus anciennes restent consultables.

## Mesures locales

`python -m tests.benchmarks.production_v2` mesure séparément la validation du catalogue, la
construction de la connaissance et la planification. Sur macOS ARM64, 21 répétitions utilisent
les mêmes 2 024 recettes, 600 coffres et une chaîne de 24 étapes. Les pics concernent les
allocations Python par opération mesurées par `tracemalloc`.

| Opération                         | Temps médian |      P95 | CPU médian | Pic Python |
| --------------------------------- | -----------: | -------: | ---------: | ---------: |
| Lire et valider le catalogue      |     30,76 ms | 33,82 ms |   30,52 ms |   4,79 Mio |
| Construire la vue du monde        |     26,54 ms | 29,64 ms |   26,19 ms |   1,50 Mio |
| Calculer le plan depuis cette vue |      6,13 ms |  9,47 ms |    6,10 ms |   0,76 Mio |

La première version du lecteur réservait 64,01 Mio sur cette fixture. La lecture bornée par la
taille du fichier observé ramène le pic à 4,79 Mio tout en refusant un fichier modifié pendant la
lecture. Pour le parcours historique de Discussion, les sept empreintes du contexte V1.5 restent
identiques. La médiane des sept temps médians passe de 1,289 à 1,254 ms dans cet essai. La
différence de temps est insuffisante pour conclure à un gain. Ces mesures concernent des appels
hors jeu et ne renseignent ni les FPS ni le MSPT.

## Contrôles de livraison

Le code `8590e1bc1c471421301b49ea999b4c16b20b8e9a` passe **574 tests**, dont **95 tests dédiés V2**,
et **26 scénarios EMI** sur macOS avec Python 3.12. Ruff, Prettier, `pip check` et
`git diff --check` passent aussi. L’import des moteurs hors jeu n’ouvre
pas la configuration de l’instance. Les essais du planificateur comprennent une génération
déterministe de 60 chaînes et des cas de sortie aléatoire, fluide et contenant distincts, catalyseur,
réception, variante et cycle.

Le Java et le KubeJS sont identiques à la référence. La compilation de contrôle de `main`
sous JDK 17, le format Java, la syntaxe Node et les contrats sur le Rhino du pack passent.
Le fichier `assistantia-1.0.0-shared.40.jar` de ce contrôle porte l’empreinte SHA-256
`dead3bca58f6e8c66d2fbf5fbebeade4e0397eb96ed9d1abc0d2d7f43df71e3a`.

## Données et livraison

Le code V2 reste sur `codex/v2-limited`, issu de `5d280c3`. L’exemple et les benchmarks utilisent
des fichiers synthétiques et temporaires. Les formats existants, les scripts client et serveur,
les sessions des deux moteurs, les lanceurs et les règles d’installation sont identiques à `main`.
Le retrait de la branche de travail suffit pour revenir à la référence, sans restauration de données.

La décision **GO LIMITÉ** s’applique aux composants hors jeu. Le branchement au catalogue natif,
la qualification en jeu sur les deux postes, la confirmation R5 et la reprise P4 restent des étapes
distinctes avant publication de comportements intégrés. Le lot 2 reste reporté.
