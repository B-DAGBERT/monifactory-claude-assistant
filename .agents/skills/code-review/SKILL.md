---
name: code-review
description: Relire en lecture seule un diff Monifactory et signaler les défauts concrets de comportement, de compatibilité ou de maintenabilité. Pour diagnostiquer un symptôme connu, utiliser bug-investigation.
---

# Relire des changements Monifactory

Lire `AGENTS.md`, identifier la base et le diff à revoir, puis consulter les sections pertinentes de `docs/developpement.md`. Les chemins sont relatifs à la racine Git. Ne pas mélanger une correction au diff relu sans demande.

Examiner les frontières que le diff modifie.

- **EMI et inventaires** — Le retrait utilise-t-il les quotas du scan actif ? Un composant prêt couvre-t-il ses enfants et outils ? Stock possédé, réservations et acquisitions restent-ils distincts ? Vérifier les circuits virtuels GregTech sans réglage automatique ni repli vers un circuit physique. Un circuit consommé ou demandé comme cible reste un objet physique. Voir `docs/emi-transferts.md`.
- **Asynchronisme et contexte** — Une fermeture de menu, une pause, une déconnexion ou une nouvelle requête rend-elle les anciens résultats inopérants ? Les validations précèdent-elles le premier transfert et les clics suivants ? Un refus serveur doit arrêter la séquence, sans répétition automatique.
- **Persistance et observations** — Le changement respecte-t-il les dates, les stocks vides confirmés et les coordonnées multijoueurs ? Les instantanés d’inventaire se substituent aux précédents. Un cache doit suivre le contenu pertinent. `docs/isolation-mondes.md` décrit les rôles du monde et de la session, ainsi que l’association explicite de l’historique.
- **Rendu et intégration** — Vérifier les buffers de rendu dédiés et la restauration des textures en cas d’erreur. Préserver les chemins d’exécution où un adaptateur optionnel est absent, les points d’entrée historiques et les chemins des données.

Vérifier que les tests exercent l’invariant concerné et ses interruptions. Utiliser `docs/developpement.md` pour choisir une vérification ciblée. Présenter chaque anomalie avec son emplacement, son déclencheur, sa conséquence et sa preuve. Séparer les défauts démontrés des parcours qui restent à tester.
