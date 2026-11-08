# Ordonnancement des fabrications EMI et GregTech

La version shared.46 présente les opérations restantes dans un ordre de fabrication exploitable. Le calcul des besoins de shared.45 conserve ses quantités et ses choix EMI. Une projection de présentation organise ces opérations et répartit virtuellement les ressources physiques nécessaires à une première fabrication de chaque recette recommandée.

## Références

| Élément                | Référence                                                          |
| ---------------------- | ------------------------------------------------------------------ |
| Base main vérifiée     | `91ab34f27de03291e13ee430ebb20d2787a30457`                         |
| Version précédente     | `1.0.0-shared.45`                                                  |
| Branche                | `codex/machine-order`                                              |
| Code candidat          | `b5d120409c5678adcf06cdab1e5b070398cae93f`                         |
| JAR                    | `assistantia-1.0.0-shared.46.jar`                                  |
| Empreinte du JAR testé | `3248c3cc169684f090bed7dd7b75a57061add03aa9e5886cb404c7fa228689dc` |
| Script client          | KubeJS 12, fichier identique à shared.45                           |
| Protocoles Production  | Capture et défi v2, suivi des lots v3                              |

## Ancien ordre et correction

`PlanMachines.calculer()` réserve les produits disponibles avant de visiter les composants. Cette orientation permet d'éviter des fabrications inutiles lorsque le produit intermédiaire est déjà possédé. Son ordre de parcours par niveaux était directement repris dans `MachinesEmi.lignes()` et dans le panneau des recettes d'une machine.

Chaque recette du suivi était vérifiée isolément contre le même inventaire. Deux recettes nécessitant trois lingots chacune pouvaient donc sembler simultanément réalisables avec quatre lingots. Le panneau conservait aussi un index lorsque la recette disparaissait. Son clic d'envoi pouvait relire une autre sélection après actualisation.

Shared.46 construit un ordre de présentation séparé. Le joueur garde les boutons d'action natifs, leurs validations et les quantités restantes. Les acquisitions, réservations, réceptions et crédits EMI continuent d'alimenter le calcul existant.

## Dépendances et allocation

`OrdreMachines` relie les recettes parent et enfant du vrai arbre sélectionné. Les recettes répétées partagent un sommet. Les nœuds sans recette sont traversés. Les opérations inutiles déjà éliminées par `PlanMachines` restent éliminées. Les cycles, les recettes absentes de l'arbre et les données non synchronisées donnent un état explicite.

L'ordre topologique est stable. Parmi les sommets admissibles, les recettes qui débloquent davantage d'étapes dépendantes passent en premier, puis l'ordre existant départage les recettes indépendantes. Le tri par groupe conserve cet ordre à l'intérieur de chaque groupe.

Une allocation virtuelle vérifie les besoins cumulés des recommandations avec `PlanRetrait`. Les variantes peuvent être réaffectées par le flot exact. Une ressource consommée ne couvre pas deux recommandations. Les outils réellement réutilisables partagent une clé exacte et une quantité maximale, sans être soustraits à chaque utilisation. Une propriété de durabilité seule ne transforme pas un objet consommé en catalyseur. Les fluides restent en mB. Un contenant demandé comme objet ou catalyseur ne fournit pas aussi son fluide à une autre recommandation.

**Prêt garantit au moins une fabrication recommandée.** La quantité restante affichée n'est pas la quantité simultanément réservée. Si un stock peut servir à plusieurs recettes, une priorité déterministe est affichée. Le joueur peut sélectionner l'autre recette dans la machine, avec l'indication de stock partagé. Son envoi repasse par le contrôle natif et le prochain relevé recalcule les priorités.

Les sorties attendues et les crédits historiques servent uniquement au calcul des besoins qui les connaissait déjà. Ils ne sont pas insérés dans le stock physique utilisé pour déclarer une opération prête.

## États et origines

| Groupe              | État et preuve                                                                                                                                                                                                                             |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| À lancer maintenant | Au moins une fabrication possède ses ingrédients utilisables. Pour GregTech, le menu courant et le précontrôle `EnvoiMachine.preparer(..., 1)` doivent autoriser le transfert. L'alimentation énergétique n'est pas garantie par cet état. |
| À préparer          | Ingrédients attestés à déplacer depuis le sac, le curseur ou le coffre ouvert, circuit connu à régler, case ou entrée à libérer. Une quantité manquante continue de bloquer la recette.                                                    |
| En cours et sorties | Lots natifs et quantités réellement suivies. La sortie attendue reste séparée du stock physique.                                                                                                                                           |
| Étapes suivantes    | Dépendance manquante nommée, stock affecté à une autre priorité, poste non vérifié, inventaire non synchronisé, fluide ou circuit illisible, cycle ou calcul trop complexe.                                                                |

Les 36 cases des poches sont distinguées des objets à sortir du backpack, de l'équipement et du curseur. Le coffre apporte uniquement sa trame serveur complète encore valable, avec les poches du même menu. Cette trame exclut les sacs et l'équipement qui n'en font pas partie. Les anciens relevés du scan ne deviennent pas du stock garanti pour cette décision.

Une machine inconnue reste indiquée comme à vérifier. Les autres postes EMI, comme un four ou une machine Thermal, restent inconnus lorsque seule leur recette est connue. Les recettes de la catégorie native `minecraft:crafting` peuvent être préparées sur la base des ingrédients. Dans le menu GregTech courant, les cases d'entrée et les cuves sont dédupliquées et ne servent directement qu'aux recettes compatibles avec cette machine.

Le circuit virtuel conserve sa logique GregTech. Un mauvais numéro connu propose un réglage. Une lecture illisible garde un état inconnu. Les moules et catalyseurs sont comptés selon les preuves présentes, sans convertir un ancien relevé de machine en disponibilité actuelle.

## Interface et navigation

Le suivi comporte quatre groupes repliables avec compteurs. Les groupes immédiatement utiles sont ouverts au départ. Quand ils sont vides, le premier autre groupe non vide reste accessible. Les choix de repli de l'utilisateur sont conservés. Le dessin est borné aux lignes visibles, y compris à 320 × 240.

Dans une machine, les recettes compatibles sont classées avant sélection. Les icônes indiquent les groupes par une barre colorée. Le survol conserve la quantité restante et la raison du classement. Un choix explicite reste attaché à sa recette. La sélection automatique peut suivre la première recette pertinente lorsqu'aucun choix explicite n'a été fait.

Les clics désignent le dernier contenu rendu, avec sa page, sa recette ou son lot. L'arbre, sa révision structurelle, sa quantité, le monde, la connexion et le contexte du projet sont revérifiés avant une action. Le panneau refait le précontrôle du transfert pour cette même recette. Une ancienne zone d'un panneau disparu cesse d'intercepter les événements. Échap depuis EMI retrouve le suivi ou la machine lorsque son menu est encore valable.

Le passage depuis un coffre vers Suivi ou le menu compact Aide préserve le menu serveur attesté. Un retour dans un menu fermé ou dans une autre connexion est refusé.

## Actualisation et coût

Les relevés actuels sont relus lors de l'actualisation existante, espacée de 500 ms. Le rendu ne calcule pas le graphe. Un résultat de présentation est mémorisé tant que l'arbre, les opérations, la connexion, le niveau, la machine, les stocks par origine et les conditions natives sont identiques. La preuve de coffre et le précontrôle sont vérifiés avant de réutiliser ce résultat. Le cache est explicitement libéré lors du nettoyage natif après changement d’arbre, de monde, de dimension ou de connexion.

Les acquisitions, les sacs, les entrées du menu, le circuit et les lots modifient les éléments pertinents de ce calcul. La pause et l'arrêt de l'arbre continuent d'utiliser leurs mécanismes existants. La lecture de l'ordre ne crée aucune réservation ni aucun crédit.

Le calcul est borné à 4 096 nœuds, 16 384 passages et règles, 8 192 sources physiques, quatre millions de comparaisons de prédicats et un coût cumulé de flot de 25 000 ressources × demandes après compression exacte. Un dépassement retire les disponibilités positives de ce calcul et indique que les variantes de l'arbre doivent être réduites.

Les mesures du code final utilisent JDK 17.0.15, sept chauffes et 21 échantillons, sur des fixtures synthétiques identiques aux deux versions. Les fichiers `PlanMachines` et `PlanRetrait` sont byte-identiques à shared.45. Des assertions sur chaque fixture ont retrouvé les mêmes recettes et quantités. La projection transforme la chaîne de 24 opérations du dernier produit vers les composants en un ordre des composants vers le dernier produit.

| Fixture                                  | Nouveau calcul hors cache, médiane / p95 | Allocations cumulées médianes par appel |
| ---------------------------------------- | ---------------------------------------: | --------------------------------------: |
| Chaîne de 24 étapes                      |                         0,591 / 1,117 ms |                                0,21 Mio |
| 1 500 opérations, ressource partagée     |                       60,361 / 97,223 ms |                              116,35 Mio |
| 1 500 opérations, sources distinctes     |                       15,116 / 15,750 ms |                                7,32 Mio |
| 200 alternatives imbriquées, coût refusé |                         4,237 / 4,833 ms |                                2,42 Mio |

L'énumération seule de la liste shared.45 prenait entre 0,002 et 0,073 ms en médiane sur ces fixtures. Cette borne basse exclut ses contrôles d'ingrédients et son interface. Le nouveau calcul ajoute le graphe et l'allocation partagée. Ces mesures ne représentent donc pas une accélération de l'interface ou un résultat de FPS. La mémoire indiquée est l'allocation cumulée du thread pendant un appel, sans mesure de mémoire retenue. Le cas extrême des 1 500 recettes sur une ressource reste coûteux ; le résultat inchangé est réutilisé par la passerelle. Les mesures n'isolent pas les coûts Forge, de réseau et de rendu.

## Tests automatisés

| Scénarios            | Contrats exercés                                                                                                                                     |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| ORDER-01 à ORDER-04  | Chaîne, branches indépendantes, stock partagé et opérations déjà éliminées par le calcul existant.                                                   |
| ORDER-05 et ORDER-06 | Coffre attesté, origine sac distincte, curseur, équipement et absence de faux envoi.                                                                 |
| ORDER-07 à ORDER-09  | Précontrôle et circuit, catalyseurs avec maximum des quantités répétées, objet endommageable consommé, unités mB et contenant partagé.               |
| ORDER-10 et ORDER-11 | Sorties attendues séparées des sources physiques et recalcul du reste selon les opérations fournies.                                                 |
| ORDER-12 à ORDER-15  | Nouvelle branche, cycle borné, dépendances partagées, informations périmées ou inconnues.                                                            |
| ORDER-16 et ORDER-17 | Passerelle de machine compatible et clics de suivi liés à leur identité rendue. Les interactions du panneau Forge sont qualifiées séparément en jeu. |
| ORDER-18 et ORDER-19 | Pas d'effet de lecture sur le projet, refus de la navigation ancienne et invalidation du résultat par changement de connexion ou de niveau.          |
| ORDER-20             | Grandes listes, rendu borné, résultat stable, réutilisation du calcul et cas d'alternatives coûteuses borné.                                         |

Les nouveaux contrats couvrent 25 scénarios du helper, 15 de la passerelle EMI et 17 de l'écran de suivi, ainsi que la conservation du menu natif du coffre. Le candidat exact a réussi **673 tests Python et contrats Java**, **26 scénarios EMI**, Ruff, le formatage Java, Prettier et la compilation Forge JDK 17. Aucun test antérieur n’a été supprimé pour obtenir ce résultat.

## Essais Minecraft et livraison

Les essais utilisent une copie jetable Monifactory Expert 0.13.8, Minecraft 1.20.1, Forge 47.4.13, KubeJS 2001.6.5-build.16, EMI 1.1.22, GTCEu 7.5.3 et JDK 17. Ils restent distincts des contrats synthétiques.

Les parcours complets suivants ont été exécutés **IN_GAME** sur le candidat de code `d45c815b8e561f5db42a510314db622f54b87d29`, JAR SHA-256 `246407fd4282123a55b594b4b406975594f185240ebd7404537d8b75018d416a`. Le candidat final ajoute uniquement la libération explicite du cache lors du nettoyage du contexte. Le graphe, les allocations et les interfaces de ces parcours sont identiques.

| Scénario                               | Résultat observé                                                                                                                                                                                                                                                                                   |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Chaîne de trois étapes réelles         | `gtceu:alloy_smelter/copper_ingot_and_tin_ingot_into_bronze`, `gtceu:bender/bend_bronze_to_plate`, puis `gtceu:bender/bend_bronze_plate_to_double_plate`. Ordre alliage ×2, plaques ×8, doubles plaques ×4.                                                                                        |
| Acquisition d'un intermédiaire         | Huit lingots de bronze physiques retirent l'alliage des opérations restantes. Huit plaques physiques retirent ensuite la fabrication intermédiaire des plaques. Les quatre doubles plaques restent à produire.                                                                                     |
| Recette et retour                      | Le bouton ouvre la vraie recette EMI. Une touche Échap ciblée sur la fenêtre QA emprunte le chemin natif GLFW/Forge et retrouve le suivi avec sa sélection.                                                                                                                                        |
| Coffre et petit menu Aide              | Le paquet complet garde la même révision pendant Aide et Suivi. Retour au vrai coffre. Un transfert QUICK_MOVE de six cuivres invalide ensuite ce relevé.                                                                                                                                          |
| Lot manuel et étape suivante           | Déclaration native d'un lot alloy ×2, huit lingots attendus et zéro reçu dans le groupe des lots. La cintreuse reste une autre opération bloquée par le lingot physique manquant. Le gain d'un fer sans rapport ne modifie pas le lot. L'annulation restitue l'opération d'alliage sans transfert. |
| Envoi dans une vraie machine           | LV Alloy Smelter ouverte, recette compatible sélectionnée et précontrôle favorable. Le clic natif Envoyer dépose six cuivres et deux étains dans les entrées. La machine n'est pas alimentée pour cette vérification.                                                                              |
| Fermeture du panneau                   | Les anciens clics et la molette ne sont plus interceptés après fermeture de la machine et retour à l'inventaire.                                                                                                                                                                                   |
| Panneau disparu dans la même machine   | Cintreuse maintenue ouverte, arbre remplacé par une recette Alloy incompatible. Le panneau devient caché et son plan nul. Les clics et la molette dans l’ancien rectangle ne sont pas interceptés et aucune exception n’est produite.                                                              |
| Circuit réel manquant                  | La cintreuse lit le circuit requis 1 et le réglage actuel absent. Le panneau affiche Circuit à régler et refuse Envoyer. Le réglage par widget et le cas natif lot + autre opération Prêt restent NOT_TESTED.                                                                                      |
| Petite fenêtre                         | Contrôle visuel à 720 × 510. Lignes et boutons dans leurs zones, descriptions longues tronquées avec détail au survol. Le titre est partiellement couvert par le bandeau FancyMenu de personnalisation de cette copie de laboratoire.                                                              |
| Deux recettes, quatre lingots pour six | **AUTOMATED** sur helper et passerelle. Un arbre réel adapté reste à attester pour ce cas exact.                                                                                                                                                                                                   |

Le candidat final `b5d120409c5678adcf06cdab1e5b070398cae93f` a ensuite été chargé normalement avec l'empreinte `3248c3cc169684f090bed7dd7b75a57061add03aa9e5886cb404c7fa228689dc`. Ce nouvel essai **IN_GAME** confirme la chaîne à trois opérations puis deux après acquisition physique, le retour par la vraie touche Échap depuis EMI, ainsi que coffre → Aide → Suivi → même coffre avec sa révision de capture conservée. La sonde constate le cache présent avant **Sauvegarder et quitter**, puis nul avec joueur et connexion absents. Les processus de test sont arrêtés après cet essai. Ce contrôle final ne remplace pas les parcours complets du tableau sur le précédent candidat.

Les acquisitions d'intermédiaires sont des objets réels de la copie QA, ajoutés par les commandes de préparation du test. Ce constat qualifie la réaction de l'ordre au stock physique. Il ne qualifie pas une fabrication machine achevée. Les machines sans menu contrôlé restent signalées comme à vérifier. La sonde de laboratoire utilise ses propres fichiers et les appels fournisseur y sont bloqués.

**Qualification macOS ciblée réussie.** Le scénario de stock concurrent quatre pour six est automatisé sur le helper et la passerelle, avec les classes de production. Le cas exact d'une opération prête à côté d'un lot en cours reste automatisé et doit encore être rejoué manuellement dans le jeu. La sonde a pu lire et constater le mauvais circuit, sans piloter sûrement son widget de réglage. Ces limites ne sont pas des preuves de réussite native. La cadence du garde de rafraîchissement du panneau vide n'a pas été chronométrée en jeu.

La livraison shared.46 publie l'ordre et la présentation sur `main` et conserve la branche `codex/machine-order` pour le suivi du changement. Les tests Windows restent **NOT_TESTED** et utilisent le candidat exact indiqué ci-dessus.

Le code serveur, le lot 2, R5, les formats de données personnelles, les calculs de besoins et le moteur de transfert sont hors de cette modification. La compilation possède sa version propre shared.46. Les essais Windows du même code restent à exécuter sur une copie.

Pour Windows, reprendre les commandes de [la qualification native](qualification-v2-native-stock-lots.md#h--livraison-et-prochaine-qualification) avec le commit de la section Références et le JAR `assistantia-1.0.0-shared.46.jar`. Noter l'empreinte du build local et la comparer au seul JAR installé. Rejouer une chaîne machine, deux branches partageant leur matière, une machine offrant plusieurs recettes et un lot en cours accompagné d'une recette prête. Contrôler le mauvais circuit, le sac fermé, le retour EMI, le repli des groupes, la reconnexion et un projet fictif en pause. Les échanges avec Claude utilisent la configuration personnelle de ce poste.

## Préparer le même candidat sous Windows 11

Créer un checkout de test séparé et une copie CurseForge Monifactory Expert. Le commit fixe le code testé ; les éventuels descendants contenant uniquement les guides peuvent être consultés séparément.

```powershell
git clone https://github.com/B-DAGBERT/monifactory-claude-assistant.git monifactory-order46-test
Set-Location .\monifactory-order46-test
git switch --detach b5d120409c5678adcf06cdab1e5b070398cae93f
git status --short --branch
$env:MINECRAFT_DIR = (Resolve-Path (Read-Host 'Copie Monifactory jetable')).Path
$env:JAVA_HOME = (Resolve-Path (Read-Host 'Dossier du JDK 17')).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
py -3.12 -m venv .venv
$Py = (Resolve-Path '.venv\Scripts\python.exe').Path
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
Get-FileHash .\mod\build\libs\assistantia-1.0.0-shared.46.jar -Algorithm SHA256
& $Py codex_setup.py install --dry-run
```

Vérifier la cible de la simulation, fermer les processus de la copie, puis installer dans cette copie avec `& $Py codex_setup.py install`. Comparer l'empreinte du build local à celle du seul JAR assistant installé. Consigner le commit, Windows, Python, Java, la version Forge, les cas exécutés et les résultats. Une compilation Windows peut produire une autre empreinte d'archive. Conserver la sauvegarde du couple JAR et script indiquée par l'installateur pour le retour arrière. La qualification Windows reste **NOT_TESTED** tant que ce parcours n'a pas été exécuté sur ce poste.

## Fichiers et responsabilités

- `OrdreMachines` calcule l'ordre et la répartition virtuelle.
- `OrdonnancementEmi` lit les sources et préconditions natives puis fournit une vue bornée.
- `MachinesEmi.lignes()` construit les états du suivi et `actualiserInterface()` ordonne les recettes compatibles.
- `PanneauMachineEmi` affiche les états déjà calculés et les raisons au survol.
- `SuiviEmiEcran` gère groupes, défilement, sélection et retours.
- `StockProduction` et `CoffresArbreEmi` préservent le menu attesté pendant la navigation vers Suivi ou Aide.
- Les tests dédiés gardent les scénarios d'ordre, de passerelle, de navigation et de non-régression.
