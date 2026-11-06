---
name: release-readiness
description: Évaluer si une version ou un lot Monifactory est prêt à être intégré ou livré. Examiner les preuves du diff final, la compatibilité, la conservation des données et l’impact de la mise à jour avant un jalon demandé.
---

# Qualifier une livraison

Lire `AGENTS.md`, `docs/developpement.md` et `docs/collaboration.md` depuis la racine Git. Noter la branche, le SHA à livrer, le périmètre du diff et la décision attendue. Consulter le rapport du lot s’il existe.

## Réunir les preuves du diff final

- Sélectionner les contrôles avec `test-and-verify` et le guide de développement. Pour une livraison de code, vérifier la suite de régression, le format et la compilation JDK 17 si le mod change. Pour un changement de documentation ou de skills, vérifier la découverte et les workflows concernés. Les contrôles locaux tiennent lieu de CI dans ce dépôt.
- Associer à chaque preuve la commande, le code de retour, l’environnement et la révision réellement testée. Refaire les contrôles touchés par une fusion ou un changement de code. Distinguer tests de contrats, jeu réel et Windows 11.
- Pour une version du mod, vérifier la cohérence de `mod/gradle.properties`, du JAR produit dans `mod/build/libs/` et de la documentation. Distinguer la compilation, l’installation et la version chargée après un redémarrage complet de Minecraft.

## Vérifier les conséquences de la livraison

- `main` est la source de la mise à jour automatique de l’assistant. Examiner ce qui se passera quand le collègue redémarrera son assistant, dont le report du remplacement du JAR tant que le jeu tourne. Les tests de mise à jour utilisent des dépôts temporaires.
- Contrôler que le diff préserve les objectifs en pause, stocks, sessions et configurations propres à chaque poste. Un nouveau format ou une migration exige une sauvegarde et une restauration vérifiées. Consulter `docs/isolation-mondes.md` pour l’activation explicite par installation.
- Vérifier le contenu du commit, y compris les fichiers générés et les changements d’autres agents. Réserver le commit, la fusion, le push, l’installation et l’activation aux actions autorisées dans la session.

## Donner le verdict

Pour chaque critère, marquer **PASS** si le contrôle exécuté réussit, **FAIL** si un défaut est constaté ou **PENDING** si le contrôle reste à faire. Le lot est **PARTIAL** lorsqu’une preuve essentielle manque. Identifier le périmètre prêt, les défauts et les validations restantes en indiquant leur impact sur la décision de livraison.
