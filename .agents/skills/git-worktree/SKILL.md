---
name: git-worktree
description: Isoler un chantier Monifactory dans un worktree ou coordonner des branches parallèles lorsque des modifications locales, plusieurs agents ou le checkout de jeu risquent de se chevaucher.
---

# Isoler le travail Git

Lire `AGENTS.md` et `docs/collaboration.md` depuis la racine Git. Identifier le checkout de l’utilisateur, puis les changements suivis, indexés et non suivis avec `git status --short`, `git diff --stat` et `git diff --cached --stat`. `git worktree list --porcelain` indique les branches déjà utilisées. Laisser le checkout de jeu à sa place.

- Choisir une référence de départ vérifiée et noter son SHA. Vérifier l’état distant si la tâche dépend d’un `main` à jour. Réutiliser un worktree approprié de la tâche ou en créer un dans un dossier voisin neuf. La commande suivante crée un exemple de branche depuis une base `main` vérifiée.

  ```sh
  git worktree add -b codex/sujet ../monifactory-sujet main
  ```

- Dans un worktree partagé, les agents voient immédiatement les éditions des autres. Répartir les fichiers, se prévenir des changements de contrats et désigner un intégrateur. Pour de vrais travaux indépendants, choisir des worktrees distincts.
- Tester avec des fixtures ou dossiers temporaires. Les fichiers ignorés restent locaux à chaque checkout, dont `assistant.local.json`, les sessions, plans, stocks et sauvegardes. Vérifier `monifactory_assistant/paths.py` avant d’exécuter un outil d’écriture. Un lanceur d’installation ou de mise à jour peut viser l’instance CurseForge.
- Préserver le travail existant pendant les conflits. Comparer les deux historiques, comprendre le contrat modifié de chaque côté et vérifier le résultat combiné. Les opérations d’intégration et de publication suivent les autorisations de la session.
- Rapporter branche, SHA de départ et d’arrivée, périmètre et vérifications. Si le retrait du worktree est demandé, inventorier aussi ses fichiers ignorés et non suivis. Retirer seulement le dossier concerné, propre et sans donnée à conserver, par la commande non forcée.
