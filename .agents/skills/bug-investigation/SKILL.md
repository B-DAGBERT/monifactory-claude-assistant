---
name: bug-investigation
description: Reproduire, expliquer et corriger un dysfonctionnement observé dans Monifactory Assistant, notamment un état EMI incohérent, une réponse tardive, un transfert ou un affichage défectueux. Ne remplace pas une revue générale de code.
---

# Enquêter sur un défaut Monifactory

Lire `AGENTS.md`. Utiliser les chemins depuis la racine Git et les contrats décrits dans `docs/developpement.md`.

- Si le problème vient d’une partie réelle, distinguer code du dépôt, assistant Python lancé et JAR chargé. Une recompilation ou un rechargement des ressources ne change pas le JAR d’un Minecraft déjà ouvert. Chercher le producteur de l’état affiché avant de modifier le bouton.
- Reconstituer la séquence minimale avec le contexte, l’arbre actif, les quantités, l’ouverture et la fermeture du menu et l’ordre des confirmations. Pour une réponse tardive, établir l’origine avec le numéro de requête, la connexion, le monde et la dimension.
- Pour une collecte EMI, suivre `RechercheEmi` → `MemoireCollecte` → `RetraitCoffre`. Pour un envoi, suivre `MachinesEmi` → `EnvoiMachine` → `SequenceEnvoi`. Lire `docs/emi-transferts.md` pour distinguer résultat réservé, reçu et rangé. Ne pas corriger une incohérence en créditant un aperçu de recette, un lot manuel ou une seconde lecture du même gain.
- Pour un inventaire manquant, vérifier la fraîcheur et la synchronisation de `InventaireJoueur` et `SacsPortes`. Pour un coffre historique, vérifier la fusion datée dans `monifactory_assistant/game/storage.py` et `monifactory_assistant/game/world.py`. Utiliser une copie ou une fixture synthétique pour les fichiers invalides, en préservant les données personnelles.
- Reproduire le défaut dans un test ciblé ou un scénario existant, appliquer la correction minimale, puis rejouer la séquence et son interruption. Les refus serveur ne justifient aucun nouvel essai automatique des clics.

Rapporter la cause démontrée, la correction, les vérifications exécutées et ce qui reste seulement supposé. Les commandes et limites des simulations figurent dans `docs/developpement.md` et `docs/scenarios.md`.
