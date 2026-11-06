# Recette V2 à deux joueurs

Cette recette utilise le même code `a6a2d68dd56fa117d1d70a492733b4a709016ccd` sur Mac
et Windows, le JAR `1.0.0-shared.42` et le script KubeJS client 12. La branche
[`qualification/v2-shared42`](https://github.com/B-DAGBERT/monifactory-claude-assistant/tree/qualification/v2-shared42)
regroupe le candidat et ses procédures. Les preuves Mac en solo et les contrats simulés
sont consignés dans [le rapport de qualification](qualification-v2.md). Les essais à deux
commencent au statut **NOT RUN**.

## Préparer les deux postes

1. Installer sur chaque poste dans une copie de test selon [la recette Windows](recette-v2-windows.md)
   ou [les étapes Mac](integration-v2.md#rejouer-sur-macos-et-windows). Fermer les processus
   des copies avant l’installation. Comparer le commit, les versions chargées et le hash
   du JAR installé au build de chaque poste. Garder une copie du couple JAR et script précédent.
2. Choisir un serveur de test déjà autorisé et compatible avec les deux clients. Utiliser
   la même adresse et le même port normalisés sur les deux postes. Relever en privé
   l’identité opaque du monde et la session de chacun. Sur ce serveur, le monde doit
   correspondre. Les sessions restent propres à chaque connexion.
3. Garder les projets, configurations, sessions IA et mémoires de chaque joueur dans son
   propre checkout et sa propre instance. Vérifier qu’aucun assistant de test ne pointe vers
   l’instance habituelle. Laisser le lot 5 dans son état initial.
4. Préparer un objectif fictif en pause sur chaque poste, avec des noms distincts. Choisir un
   coffre de test accessible et un coffre déjà protégé par FTB auquel un des joueurs a accès
   et l’autre n’a jamais eu accès. Utiliser des ingrédients de test, jamais un stock personnel.
5. Laisser le serveur, FTB et son script KubeJS dans leur configuration actuelle. Si aucun
   coffre adapté, serveur autorisé ou second poste n’est disponible, laisser le cas à **NOT RUN**.

Les identifiants peuvent être comparés localement dans `kubejs/assistant/identite_monde.json`.
Noter uniquement les résultats « même monde » et « session renouvelée » dans le relevé partagé.
Ne pas publier le fichier, les noms des joueurs ni les coordonnées privées.

## Parcours commun

Le joueur A utilise Codex sur Mac et le joueur B Claude sur Windows. À la fin, intervertir
les rôles pour les deux essais de permissions si une zone de test existante le permet.

| ID    | Manipulation des deux joueurs                                                                                    | Attendu                                                                                                                                     | Résultat |
| ----- | ---------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| MP-01 | A et B se connectent simultanément. Chacun ouvre Y et Production.                                                | Les deux clients identifient le serveur et leur connexion.                                                                                  | NOT RUN  |
| MP-02 | A se déplace dans une zone de test observée, puis B fait de même.                                                | Positions réciproques accessibles dans le même périmètre qu’avant V2. Relever si le relevé facultatif est présent.                          | NOT RUN  |
| MP-03 | A passe dans une autre dimension. B reste en place.                                                              | Dimensions distinctes et résultat Production de la dimension précédente retiré.                                                             | NOT RUN  |
| MP-04 | A quitte via le bouton Minecraft. B observe la dernière position selon les données disponibles.                  | Observation datée ou retirée. Un ancien relevé reste identifiable comme historique.                                                         | NOT RUN  |
| MP-05 | A revient sur le même serveur et relance le calcul.                                                              | Monde reconnu, nouvelle session et nouvelle preuve. Aucune activation d’un résultat ancien.                                                 | NOT RUN  |
| MP-06 | A et B observent un coffre de test accessible et comparent le contenu visible.                                   | Les relevés autorisés restent disponibles dans les vues existantes. Production indique son propre périmètre d’allocation.                   | NOT RUN  |
| MP-07 | A contrôle le coffre protégé qu’il possède. B reste à portée de relevé et vérifie Base, EMI et les vues locales. | Les règles FTB et la portée existante restent intactes. B ne reçoit aucun nouvel inventaire privé. Le coffre n’est pas ouvert par B.        | NOT RUN  |
| MP-08 | A place un ingrédient dans un backpack porté et attend sa synchronisation. Déconnexion puis retour.              | La nouvelle connexion demande un nouveau relevé du sac. Aucune ancienne quantité n’est comptée deux fois.                                   | NOT RUN  |
| MP-09 | A et B choisissent des branches EMI différentes pour huit plaques de bronze.                                     | Chaque résultat garde son monde, sa dimension et sa session. Les coûts restent 6 cuivre et 2 étain ou 9 cuivre et 3 étain selon la branche. | NOT RUN  |
| MP-10 | A et B relisent chacun leur projet en pause, puis relancent leur assistant.                                      | Projets distincts, pauses conservées. Aucune copie d’une mémoire ou d’une conversation vers l’autre poste.                                  | NOT RUN  |
| MP-11 | Si chacun a autorisé son compte, demander un seul résumé du plan et des observations de test visibles.           | Codex et Claude distinguent les preuves des inconnues et gardent les informations multijoueurs déjà autorisées.                             | NOT RUN  |
| MP-12 | Rejouer dans le monde Expert local de test ou sur un serveur de test qui n’a déjà pas le script facultatif.      | EMI, Production et l’inventaire personnel restent utilisables. Les données de serveur non attestées sont signalées.                         | NOT RUN  |

## Stocks et lot de test

Choisir la route cintreuse, stock vide au départ. Placer **3 cuivre et 1 étain** dans les poches,
**1 cuivre** dans le backpack porté et **2 cuivre et 1 étain** entre un coffre simple et un
double. Refaire l’essai avec un Sophisticated Storage accessible. Pour chaque vue, noter
la quantité observée, la quantité effectivement allouée, les manques et la date du relevé.
Dans le candidat actuel, Production compte les poches et le sac synchronisé. Le scan des
coffres EMI est distinct. Lorsque le sac est attesté, la vue Production devrait retenir
4 cuivre et 1 étain et laisser 2 cuivre et 1 étain à obtenir selon ses relevés. Le stock
physique total connu par les joueurs est plus large.

Déplacer ensuite un lingot du coffre vers les poches, reconsulter le coffre puis recalculer.
Comparer les observations courantes et historiques. Vérifier que les deux moitiés d’un coffre
double ne produisent pas deux allocations dans la même vue. Si la vue Production ne consomme
pas cette source, consigner **PARTIAL**, sans la compter comme preuve de dédoublonnage natif.

Dans une machine de test déjà alimentée, déclarer un petit lot avec les commandes EMI existantes.
Relever successivement la réservation, les entrées envoyées, la sortie attendue, une réception
partielle et l’annulation ou la fin du lot. Mettre le projet en pause pendant une étape, puis
rejouer avec une déconnexion et une reconnexion. Comparer **Suivi EMI**, **Production** et le
crédit déjà acquis. Une sortie attendue ne vaut pas stock physique et une réception doit rester
comptée une seule fois. Le candidat ne reçoit pas dans Production un identifiant fiable pour
les lots du Suivi. Un lot inconnu de Production reste **PARTIAL**, même si le suivi EMI réussit.

## Relevé commun

Pour chaque cas, noter le statut **PASS**, **FAIL**, **PARTIAL** ou **NOT RUN** et son niveau
**AUTOMATED**, **LOCAL_INTEGRATION**, **IN_GAME**, **USER_CONFIRMED** ou **NOT_TESTED**. Joindre
le système, le SHA Git, le JAR chargé et son empreinte, le script 12, les opérations et les
résultats. Garder les journaux complets sur chaque poste. Partager uniquement les extraits
expurgés nécessaires pour reproduire un écart. Un résultat confirmé sur un poste reste distinct
du résultat de l’autre.
