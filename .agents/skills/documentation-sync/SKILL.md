---
name: documentation-sync
description: Synchroniser les guides Monifactory avec une évolution validée des menus, d'EMI, des moteurs IA, de l'installation ou des protocoles. À utiliser pour une demande de documentation ou lorsqu'un changement modifie réellement un parcours, une commande, une limite ou une procédure de maintenance.
---

# Synchroniser la documentation

Les chemins cités partent de la racine Git. Partir du diff et du comportement final vérifié, puis lire `AGENTS.md` et les guides concernés. Utiliser le code pour décrire l’implémentation et les résultats d’essai pour qualifier son état.

## Choisir le bon guide

| Changement                                             | Documentation à vérifier                                 |
| ------------------------------------------------------ | -------------------------------------------------------- |
| Accès à une fonction ou parcours débutant              | `README.md`, puis `docs/utilisation.md`                  |
| Recipe Tree, collecte, machines, lots ou backpack      | `docs/emi-transferts.md`                                 |
| Discussion et lecture seule Codex                      | `docs/codex.md`                                          |
| Lanceurs Mac/Windows, configuration et mise à jour     | `docs/installation.md`                                   |
| Organisation des modules, tests et interfaces internes | `docs/developpement.md`                                  |
| Travail partagé, branches et livraison                 | `docs/collaboration.md`                                  |
| Scénarios déterministes                                | `docs/scenarios.md`                                      |
| Sauvegarde, migration et isolation par monde           | `docs/sauvegarde-donnees.md`, `docs/isolation-mondes.md` |

Mettre le détail dans le guide spécialisé et un lien court dans le README si le point d'entrée change. Ne pas réécrire `docs/historique-claude.md` ni un rapport d'audit ancien pour lui faire décrire la version actuelle. Actualiser un verdict de qualification seulement avec de nouvelles preuves, en conservant sa référence, son environnement et ses limites.

## Écrire ce que le joueur ou le développeur peut vérifier

- Employer les libellés réellement présents dans les écrans. Expliquer l'action et son résultat avant les détails internes.
- Distinguer `build`, installation et chargement effectif du JAR. Un changement Java nécessite un redémarrage complet de Minecraft. Un changement Python nécessite celui de l'assistant. Vérifier les commandes dans les scripts actuels et les variantes de lanceur Mac/Windows.
- Décrire les limites observées pour la pause des objectifs, inventaires accessibles aux transferts, acquittements serveur, données et comptes locaux. Ne pas promettre l'envoi depuis un backpack simplement parce que son contenu est compté.
- Vérifier les liens et les chemins avec les fichiers suivis du dépôt. Utiliser des exemples fictifs et des chemins relatifs au projet.
- En cas de contradiction entre deux guides, confronter au code et aux contrôles réellement disponibles. Les workflows GitHub Actions ont été supprimés pour préserver le quota. Décrire les contrôles locaux en vigueur.

## Vérifier la mise à jour

Relire le diff et exécuter `npm run format:check`, qui couvre les guides, le panneau web et les skills du projet. Pour corriger un écart, cibler les fichiers modifiés et signaler les écarts préexistants ailleurs.

Pour une modification exclusivement documentaire, vérifier commandes, liens, libellés et portée suffit généralement. Rapporter les guides modifiés et les vérifications faites. Ce skill n'autorise ni publication Git ni changement d'une installation.
