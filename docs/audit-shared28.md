# Audit de stabilité — shared.28

Cette version corrige des défauts reproductibles du rendu, des stocks et des transports IA.
Elle conserve les données personnelles et le fonctionnement commun Claude/Codex sur `main`.
Ce bilan distingue les contrôles automatiques de ce qui reste à vérifier dans une vraie partie.

## Corrections

| Zone                   | Problème corrigé                                                                                                                                                                                             |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Luminosité             | Le mode gamma restaure la texture liée après sa mise à jour, même en cas d’erreur.                                                                                                                           |
| Contours et guidage    | Leurs buffers de rendu sont dédiés ; ils ne vident plus le buffer global de Minecraft.                                                                                                                       |
| Menus                  | La relance de l’assistant a sa propre rangée, sans recouvrir les onglets. La quantité d’une fabrication survit au redimensionnement ; Entrée confirme une seule fois.                                        |
| Base                   | Filtrage et tri réutilisés tant que le relevé et les filtres sont identiques.                                                                                                                                |
| Stocks                 | Les drawers et barrels voisins restent indépendants. Les doubles coffres connus sont comparés sur tout leur contenu, pas seulement les ingrédients cherchés.                                                 |
| Recherche EMI          | Regroupement par les quatre voisins, avec index, au lieu de comparer chaque coffre à tous les précédents. Même principe pour charger les relevés mémorisés.                                                  |
| État du jeu            | Une trame mal formée ou future ne remplace plus un relevé sain ; une ancienne trame expire normalement.                                                                                                      |
| Machines et avancement | Les paquets inventaire/sortie décalés sont rapprochés ; une preuve tardive ne double pas le crédit ni celui d’un ancien objet repris. Un lot manuel ne se termine plus en reprenant deux fois le même objet. |
| Backpack               | Un échec d’envoi natif ne laisse plus le sac bloqué en attente ; le calcul des réceptions évite les comparaisons entre objets sans rapport.                                                                  |
| Codex                  | Une demande expirant dans la file d’attente ne coupe plus la réponse déjà en cours. Le fichier de session n’est plus réécrit sans changement.                                                                |
| Claude/Codex           | Une erreur d’affichage de la réponse nettoie le tour interrompu, sans attribuer ses fragments à la question suivante ni rejouer la question.                                                                 |
| Suivi Discussion       | Les fichiers inchangés restent en cache ; les remplacements atomiques et le passage hors ligne restent détectés.                                                                                             |
| Windows                | Le verrou d’instance utilise un handle 64 bits et signale un échec de création.                                                                                                                              |

Le README est devenu un point d’entrée court. Les usages détaillés sont dans [Utilisation](utilisation.md),
les règles de collecte dans [EMI et machines](emi-transferts.md), et le travail à deux dans
[Collaboration](collaboration.md).

## Mesure locale de recherche

Sur un jeu de données synthétique de **5 000 coffres**, tous contenant l’objet recherché et espacés
de trois blocs, le calcul Python est passé d’environ **1,6 seconde à 40 ms** lors de la comparaison
locale. Cette mesure porte sur `chest_search`, pas sur les FPS, la latence réseau ou la réponse IA.
Elle n’est pas une garantie de temps sur un autre ordinateur.

## Vérifications

Validation locale finale : **208 tests réussis**, compilation Forge `build` réussie, contrôles
Ruff, Prettier, `checkJavaFormat` et `git diff --check` réussis.

Les tests rejouent les transports CLI simulés, l’affichage de discussion, les pauses, les mises à jour
sur de vrais dépôts temporaires, le calcul EMI, les transferts et les réceptions de sorties machines.
Les régressions ajoutées couvrent aussi les stockages voisins, les écritures partielles, les dates
invalides, les remplacements atomiques et la restauration de l’état de rendu.

Les contrats Java utilisent des interfaces de jeu simulées et compilent les classes testées.
La compilation Forge vérifie séparément la compatibilité avec les dépendances réelles. Les tests
n’envoient aucune requête IA et ne déplacent aucun objet dans une partie réelle.

## Validation en partie

Après installation, redémarrer l’assistant et Minecraft, puis vérifier :

1. Coffres visibles à proximité, avec gamma désactivé puis activé ; contours cyan lors d’un scan.
2. Deux drawers ou barrels voisins au même contenu : les quantités restent distinctes. Un double coffre partage son stock.
3. Objet déplacé dans le backpack, sac fermé : le stock reste présent dans Recipe Tree.
4. Sortie machine récupérée partiellement puis rangée : le lot et l’avancement ne demandent pas de la refabriquer.
5. Déclaration manuelle d’un lot : redimensionnement, validation unique, Échap vers le suivi.
6. Une question dans Discussion, fermeture/réouverture du menu et lecture du dernier message dans le panneau local.

Les coffres invisibles n’ont pas été reproduits avec certitude. Les défauts de rendu corrigés sont
confirmés dans le code et l’API locale, mais leur responsabilité dans ce symptôme reste à confirmer
avec Oculus/Embeddium et le serveur réels. Le test du mutex Windows est simulé ; aucun poste Windows
n’a été piloté pour cette validation.

L’ancien relevé serveur n’indique pas la moitié principale d’un coffre : deux coffres simples voisins
au contenu parfaitement identique peuvent encore être confondus avec un double coffre. Les stocks
mémorisés et les preuves de fabrication restent des observations du client, avec les limites décrites
dans [le guide EMI](emi-transferts.md).
