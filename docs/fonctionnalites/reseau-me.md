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
- Fermer le terminal invalide son relevé. Un curseur occupé empêche le départ. Après un arrêt,
  vérifier le curseur et le stock restant avant de reprendre.
- **Prendre** et **Tout** signalent à Suivi les quantités déposées dans les poches. Le passage
  par le curseur reste une étape intermédiaire. Après Échap, seul le dernier dépôt déjà envoyé
  peut encore être confirmé, y compris par la mise à jour serveur de la case dans le menu fermé.
  Un retrait ME ne prouve pas la machine d’origine.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest tests.test_me_terminal_panel tests.test_emi_backpack_tree
python -m monifactory_assistant.commands.scenarios parcours-stock
```

Dans une copie du pack, vérifier le retrait en piles, le changement de filtre et un tampon
exposé par bus de stockage. Voir [Le terminal ME](../emi-transferts.md#terminal-me)
et [le test de retrait en jeu](../recette-fabrication.md#retrait-par-piles-dans-le-terminal-ae2).
