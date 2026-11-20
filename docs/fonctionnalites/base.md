# Base

## Points d’entrée

- **Y → Base** montre le nombre de coffres mémorisés et les machines connues. Chercher une machine par son nom ou utiliser **Toutes / À vérifier / En marche**. Cliquer sur le groupe pour voir les machines et leurs emplacements.
- **Production…** ouvre un calcul sur l’arbre EMI. La provenance et la fraîcheur des stocks sont contrôlées par [Production](production-projets.md).
- [OngletBase.java](../../mod/src/main/java/fr/assistantia/OngletBase.java) et [GroupesMachines.java](../../mod/src/main/java/fr/assistantia/GroupesMachines.java) construisent la liste. [world.py](../../monifactory_assistant/game/world.py), [storage.py](../../monifactory_assistant/game/storage.py) et [snapshots.py](../../monifactory_assistant/game/snapshots.py) suivent les observations et alimentent le pont.

## Règles à préserver

- Grouper par identifiant de type. Les tiers et les emplacements restent distincts. Une machine unique s’affiche directement. Appliquer recherche et filtre avant de construire les groupes.
- Une observation fraîche du serveur et un relevé ancien ont des statuts différents. Si la liaison cesse, conserver les derniers relevés et indiquer leur âge. Leur présence ne confirme pas l’état actuel de la machine.
- Les coffres mémorisés servent d’indice. L’ouverture d’un coffre actualise son relevé. Un coffre double représente un seul stock physique. Respecter les exclusions, la dimension et le monde actif.
- La mémoire du monde garde en mémoire vive le contenu, les niveaux de machine et les dates de chaque trame serveur : direct, fusion des stocks, Base et contexte de l’IA les voient aussitôt.
  - Contenu : bloc ajouté ou disparu, identifiant, objets, ou état visible d’une machine (statut `s`, multibloc formé `f`, chaudière à sec `se`, « en marche » : `s` WORKING avec une durée non nulle). Niveaux : progression `pr`, durée `du`, raison d’attente `r`, eau, température, vapeur.
  - `monde_memoire.json` est chargé à l’ouverture de la mémoire du monde. En fonctionnement normal, le premier changement est enregistré aussitôt, puis les écritures sont espacées de 30 s. Une sortie du direct (monde, connexion ou dimension) déclenche une sauvegarde immédiate. La copie `.bak` n’est refaite que par un enregistrement qui porte un contenu nouveau.
  - Si l’écriture échoue, les observations restent en mémoire et la boucle de suivi peut relancer l’écriture en arrière-plan à partir de 5 s, même sans trame serveur. Lorsque le monde est quitté, le contrôleur conserve un enregistrement en attente par chemin et indique son chemin dans le journal. Un retour dans ce monde récupère les observations historiques et laisse l’écriture déjà lancée se terminer avant d’enregistrer un relevé plus récent. À l’arrêt, l’assistant lance une dernière tentative par partition en attente et attend au plus 2 s les écritures. Les partitions dont l’écriture reste non confirmée sont signalées dans le journal et sur la sortie d’erreur. Une interruption du processus peut perdre les observations présentes uniquement en mémoire.
  - `coffres_connus.json` part de la mémoire vive, sans attendre le fichier. Il n’est republié que si son contenu hors dates change (objets, blocs, positions) : un coffre vidé part à la trame suivante. Des dates plus récentes seules partent au plus toutes les 60 s. L’état des machines n’y figure pas.
  - Un bloc toujours absent garde la date de sa première absence. [world.py](../../monifactory_assistant/game/world.py), [publication](../../monifactory_assistant/application/game.py)
- **Y → Réglages → Drawers pleins** marque les tiroirs Functional Storage proches dont toutes les cases de stockage ont atteint leur capacité réelle. Le repère est rouge, ou orange si le Void Upgrade est actif. [Calcul de capacité](../../mod/src/main/java/fr/assistantia/compat/storage/CapaciteDrawers.java)
- L’interface est en lecture seule. Le résumé provient des données du pont. Garder le dernier relevé valide si une lecture échoue et retrier la liste seulement si le relevé ou le filtre change.

## Tests à lancer

Lancer ces tests depuis la racine du dépôt avec Python et le JDK 17.

```sh
python -m unittest tests.test_base_groups tests.test_dashboard_layout
python -m unittest tests.test_world_freshness tests.test_storage_snapshots tests.test_world_routing tests.test_world_save_recovery
```

Dans une copie du pack, vérifier deux machines du même type et une d’un autre tier. Replier et déplier leur groupe, filtrer par état puis couper la liaison de test. Vérifier l’affichage des derniers relevés. [Machines regroupées dans Base](../utilisation.md#machines-regroupées-dans-base), [isolation par monde](../isolation-mondes.md).
