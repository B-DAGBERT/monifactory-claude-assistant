# Développement

## Repères

- `monifactory_assistant/app.py` coordonne le jeu, les objectifs, la voix et l’interface.
- `providers/` isole les deux moteurs : le choix de l’un ne charge pas l’autre.
- `commands/` contient les commandes exécutées par les lanceurs Mac et Windows.
- `paths.py` définit la racine des données et celle du code. Ne pas déduire le dossier des sauvegardes depuis l’emplacement d’un module.
- `ui/discussion.py` sert uniquement le suivi en lecture seule ; ses fichiers sont dans `ui/web/`.
- `game/` contient les projets et les imports de filons. Le journal GregTech du client reste la source des filons.
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
La CI exécute ces vérifications sur `main` et les pull requests qui la ciblent.

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
- `ConfigurationCircuit` lit les ingrédients natifs GregTech, y compris un `SizedIngredient`. `ComparaisonEmi` limite l’exception NBT au Programmed Circuit.
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
