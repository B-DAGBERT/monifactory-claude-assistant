# Assistant Monifactory — Claude et Codex

Un assistant dans Minecraft pour comprendre un bloc, suivre un objectif, préparer ses crafts dans
EMI et retrouver ses ingrédients. Les deux moteurs utilisent **le même mod et la branche `main`**.
Le compte IA se choisit sur chaque poste ; les conversations, coffres et objectifs restent locaux.

## Démarrer

1. Installer Python 3.12 avec Tk, Git, un JDK 17 et le CLI de Claude ou Codex connecté.
2. Ouvrir **Choisir IA.command** sur Mac ou **Choisir IA.bat** sur Windows.
3. Fermer Minecraft, puis lancer **Installer Mac.command** ou **Installer.bat**.
4. Lancer **Lancer Mac.command** ou **Lancer assistant.bat**, puis Minecraft.
5. **Y** ouvre l’assistant ; **I** explique le bloc ou l’objet.

[Installation détaillée, configuration et dépannage](docs/installation.md)

## Trouver la bonne fonction

| Besoin                                    | Où aller                                                                                               |
| ----------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| Avancer étape par étape                   | **Y → Objectif** ; **Mes projets ▾** pour ouvrir, créer, renommer ou supprimer.                        |
| Mettre un objectif en attente             | **Objectif / Projet → Mettre en pause**. Le statut apparaît dans les deux onglets.                     |
| Préparer le craft complet                 | **Objectif EMI**, puis choisir les branches de **Recipe Tree**.                                        |
| Chercher les ingrédients                  | **Coffres**, à droite de Recipe Tree. Un second clic arrête le scan.                                   |
| Récupérer dans un coffre                  | Panneau **Collecte → Prendre / Tout**, pour les besoins du scan actif.                                 |
| Utiliser une machine                      | Contour rouge, puis **Envoyer** dans sa fenêtre. Récupérer soi-même les sorties.                       |
| Déclarer une fabrication lancée à la main | **Suivi → En cours de craft**, quantité puis confirmation.                                             |
| Voir les stocks ou les filons connus      | **Y → Base / Filons**.                                                                                 |
| Poser une question                        | **Y → Discussion**.                                                                                    |
| Comprendre un problème                    | **Y → Réglages → Diagnostic…** : connexion, inventaire, EMI, transferts et erreurs.                    |
| Régler l’affichage et la luminosité       | **Y → Réglages**, onglet le plus à droite.                                                             |
| Choisir les modèles et la réflexion       | **Y → Réglages → Modèles IA…**, choisir dans la liste puis **Appliquer** pour les prochaines demandes. |

Le contenu des **Sophisticated Backpacks portés** compte dans les besoins, EMI et l’avancement.
Les boutons de transfert utilisent les cases accessibles : sortir les ingrédients du sac pour les envoyer.
**Filons → Vue : Surface / Total** et **Blocs : …** règlent directement la visibilité et le nombre
de contours, sans toucher aux relevés.
Dans EMI, **Sac N** indique les objets déjà sur toi dans le backpack et permet de les retrouver.

Le scan, les retraits, le suivi des machines et les filons ne font **aucun appel IA**.

## Guides

- [Utilisation des menus, objectifs et filons](docs/utilisation.md)
- [Collecte EMI, machines, backpack et limites du suivi](docs/emi-transferts.md)
- [Conversation Codex et panneau en lecture seule](docs/codex.md)
- [Partager une fonctionnalité avec un collègue](docs/collaboration.md)
- [Organisation du code, formatage et tests](docs/developpement.md)
- [Corrections et validation de la version shared.28](docs/audit-shared28.md)
- [Documentation historique de la version Claude](docs/historique-claude.md)

## Mises à jour

Lancer **l’assistant avant Minecraft** : il vérifie `main` et installe les nouveautés validées avec
sauvegarde. Si le jeu est ouvert, l’installation automatique est reportée. Sans réseau, la version
locale démarre. Le bouton **Masquer** ferme l’avis de cette version.

Un changement du mod Java demande de **quitter complètement puis relancer Minecraft**.
Changer de monde ou recharger les ressources ne recharge pas le mod. Après une modification Python,
relancer aussi l’assistant. Une installation ancienne peut nécessiter **Mettre à jour** une première fois.

## Développer

Le code Python est dans `monifactory_assistant/`, le mod Forge dans `mod/`, les tests dans `tests/`
et les guides dans `docs/`. Les lanceurs restent à la racine pour les installations existantes.

Travailler sur une branche issue de `main`, valider, fusionner puis publier sur `main`.
Les commandes et le parcours à deux sont dans [le guide de collaboration](docs/collaboration.md).
Ne jamais ajouter les configurations privées, sessions, journaux ou données de jeu au dépôt.
