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

Pour les versions suivantes, lancer **l’assistant avant Minecraft** : il vérifie `main` et installe
les nouveautés automatiquement, avec sauvegarde. S’il détecte le jeu ouvert, il reporte
l’installation et affiche un message dans le menu. Sans réseau, la version locale démarre.
Une installation existante doit utiliser **Mettre à jour** une première fois pour activer ce mécanisme.

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

## Objectif dans EMI

Dans **Y → Objectif** ou **Plan**, **Objectif EMI** met le craft demandé dans l’arbre
EMI : toutes les cibles avec leurs quantités, et les recettes intermédiaires choisies par
l’assistant. Le livre « Objectif » est une racine virtuelle qui regroupe les cibles ; il
n’est pas un objet à fabriquer. Clique sur les branches pour suivre leurs recettes dans EMI.

La synchronisation se fait au clic, sans appel IA. Après un changement d’objectif ou de
projet, clique à nouveau sur **Objectif EMI**. Réouvrir le même objectif conserve les choix
et les branches de l’arbre tant que tu n’as pas remplacé cet arbre dans EMI. Les favoris et
les recettes par défaut d’EMI ne sont pas modifiés. Un objectif en pause reste en pause.

Si la recette indiquée manque ou si plusieurs recettes sont possibles sans choix dans le
plan, un message indique les objets concernés : choisis leurs recettes directement dans EMI.
Les quantités transmises sont les cibles totales de l’objectif, pas seulement les manques.
EMI tient compte de ton inventaire ; il ne connaît pas les coffres mémorisés ni les machines
posées. L’assistant continue de suivre ces stocks. Échap revient à l’inventaire, puis au menu
assistant. Il faut relancer **l’assistant et Minecraft** après installation de cette version.

## Filons : scan, marquage et téléportation

Sélectionner un filon puis **Scanner** : le scan vise son centre GregTech, même si tu es
à la surface. **Arrêter** apparaît pendant le scan. Le scan autour du joueur a été retiré.

Le cache garde jusqu’à **100 000 blocs par monde**. Au plafond, les nouvelles observations
remplacent par lots les relevés de blocs minés, puis les blocs les moins récemment revus hors
de la zone scannée. Les blocs présents dans cette zone sont protégés. Les filons GregTech,
les choix d’affichage et les marquages « épuisé » restent conservés ; un ancien filon peut
être rescanné pour retrouver ses blocs. Il s’agit toujours de relevés partiels, pas du total du gisement.

Dans la fiche d’un filon, **Épuisé** ajoute un marquage personnel, enregistré pour le monde actif.
Il disparaît de la liste si les épuisés sont masqués. Pour annuler, choisir **Épuisés visibles**,
sélectionner le filon, puis **Rétablir**. Les relevés et le journal GregTech restent conservés.

**TP** cherche le point libre le plus proche au-dessus du filon, dans un voisinage de 8 blocs
horizontalement. Si aucun point libre n’est connu, la TP vise directement un bloc au-dessus
du centre, même en zone non chargée : **l’arrivée peut être dans la roche**. Aucun bloc n’est
creusé ou supprimé. Les commandes / permissions opérateur restent nécessaires.

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
