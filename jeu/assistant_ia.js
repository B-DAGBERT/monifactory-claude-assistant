// Assistant IA (Claude) : exporte l'état du jeu, mémorise le contenu des coffres ouverts, et affiche
// à l'écran les lignes préparées par l'assistant (plan.json). Les calculs se font dans assistant_mc.py.
// Installé et mis à jour automatiquement par assistant_mc.py : ne pas modifier cette copie.
//
// Pièges de ce Rhino (KubeJS 6) :
// - les textes passent souvent par les méthodes Java (substring, split, length) : voir longueur, couper, decoupe ;
// - les listes internes de Java (List.of, keySet...) refusent les appels : liste() les copie dans une ArrayList ;
// - pas de let/const dans un bloc (try, if, boucle) à l'intérieur d'une fonction fléchée : Rhino y voit une
//   redéclaration à chaque appel. Mettre ce code dans une fonction normale.

const DOSSIER = 'kubejs/assistant/'
const VERSION_SCRIPT = 10  // à augmenter à chaque modification : l'assistant demande F3 + T si le jeu a une autre version
const VERSION_RECETTES = 2 // export des recettes plus ancien : refait au chargement (2 : durée et EU/t des recettes GregTech)
const COULEURS = { or: 0xFFC94A, blanc: 0xFFFFFF, gris: 0xA8A8A8, vert: 0x6BE36B, bleu: 0x8FC7FF, rouge: 0xFF8A7A, jaune: 0xFFE45C }
const FOND = 0xB4101418 | 0
const LARGEUR_MAX = 230
const HUD_X = 6, HUD_Y = 16      // sous le compteur de FPS
const HUD_LARGEUR_ECRAN = 0.3    // le panneau prend au plus 30 % de la largeur de l'écran
const HUD_HAUTEUR_ECRAN = 0.7    // et 70 % de sa hauteur
const RECETTES_PAR_TICK = 400
const RECETTES_PAR_FICHIER = 15000
const STOCKAGE = /chest|barrel|crate|drawer|shulker|storage|cabinet|drum|safe|backpack/
const MINERAI = /_ore$/
const MINERAIS_MAX = 3000            // blocs de minerai retenus au plus (les plus anciens sont oubliés)
const CASES_INVENTAIRE_JOUEUR = 46   // écran de l'inventaire du joueur (aussi derrière le menu pause, le chat...)
const VERSION_STOCKAGE = 2           // les coffres mémorisés par une version plus ancienne (faussés) sont oubliés
const ORIENTATION = { south: 0, west: 90, north: 180, east: 270 }

const ArrayList = Java.loadClass('java.util.ArrayList')
let EmiApi = null, EmiReload = null, QuestFile = null
try {
  EmiApi = Java.loadClass('dev.emi.emi.api.EmiApi')
  EmiReload = Java.loadClass('dev.emi.emi.runtime.EmiReloadManager')
} catch (e) { console.warn('Assistant IA : EMI introuvable, pas d\'export des recettes') }
try { QuestFile = Java.loadClass('dev.ftb.mods.ftbquests.client.ClientQuestFile') } catch (e) { console.warn('Assistant IA : FTB Quests introuvable') }

let ticks = 0
let plan = null          // plan.json : { version, visible, lignes }
let icones = {}          // id -> ItemStack pour dessiner les icônes
let inventaire = {}      // id -> nombre
let lignes = []          // ce qu'on dessine : { s, c, icone }
let hauteur = 0, largeur = 0
let iconesActives = true
let derniereCommande = null
let exportRecettes = null
let etatRecettes = { etat: 'attente' }
let erreurs = []
let stockage = {}        // "dimension|x|y|z" -> { bloc, nom, x, y, z, dimension, t, objets: [{ item, n }] }
let machines = {}        // "dimension|x|y|z" -> { bloc, nom, x, y, z, dimension, t } : blocs à interface déjà ouverts
let stockageModifie = false
let conteneur = null     // bloc dont l'interface est ouverte (coffre ou machine)
let minerais = {}        // "dimension|x|y|z" -> { bloc, x, y, z, dimension, t, mine } : minerais vus ou minés
let mineraiVise = null   // dernier minerai regardé : s'il disparaît, il vient d'être miné
let mineraisModifies = false
let ecranPrecedent = null
let manquants = {}       // coffres mémorisés introuvables à la dernière vérification
let dimensionPrecedente = null, debutDimension = 0

function noterErreur(ou, e) {
  const msg = ou + ' : ' + e
  if (erreurs.indexOf(msg) < 0) {
    erreurs.push(msg)
    if (erreurs.length > 5) erreurs.shift()
    console.error('Assistant IA, ' + msg)
  }
}

// Java List / Collection / tableau -> vrai tableau JS
function liste(x) {
  if (x == null) return []
  const out = []
  if (typeof x.iterator == 'function') {
    const it = new ArrayList(x).iterator()
    while (it.hasNext()) out.push(it.next())
  } else {
    for (let i = 0; i < x.length; i++) out.push(x[i])
  }
  return out
}

function str(x) { return x == null ? '' : String(x) }
function num(x) { return x == null ? null : Number(x) }
function longueur(s) { return typeof s.length == 'function' ? s.length() : s.length }
function couper(s, n) { s = str(s); return longueur(s) > n ? s.substring(0, n) : s }
function decoupe(s, separateur) { return liste(str(s).split(separateur)).map(str) }
function texte(composant) { return composant == null ? '' : str(composant.getString()) }
function sansCodes(s) { return str(s).replace(/&[0-9a-fk-or]/g, '') }

// Orientation du joueur en degrés (0 = sud, 90 = ouest). getYRot n'est pas toujours accessible :
// à défaut, la direction cardinale fournie par KubeJS.
function lireYaw(p) {
  try { return Number(p.getYRot()) } catch (e) { /* essai suivant */ }
  try { return Number(p.m_146908_()) } catch (e) { /* essai suivant */ }
  let f = ORIENTATION[str(p.facing)]
  return f == null ? null : f
}

// ---------- Lecture du jeu ----------

// Première valeur lisible parmi plusieurs façons de la lire (selon les correspondances de noms de KubeJS)
function premiereValeur(lectures) {
  for (let i = 0; i < lectures.length; i++) {
    try {
      let v = lectures[i]()
      if (v != null) return v
    } catch (e) { /* lecture suivante */ }
  }
  return null
}

function nombre(lectures) {
  const v = premiereValeur(lectures)
  return v == null || isNaN(Number(v)) ? null : Number(v)
}

function casesLibres() {
  const inv = Client.player.getInventory()
  let libres = 0
  for (let i = 0; i < 36; i++) if (inv.getItem(i).isEmpty()) libres++
  return libres
}

function lireInventaire() {
  const inv = Client.player.getInventory()
  const res = {}
  const n = inv.getContainerSize()
  for (let i = 0; i < n; i++) {
    let st = inv.getItem(i)
    if (st.isEmpty()) continue
    let id = str(st.id)
    res[id] = (res[id] || 0) + st.getCount()
  }
  return res
}

function decrireQuete(q, equipe, chapitre) {
  const taches = liste(q.getTasksAsList()).map(t => {
    const d = { titre: texte(t.getTitle()), progres: Number(equipe.getProgress(t)), max: Number(t.getMaxProgress()) }
    let st = null
    try { st = t.getItemStack() } catch (e) { /* pas une tâche d'objet */ }
    if (st != null && !st.isEmpty()) d.item = str(st.id)
    return d
  })
  const description = couper(liste(q.getRawDescription()).map(sansCodes).join(' '), 600)
  return {
    id: str(q.getCodeString()), titre: texte(q.getTitle()), chapitre: chapitre,
    optionnelle: q.isOptional() == true, taches: taches, description: description
  }
}

function lireQuetes(details) {
  if (QuestFile == null || !QuestFile.exists()) return null
  const fichier = QuestFile.INSTANCE
  const equipe = fichier.selfTeamData
  if (equipe == null) return null
  let terminees = 0, total = 0
  const chapitres = [], disponibles = [], faites = []
  // Chapitres visibles seulement : le pack a un chapitre caché de quêtes techniques
  liste(fichier.getVisibleChapters(equipe)).forEach(ch => {
    const titreChapitre = texte(ch.getTitle())
    let cTerminees = 0, cTotal = 0
    liste(ch.getQuests()).forEach(q => {
      cTotal++
      if (equipe.isCompleted(q)) {
        cTerminees++
        if (details) faites.push(titreChapitre + ' > ' + texte(q.getTitle()))
      } else if (details && equipe.canStartTasks(q) && q.isVisible(equipe)) {
        disponibles.push(decrireQuete(q, equipe, titreChapitre))
      }
    })
    terminees += cTerminees
    total += cTotal
    if (details) chapitres.push({ titre: titreChapitre, terminees: cTerminees, total: cTotal })
  })
  return { terminees: terminees, total: total, chapitres: chapitres, disponibles: disponibles, terminees_titres: faites }
}

function ecrireEtat() {
  const p = Client.player
  const quetes = lireQuetes(false)
  JsonIO.write(DOSSIER + 'etat.json', {
    t: Date.now(),
    version_script: VERSION_SCRIPT,
    serveur_recu: mondeRecu,
    joueur: texte(p.getName()),
    dimension: str(Client.level.dimension),   // KubeJS en fait une propriété (ResourceLocation)
    x: Math.floor(p.getX()), y: Math.floor(p.getY()), z: Math.floor(p.getZ()),
    yaw: lireYaw(p),
    ecran: Client.screen == null ? null : texte(Client.screen.getTitle()),
    coffre_ouvert: conteneur == null || !conteneur.stockage ? null : conteneur.cle,
    machine_ouverte: conteneur == null || conteneur.stockage ? null : conteneur.cle,
    en_main: str(p.getMainHandItem().id),
    // Pour le coach : santé, faim, heure (0 = lever du soleil, 13000 = nuit), pluie, place libre, mort
    sante: nombre([() => p.getHealth(), () => p.health]),
    sante_max: nombre([() => p.getMaxHealth(), () => p.maxHealth]),
    faim: nombre([() => p.getFoodData().getFoodLevel(), () => p.foodLevel]),
    heure: nombre([() => Client.level.getDayTime() % 24000, () => Client.level.dayTime % 24000]),
    pluie: premiereValeur([() => Client.level.isRaining() == true, () => Client.level.raining == true]),
    cases_libres: casesLibres(),
    mort: premiereValeur([() => p.isDeadOrDying() == true, () => p.getHealth() <= 0]),
    inventaire: inventaire,
    quetes_terminees: quetes == null ? -1 : quetes.terminees,
    quetes_total: quetes == null ? -1 : quetes.total,
    plan_version: plan == null ? 0 : plan.version,
    recettes: etatRecettes,
    erreurs: erreurs
  })
}

function ecrireQuetes() {
  const quetes = lireQuetes(true)
  if (quetes != null) {
    quetes.t = Date.now()
    JsonIO.write(DOSSIER + 'quetes.json', quetes)
  }
}

// ---------- Mémoire des coffres ----------

function chargerStockage() {
  const s = JsonIO.read(DOSSIER + 'stockage.json')
  if (s == null) return
  liste(s.get('conteneurs')).forEach(c => {
    if (Number(c.get('v')) != VERSION_STOCKAGE) return
    const entree = {
      v: VERSION_STOCKAGE, signature: str(c.get('signature')),
      cle: str(c.get('cle')), bloc: str(c.get('bloc')), nom: str(c.get('nom')), dimension: str(c.get('dimension')),
      x: Number(c.get('x')), y: Number(c.get('y')), z: Number(c.get('z')), t: Number(c.get('t')),
      objets: liste(c.get('objets')).map(o => ({ item: str(o.get('item')), n: Number(o.get('n')) }))
    }
    stockage[entree.cle] = entree
  })
  liste(s.get('machines')).forEach(c => {
    if (Number(c.get('v')) != VERSION_STOCKAGE) return
    const entree = {
      v: VERSION_STOCKAGE, cle: str(c.get('cle')), bloc: str(c.get('bloc')), nom: str(c.get('nom')),
      dimension: str(c.get('dimension')), x: Number(c.get('x')), y: Number(c.get('y')), z: Number(c.get('z')),
      t: Number(c.get('t'))
    }
    machines[entree.cle] = entree
  })
}

function chargerMinerais() {
  const s = JsonIO.read(DOSSIER + 'filons.json')
  if (s == null) return
  liste(s.get('blocs')).forEach(c => {
    const entree = {
      cle: str(c.get('cle')), bloc: str(c.get('bloc')), dimension: str(c.get('dimension')),
      x: Number(c.get('x')), y: Number(c.get('y')), z: Number(c.get('z')), t: Number(c.get('t')),
      mine: str(c.get('mine')) == 'true'
    }
    minerais[entree.cle] = entree
  })
}

// À l'ouverture d'un écran : le bloc regardé est celui qu'on vient d'ouvrir (coffre, machine...)
function surOuvertureEcran() {
  conteneur = null
  const visee = Client.player.rayTrace(6)
  if (visee == null || visee.block == null) return
  const bloc = visee.block
  const id = str(bloc.id)
  const dimension = str(Client.level.dimension)
  conteneur = {
    cle: dimension + '|' + bloc.x + '|' + bloc.y + '|' + bloc.z, bloc: id, nom: texte(Client.screen.getTitle()),
    dimension: dimension, x: Number(bloc.x), y: Number(bloc.y), z: Number(bloc.z), stockage: STOCKAGE.test(id)
  }
}

// Une machine (tout bloc à interface qui n'est pas un coffre) : on retient où elle est
function retenirMachine() {
  const ancienne = machines[conteneur.cle]
  if (ancienne != null && ancienne.bloc == conteneur.bloc && ancienne.nom == conteneur.nom) return
  machines[conteneur.cle] = {
    v: VERSION_STOCKAGE, cle: conteneur.cle, bloc: conteneur.bloc, nom: conteneur.nom, dimension: conteneur.dimension,
    x: conteneur.x, y: conteneur.y, z: conteneur.z, t: Date.now()
  }
  stockageModifie = true
}

// Contenu du coffre ouvert = tout ce que montre l'écran moins l'inventaire du joueur (36 cases)
function lireConteneur() {
  const menu = Client.player.openInventory
  let stacks = null
  try { stacks = liste(menu.getItems()) } catch (e) { stacks = liste(menu.slots).map(s => s.getItem()) }
  // L'écran ouvert ne montre que l'inventaire du joueur (menu pause, inventaire, établi...) : rien à retenir
  if (stacks.length == CASES_INVENTAIRE_JOUEUR) return
  if (!conteneur.stockage) { retenirMachine(); return }
  const total = {}
  for (let i = 0; i < stacks.length; i++) {
    let st = stacks[i]
    if (st.isEmpty()) continue
    let id = str(st.id)
    total[id] = (total[id] || 0) + st.getCount()
  }
  const inv = Client.player.getInventory()
  for (let i = 0; i < 36; i++) {
    let st = inv.getItem(i)
    if (st.isEmpty()) continue
    let id = str(st.id)
    total[id] = (total[id] || 0) - st.getCount()
  }
  const objets = []
  Object.keys(total).forEach(id => { if (total[id] > 0) objets.push({ item: id, n: total[id] }) })
  const signature = objets.map(o => o.item + ':' + o.n).sort().join(',')
  const ancien = stockage[conteneur.cle]
  if (ancien != null && ancien.signature == signature) return   // rien n'a changé
  stockage[conteneur.cle] = {
    v: VERSION_STOCKAGE, signature: signature,
    cle: conteneur.cle, bloc: conteneur.bloc, nom: conteneur.nom, dimension: conteneur.dimension,
    x: conteneur.x, y: conteneur.y, z: conteneur.z, t: Date.now(), objets: objets
  }
  stockageModifie = true
}

// Un coffre mémorisé qui n'est plus là (cassé, remplacé) est oublié. Seulement près du joueur, une fois le
// monde chargé, et après deux vérifications : un morceau de monde pas encore reçu se lit comme du vide.
function verifierCoffres() {
  const dimension = str(Client.level.dimension)
  if (dimension != dimensionPrecedente) {
    dimensionPrecedente = dimension
    debutDimension = ticks
    manquants = {}
  }
  if (ticks - debutDimension < 600) return
  verifierCarte(stockage, dimension)
  verifierCarte(machines, dimension)
}

function verifierCarte(carte, dimension) {
  const p = Client.player
  const cles = Object.keys(carte)
  for (let i = 0; i < cles.length; i++) {
    let c = carte[cles[i]]
    if (c.dimension != dimension) continue
    let dx = c.x - p.getX(), dz = c.z - p.getZ()
    if (dx * dx + dz * dz > 32 * 32 || str(Client.level.getBlock(c.x, c.y, c.z).id) == c.bloc) {
      delete manquants[c.cle]
    } else if (manquants[c.cle]) {
      delete carte[c.cle]
      delete manquants[c.cle]
      stockageModifie = true
    } else {
      manquants[c.cle] = true
    }
  }
}

// Minerais : ceux que le joueur regarde sont retenus ; si le dernier regardé disparaît, il vient d'être miné
function suivreMinerais() {
  if (mineraiVise != null && str(Client.level.getBlock(mineraiVise.x, mineraiVise.y, mineraiVise.z).id) != mineraiVise.bloc) {
    mineraiVise.mine = true
    mineraiVise.t = Date.now()
    mineraisModifies = true
    mineraiVise = null
  }
  const visee = Client.player.rayTrace(6)
  if (visee == null || visee.block == null) return
  const bloc = visee.block
  const id = str(bloc.id)
  if (!MINERAI.test(id)) return
  const dimension = str(Client.level.dimension)
  const cle = dimension + '|' + bloc.x + '|' + bloc.y + '|' + bloc.z
  if (minerais[cle] == null) {
    minerais[cle] = { cle: cle, bloc: id, dimension: dimension, x: Number(bloc.x), y: Number(bloc.y), z: Number(bloc.z), t: Date.now(), mine: false }
    mineraisModifies = true
  }
  mineraiVise = minerais[cle]
}

function ecrireMinerais() {
  if (!mineraisModifies) return
  mineraisModifies = false
  let blocs = Object.keys(minerais).map(k => minerais[k])
  if (blocs.length > MINERAIS_MAX) {
    blocs.sort((a, b) => b.t - a.t)
    blocs = blocs.slice(0, MINERAIS_MAX)
    minerais = {}
    blocs.forEach(b => { minerais[b.cle] = b })
  }
  JsonIO.write(DOSSIER + 'filons.json', { blocs: blocs })
}

function suivreConteneur() {
  const ecran = Client.screen
  // Chaque nouvel écran est un autre coffre, ou un menu : on regarde à nouveau quel bloc est visé.
  // (Ne surveiller que le passage « aucun écran → écran » confondait deux coffres ouverts coup sur coup.)
  if (ecran == null) conteneur = null
  else if (ecran != ecranPrecedent) surOuvertureEcran()
  ecranPrecedent = ecran
  if (conteneur != null) lireConteneur()
  if (stockageModifie) {
    stockageModifie = false
    JsonIO.write(DOSSIER + 'stockage.json', {
      conteneurs: Object.keys(stockage).map(k => stockage[k]),
      machines: Object.keys(machines).map(k => machines[k])
    })
  }
}

// ---------- Export des recettes (EMI) ----------

function decrireStack(st) {
  const n = Number(st.getAmount())
  const chance = Number(st.getChance())
  return (n != 1 ? n + 'x ' : '') + str(st.getId()) + (chance > 0 && chance < 1 ? ' (' + Math.round(chance * 100) + '%)' : '')
}

function decrireIngredient(ing) {
  if (ing.isEmpty()) return null
  const n = Number(ing.getAmount())
  // Un tag a un champ key (TagKey). Sur un EmiStack, .key renvoie getKey() (un Item) : pas de location()
  const tag = ing.key
  if (tag != null && typeof tag.location == 'function') return (n != 1 ? n + 'x ' : '') + '#' + str(tag.location())
  const stacks = liste(ing.getEmiStacks())
  if (stacks.length == 1) return decrireStack(stacks[0])
  return (n != 1 ? n + 'x ' : '') + stacks.slice(0, 3).map(s => str(s.getId())).join('|')
}

let gestionRecettes = null   // les recettes du jeu : pour retrouver la durée et l'EU/t d'une recette GregTech

// « | 200t 16EU/t » pour une recette GregTech (20 ticks = 1 seconde), rien pour les autres
function infosGregTech(categorie, id) {
  if (gestionRecettes == null || id == null || categorie.indexOf('gtceu:') != 0) return ''
  const gt = gestionRecettes.byKey(id).orElse(null)
  if (gt == null || gt.duration == null) return ''
  return ' | ' + Number(gt.duration) + 't ' + Number(gt.getInputEUt().voltage()) + 'EU/t'
}

function decrireRecette(r) {
  const entrees = liste(r.getInputs()).map(decrireIngredient).filter(s => s != null)
  const sorties = liste(r.getOutputs()).filter(s => !s.isEmpty()).map(decrireStack)
  const id = r.getId()
  const categorie = str(r.getCategory().getId())
  let gt = ''
  try { gt = infosGregTech(categorie, id) } catch (e) { /* recette sans durée */ }
  return categorie + ' | ' + entrees.join(' + ') + ' => ' + sorties.join(' + ') + (id == null ? '' : ' | ' + str(id)) + gt
}

function lancerExportRecettes() {
  if (EmiApi == null || exportRecettes != null) return
  if (EmiReload != null && !EmiReload.isLoaded()) return
  const recettes = EmiApi.getRecipeManager().getRecipes()
  if (recettes == null || recettes.size() == 0) return
  gestionRecettes = premiereValeur([() => Client.level.getRecipeManager(), () => Client.level.m_7465_()])
  // Copie : la liste d'EMI est une liste interne de Java dont Rhino ne peut pas appeler get()
  const copie = new ArrayList(recettes)
  exportRecettes = { liste: copie, total: copie.size(), i: 0, partie: 0, lignes: [], ok: 0, ratees: 0 }
  etatRecettes = { etat: 'export', progression: 0 }
}

function avancerExportRecettes() {
  const ex = exportRecettes
  const fin = Math.min(ex.i + RECETTES_PAR_TICK, ex.total)
  for (; ex.i < fin; ex.i++) {
    try {
      ex.lignes.push(decrireRecette(ex.liste.get(ex.i)))
      ex.ok++
    } catch (e) {
      // recette illisible : on passe, mais on garde les premières erreurs pour comprendre
      if (ex.ratees++ < 3) noterErreur('recette n°' + ex.i, e)
    }
  }
  if (ex.lignes.length >= RECETTES_PAR_FICHIER || ex.i >= ex.total) {
    JsonIO.write(DOSSIER + 'recettes_' + ex.partie + '.json', { recettes: ex.lignes })
    ex.partie++
    ex.lignes = []
  }
  etatRecettes = { etat: 'export', progression: Math.floor(ex.i * 100 / ex.total) }
  if (ex.i >= ex.total) {
    if (ex.ok > ex.total / 2) {
      // Les parties en trop d'un export précédent plus long sont effacées (JsonIO.write avec null supprime)
      for (let k = ex.partie; k < ex.partie + 20; k++) JsonIO.write(DOSSIER + 'recettes_' + k + '.json', null)
      JsonIO.write(DOSSIER + 'recettes_info.json', {
        t: Date.now(), version: VERSION_RECETTES, nombre: ex.ok, ratees: ex.ratees, parties: ex.partie
      })
      etatRecettes = { etat: 'pret', nombre: ex.ok }
    } else {
      // pas de recettes_info.json : l'export sera retenté au prochain rechargement
      etatRecettes = { etat: 'echec', lues: ex.ok, ratees: ex.ratees }
    }
    exportRecettes = null
  }
}

function verifierRecettes() {
  // Premier lancement : exporte toutes les recettes une fois EMI chargé
  if (exportRecettes != null || etatRecettes.etat != 'attente') return
  const info = JsonIO.read(DOSSIER + 'recettes_info.json')
  if (info == null || (Number(info.get('version')) || 1) < VERSION_RECETTES) lancerExportRecettes()
  else etatRecettes = { etat: 'pret' }
}

// ---------- Ce que l'assistant demande d'afficher ----------

function lireLigne(l) {
  const d = l.get('dir')
  return {
    t: str(l.get('t')), c: str(l.get('c')), item: str(l.get('item')), indent: Number(l.get('indent')) || 0,
    dir: d == null ? null : { x: num(d.get('x')), y: num(d.get('y')), z: num(d.get('z')) },
    // rangée d'icônes avec quantités (recette de l'étape en cours)
    icones: liste(l.get('icones')).map(o => ({ item: str(o.get('item')), n: Number(o.get('n')) || 1 }))
  }
}

function lirePlan() {
  const p = JsonIO.read(DOSSIER + 'plan.json')
  if (p == null) { plan = null; return }
  plan = {
    version: Number(p.get('version')) || 0,
    visible: str(p.get('visible')) != 'false',
    lignes: liste(p.get('lignes')).map(lireLigne)
  }
}

function lireCommande() {
  const c = JsonIO.read(DOSSIER + 'commande.json')
  if (c == null) return
  const n = str(c.get('n'))
  if (derniereCommande == null) { derniereCommande = n; return }   // ancienne commande : ignorée au démarrage
  if (n == derniereCommande) return
  derniereCommande = n
  const action = str(c.get('action'))
  if (action == 'exporter_recettes') lancerExportRecettes()
  if (action == 'oublier_coffres') {
    liste(c.get('cles')).forEach(cle => { delete stockage[str(cle)] })
    stockageModifie = true
  }
}

// ---------- Affichage ----------

function decouper(s, largeurMax) {
  const font = Client.font
  const mots = decoupe(s, ' ')
  const res = []
  let ligne = ''
  mots.forEach(m => {
    const essai = ligne == '' ? m : ligne + ' ' + m
    if (font.width(essai) > largeurMax && ligne != '') { res.push(ligne); ligne = m } else ligne = essai
  })
  if (ligne != '') res.push(ligne)
  return res
}

// Distance et flèche vers un point, recalculées en direct
function direction(c) {
  const p = Client.player
  let s = ''
  if (c.x != null && c.z != null) {
    let dx = c.x + 0.5 - p.getX(), dz = c.z + 0.5 - p.getZ()
    let distance = Math.round(Math.sqrt(dx * dx + dz * dz))
    let angle = Math.atan2(-dx, dz) * 180 / Math.PI   // orientation (yaw) qui regarde la cible
    let yaw = lireYaw(p)
    if (distance < 3) {
      s += ' : tu y es'
    } else if (yaw != null) {
      let relatif = (((angle - yaw) % 360) + 360) % 360
      s += ' : ' + distance + ' blocs ' + ['↑', '↗', '→', '↘', '↓', '↙', '←', '↖'][Math.round(relatif / 45) % 8]
    } else {
      let points = ['sud', 'sud-ouest', 'ouest', 'nord-ouest', 'nord', 'nord-est', 'est', 'sud-est']
      s += ' : ' + distance + ' blocs au ' + points[Math.round(((angle % 360) + 360) % 360 / 45) % 8]
    }
  }
  if (c.y != null) {
    let dy = c.y - Math.floor(p.getY())
    s += dy == 0 ? ' (bonne hauteur)' : dy > 0 ? ' (monte de ' + dy + ')' : ' (descends de ' + (-dy) + ')'
  }
  return s
}

function icone(id) {
  if (!icones[id]) icones[id] = Item.of(id)
  return icones[id]
}

function construireLignes() {
  const res = []
  if (plan != null && plan.visible) {
    for (let i = 0; i < plan.lignes.length; i++) {
      let l = plan.lignes[i]
      let s = l.dir == null ? l.t : l.t + direction(l.dir)
      let c = COULEURS[l.c] != null ? COULEURS[l.c] : COULEURS.blanc
      if (l.icones.length > 0) {
        res.push({ s: s, c: c, rangee: l.icones.map(o => icone(o.item)), quantites: l.icones.map(o => o.n) })
      } else if (l.item != '') {
        res.push({ s: s, c: c, icone: icone(l.item) })
      } else {
        let morceaux = decouper(s, LARGEUR_MAX - l.indent)
        for (let j = 0; j < morceaux.length; j++) res.push({ s: (j > 0 ? '   ' : '') + morceaux[j], c: c })
      }
    }
  }
  lignes = res
  hauteur = 0
  largeur = 0
  for (let i = 0; i < res.length; i++) {
    let r = res[i]
    hauteur += r.icone || r.rangee ? 18 : 10
    largeur = Math.max(largeur, Client.font.width(r.s) + (r.icone ? 18 : 0) + (r.rangee ? 4 + 18 * r.rangee.length : 0))
  }
}

function dessinerIcone(event, stack, x, y) {
  if (!iconesActives) return
  try { event.graphics.renderItem(stack, x, y) } catch (e) { iconesActives = false; noterErreur('icônes', e) }
}

// Fonction normale, pas fléchée (voir les pièges en haut du fichier)
// Avec le mod de l'assistant, c'est lui qui dessine le panneau : ce script ne sert plus que de secours
const MOD_PRESENT = premiereValeur([() => Platform.isLoaded('assistantia') == true]) == true

function dessiner(event) {
  if (MOD_PRESENT || lignes.length == 0 || Client.options.hideGui) return
  // L'écran de l'assistant (mod, touche Y) montre déjà tout : le panneau le recouvrirait
  if (Client.screen != null && texte(Client.screen.getTitle()) == 'Assistant IA') return
  // Réduit le panneau quand l'échelle d'interface est grande, pour ne pas cacher le jeu
  let echelle = Math.min(1, event.width * HUD_LARGEUR_ECRAN / (largeur + 8),
    event.height * HUD_HAUTEUR_ECRAN / (hauteur + 6))
  let y = 0
  event.push()
  try {
    event.translate(HUD_X, HUD_Y)
    event.scale(echelle)
    // Fond : même séquence que les rectangles du Painter de KubeJS
    event.setPositionColorShader()
    event.blend(true)
    event.beginQuads(false)
    event.rectangle(-4, -4, 0, largeur + 8, hauteur + 6, FOND)
    event.end()
    for (let i = 0; i < lignes.length; i++) {
      let l = lignes[i]
      if (l.rangee) {
        event.text(Text.of(l.s), 0, y + 4, l.c, true)
        let x0 = Client.font.width(l.s) + 4
        for (let k = 0; k < l.rangee.length; k++) {
          dessinerIcone(event, l.rangee[k], x0 + 18 * k, y)
          if (l.quantites[k] > 1) {
            // la quantité par-dessus l'icône, comme dans un inventaire
            let n = '' + l.quantites[k]
            event.push()
            event.translate(0, 0, 200)
            event.text(Text.of(n), x0 + 18 * k + 17 - Client.font.width(n), y + 9, COULEURS.blanc, true)
            event.pop()
          }
        }
        y += 18
      } else if (l.icone) {
        dessinerIcone(event, l.icone, 0, y)
        event.text(Text.of(l.s), 18, y + 4, l.c, true)
        y += 18
      } else {
        event.text(Text.of(l.s), 0, y, l.c, true)
        y += 10
      }
    }
  } catch (e) {
    noterErreur('affichage', e)
    lignes = []
  }
  event.pop()
}

// ---------- Données du serveur (script assistant_ia_serveur.js sur le serveur) ----------

let mondeRecu = 0   // heure de la dernière réception (ms)

// Dire au serveur que l'assistant est lancé : il ne regarde autour du joueur que dans ce cas
function signalerPresence() {
  Client.player.sendData('assistant_ia_present', { v: VERSION_SCRIPT })
}

function lirePartie(d, cle) {
  return str(premiereValeur([() => d.getString(cle), () => d.get(cle).getAsString()]))
}

// Le serveur envoie ce qu'il voit en plusieurs morceaux de texte JSON (un texte réseau est limité à 64 Ko)
function recevoirMonde(event) {
  const d = event.data
  const n = nombre([() => d.getInt('n'), () => d.get('n').getAsInt()])
  let json = ''
  for (let i = 0; i < n; i++) json += lirePartie(d, 'p' + i)
  JsonIO.write(DOSSIER + 'monde.json', JsonIO.parseRaw(json).getAsJsonObject())
  mondeRecu = Date.now()
}

NetworkEvents.dataReceived('assistant_ia_monde', event => essayer('données du serveur', () => recevoirMonde(event)))

// ---------- Boucle ----------

// Chaque tâche a son propre try : une erreur dans l'une ne bloque pas les autres
function essayer(nom, tache) {
  try { tache() } catch (e) { noterErreur(nom, e) }
}

essayer('chargement des coffres', chargerStockage)
essayer('chargement des minerais', chargerMinerais)

ClientEvents.tick(event => {
  if (Client.player == null || Client.level == null) return
  ticks++
  if (exportRecettes != null) essayer('export des recettes', avancerExportRecettes)
  if (ticks % 10 == 0) {
    essayer('inventaire', () => { inventaire = lireInventaire() })
    essayer('lecture du plan', lirePlan)
    essayer('affichage du plan', construireLignes)
  }
  if (ticks % 5 == 0) essayer('coffres', suivreConteneur)
  if (ticks % 4 == 2) essayer('minerais', suivreMinerais)
  if (ticks % 100 == 20) essayer('écriture des minerais', ecrireMinerais)
  if (ticks % 20 == 0) {
    essayer('commande', lireCommande)
    essayer('état', ecrireEtat)
  }
  if (ticks % 100 == 80) essayer('coffres cassés', verifierCoffres)
  if (ticks % 200 == 10) essayer('présence', signalerPresence)
  if (ticks % 200 == 40) essayer('quêtes', ecrireQuetes)
  if (ticks % 100 == 60) essayer('recettes', verifierRecettes)
})

ClientEvents.paintScreen(event => dessiner(event))
