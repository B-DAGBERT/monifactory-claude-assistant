# Recette V2 sur Windows 11 avec Claude Code

Cette campagne porte sur le code `a6a2d68dd56fa117d1d70a492733b4a709016ccd`, le mod
`1.0.0-shared.42` et le script client KubeJS 12. La branche GitHub
[`qualification/v2-shared42`](https://github.com/B-DAGBERT/monifactory-claude-assistant/tree/qualification/v2-shared42)
peut recevoir des mises à jour de ces guides. Le code testé reste identifiable par le commit
ci-dessus. Les résultats Windows sont **PENDING** jusqu’au retour du poste de test.

## Préparer une copie jetable

1. Dans CurseForge, préparer une instance de test **Monifactory 0.13.8**, Minecraft 1.20.1,
   Forge 47.4.13. Garder les configurations publiques du pack, dont `config/quark-common.toml`
   et `kubejs/config/common.properties`. Créer un monde Expert neuf et un projet fictif en pause.
2. Garder le monde, les configurations privées, les sessions et les mémoires habituels dans
   leurs dossiers respectifs. Faire les essais de verrouillage et de restauration sur cette
   copie. Fermer cette instance et son assistant avant de remplacer des fichiers.
3. Ouvrir PowerShell dans un dossier de travail. Créer un checkout de test distinct avec
   Git, Python 3.12 avec Tk, Node, JDK 17 et Claude Code déjà connecté.

```powershell
git clone --branch qualification/v2-shared42 --single-branch https://github.com/B-DAGBERT/monifactory-claude-assistant.git monifactory-v2-qualification
Set-Location .\monifactory-v2-qualification
$SourceCommit = 'a6a2d68dd56fa117d1d70a492733b4a709016ccd'
git rev-parse HEAD
git status --short --branch
git merge-base --is-ancestor $SourceCommit HEAD
if ($LASTEXITCODE -ne 0) { throw 'Commit de référence absent' }
git diff --exit-code $SourceCommit HEAD -- . ':(exclude,glob)docs/**'
if ($LASTEXITCODE -ne 0) { throw 'Le code de la branche diffère du candidat qualifié' }
if (git status --porcelain) { throw 'Checkout de test modifié' }

$TestInstance = (Resolve-Path (Read-Host 'Dossier de la copie Monifactory jetable')).Path
$env:MINECRAFT_DIR = $TestInstance
$env:JAVA_HOME = (Resolve-Path (Read-Host 'Dossier du JDK 17')).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:GRADLE_USER_HOME = Join-Path $HOME '.gradle'
py -3.12 -m venv .venv
$Py = (Resolve-Path '.venv\Scripts\python.exe').Path
& $Py --version
& $Py -c 'import tkinter; print(tkinter.TkVersion)'
java -version
javac -version
node --version
npm --version
claude --version
& $Py -m pip install -r requirements.txt -r requirements-dev.txt
& $Py choose_ai.py claude
& $Py codex_setup.py check
```

Vérifier que `check` indique la copie de test, Claude et le JDK 17. La sélection écrit la
configuration du checkout de test. Consigner tout outil absent avant de poursuivre les essais.

## Compiler, vérifier et installer

```powershell
& $Py -m ruff format --check .
& $Py -m ruff check .
npm ci
npm run format:check
& .\mod\gradlew.bat -p mod checkJavaFormat
& $Py codex_setup.py build
& $Py -m unittest discover -s tests -v
& $Py -m monifactory_assistant.commands.scenarios tous
node --check jeu/assistant_ia.js
node --check serveur/assistant_ia_serveur.js
git diff --check
git status --short --branch
& $Py codex_setup.py install --dry-run
```

Noter les codes de sortie et les tests ignorés. La compilation remplit le cache Gson utilisé
par les contrats Java. La suite complète exerce notamment les contrats natifs Java et les
rejeux KubeJS sous Node. Une absence d’outil ou un test ignoré ne compte pas comme réussite.
La référence Mac est de **627 tests** et **26 scénarios EMI**.

Si la simulation vise la bonne copie et que ses processus sont fermés, installer puis vérifier
l’artefact copié. Garder le chemin de sauvegarde indiqué par l’installateur.
Pour WIN-15, préparer d’abord dans la copie le couple JAR et script de la version précédente.
Si la copie ne contenait pas l’assistant, le retour arrière retrouve cet état initial.

```powershell
$Jar = (Resolve-Path 'mod\build\libs\assistantia-1.0.0-shared.42.jar').Path
& $Py codex_setup.py install
$Installed = @(Get-ChildItem (Join-Path $TestInstance 'mods') -Filter 'assistantia-*.jar')
if ($Installed.Count -ne 1) { throw 'Il faut un seul JAR assistant dans la copie' }
if ((Get-FileHash $Jar).Hash -ne (Get-FileHash $Installed[0].FullName).Hash) { throw 'JAR installé différent du build' }
$Client = Join-Path $TestInstance 'kubejs\client_scripts\assistant_ia.js'
if ((Get-FileHash $Client).Hash -ne (Get-FileHash 'jeu\assistant_ia.js').Hash) { throw 'Script installé différent du candidat' }
Get-FileHash $Installed[0].FullName, $Client -Algorithm SHA256
& $Py assistant_mc.py
```

Lancer la copie depuis CurseForge. Confirmer que Forge charge `1.0.0-shared.42` depuis ce
profil et que le script annonce 12. Comparer le JAR installé au build local. Une compilation
sur un autre poste peut donner une autre empreinte d’archive. Conserver le commit, l’empreinte
locale et la version chargée dans le relevé. Le script client de référence a pour SHA-256
`a77b218d8778fd3dc123444a58b1e194d226080db0121e6c81d8e87175387f1e`.

## Essais dans Minecraft

Choisir **Recipe Tree** dans EMI puis **Y → Production**. Pour le bronze, sélectionner
l’alliage cuivre-étain comme branche du lingot, avec huit plaques comme objectif. Le
tableau commence à **NOT RUN** sur Windows.

| ID     | Opération et attendu                                                                                                                                                                              | Résultat |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- |
| WIN-01 | Entrer dans le monde Expert. Le JAR de test est le seul assistant chargé.                                                                                                                         | NOT RUN  |
| WIN-02 | Vérifier le chargement KubeJS des recettes avec la configuration du pack.                                                                                                                         | NOT RUN  |
| WIN-03 | Ouvrir EMI et constater que les recettes du pack sont disponibles.                                                                                                                                | NOT RUN  |
| WIN-04 | Calculer et ouvrir le diagnostic dans Production. Un état machine inconnu reste indiqué.                                                                                                          | NOT RUN  |
| WIN-05 | Cintreuse 6 cuivre et 2 étain. Marteau 9 cuivre et 3 étain. Comparer au Recipe Tree.                                                                                                              | NOT RUN  |
| WIN-06 | Changer de branche après calcul. L’ancien résultat cesse d’être actuel.                                                                                                                           | NOT RUN  |
| WIN-07 | Poser le verrou NTFS ci-dessous. Le marqueur reste lisible et son remplacement échoue.                                                                                                            | NOT RUN  |
| WIN-08 | Changer de branche sous verrou. La preuve ancienne est rejetée. Relancer après libération.                                                                                                        | NOT RUN  |
| WIN-09 | Quitter avec le bouton normal. Revenir dans le même monde. Le monde reste identifié, la session change et le nouveau calcul fonctionne.                                                           | NOT RUN  |
| WIN-10 | Porter un backpack avec un ingrédient, l’ouvrir puis le fermer. Comparer EMI et Production après synchronisation.                                                                                 | NOT RUN  |
| WIN-11 | Répartir le stock entre plusieurs coffres et un double. Relever le total EMI, le stock retenu par Production et les limites.                                                                      | NOT RUN  |
| WIN-12 | Utiliser une machine de test selon les commandes EMI existantes. Vérifier réglage virtuel, moule et quantités en mB quand applicables. Relever séparément l’état du lot dans Suivi et Production. | NOT RUN  |
| WIN-13 | Conserver un projet fictif en pause lors du calcul, de la reconnexion et du redémarrage Python. Comparer le fichier de sauvegarde.                                                                | NOT RUN  |
| WIN-14 | Une demande dans Discussion via Claude Code après un calcul. Appliquer le scénario ci-dessous.                                                                                                    | NOT RUN  |
| WIN-15 | Restaurer le couple JAR et script de la copie. Vérifier les empreintes et redémarrer.                                                                                                             | NOT RUN  |

Dans le candidat actuel, Production alloue le stock personnel attesté, dont le sac synchronisé.
Le total des coffres du scan EMI est distinct. Une combinaison cohérente de ces sources reste à
qualifier. Les réservations, sorties attendues et réceptions visibles dans Suivi ne constituent
pas un relevé de lot attesté pour Production. Noter la différence comme **PARTIAL** plutôt que
déduire un stock nul ou déclarer les deux vues équivalentes.

### Verrouillage NTFS

Une fois le plan de la cintreuse prêt, ouvrir une deuxième fenêtre PowerShell. Pointer
de nouveau vers la copie jetable. Cette fenêtre maintient un descripteur qui partage la
lecture et refuse l’écriture et le remplacement du marqueur.

```powershell
$TestInstance = (Resolve-Path (Read-Host 'Dossier de la copie Monifactory jetable')).Path
$Marker = Join-Path $TestInstance 'kubejs\assistant\catalogue_production_etat.json'
$Before = (Get-FileHash $Marker).Hash
$Lock = [System.IO.File]::Open($Marker, 'Open', 'Read', 'Read')
try {
    Read-Host 'Changer de branche EMI, vérifier le refus, puis appuyer sur Entrée'
    $After = (Get-FileHash $Marker).Hash
    (Get-Content -Raw $Marker | ConvertFrom-Json).etat
    "Octets du marqueur conservés sous verrou = $($Before -eq $After)"
} finally {
    $Lock.Dispose()
}
```

Sous verrou, passer de la cintreuse au marteau. Le marqueur peut rester `pret` sur disque.
L’ancien plan doit disparaître, et « Explique le plan de production V2 » doit être refusé
avant appel à Claude. Redémarrer l’assistant de test avec le marqueur encore présent et
vérifier qu’il ne restaure pas l’ancien résultat. Libérer le verrou. Relancer le calcul
pour le marteau et vérifier les nouveaux chiffres. Garder seulement l’état, l’erreur de
remplacement et la comparaison des empreintes. Les identifiants du pont restent privés.

### Un essai avec Claude Code

Avec le compte déjà connecté et autorisé sur le poste, poser une seule question depuis
**Discussion**, après le calcul de la cintreuse sur le projet fictif en pause.

> Explique le plan de production V2 pour huit plaques de bronze. Donne le coût et les
> manques vérifiés, distingue les relevés historiques du stock actuel, et explique si la
> machine est attestée. Laisse le projet en pause.

Comparer la réponse au résultat déterministe dans Production. Vérifier que l’assistant ne
transfère ni objet ni fluide, ne lance aucune machine et ne change pas le projet. Les injections
d’actions destructives se rejouent avec les fournisseurs simulés de la suite de tests. Les
commandes de consentement R5 appartiennent à une autre branche.

### Retour arrière et compte rendu

Fermer la copie Minecraft et son assistant. À partir du manifeste `installation.json` de la
sauvegarde annoncée, restaurer uniquement les JAR listés et le script client précédent.
Retirer le JAR candidat de cette copie avant le redémarrage. Comparer les empreintes avant
installation et après restauration, puis lancer le checkout Python correspondant. Le monde,
les plans, les coffres et les conversations restent dans leurs dossiers respectifs. Voir aussi
[le guide de collaboration](collaboration.md).

```text
Date et OS
SHA du checkout et du commit de code de référence
Version JAR chargée et SHA-256 local
Version et SHA-256 du script client KubeJS
Monifactory / Forge / Python / Java / Node / Claude CLI
Tests réussis, échecs, ignorés et durée
WIN-01 à WIN-15, résultat et niveau de preuve par ligne
Observations physiques, quantités retenues par EMI et Production, limites
Verrou NTFS, état sous verrou et résultat après libération
Rollback et comparaison des empreintes
Défaut éventuel, étape de reproduction, attendu, obtenu
Extrait expurgé du journal si utile
```

Utiliser les niveaux **AUTOMATED**, **LOCAL_INTEGRATION**, **IN_GAME**, **USER_CONFIRMED** ou
**NOT_TESTED**. Enlever chemins personnels, noms, coordonnées privées, identifiants de monde
et de session, et tout extrait d’une conversation personnelle.
