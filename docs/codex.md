> La version commune est maintenant sur **main**. Voir [installation.md](installation.md).
> Ce document décrit le fonctionnement Codex. Pour installer ou migrer, suivre le guide commun ci-dessus.

# Variante Codex — Mac et suivi des mises à jour

Le portage Codex, initialement développé sur `codex-compat` et désormais intégré à `main`,
a été réalisé à partir du commit
`1a6a514160d772e4846e453fe3755bab8dbcc133`. Le dépôt et son historique restent communs avec `main`.
Le code est sous la licence MIT d'origine ; les auteurs et la licence sont conservés.

## Ce qui est conservé

Les écrans **Objectif, Plan, Base, Discussion, Filons, Réglages**, les fiches avec **I**, les étapes cochables,
les plans et objectifs automatiques, le calcul des besoins, la mémoire des coffres et des lieux,
la recherche et la surbrillance des objets, le guidage, les alertes et le coach, les recettes EMI,
les boutons **Remplir / Tout**, les caractéristiques des outils et le script serveur facultatif.
Les deux scripts KubeJS sont identiques à ceux de la révision d'origine indiquée ci-dessus.

Le fonctionnement et la disposition des écrans sont ceux de ce dépôt. Les personnalisations de
l'ancien dossier `monifactory_mod` ne sont pas importées dans cette variante.

## Ce qui change

- Codex remplace Claude, avec le compte déjà connecté à Codex CLI. Pas de clé API à ajouter.
- Un processus `codex app-server` reste ouvert ; le fil de discussion est sauvegardé dans `session.json`
  et repris au prochain démarrage. **Ctrl+F8** démarre une nouvelle conversation.
  Les fiches, plans et interventions du coach utilisent des fils séparés.
- Les réponses apparaissent progressivement. Une erreur ne renvoie jamais automatiquement la demande.
- Les données et recettes locales sont consultées en priorité. La recherche web reste disponible pour
  compléter une information absente, comme dans le dépôt d'origine.
- Sur Mac, Minecraft transmet directement les raccourcis : aucune autorisation **Accessibilité**
  n'est nécessaire. La voix neuronale d'origine est conservée, avec la voix Mac Amélie en secours.
- Les réglages personnels, la mémoire, les captures, l'environnement Python et les sauvegardes
  sont exclus de Git.

La conversation persistante ne signifie pas un abonnement supplémentaire ni des requêtes en continu :
la connexion inactive ne lance aucune génération. Le coach automatique peut en déclencher quand il est
activé, comme dans l'original. Les outils Codex sont en lecture seule ; les actions dans l'assistant
passent par les mêmes actions JSON que dans l'original. Le compte doit disposer des modèles choisis.

## Première installation sur Mac

1. Avoir Monifactory installé avec CurseForge, lancé au moins une fois, et Codex connecté.
   Si le CLI n'est pas trouvé automatiquement, installer Codex CLI et exécuter `codex login`,
   ou définir `CODEX_BIN` vers son exécutable.
2. Utiliser ce dossier sur la branche `main`, puis choisir Codex avec **Choisir IA.command**.
3. Arrêter l'ancien assistant. Fermer Minecraft pour passer à cette variante.
4. Double-cliquer sur **Installer Mac.command**. Il prépare `.venv`, installe les dépendances,
   compile avec Java 17 et installe le `.jar` et le script client dans l'instance.
   Les anciens fichiers de l'assistant sont sauvegardés dans `backups/`.
5. Double-cliquer sur **Lancer Mac.command**, puis ouvrir Minecraft avec CurseForge.

Python 3.12 avec Tk est recommandé. Le lanceur reconnaît notamment Python installé par python.org,
Homebrew et le runtime Python local de Codex. Au besoin : `PYTHON_BIN=/chemin/python3.12 bash "Installer Mac.command"`.
Un JDK 17 (avec `javac`) est recherché dans `JAVA_HOME`, `java_home` de `assistant.local.json`,
les installations macOS et le runtime CurseForge.

L'instance est recherchée dans `~/Documents/curseforge/minecraft/Instances/Monifactory`, puis
`~/curseforge/minecraft/Instances/Monifactory`. Pour une autre instance, créer `codex.local.json` :

```json
{
  "minecraft_dir": "/chemin/vers/Instances/Monifactory",
  "model_question": "gpt-5.6-sol",
  "model_quick": "gpt-5.6-sol",
  "model_plan": "gpt-6.1-sol"
}
```

Les questions et fiches utilisent par défaut **5.6 Sol / low**, les plans **6.1 Sol / medium**.
Ces modèles ont été vérifiés sur le compte du Mac de développement ; adapter la configuration si
un autre compte ne les propose pas. Un modèle indisponible produit une erreur, sans substitution cachée.

Ne pas faire tourner les assistants des deux dossiers sur la même instance en même temps.
Un seul `.jar` portant l'identifiant `assistantia` doit être installé : l'installateur s'en charge.
Le `.jar` des Releases de la branche d'origine n'inclut pas les raccourcis Mac de cette variante.

## Commandes en jeu

| Commande          | Fonction                                                             |
| ----------------- | -------------------------------------------------------------------- |
| **Y**             | Tableau de bord : Objectif, Plan, Base, Discussion, Filons, Réglages |
| **I** / **Maj+I** | Fiche de l'objet survolé ou du bloc regardé / de l'objet en main     |
| **F7**            | Objectif suivant                                                     |
| **F8 maintenu**   | Parler ; relâcher pour envoyer                                       |
| **Maj+F8**        | Saisie d'une question écrite                                         |
| **Ctrl+F8**       | Nouvelle conversation                                                |
| **F9**            | Afficher / masquer le panneau et arrêter la lecture                  |
| **F10**           | Activer / désactiver la lecture à voix haute                         |

Selon le réglage du clavier Mac, utiliser **Fn+F8**, ou modifier les touches dans
**Options → Contrôles → Assistant IA**. Les raccourcis ne sont captés que lorsque Minecraft
est au premier plan et connecté à une partie.
Le microphone nécessite son autorisation macOS ; les captures visuelles nécessitent l'autorisation
d'enregistrement de l'écran. Les questions écrites et données exportées du jeu fonctionnent sans ces permissions.
Whisper reconnaît la parole localement ; son modèle est téléchargé au premier usage.
La voix neuronale utilise le service en ligne Edge ; la voix Mac sert de secours si celui-ci échoue.

## Recevoir les mises à jour

`main` est la branche commune Claude/Codex et la source de toutes les mises à jour.
Suivre [le guide commun](installation.md#mises-à-jour-pour-les-deux-utilisateurs) pour migrer
une ancienne installation puis lancer **Mettre à jour.command**. Le lanceur conserve les réglages,
les fiches, la mémoire, les plans et les conversations locales ; il refuse les modifications
non enregistrées et les commits absents de main.

### Intégrer une nouveauté (mainteneur)

Créer une branche de fonctionnalité depuis `main`, tester les deux adaptateurs et le mod,
puis ouvrir une pull request vers `main`. Les tests de parité conservent la révision d’origine
comme référence pour les fonctions de jeu inchangées ; ne modifier cette référence qu’après
examen des changements concernés.

## Vérifications et limites

```sh
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python codex_setup.py check
.venv/bin/python codex_setup.py build
.venv/bin/python codex_setup.py install --dry-run
```

Tests automatisés : parité du code de jeu et des écrans, reprise du fil Codex, absence de double envoi,
erreurs et délais, séparation des réponses et recherches, raccourcis Mac, sauvegarde avant installation.
La compilation Forge et les échanges réels avec Codex ont été vérifiés sur Mac Apple Silicon.
Le jeu, le micro et les interactions EMI doivent encore être vérifiés ensemble dans une vraie partie.
La compatibilité Windows reste prévue par le code, mais n'a pas été testée sur une machine Windows.

Protocole utilisé : [documentation officielle OpenAI du Codex app-server](https://learn.chatgpt.com/docs/app-server).
Les règles de progression et détails des fonctions du jeu restent décrits dans le [README d'origine](README.md).

## Mettre l'objectif en pause

Dans **Y → Objectif** (ou **Plan**), cliquer sur **Mettre en pause**. Le bouton devient
**Reprendre** et l'état « En pause » reste visible. Aucune requête IA n'est nécessaire.
Les étapes, compteurs et recettes sont conservés, même après fermeture de l'assistant.
Les objets obtenus pendant la pause restent comptés pour la reprise. Les rappels de
progression, le guidage automatique de l'objectif et l'enchaînement sont suspendus.
Les questions, recherches manuelles et alertes de danger restent disponibles.
Reprendre avant de remplacer l'objectif du projet actif ; choisir un autre projet conserve sa pause.
Supprimer l'objectif reste possible depuis Discussion.
On peut aussi demander « mets mon objectif en pause » ou « reprends mon objectif » dans Discussion.
Un double clic envoie le même état ; il ne bascule pas deux fois. Le bouton attend la
confirmation de l'assistant, puis permet de réessayer après cinq secondes en cas de coupure.

Cette fonction est un ajout volontaire à la parité amont : ses méthodes sont testées
séparément dans `tests/test_objective_pause.py`. Le reste du code de jeu garde le contrôle de parité.

## Filons : liste, surbrillance et téléportation (Codex.3)

Ouvrir **Y → Filons**. La liste utilise les **gisements natifs GregTech**, avec leur nom, leur centre,
leur composition et leur indicateur d'épuisement. Elle ne transforme plus les blocs voisins en filons.
Les données proviennent du cache de prospection de GTCEu 7 (intégration vérifiée sur les API de 7.5.3).
Les gisements déjà découverts sont récupérés, sans IA et sans nouveau scan de blocs.
Seule la dimension active du monde identifié par GregTech est affichée.

- **Scanner ce filon** (dans sa fiche) : un passage à 16 blocs autour du centre du gisement sélectionné,
  même si le joueur se trouve au-dessus. Le centre doit être chargé côté client. Le bilan distingue les
  positions vérifiées et celles non chargées, et ne garantit pas la totalité du gisement.
- **Scanner autour de moi** (en haut) : un passage dans un cube à 16 blocs du point de départ, avec progression,
  arrêt manuel et arrêt automatique à 100 %. Aucun scan permanent, même après redémarrage.
  En solo, le bouton demande aussi à GregTech de prospecter les 9 chunks déjà chargés autour du joueur,
  via son cache serveur natif. Il ne génère aucun terrain. Sur serveur distant, seuls les gisements déjà
  connus du client sont disponibles : utiliser la prospection GregTech pour en découvrir d'autres.
- **Quantité totale inconnue** : les métadonnées GT n'indiquent pas le nombre exact de blocs restants.
  Le relevé local compte les blocs de composition compatible, à 64 blocs au plus du centre connu le plus
  proche. C'est une association de proximité, pas une reconstruction des limites générées. Un zéro
  observé ne signifie jamais que le gisement est vide. Seul l'état GregTech pilote le filtre **Épuisés**.
- **Afficher / Masquer** : choisir les types à surligner puis valider. Le sélecteur montre uniquement les
  types avec au moins un bloc observé ; les lignes à zéro sont masquées dans la fiche, le sélecteur et au survol du filon, même si le relevé change pendant
  que le menu est ouvert. Sans relevé, Afficher est désactivé et son infobulle invite à scanner le filon. Seuls les blocs effectivement
  relevés et encore présents seront surlignés.
  **Tout cocher / Tout décocher**, pagination, **Échap / Annuler** et mémorisation des choix sont conservés.
  Les contours restent limités aux blocs chargés à 64 blocs du joueur, 768 au maximum.
- **Vider les relevés** : supprimer le cache des blocs scannés du monde actif (toutes dimensions),
  uniquement lorsque le scan est arrêté. Les fiches des gisements et les choix d'affichage sont conservés.
  Le plafond de 20 000 positions concerne ce cache, pas le nombre de gisements connus.
- **Se téléporter** remonte strictement à la verticale du centre GregTech et choisit le premier
  emplacement sûr au-dessus : sol solide, deux blocs libres, sans liquide ni danger immédiat.
  Une cavité sûre est choisie avant la surface si elle est rencontrée en premier. La recherche va
  jusqu'au plafond de la dimension ; sans emplacement sûr, la téléportation est annulée.
  Aucun relevé local n'est nécessaire. Sur serveur, la zone doit déjà être chargée côté client et
  les permissions de téléportation restent nécessaires. Le bouton et le faisceau de guidage des
  filons ont été retirés ; la surbrillance des minerais reste disponible.
  En solo, elle peut charger au plus 4 chunks autour de la destination explicitement demandée ;
  sur serveur distant, elle n'utilise que des chunks déjà chargés. Aucune permission n'est modifiée.

Les gisements, relevés et choix sont sauvegardés dans `kubejs/assistant/filons_gregtech/`, séparés par
l'identité du monde fournie par GregTech. Les anciennes données `filons/` et `filons.json` restent intactes,
mais ne sont pas importées dans cette liste : elles ne prouvent pas l'existence d'un gisement GregTech.
Le scan consomme au plus 192 nouvelles positions et 32 vérifications par tick, avec un budget cible de 1 ms.
Les changements sont sauvegardés en arrière-plan. Les quantités ne sont actualisées que pendant un scan.

## Luminosité maximale (Codex.13)

Dans **Y → Réglages**, **Luminosité maximale (Fullbright)** affiche son état **Activé / Désactivé**.
L'effet s'applique immédiatement à l'éclairage côté client ; le gamma habituel de Minecraft reste
inchangé et revient dès la désactivation. Aucun effet de potion, commande serveur ou appel IA.
Le choix est conservé dans `config/assistantia-visuel.json`, même après redémarrage et sans assistant
Python lancé. Les doubles clics rapprochés sont ignorés. Les shaders qui remplacent l'éclairage
vanilla peuvent empêcher l'effet ; leur compatibilité n'est pas garantie.

## Relevés du filon sélectionné (Codex.14)

**Scanner ce filon** parcourt une fois un cube de rayon 16 autour du centre GregTech sélectionné,
plutôt qu'autour du joueur. Le bouton **Scanner autour de moi** reste disponible en haut pour la
prospection locale ; il devient **Arrêter le scan** pendant l'un ou l'autre scan. Aucune zone n'est chargée de force : un centre non chargé bloque le scan
avec une explication, et le bilan distingue les positions vérifiées de celles non chargées.

La fiche, le survol et les cases à cocher affichent uniquement les types de minerais réellement
repérés avec une quantité positive. La composition théorique (toutes les variantes de roche) reste
utilisée en interne pour reconnaître les minerais. Sans relevé, la quantité est **inconnue**, jamais
présentée comme un filon vide. **Afficher** devient disponible quand des blocs ont été repérés.
Le relevé reste partiel et associe les blocs compatibles au centre connu le plus proche ; il ne
prouve pas les limites exactes du gisement ni sa quantité totale restante.

## Menu Filons compact (Codex.16)

Recherche et tri en haut ; scan autour du joueur et filtre des gisements épuisés juste dessous.
**Outils** déplie **Tout masquer** et **Vider les relevés**. Chaque filon garde son bouton
**Afficher / Masquer**, avec deux lignes pour le nom, la distance et le nombre de blocs repérés.
La fiche sélectionnée regroupe les coordonnées, les minerais repérés et **Scanner ce filon / Téléporter**.
La molette fait défiler la liste ou les minerais selon la zone survolée. Sur petite fenêtre, la fiche
passe sous la liste. L'âge du relevé, la dimension et les limites du comptage se lisent au survol.
Une seule ligne de statut et une barre de progression rendent le scan visible ; son arrêt reste en haut.

## Plusieurs projets et onglet Base (Codex.17)

Dans **Y → Objectif** ou **Plan**, cliquer sur **Projet : …** en haut du contenu.
Le sélecteur **Mes projets** propose :

- **Choisir** : retrouver un projet existant, son plan, ses étapes cochées, ses compteurs et sa pause, sans requête IA.
- **Créer** : saisir un nom ou un but, choisir **Plan complet** ou **Objectif seul**, puis créer. Codex prépare uniquement ce nouveau projet.
- **Renommer** : modifier le nom du projet actif avec le texte saisi, sans refaire son plan.

Un seul projet est suivi à la fois. Les autres restent sauvegardés localement : leur progression
ne s'actualise pas pendant leur inactivité. Le contenu actuel des coffres et de l'inventaire
reste partagé et est réévalué au retour. Choisir un projet en pause ne le reprend jamais.
La création conserve le projet précédent, même s'il était en pause. Changer de projet attend
la fin d'une réponse en cours. Les clics répétés sont bloqués pendant l'enregistrement ;
**Réessayer** renvoie la même demande sans créer un doublon.

Le projet existant est repris automatiquement. La sauvegarde `plan_ia.json` contient le
catalogue et le projet actif, avec écriture atomique ; sa première migration conserve aussi
`plan_ia.json.avant-projets`. Aucun effacement de monde, coffre ou historique n'est nécessaire.

**Y → Base** affiche une liste défilante de machines, avec recherche par nom et filtres
**Toutes / À vérifier / En marche**. Les alertes sont placées en premier. Le résumé indique
le nombre de machines, de coffres et d'alertes ; les coordonnées et la dimension sont au survol.
Les observations anciennes sont signalées comme « Dernier relevé », sans prétendre que la
machine tourne encore actuellement. Les actions d'objectif ne sont plus affichées sur cet onglet.
