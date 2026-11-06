# Architecture V2 en mode limité

## Périmètre

Le premier lot fournit des calculs locaux à la demande. L’assistant en jeu, les lanceurs et les
adaptateurs Codex et Claude ne chargent aucun moteur V2 par défaut. Le lot 5 reste propriétaire de
l’identité du monde et de la connexion. Les sauvegardes existantes restent propriétaires des
projets, des stocks et des historiques. Le lot 2 reste reporté.

| Couche        | Entrée et responsabilité                                                                                             | Sortie                                                                     |
| ------------- | -------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| Acquisition   | Consommer des instantanés explicitement fournis, en appliquant les identités et contrôles existants                  | Relevés datés, provenance et état de couverture                            |
| Connaissance  | Distinguer monde, session, dimension, historique et fraîcheur. Regrouper un même stock physique une seule fois       | Vue du monde et quantités traçables                                        |
| Calcul        | Lire un catalogue immuable, calculer recettes et quantités, qualifier capacités et diagnostics                       | Plans, hypothèses et preuves structurés                                    |
| Orchestration | Préparer les données pour une question, en gardant le calcul déterministe et les droits de mutation côté application | Contrats de résultats. Intégration au dialogue en attente de qualification |
| Présentation  | Garder la confirmation native et les parcours de jeu sous leurs propriétaires actuels                                | Informations lisibles par le joueur. Intégration native séparée            |

## Décisions et contrats

- Les composants Python `monifactory_assistant.production` reçoivent leurs données en arguments.
  Aucun chargement automatique, balayage périodique, appel IA, service de synchronisation ou nouveau
  fichier de sauvegarde de production n’est nécessaire pour calculer. Une même entrée donne un même
  résultat. Les tests isolent les calculs et leurs effets de bord.
- Les vues distinguent fait observé, déclaration, déduction et inconnu. La fraîcheur dépend de la
  source et de la connexion. Un stock partiel peut prouver une quantité trouvée. Il laisse ouverte
  la quantité totale et l’absence d’une ressource. Une observation n’autorise aucun transfert.
- La planification conserve les unités et les révisions. Un catalogue exact exige un document
  versionné vérifié comme un tout. Le dernier document accepté peut servir de repli avec son statut
  explicite. L’export textuel V1.5 écrit ses parties et ses capacités séparément, et perd certaines
  alternatives et métadonnées de recette. Il reste utilisable par V1.5. Le moteur exact attend une
  source structurée attestée pour les calculs du pack en jeu.
- Les réservations réduisent la disponibilité physique. Les sorties en attente et les réceptions
  déjà comptées restent des informations distinctes. Une correspondance de catégorie prouve au mieux
  une compatibilité de recette. L’énergie, l’alimentation et la topologie demandent leurs propres preuves.
- La mémoire utilise le projet et le monde existants comme clés de lecture. Les décisions déclarées
  gardent leur provenance. Les préparations V2 ne s’enregistrent pas dans un projet actif. La mutation
  durable et les nouvelles actions issues du dialogue restent soumises à leur qualification.

## Ordre de développement

1. Préflight V1.5 et rapport de transition.
2. Modèle de connaissance, catalogue cohérent et moteur de production.
3. Modèle d’installations, diagnostics et mémoire en lecture seule.
4. Qualification des contrats, scénarios et mesures sur fixtures.
5. Qualification native des fournisseurs de données et essais sur macOS et Windows. Les commandes de
   mutation issues du dialogue restent `BLOCKED_BY_R5`. Les changements d’installation sont soumis à P4.

Les critères par chantier et les résultats mesurés sont consignés dans la qualification V2.

## Rejouer un calcul hors jeu

Depuis la racine du dépôt, le scénario de huit plaques utilise un catalogue et des stocks fictifs.

```sh
python -m monifactory_assistant.commands.plan_production \
  tests/fixtures/production_v2/catalogue.json \
  tests/fixtures/production_v2/demande.json --source scenario-bronze
```

Le résultat donne deux lots d’alliage puis huit opérations à la cintreuse. Il identifie les stocks
utilisés, les quantités, le circuit configuré à vérifier et l’empreinte du catalogue. Le champ
`execution_readiness` reste `not_evaluated`. Une fabrication en cours apparaît dans `waiting` et
sa réception ne crée aucun stock physique. Le résultat `partial` indique un choix, une quantité
ou une condition à résoudre avant de considérer le calcul comme complet.

Le format `monifactory.production.catalogue` v1 enveloppe le catalogue de recettes v1 et l’associe
à une source et à une empreinte SHA-256. La génération est croissante par source. La publication
de référence utilise un remplacement atomique d’un document complet. L’empreinte vérifie l’intégrité
du document, tandis que le choix du producteur relève du code appelant. Le lecteur refuse un
document incomplet, une source étrangère et la réutilisation d’un numéro pour un contenu différent.

Les stocks de la commande sont des relevés fournis par l’appelant. Chaque identifiant désigne un
seul inventaire physique. L’adaptateur des observations du jeu impose en plus une preuve de fraîcheur,
une portée de monde et un accord explicite avant de combiner les stocks personnels et les coffres.
Un identifiant d’objet seul ne suffit pas pour compter une variante, un outil distinct par ses données
ou un fluide. Les anciens relevés restent consultables. Les capacités des réseaux restent inconnues
en l’absence d’une observation dédiée.
