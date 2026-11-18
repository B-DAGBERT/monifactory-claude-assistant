# Interface, navigation et réglages

## Points d’entrée

- [EcranAssistant](../../mod/src/main/java/fr/assistantia/EcranAssistant.java) assemble les onglets. [EtatReglages](../../mod/src/main/java/fr/assistantia/EtatReglages.java) suit les réglages en cours. [Theme](../../mod/src/main/java/fr/assistantia/Theme.java), [BarreDefilement](../../mod/src/main/java/fr/assistantia/BarreDefilement.java) et [PanneauAssistant](../../mod/src/main/java/fr/assistantia/PanneauAssistant.java) partagent les conventions d’affichage.
- [EcranConfigurationIA](../../mod/src/main/java/fr/assistantia/EcranConfigurationIA.java) et [EcranChoixModele](../../mod/src/main/java/fr/assistantia/EcranChoixModele.java) utilisent le [catalogue de modèles](../../monifactory_assistant/providers/catalog.py) et les [préférences IA](../../monifactory_assistant/providers/settings.py).
- [Contrôleur d’interface](../../monifactory_assistant/application/interface.py) et [vues du mod](../../monifactory_assistant/ui/views.py) relient les actions et les états Python aux écrans Forge.

## Règles à préserver

- Garder **Réglages** à droite, montrer l’état courant d’une bascule et laisser assez de place aux listes à petite résolution. Réutiliser les widgets communs.
- Échap revient au bon écran. Défilement, clic et relâchement restent dans le panneau qui les possède. Un clic lié à une ligne périmée ne déclenche pas l’action de la nouvelle ligne.
- Les réglages IA s’enregistrent contre la révision affichée. Conserver les autres clés et les préférences du fournisseur, y compris lors d’une reprise depuis la sauvegarde.

## Tests à lancer

```sh
python -m unittest tests.test_dashboard_layout tests.test_ai_settings_menu tests.test_ai_settings -v
```

En jeu, vérifier à petite résolution les menus imbriqués, Échap, les doubles clics et les zones partagées avec EMI. [Réglages en jeu](../utilisation.md#réglages)
