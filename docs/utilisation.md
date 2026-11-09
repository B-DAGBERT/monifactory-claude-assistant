# Utiliser l’assistant dans Minecraft

[Retour au démarrage](../README.md) · [Collecte et machines EMI](emi-transferts.md)

## Discussion dans Minecraft

Dans **Y → Discussion**, les questions et les réponses sont séparées, avec un bouton **Copier**
pour chaque message. La molette, la barre de défilement (que l’on peut faire glisser) et les
touches Page précédente / Page suivante permettent de relire l’historique. **Derniers messages**
ramène à la réponse en cours.

Le brouillon reste disponible quand tu fermes le menu ou redimensionnes le jeu, pendant cette
session Minecraft. Il est effacé uniquement après réception par l’assistant. Un second envoi
est bloqué pendant la transmission et la réponse ; tu peux préparer la question suivante.
La ligne de statut, au-dessus de la question, indique le moteur, la transmission, la recherche ou
une erreur. Ces fonctions sont locales et ne déclenchent aucun appel IA supplémentaire.

Si l’assistant est arrêté, chaque onglet affiche **● Assistant arrêté** avec **Relancer
l’assistant**, et le champ de question le rappelle en gris. Recette et Filons continuent de
fonctionner sans lui.

Pour une recette ou un objet reconnu dans ta question, l’assistant joint les stocks et les
machines utiles. Ajoute **contexte complet** dans la question si tu veux aussi lui donner
les autres relevés et événements. Un état historique indique ce qui a été observé à cette date.

La discussion ne crée plus d’objectif et ne met rien en pause. Aucune demande IA ne part sans
action de ta part, même à la connexion.

## Onglet Recette

**Y** s’ouvre sur **Recette**, le premier onglet. Il analyse l’arbre choisi dans **EMI Recipe
Tree** et remplace les anciens onglets Objectif et Projet ; la touche F7 et l’objectif préparé
par l’IA ont disparu. Le calcul est local, sans appel IA, et rien ne change dans l’arbre sans
clic sur **Appliquer** ou **Annuler**. Sans arbre, l’onglet invite à choisir un objet dans Recipe
Tree.

L’en-tête montre l’objet final, sa quantité, le nombre d’étapes et celles qui sont prêtes. Un clic
sur une icône ouvre ses recettes dans EMI.

- **Plan** : les fabrications restantes, numérotées dans l’ordre où les faire. Chaque carte
  indique le produit, le poste (établi ou machine), le circuit, les entrées et l’état, par exemple
  **Prête**, **Machine à poser**, **Attend l’étape 2**, **Manque …** ou **En machine**. La première
  étape où tu peux agir porte une **★**. Le survol détaille l’étape et explique sa place.
- **Meilleures alternatives** : une autre recette pour un produit de l’arbre, objet final compris,
  avec le gain en clair, par exemple « 1 Iron Ingot au lieu de 2 par Iron Plate · 2 étapes au lieu
  de 12 ». **Appliquer** la choisit dans cet arbre seulement ; **Annuler : …** rétablit le choix
  précédent. Pour l’objet final, la quantité visée est conservée. Les favoris et les recettes par
  défaut d’EMI ne sont jamais modifiés.
- **Introuvable** : une matière de base de l’arbre absente de l’inventaire, du sac, des coffres
  connus et des lots en cours. C’est une simple information, sans bouton.
- **Machine à fabriquer** : une machine que tu n’as pas encore, faisable à l’établi avec ce que
  tu as (« tu as tout » ou « 2 sous-crafts avec ce que tu as »), et qui permettrait une meilleure
  recette.

Une alternative n’est proposée que si elle est **strictement meilleure** : moins de matière sans
plus d’une étape supplémentaire, autant de matière ou moins avec moins d’étapes, ou au moins deux
fois moins d’étapes (sur une chaîne d’au moins quatre) sans plus de matière au total. Ses postes
et ses outils réutilisables (moules, lentilles, formes d’extrudeuse) doivent être posés, portés ou
rangés dans un coffre connu, et elle ne doit pas demander de matière introuvable. Les
sous-ingrédients suivent les choix actuels de l’arbre, sinon les défauts d’EMI.
**Machine à fabriquer** n’apparaît que si tu n’as aucune machine de ce type et qu’elle se fabrique
à l’établi avec l’inventaire, le sac et les coffres connus, avec au plus un niveau de sous-craft.
Une montée de tier n’est jamais proposée : si une étape demande plus de tension que ta machine, elle
indique « Tension insuffisante (Basic Bender possédé) », et aucune machine au-delà de ta plus forte
tension n’est nommée ni proposée.

Si le plan ne peut pas encore être calculé (inventaire ou sac pas encore synchronisés, par exemple),
l’onglet affiche **Plan non calculé** avec la raison au lieu de « Rien à fabriquer ». Une lecture
impossible de l’arbre EMI affiche « Arbre EMI illisible · détails dans le journal ».

Dans le monde, les machines entourées en rouge affichent le numéro de leur étape. La **★** dorée
marque la plus proche machine de la première étape machine à faire, un numéro grisé signale une
étape qui ne peut pas encore se faire et « 3 · en cours » ou « 3 · prêt » suit un lot déjà envoyé.
Au plus 16 machines, les plus proches, sont numérotées. Ces repères n’apparaissent que si le mode
fabrication de Recipe Tree ou **Coffres** est actif ; sinon, l’onglet le rappelle.

La prochaine étape s’affiche aussi dans le panneau en haut à gauche, par exemple
**★ Basic Bender** puis « 4 Iron Ingot → 4 Iron Plate ». **F9** ou **Y → Réglages → Panneau en
jeu** masque ce panneau ; les réponses de l’assistant restent dans leur bulle.

Limites : les coûts sont comparés par unité produite et peuvent être fractionnaires ; un tag compte
pour sa première variante ; un multibloc est supposé assez puissant ; « facile » ignore le NBT.
Changer d’objet dans Recipe Tree efface l’historique d’**Annuler**, limité aux huit derniers
choix.

## Backpack comme inventaire

Le contenu des **Sophisticated Backpacks portés**, dans l’inventaire ou sur un emplacement **Curios**, compte dans l’arbre natif EMI et sa liste latérale, les besoins du scan, la planification des machines et l’onglet Recette. Un objet dans le backpack évite de le reprendre dans un coffre ou de planifier sa fabrication. Les sacs imbriqués sont suivis sans compter deux fois leur contenu.

La synchronisation fonctionne aussi sac fermé, via le mod déjà installé, sans appel IA. Pendant un déplacement, **Lecture du sac…** indique que le mod attend un relevé cohérent. Un sac posé au sol ou rangé dans un coffre sort de l’inventaire personnel.

La barre près de Recipe Tree propose **Coffres OFF / ON**, **Suivi** et **Sac N**.
**Coffres ON** s’affiche en doré pendant le scan ; **Sac N** compte les types d’objets du sac.
Les boutons restent sur une seule ligne, avec une largeur calculée sur leur texte.
Si la place manque à côté de la recherche EMI, **Aide…** ouvre les trois actions.
Échap ou **Retour à EMI** retrouve l’écran précédent. Les infobulles précisent l’action
et la raison d’un bouton désactivé. Un clic sur cette barre ou sur un panneau de l’assistant,
même sur un bouton grisé, ne fait jamais tomber ni poser l’objet tenu au curseur ; revenir de
**Suivi**, **Sac** ou d’un menu non plus.

Le bouton **Sac N**, près de **Coffres** et **Suivi** dans EMI, ouvre la liste des
objets présents dans tes backpacks portés, chacun sur une carte avec sa quantité, et une
recherche par nom. Le sous-titre rappelle qu’ils sont déjà sur toi et comptés dans EMI. `N` est
le nombre de types d’objets ; les grandes valeurs sont abrégées. **Retour à EMI** ou Échap ramène
au même écran.
Pendant la synchronisation, la liste attend les données du sac sans afficher l’ancien stock.

Cette option concerne le **comptage**. **Envoyer** continue à utiliser les cases accessibles de l’inventaire : le suivi indique **À sortir du sac** lorsque les ingrédients nécessaires sont dans le backpack. Le mod ne les extrait pas automatiquement.

## Coffres depuis l’arbre EMI

Choisir l’objet et ses recettes dans **Recipe Tree**, puis cliquer sur **Coffres**,
en bas à gauche, juste à droite du bouton Recipe Tree. Le bouton indique **Coffres ON**, en doré :
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

Le panneau en haut à gauche montre seulement la prochaine étape de l’onglet **Recette**.
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
les composants déjà présents dans l’inventaire sont déduits. Les contours portent aussi le numéro
de leur étape dans l’onglet **Recette**.

Dans une machine compatible, le panneau de l’assistant, au même style que celui des coffres, propose les recettes utiles :

- Choisir le produit à préparer. Le titre indique les fabrications possibles et restantes ;
  pour un lot en cours, la deuxième ligne compte les résultats reçus, par exemple « 2/8 récupérés ».
- Survoler **Envoyer** pour voir les ingrédients, le circuit et les volumes de fluide.
- Cliquer **Envoyer** pour déposer un lot réalisable, limité par le stock et la place disponible.
  Les entrées existantes sont déduites, les sorties ne sont pas touchées. Le **Circuit setting**
  est vérifié comme réglage virtuel de la machine : aucun circuit physique à collecter ou à envoyer.
  Si le numéro est incorrect, **Circuit à régler** indique le numéro requis au survol. Le mod ne
  modifie pas ce réglage à ta place et n’utilise pas le NBT factice de l’aperçu EMI.
- **Autre priorité** signale une recette encore envoyable dont le stock est d’abord prévu pour une
  autre recette ; la choisir reste possible.
- **En cours de craft**, directement dans le panneau machine, réserve les résultats du lot déjà lancé.
  EMI masque les ingrédients des branches entièrement couvertes et garde le produit attendu.
  Les résultats restent à récupérer : cette réservation ne termine pas le craft.
- **Suivi**, près de **Coffres**, conserve l’accès aux étapes, à **Recette** et à **Annuler le lot**.
  Récupérer soi-même le résultat dans la machine.

Les fluides proviennent uniquement des seaux et cellules de l’inventaire. Les contenants sont
vidés par le clic natif GregTech puis rendus à l’inventaire. Le volume réel est affiché avant
l’envoi : par exemple, un seau de 1 000 mB peut dépasser un besoin de 144 mB. Les fluides déjà
présents dans les cuves d’entrée sont déduits. Les réservoirs voisins ne sont pas sollicités.

Le bouton affiche l’avancement de l’envoi, attend la confirmation des transferts et ignore les doubles clics. Fermer l’interface
ou arrêter le craft interrompt la séquence. Une machine occupée,
un ingrédient manquant ou une entrée incompatible désactive l’envoi avec le motif au survol.
Les fabrications envoyées restent réservées dans ce suivi pour éviter de les proposer à nouveau
avant la récupération des sorties. Modifier l’arbre actif conserve ces réservations ; arrêter l’arbre les efface.
Les machines et cuves sans entrées accessibles dans leur interface ne permettent pas cet envoi.

À côté de **Coffres**, **Suivi** affiche les états **À collecter**, **À envoyer / À fabriquer**, **En machine**, **À récupérer** et **Terminé**. **En cours de craft** réserve le nombre de fabrications déjà lancées manuellement. Leurs résultats attendus et leurs ingrédients cessent d’être demandés par le suivi et le scan. **Recalculer** relit les besoins sans effacer l’avancement final ni les lots en attente. **Annuler le lot** libère sa réservation sans retirer les objets de la machine. Le fonctionnement et les limites sont détaillés dans [le guide EMI](emi-transferts.md).

Les composants transformés ne laissent plus un double crédit de matières premières. Les outils partagés sont prévus une seule fois, et les allers-retours du même résultat dans un coffre ne font plus progresser artificiellement la cible.

## Récupérer les ingrédients depuis un coffre

Dans un coffre vanilla, une boîte de Shulker ou un coffre Sophisticated Storage, le panneau
**Collecte** suit **les ingrédients du scan actif** : activer **Coffres** pour utiliser
**Prendre / Tout**. Un arbre simplement consulté ou un scan arrêté ne propose aucun transfert.
Le sélecteur montre les objets manquants disponibles dans ce
coffre, avec la quantité à prendre sur chaque icône ; ses flèches (ou la molette) parcourent les pages.

Le panneau ne recouvre jamais le coffre : il se place au-dessus quand il y a la place, sinon en
bloc large et bas sur le côté le plus libre, et EMI range ses listes d’objets autour de lui. Il se coupe dans
**Y → Réglages → Retrait depuis les coffres**, indépendamment du remplissage de l’établi.
Sur une petite fenêtre, un libellé trop long est coupé par « … » et reste lisible au survol.

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

Depuis une recette EMI ouverte avec un coffre, la flèche verte **↓** permet aussi de **tout récupérer
pour le scan actif** ; elle est grisée quand ce coffre n’a rien à donner. Il n’y a plus de mode
« une fabrication » dans les coffres.
Le panneau d’un établi a le même aspect que ceux des coffres et des machines. Il propose
**Remplir / Tout** pour les recettes d’établi de l’arbre EMI actif et se place au-dessus ou à
côté de l’interface, jamais sur la grille. Sans arbre EMI actif, il n’affiche rien. Il se coupe
dans **Y → Réglages → Remplissage de l’établi**.
Le bouton **Coffres** prépare le repérage et la liste de retrait, sans déplacer d’objet.

Les transferts utilisent les clics du jeu, avec le mécanisme natif de Sophisticated Storage
pour ses coffres, sans commande ni installation serveur. Un transfert réussi est silencieux.
Pendant un retrait, le panneau indique **Retrait…** et les clics répétés sont bloqués ; fermer le
coffre l’arrête.
Les mises à jour d’objets sans rapport (par exemple la charge d’un outil électrique) n’interrompent
pas le retrait. Après un déplacement, le mod attend jusqu’à trois secondes la synchronisation du
serveur, sans renvoyer les clics. Si le contenu reste incohérent, il s’arrête et consigne le slot
concerné dans le journal. Les retraits partiels réduisent le nombre de clics envoyés. Si l’inventaire
est plein, le retrait s’arrête en conservant les objets déjà récupérés. Le stock est lu directement
dans le coffre ouvert ; la sélection vient du scan et des coffres mémorisés. Aucun appel IA
n’est nécessaire.

## Filons : scan complet, surbrillance et téléportation

**Y → Filons** liste les gisements GregTech connus de la dimension actuelle. Sélectionner un filon
puis **Scanner** : le scan parcourt tout le volume où le générateur GregTech de ce filon peut placer
ses minerais. Pour un générateur inconnu, il couvre un cube autour du centre puis suit le filon de
proche en proche, en tolérant des trous de 2 blocs. Le bouton affiche **Scan 63 %** ; un clic
l’arrête en gardant les minerais trouvés. Les zones non chargées restent en attente, même après
un redémarrage, et se scannent dès que tu t’en approches (« zones à charger : approche-toi »).

La fiche du filon indique **X restants sur Y trouvés (Z %)**, avec une barre, puis les minerais par
type. Le compte suit le minage en direct dans les chunks chargés : les minerais connus y sont
revérifiés en continu, et les plus proches du filon surligné à chaque tick. Un minerai miné reste
compté dans les trouvés. Un relevé de plus de dix minutes est signalé comme ancien. La discussion
reçoit les mêmes comptes.

Quand il reste au plus **10 %** des minerais trouvés (au moins 16 trouvés, après un scan complet
et sans zone en attente), la liste affiche **presque épuisé** et **Épuisé** devient le bouton vert.
Un filon vu seulement en partie (ancien relevé, scan arrêté) n’est jamais déclaré presque épuisé :
la fiche indique « Peu de minerai vu : scanne pour confirmer. » et **Scanner** devient le bouton
principal. Le filon n’est jamais marqué tout seul. **Épuisé** ajoute un marquage personnel, enregistré pour le monde actif :
le filon disparaît de la liste si les épuisés sont masqués. Pour annuler, choisir **Épuisés
visibles**, sélectionner le filon, puis **Rétablir**. Les relevés et le journal GregTech restent
conservés.

La première ligne regroupe la recherche et le tri : **Tri : distance**, **Tri : recette** ou
**Tri : nom**. **Tri : recette** place en tête, avec une **★** cyan, les filons qui fournissent une
matière première de l’arbre choisi dans EMI ; sans arbre, l’ordre reste celui de la distance.
La deuxième ligne contient **Épuisés masqués / visibles** puis les réglages de surbrillance,
enregistrés localement à chaque clic. Sur une petite fenêtre, ceux qui ne tiennent pas passent dans
**Affichage…** :

- **Vue : Surface / Total** : Surface montre les minerais visibles ; Total ajoute ceux derrière la
  roche. Le bloc visé garde son contour de minage normal.
- **Blocs : 64 / 192 / 512 / 1 024 / 4 096** : plafond de blocs surlignés simultanément, en
  donnant la priorité aux plus proches. Le défaut est **512** pour une nouvelle installation ; un
  réglage existant est conservé. Ce plafond ne réduit ni le scan ni les données enregistrées.
- **Épaisseur : Fine / Normale / Épaisse / Très épaisse** : épaisseur des contours.
- **Intensité : Douce / Normale / Forte / Maximale** : opacité des contours ; Maximale ajoute un
  liseré sombre, lisible sur tout fond. Une ancienne configuration à l’intensité 0 passe à **Normale**.
- **Remplissage : oui / non** : teinte légèrement les faces visibles des minerais surlignés.

**Afficher** ouvre le choix des minerais à surligner et devient disponible après un scan qui en a
trouvé ; **Masquer** retire la surbrillance. Un seul filon est surligné par dimension : en afficher
un autre remplace le précédent. Chaque minerai a sa couleur, tirée de son matériau GregTech et
ajustée au fond de la dimension. Le brouillard ne cache plus les contours, y compris dans le Nether.
La surbrillance porte sur les minerais encore présents dans les chunks chargés, à 96 blocs au plus,
et respecte la sélection des types. Pendant la partie, chaque dimension garde son filon surligné :
il revient quand tu y retournes, et le statut rappelle une surbrillance active ailleurs.

Pendant la lecture de la mémoire des filons, faite hors du jeu, la liste indique **Chargement des
filons…**. La mémoire garde jusqu’à 400 000 minerais présents par monde ; au-delà, un message
indique que les nouveaux ne sont plus retenus. Les minerais minés ne sont plus mémorisés un par un :
seul leur nombre est gardé, ce qui conserve le total trouvé. Les filons GregTech, les choix
d’affichage et les marquages « épuisé » restent conservés. Il n’y a pas de bouton global Tout
masquer ni de vidage manuel des relevés.

La liste garde sa position de défilement quand tu reviens du choix des minerais ou que la fenêtre
change de taille ; elle revient en haut après un changement de monde, de dimension, de recherche ou
de tri.

Limites : un filon voisin non prospecté qui contient les mêmes minerais n’est pas séparé du filon
scanné ; les minerais des chunks non chargés ne sont revérifiés qu’à leur chargement.

**TP** cherche le point libre le plus proche au-dessus du filon, dans un voisinage de 8 blocs
horizontalement. Si aucun point libre n’est connu, la TP vise directement un bloc au-dessus
du centre, même en zone non chargée : **l’arrivée peut être dans la roche**. Aucun bloc n’est
creusé ou supprimé. Les commandes / permissions opérateur restent nécessaires.

## Réglages

**Y → Réglages** regroupe les options de l’assistant. **Panneau en jeu**, ou **F9**, masque
seulement le panneau en haut à gauche ; les réponses restent dans leur bulle. **Annonces de
progression** concerne les quêtes terminées, les nouveaux filons et les machines retenues.
**Objectif suivant automatique** et **Surbrillance des besoins** ont disparu avec les objectifs.
**Modèles IA…** ne règle plus que le profil **Discussion et fiches**.

La liste défile à la molette ou avec sa barre de défilement. **Modèles IA…** et **Diagnostic…**
s’ouvrent depuis cet onglet.

## Machines regroupées dans Base

Les machines d’un même type apparaissent dans un groupe repliable avec leur quantité et un résumé
des états. Cliquer sur le groupe pour voir chaque emplacement et son propre état. Les tiers différents
restent séparés ; une machine unique apparaît directement. La recherche et les filtres continuent
de s’appliquer aux machines du groupe.

**Production…**, dans Base, lit le plan ou le diagnostic de l’arbre EMI choisi, sans rien modifier
dans le jeu. Son ancien onglet Projet a disparu. Dans la discussion, seule une question qui vise ce
calcul (« plan de production », « diagnostic de production »…) demande d’ouvrir Production ; une
question sur « cette recette » ou « cette machine » reçoit une réponse normale.

## Diagnostic et demandes en attente

**Y → Réglages → Diagnostic…** affiche la version du mod réellement chargée, la révision de
l’assistant, l’âge du relevé d’inventaire, la synchronisation du backpack, l’état du serveur,
l’arbre EMI, le scan des coffres, les transferts, la dernière demande et les erreurs relevées.
Le panneau se rafraîchit automatiquement et défile à la molette ou avec sa barre. **Retour** ou
Échap revient aux réglages. L’ouvrir ne lance aucune demande IA.

Pour limiter les saccades, les relevés ne sont réécrits qu’à un changement, sur un fil d’écriture
séparé : le jeu n’attend plus le disque. L’âge affiché du relevé d’inventaire peut donc atteindre
environ 5 secondes au repos. « Coffre mémorisé » arrive dès la première modification d’un coffre
ouvert ; les suivantes sont regroupées toutes les deux secondes et tout est écrit à la fermeture,
ainsi qu’à une déconnexion brutale (expulsion, redémarrage du serveur). L’assistant voit la
progression d’une tâche de quête au plus toutes les 30 secondes, et une quête terminée en moins de
dix secondes.

Avec shared.31 et l’assistant à jour, chaque clic destiné à l’assistant est enregistré séparément.
Une demande reste valable 45 secondes ; une action liée à une ancienne connexion ou une ancienne
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

Dans une machine, le clic sur l’icône d’un produit du bandeau ouvre sa recette dans EMI.
Échap ramène à la machine avec la même sélection. Le placement du bandeau tient compte du titre
et des onglets natifs, même lorsqu’ils dépassent du rectangle principal.

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
valide aucune production.

Un résultat réellement récupéré reste **acquis pour le craft en cours**, même après l’avoir posé
ou rangé ailleurs. Exemple : 64 récupérés sur 200 laissent **136 à récupérer** ; déplacer ensuite
ces 64 objets ne relance pas leur fabrication. EMI, le suivi machine et la recherche partagent
ce crédit. Les objets encore dans les poches ou le backpack ne sont jamais ajoutés deux fois.
Les crédits servent au calcul du travail déjà fait ; ils ne deviennent pas des cases transférables
ni des objets disponibles pour Envoyer. Ils suivent la durée de vie du craft et de ses lots.
