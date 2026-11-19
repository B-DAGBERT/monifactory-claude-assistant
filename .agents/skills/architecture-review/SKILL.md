---
name: architecture-review
description: Évaluer une décision de découpage, de persistance ou de performance de Monifactory Assistant quand elle touche plusieurs composants, producteurs de données ou consommateurs.
---

# Examiner une décision d’architecture

Repérer les parcours dans [AGENTS.md](../../../AGENTS.md). Pour le propriétaire
des composants, consulter les [repères du dépôt](../../../docs/developpement.md#repères).

- Suivre un exemple réel depuis le producteur jusqu’aux consommateurs. Pour un calcul
  commun, préciser qui décide des besoins, qui fournit les preuves et qui peut déplacer
  des objets. Rejouer la même quantité dans EMI, Repérage, Réseau ME et Envoyer lorsque
  le contrat est partagé. Les tests doivent préserver les différences d’accès et de fraîcheur
  propres à chaque source.
- Pour un résultat différé, vérifier à quel changement de révision, de menu, de monde,
  de dimension ou de connexion il devient périmé. Suivre les producteurs Forge et KubeJS
  et les consommateurs Python dans la [fiche du pont](../../../docs/fonctionnalites/pont-mondes.md).
- Avant de déplacer une responsabilité, retrouver la racine de données dans
  `monifactory_assistant/paths.py` et les lecteurs existants. Les lanceurs et les formats
  utilisateur ont des contraintes de compatibilité documentées dans le
  [guide de développement](../../../docs/developpement.md#compatibilité).
- Pour un coût récurrent, séparer rendu, tick, I/O et réponse IA. Mesurer sur la même
  fixture avant et après. Les mesures locales de `tests/benchmarks/` renseignent le
  coût des fonctions. Qualifier les FPS et les délais serveur dans leur environnement réel.

Rendre la décision à prendre, le contrat observé, les couches touchées et le plus petit
changement qui répond au besoin. Pour une demande d’analyse, livrer les constats et le
compromis. Une demande d’implémentation permet ensuite d’appliquer la décision dans son périmètre.
