# Qualification de Monifactory Assistant V2

## Qualification du candidat stabilisé

Le [rapport d’intégration](integration-v2.md) suit les commits, les deux anomalies prioritaires,
les essais dans Minecraft et la procédure de reproduction Mac et Windows. Les tableaux suivants
décrivent la portée de l’essai de stabilisation. Les sections historiques gardent leurs références.

### Stocks et productions

Les cas de `tests/test_production_stock_matrix.py` emploient les contrats réels du planificateur,
des relevés datés et de leur rapprochement. Leurs entrées sont synthétiques, à l’exception des
contrats d’acquisition exécutés sur de vrais fichiers temporaires. Le stock issu de chaque
source, ses réservations et ses manques sont comparés par assertions. Les cas EMI précédents
restent dans la suite de scénarios et ne sont pas remplacés par cette matrice.

| Cas                            | Comparaison vérifiée                                                         | Niveau et limite                                                                                 |
| ------------------------------ | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| STOCK-01 · Inventaire          | Stock physique porté déduit une seule fois                                   | Contrat et inventaire du joueur de test dans Minecraft                                           |
| STOCK-02 · Sac                 | Le total synchronisé du joueur remplace l’ancien relevé et son sous-total    | Contrat et Java de backpack fermé. En jeu, compter le sac lorsqu’une réponse native l’atteste    |
| STOCK-03 · Plusieurs coffres   | Quantités distinctes allouées sur un même relevé fiable                      | Contrat. La V2 native ne somme pas ce relevé et un inventaire sans révision de transfert commune |
| STOCK-04 · Coffre double       | Fusion des moitiés attestées. Une chaîne de voisins reste ambiguë            | Contrat. Parcours Sophisticated Storage, Drawers et protections à rejouer en jeu                 |
| STOCK-05 · Déplacement         | La nouvelle localisation prime sans compter l’ancien coffre                  | Contrat de provenance                                                                            |
| STOCK-06 · Observation tardive | Un ancien paquet ne réactive pas la source périmée                           | Contrat de fraîcheur                                                                             |
| STOCK-07 · Stock incomplet     | Manques relatifs aux relevés. Total inconnu                                  | Contrat et indication de la vue V2                                                               |
| STOCK-08 · Réservation         | Quantité réservée déduite du physique utilisable                             | Contrat du registre de lots. Réservation native V2 indisponible                                  |
| STOCK-09 · Mise en machine     | Une entrée machine ne devient ni stock de coffre ni lot déclaré              | Contrat. Transfert EMI natif inchangé                                                            |
| STOCK-10 · Production attendue | Le produit reste en attente et n’alimente aucune allocation physique         | Contrat du registre de lots. Sortie native V2 indisponible                                       |
| STOCK-11 · Réception partielle | Le reçu est cumulatif, idempotent et borné au lot                            | Contrat et Java des réceptions. Reçu natif V2 indisponible                                       |
| STOCK-12 · Annulation          | Seule l’attente restante est libérée. L’objet physique reste compté une fois | Contrat et Java de suivi. Annulation en V2 indisponible                                          |
| STOCK-13 · Circuit             | Le réglage virtuel ne consomme aucun circuit physique                        | Contrat et recettes GregTech chargées dans Minecraft                                             |
| STOCK-14 · Fluide              | mB exacts. Un seau n’est pas assimilé à un volume disponible                 | Contrat et recettes chargées. Fluides de stock non attestés                                      |
| STOCK-15 · Dimensions          | Les mêmes coordonnées restent séparées selon la dimension                    | Contrat d’isolation. Passage réel entre dimensions à rejouer                                     |

Le moteur sépare les lots non commencés, réservés, envoyés et attendus, puis les réceptions
partielles, complètes et les annulations. Le rapport natif ne dispose aujourd’hui que du physique
attesté. `fabrication_emi.json` agrège des crédits d’avancement par objectif. Il ne prouve ni
l’identité d’un lot, ni sa réservation, ni sa réception. Le test ouvre ce fichier temporaire
avec l’adaptateur réel et vérifie que ses crédits ne sont promus dans aucun de ces états. Les
quantités de lots sont donc inconnues dans Production. Les onglets EMI existants gardent leur
suivi. Les tests de pause, reprise, changement de projet, révision, déconnexion et rejeu
vérifient que le calcul V2 ne modifie ni le suivi ni la sauvegarde de progression.

### Consentement R5 indépendant

`codex/r5-consent` reste à `5105320`. Le précédent essai Minecraft a exercé la cible du
dialogue, la confirmation, Annuler et Échap, la sauvegarde et le refus de l’injection IA.
La présente revue a réussi 55 tests ciblés, puis sept vrais arrêts `SIGKILL` de processus
sur un plan fictif. Les points d’arrêt suivent la réservation durable, la sauvegarde,
l’écriture du temporaire à mi-chemin, sa synchronisation, le remplacement du primaire,
le moment précédant le reçu et le reçu publié avant nettoyage. Chaque reprise rejoue la
même demande trois fois et vérifie l’absence de second effacement, la sauvegarde exacte et
le refus d’une preuve associée à une autre connexion.

Le nouvel essai natif utilise le même JAR `.40` indépendant et une sonde réservée à la copie
Expert. Après 32,3 s, Confirmer refuse la proposition expirée et préserve le plan et sa
sauvegarde octet pour octet. Une déconnexion juste après le clic a vidé l’identité, retiré
la proposition et publié un reçu négatif. Le plan est resté intact. La sonde n’a pas mené à
terme son retour au menu dans ce dernier cas. Après redémarrage, deux activations du même
bouton Confirmer pendant le même tick ont gardé un seul identifiant de demande. Un seul
effacement a été confirmé et la sauvegarde correspondait exactement au plan fictif initial.

Une annulation parvenue après le dernier contrôle de la file et juste avant l’écriture peut
arriver trop tard. Le scénario reproductible commence avec une confirmation positive et
valide. Le plan fictif est alors effacé une seule fois et sa sauvegarde exacte est disponible.
Cette limite de retrait du consentement et la qualification native restante gardent R5 en
statut partiel. Le garde-fou publié sur `main` continue de refuser les suppressions proposées
par l’IA.

### Mesures avant et après

Le même scénario hors jeu, `python -m tests.benchmarks.production_v2`, a utilisé 2 024 recettes,
600 coffres et une chaîne de 24 étapes, avec 21 répétitions par série et ordre avant, après,
après, avant. Les temps médians du calcul passent de 6,46–10,97 ms à 6,71–11,30 ms et le
pic d’allocations reste à 0,756 Mio. Le catalogue est mesuré entre 36,70–72,15 ms avant et
33,37–47,86 ms après. L’agrégation va de 27,31–51,23 ms avant à 39,02–94,28 ms après.
Les essais partagent les ressources de la machine avec le laboratoire. La dispersion et
les moteurs de calcul identiques empêchent d’attribuer ces écarts au candidat.

Le nouveau contrôle hors moteur écrit deux petits documents au plus par attestation
réussie et attend au maximum 450 ms la réponse du client. Le client ne recalcule pas
l’arbre et ne réécrit pas le catalogue pour y répondre. Aucun contrôle n’est émis par
l’assistant au repos. Les aperçus V2 sont limités à un contrôle de flux toutes les 350 ms,
avec un contrôle avant affichage. Une réponse ou un aperçu en attente de preuve peut
donc être retardé. Les mesures du moteur excluent ce pont, le rendu et les CLI distants.

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
