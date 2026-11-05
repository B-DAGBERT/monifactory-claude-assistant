# Développement

## Repères

- `monifactory_assistant/app.py` assemble les composants, branche les raccourcis et supervise le processus.
- `application/projects.py` gère les projets, leur pause, la progression et la préparation des objectifs.
- `application/game.py` suit les relevés du monde, les quêtes, le journal et les actions de jeu.
- `application/conversation.py` traite les questions, les fiches et les événements vocaux.
- `application/interface.py` gère les fenêtres locales, les réglages et les demandes du menu Minecraft.
- `game/bridge.py`, `recipes.py`, `world.py`, `context.py` et `objectives.py` isolent les échanges et calculs du jeu.
- `ui/views.py` construit les données du HUD et des menus ; `audio.py` contient la chaîne micro, transcription et voix.
- `platform/capture.py` et `hooks.py` isolent la capture, le focus et le choix des raccourcis natifs.
- `settings.py` conserve les constantes et chemins historiques ; `prompts.py` contient les instructions IA.
- `providers/` isole les deux moteurs : le choix de l’un ne charge pas l’autre.
- `commands/` contient les commandes exécutées par les lanceurs Mac et Windows.
- `paths.py` définit la racine des données et celle du code. Ne pas déduire le dossier des sauvegardes depuis l’emplacement d’un module.
- `ui/discussion.py` sert uniquement le suivi en lecture seule ; ses fichiers sont dans `ui/web/`.
- `game/` contient les projets, la mémoire des stockages, les relevés d’inventaire, les échanges EMI et les imports de filons. Le journal GregTech du client reste la source des filons.
- `mod/src/main/java/fr/assistantia/` contient les écrans et fonctions Forge.
- `jeu/` et `serveur/` conservent les scripts KubeJS d’origine, vérifiés par les tests de parité.

## Compatibilité

Les lanceurs et les points d’entrée `assistant_mc.py`, `codex_setup.py`, `choose_ai.py` et
`update_assistant.py` restent à la racine. Ils délèguent au paquet Python et permettent aux
installations existantes de continuer à se lancer et à se mettre à jour.

Les configurations, sessions, plans, coffres et journaux personnels conservent leur emplacement
historique à la racine, en dehors du code et ignorés par Git. Les données ne sont pas migrées
pendant un simple rangement des sources. Les objectifs en pause restent en pause.

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
Le Java se vérifie avec `bash mod/gradlew -p mod checkJavaFormat`. Les scripts KubeJS restent
identiques à la référence historique et sont exclus du formatage automatique.
Les commandes Gradle directes nécessitent `JAVA_HOME` pointant vers un **JDK 17** ; elles ne
lisent pas `assistant.local.json`. `python codex_setup.py build` résout, lui, le réglage `java_home`
et les emplacements détectés. Vérifier `java -version` si Gradle refuse de démarrer.
Les workflows GitHub Actions ont été supprimés pour préserver le quota. Ces vérifications
se lancent localement avant de publier sur `main` ; les tests et les outils de compilation
restent disponibles. La mise à jour de l’assistant depuis `main` fonctionne indépendamment
de GitHub Actions.

Les scénarios de mise à jour utilisent de vrais dépôts Git temporaires. Les tests des moteurs
utilisent des CLI simulés : aucun compte IA ni appel facturé n’est nécessaire.

## Livraison

Travailler sur une branche de fonctionnalité issue de `main`, tester, puis fusionner vers `main`.
La commande **Mettre à jour** récupère uniquement `origin/main` et refuse d’écraser des modifications
locales ou des commits non publiés. Ne pas utiliser de reset forcé pour contourner ces protections.

## Collecte et machines EMI

- `RechercheEmi` possède la sélection globale du scan et ses quantités. `AideCoffre` et `RecuperationEmi` passent par cette sélection ; aucun retrait ne doit reconstruire un arbre depuis le seul coffre ouvert.
- `MemoireCollecte` conserve les ingrédients acquis ; `PlanArbre` réserve les composants avant leurs enfants. `RetraitCoffre.planRecherche` applique les quotas aux cases réelles, sans déduire deux fois l’inventaire.
- `SuiviFabrication` conserve les acquisitions finales et déduit les retours d’objets déjà sortis. `OrigineFabrication` rapproche les gains réels des événements de craft et des sorties natives ; ne jamais compter l’aperçu de résultat. `CycleArbreEmi` exporte les totaux absolus dans `fabrication_emi.json`, rattachés au projet et à la version du plan. Python les applique par maximum, sans rejouer une hausse à chaque lecture.
- `LotFabrication` distingue réservation et récupération réelle. `ReceptionSortie` rapproche les notifications séparées des cuves et des contenants. `MachinesEmi` garde seulement les sorties encore attendues dans son stock réservé. `SuiviEmiEcran` regroupe les états, le recalcul et l’annulation ; annuler ne déplace aucun objet.
- Les allocations de `MachinesEmi` sont invalidées par une modification de l’arbre, des stocks ou des lots. Le cache des compatibilités machine est également vidé lors d’un changement de contexte, toutes les 100 ticks et sur recalcul explicite.
- `ConfigurationCircuit` lit les ingrédients natifs GregTech, y compris un `SizedIngredient`. Le réglage est contrôlé via `getCircuitInventory`, sans setter ni repli vers un circuit physique. `ComparaisonEmi.circuitVirtuel` ne retire que les catalyseurs circuits des recettes GT, jamais une cible racine ni un circuit consommé. Pour le stock natif EMI, les outils `IGTTool` conservent la comparaison par défaut ; leur matériau et leur type sont portés par l’Item. Les comparaisons explicites EMI restent inchangées.
- `SequenceEnvoi` attend une confirmation stable avant l’action suivante. `EnvoiMachine` valide l’interface, les sources, les entrées et les cuves ; il n’effectue aucun nouvel essai automatique après un refus.
- `game/emi.py` exporte l’avancement conservé uniquement lors d’un import explicite. Ne pas reprendre un objectif en pause.

Les tests `test_emi.py`, `test_chest_transfers.py` et `test_machine_inputs.py` rejouent les allocations et les interactions client dans des conteneurs déterministes. Ils couvrent notamment les composants répartis entre coffres, les transformations partielles et en chaîne, les retours du même objet, les lots finaux rangés séparément, les outils partagés, les réceptions et annulations partielles, les circuits à NBT factice, les cases verrouillées, les doubles clics et les fluides. Ils ne remplacent pas une validation visuelle dans Minecraft ni les réponses d’un serveur réel.

## Inventaire personnel et backpacks

`InventaireJoueur` est la source commune du scan, du suivi final et du plan machine. `compat/backpacks/SacsPortes` utilise les API natives de Sophisticated Backpacks de manière optionnelle : inventaires enregistrés (dont Curios), lecture du menu ouvert et requête de contenu d’un UUID porté. Le mixin client `ContenuSacMixin` observe la réponse native, y compris pour un sac vide ; aucune nouvelle extension serveur n’est requise.

Une seule requête par sac reste en vol. Le relevé est lié à la révision des quantités personnelles ; une réponse précédant un déplacement ne peut pas valider le nouvel inventaire. Le cache est effacé à la déconnexion et limité aux sacs encore portés. `InventaireArbreEmi` raccorde cette vue aux deux calculs natifs d’EMI (Recipe Tree et favoris de fabrication) via des mixins optionnels. Il reconstruit le stock personnel avec les vraies grilles de craft, garde les fluides natifs et ne modifie jamais les inventaires des remplisseurs. Le relevé doit appartenir à la connexion actuelle. La liste est rafraîchie quand le stock change, sans réinitialiser la disposition des panneaux.

Le fichier `inventaire_joueur.json` exporte une vue complète ; `game/inventory.py` la substitue au relevé KubeJS seulement si elle est synchronisée, récente et correspond au joueur et à la dimension. Ne jamais ajouter ces quantités à un autre relevé : cela compterait deux fois les poches.

`test_backpack_inventory.py` rejoue la lecture native, Curios, les grosses piles, les sacs imbriqués, les déplacements, les réponses retardées, le retrait d’un sac et le remplacement des relevés Python. Les transferts restent limités aux cases du menu natif ; les tests ne prétendent pas simuler un serveur Minecraft complet.

## Édition d’un arbre et lots manuels

`CoffresArbreEmi` compare une vue des besoins et variantes toutes les dix ticks quand le scan est activé, hors transfert. Une modification remplace les quotas ; les anciennes réponses sont rejetées par leur numéro. `MemoireCollecte.reprendre` garde les acquisitions pertinentes et `reserver` ajoute un stock de planification réversible. Les matières historiquement envoyées sont retirées de cette seule vue pour éviter de créditer à la fois un résultat attendu et ses ingrédients.

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
`model_plan`, `effort_plan`. L’accusé dans `ecran.configuration_ia.reponse` doit correspondre au
numéro envoyé ; écrire le fichier n’est pas une confirmation de sauvegarde.

La sauvegarde est locale et atomique, sans essai IA. Le service refuse une demande périmée, un
formulaire obsolète, un changement de moteur ou un changement pendant un travail. Les fichiers
modifiés par un autre outil ne sont pas écrasés. Le chargement ne publie que les noms de modèles,
les efforts et les états du formulaire ; aucune autre clé de configuration n’est exportée au jeu.

Les appels de plan et de fiche relisent leurs réglages à chaque nouvelle demande. La conversation
Codex conserve son thread et passe explicitement le modèle et l’effort à chaque `turn/start`,
conformément à la [documentation officielle OpenAI](https://learn.chatgpt.com/docs/app-server).
Claude conserve son identifiant de session et renouvelle son processus au prochain appel lorsque
ses paramètres ont changé. Les objectifs, leur pause et les crédits de fabrication ne sont pas
concernés par ce canal.

## Projets et catalogue des modèles (shared.30)

`ProjectStore.delete` exige l’identifiant et la confirmation du nom affiché. Les écritures restent
atomiques ; un échec restaure le catalogue et l’objectif actifs. La suppression d’un projet secondaire
ne réinitialise ni les compteurs ni la pause du projet ouvert. Le dernier projet supprimé est remplacé
par un brouillon dont l’identifiant est neuf, pour qu’une ancienne requête ne puisse pas le supprimer.
`EcranProjets` conserve la demande en attente même après fermeture ; les actions sont désactivées
jusqu’à l’accusé exact. Renommer et supprimer restent dans la liste, ouvrir et créer reviennent au
panneau parent. Les champs `plan` et `model_plan` restent compatibles avec les anciennes sauvegardes ;
seuls leurs libellés visibles deviennent « Projet ».

`providers/catalog.py` lit uniquement les métadonnées publiques du cache local Codex : identifiant,
nom affiché et efforts supportés. Il conserve l’ordre, exclut les modèles masqués et ne transmet ni
identité du compte ni instructions internes. Les lectures sont limitées par délai et signature du
fichier ; une écriture partielle déclenche un repli vers la configuration connue. Les alias Claude
proviennent de la [configuration officielle Claude Code](https://code.claude.com/docs/en/model-config).
La liste ne vérifie pas les droits d’accès et ne lance aucune inférence. `CatalogueModeles` et
`EcranChoixModele` présentent ce catalogue et ajustent uniquement le brouillon sur choix explicite.

Les tests couvrent suppression active/secondaire/dernière, conservation de la pause, reprise après
échec disque, requêtes répétées, cache absent/corrompu/remplacé, données privées exclues, ainsi que les
écrans réels via contrats Minecraft : sélection, confirmation, Échap, attente conservée, recherche,
redimensionnement et limites en 320 × 240. Les contrôles en jeu restent à effectuer après redémarrage.

## Découpage de l’application

`App` compose quatre contrôleurs par héritage explicite. Ils partagent le même état et la même
boucle d’événements Tk : les déplacements n’ajoutent ni service, ni copie de l’inventaire, ni
seconde source de progression. Chaque méthode reste définie dans un seul contrôleur. Les
contrôleurs s’appellent par `self`, et ne s’importent pas entre eux. Aucun module de domaine ne
réimporte `app.py`.

Les calculs des besoins et des étapes sont dans `game/objectives.py` ; les vues JSON restent dans
`ui/views.py`. Les réponses structurées et la session commune aux deux moteurs sont isolées dans
`providers/responses.py` et `providers/session.py`. La reconnaissance vocale conserve ses imports
lourds différés. Les modules de jeu peuvent être importés sans Tk, capture, micro ou moteur IA.
Les constantes de données utilisent toujours `paths.ROOT`, même après déplacement d’un module.

Pour modifier une fonctionnalité, commencer par son contrôleur puis son calcul de domaine ;
garder dans `app.py` uniquement l’initialisation, le branchement et le lancement. Les anciens
noms de commandes, formats JSON, chemins, modèles et réglages sont conservés.

Les tests ciblés qui injectent un environnement sans interface lisent les définitions de production
avec `tests/app_source.py`. Ce lecteur rassemble les méthodes réellement héritées par `App` ; il
ne contient aucune copie de leur logique. `test_application_modules.py` vérifie aussi les imports
sans dépendances de bureau, l’absence de méthodes masquées entre contrôleurs et le chargement réel
d’un projet en pause après changement de projet et redémarrage. La vérification de parité historique
continue à comparer le comportement conservé malgré le déplacement des définitions.

## File durable, sauvegardes et rendu (shared.31)

`game/requests.py` lit au plus huit demandes par passage dans `kubejs/assistant/demandes/`.
`FileDemandes` publie un fichier atomique par UUID et numéro chronologique ; un renvoi conserve
ces deux valeurs. La file du client est limitée à 128 fichiers. Le protocole 2 est annoncé dans
`ecran.demandes` ; les anciens couples client/assistant utilisent encore `demande_mod.json`.
Les réglages IA et le scan EMI gardent leurs canaux indépendants.

Avant chaque exécution, une réservation est écrite dans `reponses_demandes.json`. La confirmation
et les accusés de question/projet sont enregistrés avant suppression du fichier de demande.
Un résultat durable est restitué lors d’un renvoi, sans refaire l’action. Une réservation laissée
par un arrêt devient « interrompue » ; le résultat peut avoir été appliqué et ne doit jamais être
rejoué automatiquement. Un historique illisible bloque la file plutôt que d’effacer la preuve.
Les commandes périmées et les contextes projet/dimension différents sont refusés. Les accusés anciens ne remplacent pas ceux d’une demande plus récente. Ce protocole
privilégie l’absence de double exécution ; il ne promet pas de terminer une action après un crash.

`persistence.py` partage les écritures de sauvegarde : fichier temporaire unique dans le même
dossier, conservation des permissions, flush/fsync, remplacement atomique, nettoyage et copie
précédente `.bak` lorsqu’elle contient un JSON valide. Une copie corrompue ne remplace pas un backup
sain. Les lectures peuvent récupérer la copie ; les projets refusent toute perte silencieuse
lorsqu’aucune copie n’est lisible. Les chemins restent identiques. Le catalogue des projets et le statut de pause/reprise ne sont publiés en mémoire qu’après sauvegarde réussie. Le canal de réglages IA conserve
ses vérifications supplémentaires de révision, sauvegarde son ancienne configuration et revalide
la révision juste avant son écriture atomique dédiée.

Le rendu utilise une horloge monotone distincte de la dernière écriture du plan. `game/snapshots.py`
partage inventaire, progression, totaux des coffres et machines uniquement pendant un cycle.
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
second lot. L’arrêt de l’arbre hors pause et le changement de monde/connexion conservent les règles
de nettoyage précédentes. Les tests Java couvrent lot envoyé/complet, quantité devenue invalide,
ancien arbre, retrait sur plusieurs coffres, réception partielle, sac, annulation et absence de double
attribution entre lots identiques. Les interactions finales sont à confirmer dans le jeu réel.
