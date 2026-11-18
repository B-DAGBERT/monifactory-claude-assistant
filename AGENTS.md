# Travail dans ce dépôt

## Démarrer une modification

1. Lire la fiche du parcours concerné ci-dessous. Suivre ses points d’entrée jusqu’au code qui produit et consomme les données.
2. Pour un bug en jeu, relever la version chargée, le geste, le résultat attendu et le résultat affiché. Quand c’est accessible, vérifier les relevés locaux avant de conclure. Un objet présent dans les poches ne prouve pas à lui seul l’origine d’une réception.
3. Reproduire le problème au niveau le plus proche du geste. Lancer les tests de la fiche, puis ceux des autres parcours qui utilisent la règle modifiée.
4. Mettre à jour la fiche si un point d’entrée, une règle ou un test de référence change. Les fiches orientent la lecture. Le code et les tests font foi.

## Trouver le bon parcours

| Sujet                                                                       | Fiche                                                                       |
| --------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Arbre EMI, variantes, recettes, moules, circuits, remplissage de l’établi   | [EMI et recettes](docs/fonctionnalites/emi.md)                              |
| Coffre qui brille, objets à prendre, seaux et fûts, boutons Prendre et Tout | [Repérage et collecte](docs/fonctionnalites/reperage-collecte.md)           |
| Poches, backpack, coffre tampon, stockage exclu                             | [Stock personnel et tampon](docs/fonctionnalites/stock-local.md)            |
| Terminal AE2, quotas et retraits par piles                                  | [Réseau ME](docs/fonctionnalites/reseau-me.md)                              |
| Envoyer, reprise, cuves, sorties, lots et bouton Terminer                   | [GregTech et Suivi](docs/fonctionnalites/gregtech-suivi.md)                 |
| Production, preuves de stock, anciens projets et pauses                     | [Production et anciens projets](docs/fonctionnalites/production-projets.md) |
| Base, machines regroupées, mémoire des stockages                            | [Base et mémoire](docs/fonctionnalites/base.md)                             |
| Scan GregTech, surbrillance et téléportation                                | [Filons](docs/fonctionnalites/filons.md)                                    |
| Discussion, voix, fiches d’objets, Codex et Claude                          | [Discussion et moteurs IA](docs/fonctionnalites/discussion-ia.md)           |
| Menus, HUD, Échap, double clic, réglages et modèles                         | [Interface et réglages](docs/fonctionnalites/interface-reglages.md)         |
| Échanges Forge/KubeJS/Python, demandes, session et reconnexion              | [Pont et mondes](docs/fonctionnalites/pont-mondes.md)                       |
| Compilation, installation, auto-update et retour arrière                    | [Installation et versions](docs/fonctionnalites/installation.md)            |

## Règles communes

- `main` est la source commune de vérité pour Claude et Codex. À la demande de Damien, valider les changements terminés, créer un commit et l’intégrer à `main` avant de pousser. Respecter les consignes de la session sur la publication et l’installation.
- Préserver les configurations, conversations, objectifs, coffres et autres données locales ignorées par Git.
- Un changement du stock, de l’allocation ou des lots se vérifie dans **EMI**, **Repérage**, **Réseau ME** et **Envoyer** quand ces parcours consomment la même règle. Réutiliser le calcul commun. Chaque adaptateur conserve ses droits et ses sources de transfert. [Scénario commun du tampon](docs/developpement.md#recette-coffre-tampon-en-jeu)
- Un changement de menu, de mixin ou d’échange natif se vérifie en jeu sur une copie. Regrouper les essais dans la même session quand c’est possible, couper l’audio de la copie et la fermer à la fin. Consigner les essais réellement joués séparément des simulations.
- Vérifier le format et les tests adaptés. Compiler avec JDK 17 si les sources du mod changent. Avant chaque publication, exécuter `python -m monifactory_assistant.commands.scenarios tous`. Un échec ou un test ignoré bloque cette vérification. [Commandes de développement](docs/developpement.md#formatage-et-tests)
- L’installation remplace les fichiers du mod quand Minecraft est fermé. Les modifications Java prennent effet au redémarrage du jeu.
