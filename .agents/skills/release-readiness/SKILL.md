---
name: release-readiness
description: Qualifier un commit ou un JAR Monifactory en vue d’une intégration ou d’une livraison demandée, en vérifiant les preuves du diff final et les effets de la mise à jour.
---

# Qualifier une livraison Monifactory

Prendre les contrôles de [développement](../../../docs/developpement.md#formatage-et-tests)
et la procédure [d’installation et de retour arrière](../../../docs/fonctionnalites/installation.md).
Identifier le SHA à livrer, sa base et la différence entre le checkout local et le
dépôt distant. Réexaminer les preuves touchées par une nouvelle fusion ou un nouveau build.

- Sur une livraison du mod, rapprocher la version Gradle, le JAR effectivement testé et
  le script client. Relever l’empreinte du JAR et les versions du pack pour les essais
  natifs. La compilation, la copie dans l’instance et le chargement Forge correspondent
  à trois contrôles distincts.
- Avant une publication, exécuter la suite requise par [AGENTS.md](../../../AGENTS.md).
  Pour un changement de guides ou de skills, vérifier aussi leurs liens, leur format et,
  pour les skills, leur découverte dans le dépôt.
- Examiner l’effet sur les lecteurs de `main`, qui alimente la mise à jour automatique.
  Vérifier le remplacement cohérent du JAR et du script, le report lorsque Minecraft
  tourne, le reçu d’installation et la sauvegarde. Tester les reprises dans des dépôts ou
  instances temporaires. Distinguer une panne simulée et un arrêt réel sur le poste cible.
- Contrôler le contenu indexé avant le commit. Les données locales et les travaux
  appartenant à un autre chantier restent séparés. Appliquer les autorisations distinctes
  de la session pour l’intégration, la publication et l’installation.

Présenter le périmètre validé, les défauts confirmés, les essais encore attendus et leur
conséquence sur le verdict de livraison. Indiquer les plateformes réellement vérifiées.
