---
name: bug-investigation
description: Reproduire et localiser un symptôme précis de Monifactory Assistant, par exemple un double crédit, un transfert incomplet, un résultat périmé ou un bouton bloqué.
---

# Enquêter sur un défaut Monifactory

Choisir le parcours dans [AGENTS.md](../../../AGENTS.md). Pour un défaut en jeu, relever
le geste, le résultat attendu et l’écart observé. Identifier la version du JAR réellement
chargée et, si le parcours utilise Python, la révision de l’assistant lancé.

- Relever le premier état faux. Pour un transfert, comparer l’action demandée, la source,
  la case de destination, le curseur, le lot et les quantités avant et après. Faire varier
  l’ordre de la fermeture, du paquet serveur et de la lecture de l’inventaire.
- Pour un double crédit, suivre une unité physique sur tout le parcours décrit dans
  [GregTech et Suivi](../../../docs/fonctionnalites/gregtech-suivi.md) ou
  [Réseau ME](../../../docs/fonctionnalites/reseau-me.md). Une apparition dans les poches
  peut être le retour d’un objet rangé ou la resynchronisation d’un dépôt déjà attribué.
- Pour un état périmé, relier la demande et sa réponse à la même connexion, au monde,
  à la dimension et à la révision active. Choisir le producteur et le consommateur
  dans la [fiche du pont](../../../docs/fonctionnalites/pont-mondes.md).
- Créer une reproduction courte avec une fixture ou une copie de test. Lorsqu’une correction
  est demandée, faire échouer le contrôle sur la version de départ, puis le rejouer sur
  la correction. Vérifier aussi une acquisition légitime proche et une interruption du
  parcours pour détecter un correctif qui masque des gains ou rejoue un transfert.

Rendre la cause prouvée, le résultat des essais et la part qui dépend encore d’un
essai natif. Regrouper les essais natifs selon la [recette de test](../../../docs/recette-fabrication.md).
