---
name: security-audit
description: Auditer une frontière de confiance de Monifactory Assistant lors d’une revue de permissions, de lecture distante, de commande externe, de sauvegarde, de migration ou de mise à jour.
---

# Auditer une frontière de confiance

Choisir la surface touchée et examiner d’abord les producteurs, validations et
effets en lecture seule. Utiliser des fixtures pour vérifier les cas de refus.

- **Adaptateurs IA et discussion.** Suivre les arguments transmis par
  `monifactory_assistant/providers/` et les données qui peuvent devenir une action.
  Vérifier que l’autorisation reste liée à la demande et à la session. Le miroir de
  discussion est local et en lecture seule. Vérifier la liaison, les contrôles de
  requête et le traitement du texte dans la [fiche Discussion](../../../docs/fonctionnalites/discussion-ia.md).
- **Forge et KubeJS.** Identifier l’origine Forge, le contexte et la fraîcheur dans
  la [fiche du pont](../../../docs/fonctionnalites/pont-mondes.md). Vérifier les refus
  de lecture quand l’autorisation FTB Chunks est indisponible et les conteneurs soumis à
  des restrictions. Un relevé tronqué laisse le stock concerné inconnu.
- **Fichiers et mise à jour.** S’appuyer sur les
  [guides de sauvegarde](../../../docs/sauvegarde-donnees.md) et
  [d’isolation](../../../docs/isolation-mondes.md). Vérifier la racine effective,
  les liens, la confirmation d’installation et les originaux. Pour un déploiement
  interrompu, vérifier le couple JAR et script et le reçu dans un dépôt de test.

Pour chaque problème, donner l’entrée, le contrôle en cause, l’effet démontré et le
test défensif. Utiliser des données fictives et des extraits expurgés dans le rapport.
