# Collecte EMI et machines

## Le parcours

1. Choisir le craft et ses branches dans **Recipe Tree**, ou importer **Objectif EMI**.
2. Activer **Scanner coffres**, à droite de Recipe Tree.
3. Ouvrir un coffre entouré en cyan : **Prendre** retire la quantité de l’icône choisie ; **Tout** retire les ingrédients du scan présents ici.
4. Aller dans une machine entourée en rouge. Choisir le produit, survoler **Envoyer** pour vérifier le lot puis cliquer.
5. Récupérer le résultat soi-même. Les cibles finales reçues restent acquises après rangement.

## Ce qui est réellement sélectionné

Le scan réserve d’abord les composants prêts, puis les matières et les outils nécessaires aux branches restantes. Il considère l’ensemble des coffres mémorisés de la dimension. Un composant dans un autre coffre évite de prélever ici de quoi le refabriquer.

Les contours, les objets surlignés et les boutons de retrait utilisent la même sélection. Exemple : sur trois boîtiers nécessaires, deux existent dans un coffre ; le drawer de fer ne propose que le fer pour le troisième. Un outil déjà possédé ne doit pas être prélevé une seconde fois. Si le composant est déjà récupéré, son outil de fabrication ne devient pas nécessaire après rangement.

Le scan est un instantané de l’arbre : après modification des recettes, utiliser **Suivi → Recalculer** ou arrêter puis relancer le scan. Ouvrir un coffre actualise son contenu mémorisé. Arrêter le scan retire les contours et le panneau **Collecte**. Un objectif seul n’autorise aucun retrait.

La mémoire des coffres distingue les identifiants des objets, pas leurs variantes NBT. Dans le coffre ouvert, le retrait vérifie aussi les variantes EMI, avec une exception pour le NBT factice du Programmed Circuit. Un coffre peut donc être signalé sans proposer de retrait si la variante est incompatible. La recherche ne suit pas les fluides. Un stock inaccessible ou périmé ne crée jamais d’objet : les retraits sont bornés par les vraies cases du coffre ouvert.

## Envoyer dans une machine

- Le panneau indique les fabrications possibles / restantes. Les quantités détaillées sont au survol ; **Suivi → Recette** ouvre la recette EMI.
- Les entrées déjà remplies sont déduites. Les outils et catalyseurs ne sont pas multipliés par le nombre de fabrications.
- Le **Programmed Circuit** est reconnu sans son NBT factice. Avec un sélecteur de circuit GregTech, le numéro est lu dans la vraie recette puis réglé sur la machine. Les autres objets gardent les règles de comparaison EMI.
- Les fluides viennent uniquement des contenants de l’inventaire. Le clic natif vide le contenant puis le rend. Un seau de 1 000 mB peut dépasser le besoin : le volume réellement envoyé est indiqué au survol.
- Les sorties ne sont jamais récupérées automatiquement. Les fabrications envoyées sont réservées pour éviter les doublons ; elles ne sont pas déclarées terminées avant récupération du résultat.

Pendant un envoi, la progression est affichée et les doubles clics sont ignorés. Fermer le menu, arrêter l’arbre ou mettre l’objectif en pause interrompt les actions restantes. Si le serveur refuse un transfert ou qu’une case change, les clics ne sont pas répétés automatiquement : vérifier les entrées et l’objet éventuellement sur le curseur avant de réessayer. Les machines sans entrées accessibles ne permettent pas cet envoi.

## Suivi et reprise

Le bouton **Suivi** est placé à côté de **Scanner coffres**, en bas d’EMI. Il est aussi accessible dans le panneau d’une machine. Chaque ligne affiche le produit, le nombre de fabrications et son état :

| État        | Signification                                                                                                                                        |
| ----------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| À collecter | Il manque encore des ingrédients dans l’inventaire pour un lot.                                                                                      |
| À envoyer   | Les ingrédients d’au moins une fabrication machine sont disponibles. Le panneau de la machine vérifie ensuite ses entrées, sa place et son activité. |
| À fabriquer | Les ingrédients d’au moins une fabrication manuelle sont disponibles.                                                                                |
| En machine  | Le dépôt a réussi ; ses sorties garanties restent réservées. Cela ne garantit pas que la machine reçoit assez d’énergie.                             |
| À récupérer | Une sortie attendue a été observée dans l’interface de cette machine.                                                                                |
| Terminé     | Les résultats attendus ont été récupérés.                                                                                                            |

Sélectionner une ligne donne accès à **Recette** et, pour un lot envoyé non terminé, **Annuler le lot**. Échap depuis la recette ramène au suivi ; **Retour** ramène au menu précédent.

**Recalculer** relit les branches et les stocks actuels. L’avancement final et les lots encore en attente sont conservés ; un scan arrêté reste arrêté. Le scan actif repart de l’inventaire actuel et des cibles finales acquises : ses anciens crédits d’ingrédients intermédiaires sont remplacés. Les contenus des coffres restent ceux mémorisés lors de leur ouverture.

**Annuler le lot** libère la réservation dans le suivi. Cette action ne déplace pas les objets déjà envoyés et ne stoppe pas physiquement la machine. Vérifier son contenu avant d’envoyer un autre lot.

Les sorties sont observées quand l’interface native est ouverte. Le suivi reconnaît les objets repris et les fluides repris dans les contenants de l’inventaire ou du curseur. Une sortie transférée automatiquement vers un coffre ou un tuyau ne prouve pas une récupération personnelle ; annuler sa réservation si nécessaire. Seules les sorties garanties sont réservées, pas les résultats aléatoires. Les lots sont locaux à l’arbre, à la dimension et à la session.

Les composants transformés remplacent leur crédit de matières premières : quatre lingots devenus deux plaques ne restent pas disponibles comme lingots pour les plaques suivantes. Un moule ou outil partagé n’est planifié qu’une fois, même dans des branches de profondeurs différentes.

## Avancement conservé

Les résultats finaux sont comptés lorsqu’ils arrivent dans l’inventaire ou sur le curseur, y compris par Maj-clic. Une baisse du stock ne retire pas le crédit. Trois machines fabriquées et rangées une par une peuvent donc terminer une cible de trois machines.

Le suivi distingue un retour d’objet d’une nouvelle fabrication grâce aux événements de craft et aux retraits des vraies sorties machine. Ranger et reprendre le même objet ne gonfle plus le compteur. Les déplacements entre l’inventaire, l’équipement et le curseur ne créent pas de crédit supplémentaire. Sans preuve de fabrication, un gain remplaçant un objet précédemment sorti est traité prudemment comme son retour ; récupérer un objet différent mais identique depuis un coffre ne prouve donc pas toujours une nouvelle fabrication. Ce suivi reste une observation client, pas un journal serveur.

L’avancement d’un objectif est enregistré localement par l’assistant. Réimporter **Objectif EMI** utilise le reste à obtenir ; les cibles terminées ne reviennent pas après rangement. Le suivi d’un craft libre est limité à son arbre et à la session ; changer d’arbre démarre un nouveau suivi. Une pause n’est jamais annulée automatiquement.

## Tester une mise à jour

Redémarrer l’assistant et Minecraft. Choisir un petit arbre avec un composant prêt dans un coffre et ses matières dans un autre, activer le scan et vérifier **Prendre / Tout**. Tester ensuite une recette machine avec circuit, une récupération partielle, **Suivi → Recalculer / Annuler le lot**, puis une cible de plusieurs objets rangés au fur et à mesure. Reprendre plusieurs fois le même objet ne doit pas augmenter son avancement.

Ces fonctions utilisent le client, les recettes locales et les contenus mémorisés. Aucun appel IA n’est nécessaire pour calculer les retraits ou envoyer un lot.

Les allocations machine sont conservées tant que l’arbre, les stocks et les réservations ne changent pas. Les compatibilités machine sont mises en cache et invalidées régulièrement ou par **Recalculer**.
