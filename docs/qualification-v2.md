# Qualification de Monifactory Assistant V2

## Campagne Mac, Windows et multijoueur

**V2 RELEASE CANDIDATE — PARTIAL**. La branche
[`qualification/v2-shared42`](https://github.com/B-DAGBERT/monifactory-claude-assistant/tree/qualification/v2-shared42)
est disponible pour le second poste. Le premier envoi a publié exactement
`a6a2d68dd56fa117d1d70a492733b4a709016ccd`. Les commits de campagne ajoutent les guides et
le rapport. Comparer le checkout au SHA de code avant l’installation. Le dernier `origin/main`
vérifié est `5d280c3a949f4a6521fb1a4eed0303550dd25ec9`. Les 19 commits de code et de
qualification du candidat descendent de ce point. R5 a cinq commits indépendants à
`5105320384d5e9e7f56eb58e2662831f07f1e329`. La confirmation native R5 reste séparée.

- [Recette Windows 11, Claude Code et NTFS](recette-v2-windows.md)
- [Recette à deux joueurs et permissions FTB](recette-v2-multijoueur.md)
- [Origine des preuves Mac, versions et procédures de retour arrière](integration-v2.md)

Les résultats distinguent **AUTOMATED** pour les tests, **LOCAL_INTEGRATION** pour un vrai
composant exercé hors jeu, **IN_GAME** pour Minecraft, **USER_CONFIRMED** pour un essai attesté
par le joueur, et **NOT_TESTED** quand le parcours n’a pas été exécuté.

### Versions et intégrité de la livraison

Le JAR Mac chargé de référence est `assistantia-1.0.0-shared.42.jar`. SHA-256
`5d6516f6ffa57ae116d7f91fcf4326155330b153345e782cbbbf62bd8e441737`.
La reconstruction Forge de la campagne a donné
`d885676135fef6c4f76eac29b1659bf9b97693ea996010af90b25bdead22ee15`.
Les 192 entrées non répertoires concordent en contenu, à l’exception de l’horodatage de
compilation dans `META-INF/MANIFEST.MF`. L’essai de jeu désigne l’empreinte réellement chargée.
Le script client KubeJS est en version 12. SHA-256
`a77b218d8778fd3dc123444a58b1e194d226080db0121e6c81d8e87175387f1e`.
La file de demandes reste en version 2, le catalogue et la preuve de génération en version 1.
Le script serveur, les formats existants et le déclenchement du lot 5 restent hors de cette
livraison. La branche de qualification n’est pas la source de mise à jour automatique.

La revue avant envoi a inspecté les 19 commits, y compris les lignes ajoutées à chaque commit,
leurs noms de fichiers, les chemins personnels et les signatures usuelles de secrets. Aucun
fichier de sauvegarde, export de session, conversation, résultat brut ou JAR de jeu n’a été
détecté dans les changements. Le binaire du wrapper Gradle préexistait sur `main`. Les essais
de jeu ont utilisé uniquement une instance jetable et un projet fictif. Le calcul Production
consulte les données et la preuve de session. Les transferts et les actions destructives
restent sous les confirmations natives existantes. Le garde-fou Python refuse les suppressions
issues des réponses IA. Le script de relevé et les protections FTB côté serveur n’ont aucune
modification dans la branche candidate.

Le scénario d’installation `.40 → .42 → .40` a été rejoué avec le JAR reconstruit sur une
copie. Le JAR, le script et le plan fictif concordaient avant installation et après restauration.
Une coupure brutale entre les deux remplacements de fichiers
reste un risque P4 identifié. La sauvegarde de l’installateur permet de remettre le couple
précédent. Le verrou NTFS et la reprise d’installation sur Windows restent à tester.

### Matrice de preuve

| Domaine                  | Résultat                                                                                                                               | Niveau                               |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| Git                      | Branche de qualification publiée, `main` contrôlé, R5 séparé                                                                           | LOCAL_INTEGRATION                    |
| Tests Mac                | 627 tests et 26 scénarios EMI. Contrats Java, Forge JDK 17, Node et Rhino, Ruff, Prettier                                              | AUTOMATED                            |
| Mac et KubeJS            | Démarrage normal dans une copie Expert avec les configurations publiques du pack                                                       | IN_GAME                              |
| EMI                      | 8 plaques, coût cintreuse 6 cuivre + 2 étain, marteau 9 cuivre + 3 étain                                                               | IN_GAME                              |
| Verrou macOS             | Ancien marqueur lisible, nouvelle preuve refusée, nouveau calcul après libération                                                      | IN_GAME                              |
| Production               | Inventaire personnel alloué. Contrats de stocks, lots et diagnostics. Acquisition native de coffres et lots partielle                  | IN_GAME et AUTOMATED                 |
| Windows / NTFS           | Procédure prête. Poste et verrou natif à qualifier                                                                                     | NOT_TESTED                           |
| Multijoueur / FTB        | Positions et mondes vérifiés sur contrats. Deux vrais joueurs et permissions FTB à qualifier                                           | AUTOMATED, IN_GAME NOT_TESTED        |
| IA                       | Un appel Codex CLI éphémère sur les quantités fictives exactes. Dialogue de jeu avec fournisseur simulé. Claude réel à qualifier       | LOCAL_INTEGRATION, Claude NOT_TESTED |
| R5                       | Candidat original testé en jeu pour consentement, expiration, déconnexion et double clic. Course et preuve durable étudiées séparément | IN_GAME et AUTOMATED                 |
| Performance              | Catalogue, agrégation, calcul, diagnostic, contexte et invalidation mesurés sur fixture commune                                        | LOCAL_INTEGRATION                    |
| Intégrité et déploiement | JAR chargé identifié. Sauvegarde du projet fictif, installation et retour arrière contrôlés sur copies                                 | IN_GAME et LOCAL_INTEGRATION         |

### Rejeu Mac sur le candidat exact

Sur Monifactory 0.13.8 en mode Expert, Forge 47.4.13 et JDK 17, la campagne a utilisé le
JAR chargé identifié ci-dessus et la copie Python de `a6a2d68`. La sonde de laboratoire
pilote les boutons natifs et relève leurs états. La JVM a démarré sans agent de contournement.

Le premier passage a détecté sur disque un JAR R5 `.40` résiduel de la campagne précédente.
La liste active de Forge contenait le seul assistant `.42`, qui a aussi répondu par son
protocole natif de preuve. Les essais longs sont attribués à ce JAR chargé. Le second
démarrage, après retrait du JAR résiduel dans la copie uniquement, a confirmé une seule
archive assistant sur disque et un seul assistant `.42` chargé par Forge. Le sac fermé a
été reconnu après démarrage, le plan bronze a reçu une nouvelle preuve et le projet en
pause est resté identique. Les avertissements de recettes du pack ont été consignés
séparément du chargement V2.

| Cas    | Résultat de la campagne                                                                                                                                                                                        | Niveau               |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------- |
| MAC-01 | PASS. Démarrage de la copie conforme et entrée dans le monde jetable                                                                                                                                           | IN_GAME              |
| MAC-02 | PASS. Registre EMI réellement chargé. Écrans natifs de recette et de Recipe Tree ouverts                                                                                                                       | IN_GAME              |
| MAC-03 | PASS. Le plan Production a été calculé depuis l’arbre natif et le pont a reçu sa preuve                                                                                                                        | IN_GAME              |
| MAC-04 | PASS. Huit plaques par cintreuse et marteau, coûts 6 cuivre + 2 étain et 9 cuivre + 3 étain                                                                                                                    | IN_GAME              |
| MAC-05 | PASS. Le changement d’arbre révoque l’ancien plan et sa preuve                                                                                                                                                 | IN_GAME              |
| MAC-06 | PASS. Le marqueur macOS immuable reste identique sur disque. L’ancien résultat est refusé avant appel au fournisseur simulé. Nouveau calcul après libération                                                   | IN_GAME              |
| MAC-07 | PASS. Le bouton Minecraft de sortie revient normalement au menu. Identité et ancien résultat révoqués                                                                                                          | IN_GAME              |
| MAC-08 | PASS. Retour dans le même monde, nouvelle session, ancienne preuve rejetée et nouveau calcul accepté                                                                                                           | IN_GAME              |
| MAC-09 | PASS. Projet fictif en pause conservé octet pour octet pendant la reconnexion et la relance de Python seul. Ancien résultat absent et nouveau calcul accepté                                                   | IN_GAME et AUTOMATED |
| MAC-10 | PARTIAL. Recettes, arbre EMI et stock du backpack testés en jeu. Collecte et envoi réels en machine restent à rejouer. Les 26 scénarios contrôlent les contrats de fluides, circuits, catalyseurs et transfert | IN_GAME et AUTOMATED |

La sortie normale règle le blocage observé avec l’ancienne sonde qui appelait directement
la fermeture du monde. Le test a quitté puis rechargé le même monde et vérifié séparément
la continuité de la clé de monde et le renouvellement de la session. Les calculs observés
avant et après reconnexion ont duré 0,760 et 0,921 s. Après libération du marqueur, le
calcul du marteau a duré 1,083 s. Ces valeurs incluent les échanges et les ticks du client.
Après relance de Python seul, le nouveau calcul natif a pris 1,075 s. Le plan du marteau
réutilise 6 cuivre et 2 étain du sac synchronisé et indique 3 cuivre et 1 étain à obtenir.

### Stocks de la campagne

Les numéros ci-dessous suivent la campagne multiplateforme. Le tableau de stabilisation à
quinze cas, plus bas, conserve sa numérotation. En cas de différence, distinguer le contenu
physique du stock effectivement alloué et du crédit de progression.

| ID       | Résultat établi et limite                                                                                                                                                                                                                   | Niveau                     |
| -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| STOCK-01 | Un inventaire physique attesté est alloué une seule fois                                                                                                                                                                                    | IN_GAME et AUTOMATED       |
| STOCK-02 | Déplacement en jeu de 6 cuivre et 2 étain vers un Sophisticated Backpack dans la barre rapide. Sac fermé, quantité synchronisée et allouée une seule fois, zéro manque. L’emplacement Curios et les variantes NBT restent hors de cet essai | IN_GAME et AUTOMATED       |
| STOCK-03 | Le coffre observé reste distinct du stock personnel. Avec 3 en poche et 5 en coffre, le plan demande encore 5 sur une cible de 8                                                                                                            | LOCAL_INTEGRATION, PARTIAL |
| STOCK-04 | Le contrat regroupe les deux moitiés attestées. Production native n’alloue actuellement pas le coffre double                                                                                                                                | AUTOMATED, PARTIAL         |
| STOCK-05 | Un relevé tardif ne réattribue pas une pile déplacée. Sans révision commune, Production évite la somme des deux sources                                                                                                                     | AUTOMATED                  |
| STOCK-06 | Un stockage ancien reste historique et hors des disponibilités garanties                                                                                                                                                                    | AUTOMATED                  |
| STOCK-07 | Une réservation réduit les quantités disponibles dans le contrat. Aucun lot natif de Production ne l’atteste                                                                                                                                | AUTOMATED, PARTIAL         |
| STOCK-08 | Une entrée de machine ne devient ni stock de coffre ni produit acquis                                                                                                                                                                       | AUTOMATED, PARTIAL         |
| STOCK-09 | Une sortie attendue reste en attente, distincte du physique                                                                                                                                                                                 | AUTOMATED, PARTIAL         |
| STOCK-10 | Les reçus partiels sont cumulatifs et bornés au lot. L’export actuel ne permet pas d’identifier ce lot dans Production                                                                                                                      | AUTOMATED, PARTIAL         |
| STOCK-11 | L’annulation retire uniquement la part encore attendue dans le contrat. Le suivi EMI garde sa commande native                                                                                                                               | AUTOMATED, PARTIAL         |
| STOCK-12 | Le projet reste en pause et le calcul n’avance aucune étape. Le suivi EMI conserve ses lots en mémoire pendant la pause                                                                                                                     | IN_GAME et AUTOMATED       |
| STOCK-13 | Un changement de connexion invalide le résultat et le suivi local EMI. Un lot actif réel reste à rejouer                                                                                                                                    | AUTOMATED, PARTIAL         |
| STOCK-14 | La reprise de file durable n’exécute pas à nouveau la demande. La restauration de lots V2 reste hors du contrat natif                                                                                                                       | AUTOMATED, PARTIAL         |

Le module d’acquisition natif alloue actuellement l’inventaire personnel du clic. Avec
une capture valide, la vue de connaissance privilégie cette source. Sans capture attestée,
les agrégats génériques sont écartés des allocations exactes. Le suivi EMI peut indiquer une
réservation ou une réception, mais le fichier de crédits cumulés ne fournit pas l’identité
d’un lot à Production. Des essais sur le poste Windows ne peuvent pas combler cette limite
d’intégration. Pour qualifier un périmètre réunissant coffres et lots, il faut une vue de
stock cohérente et une provenance de lot qui permette de rapprocher les réceptions sans
doubler les crédits. Le candidat préfère signaler l’inconnu plutôt que combler ces données.

### Dernière revue de R5

Le candidat natif `5105320` garde ses preuves en jeu. Le correctif de résultat durable
`97a595a` reste sur la branche locale `codex/r5-commit-status`. Le point d’engagement
logique est le dernier relevé de la file et la revalidation du contexte avant consommation
de l’autorisation. Une annulation visible lors de ce contrôle empêche l’écriture. Une demande
qui arrive après peut être trop tardive, même avant le remplacement du fichier. Ce protocole
interprocessus ne promet pas une annulation jusqu’à l’écriture atomique.

Le correctif restitue le résultat terminal à une annulation tardive et après redémarrage,
sans rétablir l’autorisation de suppression. Un reçu contradictoire ou une erreur survenue
après modification rend le résultat incertain. La suite complète séparée a réussi **535
tests**, dont la passe ciblée de **114 tests** avec 17 nouveaux cas. **Sept arrêts SIGKILL**
sur projets fictifs ont réussi. La contre-revue a rejoué 83 tests. Le
correctif n’a pas été essayé avec le client Minecraft réel, même si son Java n’a pas changé.
R5 reste **PARTIAL**, indépendant de la V2 en lecture seule et du garde-fou de `main`.

### Mesures de la campagne

Même fixture entre `2c734d8` et `a6a2d68`, avec 2 024 recettes, 600 coffres, une machine
et une chaîne de 24 étapes. 21 passages après trois échauffements. Le scénario de base se
rejoue avec `python -m tests.benchmarks.production_v2`. La comparaison ajoute le diagnostic
de cette machine et un répondant immédiat sur les fichiers temporaires pour mesurer le pont.

| Opération                | Médiane avant | Médiane après | P95 après | Pic Python après |
| ------------------------ | ------------: | ------------: | --------: | ---------------: |
| Catalogue                |     29,761 ms |     31,066 ms | 38,668 ms |        4,794 Mio |
| Agrégation               |     26,549 ms |     25,094 ms | 31,236 ms |        1,497 Mio |
| Plan pur                 |      2,225 ms |      2,146 ms |  2,271 ms |        0,320 Mio |
| Plan depuis observations |      6,124 ms |      6,341 ms |  7,546 ms |        0,696 Mio |
| Diagnostic               |      0,150 ms |      0,150 ms |  0,154 ms |        0,012 Mio |
| Service complet          |     54,429 ms |     57,303 ms | 61,095 ms |        4,797 Mio |
| Contexte IA              |      0,077 ms |      0,834 ms |  1,022 ms |        0,014 Mio |
| Invalidation locale      |      0,148 ms |      0,155 ms |  0,165 ms |        0,008 Mio |

Les moteurs de calcul et d’agrégation sont inchangés entre les deux versions. Les petits
écarts entre passages ne démontrent pas de régression du moteur. La préparation du contexte
inclut maintenant un contrôle positif du client. Le répondant simulé ne représente pas un
tick Minecraft. Sans réponse native, le contrôle de 450 ms a expiré en environ 457–460 ms,
sondage et lectures inclus. Ces mesures excluent le temps du fournisseur IA, les FPS et le
temps du serveur. Le jeu et la reconnexion ont été mesurés séparément sur Mac.

## Qualification du candidat stabilisé

Le [rapport d’intégration](integration-v2.md) suit les commits, les deux anomalies prioritaires,
les essais dans Minecraft et la procédure de reproduction Mac et Windows. Les tableaux suivants
décrivent la portée de l’essai de stabilisation. Les sections historiques gardent leurs références.

### Stocks et productions

Les cas de `tests/test_production_stock_matrix.py` emploient les contrats réels du planificateur,
des relevés datés et de leur rapprochement. Leurs entrées sont synthétiques, à l’exception des
contrats d’acquisition exécutés sur de vrais fichiers temporaires. Le stock issu de chaque
source, ses réservations et ses manques sont comparés par assertions. Les cas EMI précédents
restent dans la suite de scénarios et ne sont pas remplacés par cette matrice.

| Cas                            | Comparaison vérifiée                                                         | Niveau et limite                                                                                 |
| ------------------------------ | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| STOCK-01 · Inventaire          | Stock physique porté déduit une seule fois                                   | Contrat et inventaire du joueur de test dans Minecraft                                           |
| STOCK-02 · Sac                 | Le total synchronisé du joueur remplace l’ancien relevé et son sous-total    | Contrat et Java de backpack fermé. En jeu, compter le sac lorsqu’une réponse native l’atteste    |
| STOCK-03 · Plusieurs coffres   | Quantités distinctes allouées sur un même relevé fiable                      | Contrat. La V2 native ne somme pas ce relevé et un inventaire sans révision de transfert commune |
| STOCK-04 · Coffre double       | Fusion des moitiés attestées. Une chaîne de voisins reste ambiguë            | Contrat. Parcours Sophisticated Storage, Drawers et protections à rejouer en jeu                 |
| STOCK-05 · Déplacement         | La nouvelle localisation prime sans compter l’ancien coffre                  | Contrat de provenance                                                                            |
| STOCK-06 · Observation tardive | Un ancien paquet ne réactive pas la source périmée                           | Contrat de fraîcheur                                                                             |
| STOCK-07 · Stock incomplet     | Manques relatifs aux relevés. Total inconnu                                  | Contrat et indication de la vue V2                                                               |
| STOCK-08 · Réservation         | Quantité réservée déduite du physique utilisable                             | Contrat du registre de lots. Réservation native V2 indisponible                                  |
| STOCK-09 · Mise en machine     | Une entrée machine ne devient ni stock de coffre ni lot déclaré              | Contrat. Transfert EMI natif inchangé                                                            |
| STOCK-10 · Production attendue | Le produit reste en attente et n’alimente aucune allocation physique         | Contrat du registre de lots. Sortie native V2 indisponible                                       |
| STOCK-11 · Réception partielle | Le reçu est cumulatif, idempotent et borné au lot                            | Contrat et Java des réceptions. Reçu natif V2 indisponible                                       |
| STOCK-12 · Annulation          | Seule l’attente restante est libérée. L’objet physique reste compté une fois | Contrat et Java de suivi. Annulation en V2 indisponible                                          |
| STOCK-13 · Circuit             | Le réglage virtuel ne consomme aucun circuit physique                        | Contrat et recettes GregTech chargées dans Minecraft                                             |
| STOCK-14 · Fluide              | mB exacts. Un seau n’est pas assimilé à un volume disponible                 | Contrat et recettes chargées. Fluides de stock non attestés                                      |
| STOCK-15 · Dimensions          | Les mêmes coordonnées restent séparées selon la dimension                    | Contrat d’isolation. Passage réel entre dimensions à rejouer                                     |

Le moteur sépare les lots non commencés, réservés, envoyés et attendus, puis les réceptions
partielles, complètes et les annulations. Le rapport natif ne dispose aujourd’hui que du physique
attesté. `fabrication_emi.json` agrège des crédits d’avancement par objectif. Il ne prouve ni
l’identité d’un lot, ni sa réservation, ni sa réception. Le test ouvre ce fichier temporaire
avec l’adaptateur réel et vérifie que ses crédits ne sont promus dans aucun de ces états. Les
quantités de lots sont donc inconnues dans Production. Les onglets EMI existants gardent leur
suivi. Les tests de pause, reprise, changement de projet, révision, déconnexion et rejeu
vérifient que le calcul V2 ne modifie ni le suivi ni la sauvegarde de progression.

### Consentement R5 indépendant

`codex/r5-consent` reste à `5105320`. Le précédent essai Minecraft a exercé la cible du
dialogue, la confirmation, Annuler et Échap, la sauvegarde et le refus de l’injection IA.
La présente revue a réussi 55 tests ciblés, puis sept vrais arrêts `SIGKILL` de processus
sur un plan fictif. Les points d’arrêt suivent la réservation durable, la sauvegarde,
l’écriture du temporaire à mi-chemin, sa synchronisation, le remplacement du primaire,
le moment précédant le reçu et le reçu publié avant nettoyage. Chaque reprise rejoue la
même demande trois fois et vérifie l’absence de second effacement, la sauvegarde exacte et
le refus d’une preuve associée à une autre connexion.

Le nouvel essai natif utilise le même JAR `.40` indépendant et une sonde réservée à la copie
Expert. Après 32,3 s, Confirmer refuse la proposition expirée et préserve le plan et sa
sauvegarde octet pour octet. Une déconnexion juste après le clic a vidé l’identité, retiré
la proposition et publié un reçu négatif. Le plan est resté intact. La sonde n’a pas mené à
terme son retour au menu dans ce dernier cas. Après redémarrage, deux activations du même
bouton Confirmer pendant le même tick ont gardé un seul identifiant de demande. Un seul
effacement a été confirmé et la sauvegarde correspondait exactement au plan fictif initial.

Une annulation parvenue après le dernier contrôle de la file et juste avant l’écriture peut
arriver trop tard. Le scénario reproductible commence avec une confirmation positive et
valide. Le plan fictif est alors effacé une seule fois et sa sauvegarde exacte est disponible.
Cette limite de retrait du consentement et la qualification native restante gardent R5 en
statut partiel. Le garde-fou publié sur `main` continue de refuser les suppressions proposées
par l’IA.

### Mesures avant et après

Le même scénario hors jeu, `python -m tests.benchmarks.production_v2`, a utilisé 2 024 recettes,
600 coffres et une chaîne de 24 étapes, avec 21 répétitions par série et ordre avant, après,
après, avant. Les temps médians du calcul passent de 6,46–10,97 ms à 6,71–11,30 ms et le
pic d’allocations reste à 0,756 Mio. Le catalogue est mesuré entre 36,70–72,15 ms avant et
33,37–47,86 ms après. L’agrégation va de 27,31–51,23 ms avant à 39,02–94,28 ms après.
Les essais partagent les ressources de la machine avec le laboratoire. La dispersion et
les moteurs de calcul identiques empêchent d’attribuer ces écarts au candidat.

Le nouveau contrôle hors moteur écrit deux petits documents au plus par attestation
réussie et attend au maximum 450 ms la réponse du client. Le client ne recalcule pas
l’arbre et ne réécrit pas le catalogue pour y répondre. Aucun contrôle n’est émis par
l’assistant au repos. Les aperçus V2 sont limités à un contrôle de flux toutes les 350 ms,
avec un contrôle avant affichage. Une réponse ou un aperçu en attente de preuve peut
donc être retardé. Les mesures du moteur excluent ce pont, le rendu et les CLI distants.

## Transition V1.5

| Prérequis                   | Résultat vérifié                                                                                                                                                                                                          |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Référence `origin/main`     | `5d280c3a949f4a6521fb1a4eed0303550dd25ec9` après récupération Git.                                                                                                                                                        |
| Candidat V1.5               | `1a56fe6f820332847ca80e4a9d5694a46e25b495`, ancêtre exact de `main`. Aucun commit V1.5 candidat manquant.                                                                                                                 |
| Différence avec le candidat | Rapport d’intégration, garde-fou R5 Python, instructions des deux moteurs et tests de non-régression.                                                                                                                     |
| V1.5 Python et Git          | Reprise sur copie figée de la référence, **479 tests et 26 scénarios EMI réussis** sur macOS. Le premier essai sur la copie de travail a rencontré une course de fixture de mise à jour pendant la création du paquet V2. |
| V1.5 Java et Forge          | Compilation JDK 17, contrats Java, Node et Rhino documentés dans la [qualification V1.5](qualification-v1.5.md).                                                                                                          |
| Windows 11 et lot 5         | Essais utilisateurs déclarés satisfaisants pour la V1.5. Détail des tests natifs et empreintes des JAR utilisés non établis. Migration personnelle non activée.                                                           |
| R5                          | Garde-fou publié. `codex/r5-consent` à `5105320384d5e9e7f56eb58e2662831f07f1e329` contient 5 commits locaux et 20 fichiers différents de `main`. Confirmation native non qualifiée en jeu.                                |
| P4                          | Reprise partielle. Une qualification distincte reste nécessaire pour les changements d’installation et les coupures brutales.                                                                                             |
| Catalogue                   | Le texte V1.5 et ses parties ne portent pas de preuve commune de génération avec les capacités. Leur exploitation exacte reste indisponible.                                                                              |
| Lot 2                       | **DEFERRED**. Relevé, permissions et scans serveur hors du chantier.                                                                                                                                                      |
| Démarrage V2                | **GO LIMITÉ** pour les contrats et moteurs isolés. Mutations du dialogue `BLOCKED_BY_R5`.                                                                                                                                 |

Le préflight ne lit pas les données personnelles du joueur. Les contrôles Forge et Windows
mentionnés dans les documents de référence conservent leur niveau de preuve d’origine.

## Chantiers V2

| Chantier             | Statut sur le périmètre V2 et preuve                                                                                                                                                                                                                                      |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| V2.1 · Connaissance  | **PARTIAL**. Vues isolées avec preuves, dates, historique et couverture. L’inventaire consolidé remplace le relevé KubeJS. Les coffres ambigus et les inventaires de type inconnu restent hors des allocations. L’intégration des acquisitions natives reste à qualifier. |
| V2.2 · Usines        | **PARTIAL**. Les groupes déclarés et les liens de recette sont testés. Un membre non revu reste dans le groupe. Une omission d’un relevé autorisé laisse la présence physique inconnue. Les données actuelles ne démontrent ni câblage ni réseau.                         |
| V2.3 · Diagnostics   | **PARTIAL**. Causes, preuves, contradictions et vérifications sont calculées sur fixtures. L’état GregTech actuel, la compatibilité et les stocks sont séparés. Une machine fermée, le circuit ou la tension peuvent rester inconnus.                                     |
| V2.4 · Planification | **PARTIAL**. Contrat exact et commande hors jeu validés. Lots entiers, variantes explicites, fluides en mB, catalyseurs, circuits, coproduits, réceptions, cycles et limites. La preuve structurée du catalogue du pack réel reste à produire et à qualifier.             |
| V2.5 · Mémoire       | **PARTIAL**. Lecture des sauvegardes et repli `.bak` sur copies. La pause et les étapes décochées sont conservées. Notes revues et corrections sont interrogeables sans écriture. La persistance des nouvelles notes reste à qualifier.                                   |
| V2.6 · Conversation  | **BLOCKED_BY_R5** pour les mutations. Les résultats calculés sont disponibles sous forme de contrats locaux. Le raccordement en lecture au dialogue et à l’interface dépend de la qualification de ses données et parcours natifs.                                        |
| V2.7 · Multijoueur   | **PARTIAL**. Positions datées et dimensions préservées dans les fixtures. Un serveur commun ne fusionne ni projets ni sessions des installations. Essais simultanés sur les deux postes à réaliser.                                                                       |
| V2.8 · Qualification | **PARTIAL**. Contrats et scénarios automatisés sur macOS. Les parcours Windows, NTFS et Minecraft réel restent à vérifier.                                                                                                                                                |

## Nature des preuves

- **Simulation.** Recettes et observations générées. Le scénario de calcul et de diagnostic teste
  un inventaire consolidé, deux lots d’alliage, un circuit déclaré et un état machine en attente.
  La machine peut signaler une attente sans que la cause du blocage soit établie.
- **Intégration locale.** La commande hors jeu lit un catalogue et une demande versionnés depuis
  des fichiers isolés. Les tests rejouent une publication remplacée en lecture, une génération
  incomplète, un changement de monde et la récupération d’une copie de sauvegarde. Les fixtures
  de mise à jour utilisent des dépôts temporaires. Les fournisseurs IA restent simulés.
- **Jeu réel.** R5 natif, provenance des paquets de backpack, reconnexions, filons, transferts EMI,
  présence multijoueur et fichiers verrouillés ont une campagne restante. Les preuves de Forge
  et Rhino établissent la compilation et les contrats locaux. Le lot 5 n’est pas activé ici.

La revue indépendante a reproduit la double allocation entre relevés successifs, la classification
erronée d’un inventaire de machine, le jumelage ambigu de coffres et la confusion entre les horloges
du serveur et du client. Les tests de régression couvrent ces cas. Sans révision commune prouvée,
la vue de calcul privilégie l’inventaire personnel, puis le dernier relevé serveur autorisé s’il est
la seule source de stock fraîche. Les observations plus anciennes restent consultables.

## Mesures locales

`python -m tests.benchmarks.production_v2` mesure séparément la validation du catalogue, la
construction de la connaissance et la planification. Sur macOS ARM64, 21 répétitions utilisent
les mêmes 2 024 recettes, 600 coffres et une chaîne de 24 étapes. Les pics concernent les
allocations Python par opération mesurées par `tracemalloc`.

| Opération                         | Temps médian |      P95 | CPU médian | Pic Python |
| --------------------------------- | -----------: | -------: | ---------: | ---------: |
| Lire et valider le catalogue      |     30,76 ms | 33,82 ms |   30,52 ms |   4,79 Mio |
| Construire la vue du monde        |     26,54 ms | 29,64 ms |   26,19 ms |   1,50 Mio |
| Calculer le plan depuis cette vue |      6,13 ms |  9,47 ms |    6,10 ms |   0,76 Mio |

La première version du lecteur réservait 64,01 Mio sur cette fixture. La lecture bornée par la
taille du fichier observé ramène le pic à 4,79 Mio tout en refusant un fichier modifié pendant la
lecture. Pour le parcours historique de Discussion, les sept empreintes du contexte V1.5 restent
identiques. La médiane des sept temps médians passe de 1,289 à 1,254 ms dans cet essai. La
différence de temps est insuffisante pour conclure à un gain. Ces mesures concernent des appels
hors jeu et ne renseignent ni les FPS ni le MSPT.

## Contrôles de livraison

Le code `8590e1bc1c471421301b49ea999b4c16b20b8e9a` passe **574 tests**, dont **95 tests dédiés V2**,
et **26 scénarios EMI** sur macOS avec Python 3.12. Ruff, Prettier, `pip check` et
`git diff --check` passent aussi. L’import des moteurs hors jeu n’ouvre
pas la configuration de l’instance. Les essais du planificateur comprennent une génération
déterministe de 60 chaînes et des cas de sortie aléatoire, fluide et contenant distincts, catalyseur,
réception, variante et cycle.

Le Java et le KubeJS sont identiques à la référence. La compilation de contrôle de `main`
sous JDK 17, le format Java, la syntaxe Node et les contrats sur le Rhino du pack passent.
Le fichier `assistantia-1.0.0-shared.40.jar` de ce contrôle porte l’empreinte SHA-256
`dead3bca58f6e8c66d2fbf5fbebeade4e0397eb96ed9d1abc0d2d7f43df71e3a`.

## Données et livraison

Le code V2 reste sur `codex/v2-limited`, issu de `5d280c3`. L’exemple et les benchmarks utilisent
des fichiers synthétiques et temporaires. Les formats existants, les scripts client et serveur,
les sessions des deux moteurs, les lanceurs et les règles d’installation sont identiques à `main`.
Le retrait de la branche de travail suffit pour revenir à la référence, sans restauration de données.

La décision **GO LIMITÉ** s’applique aux composants hors jeu. Le branchement au catalogue natif,
la qualification en jeu sur les deux postes, la confirmation R5 et la reprise P4 restent des étapes
distinctes avant publication de comportements intégrés. Le lot 2 reste reporté.
