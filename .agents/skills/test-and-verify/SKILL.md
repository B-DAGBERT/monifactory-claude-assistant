---
name: test-and-verify
description: Qualifier une modification Monifactory déjà définie, choisir et exécuter les contrôles adaptés, puis produire des preuves PASS, PARTIAL ou FAIL. Utiliser pour une demande de tests ou de validation, sans transformer la qualification en déploiement.
---

# Qualifier une modification Monifactory

Lire `AGENTS.md`. Depuis la racine Git, prendre les commandes maintenues dans `docs/developpement.md` et les parcours dans `docs/scenarios.md`, plutôt que recopier une ancienne commande ou un ancien nombre de tests.

- Relier chaque comportement modifié à un contrôle. Pour EMI, vérifier l’allocation depuis le scan actif, résultat déjà disponible avant ses ingrédients, réservation partielle, retour du même objet, pause et annulation. Pour les échanges, vérifier fichiers incomplets, remplacements atomiques, réponses périmées et changements de contexte.
- Les suites Python couvrent aussi des contrats Java et des CLI IA simulés. Vérifier les prérequis du guide, dont Node pour KubeJS et le JDK 17 avec les bibliothèques Java en cache selon le test. Gradle direct utilise `JAVA_HOME`. `codex_setup.py build` résout également la configuration locale.
- Exécuter d’abord les tests du périmètre. Pour cibler un module qui importe un autre fichier de tests, utiliser `python -m unittest discover -s tests -p test_machine_inputs.py -v` avec le nom de fichier voulu. Pour une qualification de livraison, appliquer les contrôles de formatage, la suite et le build requis dans le guide. KubeJS est exclu du formatage automatique. Préserver son contrat de parité client et ses tests serveur.
- Les scénarios EMI écrivent leurs rapports dans `.runtime/scenarios/`, sans action dans la partie. Leur code de sortie `0` exige tous les cas réussis. Marquer PENDING un test ignoré ou un prérequis absent. Ne pas publier ces rapports locaux comme données du projet.
- Séparer preuves déterministes et validation en jeu. Pour vérifier le rendu GPU, le chargement des mixins et les permissions du serveur, tester en jeu. Un test de fichiers simulant Windows n’est pas une exécution Windows 11.

Rendre un bilan par périmètre avec commandes, résultats et limites. Attribuer **PASS** aux critères exécutés et satisfaits, **PARTIAL** si une preuve manque et **FAIL** pour un défaut reproduit. Marquer **PENDING** la validation réelle sur l’autre OS non disponible. Compilation, installation et publication restent des opérations distinctes.
