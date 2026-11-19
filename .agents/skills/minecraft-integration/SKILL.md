---
name: minecraft-integration
description: Modifier ou vérifier un contrat natif Monifactory quand les menus, slots, mixins, paquets ou API du pack Forge, KubeJS, EMI, GregTech, AE2 ou Sophisticated Backpacks déterminent le résultat.
---

# Vérifier une intégration Minecraft

Partir de la fiche du parcours répertoriée dans [AGENTS.md](../../../AGENTS.md).
Comparer `mod/gradle.properties` et `mod/build.gradle` avec les versions du pack testé.
Pour un mixin ou une API native, vérifier la signature, les mappings et la version du
JAR local avec les sources, `jar` ou `javap` selon le besoin. Contrôler les déclarations
dans `mod/src/main/resources/assistantia.mixins.json` lorsque la cible change.

- Dans un menu, identifier le conteneur du joueur et le vrai propriétaire du slot.
  L’index du menu et l’index dans les poches peuvent différer. Après Échap, distinguer
  une confirmation de l’ancien menu, une mise à jour appliquée aux poches et la
  réapparition d’un dépôt déjà crédité. Voir [Réseau ME](../../../docs/fonctionnalites/reseau-me.md).
- Pour une recette, suivre les variantes réellement comparées, dont NBT et capacités
  si elles participent à l’identité. Vérifier au clic que les sources sont accessibles.
  Un moule réutilisable se compte une fois et les fluides se vérifient en mB.
  Le bouton explicite **Circuit** est distinct d’**Envoyer**. Vérifier sa confirmation
  native avant d’envoyer les ingrédients. Voir [GregTech et Suivi](../../../docs/fonctionnalites/gregtech-suivi.md).
- Pour les sacs et le tampon, suivre le relevé, sa révision et son éventuel chevauchement
  avec ME. Tester la lecture pendant synchronisation et le retrait depuis la vraie case.
  Voir [Stock local](../../../docs/fonctionnalites/stock-local.md).
- Pour les scripts KubeJS, vérifier les conversions et événements dans le Rhino du pack
  lorsque le contrat change. Pour un rendu modifié, vérifier la restauration de l’état de
  rendu et le comportement des autres écrans. Exercer les intégrations optionnelles
  concernées dans les configurations prises en charge.

Compiler le mod avec JDK 17. Pour un contrat qui dépend du jeu, utiliser une copie,
vérifier le JAR chargé, regrouper les cas, couper l’audio de la copie et la fermer
après les essais. La [recette de fabrication](../../../docs/recette-fabrication.md)
donne les contrôles de quantité, de fermeture et de reprise.
