# Qualification de Monifactory Assistant V2

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
