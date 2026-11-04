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

## Coffres depuis une recette EMI

Sur une recette EMI, cliquer **Coffres** juste en dessous du craft : ses ingrédients objets
et ses outils sont surlignés en cyan dans les inventaires. Les coffres connus qui en contiennent
ont un contour et un faisceau cyan. Les variantes acceptées par la recette sont incluses ;
les fluides et les différences de NBT ne sont pas suivis par la mémoire des coffres.

Le bouton devient **Recherche…**, puis **Arrêter**. Cliquer à nouveau coupe le repérage ;
choisir une autre recette remplace la recherche. Celle-ci reste active après fermeture d’EMI,
pendant dix minutes au maximum, et s’efface à la déconnexion ou au changement de dimension.
Le repérage EMI prend temporairement la priorité sur les objets de l’objectif dans les inventaires.
Les quantités sont celles d’une fabrication : les ingrédients déjà dans le sac sont déduits.
Dès que la quantité d’un ingrédient est récupérée, ses faisceaux et sa surbrillance disparaissent.
Cet ingrédient reste validé pour cette recherche, même s’il est ensuite consommé, rangé ou perdu.
Les autres ingrédients restent recherchés ; quand tout est récupéré, tous les faisceaux s’éteignent.
Pour repartir sur une nouvelle collecte, arrêter la recherche puis cliquer de nouveau sur **Coffres**.

La recherche utilise les derniers contenus mémorisés, sans appel IA ni scan du monde.
Ouvrir un coffre actualise son contenu. Un message indique si aucun coffre connu ne correspond.
Seuls les 16 coffres les plus proches de la dimension actuelle sont signalés pour limiter le rendu.
Les questions dans Discussion et la pause de l’objectif ne sont pas modifiées.

Le panneau en haut à gauche montre désormais uniquement le **plan**, l’**objectif** et son
**avancement** : les listes d’objets à collecter et leurs quantités restent dans EMI et le menu.

## Récupérer les ingrédients depuis un coffre

Dans un coffre vanilla, une boîte de Shulker ou un coffre Sophisticated Storage, le panneau
au-dessus du coffre propose **Récupérer** (les ingrédients pour une fabrication) et
**Pour l’objectif** (les ingrédients pour le nombre de fabrications demandé par le plan).
Le panneau d’un établi conserve **Remplir / Tout** et son fonctionnement habituel.

Depuis une recette EMI ouverte avec un coffre, le bouton **+** devient une flèche **↓** :
elle récupère les ingrédients pour **une fabrication**, y compris avec Maj enfoncée.
Pour plusieurs fabrications de l’objectif, utiliser **Pour l’objectif** au-dessus du coffre.
Le bouton **Coffres** continue de servir au repérage, sans déplacer d’objet.

Le retrait déduit les objets déjà dans l’inventaire, respecte les variantes de la recette EMI
et ne prend que les quantités manquantes. Il fonctionne aussi si ce coffre ne contient qu’une
partie des ingrédients. Les outils réutilisables ne sont pas multipliés et les fluides sont
ignorés. Les upgrades, filtres et grilles de craft du coffre ne sont jamais des sources.

Les transferts utilisent les clics normaux du jeu, sans commande ni installation serveur.
Les clics répétés sont bloqués pendant le transfert ; fermer le coffre l’arrête. Si l’inventaire
est plein, le retrait s’arrête en conservant les objets déjà récupérés. Le stock est lu directement
dans le coffre ouvert, sans appel IA ni dépendance à la mémoire des coffres.

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
