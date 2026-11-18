# Collecte EMI et machines

## Le parcours

1. Choisir le craft et ses branches dans **Recipe Tree**. **Y → Recette** donne l’ordre des étapes et signale les meilleures recettes.
2. Activer **Repérage**, à droite de Recipe Tree : le bouton passe en doré, les coffres et machines utiles s’entourent.
3. Ouvrir un coffre entouré en cyan : **Prendre** retire la quantité de l’icône choisie ; **Tout** retire les ingrédients du scan présents ici.
4. Aller dans une machine entourée en rouge, en suivant les numéros de l’onglet Recette (★ pour la première). Cliquer sur le produit du bandeau le choisit quand la machine en a plusieurs à faire ; le clic droit ouvre sa recette précise dans EMI, et Échap revient à la machine avec ce produit sélectionné. Survoler **Envoyer** pour vérifier le lot puis cliquer.
5. Récupérer le résultat soi-même. Les cibles finales reçues restent acquises après rangement.

## Ce qui est réellement sélectionné

Le scan réserve d’abord les composants prêts, puis les matières et les outils nécessaires aux branches restantes. Il considère l’ensemble des coffres mémorisés de la dimension. Un composant dans un autre coffre évite de prélever ici de quoi le refabriquer.

Les contours, les objets surlignés et les boutons de retrait utilisent la même sélection. Exemple : sur trois boîtiers nécessaires, deux existent dans un coffre ; le drawer de fer ne propose que le fer pour le troisième. Un outil déjà possédé ne doit pas être prélevé une seconde fois. Pour les outils GregTech, l’usure ne crée pas un nouvel outil à obtenir : EMI conserve sa comparaison native par type et matériau. Une comparaison particulière définie par une recette reste respectée ; les autres objets conservent leurs variantes NBT. Si le composant est déjà récupéré, son outil de fabrication ne devient pas nécessaire après rangement.

Quand le scan est actif, il suit automatiquement les modifications de recettes, de branches et de quantités, au plus toutes les 500 ms hors transfert. Les acquisitions pertinentes de la même cible et les lots en machine sont conservés. Choisir une autre cible remet les crédits de collecte à zéro, en gardant le stock réel et les lots encore en cours. **Suivi → Recalculer** permet aussi de forcer une relecture. Cette actualisation ne réactive jamais un scan arrêté. Ouvrir un coffre actualise son contenu mémorisé. Arrêter le scan retire les contours et le panneau **Collecte**. Un arbre simplement consulté n’autorise aucun retrait.

La mémoire des coffres distingue les identifiants des objets, pas leurs variantes NBT. Dans le coffre ouvert, le retrait vérifie aussi les variantes EMI, avec une exception pour le NBT factice du Programmed Circuit. Un coffre peut donc être signalé sans proposer de retrait si la variante est incompatible. **Collecte** lit les mB des seaux, cellules et fûts compatibles Forge dans les cases du coffre ouvert. Le liquide déjà porté, y compris dans un backpack pris en compte, ou relevé dans un tampon réduit le besoin. Les boutons prennent le nombre minimal de contenants entiers. Les relevés mémorisés des coffres ordinaires ne garantissent aucun volume. Un stock inaccessible ou périmé ne crée jamais d’objet : les retraits sont bornés par les vraies cases du coffre ouvert.

## Objets dans le backpack

**Y → Réglages → Backpacks dans les crafts** contrôle leur prise en compte dans les besoins
d’EMI, les retraits des coffres et du réseau ME, ainsi que les plans de fabrication.
Le choix est activé par défaut et mémorisé sur le client. Une bascule recalcule le plan et le
repérage en cours. Les réceptions confirmées et l’indication **Dans ton backpack** continuent
de suivre l’inventaire physique.

Les sacs Sophisticated Backpacks portés sont inclus dans le stock personnel : inventaire, armure, main secondaire, curseur ou emplacement Curios. Les contenus imbriqués sont dédupliqués par identifiant de sac. Les piles agrandies conservent leur vraie quantité et leur NBT ; les upgrades et filtres ne sont pas des ingrédients disponibles.

Le client demande périodiquement le contenu via le protocole natif du mod. Le menu ouvert donne une lecture directe. Les besoins et le compteur attendent une synchronisation cohérente lors d’un déplacement entre les poches et le sac ; un ancien cache ne valide pas un nouvel inventaire ou une nouvelle connexion. **Lecture du sac…** indique cette attente. Ouvrir le sac donne accès immédiatement au contenu synchronisé par son interface. Si l’envoi d’une demande de contenu échoue, la lecture peut reprendre après le délai normal, sans lancer deux demandes en parallèle.

Avec l’option activée, les objets du sac comptent dans **l’arbre natif EMI et sa liste latérale**
même après fermeture. L’arbre se rafraîchit à la réception du relevé. Les cases réelles de la
grille, le curseur et le contenu d’un sac ouvert sont comptés chacun une fois. Pour le remplissage
natif d’EMI et **Envoyer**, sors les ingrédients d’un sac fermé vers les poches. **À sortir du sac**
indique cette étape. Un sac posé au sol fait partie des stockages du monde.

L’arbre EMI et **Recette** comptent les fluides récupérables dans les contenants portés, avec le
même réglage pour ceux qui sont dans un sac. Un fût GregTech, un seau ou une cellule compatible
Forge fournit son volume réel en mB.

Dans EMI, **Dans ton backpack × N** apparaît au survol d’une variante présente dans les sacs portés.
Avec une recette suivie, les cases utiles du backpack ouvert sont encadrées en doré. Leur infobulle
indique la quantité **À sortir** pour une fabrication après déduction des objets dans les poches.
Les cases des poches, filtres et upgrades gardent leur affichage natif.

## Coffres tampons

Dans **Options → Contrôles → Assistant IA**, assigne une touche à **Choisir / désélectionner le coffre tampon**.
Vise un coffre ou un tonneau et presse la touche. Répète sur chaque coffre à ajouter. Si tu viens
de l’ouvrir, son relevé est repris immédiatement. Sinon ouvre-le une fois pour relever son contenu.
Les deux moitiés d’un coffre double comptent pour un seul tampon. La même touche sur un coffre
déjà marqué retire ce coffre de la sélection.

Les relevés entrent dans le calcul de l’arbre EMI, du plan **Recette** et des quantités proposées
par **Envoyer** dans les machines. Un résultat déjà couvert par les tampons retire les opérations
correspondantes. Pour envoyer des ingrédients rangés dans un tampon, prends-les d’abord.
Avec **Repérage** sur **ON**, le faisceau violet identifie le tampon. Le calcul conserve le
contenu de sa dernière ouverture aussi longtemps que le coffre reste identifié, chargé et
dans la même connexion et dimension. Le relevé se met à jour si tu ajoutes ou retires des
objets dans le menu ouvert. Un déchargement du chunk écarte l’ancien contenu jusqu’à une
nouvelle observation. Si l’identité du bloc a changé, sélectionne à nouveau le coffre.
Chaque tampon reste exclu du repérage des coffres ordinaires. Le scan cherche dans ces
coffres uniquement le complément après déduction du stock des tampons.

Tu peux utiliser **Prendre / Tout** dans un tampon ouvert. Dans Sophisticated Storage, retire à la
main les objets des cases exclues du tri. Sélectionne tes coffres de nouveau après reconnexion ou
changement de dimension.

Dans le même menu de contrôles, assigne une touche à **Exclure / réintégrer un coffre**.
Vise le stockage et presse la touche pour le retirer des repères, du calcul des coffres
à portée et des propositions **Prendre / Tout**. Appuie de nouveau pour le réintégrer.
Les deux blocs d’un coffre double sont marqués ensemble, y compris si tu agrandis un coffre
simple déjà exclu. Marquer un tampon comme exclu lui retire le rôle de tampon. Les exclusions
valent pour la connexion et la dimension en cours.

## Terminal ME

Ouvre un terminal de stockage ou de fabrication AE2 avec un **Recipe Tree** sélectionné.
L’arbre EMI compte les objets que le terminal annonce en stock, même avec un filtre de
recherche, et garde les relevés des tampons. Pour une même variante présente dans les deux sources,
il retient la plus grande quantité. Le terminal fournit un total par variante, qui peut déjà inclure
les tampons accessibles par un bus de stockage.

Le panneau **Réseau ME** a le même aspect que la **Collecte** des coffres et se
place hors du terminal ; EMI range ses listes autour de lui. Il montre chaque objet à retirer
avec sa quantité et le total « N à prendre ». Le calcul déduit tes poches, ton sac et tes tampons, puis
prend les composants déjà prêts avant de chercher les matières de leur branche.

Un simple clic suffit : **Tout** retire tout ce qui manque à l’arbre, **Prendre** seulement
l’objet choisi (clic sur son icône). Le clic relit le terminal et l’arbre à cet instant. Pendant
le retrait, les quantités restantes baissent, les objets terminés sont soulignés en vert et
**Tout** devient **Arrêter**. Un clic ailleurs dans le terminal arrête aussi le retrait.
Le retrait privilégie les piles entières de la taille de l’objet. Une pile peut aller directement
dans une case libre. Quand le reste à prendre et la place suffisent, AE2 prend une demi-pile,
puis ajuste le solde à l’unité. Chaque lot attend la confirmation du serveur avant de continuer.
Le relevé est valable tant que le menu du terminal est ouvert. Laisse le curseur vide avant
de lancer le retrait ; en cas d’interruption, les objets déjà retirés peuvent se trouver sur
le curseur. Les objets seulement autocraftables et les fluides du réseau restent à gérer dans
AE2. Sur un terminal de fabrication, le panneau **Remplir** de l’établi se place à côté de
**Réseau ME** sans le recouvrir.

## Remplir l’établi

Avec un arbre EMI actif, le panneau propose les recettes d’établi encore nécessaires à cet arbre. Il a le même aspect que ceux des coffres et des machines et se place au-dessus ou à côté de l’interface, jamais sur la grille ; EMI range ses listes autour de lui. Il se rafraîchit lorsque la recette, les stocks ou les lots en cours changent. **Remplir** utilise le gestionnaire natif EMI et les vraies cases accessibles ; les objets réservés en machine ou dans un sac fermé ne sont pas prélevés. Sans arbre EMI actif, ou si l’arbre ne contient plus de recette d’établi réalisable, le panneau n’affiche rien.

## Envoyer dans une machine

Le bandeau se place hors du titre et des onglets natifs GregTech, en tenant compte des widgets
LDLib déportés. Cliquer sur une icône sélectionne ce produit et ouvre sa recette EMI. Échap revient
à la même machine en conservant la sélection ; cela ne change pas l’arbre de fabrication actif.

- Le panneau indique les fabrications possibles / restantes ; pour un lot en cours, sa deuxième ligne compte les résultats reçus, par exemple « 2/8 récupérés ». Les quantités détaillées sont au survol ; **Suivi → Recette** ouvre la recette EMI. À 320 × 240, au-delà de deux recettes, le panneau en montre une par page.
- Les entrées déjà remplies sont déduites. Les outils et catalyseurs ne sont pas multipliés par le nombre de fabrications. Un moule manquant est nommé dans **À collecter** et apparaît dans l’arbre EMI : le scan peut le retrouver ou sa branche permet de préparer sa fabrication. Ouvrir une fois la machine, même avant de choisir l’arbre EMI, permet de reconnaître le moule déjà installé. Le relevé reste utilisable dans la même dimension tant que la machine est chargée. Il se met à jour à la réouverture.
- Le **Circuit setting** des recettes GregTech est un réglage virtuel, jamais un ingrédient à collecter ou à envoyer. Le numéro requis vient de la vraie recette ; le mod vérifie le réglage natif de la machine, y compris lorsqu’elle utilise le menu de configuration latéral. En cas de différence, **Circuit à régler** indique le numéro attendu et le numéro actuel au survol. Le bouton **Circuit** remplace **Envoyer** et reste inactif. Régler soi-même la machine pour activer **Envoyer** ; le bon réglage est relu avant le premier transfert. Un réglage illisible bloque l’envoi ; il ne déclenche jamais une demande de circuit physique. Un circuit demandé comme produit final ou comme objet consommé reste un objet normal.
- Les fluides viennent des contenants de l’inventaire. Le survol indique le besoin, le volume en cuve, le volume prévu et les contenants rendus, y compris le volume qui reste dans une cellule partiellement vidée.
- Les sorties ne sont jamais récupérées automatiquement. Les fabrications envoyées sont réservées pour éviter les doublons ; elles ne sont pas déclarées terminées avant récupération du résultat.
- Chaque dépôt est confirmé séparément. Si la machine a déjà consommé l’entrée, le solde des ingrédients doit correspondre à la recette choisie. Si un cycle d’un tick est entièrement passé entre deux relevés, un dépôt simple peut être confirmé par le bilan exact entre les cases natives d’entrée et de sortie. EMI garde le suivi de la cible tant que sa sortie est attendue, jusqu’à sa réception ou à l’arrêt manuel.

Pendant un envoi, la progression est affichée et les doubles clics sont ignorés. Fermer le menu ou arrêter l’arbre interrompt les actions restantes. Si le serveur refuse un transfert ou qu’une case change, les clics ne sont pas répétés automatiquement : vérifier les entrées et l’objet éventuellement sur le curseur avant de réessayer. Les machines sans entrées accessibles ne permettent pas cet envoi.

Après une interruption, survole **Envoyer** en rouvrant la même machine dans le même arbre EMI.
Le relevé indique les objets et fluides dont le dépôt a été confirmé. Un clic relit les entrées
actuelles et propose uniquement le complément. Les fabrications déjà démarrées avec une preuve
de recette sont réservées dans un lot partiel. Si le dernier clic ou une consommation reste
incertain, le panneau indique **Reprise à vérifier dans la machine**. Vérifie les entrées, les
sorties et le curseur avant de terminer manuellement ce cas.

## Dans quel ordre fabriquer ?

**Suivi** et le panneau de la machine classent les opérations selon les dépendances de l’arbre EMI choisi et les ressources utilisables :

| Groupe              | Ce qu’il contient                                                                                  |
| ------------------- | -------------------------------------------------------------------------------------------------- |
| À lancer maintenant | Au moins une fabrication réalisable avec les ingrédients physiques et les préconditions vérifiées. |
| À préparer          | Une collecte, un déplacement d’objets ou un réglage reste nécessaire.                              |
| En cours            | Les lots existants et leurs sorties attendues, séparés du stock disponible.                        |
| Étapes suivantes    | Une dépendance, une ressource ou une information manque ; le détail indique le blocage.            |

**Prêt** recommande **une fabrication**, sans garantir que toute la quantité restante peut être lancée. Le nombre de fabrications encore nécessaires reste inchangé. Les composants déjà disponibles et les lots en attente gardent leur rôle dans le calcul des besoins.

Les recommandations partagent virtuellement les ressources : quatre lingots ne peuvent pas rendre simultanément prêts deux crafts qui en consomment trois chacun. **Stock partagé** signale une alternative dans le suivi ; dans le panneau machine, **Autre priorité** indique qu’elle reste envoyable. Choisir cette recette reste possible si le contrôle actuel de la machine l’autorise. Ce classement ne réserve ni ne déplace d’objet dans le jeu.

Pour ce classement, un coffre compte seulement lorsque son menu natif est encore ouvert et son contenu attesté. Ouvrir **Suivi** depuis ce coffre conserve cette lecture ; les anciens relevés mémorisés n’annoncent pas un craft prêt. Un objet attesté dans le sac ou le coffre reste à ramener dans les poches. Une machine non vérifiée apparaît dans **Étapes suivantes**, même si ses ingrédients sont possédés. Le menu de la machine ouverte contrôle ses entrées, son circuit et les possibilités d’envoi ; cela ne garantit pas son alimentation en énergie.

Dans **Suivi**, cliquer sur un titre replie ou déplie son groupe ; **Tout déplier / Tout replier** agit sur l’ensemble. Les deux premiers groupes sont ouverts au départ. S’ils sont vides, le premier groupe disponible s’ouvre, sans modifier ensuite un choix de repli explicite. Le détail complet reste accessible au survol.

L’ordre se réactualise avec les données utiles. La sélection reste attachée à la recette et au lot, même si leur place change. Dans une machine, un choix explicite est conservé tant que la recette est encore proposée. Un clic visant un ancien arbre, une ancienne quantité ou un contexte remplacé est refusé : attendre le nouvel affichage puis sélectionner à nouveau. Aucun craft ni transfert ne démarre automatiquement.

L’onglet **Y → Recette** numérote le plan avec le même calcul d’ordre, et les machines entourées en rouge affichent ces numéros. Il compte une machine posée comme disponible sans l’ouvrir et la mémoire des coffres comme stock à récupérer : ses rangs peuvent donc différer légèrement des groupes du suivi, qui exige le menu natif. Voir [l’ordonnancement](ordonnancement-machines.md#onglet-recette-et-repères-dans-le-monde).

## Suivi et reprise

Dans **Suivi**, sélectionne un lot de machine en cours puis clique sur **Terminer** quand sa
fabrication est finie. Cette validation clôt tout le lot et retire les sorties de la liste des
attentes. L’arbre EMI en cours en tient compte. Sélectionne ensuite une ligne marquée **Terminé** et
clique sur **Supprimer** pour la retirer du suivi. Le calcul EMI conserve l’avancement du lot.

Le bouton **Suivi** est placé à côté de **Repérage**, en bas d’EMI. Dans le panneau d’une machine, **En cours de craft** permet directement de déclarer le lot sélectionné. Chaque ligne affiche le produit, le nombre de fabrications et son état :

| État                               | Signification                                                                                       |
| ---------------------------------- | --------------------------------------------------------------------------------------------------- |
| Prêt                               | Une fabrication est recommandée ; consulter le panneau pour les quantités effectivement envoyables. |
| À sortir du backpack : …           | Les ressources nommées sont dans ton backpack : les sortir dans les poches.                         |
| À prendre dans un coffre : …       | Les ressources nommées sont dans un coffre connu à portée.                                          |
| À reprendre en main : …            | Les ressources nommées sont en main secondaire, dans l’armure ou sur le curseur.                    |
| À récupérer dans les poches        | Les ressources sont attestées, mais pas encore toutes utilisables directement.                      |
| Circuit à régler                   | Corriger le réglage virtuel indiqué avant l’envoi.                                                  |
| Déjà en machine                    | Les ingrédients sont déjà dans les entrées ; vérifier le lancement.                                 |
| Stock partagé                      | Une autre recommandation utilise les mêmes ressources.                                              |
| Autre priorité                     | Dans le panneau machine : cette recette reste envoyable, mais son stock sert d’abord une autre.     |
| Machine à vérifier / Stock inconnu | Les informations disponibles ne permettent pas d’annoncer le craft prêt.                            |
| Attend : … / Manque : …            | Préparer le composant ou trouver la ressource indiquée.                                             |
| Cycle détecté                      | Revoir les branches choisies dans EMI ; cette chaîne n’est pas ordonnançable telle quelle.          |
| En machine                         | Lot envoyé ou déclaré manuellement ; ses sorties restent attendues.                                 |
| À récupérer / Terminé              | État de récupération estimé par le suivi client. Vérifier la sortie ou le stock réel.               |

Le suivi d’un lot machine attend un clic natif sur sa sortie, une baisse de cette sortie et un gain correspondant chez le joueur. Les lots de la même recette dans la même machine sont servis dans l’ordre. Si deux recettes différentes peuvent expliquer le même retrait, la réception du lot reste à confirmer manuellement. **Production V2** conserve ses propres exigences de preuve.

Dans **Suivi**, sélectionner une recette et cliquer sur **En cours de craft** en haut pour déclarer une fabrication déjà lancée. Le bouton accepte aussi la recette d’un lot affiché lorsqu’il reste une quantité à déclarer. Après confirmation, le groupe **En cours** se déplie et affiche le nouveau lot. **Annuler le lot** reste en bas pour le lot sélectionné.

Pour **moins de 64 résultats**, le bouton marque directement toute la quantité restante, sans fenêtre. À partir de **64 résultats**, saisir le nombre de fabrications ou choisir **Tout le lot**, puis confirmer. Le seuil tient compte des objets produits par fabrication. Seules les sorties objets garanties sont réservées pour un lot manuel. Leur réception réelle réduit la réservation, y compris si les objets arrivent dans le backpack. Les objets possédés au moment de la déclaration restent séparés du lot. Ranger puis reprendre le même résultat conserve le crédit existant. Une nouvelle fabrication prouvée peut ajouter un crédit. Un lot déclaré manuellement est visible dans **Suivi** avec la mention « déclaré manuellement ». Échap depuis la recette ramène au suivi et **Retour** ramène au menu précédent.

Les résultats déclarés **En cours de craft** couvrent temporairement leurs ingrédients dans l’arbre natif EMI : les branches entièrement prises en charge se replient, en conservant le produit attendu. Un lot partiel laisse apparaître les besoins du reste. Les résultats en attente ne sont jamais des objets transférables et ne valident pas la cible finale avant réception. Annuler le lot rétablit les besoins.

**Recalculer** relit les branches et les stocks actuels. L’avancement final et les lots encore en attente sont conservés ; un scan arrêté reste arrêté. Le scan actif conserve les crédits des ingrédients encore pertinents. Les réservations restent séparées : les annuler rend les besoins correspondants visibles, sans conserver de faux crédit. Les matières déjà envoyées ne couvrent pas aussi une autre branche. Les contenus des coffres restent ceux mémorisés lors de leur ouverture.

**Annuler le lot** libère la réservation dans le suivi. Cette action ne déplace pas les objets déjà envoyés et ne stoppe pas physiquement la machine. Vérifier son contenu avant d’envoyer un autre lot.

Les sorties sont observées quand l’interface native est ouverte. Le suivi reconnaît les retraits directs par clic, Maj-clic ou raccourci de barre rapide, ainsi que les fluides repris dans un contenant. Si une sortie passe par un coffre, un hopper ou le réseau ME, **Réception à confirmer** indique combien d’objets correspondants sont déjà sur toi. Quand tu as récupéré toute la sortie, sélectionne la ligne et clique sur **Terminer**. Seules les sorties garanties sont réservées, pas les résultats aléatoires. Les lots survivent aux modifications de l’arbre actif. Arrêter l’arbre, changer de dimension ou se déconnecter efface les réservations locales. Elles ne sont pas restaurées au redémarrage.

Les composants transformés remplacent leur crédit de matières premières : quatre lingots devenus deux plaques ne restent pas disponibles comme lingots pour les plaques suivantes. Un moule ou outil partagé n’est planifié qu’une fois, même dans des branches de profondeurs différentes.

## Avancement conservé

Les résultats finaux sont comptés lorsqu’ils arrivent dans l’inventaire ou sur le curseur, y compris par Maj-clic. Une baisse du stock ne retire pas le crédit. Les mises à jour séparées de la sortie machine et de l’inventaire sont rapprochées sur une courte fenêtre ; une preuve arrivée après le gain est prise en compte une seule fois. Trois machines fabriquées et rangées une par une peuvent donc terminer une cible de trois machines.

Le suivi distingue un retour d’objet d’une nouvelle fabrication grâce aux événements de craft et aux retraits des vraies sorties machine. Ranger et reprendre le même objet ne gonfle plus le compteur. Les déplacements entre l’inventaire, l’équipement et le curseur ne créent pas de crédit supplémentaire. Sans preuve de fabrication, un gain remplaçant un objet précédemment sorti est traité prudemment comme son retour ; récupérer un objet différent mais identique depuis un coffre ne prouve donc pas toujours une nouvelle fabrication. Ce suivi reste une observation client, pas un journal serveur.

Le suivi d’un craft est limité à la session ; changer sa cible démarre un nouveau suivi final. Modifier seulement la quantité de la même cible conserve les objets déjà obtenus, même rangés.

## Tester une mise à jour

Redémarrer l’assistant et Minecraft. Choisir un petit arbre avec un composant prêt dans un coffre et ses matières dans un autre, activer le scan et vérifier **Prendre / Tout**. Tester ensuite une recette machine avec circuit, une récupération partielle, **Suivi → Recalculer / Annuler le lot**, puis une cible de plusieurs objets rangés au fur et à mesure. Reprendre plusieurs fois le même objet ne doit pas augmenter son avancement.

Pour cette version, tester aussi :

- Déplacer un objet des poches au backpack, fermer le sac et consulter Recipe Tree : le total possédé doit rester identique.
- Déclarer deux fabrications en cours dans **Suivi** : le scan ne doit plus demander leurs matières ni leurs outils. Récupérer un résultat, puis annuler le reste pour vérifier le besoin rétabli.
- Modifier une branche ou la quantité avec le scan actif : seuls les nouveaux besoins doivent apparaître et les lots déjà lancés doivent rester dans **Suivi**.

Ces fonctions utilisent le client, les recettes locales et les contenus mémorisés. Aucun appel IA n’est nécessaire pour calculer les retraits ou envoyer un lot.

Les allocations machine sont conservées tant que l’arbre, les stocks et les réservations ne changent pas. Les compatibilités machine sont mises en cache et invalidées régulièrement ou par **Recalculer**.

## Résultats récupérés puis déplacés

Les réceptions réelles d’un lot restent acquises après pose ou rangement. La quantité encore
attendue diminue au retrait du coffre jaune ; un déplacement ultérieur ne recrée pas le besoin.
EMI et le suivi reçoivent uniquement le complément hors des poches, après déduction des cibles
finales déjà comptées. Le stock des remplisseurs et d’Envoyer reste physique.
Annuler un surplus libère ses réservations sans effacer la partie déjà récupérée.

## Scénarios reproductibles

**Tester Mac.command** / **Tester.bat** proposent les jeux basique et complexe, sans toucher à la partie.
Voir [les parcours, commandes et limites](scenarios.md). Le scan des coffres se renouvelle tant qu’il
reste choisi et que l’arbre est actif ; une longue fabrication ne l’arrête plus après dix minutes.
Un retard de l’assistant relance seulement la recherche, jamais les clics d’un transfert.
