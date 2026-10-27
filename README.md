# Monifactory Claude Assistant

Un assistant IA **dans** Minecraft, pour le modpack [Monifactory](https://www.curseforge.com/minecraft/modpacks/monifactory) (GregTech CEu Modern, Minecraft 1.20.1). Il connaît ta partie en temps réel (inventaire, coffres, quêtes, machines, recettes du pack). Il te dit **quoi faire ensuite, dans quel ordre, comment et pourquoi**, et tu lui parles à la voix pendant que tu joues.

Il utilise Claude via **Claude Code et ton abonnement Claude** (Pro ou Max) : pas de clé API, pas de facturation à l'usage.

> **English summary.** An in-game AI assistant for the Monifactory modpack, powered by Claude through the Claude Code CLI on your Claude subscription (no API key). It reads your game live: inventory, chests, FTB quests, GregTech machines and every recipe in the pack. It plans your next objective and shows it on a HUD, and answers voice questions. It highlights items in chests, explains any item or machine, fills crafting grids and guides you with 3D beams. The interface and voice are in French; item names stay in English as in game. Windows only.

---

## Ce qu'il fait

**Progression guidée**
- Un objectif à la fois, choisi d'après tes quêtes et ce que tu possèdes, avec le **pourquoi**, les étapes dans l'ordre et l'arbre de fabrication.
- Il met à jour en direct ce qui reste **à fabriquer**, **à récupérer dans tes coffres** et **à récolter**.
- Une **feuille de route** (« passer en LV ») enchaîne les objectifs : le suivant se prépare tout seul.
- Un panneau discret en jeu montre l'objectif, la progression, l'étape en cours, les 3 prochains objets et une flèche vers la destination.

**Tableau de bord (touche Y)**
- Onglets Plan, Feuille de route, Base (machines vues par le serveur) et Discussion.
- Tous les objets du plan apparaissent avec leur icône. Un clic ouvre la recette dans EMI, un clic droit ouvre la fiche « à quoi ça sert ».

**Voix**
- Tu parles en maintenant **F8**. La transcription se fait en local (faster-whisper) et la réponse est lue par une voix neuronale.
- Il agit aussi sur ce que tu lui demandes : changer d'objectif, retenir un lieu, te guider, oublier un coffre cassé, planifier une usine…

**Coffres et recherche**
- Il retient chaque coffre ouvert, sait où est chaque objet et t'y guide.
- « *Trouve-moi mes torches* » :
  - les objets pulsent en cyan dans les interfaces ;
  - les coffres qui en contiennent ont un **contour visible à travers les murs**, avec leur contenu au-dessus.
- « *Mets en surbrillance tous les minerais qui donnent du Iron Ingot* » : Claude cherche lui-même dans les recettes du pack ce qui donne ce résultat, puis surligne toute la liste.

**Fiche « à quoi ça sert » (touche I)**
- Elle porte sur l'objet sous la souris, le bloc regardé ou l'objet en main. On y trouve :
  - à quoi il sert ;
  - comment l'utiliser ;
  - un **schéma de branchement en icônes** ;
  - le piège classique du mode Expert.
- Pour une machine posée, elle donne aussi son **état en direct**.
- La première fiche prend 15 à 20 s, ensuite elle est instantanée.

**Aide à la fabrication**
- À l'établi, la fabrication que le plan attend apparaît au-dessus de l'interface, avec **Remplir** et **Tout (×N)**. Ces boutons remplissent la grille comme le « + » d'EMI.
- Les objets dont le plan a besoin brillent en vert dans tes coffres.

**Coach**
- Il prévient tout seul :
  - des dangers : boiler à sec, santé, nuit loin de la base ;
  - des étapes et quêtes terminées ;
  - des machines bloquées.
- Il propose son aide si tu n'avances plus, et fait un briefing quand tu reprends la partie.

**Yeux du serveur** (optionnel)
- Avec le script serveur, il voit tous les coffres autour de toi, même jamais ouverts.
- Il voit aussi l'état des machines GregTech (en marche, en attente, multibloc non formé), l'eau et la température des boilers, et les autres joueurs.

## Comment ça marche

```
 Minecraft (client)                         Ton PC                              Claude
┌──────────────────────────┐   fichiers    ┌─────────────────────────┐   CLI   ┌─────────────────┐
│ KubeJS : jeu/assistant_ia│  JSON dans    │ assistant_mc.py         │ ──────> │ Claude Code     │
│   état, quêtes, recettes,│ kubejs/       │  plan, coach, voix,     │ <────── │ (ton abonnement)│
│   coffres, filons        │ assistant/ <─>│  mémoire de la partie   │         └─────────────────┘
│ Mod Forge « assistantia »│               └─────────────────────────┘
│   HUD, écran Y, fiche I, │
│   surbrillance, faisceaux│ <── données du serveur (optionnel) ── KubeJS serveur : coffres, machines
└──────────────────────────┘
```

- **`assistant_mc.py`** : l'application. Elle tourne en arrière-plan et gère :
  - les raccourcis et la voix ;
  - les appels à Claude Code ;
  - le plan et le coach ;
  - la mémoire de la partie.
- **`jeu/assistant_ia.js`** : le script KubeJS client, copié automatiquement dans l'instance. Il exporte :
  - l'état du joueur ;
  - les quêtes FTB ;
  - toutes les recettes (via EMI), les coffres ouverts et les minerais vus.
- **`mod/`** : le mod Forge côté client. Il dessine l'interface : panneau, écran Y, fiche I, surbrillance, contours, faisceaux et boutons de remplissage.
- **`serveur/assistant_ia_serveur.js`** : un script KubeJS serveur optionnel, en lecture seule. Il envoie au client les coffres et l'état des machines autour du joueur.

## Prérequis

- **Windows 10 ou 11.** Les raccourcis globaux, la voix de secours et le lanceur sont propres à Windows.
- **Monifactory** (Minecraft 1.20.1, Forge 47), qui inclut déjà KubeJS, EMI, FTB Quests et GregTech CEu Modern.
- **Python 3.11 ou plus récent**, avec la case « Add python.exe to PATH » cochée à l'installation.
- **Un abonnement Claude Pro ou Max** et **Claude Code**, connecté à ton compte : soit le CLI `claude`, soit l'app de bureau Claude qui l'inclut.
- **Un micro** pour parler à l'assistant.
- **Internet** pour Claude et la voix neuronale. La reconnaissance vocale, elle, est locale.

## Installation

1. **Récupère le dépôt** : `git clone`, ou *Code > Download ZIP* puis décompresse-le où tu veux.
2. **Installe les modules Python** : double-clic sur **`Installer.bat`**. Il le fait une seule fois. Au premier lancement, le modèle de reconnaissance vocale (~500 Mo) se télécharge tout seul.
3. **Connecte Claude Code à ton abonnement**, une seule fois : lance `claude` dans un terminal et connecte-toi avec ton compte claude.ai. Si tu as l'app de bureau Claude, l'assistant trouve aussi le Claude Code qu'elle contient.
   - Ne définis pas de variable `ANTHROPIC_API_KEY` : l'assistant les retire de toute façon, pour rester sur ton abonnement.
4. **Indique où est ton instance**, si elle n'est pas à l'emplacement par défaut de CurseForge (`%USERPROFILE%\curseforge\minecraft\Instances\Monifactory`) : modifie `MINECRAFT_DIR` en haut de `assistant_mc.py`.
5. **Installe le mod** :
   - télécharge `assistantia-1.0.0.jar` dans les [Releases](../../releases) ;
   - mets-le dans le dossier `mods` de ton instance.
   - Le mod est côté client uniquement : il n'a pas besoin d'être sur le serveur et n'empêche pas de s'y connecter.
6. **Lance l'assistant** : double-clic sur **`Lancer assistant.bat`**, avant ou pendant le jeu.
   - Il copie tout seul le script KubeJS dans `kubejs/client_scripts` de l'instance.
   - Si Minecraft était déjà ouvert, fais **F3 + T** une fois. Un avertissement rouge en jeu te le rappelle quand c'est nécessaire.
7. **(Optionnel) Script serveur** :
   - copie `serveur/assistant_ia_serveur.js` dans `kubejs/server_scripts/` du serveur, puis fais `/reload` ;
   - « serveur ✔ » s'affiche dans l'écran Y quand il fonctionne ;
   - il ne travaille que pour les joueurs dont l'assistant est lancé.

En solo, le script serveur va dans `kubejs/server_scripts/` de ton instance.

## Utilisation

| Touche | Action |
|---|---|
| **F8** (maintenir) | Parler à l'assistant, relâcher pour envoyer |
| **Maj + F8** | Question écrite |
| **F7** | Objectif suivant : Claude regarde ta progression et prépare un plan |
| **Y** | Tableau de bord |
| **I** | Fiche « à quoi ça sert » (objet survolé, bloc regardé ou objet en main) ; **Maj + I** : objet en main |
| **F9** | Cacher ou réafficher le panneau (coupe aussi la voix) |
| **F10** | Voix on/off |
| **Ctrl + F8** | Nouvelle conversation |

Y et I se changent dans *Options > Contrôles > Assistant IA*. Les touches F se changent en haut de `assistant_mc.py`.

**Exemples de phrases (en maintenant F8)**

- « Fais-moi une feuille de route pour passer en LV » · « Ajoute la Steam Foundry juste après » · « Objectif suivant »
- « Régénère le plan pour la quête Steam Grinder » · « Planifie-moi une usine de 2 Steel par minute »
- « Où est mon Alloy Smelter ? » · « Trouve-moi mes torches » · « C'est bon, enlève la surbrillance »
- « Retiens qu'ici c'est ma base » · « Ramène-moi à mon filon de Galena »
- « J'ai cassé ce coffre, oublie-le » · « Coupe la voix » · « Qu'est-ce que j'ai fait ce soir ? »

## Réglages

Ils se trouvent en haut de `assistant_mc.py`.

| Réglage | Rôle |
|---|---|
| `MINECRAFT_DIR` | Dossier de l'instance Monifactory |
| `MODEL_PLAN`, `MODEL_QUESTION`, `MODEL_QUICK` | Modèles Claude utilisés : `sonnet` pour réfléchir, `haiku` pour les questions simples ; `opus` est possible mais plus lent et consomme plus de quota |
| `AUTO_NEXT` | Préparer tout seul l'objectif suivant |
| `COACH` | `"calme"` (dangers seulement), `"normal"` ou `"bavard"` |
| `BRIEFING`, `BRIEFING_PAUSE` | Briefing de reprise, après combien de minutes sans jouer |
| `STUCK_MINUTES` | Au bout de combien de minutes sans avancer le coach propose son aide |
| `SEARCH_MINUTES` | Durée de la surbrillance d'une recherche |
| `VOICE`, `VOICE_SPEED`, `SPEAK` | Voix de lecture (Microsoft Edge, gratuite) et vitesse |
| `WHISPER_MODEL` | `"small"` comprend mieux, `"base"` est plus rapide |

## Confidentialité et quota

- Les questions et les plans passent par **Claude Code, sur ton compte Claude**. Ils consomment le quota de ton abonnement, comme une conversation normale.
- Ce qui est envoyé à Claude :
  - l'état de ta partie (inventaire, contenu des coffres connus, quêtes, machines) ;
  - avec **F7** et **F8**, une capture d'écran du jeu.
- Claude Code tourne en lecture seule sur les données exportées : seuls Read, Grep, Glob, WebSearch et WebFetch sont autorisés.
- La reconnaissance vocale est **locale**. La lecture à voix haute utilise le service gratuit de Microsoft Edge ; sans Internet, la voix Windows prend le relais.
- Ce que l'assistant retient reste sur ton PC, dans son dossier : journal de partie, lieux, coffres vus par le serveur, fiches, plan.

## Compiler le mod

Le `.jar` est fourni dans les Releases. Pour le recompiler, il faut un **JDK 17** (par exemple Eclipse Temurin) :

```bat
cd mod
set JAVA_HOME=C:\chemin\vers\jdk-17
gradlew.bat build
```

Le mod compilé se trouve dans `mod/build/libs/assistantia-1.0.0.jar`. La première compilation télécharge Minecraft et Forge, ce qui prend quelques minutes.

## Dépannage

- **« Assistant arrêté » sur le panneau, ou plus de réponse** : l'application ne tourne plus. Touche **Y** puis **Relancer l'assistant**, ou double-clic sur `Lancer assistant.bat`. Une question posée juste avant est traitée dès qu'il redémarre.
- **Rien ne s'affiche en jeu** :
  - vérifie que l'assistant tourne ;
  - fais **F3 + T** ;
  - regarde `assistant.log` (dossier de l'assistant) et `logs/kubejs/client.log` (dossier de l'instance).
- **« Claude Code introuvable »** : installe Claude Code ou l'app de bureau Claude, puis connecte-toi une fois avec `claude`.
- **L'assistant n'entend rien** : maintiens F8 pendant toute la phrase et vérifie le micro par défaut de Windows.
- **« Instance Monifactory introuvable »** : règle `MINECRAFT_DIR` en haut de `assistant_mc.py`.
- **« serveur non connecté »** dans l'onglet Base : le script serveur n'est pas installé ou pas rechargé (`/reload`).
- **Une fiche « à quoi ça sert » est fausse** : bouton **Refaire** dans la fiche.

## Limites

- Les explications et le plan sont pensés pour **Monifactory** (mode Expert compris). Avec un autre pack 1.20.1 qui a KubeJS, EMI et FTB Quests, le reste fonctionne en grande partie, mais sans garantie.
- **Windows uniquement**, et interface en français. Les noms d'objets restent en anglais, comme dans le jeu.
- Claude peut se tromper : il vérifie les recettes dans celles du pack, mais garde un œil critique.

## Licence

[MIT](LICENSE).

Ce projet n'est affilié ni à Anthropic, ni à Mojang ou Microsoft, ni à l'équipe Monifactory.
