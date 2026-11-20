# Pont Forge, KubeJS, Python et isolation des mondes

## Points d’entrée

- [Pont](../../mod/src/main/java/fr/assistantia/Pont.java), [Ecritures](../../mod/src/main/java/fr/assistantia/Ecritures.java) et [IdentiteMonde](../../mod/src/main/java/fr/assistantia/IdentiteMonde.java) portent les échanges Forge et leur contexte.
- [Script client](../../jeu/assistant_ia.js) et [EchangesKubeJS](../../mod/src/main/java/fr/assistantia/EchangesKubeJS.java) exportent les observations. Le [script serveur](../../serveur/assistant_ia_serveur.js) est une source facultative.
- [Bridge](../../monifactory_assistant/game/bridge.py), [CommandQueue](../../monifactory_assistant/game/requests.py) et [WorldRouting](../../monifactory_assistant/game/world_scope.py) lisent, publient et valident les demandes.
- [Deconnexion](../../mod/src/main/java/fr/assistantia/Deconnexion.java) oublie à la déconnexion les références à la partie quittée que certains parcours ne remplaçaient qu’à leur prochain usage.

## Règles à préserver

- Vérifier la portée monde, session et dimension avant d’utiliser une demande ou un résultat. Après reconnexion, ignorer les accusés et exports de l’ancienne connexion.
- Réutiliser l’identité de la demande après un retard ou une nouvelle tentative. Un accusé durable empêche de rejouer l’action. Une erreur dans un parcours doit laisser les autres demandes traitables.
- Un relevé manquant ou illisible reste indisponible. Les observations historiques restent dans leur monde. Les migrations personnelles sont des commandes explicites.
- Une reprise d’écriture de `monde_memoire.json` conserve le chemin de la partition d’origine. Elle passe par la mémoire du monde et le contrôleur de jeu. La publication de `coffres_connus.json` utilise la portée active du pont. [Persistance de la mémoire du monde](base.md#règles-à-préserver)
- Si le script client change, vérifier le numéro de script et la compatibilité avec le JAR. Respecter les permissions de stockage du serveur et le fonctionnement client seul.

## Tests à lancer

```sh
python -m unittest tests.test_bridge_resilience tests.test_world_routing tests.test_world_identity tests.test_kubejs_exports -v
```

Rejouer une déconnexion et une reconnexion dans une copie pour vérifier le rejet d’un résultat ancien. [Contrat d’isolation](../isolation-mondes.md#identité-et-contexte)
