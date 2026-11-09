# Collecte EMI et machines

## Le parcours

1. Choisir le craft et ses branches dans **Recipe Tree**. **Y → Recette** donne l’ordre des étapes et signale les meilleures recettes.
2. Activer **Coffres**, à droite de Recipe Tree : le bouton passe en doré.
3. Ouvrir un coffre entouré en cyan : **Prendre** retire la quantité de l’icône choisie ; **Tout** retire les ingrédients du scan présents ici.
4. Aller dans une machine entourée en rouge, en suivant les numéros de l’onglet Recette (★ pour la première). Cliquer sur le produit du bandeau ouvre sa recette précise dans EMI ; Échap revient à la machine avec ce produit sélectionné. Survoler **Envoyer** pour vérifier le lot puis cliquer.
5. Récupérer le résultat soi-même. Les cibles finales reçues restent acquises après rangement.

## Ce qui est réellement sélectionné

Le scan réserve d’abord les composants prêts, puis les matières et les outils nécessaires aux branches restantes. Il considère l’ensemble des coffres mémorisés de la dimension. Un composant dans un autre coffre évite de prélever ici de quoi le refabriquer.

Les contours, les objets surlignés et les boutons de retrait utilisent la même sélection. Exemple : sur trois boîtiers nécessaires, deux existent dans un coffre ; le drawer de fer ne propose que le fer pour le troisième. Un outil déjà possédé ne doit pas être prélevé une seconde fois. Pour les outils GregTech, l’usure ne crée pas un nouvel outil à obtenir : EMI conserve sa comparaison native par type et matériau. Une comparaison particulière définie par une recette reste respectée ; les autres objets conservent leurs variantes NBT. Si le composant est déjà récupéré, son outil de fabrication ne devient pas nécessaire après rangement.

Quand le scan est actif, il suit automatiquement les modifications de recettes, de branches et de quantités, au plus toutes les 500 ms hors transfert. Les acquisitions pertinentes de la même cible et les lots en machine sont conservés. Choisir une autre cible remet les crédits de collecte à zéro, en gardant le stock réel et les lots encore en cours. **Suivi → Recalculer** permet aussi de forcer une relecture. Cette actualisation ne réactive jamais un scan arrêté. Ouvrir un coffre actualise son contenu mémorisé. Arrêter le scan retire les contours et le panneau **Collecte**. Un arbre simplement consulté n’autorise aucun retrait.

La mémoire des coffres distingue les identifiants des objets, pas leurs variantes NBT. Dans le coffre ouvert, le retrait vérifie aussi les variantes EMI, avec une exception pour le NBT factice du Programmed Circuit. Un coffre peut donc être signalé sans proposer de retrait si la variante est incompatible. La recherche ne suit pas les fluides. Un stock inaccessible ou périmé ne crée jamais d’objet : les retraits sont bornés par les vraies cases du coffre ouvert.

## Objets dans le backpack

Les sacs Sophisticated Backpacks portés sont inclus dans le stock personnel : inventaire, armure, main secondaire, curseur ou emplacement Curios. Les contenus imbriqués sont dédupliqués par identifiant de sac. Les piles agrandies conservent leur vraie quantité et leur NBT ; les upgrades et filtres ne sont pas des ingrédients disponibles.

Le client demande périodiquement le contenu via le protocole natif du mod. Le menu ouvert donne une lecture directe. Les besoins et le compteur attendent une synchronisation cohérente lors d’un déplacement entre les poches et le sac ; un ancien cache ne valide pas un nouvel inventaire ou une nouvelle connexion. **Lecture du sac…** indique cette attente. Ouvrir le sac donne accès immédiatement au contenu synchronisé par son interface. Si l’envoi d’une demande de contenu échoue, la lecture peut reprendre après le délai normal, sans lancer deux demandes en parallèle.

Les objets du sac comptent dans **l’arbre natif EMI et sa liste latérale**, même après fermeture, ainsi que pour l’avancement et les retraits dans les coffres. L’arbre se rafraîchit à la réception du relevé ; ses calculs ne modifient pas les sources de transfert. Les cases réelles de la grille de craft restent comptées, et le curseur ainsi qu’un sac ouvert ne sont comptés qu’une fois. Pour un envoi machine, **À sortir du sac** signifie qu’il faut d’abord déplacer les ingrédients dans les poches. Le remplissage natif d’EMI et le bouton **Envoyer** ne prélèvent pas automatiquement dans un sac fermé. Un sac posé au sol reste un stockage du monde, et non un inventaire personnel.

Le bouton **Sac N** à côté du suivi ouvre une liste en lecture seule : une carte par objet, avec
son nom et sa quantité, et une recherche. La liste conserve les identités NBT distinctes et n’affiche
aucun ancien stock pendant la synchronisation. Elle n’extrait pas d’ingrédient et ne modifie pas l’arbre.
Échap et **Retour à EMI** retrouvent l’écran précédent.

## Remplir l’établi

Avec un arbre EMI actif, le panneau propose les recettes d’établi encore nécessaires à cet arbre. Il a le même aspect que ceux des coffres et des machines et se place au-dessus ou à côté de l’interface, jamais sur la grille ; EMI range ses listes autour de lui. Il se rafraîchit lorsque la recette, les stocks ou les lots en cours changent. **Remplir** utilise le gestionnaire natif EMI et les vraies cases accessibles ; les objets réservés en machine ou dans un sac fermé ne sont pas prélevés. Sans arbre EMI actif, ou si l’arbre ne contient plus de recette d’établi réalisable, le panneau n’affiche rien.

## Envoyer dans une machine

Le bandeau se place hors du titre et des onglets natifs GregTech, en tenant compte des widgets
LDLib déportés. Cliquer sur une icône sélectionne ce produit et ouvre sa recette EMI. Échap revient
à la même machine en conservant la sélection ; cela ne change pas l’arbre de fabrication actif.

- Le panneau indique les fabrications possibles / restantes ; pour un lot en cours, sa deuxième ligne compte les résultats reçus, par exemple « 2/8 récupérés ». Les quantités détaillées sont au survol ; **Suivi → Recette** ouvre la recette EMI. À 320 × 240, au-delà de deux recettes, le panneau en montre une par page.
- Les entrées déjà remplies sont déduites. Les outils et catalyseurs ne sont pas multipliés par le nombre de fabrications. Un moule manquant est nommé dans **À collecter** et apparaît dans l’arbre EMI : le scan peut le retrouver ou sa branche permet de préparer sa fabrication. Ouvrir une fois la machine permet de reconnaître le moule déjà installé ; cet outil sert de disponibilité temporaire, sans valider un craft. Son relevé se met à jour à la réouverture.
- Le **Circuit setting** des recettes GregTech est un réglage virtuel, jamais un ingrédient à collecter ou à envoyer. Le numéro requis vient de la vraie recette ; le mod vérifie le réglage natif de la machine, y compris lorsqu’elle utilise le menu de configuration latéral. En cas de différence, **Circuit à régler** indique le numéro attendu et le numéro actuel au survol. Le bouton **Circuit** remplace **Envoyer** et reste inactif. Régler soi-même la machine pour activer **Envoyer** ; le bon réglage est relu avant le premier transfert. Un réglage illisible bloque l’envoi ; il ne déclenche jamais une demande de circuit physique. Un circuit demandé comme produit final ou comme objet consommé reste un objet normal.
- Les fluides viennent uniquement des contenants de l’inventaire. Le clic natif vide le contenant puis le rend. Un seau de 1 000 mB peut dépasser le besoin : le volume réellement envoyé est indiqué au survol.
- Les sorties ne sont jamais récupérées automatiquement. Les fabrications envoyées sont réservées pour éviter les doublons ; elles ne sont pas déclarées terminées avant récupération du résultat.

Pendant un envoi, la progression est affichée et les doubles clics sont ignorés. Fermer le menu ou arrêter l’arbre interrompt les actions restantes. Si le serveur refuse un transfert ou qu’une case change, les clics ne sont pas répétés automatiquement : vérifier les entrées et l’objet éventuellement sur le curseur avant de réessayer. Les machines sans entrées accessibles ne permettent pas cet envoi.

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

Le bouton **Suivi** est placé à côté de **Coffres**, en bas d’EMI. Dans le panneau d’une machine, **En cours de craft** permet directement de déclarer le lot sélectionné. Chaque ligne affiche le produit, le nombre de fabrications et son état :

| État                               | Signification                                                                                       |
| ---------------------------------- | --------------------------------------------------------------------------------------------------- |
| Prêt                               | Une fabrication est recommandée ; consulter le panneau pour les quantités effectivement envoyables. |
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

Les états des lots conservent le fonctionnement du suivi local. Une réception déduite uniquement de mouvements proches n’est pas une preuve de transfert exact : **Production V2** exclut les lots affectés de ses allocations.

Sélectionner une recette donne accès à **Recette** et **En cours de craft**. Ce bouton sert aux fabrications déjà lancées manuellement. Pour **moins de 64 résultats**, il marque directement toute la quantité restante, sans fenêtre. À partir de **64 résultats**, il ouvre le choix de quantité : saisir le nombre de fabrications ou choisir **Tout le lot**, puis confirmer. Le seuil tient compte des objets produits par fabrication ; les fluides et sous-produits aléatoires ne déclenchent pas la fenêtre. Le nombre de résultats réservé est affiché avant confirmation. Seules les sorties objets garanties sont réservées pour un lot manuel. Leur réception réelle réduit la réservation, y compris si les objets arrivent dans le backpack. Les objets déjà possédés au moment de la déclaration ne valident pas ce lot. Ranger puis reprendre le même résultat ne valide pas une deuxième unité ; une nouvelle fabrication prouvée reste comptée même si le premier résultat a été rangé. Une réservation ne fait jamais avancer à elle seule la cible finale. Un lot déclaré manuellement n’a pas de position de machine : il reste visible dans **Suivi** et ne bloque pas l’ouverture d’une autre machine. Pour un lot encore en attente, le même emplacement propose **Annuler le lot**. Échap depuis la recette ramène au suivi ; **Retour** ramène au menu précédent.

Les résultats déclarés **En cours de craft** couvrent temporairement leurs ingrédients dans l’arbre natif EMI : les branches entièrement prises en charge se replient, en conservant le produit attendu. Un lot partiel laisse apparaître les besoins du reste. Les résultats en attente ne sont jamais des objets transférables et ne valident pas la cible finale avant réception. Annuler le lot rétablit les besoins.

**Recalculer** relit les branches et les stocks actuels. L’avancement final et les lots encore en attente sont conservés ; un scan arrêté reste arrêté. Le scan actif conserve les crédits des ingrédients encore pertinents. Les réservations restent séparées : les annuler rend les besoins correspondants visibles, sans conserver de faux crédit. Les matières déjà envoyées ne couvrent pas aussi une autre branche. Les contenus des coffres restent ceux mémorisés lors de leur ouverture.

**Annuler le lot** libère la réservation dans le suivi. Cette action ne déplace pas les objets déjà envoyés et ne stoppe pas physiquement la machine. Vérifier son contenu avant d’envoyer un autre lot.

Les sorties sont observées quand l’interface native est ouverte. Le suivi reconnaît les objets repris et les fluides repris dans les contenants de l’inventaire ou du curseur. Une sortie transférée automatiquement vers un coffre ou un tuyau ne prouve pas une récupération personnelle ; annuler sa réservation si nécessaire. Seules les sorties garanties sont réservées, pas les résultats aléatoires. Les lots survivent aux modifications de l’arbre actif. Arrêter l’arbre, changer de dimension ou se déconnecter efface les réservations locales. Elles ne sont pas restaurées au redémarrage.

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
