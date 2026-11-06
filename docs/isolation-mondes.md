# Isolation des mondes : préparation et migration explicites

Le lot 5 sépare les observations futures par monde. Il ne s'active jamais lors d'une mise à
jour, d'un lancement ou d'un premier contact avec un serveur. Les deux utilisateurs ont
confirmé un serveur historique unique, mais chaque installation doit confirmer sa propre
association : cette information n'est pas appliquée aux autres installations.

La qualification réelle Windows et les essais de reconnexion/paquet retardé en partie restent
nécessaires avant de déclarer le lot PASS. Les essais de migration décrits ici utilisent des
fixtures et des écrivains simulés ; aucune installation personnelle n'est migrée pendant
le développement.

## Identité et contexte

Le client exporte `identite_monde.json` avec une version, un identifiant de monde opaque
`m1_<64 chiffres hexadécimaux>`, un jeton de connexion de 32 chiffres hexadécimaux, la dimension
et une date. Le monde et la connexion sont deux notions distinctes : une reconnexion garde
le monde mais change de session. La dimension n'identifie pas un serveur.

La commande `identify` ne déduit rien des coordonnées, du nom du joueur ou du nom d'affichage
du serveur. Elle exige un relevé technique complet de moins de quatre secondes. Son reçu
contient uniquement ces identifiants opaques et les deux racines locales. Il n'enregistre
pas d'adresse serveur ni de nom de joueur. Le reçu est lié aux racines de **cette installation**,
ce qui empêche de réutiliser la confirmation du collègue sur son propre dossier.

En multijoueur, la clé est le SHA-256 versionné de l'hôte et du port normalisés associés
à l'identifiant persistant envoyé par GregTech. Deux alias de connexion distincts peuvent
donner deux clés. En solo, la clé associe le dossier de sauvegarde à l'identifiant GregTech.
Sans ces éléments, le mode isolé attend une identité valide. Le fichier de confirmation lie
les chemins des deux racines locales, indépendamment du nom ou du compte du joueur.

Le client Forge compare la connexion d'origine du paquet avec la connexion active avant
de remettre le relevé au script KubeJS. Le script vérifie ensuite le monde, le jeton et la
dimension. Python vérifie ces mêmes champs dans les trames, les demandes, les quêtes, les
preuves EMI et les inventaires. Les réponses d'une ancienne question restent dans la
conversation, avec une indication de leur origine, et leurs actions ne sont pas exécutées.
Le protocole serveur v4 et ses contrôles FTB Chunks sont compatibles. GregTech fournit
l'identité même en client seul, où les coffres ouverts restent disponibles dans la partition.

La compilation Forge et les tests simulés vérifient ce contrat. L'injection des mixins et
la réception réelle de paquets doivent encore être qualifiées en partie.

## Ce qui est conservé et copié

La préparation réutilise l'[outil de sauvegarde](sauvegarde-donnees.md). Elle crée une archive
complète du périmètre Python et de tous les fichiers `kubejs/assistant`, puis effectue
elle-même une restauration dans deux racines neuves. Elle relit les octets restaurés et
compare leurs SHA-256, tailles et chemins au manifeste. L'archive et cette restauration
restent disponibles pour contrôle et retour arrière.

Après confirmation, seuls les fichiers suivants sont copiés dans la partition du monde :

| Racine           | Fichiers copiés sans transformation                                 |
| ---------------- | ------------------------------------------------------------------- |
| Python           | `monde_memoire.json`, `plan_ia.json`, `lieux.json`, `journal.jsonl` |
| KubeJS assistant | `stockage.json`, `filons.json`                                      |

Leurs copies de récupération sont conservées également : `.bak`, `.avant-projets` et
fichiers temporaires associés, lorsqu'ils sont lisibles et attribuables. Les données sont
copiées octet par octet : aucun identifiant, état, quantité, coordonnée, date, projet en
pause ou objectif n'est réécrit. Le rapport indique les collections reconnues et leur nombre ;
les empreintes prouvent la conservation intégrale des champs, y compris ceux non interprétés.

Les fichiers invalides ou inconnus restent intacts dans les sources, l'archive et la restauration.
Un JSON illisible n'est pas remplacé par un objet vide. Si un fichier destiné à la partition
est illisible ou de forme inconnue, la préparation garde sa copie `.bak` lisible éventuelle,
mais **refuse la confirmation et l'activation**. Le fichier précis est signalé dans
`historique` et `contradictions` de `migration.json`. Cela évite de masquer silencieusement un
stock historique en activant une partition vide. Il faut réparer sur copie, contrôler la
récupération et créer une nouvelle préparation ; la CLI ne remplace aucun original.

Les sessions Codex/Claude, configurations, conversations et captures restent à leur emplacement
historique et dans l'archive. Les filons Java déjà séparés sous `filons/` ou `filons_gregtech/`
ne sont pas déplacés. Les états instantanés, commandes, reçus réseau, questions et preuves de
fabrication d'une ancienne connexion ne sont pas réinjectés dans le monde actif.

Les partitions futures sont `PYTHON/mondes/<monde>/` et
`GAME_DATA/mondes/<monde>/`. Les sauvegardes ultérieures incluent aussi le dossier Python
`mondes`. Un autre monde démarre avec une partition propre ; les fichiers historiques
ne sont jamais recopiés automatiquement dans ce nouveau monde.

## Procédure sur chaque installation

Utiliser des chemins absolus réels, sans lien ni jonction, et un dossier de préparation
**extérieur aux deux sources**. Son parent doit déjà exister. Sur Windows, mettre les chemins
contenant des espaces entre guillemets et saisir chaque commande sur une seule ligne,
sans les barres obliques de continuation des exemples. `python` doit désigner
l'environnement de l'assistant.

Pour qualifier le lot, utiliser des copies distinctes des deux racines avec le candidat
`shared.40` et le script client version 11. Les étapes jusqu'à `verify` sont une répétition
de la migration. Exécuter `activate` seulement sur ces copies lors de la qualification.

1. Se connecter au serveur connu, puis capturer son identité pendant que Minecraft est ouvert :

   ```sh
   python -m monifactory_assistant.commands.migrate_world identify \
     --python-root /chemin/monifactory_mod \
     --game-data /chemin/instance/kubejs/assistant \
     --receipt /chemin/archives/identite-installation.json
   ```

2. Fermer Minecraft et l'assistant. Préparer la sauvegarde, la restauration de contrôle et les
   copies, sans activer le routage :

   ```sh
   python -m monifactory_assistant.commands.migrate_world prepare \
     --python-root /chemin/monifactory_mod \
     --game-data /chemin/instance/kubejs/assistant \
     --receipt /chemin/archives/identite-installation.json \
     --destination /chemin/archives/migration-installation
   ```

   Le dossier créé contient `archive/`, `restauration/`, `copie/`, `identite.json`,
   `migration.json` et `preuve_restauration.json`. Lire le monde proposé, les deux racines,
   les comptages et les éventuelles contradictions. Toutes les données originales restent
   inchangées. Si un autre serveur est apparu depuis la capture, la préparation refuse.

3. Confirmer explicitement que **cette installation** a utilisé uniquement ce serveur :

   ```sh
   python -m monifactory_assistant.commands.migrate_world confirm \
     /chemin/archives/migration-installation \
     --world m1_IDENTIFIANT_EXACT_DU_RAPPORT --single-server
   ```

   Cela enregistre `confirmation.json` localement. La confirmation est liée à l'identité,
   aux deux racines, à l'archive et à la correspondance présentée. Une confirmation copiée
   depuis une autre installation est refusée. Plusieurs identités explicites dans les
   observations, les lignes lisibles du journal ou les caches de filons bloquent l'association : les fichiers restent
   historiques, une clarification est nécessaire, aucune origine n'est devinée.

4. Après qualification, toujours avec les écrivains arrêtés, activer explicitement :

   ```sh
   python -m monifactory_assistant.commands.migrate_world activate \
     /chemin/archives/migration-installation
   ```

   La commande vérifie encore l'archive, la restauration réelle, les copies préparées,
   la confirmation et les sources. Elle publie les deux partitions sans écraser de dossier
   existant différent, puis écrit **en dernier** `GAME_DATA/isolation_mondes.json` avec
   `actif:true`. Un marqueur absent ou désactivé laisse le mode historique. Un marqueur
   illisible ou invalide bloque les échanges de la partition.

Une erreur ne demande jamais de supprimer des données. Une interruption peut laisser une
préparation `.partial`, une partition complète encore inactive ou un dossier temporaire
privé. Une préparation partielle n'est pas activable. Relancer la même préparation et la même
activation reprend les copies complètes identiques, sans doubler ni réinitialiser les données.
Une destination divergente, un lien, un marqueur inconnu ou une modification concurrente fait
refuser la commande. Une activation déjà terminée est reconnue sans recopier les données.

Les contrôles d'arrêt sont conservateurs : s'ils ne peuvent pas vérifier que les écrivains
sont arrêtés, la commande refuse. Aucune commande n'arrête un processus ni n'intervient sur le
serveur Minecraft. Les deux installations gardent des copies indépendantes, même lorsqu'elles
ont le même identifiant de serveur et les mêmes coordonnées.

## Vérification et retour arrière

```sh
python -m monifactory_assistant.commands.migrate_world verify \
  /chemin/archives/migration-installation
```

Cette commande recontrôle l'archive, les copies et tous les fichiers de la restauration.
Elle ne se contente pas d'un marqueur affirmant que la restauration a réussi. L'activation
est refusée si une session restaurée, un fichier vide, un fichier inconnu, une copie ou la
preuve d'intégrité a été modifié ou manque.

Pour revenir au fonctionnement historique, fermer les écrivains puis désactiver :

```sh
python -m monifactory_assistant.commands.migrate_world deactivate \
  --python-root /chemin/monifactory_mod \
  --game-data /chemin/instance/kubejs/assistant
```

Le marqueur passe à `actif:false`. Ni les originaux ni les partitions ne sont supprimés.
Les observations faites après activation restent dans les partitions ; elles ne sont pas
fusionnées automatiquement dans les anciens fichiers. La réactivation d'une ancienne copie
après désactivation est volontairement refusée : une procédure de reprise des partitions
existantes, préservant aussi les nouvelles données historiques, reste à qualifier.

Une restauration complète reste possible vers un emplacement neuf avec `backup_data restore`.
Ne jamais recopier automatiquement cette restauration sur une installation en cours d'usage.

## Qualification

```sh
python -m unittest tests.test_data_backup tests.test_world_migration -v
```

Les tests utilisent deux mémoires personnelles distinctes, le même serveur fictif, deux
dimensions et des coordonnées identiques. Ils couvrent fichiers anciens/inconnus/invalides/vides,
confirmation locale, restauration exacte, sources modifiées, collision, liens, interruptions
avant et après publication de la première partition, échec du marqueur, idempotence et rollback.
Ils ne font aucun appel IA et ne lisent aucun fichier personnel des utilisateurs.

Les limites de métadonnées et de durabilité Windows sont celles de `backup_data` : octets et
noms relatifs vérifiés, ACL/attributs étendus non reproduits, fichiers synchronisés, publication
atomique sans remplacement. Une restauration réelle Windows/NTFS et une reconnexion réelle
avec paquets retardés restent nécessaires avant le verdict PASS et l'activation en production.

### Bilan de la reprise

Branche locale `fiabilite/isolation-mondes`, reprise depuis `111c8d1` et synchronisée
avec `main` à `d943bad`. Le code concerne les mixins et la liaison Java, le script
client, le routage Python, les commandes de migration et les tests. La qualification reste
**PARTIAL**. La publication du code sur `main` laisse la migration désactivée par défaut.

Sur une répétition synthétique, le manifeste et la restauration concordent sur **16 fichiers**
et **1 288 octets**. Les **7 fichiers** destinés aux partitions gardent leurs octets, notamment
deux coffres aux mêmes coordonnées dans deux dimensions, une machine de la mémoire client,
un bloc d'état serveur, un relevé de minerai, un projet en pause et son ancien format.
Les journaux, sessions Codex/Claude et fichiers inconnus sont comparés avec les originaux.
Le test de confirmation croisée refuse le reçu d'une autre installation, même lorsque les
deux installations utilisent le même serveur. Les cas contradictoires gardent un inventaire
historique et bloquent l'activation. Les empreintes varient avec la fixture et sa date.

La suite de non-régression macOS ARM64 compte **403 tests réussis**. Elle couvre notamment
**46 tests de sauvegarde et de migration** et les tests Java
avec contrats Minecraft simulés, les deux scénarios de séparation rejoués avec le Rhino
exact du pack, et **26 scénarios EMI**. Le JAR de qualification compile sous Forge avec le
JDK 17. Les fournisseurs IA sont simulés. Sur trois séries de mille lectures locales, le
temps médian de `Bridge.state` est de 39 à 40 µs en mode historique et de 109 à 111 µs
en mode isolé. Ces mesures n'incluent ni le rendu Minecraft ni le réseau.

Pour la qualification restante, sur chaque copie macOS/Codex et Windows 11/Claude :

1. Vérifier le reçu, puis l'archive et la restauration du poste. Confirmer les comptages
   de coffres, machines, minerais, projets en pause et conversations.
2. Activer sur la copie, vérifier un coffre mémorisé et un transfert EMI ou GregTech,
   puis changer de dimension et se reconnecter. Avec deux connexions de test, vérifier
   le rejet d'un relevé retardé, les positions multijoueurs et l'accès aux coffres FTB.
3. Désactiver sur la copie et vérifier les originaux et les nouvelles partitions. Le
   retour arrière conserve les deux jeux de données sur disque.

Les installations d'usage conservent leur routage historique pendant cette qualification.
