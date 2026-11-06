// Assistant Monifactory, côté serveur : regarde autour de chaque joueur qui a l'assistant (coffres, machines
// GregTech, boilers, multiblocs) et répartit les relevés sur les ticks du serveur.
// LECTURE SEULE : ce script ne modifie rien dans le monde.
// À déposer dans kubejs/server_scripts/ sur le serveur, puis /reload (ou redémarrer le serveur).
//
// Pièges de ce Rhino (KubeJS 6), comme dans le script client :
// - les listes internes de Java refusent les appels : liste() les copie dans une ArrayList ;
// - pas de let/const dans un bloc à l'intérieur d'une fonction fléchée : utiliser des fonctions normales.

const VERSION_SERVEUR = 4
const RAYON_CHUNKS = 2            // 5 x 5 chunks autour du joueur (80 blocs de côté)
const INTERVALLE = 60             // intervalle minimal par joueur, en ticks
const PRESENCE_MAX = 600         // ticks sans signe de vie du client
const TAILLE_PARTIE = 20000       // 3 octets max par unité UTF-16 dans une chaîne NBT
const TAILLE_MAX = 600000         // plafond en octets pour le contenu UTF modifié du relevé
const CASES_MAX = 200             // cases lues au plus par inventaire
const IGNORES = /pipe|cable|wire|conduit|frame|_sign|banner|bed$|skull|head$/

// Ces capacités exposent un stock personnel ou le stock d'autres blocs à distance.
const STOCKS_LIES = /^(?:lootr:|enderchests:|minecraft:ender_chest$|sophisticatedstorage:(?:controller$|.*storage_connector$)|functionalstorage:(?:framed_)?(?:storage_controller|controller_extension)$)/
const COFFRES_DOUBLES = /^(?:minecraft:(?:trapped_)?chest|sophisticatedstorage:(?:.*_)?chest)$/
const ArrayList = Java.loadClass('java.util.ArrayList')
let ticksServeur = 0
let prochainEnvoi = 0
const presents = Object.create(null) // nom du joueur -> dernier signal et échéance de scan
const erreursServeur = []
let ftbChunks = null

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

// ---------- Droits de lecture ----------

function autorisationScan(joueur, level) {
  try {
    if (!Platform.isLoaded('ftbchunks')) return () => true
    if (ftbChunks == null) {
      ftbChunks = {
        api: Java.loadClass('dev.ftb.mods.ftbchunks.api.FTBChunksAPI'),
        config: Java.loadClass('dev.ftb.mods.ftbchunks.FTBChunksWorldConfig'),
        interaction: Java.loadClass('dev.ftb.mods.ftbchunks.api.Protection').INTERACT_BLOCK,
        main: Java.loadClass('net.minecraft.world.InteractionHand').MAIN_HAND,
        ChunkDimPos: Java.loadClass('dev.ftb.mods.ftblibrary.math.ChunkDimPos')
      }
    }
    let api = ftbChunks.api.api()
    if (!api.isManagerLoaded()) return () => false
    let manager = api.getManager()
    if (ftbChunks.config.DISABLE_PROTECTION.get() == true) return () => true
    let zoneNonRevendiqueeProtegee = ftbChunks.config.noWilderness(joueur)
    return function (pos) {
      try {
        // FTB affiche un message en zone non revendiquée protégée. Le scan y reste silencieux.
        if (zoneNonRevendiqueeProtegee && manager.getChunk(new ftbChunks.ChunkDimPos(level, pos)) == null) {
          return false
        }
        return manager.shouldPreventInteraction(joueur, ftbChunks.main, pos, ftbChunks.interaction, null) === false
      } catch (e) {
        noterErreurServeur('droits FTB Chunks', e)
        return false
      }
    }
  } catch (e) {
    noterErreurServeur('droits FTB Chunks', e)
    return () => false
  }
}

function verrouilleOuButin(bloc) {
  const nbt = bloc.entityData
  if (nbt == null) return true
  const verrou = typeof nbt.getString == 'function' ? nbt.getString('Lock') : nbt.m_128461_('Lock')
  const butin = typeof nbt.contains == 'function' ? nbt.contains('LootTable') : nbt.m_128441_('LootTable')
  return str(verrou) !== '' || butin == true
}

function stockageLisible(bloc, id, level, autorise) {
  if (STOCKS_LIES.test(id)) return false
  const vanilla = id.startsWith('minecraft:')
  if (vanilla && verrouilleOuButin(bloc)) return false
  if (!COFFRES_DOUBLES.test(id)) return true

  const proprietes = bloc.properties
  const type = str(proprietes.get('type'))
  if (type === 'single') return true
  if (type !== 'left' && type !== 'right') return false
  const directions = ['north', 'east', 'south', 'west']
  const facing = directions.indexOf(str(proprietes.get('facing')))
  if (facing < 0) return false
  const autre = bloc[directions[(facing + (type === 'left' ? 1 : 3)) % 4]]
  const pos = autre.pos
  const charge = typeof level.isLoaded == 'function' ? level.isLoaded(pos) : level.m_46749_(pos)
  return charge == true && autorise(pos) && str(autre.id) === id
    && (!vanilla || !verrouilleOuButin(autre))
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

function decrireBloc(level, pos, couverture, autorise) {
  if (!autorise(pos)) return null
  const bloc = level.getBlock(pos)
  const id = str(bloc.id)
  if (IGNORES.test(id) || !stockageLisible(bloc, id, level, autorise)) return null
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

function scannerPour(p, connectes) {
  const nom = str(p.getName().getString())
  const level = p.level
  const source = premiere('source de chunks', [() => level.getChunkSource(), () => level.m_7726_()])
  if (source == null) return
  const px = p.getX(), pz = p.getZ()
  const cx = Math.floor(px / 16), cz = Math.floor(pz / 16)
  const autorise = autorisationScan(p, level)
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
          let info = decrireBloc(level, positions[i], couverture, autorise)
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
  connectes.forEach(autre => {
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

// Un nouveau signal prolonge la présence et conserve l'échéance du prochain relevé.
NetworkEvents.dataReceived('assistant_ia_present', function (event) {
  essayerServeur('présence', function () {
    const nom = str(event.player.getName().getString())
    let presence = presents[nom]
    if (presence == null) {
      presence = { prochain: ticksServeur + 1 }
      presents[nom] = presence
    }
    presence.signal = ticksServeur
  })
})

ServerEvents.tick(function (event) {
  ticksServeur++
  if (ticksServeur < prochainEnvoi) return

  let suivant = null
  let echeance = ticksServeur + 1
  let actifs = 0
  const noms = Object.keys(presents)
  for (let i = 0; i < noms.length; i++) {
    let nom = noms[i]
    let presence = presents[nom]
    if (ticksServeur - presence.signal > PRESENCE_MAX) {
      delete presents[nom]
      continue
    }
    actifs++
    if (presence.prochain < echeance) {
      suivant = nom
      echeance = presence.prochain
    }
  }
  if (suivant == null) return

  essayerServeur('scan', function () {
    const joueurs = liste(event.server.players)
    const joueur = joueurs.find(p => str(p.getName().getString()) === suivant)
    if (joueur == null) {
      delete presents[suivant]
      return
    }
    // Servir l'échéance la plus ancienne, avec au plus un scan par tick même en surcharge.
    presents[suivant].prochain = ticksServeur + INTERVALLE
    prochainEnvoi = ticksServeur + Math.max(1, Math.floor(INTERVALLE / actifs))
    scannerPour(joueur, joueurs)
  })
})
