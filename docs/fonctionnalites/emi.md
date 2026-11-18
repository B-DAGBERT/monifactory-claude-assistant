# EMI et arbre de fabrication

## Points d’entrée

- Choisir le produit et ses branches dans **Recipe Tree**. **Y → Recette** présente les étapes
  et les alternatives. Dans un établi, **Remplir** utilise le gestionnaire natif EMI.
- La sélection est lue par [ArbreEmi](../../mod/src/main/java/fr/assistantia/ArbreEmi.java).
  [InstantaneArbreEmi](../../mod/src/main/java/fr/assistantia/compat/emi/InstantaneArbreEmi.java)
  projette ce même arbre pour la collecte et les machines.
- L’ordre vient de [OrdonnancementEmi](../../mod/src/main/java/fr/assistantia/compat/emi/OrdonnancementEmi.java).
  Les variantes et catalyseurs passent par [ComparaisonEmi](../../mod/src/main/java/fr/assistantia/compat/emi/ComparaisonEmi.java)
  et [CatalyseursArbreEmi](../../mod/src/main/java/fr/assistantia/compat/emi/CatalyseursArbreEmi.java).

## Règles à préserver

- Une simple consultation d’EMI laisse les retraits inactifs. Un changement de branche ou de
  quantité recalcule les besoins de l’arbre sélectionné. Un clic périmé doit attendre la vue à jour.
- Allouer les composants prêts avant leurs matières premières. Chaque unité couvre une seule
  demande. Les sorties attendues réduisent les fabrications à prévoir et restent hors
  du stock transférable.
- La comparaison conserve les variantes exigées, dont le NBT. L’usure et la charge ne changent
  pas le type d’un outil GregTech. Un moule ou un outil réutilisable ne se multiplie pas par lot.
- Le circuit d’une recette GregTech est un réglage virtuel. Un circuit demandé comme résultat
  ou consommé par une recette reste un objet.
- Pour un moule proposé alors qu’il existe déjà, vérifier son identifiant exact et l’écran qui
  le demande. Le stock porté, le tampon relevé, le coffre ordinaire mémorisé et l’entrée d’une
  machine sont des sources différentes. Tester le moule absent, présent et remplacé par une
  autre variante.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest tests.test_emi tests.test_emi_backpack_tree tests.test_recipe_tab
```

Pour un changement de stock commun, rejouer le même cas dans EMI, Repérage, Réseau ME et
Envoyer. [Parcours stock](../scenarios.md#parcours-stock) · [Ordonnancement](../ordonnancement-machines.md)
