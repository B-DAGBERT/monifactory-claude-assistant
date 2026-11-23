# Réseau ME et terminal AE2

## Points d’entrée

- Ouvrir un terminal de stockage ou de fabrication AE2 avec un **Recipe Tree** sélectionné.
  Le panneau **Réseau ME** propose **Prendre** pour l’objet choisi et **Tout** pour le reste.
  Pendant un retrait, **Arrêter** ou un autre clic dans le terminal interrompt la suite.
- [TerminalMe](../../mod/src/main/java/fr/assistantia/compat/emi/TerminalMe.java) lit les
  entrées natives, affiche les quantités et déclenche les retraits. [StockFabricationEmi](../../mod/src/main/java/fr/assistantia/compat/emi/StockFabricationEmi.java)
  partage le stock utile avec l’arbre EMI et déduplique le chevauchement possible avec les tampons.

## Règles à préserver

- Compter le stock annoncé par le terminal même avec un filtre de recherche. Pour une même
  variante issue du terminal et d’un tampon, conserver la plus grande quantité. Un bus de stockage
  peut déjà compter ce tampon.
- Déduire les poches, les sacs pris en compte et les tampons. Retenir les composants prêts avant
  les matières de leur branche. Seules les entrées natives en stock autorisent le retrait.
  L’autofabrication et les fluides du réseau restent à gérer dans AE2.
- Relire le terminal et l’arbre au clic. Préférer les piles entières lorsque la quantité et
  la place le permettent, puis les demi-piles et le solde. Chaque transfert attend sa confirmation.
- En solo, le panneau relit les entrées objet sur le thread du serveur intégré. Il examine les
  postes visités pour la recette sélectionnée et les machines desservies par un bus d’export du
  réseau ouvert, y compris les sous-classes ExtendedAE. La recherche parcourt les classes
  concrètes indexées par la grille AE2, dans les chunks chargés de la dimension du joueur, à 48 blocs au plus. Les droits
  du monde, de GregTech et de FTB Chunks sont vérifiés avant la lecture de l’inventaire.
  Si le seul bus est démonté pendant cette ouverture, le panneau peut encore vérifier le même
  bloc déjà identifié. Un changement d’arbre, de menu serveur, de réseau, de bloc ou un
  déchargement invalide cette association. À la réouverture, sans poste identifié ni baisse ME
  observée, le quota repart du besoin de l’arbre. Une nouvelle visite permet de vérifier le bain.
  Une case d’entrée couvre uniquement les consommables de son opération, à concurrence de son contenu.
  Une livraison par bus laisse Suivi à son état précédent. Seule une réception de la sortie
  peut faire avancer le compteur du papier reçu.
- **Prendre** et **Tout** actualisent ce calcul au clic puis avant chaque nouvelle prise native.
  Une ligne entièrement couverte reste visible à zéro. Si plusieurs postes sont ambigus, si leur
  lecture est refusée ou si du stock quitte le réseau sans destination vérifiable, le panneau
  indique **Poste à vérifier**. Une baisse ME entre le clic et la première prise suit cette
  même règle. Le serveur intégré suit les quantités natives en 64 bits par clé AE2, y compris
  au-dessus du plafond utilisé pour le calcul du plan. Après chaque retrait, il déduit de sa référence le gain
  physique du curseur ou de la poche. Un import ultérieur actualise cette référence.
  Au traitement de la demande, il relit les entrées et les quantités sur le même thread que
  l’interaction native AE2. Si le bain a changé depuis le calcul client, il demande un nouveau
  calcul du quota avant de retirer. Une baisse sans poste vérifiable suspend les branches concernées.
  En connexion à un serveur distant, le JAR client seul ne peut pas relire ces entrées. Les
  matières dépendant de ces opérations demandent une vérification au poste et un retrait manuel
  dans AE2. [Observation des entrées](../../mod/src/main/java/fr/assistantia/compat/emi/EntreesTerminalMe.java)
- Fermer le terminal invalide son relevé. Un curseur occupé empêche le départ. Après un arrêt,
  vérifier le curseur et le stock restant avant de reprendre.
- **Prendre** et **Tout** signalent à Suivi les quantités déposées dans les poches. Le passage
  par le curseur reste une étape intermédiaire. Après Échap, seul le dernier dépôt déjà envoyé
  peut encore être confirmé, y compris par la mise à jour serveur de la case dans le menu fermé.
  Suivi déduit ce dépôt de la réapparition dans la même case des poches, même après 120 secondes.
  Le dépôt est rapproché dès qu’il apparaît dans le relevé des cases, même si le départ simultané
  d’un objet identique maintient le total constant. Un gain indépendant de la même variante
  dans une autre case reste éligible. Un retrait ME ne
  prouve pas la machine d’origine.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest tests.test_me_terminal_panel tests.test_me_export_bus tests.test_emi_backpack_tree
python -m unittest discover -s tests -p test_native_production_lots.py -v
python -m monifactory_assistant.commands.scenarios parcours-stock
```

Pour AS-008, demander un papier, laisser le bain à 0 EU avec une canne et 100 mB de distillat,
puis conserver une canne en cellule et les poches vides. Comparer la visite préalable du bain
et la livraison par bus pendant l’ouverture du terminal. Dans les deux cas, le quota doit être
zéro et le curseur vide après **Tout**. Pour deux papiers, **Prendre** retire juste la deuxième canne.

Dans une copie du pack, vérifier le retrait en piles, le changement de filtre et un tampon
exposé par bus de stockage. Après Échap pendant un dépôt, rejouer la resynchronisation à 5 s
et à 125 s. Dans les deux cas, le second lot attend l’arrivée d’un autre objet physique.
Le test `collecte_me_total_constant` rejoue à 125 s le départ de l’ancien engrenage de la case 8
au moment où le dépôt apparaît en case 4. Seule la nouvelle arrivée en case 9 complète le lot 2.
Voir [Le terminal ME](../emi-transferts.md#terminal-me)
et [le test de retrait en jeu](../recette-fabrication.md#retrait-par-piles-dans-le-terminal-ae2).

Le contrat de retrait rejoue le clic **Tout**, la livraison entre le clic et la première prise et le rappel asynchrone de vérification. Pour un besoin de deux et quatre en cellule, il contrôle le bain inconnu, le bain vérifié et les deux prises propres à l’assistant.

Le contrat rejoue aussi un import compensant la première prise, suivi d’un export avant la
troisième, une baisse de 1 000 002 à 1 000 001 sans poste vérifiable et une livraison après
l’émission de la requête. Il mesure la cellule, le bain, les poches, le curseur et les
extractions natives effectuées. Pour trois cannes demandées et une livrée au bain, les poches
en reçoivent deux.
