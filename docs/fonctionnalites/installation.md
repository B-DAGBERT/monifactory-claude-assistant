# Installation, versions et retour arrière

## Points d’entrée

- [Démarrage](../../monifactory_assistant/startup.py) vérifie la mise à jour sous le verrou de l’assistant. [AutoUpdater](../../monifactory_assistant/commands/auto_update.py) prépare le candidat dans un worktree temporaire.
- [Commande de mise à jour](../../monifactory_assistant/commands/update.py) gère la commande manuelle. [Installation](../../monifactory_assistant/commands/setup.py) compile, sauvegarde et remplace le JAR et le script client.
- [Version du mod](../../mod/gradle.properties) et [version du script KubeJS](../../jeu/assistant_ia.js) identifient deux composants. Le reçu d’installation relie le commit aux empreintes des fichiers.

## Règles à préserver

- Compiler avec JDK 17. Préparer le couple JAR et script, sauvegarder le couple précédent, puis n’installer que si Minecraft est fermé. Vérifier le commit et les empreintes du résultat.
- Avancer `main` sans écraser les modifications locales, les fichiers ignorés ou des commits divergents. Une installation interrompue doit pouvoir être reprise à partir du journal et de la sauvegarde.
- Garder les lanceurs Mac et Windows et le choix Codex ou Claude. Distinguer compilation, installation, chargement réel du JAR et vérification du SHA distant.
- Pour une régression de mise à jour, identifier le stade atteint dans le journal local avant de relancer ou de restaurer.

## Tests à lancer

```sh
python -m unittest tests.test_startup tests.test_auto_updates tests.test_updates tests.test_install_rollback -v
bash mod/gradlew -p mod checkJavaFormat build
```

Vérifier sur une copie la mise à jour, le refus avec jeu ouvert et le retour arrière. [Mises à jour](../installation.md#mises-à-jour-pour-les-deux-utilisateurs) · [Sauvegardes](../sauvegarde-donnees.md#test-de-restauration-et-retour-arrière)
