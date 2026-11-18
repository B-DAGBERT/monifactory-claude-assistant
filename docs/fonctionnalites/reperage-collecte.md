# Repérage et collecte dans les coffres

## Points d’entrée

- Avec un **Recipe Tree** actif, activer **Repérage**. Ouvrir un coffre utile et utiliser
  **Prendre** pour l’icône choisie ou **Tout** pour les quantités retenues dans ce coffre.
- [RechercheEmi](../../mod/src/main/java/fr/assistantia/RechercheEmi.java) transmet les
  besoins et rapproche les lieux proposés par [chest_search](../../monifactory_assistant/game/emi_search.py).
  [AideCoffre](../../mod/src/main/java/fr/assistantia/AideCoffre.java) présente les choix et
  [RetraitCoffre](../../mod/src/main/java/fr/assistantia/RetraitCoffre.java) confirme les clics natifs.

## Règles à préserver

- Couvrir d’abord les composants déjà prêts, les poches, le backpack pris en compte et les tampons.
  Un tampon garde son retrait direct quand il est ouvert. Les coffres ordinaires ne proposent
  que le complément. Arrêter le repérage retire les contours et le panneau.
- Une quantité mémorisée peut indiquer où regarder. À l’ouverture et au clic, l’allocation
  est bornée par les vraies cases accessibles et la variante requise.
- Dans un coffre ouvert, **Collecte** lit les mB des seaux, cellules et fûts compatibles Forge.
  Déduire le liquide porté et les tampons relevés. Retirer le minimum de contenants entiers
  et garder le surplus, les contenants vides et les autres liquides. Un relevé ordinaire fermé
  ne garantit aucun volume.
- Préserver les acquisitions de la cible en cours et la déduction des composants transformés.
  Un changement de cible doit invalider une réponse ou un retrait devenu périmé.

## Tests à lancer

Depuis la racine du dépôt, dans l’environnement de développement avec JDK 17.

```sh
python -m unittest tests.test_chest_transfers tests.scenarios.test_basique
python -m monifactory_assistant.commands.scenarios parcours-stock
```

Le parcours de base couvre le seau partiel et le composant rangé dans le tampon. Pour vérifier
les menus natifs, suivre la [recette en jeu](../recette-fabrication.md#collecte-et-établi).
