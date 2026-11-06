# Skills Codex du projet

Ouvrir le dépôt cloné dans Codex. Les skills de `.agents/skills/` sont découverts depuis la racine
et ses sous-dossiers. Dans une conversation du projet, taper `$` puis choisir le skill, par exemple
`$bug-investigation`. Une nouvelle conversation sur le même dépôt retrouve le même catalogue. Si
le catalogue n’apparaît pas après un changement de branche, rouvrir la conversation dans le bon
checkout ou relancer Codex. [Documentation officielle de Codex](https://learn.chatgpt.com/docs/build-skills#where-codex-loads-local-skills).

`AGENTS.md` porte les règles communes. Les skills décrivent des workflows et restent soumis aux
autorisations de la session. Si un sous-module a un besoin durable, son propre `.agents/skills/`
peut compléter le catalogue avec des noms distincts. Les skills racine restent disponibles à
ses agents. Les adaptateurs Claude Code et Codex CLI qui répondent dans Minecraft restent des
composants applicatifs distincts des agents de développement.

## Audit du dépôt

- Python assemble l’assistant, les données locales, les lanceurs et les adaptateurs Claude/Codex.
  Java 17 et Forge 1.20.1 assurent les intégrations du mod. KubeJS fournit les relevés client et
  serveur. EMI et GregTech ont des règles natives décrites dans le [guide de développement](developpement.md).
- Les tests déterministes utilisent Python, Node.js et, selon le périmètre, le JDK. Les commandes
  de développement restent dans ce guide. Les workflows GitHub Actions ont été retirés pour le quota.
- `main` est la branche commune et la source des mises à jour. Les changements se préparent sur
  une branche de travail. Les sessions IA, sauvegardes, historiques et relevés personnels restent
  hors des commits. Les tests de sécurité et de migration utilisent des copies ou des fixtures.
- Le dépôt de départ ne contenait aucun skill versionné ni profil d’agent de développement
  spécialisé. Le catalogue ci-dessous est entièrement nouveau.

## Catalogue

| Skill                   | À utiliser pour                                                                   | Cas de qualification                                                            |
| ----------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `architecture-review`   | Cartographier les contrats entre Forge, KubeJS, Python et les fournisseurs IA     | Suivre un relevé de coffre jusqu’à sa consommation Python                       |
| `feature-development`   | Définir puis implémenter un comportement à travers ses vrais points d’entrée      | Cadrer un statut de scan dans l’onglet Filons                                   |
| `bug-investigation`     | Reproduire une panne et isoler sa cause avant de corriger                         | Rejouer une trame de jeu partielle ou périmée                                   |
| `test-and-verify`       | Sélectionner les vérifications et qualifier leurs preuves                         | Rejouer un paquet d’une ancienne session et identifier le reste à tester en jeu |
| `code-review`           | Relire un diff et remonter les régressions actionnables                           | Relire la protection du lot 5 contre les relevés retardés                       |
| `security-audit`        | Examiner les permissions, les écritures, les secrets ou les dépendances           | Rejouer un reçu de migration copié depuis une autre installation                |
| `git-worktree`          | Isoler des chantiers concurrents sans mélanger les modifications                  | Examiner les branches et fichiers locaux avant un chantier parallèle            |
| `documentation-sync`    | Aligner les procédures sur le code et les résultats observés                      | Comparer le guide de sauvegarde avec les commandes réellement exposées          |
| `release-readiness`     | Donner un verdict de livraison à partir des preuves et des risques de déploiement | Qualifier le lot 5 en distinguant tests locaux et Windows réel                  |
| `minecraft-integration` | Vérifier un contrat natif Forge, KubeJS, EMI ou GregTech                          | Contrôler le scénario du réglage de circuit virtuel avant envoi                 |

`ci-diagnostics` est écarté tant que les vérifications se font localement. Le catalogue n’inclut
ni les workflows React, Vercel ou PostgreSQL, ni des skills séparés pour chaque API Minecraft.
Le dépôt possède déjà un guide de collaboration et un skill Git couvre le besoin d’isolation.

## Références de conception

- [Catalogue OpenAI](https://github.com/openai/skills) pour le découpage du format, avec la
  documentation officielle comme référence actuelle pour la découverte locale.
- [Vercel Agent Skills](https://github.com/vercel-labs/agent-skills) pour des descriptions de
  déclenchement précises et des workflows qui ciblent les technologies présentes.
- [Supabase Agent Skills](https://github.com/supabase/agent-skills) pour la séparation entre
  instructions de départ et références détaillées quand le sujet l’exige.
- [Superpowers](https://github.com/obra/superpowers) pour la séparation entre reproduction,
  revue et preuve de validation.

Les instructions sont rédigées pour Monifactory. Aucun fichier, script ou composant de ces
catalogues n’est redistribué.

## Qualification

Une installation compatible de Codex suffit pour charger les `SKILL.md`. Une tâche peut demander
Git ou les outils déjà décrits dans le guide de développement. Les chemins partent de la racine
du dépôt. Sur Windows, utiliser `python` ou `py -3` selon l’installation, `Tester.bat` pour les
scénarios et `mod\gradlew.bat` pour les commandes Gradle. Sur macOS, utiliser les commandes du
guide et `Tester Mac.command` pour les scénarios.

| Contrôle                              | Résultat                                                                                                                                                                                                                                                       |
| ------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Format, liens, chemins et dépendances | **PASS**. Les dix fichiers passent le validateur `SKILL.md` et `npm run format:check`.                                                                                                                                                                         |
| Découverte sur macOS Apple Silicon    | **PASS** avec Codex CLI `0.167.0-alpha.3.2`. Dix skills depuis la racine, `mod/` et `monifactory_assistant/`. Aucun skill Monifactory depuis un dépôt indépendant.                                                                                             |
| Activation et usage                   | **PASS** pour l’invocation explicite. Le contenu intégral de chaque skill arrive à un moteur simulé local. Les workflows du catalogue ont été exercés en lecture seule sur le dépôt et des fixtures. La sélection automatique par le modèle reste **PENDING**. |
| Vérifications représentatives         | **PASS**. 62 tests locaux sur les trames, le routage, les sauvegardes et migrations, les machines, les transferts et la mise à jour. Un dépôt Git jetable a également permis de vérifier la conservation d’un travail en cours.                                |
| Chargement sur Windows 11             | **PENDING**. Les chemins sont portables et la découverte reste à vérifier sur ce système.                                                                                                                                                                      |

Pour un contrôle manuel après clonage, vérifier le catalogue avec `$` dans une conversation à
la racine et une conversation ouverte depuis `mod/`. Sélectionner `$bug-investigation` pour
examiner une trame périmée en lecture seule, puis vérifier qu’une conversation ouverte dans un
autre dépôt ne propose pas les skills de Monifactory. Après une modification de contenu, relire
les liens, les exemples et les autorisations avant de publier.
