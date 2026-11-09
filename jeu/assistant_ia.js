// Assistant IA (Claude) : exporte l'état du jeu et mémorise le contenu des coffres ouverts. L'affichage
// en jeu est celui du mod de l'assistant ; les calculs se font dans assistant_mc.py.
// Installé et mis à jour automatiquement par assistant_mc.py : ne pas modifier cette copie.
//
// Pièges de ce Rhino (KubeJS 6) :
// - les textes passent souvent par les méthodes Java (substring, split, length) : voir longueur, couper ;
// - les listes internes de Java (List.of, keySet...) refusent les appels : liste() les copie dans une ArrayList ;
// - pas de let/const dans un bloc (try, if, boucle) à l'intérieur d'une fonction fléchée : Rhino y voit une
//   redéclaration à chaque appel. Mettre ce code dans une fonction normale.

const DOSSIER = 'kubejs/assistant/'
const VERSION_SCRIPT = 13  // à augmenter à chaque modification : l'assistant demande F3 + T si le jeu a une autre version
const VERSION_RECETTES = 2 // export des recettes plus ancien : refait au chargement (2 : durée et EU/t des recettes GregTech)
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

let IdentiteMonde = null, MemoireMonde = null, Echanges = null
try {
  IdentiteMonde = Java.loadClass('fr.assistantia.IdentiteMonde')
  MemoireMonde = Java.loadClass('fr.assistantia.MemoireMondeKubeJS')
} catch (e) { /* Ancien mod ou fonctionnement KubeJS seul. */ }
// Avec le mod : relevés écrits sur son fil d'écriture (le jeu n'attend pas le disque) et
// progression des quêtes comparée par empreinte. Sans lui, écriture directe comme avant.
try { Echanges = Java.loadClass('fr.assistantia.EchangesKubeJS') } catch (e) { /* Mod plus ancien. */ }
const ECHANGES = Echanges != null && typeof Echanges.publier == 'function'
const CONFIRMATION_ETAT = 2000    // etat.json inchangé : réécrit pour rester récent (Python : 5 s)
const PROGRES_QUETES = 30000      // progression de tâche seule : quetes.json réécrit au plus toutes les 30 s
const ATTENTE_STOCKAGE = 2000     // coffre qu'on range : stockage.json écrit au plus toutes les 2 s

let contexte = null
let memoireMonde = null
let ticks = 0
let inventaire = {}      // id -> nombre
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
// Le mod suit lui-même les filons GregTech (scan complet, compte en direct) : le relevé KubeJS de
// filons.json ne sert plus qu'au fonctionnement sans mod, où l'application le lit en secours.
let FilonsMod = null
try { FilonsMod = Java.loadClass('fr.assistantia.Filons') } catch (e) { /* mod absent ou ancien */ }
let ecranPrecedent = null
let manquants = {}       // coffres mémorisés introuvables à la dernière vérification
let dimensionPrecedente = null, debutDimension = 0
let etatEcrit = null, etatEcritA = 0   // dernier etat.json écrit : texte sans l'heure, et date
let quetesEcrites = null // empreintes du dernier quetes.json écrit : { quetes, progres, t }
let resume = null        // dernier comptage des quêtes : { empreinte, terminees, total }
let stockageEnAttente = 0, ecranStockage = null   // première modification non écrite, et l'écran d'alors
let mondeVerifie = null, sessionVerifiee = null   // identifiants déjà validés

function isolationDemandee() {
  if (IdentiteMonde != null) return IdentiteMonde.isolation() == true
  try {
    let activation = JsonIO.readJson(DOSSIER + 'isolation_mondes.json')
    if (activation == null) return false
    if (!activation.isJsonObject()) return true
    let objet = activation.getAsJsonObject()
    let version = objet.get('v'), actif = objet.get('actif')
    return !(version != null && version.isJsonPrimitive()
      && version.getAsJsonPrimitive().isNumber() && str(version.getAsString()) === '1'
      && actif != null && actif.isJsonPrimitive()
      && actif.getAsJsonPrimitive().isBoolean() && actif.getAsBoolean() == false)
  } catch (e) { return true }
}

function contexteCourant() {
  if (!isolationDemandee()) return { monde: '', session: '', dimension: '' }
  if (IdentiteMonde == null || MemoireMonde == null || Client.level == null || Client.player == null) return null
  const monde = str(IdentiteMonde.monde()), session = str(IdentiteMonde.session())
  // Lu à chaque tick : seul un identifiant nouveau est revalidé
  if (monde !== mondeVerifie || session !== sessionVerifiee) {
    if (!/^m1_[0-9a-f]{64}$/.test(monde) || !/^[0-9a-f]{32}$/.test(session)) return null
    mondeVerifie = monde
    sessionVerifiee = session
  }
  return { monde: monde, session: session, dimension: str(Client.level.dimension) }
}

function memeContexte(a, b) {
  return a == null || b == null ? a == b
    : a.monde === b.monde && a.session === b.session && a.dimension === b.dimension
}

function annoterTrame(data) {
  if (contexte != null && contexte.monde) {
    data.monde_actif = contexte.monde
    data.session_monde = contexte.session
    data.dimension_monde = contexte.dimension
  }
  return data
}

function contexteTrame(data) {
  return contexte != null && (!contexte.monde
    || str(data.get('monde_actif')) === contexte.monde
      && str(data.get('session_monde')) === contexte.session
      && str(data.get('dimension_monde')) === contexte.dimension)
}

// Avec le mod, les mémoires sont écrites sur son fil d'écriture (le jeu n'attend pas le disque) ;
// une relecture voit toujours la dernière écriture, même encore en file
function memoireDifferee() {
  return MemoireMonde != null && typeof MemoireMonde.ecrireHistorique == 'function'
}

function lireMemoire(nom) {
  if (memoireMonde) return MemoireMonde.lire(memoireMonde, nom)
  return memoireDifferee() ? MemoireMonde.lireHistorique(nom) : JsonIO.read(DOSSIER + nom)
}

function ecrireMemoire(nom, data) {
  if (memoireMonde) MemoireMonde.ecrire(memoireMonde, nom, JsonIO.of(data).getAsJsonObject())
  else if (memoireDifferee()) MemoireMonde.ecrireHistorique(nom, JsonIO.of(data).getAsJsonObject())
  else JsonIO.write(DOSSIER + nom, data)
}

function amorcerCommande() {
  derniereCommande = null
  try {
    let c = JsonIO.read(DOSSIER + 'commande.json')
    // Ce qui existe déjà appartient à l'ancien écran/chargement. Une commande apparue
    // ensuite reste nouvelle, même si aucun fichier n'existait à la connexion.
    derniereCommande = c == null ? '' : str(c.get('n'))
  } catch (e) {
    // Lecture incertaine : conserver la suppression prudente de la première commande lue.
    noterErreur('initialisation des commandes', e)
  }
}

function synchroniserContexte(suivant) {
  if (memeContexte(contexte, suivant)) return
  const monde = suivant == null ? null : suivant.monde
  if (memoireMonde !== monde) {
    if (memoireMonde != null) {
      ecrireStockage()
      ecrireMinerais()
    }
    contexte = null
    stockage = {}
    machines = {}
    minerais = {}
    memoireMonde = monde
    if (monde != null) {
      try {
        chargerStockage()
        chargerMinerais()
      } catch (e) {
        memoireMonde = null
        throw e
      }
    }
  }
  conteneur = ecranPrecedent = mineraiVise = null
  dimensionPrecedente = null
  etatEcrit = quetesEcrites = ecranStockage = null   // relevés du nouveau contexte écrits sans attendre
  stockageEnAttente = 0
  amorcerCommande()
  inventaire = {}
  manquants = {}
  mondeRecu = 0
  contexte = suivant
}

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
function longueur(s) { return typeof s.length == 'function' ? s.length() : s.length }
function couper(s, n) { s = str(s); return longueur(s) > n ? s.substring(0, n) : s }
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

// Avec le mod, le fichier est remplacé atomiquement sur son fil d'écriture ; sinon écriture directe
function ecrireReleve(nom, data) {
  if (ECHANGES && Echanges.publier(nom, JSON.stringify(data, null, '\t'))) return
  JsonIO.write(DOSSIER + nom, data)
}

function cleContexte() {
  return contexte == null ? '' : contexte.monde + '|' + contexte.session + '|' + contexte.dimension
}

// Empreintes de la progression FTB Quests, lues par le mod sans parcourir les quêtes (null sans lui)
function empreinteQuetes() {
  if (!ECHANGES || QuestFile == null || !QuestFile.exists()) return null
  const fichier = QuestFile.INSTANCE
  const equipe = fichier.selfTeamData
  if (equipe == null) return null
  const quetes = str(Echanges.empreinteQuetes(fichier, equipe)), progres = str(Echanges.progresQuetes(equipe))
  return quetes === '' || progres === '' ? null : { quetes: quetes + '|' + cleContexte(), progres: progres }
}

// Le comptage des quêtes de etat.json n'est refait que si elles ont changé
function resumeQuetes() {
  const empreinte = empreinteQuetes()
  if (empreinte == null || resume == null || resume.empreinte !== empreinte.quetes) {
    const quetes = lireQuetes(false)
    resume = quetes == null ? null
      : { empreinte: empreinte == null ? null : empreinte.quetes, terminees: quetes.terminees, total: quetes.total }
  }
  return resume
}

function ecrireEtat() {
  const p = Client.player
  const quetes = resumeQuetes()
  const etat = annoterTrame({
    t: 0,   // daté à l'écriture
    version_script: VERSION_SCRIPT,
    serveur_recu: ECHANGES && contexte != null
      ? Number(Echanges.recu(contexte.monde, contexte.session, contexte.dimension)) : mondeRecu,
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
    heure: null,   // avance sans cesse : lue à l'écriture, pas un changement
    pluie: premiereValeur([() => Client.level.isRaining() == true, () => Client.level.raining == true]),
    cases_libres: casesLibres(),
    mort: premiereValeur([() => p.isDeadOrDying() == true, () => p.getHealth() <= 0]),
    inventaire: inventaire,
    quetes_terminees: quetes == null ? -1 : quetes.terminees,
    quetes_total: quetes == null ? -1 : quetes.total,
    recettes: etatRecettes,
    erreurs: erreurs
  })
  // Rien de changé : seulement confirmé toutes les 2 s
  const signature = JSON.stringify(etat)
  const maintenant = Date.now()
  if (signature === etatEcrit && maintenant - etatEcritA < CONFIRMATION_ETAT) return
  etat.t = maintenant
  etat.heure = nombre([() => Client.level.getDayTime() % 24000, () => Client.level.dayTime % 24000])
  ecrireReleve('etat.json', etat)
  etatEcrit = signature
  etatEcritA = maintenant
}

// Réécrit seulement si les quêtes ont changé ; une progression de tâche seule, au plus toutes les 30 s
function ecrireQuetes() {
  const empreinte = empreinteQuetes()
  const maintenant = Date.now()
  if (empreinte != null && quetesEcrites != null && empreinte.quetes === quetesEcrites.quetes
    && (empreinte.progres === quetesEcrites.progres || maintenant - quetesEcrites.t < PROGRES_QUETES)) return
  const quetes = lireQuetes(true)
  if (quetes != null) {
    quetes.t = maintenant
    ecrireReleve('quetes.json', annoterTrame(quetes))
    quetesEcrites = empreinte == null ? null : { quetes: empreinte.quetes, progres: empreinte.progres, t: maintenant }
    if (empreinte != null) resume = { empreinte: empreinte.quetes, terminees: quetes.terminees, total: quetes.total }
  }
}

// ---------- Mémoire des coffres ----------

function chargerStockage() {
  const s = lireMemoire('stockage.json')
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
  const s = lireMemoire('filons.json')
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

function filonsDuMod() {
  if (FilonsMod == null) return false
  try { return FilonsMod.suiviNatif() == true } catch (e) { return false }
}

// Minerais : ceux que le joueur regarde sont retenus ; si le dernier regardé disparaît, il vient d'être miné
function suivreMinerais() {
  if (filonsDuMod()) {
    mineraiVise = null
    return
  }
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
  // Aucune écriture (ni gel du rendu) quand le mod tient les filons : filons.json reste tel quel.
  if (!mineraisModifies || filonsDuMod()) return
  let blocs = Object.keys(minerais).map(k => minerais[k])
  if (blocs.length > MINERAIS_MAX) {
    blocs.sort((a, b) => b.t - a.t)
    blocs = blocs.slice(0, MINERAIS_MAX)
    minerais = {}
    blocs.forEach(b => { minerais[b.cle] = b })
  }
  ecrireMemoire('filons.json', { blocs: blocs })
  mineraisModifies = false
}

function suivreConteneur() {
  const ecran = Client.screen
  // Chaque nouvel écran est un autre coffre, ou un menu : on regarde à nouveau quel bloc est visé.
  // (Ne surveiller que le passage « aucun écran → écran » confondait deux coffres ouverts coup sur coup.)
  if (ecran == null) conteneur = null
  else if (ecran != ecranPrecedent) surOuvertureEcran()
  ecranPrecedent = ecran
  if (conteneur != null) lireConteneur()
  if (!stockageModifie) return
  // Un coffre qu'on range change à chaque déplacement : toute la mémoire est écrite au plus
  // toutes les 2 s tant qu'il reste ouvert, et dès que l'écran se ferme ou change.
  const maintenant = Date.now()
  if (stockageEnAttente == 0) {
    stockageEnAttente = maintenant
    ecranStockage = ecran
  }
  if (ecran == null || ecran != ecranStockage || maintenant - stockageEnAttente >= ATTENTE_STOCKAGE) ecrireStockage()
}

function ecrireStockage() {
  if (!stockageModifie) return
  ecrireMemoire('stockage.json', {
    conteneurs: Object.keys(stockage).map(k => stockage[k]),
    machines: Object.keys(machines).map(k => machines[k])
  })
  stockageModifie = false
  stockageEnAttente = 0
  ecranStockage = null
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

// ---------- Ce que l'assistant demande ----------

function lireCommande() {
  const c = JsonIO.read(DOSSIER + 'commande.json')
  if (c == null || !contexteTrame(c)) return
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
  const courant = contexteCourant()
  if (courant == null || !memeContexte(courant, contexte)) return
  if (!Number.isSafeInteger(n) || n < 1 || n > 30) return
  if (courant.monde && (
    lirePartie(d, 'assistant_monde') !== courant.monde
    || lirePartie(d, 'assistant_session') !== courant.session
  )) return
  // Avec le mod : assemblage, contrôle de la dimension, annotation et écriture hors du thread du jeu
  if (typeof Echanges != 'undefined' && Echanges != null && typeof Echanges.transmettreMonde == 'function') {
    const erreur = str(Echanges.erreurMonde())
    if (erreur !== '') noterErreur('données du serveur', erreur)
    Echanges.transmettreMonde(d, n, courant.monde, courant.session, courant.dimension)
    return
  }
  let json = ''
  for (let i = 0; i < n; i++) json += lirePartie(d, 'p' + i)
  const trame = JsonIO.parseRaw(json).getAsJsonObject()
  if (courant.monde) {
    let dimension = trame.get('dimension')
    if (dimension == null || str(dimension.getAsString()) !== courant.dimension) return
    trame.addProperty('monde_actif', courant.monde)
    trame.addProperty('session_monde', courant.session)
    trame.addProperty('dimension_monde', courant.dimension)
  }
  JsonIO.write(DOSSIER + 'monde.json', trame)
  mondeRecu = Date.now()
}

NetworkEvents.dataReceived('assistant_ia_monde', event => essayer('données du serveur', () => recevoirMonde(event)))

// ---------- Boucle ----------

// Chaque tâche a son propre try : une erreur dans l'une ne bloque pas les autres
function essayer(nom, tache) {
  try { tache() } catch (e) { noterErreur(nom, e) }
}

ClientEvents.tick(function (event) {
  let courant = null
  essayer('contexte du monde', function () {
    courant = contexteCourant()
    synchroniserContexte(courant)
  })
  if (Client.player == null || Client.level == null || courant == null || !memeContexte(contexte, courant)) return
  ticks++
  if (exportRecettes != null) essayer('export des recettes', avancerExportRecettes)
  if (ticks % 5 == 0) essayer('coffres', suivreConteneur)
  if (ticks % 4 == 2) essayer('minerais', suivreMinerais)
  if (ticks % 100 == 20) essayer('écriture des minerais', ecrireMinerais)
  if (ticks % 20 == 0) {
    essayer('inventaire', () => { inventaire = lireInventaire() })   // pour etat.json seulement
    essayer('commande', lireCommande)
    essayer('état', ecrireEtat)
  }
  if (ticks % 100 == 80) essayer('coffres cassés', function () { verifierCoffres(); ecrireStockage() })
  if (ticks % 200 == 10) essayer('présence', signalerPresence)
  if (ticks % 200 == 40) essayer('quêtes', ecrireQuetes)
  if (ticks % 100 == 60) essayer('recettes', verifierRecettes)
})
