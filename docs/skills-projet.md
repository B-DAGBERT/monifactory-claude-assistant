# Skills Codex de Monifactory

Les six skills sont versionnés sous `.agents/skills/`. Dans une conversation Codex
ouverte sur ce dépôt, sélectionner le skill via `$` ou le nommer dans la demande.
[AGENTS.md](../AGENTS.md) donne les règles communes et oriente vers les fiches de chaque
fonctionnalité.

| Skill                                                                       | Quand l’utiliser                                                                                        |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| [`architecture-review`](../.agents/skills/architecture-review/SKILL.md)     | Décider où doit vivre un calcul ou une donnée partagée entre Forge, KubeJS et Python.                   |
| [`bug-investigation`](../.agents/skills/bug-investigation/SKILL.md)         | Isoler le premier état erroné d’un parcours et construire sa reproduction.                              |
| [`minecraft-integration`](../.agents/skills/minecraft-integration/SKILL.md) | Vérifier une API du pack, un mixin, un slot, un paquet ou un écran réel.                                |
| [`release-readiness`](../.agents/skills/release-readiness/SKILL.md)         | Qualifier une intégration ou une livraison, son artefact et sa procédure de mise à jour.                |
| [`security-audit`](../.agents/skills/security-audit/SKILL.md)               | Examiner une autorisation de lecture, une action issue d’un moteur IA ou une opération sur les données. |
| [`test-and-verify`](../.agents/skills/test-and-verify/SKILL.md)             | Choisir et exécuter les contrôles ciblés, puis consigner leur niveau de preuve.                         |

Par exemple, demander `$bug-investigation` pour reproduire un dépôt ME compté deux fois
après Échap. Utiliser `$minecraft-integration` si la question porte sur le paquet de
l’ancien menu et la case effectivement mise à jour par le serveur.

Pour la revue d’un diff, le développement courant, la mise à jour des guides ou les
worktrees, partir des règles communes et des [fiches](../AGENTS.md#trouver-le-bon-parcours).
Les points spécifiques de ces anciens workflows sont repris dans les skills spécialisés.
