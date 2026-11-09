# Assistant Monifactory — Claude et Codex

Assistant pour Monifactory avec Claude Code et Codex. Il analyse l’arbre choisi dans EMI (ordre
des étapes, meilleures recettes), retrouve les ingrédients dans les coffres et suit les fabrications
GregTech. Il répond aussi aux questions sur les objets et aide à scanner et suivre les filons connus.

Les deux moteurs utilisent **le même mod et la branche `main`**. Le compte IA se choisit sur
chaque poste. Les conversations, relevés de coffres et filons restent locaux.

## Démarrer

1. Installer Python 3.12 avec Tk, Git, un JDK 17 et le CLI de Claude ou Codex connecté.
2. Ouvrir **Choisir IA.command** sur Mac ou **Choisir IA.bat** sur Windows.
3. Fermer Minecraft, puis lancer **Installer Mac.command** ou **Installer.bat**.
4. Lancer **Lancer Mac.command** ou **Lancer assistant.bat**, puis Minecraft.
5. **Y** ouvre l’assistant sur l’onglet **Recette** ; **I** explique le bloc ou l’objet.

[Installation détaillée, configuration et dépannage](docs/installation.md)

## Trouver la bonne fonction

| Besoin                                    | Où aller                                                                                                       |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Préparer le craft complet                 | Choisir l’objet et ses branches dans **Recipe Tree**.                                                          |
| Savoir quoi fabriquer et dans quel ordre  | **Y → Recette** : plan numéroté ; la prochaine étape s’affiche en haut à gauche.                               |
| Trouver une meilleure recette             | **Y → Recette → Meilleures alternatives**, puis **Appliquer** (cet arbre seulement).                           |
| Calculer les besoins de production        | **Y → Base → Production…**, depuis l’arbre EMI ou un coffre ouvert pour utiliser son relevé courant.           |
| Chercher les ingrédients                  | **Coffres**, à droite de Recipe Tree, doré pendant le scan. Un second clic arrête le scan.                     |
| Récupérer dans un coffre                  | Panneau **Collecte → Prendre / Tout**, pour les besoins du scan actif.                                         |
| Utiliser une machine                      | Contour rouge numéroté, puis **Envoyer** dans sa fenêtre. Récupérer soi-même les sorties.                      |
| Déclarer une fabrication lancée à la main | **Suivi → En cours de craft**, quantité puis confirmation.                                                     |
| Suivre les lots et les priorités          | **Suivi**, groupes À lancer maintenant / À préparer. Le panneau machine classe aussi ses recettes compatibles. |
| Voir les stocks ou les filons connus      | **Y → Base / Filons**.                                                                                         |
| Poser une question                        | **Y → Discussion**.                                                                                            |
| Comprendre un problème                    | **Y → Réglages → Diagnostic…** : connexion, inventaire, EMI, transferts et erreurs.                            |
| Régler l’affichage et la luminosité       | **Y → Réglages**.                                                                                              |
| Choisir les modèles et la réflexion       | **Y → Réglages → Modèles IA…**, choisir dans la liste puis **Appliquer** pour les prochaines demandes.         |

Le contenu des **Sophisticated Backpacks portés** compte dans les besoins, EMI et l’avancement.
Les boutons de transfert utilisent les cases accessibles : sortir les ingrédients du sac pour les envoyer.
**Filons → Scanner** parcourt tout le volume du filon et le compte restant suit le minage.
**Vue**, **Blocs**, **Épaisseur**, **Intensité** et **Remplissage** règlent la surbrillance, sans
toucher aux relevés.
Dans EMI, **Sac N** indique les objets déjà sur toi dans le backpack et permet de les retrouver.

L’onglet Recette, le scan, les retraits, le suivi des machines et les filons ne font **aucun appel IA**.

La Discussion et la voix peuvent expliquer les opérations ; elles n’effacent ni ne remplacent les
mémoires locales (lieux, coffres, filons). Les anciens onglets Objectif et Projet et la touche F7
ont été retirés.

## Guides

- [Utilisation des menus, de l’onglet Recette et des filons](docs/utilisation.md)
- [Collecte EMI, machines, backpack et limites du suivi](docs/emi-transferts.md)
- [Ordre des fabrications, priorités et qualification shared.46](docs/ordonnancement-machines.md)
- [Conversation Codex et panneau en lecture seule](docs/codex.md)
- [Partager une fonctionnalité avec un collègue](docs/collaboration.md)
- [Scénarios EMI basiques et complexes à la demande](docs/scenarios.md)
- [Production shared.45, preuves natives et qualification Mac/Windows](docs/qualification-v2-native-stock-lots.md)
- [Organisation du code, formatage et tests](docs/developpement.md)
- [Qualification de l’isolation des mondes et procédure de restauration](docs/isolation-mondes.md)
- [Audit d’architecture V1.5](docs/audit-architecture-v1.5.md) et [qualification des correctifs](docs/qualification-v1.5.md)
- [Corrections et validation de la version shared.28](docs/audit-shared28.md)
- [Documentation historique de la version Claude](docs/historique-claude.md)

## Mises à jour

Lancer **l’assistant avant Minecraft** : il vérifie `main` et installe les nouveautés validées avec
sauvegarde. Si le jeu est ouvert, l’installation automatique est reportée. Sans réseau, la version
locale démarre. Le bouton **Masquer** ferme l’avis de cette version.

Un changement du mod Java demande de **quitter complètement puis relancer Minecraft**.
Changer de monde ou recharger les ressources ne recharge pas le mod. Après une modification Python,
relancer aussi l’assistant. Une installation ancienne peut nécessiter **Mettre à jour** une première fois.
Tant que l’ancien mod reste chargé, un bouton retiré (Objectif suivant, Mettre en pause…) répond
« Fonction retirée : relance Minecraft pour charger la nouvelle version du mod. ».

## Développer

Le code Python est dans `monifactory_assistant/`, le mod Forge dans `mod/`, les tests dans `tests/`
et les guides dans `docs/`. Les lanceurs restent à la racine pour les installations existantes.

Travailler sur une branche issue de `main`, valider, fusionner puis publier sur `main`.
Les commandes et le parcours à deux sont dans [le guide de collaboration](docs/collaboration.md).
Ne jamais ajouter les configurations privées, sessions, journaux ou données de jeu au dépôt.

## Contributeurs

- [B-DAGBERT](https://github.com/B-DAGBERT), créateur du projet.
- [Damien Alengrin](https://github.com/damien34760), contributeur à la prise en charge de Codex,
  à l’intégration EMI et au suivi des fabrications GregTech.
