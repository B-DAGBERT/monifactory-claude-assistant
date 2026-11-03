# Assistant Monifactory — Claude et Codex

Un assistant dans Minecraft pour comprendre les blocs, préparer ses crafts, retrouver ses objets
et avancer étape par étape dans Monifactory. **main** est la branche commune et la source des mises à jour.
Claude ou Codex se choisit localement ; tes réglages, conversations, objectifs et coffres restent sur ton poste.

## Démarrer

1. Installer Python 3.12 avec Tk, Git, Java 17 et le CLI de Claude ou Codex connecté à ton compte.
2. Ouvrir **Choisir IA.command** sur Mac ou **Choisir IA.bat** sur Windows.
3. Fermer Minecraft, puis lancer **Installer Mac.command** ou **Installer.bat**.
4. Lancer **Lancer Mac.command** ou **Lancer assistant.bat**, puis Minecraft.
5. Dans le jeu : **Y** ouvre l’assistant, **I** explique le bloc ou l’objet.

Pour les versions suivantes, fermer l’assistant et le jeu, puis ouvrir **Mettre à jour**.

- [Installation, configuration et mises à jour](docs/installation.md)
- [Fonctionnement de Codex et suivi en lecture seule](docs/codex.md)
- [Organisation du code et développement](docs/developpement.md)
- [Description historique de la version Claude](docs/historique-claude.md)

## Discussion dans Minecraft

Dans **Y → Discussion**, les questions et les réponses sont séparées, avec un bouton **Copier**
pour chaque message. La molette, la barre de défilement et Page précédente / Page suivante
permettent de relire l’historique. **Derniers messages** ramène à la réponse en cours.

Le brouillon reste disponible quand tu fermes le menu ou redimensionnes le jeu, pendant cette
session Minecraft. Il est effacé uniquement après réception par l’assistant. Un second envoi
est bloqué pendant la transmission et la réponse ; tu peux préparer la question suivante.
Le statut indique la transmission, la recherche ou une erreur. Ces fonctions sont locales et
ne déclenchent aucun appel IA supplémentaire.

Après cette mise à jour, relancer **l’assistant et Minecraft** pour activer la nouvelle discussion.

## Organisation

```text
monifactory_assistant/      Application Python
  app.py                   Interface, progression et coordination du jeu
  commands/                Installation, mise à jour et choix de l’IA
  providers/               Adaptateurs Claude/Codex et transport Codex
  game/                    Projets et import des anciens relevés de filons
  platform/                Raccourcis Mac et voix Windows
  ui/                      Suivi de discussion en lecture seule
    web/                   HTML, CSS et JavaScript du panneau
mod/                       Mod Java Forge, sources et compilation Gradle
jeu/                       Script KubeJS client
serveur/                   Script KubeJS serveur facultatif
tests/                     Tests Python et scénarios Java
docs/                      Guides utilisateur et développeur
config/                    Exemple de configuration locale
```

Les lanceurs restent à la racine. Les quatre petits fichiers Python à côté sont des points d’entrée
compatibles avec les anciennes installations. Les données personnelles existantes gardent leur
emplacement pour préserver les sauvegardes ; elles sont exclues de Git.

## Développer

Créer une branche depuis `main`, puis proposer ses changements vers `main` après validation.

```sh
python -m pip install -r requirements-dev.txt
python -m ruff format .
python -m ruff check .
python -m unittest discover -s tests -v
python codex_setup.py build
```

Le panneau web et les guides se formatent avec `npm ci` puis `npm run format`.
Voir le [guide de développement](docs/developpement.md) pour les vérifications complètes.
