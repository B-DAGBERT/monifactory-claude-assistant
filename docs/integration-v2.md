# Intégration V2 dans Monifactory Expert

## Stabilisation du candidat

| Référence vérifiée       | Commit de départ                           | État                                                      |
| ------------------------ | ------------------------------------------ | --------------------------------------------------------- |
| `origin/main`            | `5d280c3a949f4a6521fb1a4eed0303550dd25ec9` | Référence commune au Mac et au PC                         |
| `codex/v2-integration`   | `2c734d817667401d908153c8b3e955e96ccb2516` | 16 commits propres, aucun retard sur `main`               |
| `codex/r5-consent`       | `5105320384d5e9e7f56eb58e2662831f07f1e329` | 5 commits propres, indépendant de V2                      |
| `codex/v2-stabilisation` | Branche issue de `2c734d8`                 | Candidat isolé, Forge `1.0.0-shared.42`, KubeJS client 12 |

Les deux branches candidates de départ étaient locales et leurs copies de travail propres. Le
checkout principal contient un dossier `.serena/` non suivi. Le lot 2,
le script serveur et les workflows ne font pas partie de ce candidat.

| Domaine                            | Preuve à l’entrée                                              | Objectif de stabilisation                              |
| ---------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------ |
| EMI, Production et projet en pause | Minecraft Expert Mac, avec agent du laboratoire                | Démarrage conforme, nouveau JAR et échanges réels      |
| Résultat périmé                    | Fichiers et application locale, contournement par réessai      | Refuser l’ancien marqueur même lorsqu’il reste lisible |
| Stocks et fabrications en cours    | Inventaire physique en jeu, autres sources sur fixtures        | Cas distribués, preuve de fraîcheur et de réception    |
| R5                                 | Confirmation et sauvegarde d’un faux projet dans Minecraft Mac | Coupures, rejeu, fenêtres de consentement              |
| IA                                 | Transports simulés Codex et Claude                             | CLI autorisés sur profils d’essai                      |
| Windows 11 et multijoueur          | Parcours préparés                                              | Essais réels et verrou NTFS                            |

### BUG-01 · Chargement KubeJS

Dans la copie initiale, Quark avait régénéré `config/quark-common.toml` avec ses valeurs par
défaut. **Color Runes** y valait `true`, alors que la configuration Expert installée le met à
`false`. Au rechargement des recettes, le thread de chargement attendait le pool de
`RecipesEventJS.runInParallel`. Un worker traversait `SmithingRuneRecipe`, puis MoreJS,
et attendait le contexte Rhino détenu par le thread de chargement. Les dumps identifient
le cycle d’attente. `allowAsyncStreams=false` seul ne lève pas ce chemin de KubeJS.

Le test sur Apple Silicon utilise Minecraft 1.20.1, Monifactory 0.13.8, Forge 47.4.13,
Quark 4.0-462, KubeJS 2001.6.5-build.16 et Rhino 2001.2.3-build.10. Les JAR concernés et les
190 scripts de démarrage et de recettes du pack concordaient avec l’instance de référence.
La correction consiste à restaurer les fichiers publics `config/quark-common.toml` et
`kubejs/config/common.properties` du même pack dans la copie de test. Cette configuration
conserve `allowAsyncStreams=true`. Aucun script n’a été modifié et aucune fonction n’a été
désactivée pour le démarrage. L’agent JVM temporaire a été retiré de la commande de lancement.
Les deux fichiers ont été restaurés ensemble. La valeur Quark seule n’a pas été testée par
une seconde bascule indépendante.

Sur cette copie, le candidat `.41` a terminé l’initialisation des recettes, ouvert EMI et
affiché les plans, le diagnostic et le projet en pause via les boutons natifs. La commande
du processus confirme l’absence de `-javaagent`. Le JAR V1.5 `.40` recompilé au `5d280c3`
a chargé les recettes dans le même environnement. Son SHA-256 est
`0353fb16001572ab242b612011061ee06ce427d848324f2cbfdf37974127f834`. Ces essais isolent le blocage à la
configuration du banc. Pour reproduire, exporter le profil du pack en gardant ses
configurations publiques, écarter les sauvegardes et les sessions, puis créer un nouveau monde
Expert. Contrôler `Color Runes`, le fichier de configuration KubeJS et la ligne de commande
avant de conclure sur un échec de recettes.

### BUG-02 · Ancien marqueur lisible

Le test de reproduction a conservé `catalogue_production_etat.json` à `pret`, tout en retirant
la possibilité d’interroger le client. `ProductionService.context` renvoyait auparavant les
quantités et le diagnostic. Une écriture de révocation retardée ne pouvait donc pas protéger
le dialogue.

Python exige désormais une preuve positive à chaque consommation du contexte EMI. La preuve
reprend le monde, la session, la dimension, la source, la génération et l’empreinte du catalogue.
Un identifiant à usage unique distingue chaque contrôle. Minecraft répond à partir de l’arbre
et de la connexion en mémoire. Les réponses manquantes, illisibles, retardées ou portant un
autre identifiant n’autorisent aucun affichage courant ni transmission. La demande et sa
réponse sont deux fichiers de liaison à remplacement atomique. Ils gardent une
seule valeur chacun et ne rejoignent ni la file des commandes ni les sauvegardes.

La révision du projet et la demande restent vérifiées côté Python avant et après l’échange.
Le résultat expire après trois minutes. Un flux d’explication est revérifié avant publication,
et Minecraft masque en mémoire un aperçu dont le catalogue a changé. La voix lit la réponse
V2 complète après contrôle. La Discussion habituelle conserve son flux. Une vérification déjà
en cours peut différer un aperçu concurrent. Elle ne révoque pas un résultat valide.

Les tests reproduisent l’ancien marqueur, un reçu rejoué, les refus d’ouverture et d’écriture,
la concurrence et le redémarrage de Python. Le contrat Java suspend le worker de révocation
et constate que le marqueur ancien reste lisible, sans pouvoir produire de nouvelle preuve ni
maintenir l’aperçu. La reprise après une nouvelle publication est testée. La limite d’attente
du contrôle est de 450 ms par échange. Aucun relevé ancien ne remplace un stock indisponible.

Le JAR `.42` de SHA-256 `5d6516f6ffa57ae116d7f91fcf4326155330b153345e782cbbbf62bd8e441737`
a été chargé sans agent, avec le script client 12 et l’assistant Python du candidat. Le test
a rendu le seul marqueur du laboratoire immuable avec `chflags uchg`, puis a changé de branche
EMI. Le fichier est resté `pret` et identique octet pour octet. La nouvelle demande de preuve
n’a reçu aucun accusé correspondant. La question V2 a été refusée avant l’appel du fournisseur
simulé. Après restauration de l’attribut du fichier, la révocation a été publiée et le calcul
au marteau a donné 9 cuivre et 3 étain. Le projet fictif en pause est resté identique.

Sur ce passage, le pont a rendu la cintreuse en 0,91 s et le marteau en 1,06 s, de la demande
au résultat observable. Il s’agit de deux mesures en jeu avec les ticks et le pont complet.
Un verrou macOS immuable et une simulation `PermissionError` ne constituent pas un essai NTFS.

Le passage final a aussi alloué exactement 6 cuivre et 2 étain depuis les poches du joueur
fictif, avec zéro manque. La déconnexion a vidé l’identité et invalidé le résultat et la preuve.
La sonde de laboratoire appelait directement la fermeture du monde et a attendu indéfiniment
le serveur intégré. L’événement de déconnexion et l’invalidation sont observés. Le cycle
complet de reconnexion du JAR `.42` reste à rejouer avec le bouton normal. La reconnexion
avait été observée sur `.41`.

### Contrôles et décision

| Contrôle                          | Résultat                                                                                                                                                           |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Suite complète macOS              | 627 tests réussis. Les tests d’origine sont conservés.                                                                                                             |
| Scénarios EMI                     | 26 réussis.                                                                                                                                                        |
| Forge et Java                     | JDK 17, compilation `.42`, format Java et contrats natifs réussis.                                                                                                 |
| KubeJS, installation et formatage | Syntaxe Node, deux tests sur le Rhino du pack, Ruff, dépendances Python, `npm ci`, Prettier et `git diff --check` réussis.                                         |
| Mac, client seul                  | Forge, KubeJS et EMI sans agent, plans bronze et stock physique, refus réel après verrou macOS.                                                                    |
| Windows 11 et serveur à deux      | Non exécutés. Le verrou NTFS, les protections FTB et la séparation des deux installations restent à qualifier.                                                     |
| Codex et Claude                   | Transports simulés dans le jeu. Un appel Codex réel éphémère vérifie l’explication des chiffres. Claude réel et le pont natif de bout en bout restent à qualifier. |

Le durcissement est dans `48129e5`. La matrice des stocks et des lots est dans `11719f8`.
Voir [la qualification des stocks, R5 et les mesures](qualification-v2.md). Le candidat est
partiel et reste sur sa branche locale. Les commits V2 dépendent des contrats de cette branche.
Aucun de ces changements ne déclenche une mise à jour des installations qui suivent `main`.

Sur une deuxième instance temporaire, l’installateur a remplacé le JAR V1.5 `.40` par
l’artefact `.42` exact et conservé un seul JAR. La restauration par le manifeste a remis
le JAR et le script précédents avec leurs empreintes initiales. Le fichier fictif de plan
est resté identique à travers l’installation et le retour arrière.

L’appel réel autorisé a utilisé Codex CLI `0.167.0-alpha.3.2`, GPT-6.1 Sol et une session
éphémère en lecture seule avec les seuls chiffres fictifs. Le coût annoncé était 6 cuivre
et 2 étain, et le manque 2 cuivre et 1 étain. La réponse a conservé la pause et distingué
le relevé historique du coffre de la disponibilité inconnue de la machine. Ce contrôle
exerce le CLI réel hors jeu. Le test de liaison dans Minecraft utilise le fournisseur simulé.

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

### Préparer le checkout Windows 11

Dans le checkout du candidat, préparer un Python 3.12 avec Tk, Node et un JDK 17. Dans la
session PowerShell de test, affecter `$TestInstance` à la racine de la copie jetable, puis
contrôler les chemins avant toute installation.

```powershell
$env:MINECRAFT_DIR = $TestInstance
python -m pip install -r requirements-dev.txt
python codex_setup.py check
python -m ruff format --check .
python -m ruff check .
npm ci
npm run format:check
python -m unittest discover -s tests -v
python -m monifactory_assistant.commands.scenarios tous
& .\mod\gradlew.bat -p mod checkJavaFormat
node --check jeu/assistant_ia.js
python codex_setup.py build
python codex_setup.py install --dry-run
```

Si la simulation affiche la copie attendue et que ses processus sont fermés, lancer
`python codex_setup.py install`. Relever `Get-FileHash` sur le JAR installé et sa version
dans le journal du démarrage. Ouvrir le profil fictif, puis vérifier le script client 12,
l’onglet Production, les deux routes du bronze et le tableau commun de scénarios.

### Reproduire un marqueur lisible sous verrou NTFS

Sur **une copie Windows jetable** en mode Expert, ouvrir Production avec le JAR `.42` et
calculer les huit plaques à la cintreuse. Depuis PowerShell, ouvrir en lecture partagée le
marqueur existant. Garder cette fenêtre ouverte et effectuer l’essai dans Minecraft avant
d’appuyer sur Entrée pour libérer le fichier.

```powershell
$mark = Join-Path $TestInstance 'kubejs\assistant\catalogue_production_etat.json'
$lock = [System.IO.File]::Open($mark, 'Open', 'Read', 'Read')
try { Read-Host 'Effectuer les contrôles dans Minecraft puis appuyer sur Entrée' }
finally { $lock.Dispose() }
```

`$TestInstance` doit désigner explicitement la racine de la copie. Avec le verrou en place,
sélectionner le marteau de forge et vérifier que l’ancien résultat disparaît même si le
fichier garde `pret`. En Discussion, demander « Explique le plan de production V2 ».
La preuve doit être refusée avant appel au fournisseur. Répéter lors d’une réponse retardée
sur un fournisseur de test. Libérer le verrou, attendre la révocation et relancer le calcul.
Le nouveau résultat doit correspondre à la route au marteau. Rejouer lors d’un redémarrage
de l’assistant et d’une reconnexion. Relever le résultat du contrôle, la latence et l’erreur
éventuelle. Masquer les identifiants de session dans le relevé.

### Essais à deux et retour arrière

Sur un serveur de test configuré comme le serveur commun, les deux joueurs notent d’abord
leurs positions et dimensions mutuelles. Alterner une déconnexion, une reconnexion et un
passage de dimension. Tester un coffre accessible, puis un coffre identique dans une zone
FTB protégée. Répartir un ingrédient entre un coffre double, un stockage Sophisticated et
un backpack porté. Déplacer une pile et vérifier l’absence de double compte avec le relevé
précédent. Distinguer les résultats EMI historiques et les observations réellement utilisables
par Production. Chaque poste compare le hash de ses projets et sauvegardes avant et après.

Pour tester le candidat, conserver un checkout et une instance à part. Fermer le jeu et
l’assistant avant toute installation, compiler au JDK 17, noter le SHA du JAR et installer le
JAR avec son script client. Vérifier un seul JAR assistant dans la copie. Relancer Python
depuis le même checkout, puis Minecraft. L’installateur de test archive les fichiers remplacés
et le manifeste `installation.json`. Pour restaurer la copie, fermer les deux processus,
restaurer uniquement le couple JAR et script listé dans ce manifeste, puis relancer Python
depuis son checkout correspondant. Le retour sur la référence commune se fait par commit
correctif ou par revert coordonné. Voir [le guide de collaboration](collaboration.md).
