# Production et anciens projets

## Points d’entrée

- **Y → Base → Production…** lit le plan ou le diagnostic de l’arbre EMI choisi. Pour inclure un coffre, ouvrir son menu natif avant de demander le calcul.
- [EcranProduction.java](../../mod/src/main/java/fr/assistantia/EcranProduction.java), [StockProduction.java](../../mod/src/main/java/fr/assistantia/StockProduction.java) et [CatalogueProduction.java](../../mod/src/main/java/fr/assistantia/CatalogueProduction.java) capturent et attestent les sources. [service.py](../../monifactory_assistant/production/service.py) vérifie la demande et orchestre le calcul local.
- [report_lines.py](../../monifactory_assistant/production/report_lines.py) met en forme les allocations physiques, les manques et les attentes du plan calculé. Le résumé de Discussion reprend les libellés d’allocation.
- Les anciens onglets **Objectif** et **Projet**, la touche F7 et les actions de pause ou reprise ont été retirés. [interface.py](../../monifactory_assistant/application/interface.py) refuse explicitement les demandes d’un ancien mod.

## Règles à préserver

- Production reste en lecture seule. Un clic explicite lance un calcul local déterministe dans le contexte courant, avec la preuve de capture et du catalogue EMI. Les transferts et fabrications relèvent des commandes de jeu dédiées.
- Une preuve doit appartenir au bon monde, à la bonne connexion, à la bonne dimension et à l’arbre EMI actif. Un changement invalide les résultats. Une ancienne réponse encore lisible sur disque ne suffit pas.
- Un coffre pris en compte dans un calcul doit être attesté au même instant que les poches. Un clic ou une modification des cases révoque la preuve. Un relevé historique ou une capture incomplète laisse la quantité inconnue.
- Discussion fait attester la preuve dans le worker de la question. Le client retire l’acquittement publié dès qu’un tick constate que la capture est périmée, même si le catalogue est toujours prêt. Tk vérifie la liaison du résultat et relit l’acquittement avant de livrer le texte. Un verdict absent, faute d’accès à la boîte d’attestation, ne révoque pas le calcul. [Discussion et moteurs IA](discussion-ia.md#lecture-du-contexte-dune-question)
- Les quantités observées, réservations, lots attendus et réceptions ont chacun leur provenance. Une réception manuelle ou non attribuée ne devient pas une preuve native de Production.
- Conserver les sauvegardes historiques des projets et objectifs. Les anciens champs de projet peuvent survivre dans le protocole de compatibilité, sans réactiver ces actions.

## Tests à lancer

Lancer ces tests depuis la racine du dépôt avec Python et le JDK 17.

```sh
python -m unittest tests.test_production_menu tests.test_production_service tests.test_production_conversation
python -m unittest tests.test_production_stock_matrix
python -m unittest tests.test_native_production_catalog
python -m unittest discover -s tests -p 'test_native_production_lots.py'
```

Dans une copie du pack, calculer depuis un coffre ouvert, déplacer un objet puis vérifier l’invalidation. Rejouer avec un changement de branche EMI et une reconnexion. [Preuves natives et cas à rejouer](../qualification-v2-native-stock-lots.md), [isolation par monde](../isolation-mondes.md).
