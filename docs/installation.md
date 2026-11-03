# Assistant Monifactory — Claude et Codex

La branche **main**, source de référence, réunit les mêmes menus, filons GregTech, scan manuel, luminosité,
coffres, objectifs en pause et projets multiples pour les deux moteurs. Le choix de l’IA est local :
aucun compte, modèle personnel, plan, coffre ou conversation n’est publié sur Git.

## Installer pour la première fois

1. Télécharger la branche `main`, ou cloner :
   ```sh
   git clone --branch main https://github.com/B-DAGBERT/monifactory-claude-assistant.git
   ```
   Préférer Git au ZIP pour disposer du bouton de mise à jour.
2. Installer Python 3.12 avec Tk, Git et Java 17 (le Java de CurseForge est détecté si disponible).
   Installer et connecter le CLI de l’IA choisie : Claude Code ou Codex. Une seule est nécessaire.
3. Ouvrir **Choisir IA.command** sur Mac, ou **Choisir IA.bat** sur Windows, puis choisir Claude ou Codex.
   Le sélecteur nécessite Python, mais pas encore les dépendances de l’assistant.
4. Lancer **Installer Mac.command** ou **Installer.bat**. L’installateur compile et installe le même mod pour les deux moteurs.
5. Lancer **Lancer Mac.command** ou **Lancer assistant.bat**, puis redémarrer Minecraft.

La connexion au compte de l’utilisateur est utilisée ; aucune clé API n’est requise.
La présence du CLI est vérifiée à l’installation ; une connexion expirée sera signalée lors de la demande.
Les raccourcis et toutes les fonctions de jeu restent identiques. Le nom de l’IA active apparaît dans les réponses.

## Choix et réglages personnels

Ouvrir **Choisir IA**, puis relancer uniquement l’assistant. Le choix s’enregistre dans
`assistant.local.json`. Sans choix explicite, **Codex reste la valeur par défaut**, pour préserver
les installations existantes. Le fichier `codex.local.json` reste lu, sans être effacé.
Les valeurs de `assistant.local.json` ont priorité. Exemple :

```json
{
  "provider": "claude",
  "providers": {
    "claude": { "model_plan": "sonnet", "model_question": "sonnet", "model_quick": "haiku" },
    "codex": {
      "model_plan": "gpt-6.1-sol",
      "model_question": "gpt-5.6-sol",
      "model_quick": "gpt-5.6-sol"
    }
  }
}
```

Les anciens `model_plan`, `model_question` et `model_quick` à la racine concernent uniquement Codex.
Ils ne sont jamais envoyés à Claude. Le chemin de l’instance peut être défini par `minecraft_dir`.
Un CLI hors PATH peut être désigné par `CLAUDE_BIN` ou `CODEX_BIN` dans l’environnement.

Les discussions sont séparées : `session.json` pour Codex, `session_claude.json` pour Claude.
Claude démarre à la première demande, garde son processus pour les suivantes et reprend la session
sauvegardée après redémarrage. Une erreur ou un délai dépassé ne renvoie jamais automatiquement
une question. Ctrl+F8 remet à zéro uniquement la conversation du moteur actif.
Projets, objectifs, pause, fiches et mémoire des coffres restent communs sur chaque installation.

## Suivre la discussion dans Codex en lecture seule

Le mod garde sa conversation et envoie les questions. Pour la consulter depuis Codex,
ouvrir son **panneau navigateur** avec l’URL locale enregistrée dans `.runtime/suivi.json`
après le lancement de l’assistant. Ce panneau reflète les 30 derniers messages de Discussion,
les réponses en cours et les erreurs. Il ne lance aucune requête IA, ne reprend aucun fil
et n’a aucun champ d’envoi. Ce n’est pas une deuxième conversation ni une copie du fil Codex.
Les messages sont affichés tels que le mod les voit, sans l’historique complet des outils du CLI.

Fermer l’onglet natif de cette conversation dans Codex avant de l’utiliser depuis Minecraft :
deux services Codex ne peuvent pas en être simultanément propriétaires en écriture.
Si le verrou persiste après fermeture de l’onglet, quitter puis rouvrir Codex, sans rouvrir ce
fil natif. Le mod conserve son identifiant de session ; aucune duplication ni nouvelle tentative
automatique d’une question échouée. Utiliser ensuite le panneau de suivi pour la lecture.

Le serveur écoute uniquement sur `127.0.0.1`, avec une URL privée locale. Le port habituel est
18764 ; s’il est occupé, un port libre est choisi et l’URL est mise à jour. Au redémarrage sur
le même port, le panneau se reconnecte automatiquement. Cette fonction fonctionne aussi avec Claude.

## Migrer une installation existante

Fermer l’assistant et Minecraft. Conserver son dossier actuel : les données personnelles y restent.
Depuis un dépôt propre (sans modifications de code non enregistrées), migrer une seule fois :

```sh
git fetch origin main:refs/remotes/origin/main
git switch main
git merge --ff-only origin/main
```

Avec un clone limité à l’ancienne branche, ajouter d’abord main aux branches suivies :
`git remote set-branches --add origin main`, puis reprendre les commandes ci-dessus.
Si Git signale des modifications ou une divergence, les conserver et les faire intégrer avant
la migration ; ne pas utiliser de reset forcé. Lancer ensuite **Mettre à jour**.

Le collègue qui utilisait Claude doit conserver ou choisir Claude dans **Choisir IA**.
L’utilisateur Codex conserve son choix. Un seul mod `assistantia` est installé ; l’ancien jar
et le script client sont sauvegardés par l’installateur.

## Mises à jour pour les deux utilisateurs

La mise à jour automatique est activée par défaut. Ouvrir **Lancer Mac.command** ou
**Lancer assistant.bat** avant de lancer Minecraft. Une vérification de `origin/main` est faite
au démarrage du superviseur ; les redémarrages automatiques après un plantage ne la répètent pas.
Le contrôle réseau attend au maximum 12 secondes avant de démarrer la version locale.

Si une nouveauté existe et Minecraft est fermé, le lanceur compile dans un dossier temporaire,
installe le mod et le script avec sauvegarde, puis avance le dépôt sur `main`. Les dépendances
Python sont installées seulement si `requirements.txt` a changé. Un changement limité au Python
ou à la documentation ne recompile pas un mod déjà installé et vérifié.

Si le jeu est ouvert, ou si son état ne peut pas être vérifié, l’installation est reportée.
Le menu **Y** affiche la mise à jour en attente : fermer Minecraft et relancer l’assistant pour
l’appliquer. Il n’y a pas de surveillance permanente ni de remplacement à chaud du mod.
Sur Windows, la fenêtre de lancement affiche la préparation ; les détails sont dans
`.runtime/update.log`. Une erreur réseau ou de compilation laisse la version installée en place.

La branche doit être `main`, sans fichiers locaux non enregistrés ni commits absents du serveur.
Le lanceur ne supprime aucun travail local. Pour désactiver l’automatisme, ajouter
`"auto_update": false` à l’objet JSON existant dans `assistant.local.json`.

Pour activer cette nouveauté sur une ancienne installation, effectuer une fois la mise à jour
manuelle ci-dessous. Elle reste également disponible en dépannage.

Fermer l’assistant et Minecraft, puis lancer **Mettre à jour.command** ou **Mettre à jour.bat**.
Le lanceur récupère `origin/main`, vérifie l’absence de modifications locales et accepte uniquement
une avance rapide. Les installations sur `shared-assistant` ou `codex-compat` passent sur `main`
si tous leurs commits y sont déjà intégrés. Une branche de développement, un HEAD détaché ou
un commit local absent de main arrête la mise à jour. Une branche locale main contenant des
commits non publiés est également conservée, sans changer de branche ni lancer l’installation.
Le choix d’IA, les modèles, les objectifs, les coffres et les conversations locales sont conservés.
Relancer ensuite le jeu et l’assistant.

La branche distante `shared-assistant` est supprimée après cette migration. Les anciens lanceurs
qui la recherchent encore doivent être remplacés en effectuant **une fois la migration manuelle
ci-dessus**. Après ce passage sur `main`, le bouton de mise à jour fonctionne normalement et
ne dépend plus d’aucune ancienne branche.

## Développer ensemble

Partir de `main`, créer une branche de fonctionnalité, puis ouvrir une pull request vers
`main`. Tester les adaptations des deux moteurs et les fonctions communes avant fusion :

```sh
python -m unittest discover -s tests -v
python codex_setup.py build
```

Les points d’entrée à la racine sont conservés pour les anciens lanceurs. Le code est rangé dans
`monifactory_assistant/` : `config.py` lit les réglages, `commands/` contient les commandes et
`providers/` isole les protocoles Claude et Codex. Voir le [guide de développement](developpement.md).
`main` est la seule branche de référence et la cible des pull requests. Les adaptations Claude et
Codex évoluent ensemble ; ne plus développer sur `shared-assistant` ou `codex-compat`.

## Validation et limites

Les tests des deux adaptateurs n’exigent aucun compte IA et couvrent le flux de réponses, la reprise,
les erreurs et l’absence de double envoi. Le protocole Claude suit le code d’origine et la
[documentation officielle du CLI](https://code.claude.com/docs/en/cli-reference).
Les tests simulés ne remplacent pas un essai avec le compte Claude du collègue. Les lanceurs Windows
et l’affichage en jeu doivent également être validés sur sa machine ; aucune connexion à son compte
n’a été effectuée pour ce développement.
