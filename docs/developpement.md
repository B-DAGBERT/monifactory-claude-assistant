# Développement

## Repères

- `monifactory_assistant/app.py` assemble les composants, branche les raccourcis et supervise le processus.
- `application/game.py` suit les relevés du monde, les quêtes, le journal et les actions de jeu.
- `application/conversation.py` traite les questions, les fiches et les événements vocaux.
- `application/interface.py` gère les fenêtres locales, les réglages et les demandes du menu Minecraft.
- `game/bridge.py`, `recipes.py`, `world.py` et `context.py` isolent les échanges et calculs du jeu.
- `ui/views.py` construit la vue écrite pour le mod (réglage du panneau, bulle, guidage, tableau de bord) ; `audio.py` contient la chaîne micro, transcription et voix.
- `platform/capture.py` et `hooks.py` isolent la capture, le focus et le choix des raccourcis natifs.
- `settings.py` conserve les constantes et chemins historiques ; `prompts.py` contient les instructions IA.
- `providers/` isole les deux moteurs : le choix de l’un ne charge pas l’autre.
- `commands/` contient les commandes exécutées par les lanceurs Mac et Windows.
- `paths.py` définit la racine des données et celle du code. Ne pas déduire le dossier des sauvegardes depuis l’emplacement d’un module.
- `ui/discussion.py` sert uniquement le suivi en lecture seule ; ses fichiers sont dans `ui/web/`.
- `game/` contient la mémoire des stockages, les relevés d’inventaire, la recherche EMI dans les coffres et la lecture des filons. Le journal GregTech du client reste la source des filons ; Python lit le résumé `filons_mod.json` du mod.
- `mod/src/main/java/fr/assistantia/` contient les écrans et fonctions Forge ; `compat/emi/` regroupe l’intégration EMI.
- `Theme`, `BoutonTheme`, `ChampTheme` et `BarreDefilement` donnent le style commun de toutes les interfaces ; `tests/stubs_theme.py` fournit leurs stubs aux harnais d’écrans.
- `OngletRecette` et `VueRecette` affichent l’onglet Recette, calculé par `compat/emi/AnalyseRecette` avec `ArbreRecette`, `ChaineRecette`, `ComparaisonAlternatives`, `FabricationFacile`, `PossessionsRecette`, `RangsRecette`, `ReperesRecette` et `MatieresArbreEmi`. `CoffresConnus` lit pour ce calcul la mémoire des coffres du client (`stockage.json`) et `coffres_connus.json`, que l’assistant publie après sa fusion avec le relevé du serveur (tiroirs jamais ouverts compris) : par position, le relevé le plus récent gagne.
- `Filons`, `OngletFilons`, `FilonMemoire`, `ScanFilon`, `VolumeFilon`, `VerificationFilons`, `GregTechFilons`, `CouleursFilons`, `StyleFilons`, `FormatFilons`, `InstantaneFilons`, `SauvegardeFilons` et `TableLong` portent les filons.
- `Ecritures` écrit les fichiers d’échange sur un fil dédié ; `EchangesKubeJS` est le point d’entrée du script client.
- `jeu/` contient le script KubeJS client d’origine : il exporte l’état du jeu et la mémoire des coffres, sans dessiner de panneau. `serveur/` contient le script de relevé avec couverture des chunks.

## Compatibilité

Les lanceurs et les points d’entrée `assistant_mc.py`, `codex_setup.py`, `choose_ai.py` et
`update_assistant.py` restent à la racine. Ils délèguent au paquet Python et permettent aux
installations existantes de continuer à se lancer et à se mettre à jour.

Les configurations, sessions, coffres et journaux personnels conservent leur emplacement
historique à la racine, en dehors du code et ignorés par Git. Les données ne sont pas migrées
pendant un simple rangement des sources. L’ancien `plan_ia.json` des projets et objectifs n’est
plus lu ni écrit ; il reste en place avec ses copies.

## Formatage et tests

Les versions des outils sont fixées dans `requirements-dev.txt`, `package-lock.json` et `mod/build.gradle`.

```sh
python -m pip install -r requirements-dev.txt
python -m ruff format .
python -m ruff check .
npm ci
npm run format
bash mod/gradlew -p mod formatJava
python -m unittest discover -s tests -v
python codex_setup.py build
```

Utiliser `python -m ruff format --check .` et `npm run format:check` pour contrôler sans modifier.
Le Java se vérifie avec `bash mod/gradlew -p mod checkJavaFormat`. Les scripts KubeJS sont
exclus du formatage automatique. La parité couvre le client. Le relevé serveur est vérifié avec
une simulation Node.js du script KubeJS.
Les commandes Gradle directes nécessitent `JAVA_HOME` pointant vers un **JDK 17** ; elles ne
lisent pas `assistant.local.json`. `python codex_setup.py build` résout, lui, le réglage `java_home`
et les emplacements détectés. Vérifier `java -version` si Gradle refuse de démarrer.
Les workflows GitHub Actions ont été supprimés pour préserver le quota. Ces vérifications
se lancent localement avant de publier sur `main` ; les tests et les outils de compilation
restent disponibles. La mise à jour de l’assistant depuis `main` fonctionne indépendamment
de GitHub Actions.

Les [scénarios EMI à la demande](scenarios.md) se lancent avec **Tester Mac.command**, **Tester.bat**
ou `python -m monifactory_assistant.commands.scenarios basique|complexe|tous`.
Ils compilent seulement les contrats Java temporaires, sans reconstruire ni installer le mod.
Les rapports de chaque cas sont conservés dans `.runtime/scenarios/`.

Les scénarios de mise à jour utilisent de vrais dépôts Git temporaires. Les tests des moteurs
utilisent des CLI simulés : aucun compte IA ni appel facturé n’est nécessaire.

## Livraison

Travailler sur une branche de fonctionnalité issue de `main`, tester, puis fusionner vers `main`.
La commande **Mettre à jour** récupère uniquement `origin/main` et refuse d’écraser des modifications
locales ou des commits non publiés. Ne pas utiliser de reset forcé pour contourner ces protections.

## Thème commun des interfaces

Toute interface nouvelle ou modifiée passe par `Theme`, `BoutonTheme` et `ChampTheme`, sans couleur
ni taille écrite à la main. `Theme` fixe les couleurs (fonds, cartes, bordures, doré pour l’accent et
la sélection, vert, jaune, rouge, bleu, cyan), les tailles et les dessins communs : panneaux, cartes,
séparateurs, cases d’objet, barres de progression et de défilement (`BarreDefilement`), infobulles
et `Theme.Fenetre` pour les écrans secondaires. Boutons, onglets et champs ont une seule hauteur,
`Theme.BOUTON` (16).

`BoutonTheme` reste un `Button` vanilla : clic, son, focus clavier, narration et infobulles sont
natifs. Son genre (`NORMAL`, `PRINCIPAL` en vert, `DANGER`, `ONGLET`) fixe sa couleur et
`choisi(true)` dore un onglet ouvert ou une option active, qui reste cliquable. `ChampTheme` habille
`EditBox`. Un texte visible se coupe avec `Theme.couper` ou `Theme.texte` (« … » et texte complet au
survol), jamais avec `plainSubstrByWidth`. Si une primitive manque, l’ajouter dans `Theme` de façon
générique.

Les harnais qui compilent de vrais écrans contre des stubs Minecraft ajoutent les sources du thème
et les stubs de `tests/stubs_theme.py` (police, bouton protégé, infobulles, `GuiGraphics`).
`test_theme_panels.py` vérifie que les panneaux EMI, coffres, machines et établi n’ont pas d’autre
source de style et gardent une seule hauteur de bouton.

## Collecte et machines EMI

- `RechercheEmi` possède la sélection globale du scan et ses quantités. `AideCoffre` et `RecuperationEmi` passent par cette sélection ; aucun retrait ne doit reconstruire un arbre depuis le seul coffre ouvert.
- `MemoireCollecte` conserve les ingrédients acquis ; `PlanArbre` réserve les composants avant leurs enfants. `RetraitCoffre.planRecherche` applique les quotas aux cases réelles, sans déduire deux fois l’inventaire.
- `SuiviFabrication` conserve les acquisitions finales et déduit les retours d’objets déjà sortis. `OrigineFabrication` rapproche les gains réels des événements de craft et des sorties natives ; ne jamais compter l’aperçu de résultat. `CycleArbreEmi` suit la durée de vie du craft et retire l’arbre d’EMI quand ses cibles finales sont obtenues ; il n’exporte plus `fabrication_emi.json`.
- `LotFabrication` distingue réservation et récupération réelle. `ReceptionSortie` rapproche les notifications séparées des cuves et des contenants. `MachinesEmi` garde seulement les sorties encore attendues dans son stock réservé. `SuiviEmiEcran` regroupe les états, le recalcul et l’annulation ; annuler ne déplace aucun objet.
- Les allocations de `MachinesEmi` sont invalidées par une modification de l’arbre, des stocks ou des lots. Le cache des compatibilités machine est également vidé lors d’un changement de contexte, toutes les 100 ticks et sur recalcul explicite.
- `ConfigurationCircuit` lit les ingrédients natifs GregTech, y compris un `SizedIngredient`. Le réglage est contrôlé via `getCircuitInventory`, sans setter ni repli vers un circuit physique. `ComparaisonEmi.circuitVirtuel` ne retire que les catalyseurs circuits des recettes GT, jamais une cible racine ni un circuit consommé. Pour le stock natif EMI, les outils `IGTTool` conservent la comparaison par défaut ; leur matériau et leur type sont portés par l’Item. Les comparaisons explicites EMI restent inchangées.
- `SequenceEnvoi` attend une confirmation stable avant l’action suivante. `EnvoiMachine` valide l’interface, les sources, les entrées et les cuves ; il n’effectue aucun nouvel essai automatique après un refus.

Les tests `test_emi.py`, `test_chest_transfers.py` et `test_machine_inputs.py` rejouent les allocations et les interactions client dans des conteneurs déterministes. Ils couvrent notamment les composants répartis entre coffres, les transformations partielles et en chaîne, les retours du même objet, les lots finaux rangés séparément, les outils partagés, les réceptions et annulations partielles, les circuits à NBT factice, les cases verrouillées, les doubles clics et les fluides. Ils ne remplacent pas une validation visuelle dans Minecraft ni les réponses d’un serveur réel.

## Inventaire personnel et backpacks

`InventaireJoueur` est la source commune du scan, du suivi final et du plan machine. `compat/backpacks/SacsPortes` utilise les API natives de Sophisticated Backpacks de manière optionnelle : inventaires enregistrés (dont Curios), lecture du menu ouvert et requête de contenu d’un UUID porté. Le mixin client `ContenuSacMixin` observe la réponse native, y compris pour un sac vide ; aucune nouvelle extension serveur n’est requise.

Une seule requête par sac reste en vol. Le relevé est lié à la révision des quantités personnelles ; une réponse précédant un déplacement ne peut pas valider le nouvel inventaire. Le cache est effacé à la déconnexion et limité aux sacs encore portés. `InventaireArbreEmi` raccorde cette vue aux deux calculs natifs d’EMI (Recipe Tree et favoris de fabrication) via des mixins optionnels. Il reconstruit le stock personnel avec les vraies grilles de craft, garde les fluides natifs et ne modifie jamais les inventaires des remplisseurs. Le relevé doit appartenir à la connexion actuelle. La liste est rafraîchie quand le stock change, sans réinitialiser la disposition des panneaux.

Le fichier `inventaire_joueur.json` exporte une vue complète ; `game/inventory.py` la substitue au relevé KubeJS seulement si elle est synchronisée, récente et correspond au joueur et à la dimension. Ne jamais ajouter ces quantités à un autre relevé : cela compterait deux fois les poches. `InventaireJoueur` compare les cases à chaque tick sans copie, réécrit le fichier seulement à un changement (au plus deux fois par seconde) et confirme un relevé inchangé toutes les 2 s ; Python le juge récent pendant 5 s.

`test_backpack_inventory.py` rejoue la lecture native, Curios, les grosses piles, les sacs imbriqués, les déplacements, les réponses retardées, le retrait d’un sac et le remplacement des relevés Python. Les transferts restent limités aux cases du menu natif ; les tests ne prétendent pas simuler un serveur Minecraft complet.

## Édition d’un arbre et lots manuels

`CoffresArbreEmi` consulte toutes les dix ticks la révision de l’instantané partagé avec `MachinesEmi` quand le scan est activé, hors transfert. Une modification remplace les quotas ; les anciennes réponses sont rejetées par leur numéro. `MemoireCollecte.reprendre` garde les acquisitions pertinentes et `reserver` ajoute un stock de planification réversible. Les matières historiquement envoyées sont retirées de cette seule vue pour éviter de créditer à la fois un résultat attendu et ses ingrédients.

`MachinesEmi.declarer` revalide la recette et les fabrications encore nécessaires à la confirmation. Les lots manuels ne déplacent rien et ne servent jamais de preuve de production finale. `ReceptionManuelle` mesure les gains du stock personnel consolidé, en déduisant les réceptions déjà attribuées aux lots automatiques. Un même gain ne remplit qu’un seul lot. Les quantités finales peuvent changer sans perdre les acquisitions de `SuiviFabrication`.

Les tests couvrent annulation, réception partielle, crédits temporaires, changement de branche, quantité de cible modifiée et lecture du backpack dans le stock natif de l’arbre. `test_emi_backpack_tree.py` exerce l’adaptateur avec des contrats d’API : la présentation et l’application des mixins doivent aussi être vérifiées après redémarrage du jeu réel.

`LotMachine` représente explicitement une position connue ou une déclaration manuelle. Toutes les comparaisons avec la machine ouverte passent par `ici` ; aucun lot manuel ne possède de coordonnées fictives. Le panneau neutralise une erreur locale sans propager l’exception au rendu du jeu. Le test de régression rejoue une déclaration suivie de l’ouverture d’une machine, puis une réception partielle et une annulation.

## Audit de stabilité shared.28

`game/storage.py` centralise l’identité des stocks pour le relevé serveur et le scan EMI. Le
regroupement de doubles coffres compare leur inventaire complet, pas seulement les ingrédients
recherchés, et consulte les quatre voisins par index. Les stockages indépendants gardent chacun
leur quantité. Les doubles coffres vanilla et Sophisticated Storage sont reconnus ; les barrels
et drawers ne sont jamais fusionnés. Sans propriété de moitié dans l’ancien relevé serveur,
deux coffres simples voisins au contenu parfaitement identique restent un cas ambigu. Les deux positions d’un double coffre restent visibles pour les contours.

`Bridge.state` conserve seulement une trame récente et valide. Une écriture partielle, une date
invalide ou future ne remplace pas le dernier état sain et ne maintient pas artificiellement un
monde connecté. Le suivi Discussion met en cache un fichier inchangé mais recalcule toujours son
état en ligne ; le remplacement atomique du fichier invalide le cache.

Le mode gamma restaure la liaison de texture via `LiaisonTexture` dans un bloc `finally`.
`Reperage` et `Guidage` utilisent des buffers dédiés réutilisables ; ne pas appeler `endBatch()` sur
le buffer global depuis un rendu secondaire. Le filtrage et le tri de Base sont recalculés au
changement de relevé ou de filtre, plutôt qu’à chaque image.

Une requête IA en attente n’est pas propriétaire du tour déjà en cours : son délai expiré ne doit
pas arrêter ce tour. Une erreur d’un consommateur de réponse abandonne sa propre séquence sans
réutiliser des fragments pour la question suivante. Les aperçus Claude sont limités à environ huit
par seconde ; le texte final est toujours transmis après succès, avant la lecture vocale.

Les tests de rendu utilisent des contrats de l’API et des états déterministes. Ils vérifient le
rétablissement de la texture, la saisie conservée au redimensionnement et la déclaration unique
d’un lot. Ils ne mesurent pas les FPS et ne remplacent pas une vérification avec les shaders,
les mods de rendu et un serveur réels. Voir [le bilan de validation](audit-shared28.md).

`OrigineFabrication` utilise `ReceptionSortie` pour rapprocher les paquets objets reçus dans un
ordre différent. Le calcul se limite aux types de sortie connus ou en attente ; les objets sans
rapport d’un gros backpack ne sont pas comparés entre eux. Un craft déjà prouvé est exclu du
rapprochement. Les attentes d’un lot automatique sont également exclues des gains manuels.

`SuiviFabrication` peut corriger un gain provisoirement considéré comme un retour d’objet lorsqu’une
preuve arrive ensuite. Cette correction restaure aussi la dette de retour de l’ancien objet rangé.
La fenêtre est bornée à vingt observations : une preuve trop ancienne n’est pas réattribuée. Les
tests rejouent les deux ordres de paquets, la réception partielle et les retours ultérieurs.

Dans `SacsPortes`, un échec d’envoi natif libère le marqueur d’attente ; la prochaine lecture peut
réessayer après le délai existant. Une vraie requête encore en vol n’est jamais dupliquée.

Le chargement des relevés ouverts (`Storage.load`) conserve sa règle historique de priorité par
date pour les moitiés de grands coffres, même lorsque leur contenu mémorisé diffère. L’index des
voisins remplace seulement le parcours de tous les stockages déjà retenus. Les régressions couvrent
les relevés vides récents et l’indépendance des stockages simples.

`ReceptionManuelle` conserve les sorties d’objets déjà possédés pour déduire leurs retours, avec
les preuves restantes après attribution aux lots automatiques. Ajouter un nouveau lot ne doit
pas oublier les retours attendus des lots précédents. Les tests vérifient les rangements/reprises,
les fabrications successives prouvées et les preuves tardives sans double attribution.

## Réglages IA en jeu

Le sous-écran **Modèles IA…** utilise un canal séparé, `configuration_ia.json`, afin qu’un réglage
ne remplace pas une question dans `demande_mod.json`. La demande contient un numéro unique, le
moteur actif, la révision du formulaire et les quatre valeurs `model_question`, `effort_question`,
`model_plan`, `effort_plan`. Seul le profil **Discussion et fiches** est affiché : les deux valeurs
`plan`, qui servaient aux anciens projets et objectifs, sont renvoyées telles quelles pour garder le
protocole. L’accusé dans `ecran.configuration_ia.reponse` doit correspondre au numéro envoyé ;
écrire le fichier n’est pas une confirmation de sauvegarde.

La sauvegarde est locale et atomique, sans essai IA. Le service refuse une demande périmée, un
formulaire obsolète, un changement de moteur ou un changement pendant un travail. Les fichiers
modifiés par un autre outil ne sont pas écrasés. Le chargement ne publie que les noms de modèles,
les efforts et les états du formulaire ; aucune autre clé de configuration n’est exportée au jeu.

Les questions et les fiches relisent leurs réglages à chaque nouvelle demande. La conversation
Codex conserve son thread et passe explicitement le modèle et l’effort à chaque `turn/start`,
conformément à la [documentation officielle OpenAI](https://learn.chatgpt.com/docs/app-server).
Claude conserve son identifiant de session et renouvelle son processus au prochain appel lorsque
ses paramètres ont changé. Les crédits de fabrication ne sont pas concernés par ce canal.

## Catalogue des modèles (shared.30)

`providers/catalog.py` lit uniquement les métadonnées publiques du cache local Codex : identifiant,
nom affiché et efforts supportés. Il conserve l’ordre, exclut les modèles masqués et ne transmet ni
identité du compte ni instructions internes. Les lectures sont limitées par délai et signature du
fichier ; une écriture partielle déclenche un repli vers la configuration connue. Les alias Claude
proviennent de la [configuration officielle Claude Code](https://code.claude.com/docs/en/model-config).
La liste ne vérifie pas les droits d’accès et ne lance aucune inférence. `CatalogueModeles` et
`EcranChoixModele` présentent ce catalogue et ajustent uniquement le brouillon sur choix explicite.

Les tests couvrent cache absent/corrompu/remplacé, données privées exclues, ainsi que les écrans
réels via contrats Minecraft : sélection, confirmation, Échap, attente conservée, recherche,
redimensionnement et limites en 320 × 240. Les contrôles en jeu restent à effectuer après redémarrage.
Les projets, leur catalogue et `EcranProjets` ont été retirés avec les objectifs.

## Découpage de l’application

`App` compose trois contrôleurs par héritage explicite : jeu, conversation et interface. Ils
partagent le même état et la même boucle d’événements Tk : les déplacements n’ajoutent ni service,
ni copie de l’inventaire, ni seconde source de progression. Chaque méthode reste définie dans un
seul contrôleur. Les contrôleurs s’appellent par `self`, et ne s’importent pas entre eux. Aucun
module de domaine ne réimporte `app.py`.

Les vues JSON restent dans `ui/views.py`. Les réponses structurées et la session commune aux deux
moteurs sont isolées dans `providers/responses.py` et `providers/session.py`. La reconnaissance
vocale conserve ses imports lourds différés. Les modules de jeu peuvent être importés sans Tk,
capture, micro ou moteur IA.
Les constantes de données utilisent toujours `paths.ROOT`, même après déplacement d’un module.

Pour modifier une fonctionnalité, commencer par son contrôleur puis son calcul de domaine ;
garder dans `app.py` uniquement l’initialisation, le branchement et le lancement. Les anciens
noms de commandes, formats JSON, chemins, modèles et réglages sont conservés.

Les tests ciblés qui injectent un environnement sans interface lisent les définitions de production
avec `tests/app_source.py`. Ce lecteur rassemble les méthodes réellement héritées par `App` ; il
ne contient aucune copie de leur logique. `test_application_modules.py` vérifie aussi les imports
sans dépendances de bureau, l’absence de méthodes masquées entre contrôleurs, l’absence de toute
préparation d’objectif à la connexion et l’abandon sans effet des demandes de projet ou d’objectif
d’un ancien mod. La vérification de parité historique continue à comparer le comportement conservé
malgré le déplacement des définitions.

## File durable, sauvegardes et rendu (shared.31)

`game/requests.py` lit au plus huit demandes par passage dans `kubejs/assistant/demandes/`.
`FileDemandes` publie un fichier atomique par UUID et numéro chronologique ; un renvoi conserve
ces deux valeurs. La file du client est limitée à 128 fichiers. Le protocole 2 est annoncé dans
`ecran.demandes` ; les anciens couples client/assistant utilisent encore `demande_mod.json`.
Les réglages IA et le scan EMI gardent leurs canaux indépendants.

Le canal historique utilise aussi la réservation durable. Le numéro d’une ancienne demande
sert de clé de déduplication quand l’UUID est absent. Un client qui renvoie son UUID par la file
version 2 retrouve le même reçu.

Avant chaque exécution, une réservation est écrite dans `reponses_demandes.json`. La confirmation
et les accusés de question sont enregistrés avant suppression du fichier de demande.
Un résultat durable est restitué lors d’un renvoi, sans refaire l’action. Une réservation laissée
par un arrêt devient « interrompue » ; le résultat peut avoir été appliqué et ne doit jamais être
rejoué automatiquement. Un historique illisible bloque la file plutôt que d’effacer la preuve.
Les commandes périmées et les contextes connexion/dimension différents sont refusés. Les accusés anciens ne remplacent pas ceux d’une demande plus récente. Ce protocole
privilégie l’absence de double exécution ; il ne promet pas de terminer une action après un crash.

`persistence.py` partage les écritures de sauvegarde : fichier temporaire unique dans le même
dossier, conservation des permissions, flush/fsync, remplacement atomique, nettoyage et copie
précédente `.bak` lorsqu’elle contient un JSON valide. Une copie corrompue ne remplace pas un backup
sain. Les lectures peuvent récupérer la copie. Les chemins restent identiques. Le canal de réglages IA conserve
ses vérifications supplémentaires de révision, sauvegarde son ancienne configuration et revalide
la révision juste avant son écriture atomique dédiée.

Le rendu utilise une horloge monotone. `game/snapshots.py` partage inventaire, progression,
totaux des coffres et machines uniquement pendant un cycle.
Le contexte est isolé par thread et supprimé en sortie, y compris en cas d’erreur. Aucun cache de
quantités ne survit au cycle suivant. `diagnostics.py` exporte des données locales limitées ; le
menu affiche aussi l’état natif du client et le temps du dernier calcul de vue.

`test_reliability.py` couvre rafales ordonnées, budget par passage, doublons, redémarrages avant et
après dispatch, échec de réservation, échec de confirmation, suppression de fichier retardée,
contexte changé, déconnexion, sauvegarde corrompue, écrivains concurrents et cache de rendu.
Les contrats Java exercent la vraie file, les limites de capacité, la pagination en 320 × 240,
les transferts avec paquets retardés et les confirmations instables. Ces tests simulent les
interactions natives ; les délais et corrections du serveur réel restent à vérifier en jeu.

## Réservations EMI et collecte des sorties (shared.32)

La réservation d’un lot est un stock virtuel pour le calcul et le pliage des branches EMI. Dans
`MemoireCollecte`, cette réservation conserve désormais l’identifiant du résultat dans les demandes
de recherche. `dansCoffres` alloue d’abord les branches encore à fabriquer, puis les sorties attendues
sur le stock restant : un même objet de coffre ne peut pas être proposé deux fois. Les acquisitions
réelles restent gérées séparément et une annulation retire la réservation.

`RechercheEmi` conserve les groupes physiques contenant un résultat attendu et les expose pour le
contour jaune et l’état « À récupérer ». Cette observation ne touche pas les compteurs reçus.
`ReceptionLots` répartit les gains réels une seule fois entre les lots automatiques et manuels ;
les fluides automatiques gardent leurs preuves de sortie natives. La réception manuelle existante
continue d’écarter les simples déplacements et reprises de stock déjà compté.

Après un envoi limité, `DeclarerFabricationEcran` présente la quantité réellement envoyée et le
lot complet. `LotFabrication.ajouter` étend la même ligne sans effacer ses réceptions. La confirmation
revalide l’identité de l’arbre et les besoins encore déclarables ; un double clic ne crée pas un
second lot. L’arrêt de l’arbre et le changement de monde/connexion conservent les règles
de nettoyage précédentes. Les tests Java couvrent lot envoyé/complet, quantité devenue invalide,
ancien arbre, retrait sur plusieurs coffres, réception partielle, sac, annulation et absence de double
attribution entre lots identiques. Les interactions finales sont à confirmer dans le jeu réel.

## Filons et repérage du backpack (shared.33 et shared.34)

`StyleFilons` porte le budget de contours (1 à 4 096, défaut 512 depuis la refonte des filons) et
l’intensité, rejoints depuis par l’épaisseur et le remplissage. `AffichageFilons`
les enregistre dans `config/assistantia-filons-visuel.json` par remplacement atomique quand le système
le permet. Un enregistrement refusé conserve le style courant et le brouillon ouvert. Le budget
ne modifie pas `FilonMemoire` : tri par distance et filtrage précèdent la limite de rendu.

`Filons` utilise un buffer dédié, des lignes fines et deux passes : discrète sans profondeur, puis
plus lisible avec profondeur. Le bloc visé est exclu. Le rendu ne vide pas le buffer global du jeu.
`OngletFilons` expose les boutons cycliques Surface/Total et budget de contours, puis Épaisseur,
Intensité et Remplissage. Surface ne lance que la passe avec profondeur ; Total ajoute une passe
traversante atténuée. Les anciennes
configurations sans champ `surface` démarrent en Surface. Le budget et l’intensité sont conservés
lors du changement de mode. Les anciens écrans d’affichage et d’entretien ont été retirés,
ainsi que les boutons globaux de masquage, de vidage et d’arrêt de scan.

`BarreDefilement` centralise le calcul des pixels, le curseur, la molette et le glisser. Les réglages
conservent leurs boutons fixes et limitent le rendu et les zones cliquables à la fenêtre visible.
`EcranSac` utilise la même barre et une vue synchronisée des sacs portés. `ListeSac` regroupe les
quantités par objet et NBT, sans modifier les piles sources. Les quantités non synchronisées sont
masquées. La recherche et le regroupement sont mis en cache entre deux rafraîchissements.
`BarreOutilsEmi` utilise les largeurs mesurées avec la police du jeu. Les boutons ne sont ni tassés
ni empilés sur les contrôles voisins. Si la rangée complète manque de place, un accès **Aide…**
ouvre le menu des trois actions. `BoutonTheme`, qui remplace `BoutonOutilEmi`, conserve le clic, le
focus clavier, les infobulles et la narration natifs ; le scan actif s’affiche en doré (`choisi`),
sans soulignement d’état. Toutes les zones visibles, y compris le bouton compact, sont exclues
d’EMI et interceptent le clic avant sa liste d’objets.

`test_mining_ui.py` définit les scénarios des limites, modes Surface/Total, budgets cycliques,
défilement, repli des boutons EMI, grosses piles, identités NBT, filtre, retour au parent,
rechargement des réglages et échec d’enregistrement. Les scénarios de `FilonMemoireTest` vérifient aussi que le
budget d’affichage conserve tous les blocs mémorisés. Ces tests sans GPU ne remplacent pas une
vérification visuelle après redémarrage de Minecraft.

## Acquis après pose ou rangement (shared.34)

`LotFabrication.obtenus` expose seulement les quantités effectivement reçues. `ReceptionLots.credits`
déduit les stocks physiques et les crédits finaux déjà attribués avant de proposer un complément
virtuel. `MachinesEmi` utilise ce complément dans sa planification et l’expose au scan par le pont
optionnel `Emi.creditsFabrication`. Les réservations des résultats encore attendus restent séparées :
un acquis ne doit pas être recherché une deuxième fois comme sortie de machine.

`InventaireArbreEmi` complète également les calculs natifs et les favoris avec ces acquis, les crédits
finaux et les réservations. Les vraies grilles sont dédupliquées avant calcul, le cache inclut ces
quantités et les plis sont actualisés sur leur évolution. Aucun crédit n’est ajouté aux sources
physiques des remplisseurs ou aux inventaires du jeu. L’historique des lots conserve sa durée de vie
existante ; il n’est pas exporté comme nouvelle preuve de fabrication.

Les scénarios de `ReceptionLotsTest` et `InventaireArbreEmiTest` couvrent réception partielle,
déplacement hors inventaire, retour dans les poches, chevauchement des crédits finaux et acquis,
annulation du surplus et isolation des sources de transfert. La validation shared.34 passe les **280 tests** de la suite complète, le formatage Python et Java,
le contrôle Markdown et `checkJavaFormat build`. Les tests sans GPU ne remplacent pas
la vérification visuelle dans Minecraft après redémarrage.

## Collecte longue et catalyseurs (shared.37)

Le scan choisi reste lié à la connexion, au monde et à l’arbre actifs. `RechercheEmi` renouvelle son
bail de dix minutes avant expiration et reprend une réponse manquante après 8, 16 puis 30 secondes,
sans répéter de transfert. Un arrêt explicite ou un changement de contexte ne relance rien.
Le contrôleur utilise une horloge remplaçable dans les tests pour rejouer les délais immédiatement.

`World.merged` arbitre d’abord toutes les observations d’une position selon leur date, avant de
classer coffres et machines. À égalité, le serveur gagne. Une disparition dans sa zone est mémorisée
avec une date et un stock vide si le chunk a été transmis en entier. Une ouverture ultérieure
peut remplacer cette absence. La fusion se fait en mémoire.

Le mixin optionnel `CatalyseursArbreEmiMixin` complète `MaterialNode.defineRecipe` après sa construction
native. Il ajoute uniquement les catalyseurs physiques séparés des recettes GT, marque leur usage
réutilisable et conserve la résolution native des sous-recettes. Il ignore les circuits virtuels et
évite les doublons avec une entrée déjà non consommée. Il ne modifie pas les objets recette.

`MachinesEmi` conserve au plus 64 relevés d’entrées de machines ouvertes. Seuls les outils réutilisables
compatibles avec les opérations de l’arbre servent de disponibilité temporaire à EMI et au scan.
Ils n’attribuent aucun résultat, ne deviennent pas un crédit acquis et ne sont pas ajoutés aux sources
de clic. La lecture réelle de la machine ouverte reste obligatoire avant envoi. Un relevé est mis à
jour à la réouverture, supprimé pour une machine cassée/remplacée dans un chunk chargé et vidé lors
d’un changement de connexion/dimension. Le client ne garantit pas le contenu d’une machine fermée
modifiée à distance par un autre joueur.

## Relevés et planification partagés

Le script KubeJS serveur annonce les chunks entièrement transmis dans `chunks_complets`.
Une erreur de lecture, une limite d’inventaire ou un retrait de blocs pour tenir dans le message
invalide la couverture du chunk. Les relevés v1 fournissent des observations positives, les
versions suivantes permettent aussi de confirmer une omission dans un chunk couvert.

Sur le serveur, copier [le script v4](../serveur/assistant_ia_serveur.js) dans
`kubejs/server_scripts` en remplaçant la copie existante, puis redémarrer le serveur.
Le client existant recompose les fragments du paquet.

Le script inspecte les chunks déjà chargés. Il répartit les relevés des joueurs sur plusieurs
ticks, à raison d’au plus un joueur par tick et un relevé par joueur au maximum tous les 60
ticks. Les signaux de présence prolongent l’inscription sans avancer le prochain relevé.

Avant toute lecture de stock ou d’état d’une machine, le script applique le droit d’interaction
FTB Chunks du joueur destinataire. Les deux moitiés des coffres doubles vanilla et Sophisticated
Storage doivent être chargées et accessibles. Quand la zone non revendiquée est protégée, elle
est ignorée pour éviter les messages d’interaction du serveur. Un contrôle indisponible empêche
la lecture.

Le scan automatique ignore aussi les coffres vanilla verrouillés, les blocs dont le butin vanilla
reste à générer, Lootr, les coffres Ender et les contrôleurs ou connecteurs qui agrègent le
stockage distant de Sophisticated Storage et Functional Storage. Un relevé complet retire du
stock serveur mémorisé les coffres filtrés dans les chunks qu’il couvre.

Python valide la structure du relevé avant de fusionner les observations. Il utilise la date
d’écriture locale du fichier reçu pour comparer les relevés du serveur et les ouvertures de
coffre. Un fichier périmé ou d’une autre dimension est ignoré. Pendant un flux régulier,
une trame hors ordre dans la même dimension ne remplace pas le dernier relevé.

Le direct expire après 15 secondes et s’arrête lors d’une perte de connexion ou d’un changement
de dimension détecté par l’assistant. Seules les machines présentes dans la dernière trame
valide ont un état en direct. Les machines déjà vues, leurs dates et les coffres restent en
mémoire. Les dernières positions des autres joueurs restent disponibles avec la date du
relevé si la liaison s’interrompt. La Base, la fiche de bloc et le contexte partagé par Claude
et Codex distinguent les états en direct et les observations historiques. Le stockage
historique reste consultable en client seul.

`StorageMonitor` réutilise la vue fusionnée tant que ses relevés source sont stables. Un changement
d’inode, de date ou de taille invalide la lecture locale. Une écriture partielle laisse la dernière
vue exploitable. L’export texte dépend de la liste des coffres, du contenu et de la minute affichée.
La recherche EMI réutilise ses correspondances tant que le stock et les besoins restent stables.
Le déplacement du joueur change seulement leur ordre.

`InstantaneArbreEmi` lit l’arbre natif une fois par révision, puis fournit les deux représentations
utilisées par le scan des coffres et le plan machine. Le mixin signale la reconstruction d’un nœud.
Le changement de racine ou de quantité de lots invalide aussi la vue. `SuiviLotsEmi` possède les
réservations et réceptions. `PanneauMachineEmi` reçoit le résultat à afficher.

L’aperçu d’envoi lit les entrées et cuves une fois. Les recettes d’objets bornent la taille du lot
avec les quantités disponibles avant de simuler. Chaque essai utilise des réserves simulées.
Au clic, le circuit, la machine et les quantités sont vérifiés sur une nouvelle lecture, puis les
clics natifs sont construits pour la quantité annoncée.

## Sélection locale du contexte de Discussion

`game/context.py` sélectionne les objets par identifiant exact ou nom reconnu sans ambiguïté.
Une question de recette garde les ingrédients des variantes connues sur deux niveaux et leurs
machines. Une recherche dans les coffres garde les quantités, dimensions, positions et dates
utiles. Un réglage simple reçoit un contexte plus court. Les questions ambiguës, les diagnostics
machine et la planification conservent le contexte étendu. Ajouter « contexte complet » à la
question force ce dernier pour ce tour. Les positions multijoueurs restent dans
le prompt. La sélection utilise le catalogue local et la question suit le même appel unique.

`python -m tests.benchmarks.question_context` mesure sept prompts synthétiques sans IA. Pour
comparer, lancer le même fichier depuis un dossier temporaire avec `PYTHONPATH` sur une copie
de `2ca966c`. Les mesures suivantes utilisent 50 répétitions sur macOS ARM64. L'estimation
des tokens est la longueur du prompt divisée par quatre, hors système et historique.

| Question                              | Caractères avant → après | Tokens estimés | Préparation médiane en ms |
| ------------------------------------- | ------------------------ | -------------- | ------------------------- |
| Fabriquer Bronze Plate                | 20 539 → 6 195           | 5 135 → 1 549  | 0,225 → 0,054             |
| Trouver Bronze Plate dans les coffres | 20 554 → 5 812           | 5 138 → 1 453  | 0,232 → 0,056             |
| Désactiver la voix                    | 20 199 → 5 096           | 5 050 → 1 274  | 0,223 → 0,028             |

Les quatre autres prompts (machine, projet, demande ambiguë, contexte complet) ont la même
empreinte SHA-256 avant et après. Les tests vérifient la recette exacte, les stocks rares,
les machines compatibles, les dates et les positions des deux joueurs. Les temps de réponse
et la qualité des modèles en partie réelle ne sont pas mesurés ici.

## Écritures de la mémoire serveur et recherche EMI

La mémoire serveur est écrite en JSON compact avec le même schéma. Chaque observation et sa date
restent enregistrées immédiatement avec remplacement atomique et copie de récupération. La fusion
choisit toujours la source la plus récente avant de préparer la recherche. Le cache EMI compare
ensuite les positions et les inventaires sous une forme immuable. Les positions en conflit et les
stocks invalides repassent par le calcul normal.

`python -m tests.benchmarks.storage_snapshots` simule 200 relevés identiques sur dix minutes,
avec 600 blocs dont 100 machines et une recherche active. Pour comparer à `03bb988`, exécuter le
même fichier dans un dossier temporaire avec `PYTHONPATH` sur la référence. Résultats sur
macOS ARM64, trois répétitions avec chaque version :

| Mesure                                         | Avant          | Après          |
| ---------------------------------------------- | -------------- | -------------- |
| Remplacements atomiques, sauvegardes comprises | 399            | 399            |
| Volume écrit                                   | 227,69 Mo      | 142,54 Mo      |
| Reconstructions de la recherche EMI            | 200            | 1              |
| Temps CPU médian pour les 200 cycles           | 6,57 s         | 3,42 s         |
| P95 médian par cycle                           | 44,62 ms       | 21,26 ms       |
| Lecture au redémarrage                         | 2,38 à 3,55 ms | 2,22 à 2,42 ms |

Les 600 observations et leurs dates sont relues à l'identique. Le test de refus de remplacement
vérifie la reprise sans état partiel, puis la restauration après fichier principal incomplet.
Les tests de stock couvrent aussi le changement de source, le déplacement d'objets, l'ajout
et le retrait de coffres, et les relevés incomplets. Les temps sont ceux du scénario local.

## Onglet Recette

`compat/emi/AnalyseRecette` lit l’arbre consulté dans Recipe Tree pendant le tick client, par tâches
bornées à environ 1 ms et 96 développements par tick : lecture de l’arbre et ordre du plan à chaque
changement, puis alternatives recette par recette, jamais sur un tick de relecture ou de plan. Il
publie une `VueRecette` immuable ; l’onglet, le panneau du HUD (`Hud.source`) et les repères ne font
que la dessiner. Un plan qui coûte plus de 1,5 ms attend 40 ticks, plus de 5 ms 100 ticks. Un plan
impossible à calculer garde un état distinct (« Plan non calculé » et sa raison). Les accès
réflexifs aux recettes GregTech sont mis en cache par classe (`GregTechRecette`).

La clé de l’arbre suit l’arbre réel (objet, recette et enfants du but, lots, résolutions, gestionnaire
de recettes). Le mixin de `MaterialNode.defineRecipe` passe par `ArbreReel` : seul le but de
`BoM.tree` déclenche `arbreModifie` et `CatalogueProduction.rechargement`, une fois par recalcul
réel. Les arbres temporaires qu’EMI construit pour l’infobulle de coût (`RecipeCostTooltipComponent`,
à chaque image) n’invalident plus ni la Recette, ni le Suivi, ni les captures Production.

`ArbreRecette` lit l’arbre EMI par réflexion. **Appliquer** écrit d’abord la recette par défaut
d’EMI de l’ingrédient (`BoM.addedRecipes` puis `EmiPersistentData.save`, comme le bouton doré
« Définir par défaut », sans recalcul), puis la résolution locale (`addResolution`), qui recalcule
l’arbre ; jamais les favoris. Si la recette par défaut ne peut pas être écrite, le choix reste local à
l’arbre et le message le dit. **Appliquer** revérifie l’arbre, sa révision, le choix actuel et la
recette avant d’écrire, et refuse pendant un transfert. **Annuler** rétablit la recette par défaut
puis la résolution, garde huit choix et se vide quand l’arbre change d’objet. Le nœud racine est
analysé comme les autres ; pour lui, `resoudreRacine` remet la quantité par lot et les lots d’avant,
car le chemin natif remet `goal.amount` à 1, et `restaurerRacine` rétablit exactement l’état
précédent. **Nouvelle recette** ouvre l’inventaire puis `EmiApi.displayRecipe`.

`ChaineRecette` évalue le coût d’une sous-chaîne par unité produite en suivant les choix de l’arbre,
puis les défauts EMI. Un ingrédient sans choix qui n’est ni une matière de base de l’arbre, ni un
fluide, ni déjà possédé se fabrique par `Source.producteur` (au plus 48 par évaluation, mémorisé par
matière) : la recette sûre la moins coûteuse au premier niveau qui consomme quelque chose et ne défait
pas un objet fait avec la matière. Une chaîne où le produit réapparaît parmi ses ingrédients est
`circulaire` et n’est jamais meilleure. `ComparaisonAlternatives` applique les règles « strictement
meilleure » et rédige le gain. Les candidats sont les producteurs à sortie garantie, 32 au plus et les
moins coûteux au premier niveau d’abord, hors recyclage, recettes désactivées, recettes qui consomment
leur produit et décompositions : une recette dont un objet consommé n’a que des recettes sûres qui
utilisent le produit (extraire une batterie pour sa coque).

`RangsRecette.stabiliser` garde l’étape en cours en tête d’un calcul du plan à l’autre (clé recette
ou lot) : prête ou à préparer jusqu’à sa fabrication ou son envoi, lot à récupérer jusqu’à la
dernière sortie. `MachinesEmi` calcule aussi les opérations encore utiles avec les coffres connus
(`stockCoffres`) : la surbrillance, les repères et le plan de l’onglet Recette les suivent, le panneau
d’une machine garde toutes les opérations. Les repères sont dessinés 1,3 bloc au-dessus de la
machine par le tampon principal du jeu. `PossessionsRecette` relève les postes posés (rayon de 48 blocs, une rangée de chunks par
tick, relevé serveur de la Base et blocs déjà ouverts), portés ou rangés (inventaire, sac, coffres
connus lus hors du rendu par `CoffresConnus`) et les sorties attendues des lots. Les outils
réutilisables nouveaux (moules, lentilles, formes) doivent aussi être possédés. `DecisionTension`
applique la décision du joueur : une machine du bon type mais trop faible donne « Tension
insuffisante (… possédé) », le poste affiché reste la machine possédée et aucune machine au-delà de
la plus forte tension possédée n’est nommée ni proposée. `FabricationFacile` limite « facile » à une
recette d’établi couverte par le stock, avec au plus un niveau de sous-craft ; ses caches sont vidés
quand le gestionnaire de recettes EMI change.
`MatieresArbreEmi` fournit les matières premières de l’arbre au **Tri : recette** des filons.

`test_recipe_tab.py` (écran à 320 × 240 contre des stubs) et `RecetteMoteurTest` (chaînes,
comparaison, rangs, repères, « facile ») couvrent le moteur pur ; l’écriture réelle dans l’arbre EMI
reste à vérifier en jeu. L’ordre du plan et les rangs des machines sont décrits dans
[l’ordonnancement](ordonnancement-machines.md#onglet-recette-et-repères-dans-le-monde).

## Filons : scan complet, compte en direct et sauvegarde v2

`GregTechFilons` lit les paramètres natifs des générateurs GTCEu ; `VolumeFilon` en déduit la boîte
où chacun peut placer ses minerais (couches, veine, dyke, cuboïde, classique, standard, géode), ou
un cube avec une marge pour un générateur inconnu. `ScanFilon` parcourt ce volume colonne de chunk
par colonne, saute les sections sans minerai de la composition et garde en attente les colonnes non
chargées, sauvegardées avec le filon. Dans la marge, seuls les minerais reliés au filon par des trous
de 2 blocs au plus sont retenus ; un minerai déjà rattaché à un autre gisement ne lui est jamais pris.
`VerificationFilons` relit à tour de rôle les minerais connus des chunks chargés (192 par tick) et les
64 plus proches du filon surligné à chaque tick ; un minerai disparu devient un compteur « miné ».
`FilonMemoire` garde les minerais présents dans des tableaux primitifs indexés par `TableLong`, au
plus 400 000 par monde. Le travail d’un tick reste sous 1 ms.

La mémoire d’un monde est enregistrée dans `kubejs/assistant/filons_gregtech/<monde>.json` au format
v2 : une table des types de blocs, puis par gisement ses métadonnées, son volume, l’avancement du scan
et ses colonnes en attente, ses compteurs de minerais minés et ses minerais présents en entiers
relatifs à l’ancre (`[dx, dy, dz, type, …]`). Le format v1, un objet par bloc, reste lisible ; avant
la première écriture v2, l’ancien fichier est copié une seule fois en `.v1.bak`. Chaque écriture
garde la dernière version saine en `.bak` ; un fichier illisible est copié à part avant la reprise de
cette copie. Le thread client ne copie que les tableaux (`InstantaneFilons`) ; `FormatFilons` produit
le texte en flux sur l’exécuteur d’écriture de `SauvegardeFilons`, au plus toutes les 10 s hors action
explicite. Sur une vraie mémoire, 20,8 Mo deviennent 519 Ko et le jeu n’y consacre plus que 0,14 ms
au lieu de 0,5 s.

Le résumé `filons_mod.json` donne à Python les gisements et leurs comptes par type, sans aucune
position ; `game/veins.py` ne le relit que si sa date ou sa taille change. Avec le mod, le script
KubeJS n’écrit plus `filons.json`, gardé comme secours sans le mod. `AffichageFilons` enregistre vue,
budget, épaisseur, intensité et remplissage (version 2) ; un réglage absent ou illisible reprend sa
valeur par défaut sans effacer les autres. `CouleursFilons` donne à chaque matériau une couleur tirée
de GregTech, contrastée selon le fond de la dimension, et le rendu coupe le brouillard le temps des
contours. `FilonMemoireTest`, `SauvegardeFilonsTest`, `test_filons.py` et `test_vein_tab.py` (onglet
et écran des blocs à 320 × 240 contre des stubs) couvrent scan, compte, format et affichage, sans GPU.

## Écritures au changement et fil d’écriture

`Ecritures` remplace atomiquement les fichiers d’échange sur un fil dédié : le thread client ne dort
jamais et n’attend pas le disque. Pour un même fichier, seul le dernier contenu compte et un contenu
plus ancien ne passe jamais après lui. Quand Windows refuse le remplacement parce que l’assistant lit
encore le fichier, le fil réessaie tant que la demande reste valable (45 s). Les demandes du menu
tentent une écriture immédiate puis passent par ce fil. `inventaire_joueur.json`, l’identité du monde
et les raccourcis Mac (signal toutes les 500 ms) l’utilisent aussi.

`EchangesKubeJS` est le point d’entrée du script client : `etat.json` et `quetes.json` passent par le
fil d’écriture, le relevé du serveur (`monde.json`) y est assemblé et vérifié, et la progression des
quêtes FTB se compare par empreinte. Le script réécrit `etat.json` seulement s’il change, avec une
confirmation toutes les 2 s, et `quetes.json` quand une quête change (vérifié toutes les 10 s), au
plus toutes les 30 s pour une simple progression de tâche ; un relevé abandonné (`perdu`) est
republié au contrôle suivant. Les nombres entiers sont réécrits en entiers (le `JSON.stringify` de
Rhino écrit des doubles). La mémoire des coffres (`stockage.json`) est écrite au premier changement
d’un écran ouvert, puis au plus toutes les 2 s, à la fermeture et quand le joueur disparaît (expulsion,
redémarrage du serveur).

Les mémoires KubeJS (`MemoireMondeKubeJS` : `stockage.json`, et `filons.json` sans le suivi natif)
passent aussi par ce fil : le script n’attend jamais le disque et relit depuis la mémoire une écriture
encore en file. Ce sont des données du joueur, jamais abandonnées : une erreur disque est retentée au
plus une fois par seconde jusqu’à réussite, avec fsync seulement pour une partition isolée ; une
partition refusée par ses contrôles est abandonnée aussitôt. Chaque erreur est rendue une seule fois
au script, sans bloquer le changement de monde. Toute erreur, `Error` comprise, libère ou replanifie
la tâche. Les temporaires s’appellent `.ecriture-<nom>-<n>.tmp`, hors des motifs de la migration et
de la sauvegarde. `IdentiteMonde` ne garde qu’une référence faible au serveur solo.

Sans le mod, le script écrit directement comme avant. Il ne dessine plus de panneau et l’assistant
n’écrit plus `plan.json` ; `fabrication_emi.json` a aussi disparu. `VERSION_SCRIPT` vaut 13 :
l’assistant demande F3 + T si le jeu exécute un script plus ancien.

`RelachementSouris` empêche qu’un clic sur la barre EMI ou un panneau de l’assistant (bouton grisé
compris), ou un retour vers le coffre ou la machine, laisse le relâchement atteindre le conteneur :
`AbstractContainerScreen.mouseReleased` jetterait l’objet tenu au curseur. Il suit un appui à la fois.

Côté assistant, `interface.unsupported_request` refuse explicitement les demandes d’un ancien mod :
boutons retirés (« Fonction retirée : relance Minecraft… ») et demandes inconnues. Pour une version,
le contexte Production publie encore `projet` et `revision_projet` constants (à retirer ensuite).
`vue_mod.json` ignore `rendu_ms` et `inventaire_t` pour décider d’une réécriture : il change sur un
vrai changement ou au battement « vie » de 5 s.

`EcrituresTest`, `test_change_only_exports.py` et `test_kubejs_exports.py` rejouent l’écrivain,
l’export d’inventaire et le script client tick par tick sous Node.js, avec ou sans le mod.
