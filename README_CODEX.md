# Variante Codex — Mac et suivi des mises à jour

Cette branche `codex-compat` porte le dépôt de B-DAGBERT vers Codex, à partir du commit
`1a6a514160d772e4846e453fe3755bab8dbcc133`. Le dépôt et son historique restent communs avec `main`.
Le code est sous la licence MIT d'origine ; les auteurs et la licence sont conservés.

## Ce qui est conservé

Les écrans **Objectif, Plan, Base, Discussion, Réglages**, les fiches avec **I**, les étapes cochables,
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
2. Utiliser ce dossier sur la branche `codex-compat`.
3. Arrêter l'ancien assistant. Fermer Minecraft pour passer à cette variante.
4. Double-cliquer sur **Installer Mac.command**. Il prépare `.venv`, installe les dépendances,
   compile avec Java 17 et installe le `.jar` et le script client dans l'instance.
   Les anciens fichiers de l'assistant sont sauvegardés dans `backups/`.
5. Double-cliquer sur **Lancer Mac.command**, puis ouvrir Minecraft avec CurseForge.

Python 3.12 avec Tk est recommandé. Le lanceur reconnaît notamment Python installé par python.org,
Homebrew et le runtime Python local de Codex. Au besoin : `PYTHON_BIN=/chemin/python3.12 bash "Installer Mac.command"`.
Java 17 est recherché dans `JAVA_HOME`, les installations macOS et le runtime CurseForge.

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

| Commande | Fonction |
| --- | --- |
| **Y** | Tableau de bord : Objectif, Plan, Base, Discussion, Réglages |
| **I** / **Maj+I** | Fiche de l'objet survolé ou du bloc regardé / de l'objet en main |
| **F7** | Objectif suivant |
| **F8 maintenu** | Parler ; relâcher pour envoyer |
| **Maj+F8** | Saisie d'une question écrite |
| **Ctrl+F8** | Nouvelle conversation |
| **F9** | Afficher / masquer le panneau et arrêter la lecture |
| **F10** | Activer / désactiver la lecture à voix haute |

Selon le réglage du clavier Mac, utiliser **Fn+F8**, ou modifier les touches dans
**Options → Contrôles → Assistant IA**. Les raccourcis ne sont captés que lorsque Minecraft
est au premier plan et connecté à une partie.
Le microphone nécessite son autorisation macOS ; les captures visuelles nécessitent l'autorisation
d'enregistrement de l'écran. Les questions écrites et données exportées du jeu fonctionnent sans ces permissions.
Whisper reconnaît la parole localement ; son modèle est téléchargé au premier usage.
La voix neuronale utilise le service en ligne Edge ; la voix Mac sert de secours si celui-ci échoue.

## Recevoir les mises à jour Codex

Arrêter l'assistant et Minecraft, puis ouvrir **Mettre à jour.command**.
Le script récupère `origin/codex-compat` en avance rapide, réinstalle les dépendances,
recompile et installe les nouveaux fichiers. Relancer ensuite l'assistant et Minecraft.
Il s'arrête si la branche n'est pas la bonne, si des fichiers suivis ont été modifiés ou si les
historiques ont divergé. Il ne fait ni `reset --hard`, ni écrasement des changements personnels.
`codex.local.json`, les fiches, la mémoire, les plans et les conversations locales sont préservés.

**Les nouveautés de `main` doivent d'abord être intégrées et testées dans `codex-compat`.**
Le script récupère les mises à jour publiées de cette variante ; il ne fusionne pas automatiquement
du code amont non vérifié pendant une partie.

### Intégrer une nouvelle version du dépôt d'origine (mainteneur)

Depuis une branche `codex-compat` propre et à jour :

```sh
git fetch origin
git switch codex-compat
git merge --ff-only origin/codex-compat
git merge origin/main
# Résoudre les conflits éventuels, examiner les nouvelles fonctions et leur adaptation Codex.
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python codex_setup.py build
git push origin codex-compat
```

Le test de parité compare les fonctions de jeu et les écrans à une révision amont précise.
Lors d'une intégration, mettre à jour `UPSTREAM_REVISION` dans `tests/test_upstream_parity.py`
après avoir examiné les changements ; mettre également à jour la révision en tête de ce document.
En cas de conflit, résoudre et tester avant de publier. `git merge --abort` annule une fusion en cours.
Ne jamais publier cette adaptation directement sur `main` sans accord du mainteneur.

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
Reprendre avant de changer d'objectif ; supprimer l'objectif reste possible depuis Discussion.
On peut aussi demander « mets mon objectif en pause » ou « reprends mon objectif » dans Discussion.
Un double clic envoie le même état ; il ne bascule pas deux fois. Le bouton attend la
confirmation de l'assistant, puis permet de réessayer après cinq secondes en cas de coupure.

Cette fonction est un ajout volontaire à la parité amont : ses méthodes sont testées
séparément dans `tests/test_objective_pause.py`. Le reste du code de jeu garde le contrôle de parité.


## Filons : liste, surbrillance et téléportation (Codex.3)

Ouvrir **Y → Filons**. La liste fonctionne dans Minecraft même si l'assistant Python est arrêté.
Chaque ligne affiche les minerais, les **blocs repérés restants**, la distance et son propre bouton
**Afficher / Masquer**. Cliquer sur une ligne sélectionne son détail ; la survoler montre tous ses
minerais avec leurs quantités. La recherche accepte les noms et identifiants des minerais.

- **Afficher** active la surbrillance de ce filon et désactive la précédente ; les couleurs distinguent
  les minerais. **Tout masquer** coupe l'affichage. Contours limités aux blocs chargés à 64 blocs,
  au maximum 768 à la fois, pour préserver les performances.
- **Me guider** affiche un faisceau bleu dans la dimension courante ; **Arrêter guidage** l'enlève.
- **Se téléporter** exige les commandes autorisées / permissions opérateur. En solo, le serveur intégré
  vérifie la destination, y compris dans une autre dimension. Il charge au maximum 9 chunks autour
  de la destination demandée. Sur serveur distant, seule une destination déjà chargée dans la dimension
  courante peut être vérifiée ; la commande `/tp @s` reste soumise aux autorisations du serveur.
  Le mod attend la position effective avant de confirmer. Les boutons indisponibles expliquent pourquoi
  dans leur infobulle. Aucun changement automatique des permissions.
- La destination doit avoir deux blocs d'air, un sol solide, aucune collision et aucun liquide ou danger
  connu à proximité (lave, feu, cactus, magma, neige poudreuse, etc.). Recherche limitée à 8 blocs du minerai :
  si rien ne convient, aucune téléportation. Les effets particuliers de blocs ajoutés par d'autres mods
  ne peuvent pas tous être prédits.
- Tri par proximité, minerai ou lien direct avec les matières de l'objectif. Ce dernier utilise les
  besoins locaux déjà calculés, sans IA ; il n'infère pas toutes les chaînes chimiques possibles.
  Les filons sans blocs restants repérés sont masqués par défaut, avec un filtre pour revoir l'historique.

**Compteurs partiels, pas un inventaire du sous-sol entier.** Le scanner explore progressivement un cube
à 16 blocs autour du joueur, en commençant près de lui. Il ne force aucun chargement de chunk pour scanner.
Au plus 192 nouvelles positions et 32 positions connues sont examinées par tick, avec un budget de parcours
cible de 1 ms. Les blocs connus sont revérifiés lorsqu'ils sont chargés : les minerais cassés par un collègue
sont également retirés des quantités. Un chunk déchargé ne compte jamais comme un filon vidé.
Les minerais voisins sont regroupés autour d'une ancre stable dans un rayon de 24 blocs : c'est un repérage
pratique, pas une reconstruction exacte des limites des filons générés par GregTech.

La mémoire est séparée par sauvegarde solo ou adresse de serveur, dans `kubejs/assistant/filons/`.
Elle contient au plus 20 000 positions ; une saturation est signalée sans effacer silencieusement
l'historique. Les écritures passent par des fichiers temporaires et un thread dédié, toutes les 10 secondes
si nécessaire. `filons_mod.json` transmet le dernier relevé à l'assistant pour ses réponses et son guidage.
Les anciens relevés `filons.json` restent conservés, mais ne sont pas importés aveuglément dans un monde :
ils n'indiquent pas de quelle sauvegarde ils proviennent. La nouvelle liste se remplit au fil de l'exploration.
Une réinitialisation du monde d'un serveur à la même adresse nécessite une nouvelle mémoire ; le client
ne connaît pas la graine distante et ne peut pas identifier cette réinitialisation automatiquement.

Tests : modèle Java du scanner, regroupement multiminéraux, disparition/réapparition, reprise des données,
séparation des dimensions, limites de mémoire, parcours complet, recherche bornée d'une destination libre,
et priorité du nouvel export dans l'assistant. Compilation Forge vérifiée ; les clics et téléportations
réels doivent être essayés en partie, notamment avec les permissions de votre serveur.
