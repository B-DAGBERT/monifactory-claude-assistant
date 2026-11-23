# Repérage et collecte dans les coffres

## Points d’entrée

- Avec un **Recipe Tree** actif, activer **Repérage**. Ouvrir un coffre utile et utiliser
  **Prendre** pour l’icône choisie ou **Tout** pour les quantités retenues dans ce coffre.
- [RechercheEmi](../../mod/src/main/java/fr/assistantia/RechercheEmi.java) transmet les
  besoins dans `recherche_emi.json` et rapproche les lieux proposés dans `vue_mod.json`.
  [AideCoffre](../../mod/src/main/java/fr/assistantia/AideCoffre.java) présente les choix et
  [RetraitCoffre](../../mod/src/main/java/fr/assistantia/RetraitCoffre.java) confirme les clics natifs.
- À chaque rafraîchissement effectif, `InterfaceController.refresh_views` appelle
  `RecipeChestSearch.acquire` puis transmet le résultat à `build_mod_view`. L’acquisition
  relit une révision modifiée et retente une lecture instable au rafraîchissement suivant.
  La projection utilise ces données en mémoire. La publication suit les changements
  de la vue et le battement de 5 s.
  [Contrôleur](../../monifactory_assistant/application/interface.py) · [Acquisition](../../monifactory_assistant/game/emi_search.py) · [Vue](../../monifactory_assistant/ui/views.py)

## Règles à préserver

- Couvrir d’abord les composants déjà prêts, les poches, le backpack pris en compte et les tampons.
  Un tampon garde son retrait direct quand il est ouvert. Les coffres ordinaires ne proposent
  que le complément. Arrêter le repérage retire les contours et le panneau.
- Une quantité mémorisée peut indiquer où regarder. À l’ouverture et au clic, l’allocation
  est bornée par les vraies cases accessibles et la variante requise. Deux branches qui
  distinguent les NBT A et B réservent chacune leur variante. Une branche qui accepte les
  deux garde ce choix. Le relevé d’un coffre fermé indique un lieu par identifiant d’objet.
  Les poches, les tampons et les cases du coffre ouvert sont comparés selon les règles EMI
  de chaque branche. Les preuves différées de craft ou de retrait conservent la variante
  réellement reçue, même si le joueur l’a déjà consommée au relevé suivant.
- Dans un coffre ouvert, **Collecte** lit les mB des seaux, cellules et fûts compatibles Forge.
  Déduire le liquide porté et les tampons relevés. Retirer le minimum de contenants entiers
  en donnant la priorité aux cases dont le volume par contenant est le plus élevé. À volume
  égal, conserver l’ordre des cases. Garder le surplus, les contenants vides et les autres liquides. Un relevé ordinaire fermé
  ne garantit aucun volume.
- Préserver les acquisitions de la cible en cours et la déduction des composants transformés.
  Un changement de cible doit invalider une réponse ou un retrait devenu périmé.
- **Prendre** et **Tout** transmettent chaque retrait confirmé au suivi. Un seul lot machine
  compatible reçoit la quantité transférée. Si plusieurs lots correspondent, Suivi conserve
  la confirmation manuelle. Production exige toujours une preuve de provenance de la machine.
- Après Échap, confirmer uniquement le dernier déplacement déjà envoyé en vérifiant la case
  source et la destination dans les poches. Le reste du transfert s’arrête.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest tests.test_chest_transfers tests.scenarios.test_basique
python -m unittest discover -s tests -p test_collecte_variantes.py -v
python -m unittest tests.test_emi_search tests.test_emi_view_refresh tests.test_storage_snapshots
python -m monifactory_assistant.commands.scenarios parcours-stock
```

Le parcours de base couvre le seau partiel et le composant rangé dans le tampon. Pour vérifier
les menus natifs, suivre la [recette en jeu](../recette-fabrication.md#collecte-et-établi).

Le test de transfert de coffre rejoue 500/1 000, 1 000/500 et 500/500 mB pour un besoin de 1 000 mB. Après **Tout**, il contrôle les cases sources, le volume dans les poches et le curseur.
