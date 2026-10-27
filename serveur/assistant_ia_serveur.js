// Assistant IA (Claude), côté serveur : regarde autour de chaque joueur qui a l'assistant (coffres, machines
// GregTech, boilers, multiblocs) et lui envoie ce qu'il voit toutes les 3 secondes.
// LECTURE SEULE : ce script ne modifie rien dans le monde.
// À déposer dans kubejs/server_scripts/ sur le serveur, puis /reload (ou redémarrer le serveur).
//
// Pièges de ce Rhino (KubeJS 6), comme dans le script client :
// - les listes internes de Java refusent les appels : liste() les copie dans une ArrayList ;
// - pas de let/const dans un bloc à l'intérieur d'une fonction fléchée : utiliser des fonctions normales.

const VERSION_SERVEUR = 1
const RAYON_CHUNKS = 2            // 5 x 5 chunks autour du joueur (80 blocs de côté)
const INTERVALLE = 60             // en ticks : toutes les 3 secondes
const PRESENCE_MAX = 30000        // ms sans signe de vie du client : l'assistant n'est plus lancé
const TAILLE_PARTIE = 30000       // un texte réseau ne doit pas dépasser 64 Ko : on découpe
const TAILLE_MAX = 600000         // au-delà, on garde les blocs les plus proches
const CASES_MAX = 200             // cases lues au plus par inventaire
const IGNORES = /pipe|cable|wire|conduit|frame|_sign|banner|bed$|skull|head$/

const ArrayList = Java.loadClass('java.util.ArrayList')
let ticksServeur = 0
let presents = {}                 // nom du joueur -> dernier signe de vie de son assistant (ms)
let erreursServeur = []

function noterErreurServeur(ou, e) {
  const msg = ou + ' : ' + e
  if (erreursServeur.indexOf(msg) < 0) {
    erreursServeur.push(msg)
    if (erreursServeur.length > 8) erreursServeur.shift()
    console.error('Assistant IA serveur, ' + msg)
  }
}

function essayerServeur(nom, tache) {
  try { tache() } catch (e) { noterErreurServeur(nom, e) }
}

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

// Première lecture qui marche parmi plusieurs (les noms des méthodes de Minecraft varient selon KubeJS)
function premiere(ou, lectures) {
  let derniere = null
  for (let i = 0; i < lectures.length; i++) {
    try {
      let v = lectures[i]()
      if (v != null) return v
    } catch (e) { derniere = e }
  }
  if (derniere != null) noterErreurServeur(ou, derniere)
  return null
}

// ---------- Ce qu'il y a dans un chunk ----------

function positionsTechniques(level, cx, cz) {
  const chunk = premiere('chunk', [() => level.getChunk(cx, cz), () => level.m_6325_(cx, cz)])
  if (chunk == null) return []
  const positions = premiere('blocs du chunk', [
    () => liste(chunk.getBlockEntitiesPos()),
    () => liste(chunk.getBlockEntities().keySet()),
    () => liste(chunk.m_5928_())
  ])
  return positions == null ? [] : positions
}

function lireInventaireBloc(bloc) {
  let inv = null
  try { inv = bloc.inventory } catch (e) { return [] }
  if (inv == null) return []
  const n = Math.min(Number(inv.getSlots()), CASES_MAX)
  const total = {}
  for (let i = 0; i < n; i++) {
    let st = inv.getStackInSlot(i)
    if (st == null || st.isEmpty()) continue
    let id = str(st.id)
    total[id] = (total[id] || 0) + Number(st.getCount())
  }
  return Object.keys(total).map(k => [k, total[k]])
}

function quantite(reservoir) {
  return [Number(reservoir.getFluidInTank(0).getAmount()), Number(reservoir.getTankCapacity(0))]
}

// État d'une machine GregTech : recette en cours, attente (et pourquoi), boiler, multibloc formé
function etatMachine(mm) {
  const m = {}
  if (typeof mm.getRecipeLogic == 'function') {
    let rl = mm.getRecipeLogic()
    m.s = str(rl.getStatus())
    m.pr = Number(rl.getProgress())
    m.du = Number(rl.getDuration())
    let raison = rl.getWaitingReason()
    if (raison != null) m.r = str(raison.getString())
  }
  if (typeof mm.isFormed == 'function') m.f = mm.isFormed() == true
  if (mm.waterTank != null && typeof mm.getCurrentTemperature == 'function') {
    m.eau = quantite(mm.waterTank)
    m.t = [Number(mm.getCurrentTemperature()), Number(mm.getMaxTemperature())]
    m.se = mm.isHasNoWater() == true
  }
  if (mm.steamTank != null) m.vap = quantite(mm.steamTank)
  return m
}

function decrireBloc(level, pos) {
  const bloc = level.getBlock(pos)
  const id = str(bloc.id)
  if (IGNORES.test(id)) return null
  const info = { p: [Number(bloc.x), Number(bloc.y), Number(bloc.z)], b: id }
  let mm = null
  try { mm = bloc.entity.metaMachine } catch (e) { /* pas une machine GregTech */ }
  if (mm != null) {
    try { info.m = etatMachine(mm) } catch (e) { noterErreurServeur('machine ' + id, e) }
  }
  const objets = lireInventaireBloc(bloc)
  if (objets.length > 0) info.o = objets
  if (info.m == null && info.o == null) return null
  return info
}

// ---------- Envoi au joueur ----------

function scannerPour(p) {
  const nom = str(p.getName().getString())
  if (!presents[nom] || Date.now() - presents[nom] > PRESENCE_MAX) return
  const level = p.level
  const px = p.getX(), pz = p.getZ()
  const cx = Math.floor(px / 16), cz = Math.floor(pz / 16)
  let blocs = []
  for (let dx = -RAYON_CHUNKS; dx <= RAYON_CHUNKS; dx++) {
    for (let dz = -RAYON_CHUNKS; dz <= RAYON_CHUNKS; dz++) {
      let positions = positionsTechniques(level, cx + dx, cz + dz)
      for (let i = 0; i < positions.length; i++) {
        try {
          let info = decrireBloc(level, positions[i])
          if (info != null) blocs.push(info)
        } catch (e) { noterErreurServeur('bloc', e) }
      }
    }
  }
  const joueurs = []
  liste(p.server.players).forEach(autre => {
    if (str(autre.getName().getString()) != nom) {
      joueurs.push({ nom: str(autre.getName().getString()), dimension: str(autre.level.dimension),
        x: Math.floor(autre.getX()), y: Math.floor(autre.getY()), z: Math.floor(autre.getZ()) })
    }
  })
  blocs.sort((a, b) => (Math.abs(a.p[0] - px) + Math.abs(a.p[2] - pz)) - (Math.abs(b.p[0] - px) + Math.abs(b.p[2] - pz)))
  let json = ''
  for (;;) {
    json = JSON.stringify({
      v: VERSION_SERVEUR, t: Date.now(), dimension: str(level.dimension),
      zone: [cx - RAYON_CHUNKS, cz - RAYON_CHUNKS, cx + RAYON_CHUNKS, cz + RAYON_CHUNKS],
      blocs: blocs, joueurs: joueurs, erreurs: erreursServeur
    })
    if (longueur(json) <= TAILLE_MAX || blocs.length < 10) break
    blocs = blocs.slice(0, Math.floor(blocs.length / 2))
  }
  const data = {}
  let n = 0
  for (let i = 0; i < longueur(json); i += TAILLE_PARTIE) {
    data['p' + n] = json.substring(i, Math.min(i + TAILLE_PARTIE, longueur(json)))
    n++
  }
  data.n = n
  p.sendData('assistant_ia_monde', data)
}

// Le script client de l'assistant se signale toutes les 10 secondes : on ne travaille que pour lui
NetworkEvents.dataReceived('assistant_ia_present', event => essayerServeur('présence', () => {
  presents[str(event.player.getName().getString())] = Date.now()
}))

ServerEvents.tick(event => {
  ticksServeur++
  if (ticksServeur % INTERVALLE != 0) return
  liste(event.server.players).forEach(p => essayerServeur('scan', () => scannerPour(p)))
})
