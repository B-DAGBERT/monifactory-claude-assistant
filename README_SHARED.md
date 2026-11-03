# Assistant Monifactory — Claude et Codex

La branche **shared-assistant** réunit les mêmes menus, filons GregTech, scan manuel, luminosité,
coffres, objectifs en pause et projets multiples pour les deux moteurs. Le choix de l’IA est local :
aucun compte, modèle personnel, plan, coffre ou conversation n’est publié sur Git.

## Installer pour la première fois

1. Télécharger la branche `shared-assistant`, ou cloner :
   ```sh
   git clone --branch shared-assistant https://github.com/B-DAGBERT/monifactory-claude-assistant.git
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
    "claude": {"model_plan": "sonnet", "model_question": "sonnet", "model_quick": "haiku"},
    "codex": {"model_plan": "gpt-6.1-sol", "model_question": "gpt-5.6-sol", "model_quick": "gpt-5.6-sol"}
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

## Migrer une installation existante

Fermer l’assistant et Minecraft. Conserver son dossier actuel : les données personnelles y restent.
Depuis un dépôt propre (sans modifications de code non enregistrées) :

```sh
git fetch origin
git switch --track origin/shared-assistant
```

Si la branche locale existe déjà : `git switch shared-assistant`, puis utiliser **Mettre à jour**.
Ne pas forcer un changement de branche si Git signale des modifications : les conserver d’abord.
Le collègue qui utilisait Claude doit ensuite ouvrir **Choisir IA**, choisir Claude, puis lancer
l’installateur. L’utilisateur Codex peut conserver son choix. Un seul mod `assistantia` est installé,
et l’ancien jar et le script client sont sauvegardés par l’installateur.

## Mises à jour pour les deux utilisateurs

Fermer l’assistant et Minecraft, puis lancer **Mettre à jour.command** ou **Mettre à jour.bat**.
Le lanceur récupère `origin/shared-assistant`, vérifie l’absence de modifications locales et accepte
uniquement une avance rapide. Une divergence arrête l’opération ; aucun reset ni écrasement forcé.
Le choix d’IA, les modèles et les données locales sont conservés. Relancer ensuite le jeu et l’assistant.
Le nouveau lanceur accepte aussi un checkout historique `codex-compat`, mais la branche recommandée
pour tout le monde est `shared-assistant`. Un ancien lanceur doit d’abord être migré comme ci-dessus.

## Développer ensemble

Partir de `shared-assistant`, créer une branche de fonctionnalité, puis ouvrir une pull request vers
`shared-assistant`. Tester les adaptations des deux moteurs et les fonctions communes avant fusion :

```sh
python -m unittest discover -s tests -v
python codex_setup.py build
```

Les noms `codex_config.py` et `codex_setup.py` sont conservés pour compatibilité des lanceurs ;
l’installation utilise bien l’IA sélectionnée. `ai_provider.py` est le point d’entrée commun,
`claude_integration.py` et `codex_integration.py` isolent les protocoles de chaque moteur.
La synchronisation avec la branche d’origine `main` reste une intégration examinée via pull request.

## Validation et limites

Les tests des deux adaptateurs n’exigent aucun compte IA et couvrent le flux de réponses, la reprise,
les erreurs et l’absence de double envoi. Le protocole Claude suit le code d’origine et la
[documentation officielle du CLI](https://code.claude.com/docs/en/cli-reference).
Les tests simulés ne remplacent pas un essai avec le compte Claude du collègue. Les lanceurs Windows
et l’affichage en jeu doivent également être validés sur sa machine ; aucune connexion à son compte
n’a été effectuée pour ce développement.
