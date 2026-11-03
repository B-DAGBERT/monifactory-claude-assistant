# Développement

## Repères

- `monifactory_assistant/app.py` coordonne le jeu, les objectifs, la voix et l’interface.
- `providers/` isole les deux moteurs : le choix de l’un ne charge pas l’autre.
- `commands/` contient les commandes exécutées par les lanceurs Mac et Windows.
- `paths.py` définit la racine des données et celle du code. Ne pas déduire le dossier des sauvegardes depuis l’emplacement d’un module.
- `ui/discussion.py` sert uniquement le suivi en lecture seule ; ses fichiers sont dans `ui/web/`.
- `game/` contient les projets et les imports de filons. Le journal GregTech du client reste la source des filons.
- `mod/src/main/java/fr/assistantia/` contient les écrans et fonctions Forge.
- `jeu/` et `serveur/` conservent les scripts KubeJS d’origine, vérifiés par les tests de parité.

## Compatibilité

Les lanceurs et les points d’entrée `assistant_mc.py`, `codex_setup.py`, `choose_ai.py` et
`update_assistant.py` restent à la racine. Ils délèguent au paquet Python et permettent aux
installations existantes de continuer à se lancer et à se mettre à jour.

Les configurations, sessions, plans, coffres et journaux personnels conservent leur emplacement
historique à la racine, en dehors du code et ignorés par Git. Les données ne sont pas migrées
pendant un simple rangement des sources. Les objectifs en pause restent en pause.

## Formatage et tests

Les versions des outils sont fixées dans `requirements-dev.txt`, `package-lock.json` et `mod/build.gradle`.

```sh
python -m pip install -r requirements-dev.txt
python -m ruff format .
python -m ruff check .
npm ci
npm run format
bash mod/gradlew -p mod formatJava
python -m unittest discover -s tests -v
python codex_setup.py build
```

Utiliser `python -m ruff format --check .` et `npm run format:check` pour contrôler sans modifier.
Le Java se vérifie avec `bash mod/gradlew -p mod checkJavaFormat`. Les scripts KubeJS restent
identiques à la référence historique et sont exclus du formatage automatique.
La CI exécute ces vérifications sur `main` et les pull requests qui la ciblent.

Les scénarios de mise à jour utilisent de vrais dépôts Git temporaires. Les tests des moteurs
utilisent des CLI simulés : aucun compte IA ni appel facturé n’est nécessaire.

## Livraison

Travailler sur une branche de fonctionnalité issue de `main`, tester, puis fusionner vers `main`.
La commande **Mettre à jour** récupère uniquement `origin/main` et refuse d’écraser des modifications
locales ou des commits non publiés. Ne pas utiliser de reset forcé pour contourner ces protections.
