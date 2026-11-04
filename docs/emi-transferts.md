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

Le scan est un instantané de l’arbre : après modification des recettes, arrêter puis relancer le scan. Ouvrir un coffre actualise son contenu mémorisé. Arrêter le scan retire les contours et le panneau **Collecte**. Un objectif seul n’autorise aucun retrait.

La mémoire des coffres distingue les identifiants des objets, pas leurs variantes NBT. Dans le coffre ouvert, le retrait vérifie aussi les variantes EMI, avec une exception pour le NBT factice du Programmed Circuit. Un coffre peut donc être signalé sans proposer de retrait si la variante est incompatible. La recherche ne suit pas les fluides. Un stock inaccessible ou périmé ne crée jamais d’objet : les retraits sont bornés par les vraies cases du coffre ouvert.

## Envoyer dans une machine

- Le panneau indique les fabrications possibles / restantes. Les quantités détaillées sont au survol ; **Voir** ouvre la recette EMI.
- Les entrées déjà remplies sont déduites. Les outils et catalyseurs ne sont pas multipliés par le nombre de fabrications.
- Le **Programmed Circuit** est reconnu sans son NBT factice. Avec un sélecteur de circuit GregTech, le numéro est lu dans la vraie recette puis réglé sur la machine. Les autres objets gardent les règles de comparaison EMI.
- Les fluides viennent uniquement des contenants de l’inventaire. Le clic natif vide le contenant puis le rend. Un seau de 1 000 mB peut dépasser le besoin : le volume réellement envoyé est indiqué au survol.
- Les sorties ne sont jamais récupérées automatiquement. Les fabrications envoyées sont réservées pour éviter les doublons ; elles ne sont pas déclarées terminées avant récupération du résultat.

Pendant un envoi, la progression est affichée et les doubles clics sont ignorés. Fermer le menu, arrêter l’arbre ou mettre l’objectif en pause interrompt les actions restantes. Si le serveur refuse un transfert ou qu’une case change, les clics ne sont pas répétés automatiquement : vérifier les entrées et l’objet éventuellement sur le curseur avant de réessayer. Les machines sans entrées accessibles ne permettent pas cet envoi.

## Avancement conservé

Les résultats finaux sont comptés lorsqu’ils arrivent dans l’inventaire ou sur le curseur, y compris par Maj-clic. Une baisse du stock ne retire pas le crédit. Trois machines fabriquées et rangées une par une peuvent donc terminer une cible de trois machines.

Le suivi client mesure les acquisitions : un objet ramassé ou récupéré peut aussi satisfaire la cible. Il n’est pas une preuve serveur de fabrication. Les déplacements entre l’inventaire, l’équipement et le curseur ne créent pas de crédit supplémentaire. Éviter les allers-retours du même objet avec un stockage extérieur pour mesurer une production exacte.

L’avancement d’un objectif est enregistré localement par l’assistant. Réimporter **Objectif EMI** utilise le reste à obtenir ; les cibles terminées ne reviennent pas après rangement. Le suivi d’un craft libre est limité à son arbre et à la session ; changer d’arbre démarre un nouveau suivi. Une pause n’est jamais annulée automatiquement.

## Tester une mise à jour

Redémarrer l’assistant et Minecraft. Choisir un petit arbre avec un composant prêt dans un coffre et ses matières dans un autre, activer le scan et vérifier **Prendre / Tout**. Tester ensuite une recette machine avec circuit et une cible de plusieurs objets rangés au fur et à mesure.

Ces fonctions utilisent le client, les recettes locales et les contenus mémorisés. Aucun appel IA n’est nécessaire pour calculer les retraits ou envoyer un lot.
