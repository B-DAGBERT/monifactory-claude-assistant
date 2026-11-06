---
name: minecraft-integration
description: Vérifier ou modifier un point d’intégration Monifactory avec Forge, KubeJS, EMI, GregTech ou Sophisticated Backpacks, lorsque l’API native, le rendu, les slots ou les événements réseau déterminent le comportement. Concerne le développement du mod et ses contrats natifs.
---

# Travailler sur une intégration Minecraft

Lire `AGENTS.md` et la section concernée de `docs/developpement.md`. Les chemins sont relatifs à la racine Git. Les chemins de classes partent de `mod/src/main/java/fr/assistantia/`.

## Vérifier la bonne API

Prendre les versions déclarées dans `mod/gradle.properties` et `mod/build.gradle`, puis la version réellement installée si le défaut concerne une partie. Distinguer API EMI de compilation et mod fourni par le pack. Pour une méthode native ou un mixin, vérifier signature, mapping et cible dans les sources ou JAR locaux, avec `jar` ou `javap` si nécessaire. Utiliser les versions Forge et GregTech du pack. Les compatibilités optionnelles sont dans `compat/` et `mixin/`, enregistrées dans `mod/src/main/resources/assistantia.mixins.json`.

## Choisir le contrat à préserver

- **EMI et remplissage** — `compat/emi/InstantaneArbreEmi.java` lit l’arbre natif. `RechercheEmi.java` possède la sélection du scan et `compat/emi/AtelierEmi.java` passe par le remplisseur EMI. Vérifier le menu réel, son handler et ses slots. Les stocks de planification peuvent inclure réservations et backpack. Les clics de remplissage/transfert utilisent seulement les cases accessibles. Une recette proposée doit rester valide au clic.
- **Machines et catalyseurs GT** — Suivre `compat/emi/ComparaisonEmi.java`, `compat/emi/ConfigurationCircuit.java`, `compat/emi/EnvoiMachine.java` et `SequenceEnvoi.java`. Un circuit virtuel se lit dans l’inventaire de configuration natif sans changer son réglage. Pour traiter un NBT d’outil, distinguer comparaison explicite EMI, outil GT dont le type/matériau appartient à l’Item, catalyseur et ingrédient consommé. Les fluides envoyés viennent des contenants de l’inventaire, pas de réservoirs voisins non reliés.
- **Backpack** — `compat/backpacks/SacsPortes.java`, `InventaireJoueur.java` et `compat/emi/InventaireArbreEmi.java` raccordent le contenu porté au stock natif. Un sac vide confirmé reste une observation valide. Après un déplacement, exiger une réponse qui corresponde à la révision courante. Préserver les vraies grilles et fluides EMI et ne jamais additionner deux relevés des mêmes poches.
- **Filons** — `GregTechFilons.java` importe le journal connu du client. Distinguer gisement enregistré et blocs localement observés. Garder une quantité inconnue lorsqu’un chunk manque ou qu’aucun relevé n’existe. Ne pas inventer un catalogue ou un scan serveur pour combler une observation absente.
- **KubeJS et réseau** — `jeu/assistant_ia.js` et `serveur/assistant_ia_serveur.js` tournent sous le Rhino du pack. Les classes Java autorisées et les conversions des valeurs peuvent différer de Node. Vérifier l’horloge, chaînes/NBT et sérialisation avec le moteur réel quand ce contrat change. En mode isolé, conserver l’origine Forge du paquet avant remise à KubeJS. Lire `docs/isolation-mondes.md` pour les modes et champs attendus.
- **Rendu et écrans** — `LiaisonTexture.java` restaure la texture même en erreur. `Reperage.java` et `Guidage.java` disposent de buffers dédiés. Ne pas terminer le buffer global depuis un rendu secondaire. Exercer retour Échap, menus étroits et interception des clics sur l’écran réellement ouvert.

Choisir les tests correspondants dans `tests/` et les parcours de `docs/scenarios.md`. `docs/emi-transferts.md` décrit les quantités et réservations attendues. Vérifier les exceptions ciblées de `tests/test_upstream_parity.py` si un contrat historique change. Le JDK 17/Forge valide la compilation. Node et les stubs Java valident des contrats. Tester le jeu redémarré pour vérifier le chargement des mixins, l’affichage et les réponses serveur. Rapporter séparément ces preuves et les essais restant à faire.
