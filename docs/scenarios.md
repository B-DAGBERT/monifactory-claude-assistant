# Rejouer les scénarios EMI

Ouvrir **Tester Mac.command** sur Mac ou **Tester.bat** sur Windows, puis choisir **Basique**,
**Complexe** ou **Tous**. Minecraft peut rester ouvert : les tests utilisent des données fictives,
des processus Java temporaires et les vrais contrôleurs du dépôt. Ils ne lancent aucune IA,
ne déplacent rien en jeu et ne modifient ni objectif, ni coffre, ni configuration personnelle.

En ligne de commande, depuis le dépôt :

```sh
.venv/bin/python -m monifactory_assistant.commands.scenarios basique
.venv/bin/python -m monifactory_assistant.commands.scenarios complexe
.venv/bin/python -m monifactory_assistant.commands.scenarios tous
```

Sous Windows, remplacer `.venv/bin/python` par `.venv\Scripts\python.exe`.
Les lanceurs résolvent le JDK 17 avec le même réglage `java_home` que l’installateur.
Les bibliothèques de compilation doivent déjà être en cache : lancer l’installation une fois.
Le test ne télécharge rien ; un prérequis absent ou un test ignoré ne donne pas de validation complète.

## Basique

Six parcours vérifient le cycle fabrication → coffre jaune → récupération → pose, les livraisons
partielles, l’arrêt du scan, l’arrivée d’un résultat dans un coffre auparavant vide, le moule unique
pour tout un lot et sa disponibilité temporaire lorsqu’il est installé dans une machine.

Un contrat machine supplémentaire rejoue les vrais planificateur et séquenceur d’envoi : moule absent,
moule récupéré, moule déjà installé, circuit virtuel erroné puis corrigé, faux NBT d’un circuit physique,
changement du réglage après le clic, transferts refusés, fermeture d’écran, cuves pleines et retour des
contenants. Trois utilisations successives d’un seau conservent exactement un seau vide après chaque vidage.
Le complément de l’arbre inclut les catalyseurs physiques séparés de GregTech, une seule fois, sans
ajouter le circuit virtuel ni les postes de fabrication des autres catégories EMI.

## Complexe

Dix-sept parcours ajoutent notamment :

- une machine qui termine après plus de dix minutes ;
- l’assistant lent ou une écriture temporairement refusée, avec reprises espacées et bornées ;
- deux lots concurrents du même résultat et les livraisons partielles ;
- les anciennes réponses après modification des besoins, la pause et la déconnexion ;
- les déplacements en backpack pendant sa synchronisation ;
- cent coffres mélangés, dont quarante utiles, et un lot de 320 objets reçu en huit étapes ;
- la priorité aux composants déjà prêts plutôt qu’à leurs ingrédients et outils ;
- la fusion des relevés client/serveur selon leur date, les coffres vides omis du relevé serveur,
  un coffre remplacé par une machine et les mêmes coordonnées dans deux dimensions.

Le contrat machine décrit plus haut est également exécuté. **Tous** le rejoue une seule fois.

## Résultats et limites

Chaque exécution affiche les cas réussis ou échoués et écrit un rapport JSON daté dans
`.runtime/scenarios/`. Ce dossier est local et ignoré par Git. Le code de sortie vaut `0` uniquement
si tous les cas ont été exécutés avec succès ; un échec, une erreur ou un test ignoré vaut `1`.
Une erreur de lancement vaut `2`.

Les tests combinent le vrai contrôleur Java `RechercheEmi`, la mémoire de collecte, les allocations,
les réceptions de lots, les interactions machine et la recherche Python `chest_search` avec des
contrats d’API déterministes. Les délais sont simulés : le scénario de douze minutes ne dure pas douze
minutes. Ils ne valident ni le rendu GPU, ni le chargement effectif des mixins dans Forge, ni les
permissions et réponses d’un serveur réel. Après redémarrage du jeu, vérifier visuellement une recette
Alloy Smelter avec moule et une machine avec mauvais circuit, puis une récupération dans un coffre.
