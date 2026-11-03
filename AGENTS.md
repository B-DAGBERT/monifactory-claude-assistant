# Travail dans ce dépôt

- `main` est la source commune de vérité pour Claude et Codex.
- À la demande de Damien, chaque modification terminée doit être validée, commitée, intégrée sur `main`, puis poussée sur `origin/main`. Ne pas publier de travail partiel ; respecter toute instruction contraire donnée dans la session.
- Préserver les configurations, conversations, objectifs, coffres et autres données locales ignorées par Git.
- Vérifier les tests adaptés, le formatage Python et Java, et compiler le mod si ses sources changent. Les commandes sont décrites dans `docs/developpement.md`.
- La mise à jour automatique ne remplace aucun fichier du mod pendant que Minecraft tourne. Les modifications Java nécessitent un redémarrage du jeu.
