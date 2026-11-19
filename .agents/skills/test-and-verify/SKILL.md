---
name: test-and-verify
description: Concevoir et exécuter les tests ciblés d’un changement Monifactory, élargir une campagne de non-régression et distinguer les preuves automatisées des essais joués.
---

# Qualifier un comportement Monifactory

Choisir les commandes dans le [guide de développement](../../../docs/developpement.md#formatage-et-tests)
et les périmètres dans [Scénarios EMI](../../../docs/scenarios.md). Écrire le résultat
attendu avant le test et relever les quantités ou états réels à chaque étape.

- Pour un transfert, contrôler source, destination, curseur et lot. Rejouer une place
  partielle, une interruption et une reprise. Lorsque la règle de stock est commune,
  comparer EMI, Repérage, Réseau ME et Envoyer avec le même besoin.
- Pour un crédit, faire suivre un dépôt déjà confirmé d’une resynchronisation tardive
  et d’un gain distinct du même type. Vérifier que chaque unité est attribuée une fois
  et que seul un lot réellement complet clôt le suivi.
- Pour un résultat du pont, rejouer aussi un paquet ancien après changement de connexion,
  de monde ou de dimension. Pour une reprise d’installation, utiliser des fichiers et
  dépôts temporaires.

Les suites Python peuvent compiler des contrats Java. Utiliser `JAVA_HOME` avec le
JDK 17 pour Gradle et suivre les bibliothèques requises par le guide. Pour un module
qui importe d’autres fixtures de `tests/`, utiliser la découverte ciblée, par exemple
`python -m unittest discover -s tests -p test_machine_inputs.py -v`.

Pour les essais réels, réutiliser la [recette en jeu](../../../docs/recette-fabrication.md).
Rapporter la commande, la révision ou le JAR réellement chargé, le résultat et le
niveau de preuve. Un test ignoré ou une plateforme indisponible reste à valider.
Documenter un échec de préparation du scénario et la mesure native disponible séparément.
