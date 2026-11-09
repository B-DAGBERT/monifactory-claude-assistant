# Partager les améliorations

`main` est la version commune à Claude et Codex. Il n’y a pas de branche permanente par moteur.
Un changement du mod, des menus ou d’EMI profite aux deux utilisateurs ; chaque poste garde son
choix d’IA et ses données. Aucun compte ni clé ne se partage pour recevoir une fonctionnalité.

## Développer à deux

Depuis un dépôt propre :

```sh
git switch main
git pull --ff-only origin main
git switch -c fonctionnalite/nom-court
```

Faire la modification et les tests du [guide de développement](developpement.md). Ajouter seulement
les fichiers concernés, puis créer un commit. Pour demander une relecture au collègue, pousser cette
branche et ouvrir une pull request vers `main`. Une branche de travail publiée ne se déploie pas automatiquement.

Pour intégrer directement une modification terminée, comme demandé dans ce dépôt :

```sh
git switch main
git pull --ff-only origin main
git merge --no-ff fonctionnalite/nom-court
# Refaire les vérifications adaptées si la fusion intègre d’autres modifications.
git push origin main
```

Si quelqu’un a publié entre-temps, récupérer son travail et résoudre la divergence avant de pousser.
Ne pas utiliser de push forcé, ni de reset pour effacer des modifications locales. Les vérifications
GitHub de `main` doivent rester vertes ; elles utilisent des CLI simulés et aucun compte IA personnel.

## Recevoir la version sur chaque poste

1. Quitter Minecraft et l’assistant.
2. Lancer l’assistant, ou **Mettre à jour** pour une installation ancienne.
3. Laisser la vérification de `main`, la compilation éventuelle et l’installation se terminer.
4. Relancer Minecraft pour charger le nouveau JAR.

L’assistant ouvert ne recharge pas son propre Python après chaque push. Un jeu déjà lancé garde
le JAR chargé en mémoire ; le lanceur reporte son remplacement automatique jusqu’à sa fermeture.
Les changements locaux non enregistrés ou les commits non publiés bloquent la mise à jour afin de
les préserver. Une absence de réseau conserve la version installée.

## Frontières à respecter

- Les adaptateurs **Claude/Codex** restent dans `monifactory_assistant/providers/` ; une fonction du jeu ne doit pas dépendre d’un seul moteur.
- Les recettes, stocks, filons et scans sont calculés localement. Une interaction de menu ne doit pas lancer une requête IA cachée.
- Un scan ou un arbre arrêté le reste ; une mise à jour ou un recalcul ne relance aucun craft, scan ni transfert.
- Les transferts partent des cases réelles et attendent la confirmation du serveur. Une erreur ne rejoue pas les clics à l’aveugle.
- `assistant.local.json`, les sessions, plans, coffres et sauvegardes restent ignorés par Git. Utiliser `git status` avant de préparer le commit.

La mise à jour sauvegarde les fichiers installés remplacés. Pour revenir sur un bug publié, préférer
un commit correctif ou un `git revert` partagé sur `main` ; ne pas réécrire l’historique du collègue.
