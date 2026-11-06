// Assistant Monifactory, côté serveur : regarde autour de chaque joueur qui a l'assistant (coffres, machines
// GregTech, boilers, multiblocs) et lui envoie ce qu'il voit toutes les 3 secondes.
// LECTURE SEULE : ce script ne modifie rien dans le monde.
// À déposer dans kubejs/server_scripts/ sur le serveur, puis /reload (ou redémarrer le serveur).
//
// Pièges de ce Rhino (KubeJS 6), comme dans le script client :
// - les listes internes de Java refusent les appels : liste() les copie dans une ArrayList ;
// - pas de let/const dans un bloc à l'intérieur d'une fonction fléchée : utiliser des fonctions normales.

const VERSION_SERVEUR = 3
const RAYON_CHUNKS = 2            // 5 x 5 chunks autour du joueur (80 blocs de côté)
const INTERVALLE = 60             // en ticks : toutes les 3 secondes
const PRESENCE_MAX = 600         // ticks sans signe de vie du client
const TAILLE_PARTIE = 20000       // 3 octets max par unité UTF-16 dans une chaîne NBT
const TAILLE_MAX = 600000         // plafond en octets pour le contenu UTF modifié du relevé
const CASES_MAX = 200             // cases lues au plus par inventaire
const IGNORES = /pipe|cable|wire|conduit|frame|_sign|banner|bed$|skull|head$/

const ArrayList = Java.loadClass('java.util.ArrayList')
let ticksServeur = 0
const presents = Object.create(null) // nom du joueur -> dernier tick de présence
let erreursServeur = []

function noterErreurServeur(ou, e) {
  const msg = (ou + ' : ' + e).slice(0, 256)
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

function dansLimite(texte) {
  const n = longueur(texte)
  if (n > TAILLE_MAX) return false
  if (3 * n <= TAILLE_MAX) return true
  let octets = 0
  for (let i = 0; i < n; i++) {
    let code = texte.charCodeAt(i)
    octets += code >= 1 && code <= 127 ? 1 : code <= 2047 ? 2 : 3
    if (octets > TAILLE_MAX) return false
  }
  return true
}


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

function positionsTechniques(source, cx, cz) {
  let chunk = null
  if (typeof source.getChunkNow == 'function') chunk = source.getChunkNow(cx, cz)
  else if (typeof source.m_7131_ == 'function') chunk = source.m_7131_(cx, cz)
  if (chunk == null) return null
  const positions = premiere('blocs du chunk', [
    () => chunk.getBlockEntitiesPos(),
    () => chunk.m_5928_(),
    () => chunk.getBlockEntities().keySet(),
    () => chunk.m_62954_().keySet()
  ])
  return positions == null ? null : liste(positions)
}

function lireInventaireBloc(bloc, couverture) {
  const inv = bloc.inventory
  if (inv == null) return null
  const cases = Number(inv.getSlots())
  if (!Number.isSafeInteger(cases) || cases < 0 || cases > CASES_MAX) {
    couverture.complet = false
    return null
  }
  if (cases === 0) return null
  const total = Object.create(null)
  for (let i = 0; i < cases; i++) {
    let st = inv.getStackInSlot(i)
    if (st == null || st.isEmpty()) continue
    let id = str(st.id)
    let count = Number(st.getCount())
    if (!id || !Number.isSafeInteger(count) || count <= 0) {
      couverture.complet = false
      return null
    }
    total[id] = (total[id] || 0) + count
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
    if (raison != null) m.r = str(raison.getString()).slice(0, 256)
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

function decrireBloc(level, pos, couverture) {
  const bloc = level.getBlock(pos)
  const id = str(bloc.id)
  if (IGNORES.test(id)) return null
  const info = { p: [Number(bloc.x), Number(bloc.y), Number(bloc.z)], b: id }
  let mm = null
  try { mm = bloc.entity.metaMachine } catch (e) { /* pas une machine GregTech */ }
  if (mm != null) {
    try { info.m = etatMachine(mm) } catch (e) {
      couverture.complet = false
      noterErreurServeur('machine ' + id, e)
    }
  }
  try {
    let objets = lireInventaireBloc(bloc, couverture)
    if (objets != null) info.o = objets // Un inventaire vide est une observation positive.
  } catch (e) {
    couverture.complet = false
    noterErreurServeur('inventaire ' + id, e)
  }
  if (info.m == null && info.o == null) return null
  return info
}

// ---------- Envoi au joueur ----------

function scannerPour(p) {
  const nom = str(p.getName().getString())
  if (presents[nom] == null || ticksServeur - presents[nom] > PRESENCE_MAX) return
  const level = p.level
  const source = premiere('source de chunks', [() => level.getChunkSource(), () => level.m_7726_()])
  if (source == null) return
  const px = p.getX(), pz = p.getZ()
  const cx = Math.floor(px / 16), cz = Math.floor(pz / 16)
  let blocs = []
  const couverts = {}
  for (let dx = -RAYON_CHUNKS; dx <= RAYON_CHUNKS; dx++) {
    for (let dz = -RAYON_CHUNKS; dz <= RAYON_CHUNKS; dz++) {
      let x = cx + dx, z = cz + dz
      let positions
      try { positions = positionsTechniques(source, x, z) } catch (e) {
        noterErreurServeur('chunk', e)
        continue
      }
      if (positions == null) continue
      let couverture = { complet: true }
      for (let i = 0; i < positions.length; i++) {
        try {
          let info = decrireBloc(level, positions[i], couverture)
          if (info == null) continue
          if (!info.p.every(Number.isSafeInteger)
              || Math.floor(info.p[0] / 16) !== x || Math.floor(info.p[2] / 16) !== z) {
            couverture.complet = false
            continue
          }
          blocs.push(info)
        } catch (e) {
          couverture.complet = false
          noterErreurServeur('bloc', e)
        }
      }
      if (couverture.complet) couverts[x + ',' + z] = [x, z]
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
      blocs: blocs, joueurs: joueurs, erreurs: erreursServeur,
      chunks_complets: Object.keys(couverts).map(k => couverts[k])
    })
    if (dansLimite(json)) break
    if (blocs.length === 0) {
      if (joueurs.length === 0) return
      joueurs.length = Math.floor(joueurs.length / 2)
      continue
    }
    let garde = Math.floor(blocs.length / 2)
    for (let i = garde; i < blocs.length; i++) {
      let position = blocs[i].p
      delete couverts[Math.floor(position[0] / 16) + ',' + Math.floor(position[2] / 16)]
    }
    blocs = blocs.slice(0, garde)
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
  presents[str(event.player.getName().getString())] = ticksServeur
}))

ServerEvents.tick(function (event) {
  ticksServeur++
  if (ticksServeur % INTERVALLE != 0) return
  const noms = Object.keys(presents)
  for (let i = 0; i < noms.length; i++)
    if (ticksServeur - presents[noms[i]] > PRESENCE_MAX) delete presents[noms[i]]
  liste(event.server.players).forEach(p => essayerServeur('scan', () => scannerPour(p)))
})
