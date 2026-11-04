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
les recettes par défaut d’EMI ne sont pas modifiés.

**Mettre en pause** arrête aussi l’arbre EMI actif, le scan des coffres, ses surbrillances et
un éventuel transfert en cours. Cela s’applique aussi à une pause demandée dans la discussion.
**Objectif EMI** reste désactivé pendant la pause. Après **Reprendre**, clique sur **Objectif EMI**
pour retrouver les choix de recettes conservés pendant cette session, puis réactive le scan si
nécessaire. Rien ne redémarre automatiquement. Tu peux toujours choisir un autre craft directement
dans EMI pendant que ton objectif reste en pause.

Si la recette indiquée manque ou si plusieurs recettes sont possibles sans choix dans le
plan, un message indique les objets concernés : choisis leurs recettes directement dans EMI.
Les quantités transmises sont les cibles totales de l’objectif, pas seulement les manques.
EMI tient compte de ton inventaire ; il ne connaît pas les coffres mémorisés ni les machines
posées. L’assistant continue de suivre ces stocks. Échap revient à l’inventaire, puis au menu
assistant. Il faut relancer **l’assistant et Minecraft** après installation de cette version.

## Coffres depuis l’arbre EMI

Choisir l’objectif et ses recettes dans **Recipe Tree**, puis cliquer sur **Scanner coffres**,
en bas à gauche, juste à droite du bouton Recipe Tree. Le bouton devient **Arrêter scan** :
un second clic désactive le repérage. Il remplace l’ancien bouton sous chaque recette.

La recherche prend un instantané de **tout l’arbre choisi**, avec les composants intermédiaires,
les matières premières, les variantes et les quantités pour le nombre de lots de l’arbre.
Les outils réutilisables ne sont pas multipliés par le nombre de fabrications. Les fluides
et les différences de NBT ne sont pas suivis par la mémoire des coffres. Si tu modifies
l’arbre ou ses quantités après activation, désactive puis réactive le scan pour le reprendre.

La recherche réserve les stocks **de haut en bas de l’arbre**, en tenant compte de tous les
coffres connus de la dimension. Un composant disponible dans un coffre masque les matières
premières de sa branche, même si leur drawer est plus proche. Si la quantité disponible ne
suffit pas, seules les matières premières de la quantité manquante restent utiles. Les stocks
communs à plusieurs branches ne sont pas comptés plusieurs fois. Un composant simplement
repéré n’est pas considéré comme récupéré : si son coffre se vide, la recherche s’adapte.

Les objets manquants sont surlignés dans les inventaires et les coffres connus qui en contiennent
ont un contour cyan lumineux visible à travers les murs. Les ingrédients récupérés restent
validés pour cette recherche, même s’ils sont ensuite consommés, rangés ou perdus.
La recherche reste active après fermeture d’EMI, pendant dix minutes au maximum, et s’efface
à la déconnexion ou au changement de dimension. Elle utilise les contenus mémorisés,
sans appel IA ni scan du monde : ouvrir un coffre actualise son contenu.
Tous les stockages connus de la dimension sont examinés, sans limite à 16 coffres.
Seules les sources utiles sont signalées, en préférant les plus proches à niveau égal dans l’arbre.

Le panneau en haut à gauche garde uniquement le **plan**, l’**objectif** et son **avancement**.
Dans le menu de l’assistant, **Masquer** ferme l’avis de mise à jour et mémorise ce choix
localement, y compris après redémarrage. Une nouvelle version peut afficher un nouvel avis.

Quand toutes les cibles finales de l’arbre sont obtenues dans les quantités demandées, le mod
réinitialise l’arbre EMI, le scan, les contours et les transferts. La détection fonctionne aussi
avec Maj-clic et avec un résultat pris sur le curseur ; l’aperçu dans la case de résultat ne compte
pas comme un objet fabriqué. Un composant intermédiaire ne termine jamais le craft principal.
Sans craft actif (arbre présent et mode fabrication EMI activé), aucun bouton de transfert n’est affiché, y compris la flèche dans les recettes
EMI ouvertes depuis un coffre. Effacer ou remplacer l’arbre coupe aussi l’ancien scan.

## Récupérer les ingrédients depuis un coffre

Dans un coffre vanilla, une boîte de Shulker ou un coffre Sophisticated Storage, le panneau
au-dessus du coffre suit **le craft actif d’EMI**, même sans objectif dans l’assistant et sans
activer le repérage des coffres. Un arbre simplement consulté ou conservé après l’arrêt du craft
ne propose aucun transfert. **Objectif EMI** et **Scanner coffres** activent explicitement le craft ;
l’objectif seul n’active ni récupération ni surbrillance. Le sélecteur montre les objets manquants disponibles dans ce
coffre ; ses flèches permettent de parcourir plus de huit objets.

- **Transférer les items** récupère la quantité manquante de l’objet sélectionné.
- **Tout récupérer** prend tous les objets utiles à l’arbre disponibles dans ce coffre,
  en se limitant aux quantités manquantes. Les objets sans rapport restent dans le coffre.

Les quantités suivent les branches encore nécessaires et le nombre de fabrications choisi dans EMI.
Un composant déjà possédé couvre sa branche : ses matières premières ne sont plus recherchées.
Lors d’un transfert, les composants disponibles dans le coffre sont pris en priorité ; seuls les
composants manquants demandent leurs ingrédients. Par exemple, un boîtier récupéré ne fait plus
chercher son fer dans les drawers. Une branche validée reste éteinte après consommation du composant.
L’inventaire est déduit et les variantes sont comparées avec les règles d’EMI, y compris le NBT.
Le retrait fonctionne même si le coffre ne fournit qu’une partie des ingrédients. Les outils
réutilisables ne sont pas multipliés ; fluides, upgrades, filtres et grilles de craft sont ignorés.
Sans arbre actif ou sans objet manquant disponible, le panneau n’apparaît pas.

Depuis une recette EMI ouverte avec un coffre, la flèche **↓** permet aussi de **tout récupérer
pour l’arbre courant**. Il n’y a plus de mode « une fabrication » dans les coffres.
Le panneau d’un établi conserve **Remplir / Tout** et son fonctionnement habituel.
Le bouton **Scanner coffres** sert uniquement au repérage, sans déplacer d’objet.

L’onglet **Objectif** affiche l’objectif, son avancement, les cibles finales et les machines
nécessaires. Les détails de fabrication sont dans **Objectif EMI**.

Les transferts utilisent les clics du jeu, avec le mécanisme natif de Sophisticated Storage
pour ses coffres, sans commande ni installation serveur. Un transfert réussi est silencieux.
Les clics répétés sont bloqués pendant le transfert ; fermer le coffre l’arrête.
Les mises à jour d’objets sans rapport (par exemple la charge d’un outil électrique) n’interrompent
pas le retrait. Après un déplacement, le mod attend jusqu’à trois secondes la synchronisation du
serveur, sans renvoyer les clics. Si le contenu reste incohérent, il s’arrête et consigne le slot
concerné dans le journal. Les retraits partiels réduisent le nombre de clics envoyés. Si l’inventaire
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
