# Sauvegarder et restaurer les données sans migration

Cet outil est explicite et indépendant du démarrage de l'assistant. Il ne change aucun
monde, ne rattache aucun historique à un serveur, n'active aucune nouvelle structure et
ne restaure jamais par-dessus une installation. Il n'appelle ni Codex ni Claude.

Le [lot d'isolation des mondes](isolation-mondes.md) réutilise cette archive pour préparer et
contrôler une migration. La restauration réelle sur Windows et la réception de paquets
lors d'une reconnexion restent à qualifier sur des copies de test.

## Périmètre

Deux racines sont fournies explicitement :

- La racine Python du dépôt : fichiers racine `*.json`, `*.jsonl`, `*.json.*`,
  `*.jsonl.*`, `.*.json-*.tmp`, `.*.jsonl-*.tmp`, `assistant.log*`, et contenu complet des dossiers
  `backups/`, `captures/`, `.runtime/`, `mondes/`.
- Le dossier exact `kubejs/assistant` de l'instance : **tous** les fichiers, y compris
  formats inconnus, exports, archives de filons, dossiers vides et fichiers temporaires.

Cela comprend configurations locales, sessions, projets et plans, lieux, fiches,
journal, observations serveur, coffres, filons, historique Discussion dans
`vue_mod.json`, accusés et demandes. Le fichier historique `plan_ia.json.avant-projets`
et les copies `.bak` restent des fichiers indépendants. Aucun contenu utilisateur n'est
réinterprété ou réécrit ; même un JSON invalide est conservé octet par octet.

La sélection racine accepte aussi de nouveaux fichiers JSON dont le sens est inconnu.
Le manifeste inventorie les fichiers retenus et leur empreinte ; il n'affiche pas leur
contenu. `.runtime` peut être volumineux car ses outils et caches sont également conservés.

Ce n'est pas une sauvegarde de Minecraft entier : sauvegardes du jeu, mods, scripts,
réglages Java sous `config/`, environnements Python, identifiants du compte et historiques
natifs stockés par les CLI hors de ces deux racines restent à leur emplacement. Les
fichiers `session*.json` conservent leurs références aux conversations existantes.

## Inventaire sans écriture

Remplacer les chemins d'exemple ; utiliser les chemins réels, sans lien symbolique ou
jonction dans les composants du chemin. Les sources et destinations doivent être disjointes.
La destination ne doit donc pas être placée dans le dossier `backups` de la source.

```sh
python -m monifactory_assistant.commands.backup_data inventory \
  --python-root /chemin/monifactory_mod \
  --game-data /chemin/instance/kubejs/assistant \
  --dry-run
```

`inventory` est toujours en lecture seule. `backup --dry-run` produit le même inventaire,
sans copier ni créer de destination. Il calcule tailles et SHA-256 ; les dates et identités
de fichiers sont contrôlées avant et après lecture pour refuser une vue incohérente.

## Sauvegarde vérifiée

Fermer **Minecraft et l'assistant** jusqu'à la fin de l'opération. Leur arrêt est contrôlé
avant et après la lecture des données ; une détection indisponible refuse la publication.
Aucun processus n'est arrêté par l'outil.

```sh
python -m monifactory_assistant.commands.backup_data backup \
  --python-root /chemin/monifactory_mod \
  --game-data /chemin/instance/kubejs/assistant \
  --destination /chemin/archives/avant-isolation

python -m monifactory_assistant.commands.backup_data verify \
  /chemin/archives/avant-isolation
```

Le parent de la destination doit déjà exister. Une nouvelle archive est un dossier avec
`python/`, `kubejs-assistant/`, `manifest.json` et `complete.json`. Les copies sont créées dans
un nouveau dossier frère `.partial` privé, vérifiées par une nouvelle lecture, puis publiées
par renommage atomique **sans remplacement**. Le manifeste contient taille, SHA-256, permissions
et date de modification de chaque fichier, les dossiers et les chemins des deux sources.
Le marqueur de fin contient l'empreinte du manifeste.

Une seconde lecture complète des sources contrôle les contenus, ajouts, suppressions,
remplacements, dates et permissions. Une modification détectée empêche la publication.
Une archive existante n'est réutilisée que si elle est intégralement valide et correspond
exactement au nouvel inventaire des mêmes sources. Sinon la commande refuse sans écraser.

Une interruption laisse éventuellement un dossier `.partial`. Il reste disponible pour
examen, mais `verify` et `restore` le refusent. Une relance crée un autre dossier temporaire ;
elle ne supprime ni ne complète une ancienne copie incertaine. Aucun nettoyage destructif
n'est effectué. La commande doit terminer avec un code de retour nul avant usage de l'archive. Si une erreur
survient juste après le renommage, la destination peut déjà contenir une archive complète :
exécuter `verify`, puis relancer `backup` au même emplacement. La relance vérifie l'ensemble avant
de réutiliser la copie ; elle ne remplace jamais une archive divergente.

## Test de restauration et retour arrière

```sh
python -m monifactory_assistant.commands.backup_data restore \
  /chemin/archives/avant-isolation \
  --destination /chemin/restaurations/controle-avant-isolation
```

La destination entière doit être **nouvelle**, même un dossier vide existant est refusé.
L'outil restaure deux racines neuves sous ce parent : `python/` et `kubejs-assistant/`.
Un seul renommage publie les deux ensemble ; aucun chemin de production n'est remplacé.
Les empreintes de tous les fichiers restaurés sont relues avant publication et l'archive
source est vérifiée une deuxième fois. Une tentative concurrente de créer la destination
fait échouer la publication, sans remplacer le dossier créé par l'autre processus.

Le contrôle attendu est la conservation des chemins relatifs et des octets de chaque fichier,
pas seulement le démarrage de l'application. Les tests automatisés comparent aussi les
sessions, projets en pause, formats anciens, contenus invalides et dossiers vides. Restaurer
le même nom une deuxième fois refuse l'opération : choisir un nouveau parent pour refaire
le contrôle. Il n'existe pas de restauration en place automatique.

Pour un retour arrière réel ultérieur : conserver l'installation actuelle et ses nouvelles
observations, effectuer cette restauration dans deux racines neuves, puis qualifier avec la
version de code correspondant à l'archive. Le basculement vers cette copie restaurée reste
une opération distincte. Cet outil ne contient aucune commande d'activation ou de migration.

## Garanties et limites de qualification

Les liens symboliques, jonctions Windows et fichiers spéciaux sont refusés. Les fichiers
ordinaires liés physiquement sont copiés comme fichiers indépendants, sans recréer les liens.
Sous POSIX, les composants internes aux sources sont ouverts sans suivre les liens. Sur
Windows, les points de réanalyse sont contrôlés avant ouverture, puis l'identité du fichier
ouvert est vérifiée. Ce n'est pas une protection contre un acteur hostile modifiant les parents
d'un chemin entre les appels système : les écrivains doivent rester arrêtés.

Les octets et noms relatifs sont vérifiés exactement ; le fichier manifeste conserve les
modes et dates. La restauration tente de restituer la date et les permissions usuelles,
sans rétablir les bits spéciaux setuid/setgid/sticky. ACL, propriétaires, attributs étendus,
flux NTFS et liens physiques ne sont pas reproduits. Des noms incompatibles avec le système
cible peuvent faire refuser une restauration ; aucun fichier n'est écrasé pour les adapter.
Les collisions de casse sont refusées avant la copie.

Les écritures de fichiers sont flushées et synchronisées. Les dossiers sont synchronisés
sur POSIX ; Python ne fournit pas ici d'équivalent portable de `fsync` de dossier sur Windows.
Les tests de panne sont des interruptions injectées, pas un essai de coupure électrique.
La protection contre un remplacement de dossier utilise `renamex_np(RENAME_EXCL)` sur macOS,
`renameat2(RENAME_NOREPLACE)` sur Linux et le refus natif de remplacement de `os.rename` sur
Windows. Une plateforme sans primitive adéquate refuse la publication.

La qualification Windows réelle, avec verrouillage antivirus/NTFS et chemins non ASCII,
reste à effectuer. Les tests automatisés n'utilisent que des fixtures temporaires ; aucune
donnée de l'installation personnelle n'est lue ou copiée pour ces tests.

```sh
python -m unittest tests.test_data_backup -v
```
