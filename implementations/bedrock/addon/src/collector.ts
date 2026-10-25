import {
  world, system, Player, CommandPermissionLevel, CustomCommandParamType, CustomCommandStatus, CustomCommandOrigin
} from '@minecraft/server'

/** JSON messages understood by BlueMap (implementations/bedrock PlayerTracker). */
export type Message =
  | { t: 'hello', v: string }
  | { t: 'players', p: PlayerInfo[] }
  | { t: 'join' | 'leave', n: string, id: string }
  | { t: 'cmd', n: string, a: string }

interface PlayerInfo {
  n: string, id: string, x: number, y: number, z: number, ry: number, rx: number, d: string,
  gm: string, sn: boolean, inv: boolean, sl?: number, bl?: number
}

export type Send = (msg: Message) => void

const VERSION = '1.0.0'
const INTERVAL_TICKS = 20
const IDLE_REPORT_TICKS = 600

const r2 = (v: number): number => Math.round(v * 100) / 100

function info (pl: Player): PlayerInfo | null {
  try {
    if (!pl.isValid) return null
    const l = pl.location
    const r = pl.getRotation()
    const p: PlayerInfo = {
      n: pl.name, id: pl.id, x: r2(l.x), y: r2(l.y), z: r2(l.z), ry: r2(r.y), rx: r2(r.x), d: pl.dimension.id,
      gm: String(pl.getGameMode()), sn: pl.isSneaking, inv: pl.getEffect('invisibility') !== undefined
    }
    try {
      // Light at head height: BlueMap can hide players in the dark (hide-below-sky-light...).
      const head = { x: l.x, y: l.y + 1.6, z: l.z }
      p.sl = pl.dimension.getSkyLightLevel(head)
      p.bl = pl.dimension.getLightLevel(head)
    } catch { /* unloaded or out of range */ }
    return p
  } catch {
    return null
  }
}

export function startCollector (send: Send): void {
  system.run(() => send({ t: 'hello', v: VERSION }))

  let lastCount = -1
  let idle = 0
  system.runInterval(() => {
    const players = world.getAllPlayers()
    idle += INTERVAL_TICKS
    if (players.length === 0 && lastCount === 0 && idle < IDLE_REPORT_TICKS) return
    idle = 0
    lastCount = players.length
    const p: PlayerInfo[] = []
    for (const pl of players) { const i = info(pl); if (i !== null) p.push(i) }
    send({ t: 'players', p })
  }, INTERVAL_TICKS)

  world.afterEvents.playerSpawn.subscribe(ev => {
    if (ev.initialSpawn) send({ t: 'join', n: ev.player.name, id: ev.player.id })
  })
  world.beforeEvents.playerLeave.subscribe(ev => {
    const n = ev.player.name; const id = ev.player.id
    system.run(() => send({ t: 'leave', n, id }))
  })
}

/**
 * /bluemap [args...] for operators: forwarded to BlueMap, which answers with tellraw.
 * Each word is one optional parameter (custom commands have no "rest of line" parameter type).
 */
export function registerCommand (send: Send): void {
  system.beforeEvents.startup.subscribe(ev => {
    const optionalParameters = Array.from({ length: 6 }, (_, i) => ({ name: `arg${i + 1}`, type: CustomCommandParamType.String }))
    ev.customCommandRegistry.registerCommand({
      name: 'bluemap:bluemap',
      description: 'BlueMap commands (render, status, help...)',
      permissionLevel: CommandPermissionLevel.GameDirectors,
      optionalParameters
    }, (origin: CustomCommandOrigin, ...args: unknown[]) => {
      const player = origin.sourceEntity
      if (!(player instanceof Player)) return { status: CustomCommandStatus.Failure, message: 'BlueMap commands must be run by a player (use the server console otherwise).' }
      const a = args.filter(x => typeof x === 'string' && x !== '').join(' ')
      const n = player.name
      system.run(() => send({ t: 'cmd', n, a }))
      return { status: CustomCommandStatus.Success }
    })
  })
}
