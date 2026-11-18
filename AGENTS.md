# Travail dans ce dépôt

- `main` est la source commune de vérité pour Claude et Codex.
- À la demande de Damien, chaque modification terminée doit être validée, commitée, intégrée sur `main`, puis poussée sur `origin/main`. Ne pas publier de travail partiel ; respecter toute instruction contraire donnée dans la session.
- Préserver les configurations, conversations, objectifs, coffres et autres données locales ignorées par Git.
- Vérifier les tests adaptés, le formatage Python et Java, et compiler le mod si ses sources changent. Les commandes sont décrites dans `docs/developpement.md`.
- Avant chaque publication, exécuter `python -m monifactory_assistant.commands.scenarios tous`. Un échec ou un test ignoré bloque cette vérification.
- Pour tout changement du stock, de l’allocation des besoins ou du suivi des fabrications, étendre le même scénario aux parcours concernés. Vérifier EMI, Repérage, Réseau ME et Envoyer quand ces quatre parcours consomment la règle modifiée. Réutiliser le calcul de stock et l’allocation communs. Les adaptateurs gardent les droits et les sources de leurs transferts natifs.
- Si les menus, les mixins ou les échanges natifs changent, rejouer les parcours affectés en jeu sur une instance de test. Utiliser la [recette du coffre tampon](docs/developpement.md#recette-coffre-tampon-en-jeu) pour les parcours de stock et consigner les cas réellement exécutés.
- La mise à jour automatique ne remplace aucun fichier du mod pendant que Minecraft tourne. Les modifications Java nécessitent un redémarrage du jeu.
