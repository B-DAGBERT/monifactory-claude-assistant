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
- Pour l’onglet Recette, `MachinesEmi.planSuivi()` réunit l’arbre suivi, son instantané et
  les opérations utiles après couverture par les coffres connus. `AnalyseRecette` compare les
  identités de l’arbre et de l’instantané avant de reprendre ces opérations.
- `AnalyseRecette` signale l’absence d’un poste ou d’un type de machine par
  `OrdreMachines.Motif.POSTE_MANQUANT`. `OrdreMachines` transmet le motif à `RangsRecette`
  pour le statut, et la vue choisit le libellé court selon le type de poste.
  La tension insuffisante garde son statut de blocage distinct. Les constructeurs historiques
  de `Condition` et `Ligne` acceptent encore les deux anciens préfixes.

## Règles à préserver

- Une simple consultation d’EMI laisse les retraits inactifs. Un changement de branche ou de
  quantité recalcule les besoins de l’arbre sélectionné. Un clic périmé doit attendre la vue à jour.
- Allouer les composants prêts avant leurs matières premières. Chaque unité couvre une seule
  demande. Les sorties attendues réduisent les fabrications à prévoir et restent hors
  du stock transférable.
- La comparaison conserve les variantes exigées, dont le NBT. L’usure et la charge ne changent
  pas le type d’un outil GregTech. Un moule ou un outil réutilisable ne se multiplie pas par lot.
- Un moule GregTech de fonderie ou d’extrusion déjà repéré dans un coffre à portée, ou vu dans
  le dernier terminal AE2 de cette connexion et dimension, couvre sa branche de fabrication
  dans l’arbre EMI, Recette et le panneau machine. L’onglet Recette indique où le récupérer.
  **Repérage** et **Prendre** gardent leurs sources de retrait. **Envoyer** vérifie le moule
  dans les poches ou dans la bonne machine au moment du transfert.
- Le circuit d’une recette GregTech est un réglage virtuel. Un circuit demandé comme résultat
  ou consommé par une recette reste un objet.
- Pour un moule proposé alors qu’il existe déjà, vérifier son identifiant exact et l’écran qui
  le demande. Le stock porté, le tampon relevé, le coffre ordinaire mémorisé et l’entrée d’une
  machine sont des sources différentes. Tester le moule absent, présent et remplacé par une
  autre variante. **Réglages → Diagnostic…** détaille le comptage par plan. [DiagnosticMoules](../../mod/src/main/java/fr/assistantia/compat/emi/DiagnosticMoules.java)
- Les chemins rejoués à chaque tick ou toutes les 250 ms ne copient ni ne nomment les piles
  sans nécessité. `GregTechMachines` retrouve une seule fois par classe ses méthodes et champs,
  absence comprise : un câble ou un tuyau scanné par Repérage ne lève pas d’exception.
  `OrdonnancementEmi` compare le stock par clé EMI, NBT et provenance, sans texte ni registre,
  et laisse `FluidUtil` faire l’unique copie unitaire de sa sonde. `AideEcran` compare les
  comptes de l’écran, pas leur texte.
- Les boutons **Repérage**, **Suivi** et **Aide…** de la barre EMI sont rangés par écran dans des
  cartes faibles. Leur action ne capture jamais l’écran : elle retrouve au clic l’écran affiché qui
  les porte, sinon elle ne fait rien. Un écran fermé, son menu et, après une reconnexion, l’ancien
  niveau ne restent pas en mémoire. [CoffresArbreEmi](../../mod/src/main/java/fr/assistantia/compat/emi/CoffresArbreEmi.java)
- À la déconnexion, [Deconnexion](../../mod/src/main/java/fr/assistantia/Deconnexion.java) oublie
  l’écran de machine, son plan et sa commande de circuit, les reprises d’**Envoyer** et le retour
  depuis une recette EMI. La partie suivante repart comme au premier usage.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest tests.test_emi tests.test_emi_backpack_tree tests.test_recipe_tab
python -m unittest tests.test_recipe_followed_plan
python -m unittest tests.test_gregtech_reflexion
python -m unittest tests.test_emi_toolbar_screens tests.test_logout_world_references
```

`test_variantes_nbt_dans_emi_et_les_entrees_du_terminal` ouvre un réseau simulé avec les poches
vides et compare le manque de chaque branche EMI aux indices et quantités proposés par Réseau ME.
Deux A en cellule couvrent seulement la branche A. Un A et un B couvrent les deux branches dans
les deux ordres. Une branche qui accepte indifféremment A ou B utilise l’un ou l’autre.

Ce test rejoue également les deux variantes en poches, terminal fermé. Un gestionnaire EMI
peut réunir A et B dans une pile de deux portant le NBT du premier objet. Pour le calcul de
l’arbre, `InventaireArbreEmi.completer` retrouve la répartition dans les poches et la grille.
Les autres entrées du gestionnaire gardent leurs quantités. Si son entrée fusionnée dépasse
le total des variantes observées, seules les quantités dont le NBT est connu couvrent ces
branches. Si cette entrée est plus petite que le total physique, le calcul conserve la
sélection restreinte du gestionnaire.

Pour un changement de stock commun, rejouer le même cas dans EMI, Repérage, Réseau ME et
Envoyer. [Parcours stock](../scenarios.md#parcours-stock) · [Ordonnancement](../ordonnancement-machines.md)

Le contrat des moules rejoue douze fabrications avec un mauvais moule en coffre, puis le bon
moule en coffre ou dans le dernier relevé ME. Avec des moules vierges dans ME, il vérifie que
le mauvais moule laisse un seul vierge à prendre et que le bon moule en coffre supprime ce
retrait. Repérage conserve le retrait du moule formé. Le contrat vérifie aussi sa prise directe
si ME en possède un et la perte du relevé ME lors d’une nouvelle connexion.
