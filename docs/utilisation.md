# Utiliser l’assistant dans Minecraft

[Retour au démarrage](../README.md) · [Collecte et machines EMI](emi-transferts.md)

## Discussion dans Minecraft

Dans **Y → Discussion**, les questions et les réponses sont séparées, avec un bouton **Copier**
pour chaque message. La molette, la barre de défilement et Page précédente / Page suivante
permettent de relire l’historique. **Derniers messages** ramène à la réponse en cours.

Le brouillon reste disponible quand tu fermes le menu ou redimensionnes le jeu, pendant cette
session Minecraft. Il est effacé uniquement après réception par l’assistant. Un second envoi
est bloqué pendant la transmission et la réponse ; tu peux préparer la question suivante.
Le statut indique la transmission, la recherche ou une erreur. Ces fonctions sont locales et
ne déclenchent aucun appel IA supplémentaire.

## Objectif dans EMI

Dans **Y → Objectif** ou **Projet**, **Objectif EMI** met le craft demandé dans l’arbre
EMI : toutes les cibles avec leurs quantités, et les recettes intermédiaires choisies par
l’assistant. Le livre « Objectif » est une racine virtuelle qui regroupe les cibles ; il
n’est pas un objet à fabriquer. Clique sur les branches pour suivre leurs recettes dans EMI.

La synchronisation se fait au clic, sans appel IA. Après un changement d’objectif ou de
projet, clique à nouveau sur **Objectif EMI**. Réouvrir le même objectif conserve les choix
et les branches de l’arbre tant que les cibles et leur avancement n’ont pas changé. Si
l’avancement a changé, l’import explicite reconstruit uniquement ce qu’il reste à obtenir. Les favoris et
les recettes par défaut d’EMI ne sont pas modifiés.

**Mettre en pause** arrête aussi l’arbre EMI actif, le scan des coffres, ses surbrillances et
un éventuel transfert en cours. Cela s’applique aussi à une pause demandée dans la discussion.
**Objectif EMI** reste désactivé pendant la pause. Après **Reprendre**, clique sur **Objectif EMI**
pour retrouver les choix de recettes conservés pendant cette session, puis réactive le scan si
nécessaire. Rien ne redémarre automatiquement. Tu peux toujours choisir un autre craft directement
dans EMI pendant que ton objectif reste en pause.

Si la recette indiquée manque ou si plusieurs recettes sont possibles sans choix dans le
plan, un message indique les objets concernés : choisis leurs recettes directement dans EMI.
L’import retire les cibles déjà obtenues, rangées ou posées, et tient compte de l’avancement
partiel. Le stock encore dans ton inventaire est inclus dans la quantité envoyée pour qu’EMI
le déduise lui-même, une seule fois. L’assistant conserve le suivi des coffres et machines posées. Échap revient à l’inventaire, puis au menu
assistant.

## Backpack comme inventaire

Le contenu des **Sophisticated Backpacks portés**, dans l’inventaire ou sur un emplacement **Curios**, compte dans l’arbre natif EMI et sa liste latérale, les besoins du scan, la planification des machines et l’avancement des objectifs. Un objet dans le backpack évite de le reprendre dans un coffre ou de planifier sa fabrication. Les sacs imbriqués sont suivis sans compter deux fois leur contenu.

La synchronisation fonctionne aussi sac fermé, via le mod déjà installé, sans appel IA. Pendant un déplacement, **Lecture du sac…** indique que le mod attend un relevé cohérent. Un sac posé au sol ou rangé dans un coffre sort de l’inventaire personnel.

La barre près de Recipe Tree propose **Coffres OFF / ON**, **Suivi** et **Sac N**.
Le soulignement vert signale le scan actif, le bleu les objets présents dans le sac.
Les boutons restent sur une seule ligne, avec une largeur calculée sur leur texte.
Si la place manque à côté de la recherche EMI, **Aide…** ouvre les trois actions.
Échap ou **Retour à EMI** retrouve l’écran précédent. Les infobulles précisent l’action
et la raison d’un bouton désactivé.

Le bouton **Sac N**, près de **Coffres** et **Suivi** dans EMI, ouvre la liste des
objets présents dans tes backpacks portés, avec leur quantité et une recherche par nom.
Le fond bleu rappelle qu’ils sont déjà sur toi. `N` est le nombre de types d’objets ; les grandes
valeurs sont abrégées. **Retour à EMI** ou Échap ramène au même écran.
Pendant la synchronisation, la liste attend les données du sac sans afficher l’ancien stock.

Cette option concerne le **comptage**. **Envoyer** continue à utiliser les cases accessibles de l’inventaire : le suivi indique **À sortir du sac** lorsque les ingrédients nécessaires sont dans le backpack. Le mod ne les extrait pas automatiquement.

## Coffres depuis l’arbre EMI

Choisir l’objectif et ses recettes dans **Recipe Tree**, puis cliquer sur **Coffres**,
en bas à gauche, juste à droite du bouton Recipe Tree. Le bouton indique **Coffres ON** :
un second clic désactive le repérage. Il remplace l’ancien bouton sous chaque recette.

La recherche suit **tout l’arbre choisi**, avec les composants intermédiaires,
les matières premières, les variantes et les quantités pour le nombre de lots de l’arbre.
Les outils réutilisables ne sont pas multipliés par le nombre de fabrications. Les fluides
et les différences de NBT ne sont pas suivis par la mémoire des coffres. Si tu modifies
l’arbre ou ses quantités après activation, le scan se recalcule automatiquement en conservant les objets acquis et les fabrications en machine.

La recherche réserve les stocks **de haut en bas de l’arbre**, en tenant compte de tous les
coffres connus de la dimension. Un composant disponible dans un coffre masque les matières
premières de sa branche, même si leur drawer est plus proche. Si la quantité disponible ne
suffit pas, seules les matières premières de la quantité manquante restent utiles. Les stocks
communs à plusieurs branches ne sont pas comptés plusieurs fois. Un composant simplement
repéré n’est pas considéré comme récupéré : si son coffre se vide, la recherche s’adapte.

Les objets manquants sont surlignés dans les inventaires et les coffres connus qui en contiennent
ont un contour cyan lumineux visible à travers les murs. Les ingrédients récupérés restent
validés pour cette recherche, même s’ils sont ensuite consommés, rangés ou perdus.
La recherche reste active après fermeture d’EMI, pendant dix minutes depuis son activation ou son dernier recalcul, et s’efface
à la déconnexion ou au changement de dimension. Elle utilise les contenus mémorisés,
sans appel IA ni scan du monde : ouvrir un coffre actualise son contenu.
Tous les stockages connus de la dimension sont examinés, sans limite à 16 coffres.
Seules les sources utiles sont signalées, en préférant les plus proches à niveau égal dans l’arbre.

Le panneau en haut à gauche garde uniquement le **plan**, l’**objectif** et son **avancement**.
Dans le menu de l’assistant, **Masquer** ferme l’avis de mise à jour et mémorise ce choix
localement, y compris après redémarrage. Une nouvelle version peut afficher un nouvel avis.

Quand toutes les cibles finales de l’arbre sont obtenues dans les quantités demandées, le mod
réinitialise l’arbre EMI, le scan, les contours et les transferts. La détection fonctionne aussi
avec Maj-clic et avec un résultat pris sur le curseur. Les cibles reçues en plusieurs lots
restent comptées après rangement ou utilisation ; il n’est pas nécessaire de détenir toute
la quantité à la fois. L’aperçu dans la case de résultat ne compte pas comme un objet fabriqué. Un composant intermédiaire ne termine jamais le craft principal.
Sans craft actif (arbre présent et mode fabrication EMI activé), aucun bouton de transfert n’est affiché, y compris la flèche dans les recettes
EMI ouvertes depuis un coffre. Effacer ou remplacer l’arbre coupe aussi l’ancien scan.

## Utiliser les machines de l’arbre EMI

Avec un craft EMI actif, les machines GregTech compatibles des chunks chargés, à moins de
48 blocs, sont entourées en **rouge**. Les recettes suivent les branches choisies dans EMI et
les composants déjà présents dans l’inventaire sont déduits. Un objectif IA seul ne déclenche
pas ce repérage.

Dans une machine compatible, le panneau de l’assistant, au même style que celui des coffres, propose les recettes utiles :

- Choisir le produit à préparer. Le titre indique les fabrications possibles et restantes.
- Survoler **Envoyer** pour voir les ingrédients, le circuit et les volumes de fluide.
- Cliquer **Envoyer** pour déposer un lot réalisable, limité par le stock et la place disponible.
  Les entrées existantes sont déduites, les sorties ne sont pas touchées. Le **Circuit setting**
  est vérifié comme réglage virtuel de la machine : aucun circuit physique à collecter ou à envoyer.
  Si le numéro est incorrect, **Circuit à régler** indique le numéro requis au survol. Le mod ne
  modifie pas ce réglage à ta place et n’utilise pas le NBT factice de l’aperçu EMI.
- **En cours de craft**, directement dans le panneau machine, réserve les résultats du lot déjà lancé.
  EMI masque les ingrédients des branches entièrement couvertes et garde le produit attendu.
  Les résultats restent à récupérer : cette réservation ne valide pas l’objectif.
- **Suivi**, près de **Coffres**, conserve l’accès aux étapes, à **Recette** et à **Annuler le lot**.
  Récupérer soi-même le résultat dans la machine.

Les fluides proviennent uniquement des seaux et cellules de l’inventaire. Les contenants sont
vidés par le clic natif GregTech puis rendus à l’inventaire. Le volume réel est affiché avant
l’envoi : par exemple, un seau de 1 000 mB peut dépasser un besoin de 144 mB. Les fluides déjà
présents dans les cuves d’entrée sont déduits. Les réservoirs voisins ne sont pas sollicités.

Le bouton affiche l’avancement de l’envoi, attend la confirmation des transferts et ignore les doubles clics. Fermer l’interface,
arrêter le craft ou mettre son objectif en pause interrompt la séquence. Une machine occupée,
un ingrédient manquant ou une entrée incompatible désactive l’envoi avec le motif au survol.
Les fabrications envoyées restent réservées dans ce suivi pour éviter de les proposer à nouveau
avant la récupération des sorties. Modifier l’arbre actif conserve ces réservations ; arrêter l’arbre les efface.
Les machines et cuves sans entrées accessibles dans leur interface ne permettent pas cet envoi.

À côté de **Coffres**, **Suivi** affiche les états **À collecter**, **À envoyer / À fabriquer**, **En machine**, **À récupérer** et **Terminé**. **En cours de craft** réserve le nombre de fabrications déjà lancées manuellement. Leurs résultats attendus et leurs ingrédients cessent d’être demandés par le suivi et le scan. **Recalculer** relit les besoins sans effacer l’avancement final ni les lots en attente. **Annuler le lot** libère sa réservation sans retirer les objets de la machine. Le fonctionnement et les limites sont détaillés dans [le guide EMI](emi-transferts.md).

Les composants transformés ne laissent plus un double crédit de matières premières. Les outils partagés sont prévus une seule fois, et les allers-retours du même résultat dans un coffre ne font plus progresser artificiellement la cible.

## Récupérer les ingrédients depuis un coffre

Dans un coffre vanilla, une boîte de Shulker ou un coffre Sophisticated Storage, le panneau
**Collecte** suit **les ingrédients du scan actif** : activer **Coffres** pour utiliser
**Prendre / Tout**. Cela fonctionne aussi sans objectif dans l’assistant. Un arbre simplement
consulté, un objectif seul ou un scan arrêté ne proposent aucun transfert. Le sélecteur montre les objets manquants disponibles dans ce
coffre, avec la quantité à prendre sur chaque icône ; ses flèches (ou la molette) parcourent les pages.

Le panneau ne recouvre jamais le coffre : il se place au-dessus quand il y a la place, sinon en
bloc large et bas sur le côté le plus libre, et EMI range ses listes d’objets autour de lui. Il se coupe dans
**Y → Réglages → Retrait depuis les coffres**, indépendamment du remplissage de l’établi.

- **Prendre** récupère la quantité manquante de l’objet sélectionné.
- **Tout** prend tous les objets sélectionnés par le scan et disponibles dans ce coffre,
  en se limitant aux quantités manquantes. Les objets sans rapport restent dans le coffre.

Les quantités suivent les branches encore nécessaires et le nombre de fabrications choisi dans EMI.
Un composant déjà possédé couvre sa branche : ses matières premières ne sont plus recherchées.
Le retrait utilise les quantités réservées par le scan dans tous les coffres connus : il ne
recalcule pas un nouvel arbre à partir du seul coffre ouvert. Si un composant prêt a été retenu
ailleurs, le bouton ne prend pas les matières ni les outils pour le refaire ici. Par exemple, un boîtier récupéré ne fait plus
chercher son fer dans les drawers. Une branche validée reste éteinte après consommation du composant.
L’inventaire, l’équipement et le curseur sont déduits par le scan. La mémoire des coffres
regroupe les objets par identifiant, sans distinguer leur NBT. Dans le coffre ouvert, le retrait
vérifie les variantes EMI et conserve les données des piles ; seul le Programmed Circuit ignore
le NBT factice. Un coffre mémorisé peut donc être signalé sans proposer de retrait si sa variante
n’est pas compatible.
Le retrait fonctionne même si le coffre ne fournit qu’une partie des ingrédients. Les outils
réutilisables ne sont pas multipliés ; fluides, upgrades, filtres et grilles de craft sont ignorés.
Sans scan actif ou sans objet sélectionné disponible, le panneau n’apparaît pas.

Depuis une recette EMI ouverte avec un coffre, la flèche **↓** permet aussi de **tout récupérer
pour le scan actif**. Il n’y a plus de mode « une fabrication » dans les coffres.
Le panneau d’un établi conserve **Remplir / Tout** et son fonctionnement habituel ; il se coupe
dans **Y → Réglages → Remplissage de l’établi**.
Le bouton **Coffres** prépare le repérage et la liste de retrait, sans déplacer d’objet.

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
dans le coffre ouvert ; la sélection vient du scan et des coffres mémorisés. Aucun appel IA
n’est nécessaire.

## Filons : scan, marquage et téléportation

Sélectionner un filon puis **Scanner** : le scan vise son centre GregTech, même si tu es
à la surface. Le pourcentage apparaît dans le statut du bas et sur le bouton du filon pendant
son scan. Le scan ponctuel s’arrête à la fin de son parcours.

Les deux lignes en haut regroupent **recherche / tri**, puis **Épuisés masqués / Vue / Blocs**.
Les boutons **Vue** et **Blocs** changent de valeur à chaque clic, avec sauvegarde locale immédiate :

- **Vue : Surface / Total** : Surface montre les contours visibles ; Total ajoute les minerais
  derrière la roche. Le bloc visé garde son contour de minage normal.
- **Blocs : 64 / 192 / 512 / 1 024 / 4 096** : plafond de blocs surlignés simultanément,
  en donnant la priorité aux plus proches. Le défaut est **192**.

Les modes concernent les blocs déjà relevés, encore présents et chargés dans un rayon de 64 blocs.
Ils respectent la sélection des types de minerai. L’intensité déjà enregistrée est conservée.
Le mode Surface est choisi par défaut pour les anciennes configurations. Il n’y a plus de
sous-menu Affichage/Options, de bouton global Tout masquer, de vidage manuel des relevés ni
de bouton Arrêter le scan. Chaque filon garde son propre bouton Afficher/Masquer.

Ce plafond concerne seulement l’affichage simultané : il ne réduit ni le scan ni les données enregistrées.

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

Dans **Réglages**, la liste défile à la molette ou en faisant glisser la barre de droite.
Les boutons **Modèles IA…** et **Diagnostic** restent fixes au-dessus de la liste.

## Machines regroupées dans Base

Les machines d’un même type apparaissent dans un groupe repliable avec leur quantité et un résumé
des états. Cliquer sur le groupe pour voir chaque emplacement et son propre état. Les tiers différents
restent séparés ; une machine unique apparaît directement. La recherche et les filtres continuent
de s’appliquer aux machines du groupe.

## Diagnostic et demandes en attente

**Y → Réglages → Diagnostic…** affiche la version du mod réellement chargée, la révision de
l’assistant, l’âge du relevé d’inventaire, la synchronisation du backpack, l’état du serveur,
l’arbre EMI, le scan des coffres, les transferts, la dernière demande et les erreurs relevées.
Le panneau se rafraîchit automatiquement et possède une pagination. **Retour** ou Échap revient
aux réglages. L’ouvrir ne lance aucune demande IA.

Avec shared.31 et l’assistant à jour, chaque clic destiné à l’assistant est enregistré séparément.
Une demande reste valable 45 secondes ; une action liée à un ancien projet ou une ancienne
dimension est refusée. Les demandes de questions faites pendant une réponse sont refusées,
pour éviter un second envoi involontaire. Une demande prise en compte n’implique pas que la
réponse IA ou une fabrication soit terminée.

Après une interruption pendant une action, le diagnostic indique que son résultat est incertain.
Vérifier son effet dans le jeu avant de la demander de nouveau : l’assistant ne la rejoue pas
seul. Les confirmations déjà enregistrées sont conservées après redémarrage.

Les sauvegardes locales disposent d’une copie `.bak` de leur version précédente valide. Si le
JSON principal devient illisible, cette copie est utilisée et la récupération apparaît dans le
diagnostic. Les copies restent personnelles et sont exclues de Git.

## Lots machines et résultats dans les coffres

Quand un envoi ne charge qu’une partie d’un lot d’au moins 64 résultats, une fenêtre propose :

- **Juste envoyé** : conserver au suivi la quantité réellement transférée.
- **Tout le lot** : suivre aussi le surplus que ton stockage alimentera ensuite.

**Confirmer** applique le choix. Le premier envoi est déjà enregistré ; **Tout le lot** agrandit
la même ligne et ne recompte pas cette première quantité. **Retour** ou Échap garde seulement
le lot réellement envoyé.

Pour **En cours de craft**, depuis une machine ou le suivi : **moins de 64 résultats** sont marqués
directement, sans question ; **64 résultats et plus** ouvrent le choix de quantité, avec **Tout le lot**.
Le seuil concerne les résultats objets garantis, en tenant compte du rendement de la recette,
et les doubles clics ne déclarent pas deux lots.

Les quantités en cours servent à masquer leurs ingrédients dans EMI. Elles restent des résultats
à récupérer pour **Coffres** : avec ce scan activé, un coffre utile contenant un résultat
attendu prend un **contour jaune**. Les autres coffres utiles gardent leur contour cyan. Ouvrir
le coffre puis utiliser **Prendre / Tout**, ou récupérer ses résultats normalement.

Le suivi passe à **À récupérer** quand le scan trouve un résultat disponible, puis progresse avec
les gains réels de l’inventaire, backpack compris. Un lot partiellement récupéré continue à chercher
le reste ; il devient **Terminé** lorsque toute sa quantité a été reçue. Observer un coffre ne
valide aucune production. Une pause de l’objectif arrête le repérage et garde les lots tant que le
monde et la connexion restent les mêmes ; la reprise reste explicite.

Un résultat réellement récupéré reste **acquis pour le craft en cours**, même après l’avoir posé
ou rangé ailleurs. Exemple : 64 récupérés sur 200 laissent **136 à récupérer** ; déplacer ensuite
ces 64 objets ne relance pas leur fabrication. EMI, le suivi machine et la recherche partagent
ce crédit. Les objets encore dans les poches ou le backpack ne sont jamais ajoutés deux fois.
Les crédits servent au calcul du travail déjà fait ; ils ne deviennent pas des cases transférables
ni des objets disponibles pour Envoyer. Ils suivent la durée de vie du craft et de ses lots.
