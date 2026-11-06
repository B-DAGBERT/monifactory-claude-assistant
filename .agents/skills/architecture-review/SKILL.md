---
name: architecture-review
description: Évaluer une proposition de découpage, de dépendance, de persistance ou de performance dans Monifactory Assistant, en suivant les frontières Python, Forge, KubeJS et moteurs IA. Pour relire un diff sans décision architecturale, préférer code-review.
---

# Examiner une décision d’architecture

Lire `AGENTS.md` et les repères de `docs/developpement.md`. Les chemins partent de la racine Git. Une demande d’analyse reste en lecture seule tant qu’une implémentation n’est pas demandée.

## Suivre la responsabilité réelle

- `monifactory_assistant/app.py` assemble l’application. `monifactory_assistant/application/` orchestre les projets, échanges et conversations. `monifactory_assistant/game/` calcule et conserve les observations. `monifactory_assistant/ui/views.py` expose les données d’écran. `tests/app_source.py` reconstitue cette application pour les tests de parité.
- `mod/src/main/java/fr/assistantia/` possède les écrans et interactions du client. `jeu/assistant_ia.js` relie les événements KubeJS aux fichiers et `serveur/assistant_ia_serveur.js` produit des observations serveur. Identifier le propriétaire d’un état avant de proposer un second calcul ou cache dans une autre couche.
- `monifactory_assistant/providers/` adapte Claude et Codex. Les calculs EMI, stocks et filons ne passent pas par une IA. `monifactory_assistant/ui/discussion.py` expose la conversation en lecture seule.

## Examiner les frontières touchées

- Pour un flux de fichiers ou une réponse différée, suivre producteur → validation → consommateur, avec numéro de demande, fraîcheur, monde, session et dimension. Consulter `docs/isolation-mondes.md` pour les deux modes. Quand l’isolation est active, l’absence d’identité suspend les échanges de la partition.
- Pour déplacer du code ou une donnée, vérifier `monifactory_assistant/paths.py`, les lanceurs racine et le consommateur existant. Un rangement des modules ne doit pas déplacer les configurations ou les mémoires personnelles. Une nouvelle partition nécessite le parcours explicite de sauvegarde/migration, pas un effet du démarrage.
- Pour EMI, utiliser `docs/emi-transferts.md`. La sélection du scan, les objets physiques, les crédits acquis et les résultats réservés sont des états différents. Comparer toute proposition de « stock unique » à ces responsabilités avant de la retenir.
- Pour un coût récurrent, distinguer rendu par image, ticks, changements de contenu et tâches IA. Chercher une invalidation par révision pertinente avant d’ajouter un cache. Suivre la révision du contenu pour les stocks. Les benchmarks de `tests/benchmarks/` mesurent des fonctions locales, pas les FPS ni un serveur réel.
- Pour une refonte partagée, vérifier `tests/test_upstream_parity.py` et `docs/collaboration.md`. Une évolution volontaire peut demander une exception ciblée, justifiée par un test du comportement.

Présenter la frontière concernée, le contrat actuel, le changement proposé et son compromis concret. Appuyer les risques sur des chemins de code ou des mesures. Séparer une amélioration démontrée d’une hypothèse. Garder les détails d’implémentation et de livraison dans leurs workflows respectifs.
