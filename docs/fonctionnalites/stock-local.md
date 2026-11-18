# Stock local

## Points d’entrée

- **Options → Contrôles → Assistant IA** permet d’associer une touche à **Choisir / désélectionner le coffre tampon** et à **Exclure / réintégrer un coffre**. Viser le conteneur et ouvrir une fois un nouveau tampon pour relever son contenu.
- **Y → Réglages → Backpacks dans les crafts** inclut les sacs portés dans le calcul. **Dans ton backpack × N** dans EMI indique la quantité à portée.
- [CoffreTampon.java](../../mod/src/main/java/fr/assistantia/CoffreTampon.java), [InventaireJoueur.java](../../mod/src/main/java/fr/assistantia/InventaireJoueur.java), [SacsFabrication.java](../../mod/src/main/java/fr/assistantia/SacsFabrication.java) et [inventory.py](../../monifactory_assistant/game/inventory.py) fournissent les relevés et leur portée.

## Règles à préserver

- Le stock personnel couvre les poches, l’équipement et le curseur. Un backpack porté, y compris via Curios, ne compte qu’une fois même quand il est ouvert ou contient d’autres sacs. Un sac posé au sol entre dans les stockages du monde.
- Un tampon compte uniquement pour la connexion et la dimension actives. Son relevé reste utilisable à la fermeture du menu tant que le bloc est identifié et son chunk chargé. Une nouvelle ouverture actualise le relevé.
- Le calcul déduit les tampons avant les coffres ordinaires. **Prendre / Tout** reste disponible dans un tampon ouvert. Un coffre exclu sort des repères, de la couverture des coffres à portée et des retraits.
- Un coffre double forme un seul stock physique. Un bus ME qui expose un tampon ne rend pas ses objets disponibles deux fois.
- **Envoyer** utilise les vraies entrées de la machine et les ingrédients accessibles dans les poches. Pour un sac fermé, l’étape indique de sortir l’ingrédient avant l’envoi.

## Tests à lancer

Lancer ces tests depuis la racine du dépôt avec Python et le JDK 17.

```sh
python -m unittest discover -s tests -p 'test_buffer_chest.py'
python -m unittest tests.test_backpack_inventory tests.test_emi_backpack_tree tests.scenarios.test_parcours_stock
```

Dans une copie du pack, déplacer la même pile entre poches, sac et tampon. Comparer le manque dans EMI, Repérage, Réseau ME et Envoyer. Rejouer après fermeture du coffre, déchargement et reconnexion. [Recette du coffre tampon](../developpement.md#recette-coffre-tampon-en-jeu), [guide des transferts](../emi-transferts.md#coffres-tampons).
