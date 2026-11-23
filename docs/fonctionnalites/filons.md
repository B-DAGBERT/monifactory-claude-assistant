# Filons GregTech

## Points d’entrée

- **Y → Filons** affiche les gisements connus dans la dimension courante. Sélectionner un filon puis **Scanner** pour vérifier ses blocs ou **Afficher** pour choisir les minerais à surligner.
- [GregTechFilons.java](../../mod/src/main/java/fr/assistantia/GregTechFilons.java) lit le journal client GregTech. [Filons.java](../../mod/src/main/java/fr/assistantia/Filons.java) gère scan, mémoire et contours. [OngletFilons.java](../../mod/src/main/java/fr/assistantia/OngletFilons.java) gère le menu.
- [veins.py](../../monifactory_assistant/game/veins.py) lit le résumé `filons_mod.json` pour la Discussion. Les positions individuelles restent dans la mémoire du mod.

## Règles à préserver

- Le journal GregTech du client est la source des filons. Le scan parcourt le volume du générateur, conserve ce qui a été trouvé et reprend les zones en attente lorsqu’elles sont chargées.
- L’affichage et le scan ont des budgets distincts. **Surface** affiche les minerais visibles. **Total** ajoute ceux derrière la roche. Le plafond de contours s’applique uniquement au rendu.
- Les blocs déjà reconnus sont revérifiés dans les chunks chargés. Le seuil de 10 % pour signaler un filon presque épuisé exige un scan complet et au moins 16 blocs trouvés. **Épuisé / Rétablir** est un choix du joueur.
- En solo, les scans et le marquage **Épuisé** appartiennent à la sauvegarde et au journal GregTech du joueur. Deux copies peuvent partager les découvertes du journal natif. Chacune conserve ses propres scans et marquages. Les anciens fichiers et leurs copies de secours restent dans `kubejs/assistant/filons_gregtech/` sans attribution automatique à une copie. Les sélections restent propres à chaque dimension.
- **TP** utilise les permissions du joueur. En l’absence de point libre connu, l’arrivée peut se faire dans la roche. L’opération ne creuse aucun bloc.

## Tests à lancer

Lancer ces tests depuis la racine du dépôt avec Python et le JDK 17.

```sh
python -m unittest tests.test_filons tests.test_filons_import
python -m unittest tests.test_filons_persistence tests.test_vein_tab tests.test_world_identity
```

Dans une copie du pack, interrompre et reprendre un scan, miner un bloc surligné et changer de dimension. Vérifier le compteur, la reprise des chunks en attente et le rendu Surface / Total. [Parcours Filons](../utilisation.md#filons--scan-complet-surbrillance-et-téléportation), [mémoire et scan](../developpement.md#filons--scan-complet-compte-en-direct-et-sauvegarde-v2).
