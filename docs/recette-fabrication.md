# Recette de test en jeu — de la collecte à la sortie

Cette campagne se joue en solo dans une **copie CurseForge de Monifactory**. Créer un monde
créatif de test avec les commandes activées. Pour les recettes GregTech, utiliser le même mode
du pack que la partie à qualifier. Démarrer le même JAR et le même script client que ceux de
la version à tester. Si l’assistant Python est nécessaire au repérage, lancer celui du même
checkout avec le profil de la copie. Garder une copie du monde une fois les postes et les stocks
préparés pour retrouver le même point de départ.

Noter avant le premier essai le commit Git, le SHA-256 du JAR chargé, les versions du pack, de
Forge, d’EMI, de GregTech et l’OS. Garder un seul JAR Assistantia dans `mods`. Refaire le test
après une relance complète de Minecraft si le JAR change.

## AT-01 — Un baril, huit planches, deux établis

Le [pack de données de test](../tests/fixtures/craft_lab/) prépare un baril et un établi sur
un plateau. Il contrôle ensuite les quantités réelles du baril et de l’inventaire. Les boutons du
mod et la fabrication restent à rejouer dans les menus.

1. Copier le dossier `tests/fixtures/craft_lab` dans `saves/<monde de test>/datapacks`. Dans ce
   monde, exécuter `/reload`, puis `/datapack list enabled` et vérifier que `file/craft_lab` apparaît.
2. Laisser les planches de chêne et les établis dans un autre coffre du monde de test. Activer le
   vol créatif avant `/tp @s 0.5 151 0.5`, puis exécuter `/function moni_qa:atelier/preparer`.
   Le baril (1, 151, 0) reçoit huit planches de chêne, l’établi est en (0, 151, 1).
3. Dans EMI, choisir la recette de l’établi à quatre planches et demander deux fabrications.
   Ouvrir une fois le baril pour le relever. Activer **Repérage**, disponible dans **Aide…** si
   l’écran est étroit. Rouvrir le baril, puis cliquer **Tout**. Fermer le menu et exécuter
   `/function moni_qa:atelier/verifier_retrait`.
   Le résultat attendu est `PASS AT-01 retrait`, avec huit planches, zéro établi et le baril vide.
4. Ouvrir l’établi. Cliquer **Tout (×2)** et vérifier les quatre cases, chacune avec deux planches.
   Prendre le résultat en Maj-clic. Dans une deuxième exécution, utiliser **Remplir** deux fois,
   en prenant le résultat entre les clics. Fermer le menu puis exécuter
   `/function moni_qa:atelier/verifier_craft`. Le résultat attendu est `PASS AT-01 craft`,
   avec zéro planche, deux établis et le baril vide. Vérifier aussi que l’arbre a compté deux résultats.
5. Ranger un établi dans un autre baril et le reprendre. Avec la même cible, vérifier que
   l’avancement ne gagne aucune unité supplémentaire. Rejouer depuis un inventaire plein et
   noter les quantités résiduelles sur le curseur, dans la grille et dans le baril après fermeture.

Exécuter les contrôles depuis le plateau, menus fermés et curseur vide. Le point de départ
prend quelques secondes à reproduire dans une nouvelle copie du monde.

## Collecte et établi

| Cas   | Actions                                                                                                                                                                                                                                          | Résultat observable                                                                                                                                                                               |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ST-01 | Rejouer le [coffre tampon en jeu](developpement.md#recette-coffre-tampon-en-jeu) avec trois unités portées, deux dans un tampon et une cible qui en demande huit. Déplacer les deux unités dans un deuxième tampon, puis dans le backpack fermé. | Besoin restant de trois après synchronisation. Chaque source conserve ses possibilités de retrait propres. Le menu du sac n’est pas compté deux fois.                                             |
| ST-02 | Ajouter une pile de huit dans un coffre ordinaire, un coffre exclu et un rangement ME. Exposer le tampon par un bus de stockage.                                                                                                                 | Le bus ne duplique pas le stock tampon. Le coffre exclu reste absent du Repérage et les quantités transférables du terminal correspondent à ses entrées natives.                                  |
| ST-03 | Choisir deux ingrédients, cliquer **Prendre** sur l’un, puis **Tout**. Refaire avec deux variantes d’un outil sous un même tag, puis deux piles d’objets à NBT distincts.                                                                        | Le premier clic ne prend que la sélection. Le second prend le complément. Une variante admissible couvre l’outil ; un NBT requis reste exact.                                                     |
| ST-04 | Cliquer deux fois rapidement sur **Tout**, avec une place libre limitée. Refaire en fermant le coffre juste après le premier clic.                                                                                                               | Une seule opération active. Les quantités restantes se retrouvent dans leurs cases ou sur le curseur. Rouvrir le coffre permet de recalculer sur l’état réel.                                     |
| ST-05 | Éteindre **Repérage** ou changer l’arbre pendant que la collecte se met à jour. Rouvrir le même coffre, puis le terminal.                                                                                                                        | Les anciennes quantités cessent d’autoriser une action. Le nouvel arbre se sert des quantités déjà acquises.                                                                                      |
| ST-06 | Retirer partiellement au terminal ME, laisser un autre ingrédient atteindre zéro dans le terminal, puis choisir une autre recette.                                                                                                               | Le panneau retire seulement ce qui manque dans la vue courante. L’article épuisé et l’ancienne sélection disparaissent. Aucun second retrait sans nouveau clic.                                   |
| AT-02 | Tester une recette de craft avec outil GregTech, d’abord avec l’outil dans les poches, ensuite dans un backpack fermé et enfin avec une place occupée dans la grille.                                                                            | Les variantes admises apparaissent couvertes dans EMI. Le remplissage utilise les cases accessibles, demande de sortir l’outil du sac et préserve la grille si le craft ne peut pas être préparé. |
| AT-03 | Fermer et rouvrir l’établi à stock identique, changer d’arbre sans modifier l’inventaire, puis passer au coffre.                                                                                                                                 | Le remplissage revient pour la recette compatible. Le coffre ne propose que la collecte pertinente. La grille précédente ne déclenche aucun craft spontanément.                                   |
| AT-04 | Fabriquer deux résultats en Maj-clic, vérifier le crédit, augmenter seulement la quantité de la même cible, puis changer de produit final.                                                                                                       | Le crédit reste acquis quand seule la quantité change. Une nouvelle cible repart de ses propres quantités, avec le stock physique courant.                                                        |

### Retrait par piles dans le terminal AE2

Dans la copie du pack, stocker plus de 130 bûches identiques dans ME. Choisir dans EMI la
recette de planches de cette essence et demander 130 fabrications à une bûche chacune.
Cliquer **Prendre** avec au moins trois cases libres. Vérifier que l’inventaire reçoit deux
piles de 64 et deux bûches, avec un débit de 130 dans le terminal. Refaire avec une seule
case presque pleine, puis avec **Arrêter** pendant le premier transfert. Comparer le stock
restant dans ME, celui des poches et celui du curseur après la dernière confirmation.

### Chaîne sac, terminal et cintreuse à 63, 64 et 65 plaques

Utiliser la copie du pack et le [pack de données de test](../tests/fixtures/craft_lab/). Dans
EMI, vérifier que la recette GregTech `gtceu:bender/bend_copper_to_plate` produit une plaque
de cuivre pour un lingot avec le circuit virtuel 1. Préparer une cintreuse UV, un backpack
Sophisticated et un réseau AE2 isolé comportant un terminal, un lecteur avec une cellule de
stockage d’objets et une source d’énergie. Garder la cintreuse sans énergie au début du cas.

| Cible EMI  | Cuivre dans le sac fermé | Cuivre ME au départ | Retrait ME attendu | Solde ME  | Envois dans la cintreuse |
| ---------- | ------------------------ | ------------------- | ------------------ | --------- | ------------------------ |
| 63 plaques | 1 lingot                 | 67 lingots          | 62 lingots         | 5 lingots | 63                       |
| 64 plaques | 1 lingot                 | 68 lingots          | 63 lingots         | 5 lingots | 64                       |
| 65 plaques | 1 lingot                 | 69 lingots          | 64 lingots         | 5 lingots | 64 puis 1                |

1. Exécuter `/function moni_qa:machine/initialiser`, puis régler la cible avec
   `/scoreboard players set @s moni_cible 63` en remplaçant 63 selon le cas. Vider les
   autres réserves de cuivre et de plaques sur la copie, puis placer un lingot dans le sac
   porté et la quantité du tableau dans ME.
2. Avec le sac fermé et l’option **Backpacks dans les crafts** activée, demander dans EMI la
   quantité du tableau. Dans le terminal, vérifier la quantité proposée et cliquer
   **Prendre**. Vérifier le solde de cinq dans le terminal, le lingot toujours dans le sac
   et le curseur vide.
3. Ouvrir le sac, transférer son lingot dans les poches et exécuter
   `/function moni_qa:machine/verifier_pret`. Le contrôle doit afficher la quantité
   demandée de lingots et zéro plaque.
4. Ouvrir la cintreuse et cliquer **Envoyer**. Vérifier le lot confirmé, la quantité
   dans l’entrée et la sortie vide. Pour 65, fermer une éventuelle déclaration de surplus
   avec Échap et garder le dernier lingot dans les poches pour le second envoi.
5. Alimenter la cintreuse, attendre les plaques et les prendre par Maj-clic dans la sortie.
   Pour 65, envoyer le dernier lingot après le premier retrait, puis prendre la dernière
   plaque. Vérifier que les lots ont été réceptionnés dans **Suivi**.
6. Fermer la machine et exécuter `/function moni_qa:machine/verifier_reception`. Le contrôle
   doit afficher 63, 64 ou 65 plaques et zéro lingot. Rouvrir le terminal pour confirmer
   les cinq lingots restés dans ME. Repartir de la copie restaurée ou d’une station fraîche
   avant le cas suivant.

Les fonctions de contrôle lisent le stock physique des poches côté serveur. Le menu du sac,
le terminal et la sortie de la machine permettent de contrôler les autres quantités.
Consigner séparément chacun des trois cas et l’empreinte du JAR utilisé.

**Qualification locale macOS, IN_GAME.** Les trois cas ont réussi dans un labo Forge
jetable sous Java 17, Minecraft 1.20.1, Forge 47.4.13, EMI 1.1.22, GTCEu 7.5.3,
AE2 15.4.10-cosmolite.36 et Sophisticated Backpacks 3.24.21.1515. JAR Assistantia
`1.0.0-shared.68`, SHA-256 `bcba95c8e3041ab39dc9cfdeeca2ac09102285711ae59297f4f7d055601a8e88`.

- **63** · 62 lingots retirés du réseau ME, un du sac, un envoi de 63 et 63 plaques
  physiquement récupérées. Solde ME de cinq lingots.
- **64** · 63 lingots retirés du réseau ME, un du sac, un envoi de 64 et 64 plaques
  physiquement récupérées. Solde ME de cinq lingots.
- **65** · 64 lingots retirés du réseau ME et un du sac. Un lingot restait dans les poches
  après le premier envoi et la réception de 64 plaques. Le second envoi a produit la
  dernière plaque. Solde ME de cinq lingots.

La sonde a préparé les stocks, le circuit et l’énergie dans la copie. Le transfert du sac,
les retraits ME, les dépôts GregTech et la récupération ont traversé les menus natifs. Les
contrôles du pack de données ont confirmé le stock physique avant chaque premier envoi et
après la réception de la cible. La copie a été fermée proprement.

**Revalidation du candidat des dix améliorations, IN_GAME.** Les trois tailles ont réussi
avec `1.0.0-shared.68-qa-suite10` et l’empreinte SHA-256
`51a40f691ba6e47f15618de14ad75f070dc74079b84bb2fa2bd7dc43903d269a`.
Dans la même copie, reprendre après Échap n’a envoyé que le second lingot. Une plaque
identique prise dans un baril n’a donné aucun crédit au lot de la cintreuse. Le retrait
dans sa vraie sortie a confirmé les trois plaques. Le parcours du tampon a vérifié cinq
lingots dans le coffre et trois en poche, puis huit en poche après retrait.

## GregTech — envoyer, fabriquer et récupérer

Préparer un LV Alloy Smelter alimenté et le circuit d’alimentation adapté. Sélectionner
l’alliage cuivre + étain → bronze dans EMI. Sur le catalogue Expert utilisé par le dépôt,
une opération consomme **3 cuivres et 1 étain** et produit **4 lingots de bronze**. Comparer les
quantités dans EMI sur la copie utilisée avant le test. Demander huit lingots et préparer six
cuivres et deux étains. Isoler le coffre de collecte nominal du coffre de sortie.

| Cas   | Actions                                                                                                                                                                                                  | Résultat observable                                                                                                                                                  |
| ----- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| GT-01 | Partir du stock dans un coffre ou dans ME. Récupérer 6 cuivres et 2 étains. Ouvrir le four, cliquer **Envoyer** et contrôler les entrées. Attendre deux opérations, récupérer huit bronzes.              | Les entrées reçoivent exactement les ingrédients du lot. Sortie attendue, stock physique et crédit sont cohérents à chaque étape.                                    |
| GT-02 | Répéter sans énergie, puis avec la sortie bloquée. Rétablir l’énergie ou libérer la sortie manuellement.                                                                                                 | L’envoi éventuel n’établit aucune réception. Les sorties existent physiquement avant leur acquisition. L’état se recalcule après déblocage.                          |
| GT-03 | Choisir dans EMI une recette de cintreuse avec circuit virtuel. Mettre un mauvais numéro, le numéro exact, puis changer le réglage juste après la lecture du panneau.                                    | Le panneau signale le numéro requis, ne collecte aucun circuit physique et revalide le réglage avant d’envoyer. Le bon réglage permet l’opération.                   |
| GT-04 | Choisir une recette d’Alloy Smelter avec moule. Essayer avec moule absent, dans un coffre, déjà installé, puis avec un mauvais moule dans la machine.                                                    | Le bon moule peut être repéré et son installation couvre le prérequis. Les fabrications répétées le laissent disponible, sans consommer une unité par cycle.         |
| GT-05 | Pour une recette GregTech à fluide, mesurer la quantité affichée par EMI. Essayer avec un contenant compatible dans les poches, un fluide différent et une cuve presque pleine.                          | Les mB envoyés correspondent à la recette et à la capacité, le contenant conserve le reliquat ou revient vide. Un transfert refusé conserve les quantités physiques. |
| GT-06 | Sur un multibloc du pack, comparer le contrôleur, deux bus d’entrée et un hatch de fluide. Refaire avec le multibloc déformé, puis reformé.                                                              | Le calcul tient compte des vraies entrées accessibles. Le panneau refuse une opération devenue incompatible ; les stocks encore valides reprennent après formation.  |
| GT-07 | Double-cliquer **Envoyer**, changer d’écran, puis rouvrir la machine. Dans une seconde exécution, retirer un ingrédient après l’affichage et avant le clic.                                              | Un seul envoi actif. Une fermeture arrête les étapes restantes en préservant les transferts déjà confirmés. Un clic périmé demande un nouveau calcul.                |
| GT-08 | Lancer une première opération tandis qu’une autre opération indépendante a déjà tous ses ingrédients. Puis choisir deux branches qui demandent trois lingots chacune, avec seulement quatre disponibles. | La première peut rester En cours pendant que l’autre devient Prête. Le stock commun ne peut pas rendre prêtes les deux branches qui se le disputent.                 |
| RE-01 | Faire arriver le bronze dans le coffre de sortie. Relever le repère de sortie. Prendre quatre bronzes, fermer puis rouvrir le coffre et récupérer le solde.                                              | Reste attendu de quatre, puis de zéro lorsque la réception est reconnue. Documenter toute attribution incertaine dans Production séparément du Suivi EMI.            |
| RE-02 | Après la première réception, ranger et reprendre quatre bronzes, puis annuler le lot encore partiel.                                                                                                     | Le rangement ne crédite pas de nouvelle fabrication. L’annulation libère le besoin restant et les quatre objets physiques déjà reçus restent acquis.                 |
| RE-03 | Déclarer deux lots du même produit, de 8 chacun. Récupérer 3, puis 5, puis 8 résultats et ajouter une acquisition sans rapport entre les deux premières récupérations.                                   | Les lots se partagent au maximum les acquisitions prouvées. La somme des unités créditées avance de 3, 8 puis 16 au total.                                           |
| RE-04 | Déclarer successivement 63, 64 et 65 résultats en utilisant un produit à sortie constante. Sur le cas de 65, choisir une quantité partielle, essayer Échap, puis confirmer.                              | Déclaration directe sous 64. À partir de 64, dialogue, quantité partielle et annulation du dialogue. La quantité non déclarée reste à préparer.                      |
| RE-05 | Marquer le lot terminé manuellement, supprimer sa ligne, puis rouvrir EMI et les stockages.                                                                                                              | L’action de suivi clôt le lot. Supprimer sa ligne laisse les crédits déjà reconnus. Le retrait de la ligne ne déplace aucun objet.                                   |

## Enchaînements qui attrapent les bugs rares

### Fabrication répartie et Échap

Utiliser une copie du monde. Choisir dans EMI une recette GregTech déterministe dont les
entrées incluent un moule réutilisable et un fluide. Relever la quantité par lot et préparer
au moins deux lots pour vérifier une réception partielle. Faire les relevés dans le même
contexte d’arbre et de connexion.

1. Placer le moule dans la machine et répartir les autres composants entre les poches, un
   backpack fermé, un tampon relevé et le réseau ME. Relever la quantité retenue chez chaque
   source. Vérifier la couverture dans EMI, puis le reste dans **Repérage** et **Réseau ME**.
2. Récupérer le complément avec **Prendre** ou **Tout**, sortir ce qui reste dans le sac et
   prendre le contenant de fluide. Vérifier les soldes physiques et le curseur avant l’envoi.
3. Ouvrir la machine. Vérifier le moule présent, le fluide attendu et le nombre de lots.
   Cliquer **Envoyer** et contrôler les entrées réelles et la sortie encore vide. Alimenter
   la machine pour lancer la fabrication.
4. Récupérer une partie du résultat, fermer aussitôt avec Échap et vérifier le crédit partiel
   dans **Suivi**. Récupérer le solde et vérifier le nombre total, le moule et le retour du
   contenant selon la recette.
5. Pour un retrait ME ou coffre suivi d’Échap avant la confirmation, comparer le lot, le
   stock du conteneur et les poches avant et après réouverture. Le même retrait doit laisser
   un seul crédit même si l’affichage de l’inventaire se met à jour après **Suivi**.

Le scénario correspondant joue les réponses serveur simulées avec le code Java, vérifie
les 2 unités proposées dans ME et Repérage et contrôle les réceptions fractionnées.

```sh
python -m monifactory_assistant.commands.scenarios fabrication
```

**Cas en jeu.** Sélectionner un coffre de l’Ender dans EMI et choisir
`gtceu:fluid_solidifier/obsidian` pour l’obsidienne. Préparer un œil de l’Ender, deux
obsidiennes dans un sac porté, deux dans un tampon, une dans un coffre ordinaire et trois
dans ME. Placer un seau de lave avec l’obsidienne du coffre, un seau de lave dans les poches
et un moule à bloc dans le Solidifier. Une fois les deux obsidiennes du Solidifier reçues,
le terminal doit fournir une obsidienne et en garder deux. Réunir les huit obsidiennes et
cliquer sur **Remplir** à l’établi.

Le JAR `1.0.0-shared.72-local.1`
(`adb6b8d3da2d79e43911d3aed597259b054bbd95c2becbf45444a1a751efd7fc`) a réussi ce parcours
dans la copie macOS avec serveur intégré. Le Solidifier a reçu 2 000 mB, rendu les deux seaux
et gardé le moule. Le retrait suivi d’Échap a crédité les deux obsidiennes une fois. L’établi
a produit un coffre de l’Ender et fermé l’arbre terminé.

### Variantes à rejouer

Rejouer les gestes dans des ordres différents et contrôler chaque étape. Pour chaque source
(coffre, ME, poches, sac), varier capacité normale et pleine, puis les interruptions avant/après
le premier clic et avant/après sa confirmation. Ajouter explicitement ces combinaisons.

- Tampon exposé dans ME → retrait partiel → changement d’arbre → ouverture de l’ancienne machine.
- Envoi en deux étapes → fermeture après confirmation du premier transfert → réouverture → envoi du reste avec le bon circuit.
- Sortie partielle → passage par le backpack → dépôt dans un coffre → reprise → annulation du solde.
- Atelier → double clic sur remplissage → Maj-clic sur le résultat → Échap → ouverture de la collecte.
- Lot attendu → changement de dimension en solo ou Sauvegarder et quitter → recharger le monde → tenter le même besoin.

## Résultats à relever

Pour chaque cas, consigner `PASS`, `FAIL` ou `À TESTER` avec les quantités avant et après
dans le coffre, le terminal, l’inventaire, le curseur, les entrées et les sorties utiles.
Un changement de quantité doit correspondre au transfert confirmé ou au rendement de la
recette. Une sortie simplement attendue n’ajoute aucun objet physique. Une réponse de l’ancien
contexte ne peut pas activer une opération actuelle.

Pour un échec, relever l’identifiant du cas, le premier état faux, la recette et sa variante,
les clics dans l’ordre, le message du panneau et un extrait de journal expurgé. Si un scénario
automatisé a généré les étapes, joindre sa graine et la liste effective des actions. Utiliser
cette séquence comme cas de régression. La [suite hors jeu](scenarios.md#fabrication) donne des
preuves de la logique simulée. Les résultats de la recette en jeu se rapportent au JAR chargé.

## Première exécution locale

Le candidat `1.0.0-shared.64-qa-craft` a été chargé sur macOS avec Minecraft 1.20.1,
Forge 47.4.13, EMI 1.1.22 et GTCEu 7.5.3 dans une instance jetable avec un sous-ensemble du pack.
SHA-256 du JAR chargé `9fadf4875ca28edfb8be8f936d2b77a9eb31491add6b2dc59f41c956d524c06b`.

- **AT-01, IN_GAME**. Le bouton **Tout** du baril a transféré huit planches. **Tout (×2)**
  à l’établi a placé deux planches dans chacune des quatre cases, puis le Maj-clic sur la
  sortie a donné deux établis. Les deux contrôles de quantité ont affiché `PASS`. Le signal
  de présence de l’assistant était simulé dans cette copie. Les menus et les transferts
  utilisaient le vrai client et son serveur intégré.
- **GT-01 et étape sans énergie de GT-02, IN_GAME**. Les matières ont été placées dans les
  poches pour isoler **Envoyer**. La vraie interface du four a reçu six cuivres et deux étains.
  La sortie est restée vide avant alimentation. La sonde a rempli le réservoir d’énergie natif
  du four. Deux opérations réelles ont suivi. Les retraits natifs ont donné quatre lingots,
  puis quatre autres. La sortie était vide après le deuxième retrait.
- **Réinitialisation de l’atelier, LOCAL_INTEGRATION**. Dans Minecraft 1.20.1 vanilla de test,
  le pack a restauré les huit planches après vidage du baril. Les contrôles ont rejeté les
  mauvais états et accepté les stocks de référence.

Les autres parcours en jeu restent **À TESTER**, notamment le terminal ME avec bus partagé,
les multiblocs, les fluides, le changement de circuit et les réceptions via un coffre de sortie.
La qualification du pack Monifactory complet reste à faire. Les tests automatisés ont réussi
761 cas découverts par `unittest` et 52 cas du lanceur de scénarios, dont 15 avec **Fabrication**.
