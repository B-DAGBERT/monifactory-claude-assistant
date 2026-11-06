---
name: feature-development
description: Implémenter une nouvelle fonction ou une évolution de comportement dans l’assistant Monifactory, du menu Forge aux échanges Python/KubeJS, avec compatibilité Claude et Codex. Pour un dysfonctionnement existant, utiliser plutôt bug-investigation.
---

# Développer une fonctionnalité Monifactory

Lire `AGENTS.md`, puis les repères d’architecture de `docs/developpement.md`. Les chemins ci-dessous partent de la racine Git.

- Définir le geste du joueur, le résultat visible et l’état qui l’autorise, par exemple un arbre EMI actif ou un inventaire synchronisé. Donner un comportement explicite pour l’attente, l’arrêt et l’erreur.
- Côté Python, placer les décisions applicatives dans `monifactory_assistant/application/`, les calculs dans `monifactory_assistant/game/` et les données d’écran dans `monifactory_assistant/ui/views.py`. Les interactions et calculs natifs restent dans `mod/src/main/java/fr/assistantia/`. Isoler les adaptateurs IA dans `monifactory_assistant/providers/` pour que la fonction de jeu reste commune à Claude et Codex.
- Pour EMI, lire `docs/emi-transferts.md` et les sections correspondantes de `docs/developpement.md`. Réutiliser la sélection du scan actif pour les retraits. Distinguer stock physique, acquisitions et réservations. Le backpack compte dans les besoins, sans rendre ses cases accessibles à un transfert. Un recalcul préserve la pause.
- Pour un nouvel échange de fichiers, suivre un producteur et son consommateur existants. Conserver numéros de requête, portée monde/session/dimension, écriture atomique et rejet des réponses périmées. Si le périmètre change, consulter `docs/isolation-mondes.md` pour l’association explicite de l’historique.
- Les scans, allocations, filons et boutons de transfert restent locaux, sans appel IA caché. Les filons viennent du journal GregTech du client.
- Choisir les contrôles dans `docs/developpement.md` et `docs/scenarios.md`, puis mettre à jour le guide utilisateur concerné. Préciser le redémarrage requis par un changement du Python ou du mod.
