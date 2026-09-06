import { useEffect, useRef, useState } from 'react'
import { Application, Container, Graphics, Particle, ParticleContainer, Texture } from 'pixi.js'

const TILE_SIZE = 8
const MAP_URL = 'http://localhost:8080/world/map'
const STATE_URL = 'http://localhost:8080/world/state'
const LIVE_URL = 'ws://localhost:8080/world/live'
const META_INTERVAL_MS = 2000
const MAP_REFRESH_MS = 8000
const INVENTORY_CAPACITY = 10
const AGENT_HIT_RADIUS = 0.55
const PATH_HIT_RADIUS = 0.45
const NPC_TEX_SIZE = 32
const LOD_FAR_SCALE = 0.8
const LOD_FAR_SCREEN_PX = 3

const TRADER_COLOR = 0x7e57c2
const STONE_COLOR = 0x26c6da

const RESOURCE_COLORS: Record<string, number> = {
  WOOD: 0x2e7d32,
  GOLD: 0xc9a227,
  FOOD: 0xd35400,
  STONE: STONE_COLOR,
  IRON: 0x8d6e63,
  HERBS: 0x43a047,
}

const TERRAIN_COLORS: Record<string, number> = {
  mountain: 0x444444,
  city: 0x5c6bc0,
  forest: 0x1b5e20,
  farm: 0xa1887f,
  mine: 0x6d4c41,
  quarry: STONE_COLOR,
  vein: 0x5d4037,
  meadow: 0x7cb342,
}

const TERRAIN_LABELS: Record<string, string> = {
  grass: 'Grass',
  mountain: 'Mountain (impassable)',
  city: 'City',
  forest: 'Forest',
  farm: 'Farm',
  mine: 'Mine',
  quarry: 'Quarry',
  vein: 'Iron vein',
  meadow: 'Meadow',
}

const JOB_RESOURCE: Record<string, string> = {
  LUMBERJACK: 'WOOD',
  MINER: 'GOLD',
  FARMER: 'FOOD',
  STONECUTTER: 'STONE',
  PROSPECTOR: 'IRON',
  HERBALIST: 'HERBS',
}

interface Coord {
  x: number
  y: number
}

interface Tile {
  id: number
  x: number
  y: number
  terrainType: string
  location: Coord
  resourceType: string | null
  quantity: number
  cityId?: number | null
}

interface Agent {
  id: number
  name: string
  location: Coord
  speed: number
  targetLocation: Coord | null
  path: Coord[]
  job: string | null
  tradeResource?: string | null
  inventory: Record<string, number>
}

interface MapState {
  width: number
  height: number
  tiles: Tile[]
}

interface MarketListing {
  resource: string
  stock: number
  price: number
}

interface CityMarket {
  id: number
  name: string
  x: number
  y: number
  listings: MarketListing[]
}

interface WorldState {
  agents: Agent[]
  tick?: TickStats
  cities?: CityMarket[]
}

interface AgentPos {
  id: number
  x: number
  y: number
}

interface LiveFrame {
  agents: AgentPos[]
  tick?: TickStats
}

interface TickStats {
  moveMs: number
  replanMs: number
  agents: number
  walking?: number
  harvesting?: number
  buying?: number
  depositing?: number
  needRoute?: number
  moved: number
  harvested: number
  delivered: number
  bought?: number
  routed?: number
  replanned?: number
  waitingForPath?: number
  physicsSql?: { name: string; ms: number; rows: number }[]
  aiSql?: { name: string; ms: number; rows: number }[]
}

interface ViewportProps {
  showPaths: boolean
  onConnectionChange?: (connected: boolean) => void
  onTickStats?: (stats: TickStats | null) => void
  onFps?: (fps: number) => void
}

interface AgentSprite {
  particle: Particle
  target: Coord
  current: Coord
  color: number
}

interface TooltipState {
  x: number
  y: number
  lines: string[]
}

function agentColor(job: string | null | undefined): number {
  if (job === 'TRADER') {
    return TRADER_COLOR
  }
  const resource = job ? JOB_RESOURCE[job] : null
  if (resource) {
    return RESOURCE_COLORS[resource] ?? 0x3366ff
  }
  return 0x3366ff
}

function npcLodScale(camScale: number, textureWidth: number): number {
  const worldSize = camScale < LOD_FAR_SCALE ? LOD_FAR_SCREEN_PX / camScale : TILE_SIZE * 0.8
  return worldSize / textureWidth
}

function createNpcTexture(app: Application): Texture {
  const gfx = new Graphics()
  const r = NPC_TEX_SIZE / 2
  gfx.circle(r, r, r - 2)
  gfx.fill(0xffffff)
  gfx.stroke({ width: 2, color: 0x222222, alpha: 0.55 })
  const texture = app.renderer.generateTexture({ target: gfx, resolution: 2 })
  gfx.destroy()
  return texture
}

function syncAgentColors(
  agents: Agent[],
  sprites: Map<number, AgentSprite>,
  layer: ParticleContainer | null,
) {
  let changed = false
  for (const agent of agents) {
    const entry = sprites.get(agent.id)
    if (!entry) {
      continue
    }
    const color = agentColor(agent.job)
    if (entry.color !== color) {
      entry.particle.tint = color
      entry.color = color
      changed = true
    }
  }
  if (changed) {
    layer?.update()
  }
}

function tileColor(tile: Tile): number {
  if (tile.terrainType === 'quarry' || tile.resourceType === 'STONE') {
    return STONE_COLOR
  }
  if (tile.resourceType && tile.quantity > 0) {
    return RESOURCE_COLORS[tile.resourceType] ?? TERRAIN_COLORS[tile.terrainType] ?? 0x888888
  }
  return TERRAIN_COLORS[tile.terrainType] ?? 0x888888
}

function inventoryCount(agent: Agent): number {
  return Object.values(agent.inventory ?? {}).reduce((sum, n) => sum + n, 0)
}

function formatInventory(agent: Agent): string {
  const entries = Object.entries(agent.inventory ?? {}).filter(([, n]) => n > 0)
  if (entries.length === 0) {
    return 'empty'
  }
  return entries.map(([type, n]) => `${type} ${n}`).join(', ')
}

function tileAt(tiles: Tile[], gridX: number, gridY: number): Tile | undefined {
  return tiles.find((tile) => tile.x === gridX && tile.y === gridY)
}

function describeAgentActivity(agent: Agent, tiles: Tile[], displayPos: Coord): string {
  const underfoot = tileAt(tiles, Math.floor(displayPos.x), Math.floor(displayPos.y))
  const jobResource = agent.job ? JOB_RESOURCE[agent.job] : null
  const count = inventoryCount(agent)
  const full = count >= INVENTORY_CAPACITY
  const targetTile = agent.targetLocation
    ? tileAt(tiles, Math.floor(agent.targetLocation.x), Math.floor(agent.targetLocation.y))
    : undefined

  if (agent.job === 'TRADER') {
    const good = agent.tradeResource
    if (underfoot?.terrainType === 'city' && count > 0 && full) {
      return good ? `Selling ${good}` : 'Selling at city'
    }
    if (underfoot?.terrainType === 'city' && !full && good) {
      return `Buying ${good}`
    }
    if (count > 0) {
      return good ? `Hauling ${good} to a high-price city` : 'Hauling goods to a high-price city'
    }
    if (good) {
      return `Traveling to buy ${good}`
    }
    return 'Seeking a price gap'
  }

  if (
    underfoot?.resourceType &&
    underfoot.quantity > 0 &&
    underfoot.resourceType === jobResource &&
    !full
  ) {
    return `Harvesting ${underfoot.resourceType}`
  }
  if (underfoot?.terrainType === 'city' && count > 0) {
    return 'Selling at city'
  }
  if (full || targetTile?.terrainType === 'city') {
    return 'Delivering to city'
  }
  if (targetTile?.resourceType) {
    return `Traveling to gather ${targetTile.resourceType}`
  }
  if (jobResource) {
    return `Seeking ${jobResource}`
  }
  return 'Idle'
}

function dist(a: Coord, b: Coord): number {
  return Math.hypot(a.x - b.x, a.y - b.y)
}

function distToSegment(point: Coord, a: Coord, b: Coord): number {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const len2 = dx * dx + dy * dy
  if (len2 < 1e-12) {
    return dist(point, a)
  }
  const t = Math.max(0, Math.min(1, ((point.x - a.x) * dx + (point.y - a.y) * dy) / len2))
  return Math.hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy))
}

function distToPolyline(point: Coord, points: Coord[]): number {
  if (points.length === 0) {
    return Number.POSITIVE_INFINITY
  }
  if (points.length === 1) {
    return dist(point, points[0]!)
  }
  let best = Number.POSITIVE_INFINITY
  for (let i = 1; i < points.length; i++) {
    best = Math.min(best, distToSegment(point, points[i - 1]!, points[i]!))
  }
  return best
}

function samePoint(a: Coord, b: Coord): boolean {
  return Math.abs(a.x - b.x) < 0.05 && Math.abs(a.y - b.y) < 0.05
}

/** Horizontal or vertical — the routing graph has no diagonal edges. */
function axisAligned(a: Coord, b: Coord): boolean {
  return Math.abs(a.x - b.x) < 0.05 || Math.abs(a.y - b.y) < 0.05
}

/** Path leftover from the NPC's live position, skipping tiles they already walked. */
function remainingPath(from: Coord, path: Coord[]): Coord[] {
  if (path.length === 0) {
    return []
  }
  let i = 0
  while (i + 1 < path.length && dist(from, path[i + 1]!) <= dist(from, path[i]!) + 0.001) {
    i += 1
  }
  while (i < path.length && samePoint(from, path[i]!)) {
    i += 1
  }
  const points: Coord[] = []
  if (i < path.length && axisAligned(from, path[i]!) && !samePoint(from, path[i]!)) {
    points.push(from)
  }
  for (; i < path.length; i++) {
    const point = path[i]!
    const last = points[points.length - 1]
    if (last && samePoint(last, point)) {
      continue
    }
    if (last && !axisAligned(last, point)) {
      // Stale leftover jumped a corner — drop the shortcut instead of drawing it.
      points.length = 0
    }
    points.push(point)
  }
  return points
}

function cityForTile(tile: Tile, cities: CityMarket[]): CityMarket | undefined {
  if (tile.cityId != null) {
    return cities.find((city) => city.id === tile.cityId)
  }
  return cities.find((city) => Math.abs(city.x - tile.x) <= 2 && Math.abs(city.y - tile.y) <= 2)
}

function describeTile(tile: Tile, cities: CityMarket[] = []): string[] {
  const lines = [`Tile (${tile.x}, ${tile.y})`]
  if (tile.terrainType === 'city') {
    const city = cityForTile(tile, cities)
    lines.push(city ? city.name : 'City')
    if (city) {
      for (const listing of city.listings) {
        lines.push(`${listing.resource}  ${listing.stock} @ ${listing.price}`)
      }
    }
    return lines
  }
  lines.push(TERRAIN_LABELS[tile.terrainType] ?? tile.terrainType)
  if (tile.resourceType) {
    if (tile.quantity > 0) {
      lines.push(`Resource: ${tile.resourceType}`)
      lines.push(`Quantity: ${tile.quantity}`)
    } else {
      lines.push(`Depleted: ${tile.resourceType}`)
    }
  }
  return lines
}

function describeAgent(agent: Agent, tiles: Tile[], displayPos: Coord): string[] {
  return [
    `Agent: ${agent.name}`,
    `Job: ${agent.job ?? 'none'}`,
    `Activity: ${describeAgentActivity(agent, tiles, displayPos)}`,
    `Inventory: ${formatInventory(agent)} (${inventoryCount(agent)}/${INVENTORY_CAPACITY})`,
  ]
}

export default function Viewport({
  showPaths,
  onConnectionChange,
  onTickStats,
  onFps,
}: ViewportProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const appRef = useRef<Application | null>(null)
  const worldRef = useRef<Container | null>(null)
  const tilesLayerRef = useRef<Container | null>(null)
  const pathsLayerRef = useRef<Container | null>(null)
  const destLayerRef = useRef<Container | null>(null)
  const pathGfxRef = useRef<Graphics | null>(null)
  const destGfxRef = useRef<Graphics | null>(null)
  const agentsLayerRef = useRef<ParticleContainer | null>(null)
  const npcTextureRef = useRef<Texture | null>(null)
  const lastCamScaleRef = useRef(0)
  const tileGfxRef = useRef<Map<number, Graphics>>(new Map())
  const tileFillRef = useRef<Map<number, number>>(new Map())
  const lastMapFetchRef = useRef(0)
  const fpsFramesRef = useRef(0)
  const fpsStampRef = useRef(0)
  const agentSpritesRef = useRef<Map<number, AgentSprite>>(new Map())
  const mapLoadedRef = useRef(false)
  const showPathsRef = useRef(showPaths)
  const lastAgentsRef = useRef<Agent[]>([])
  const lastTilesRef = useRef<Tile[]>([])
  const lastCitiesRef = useRef<CityMarket[]>([])
  const mapSizeRef = useRef({ width: 100, height: 100 })
  const onConnectionChangeRef = useRef(onConnectionChange)
  const onTickStatsRef = useRef(onTickStats)
  const onFpsRef = useRef(onFps)
  const updatePathsRef = useRef<(agents: Agent[]) => void>(() => {})
  const cameraRef = useRef({ x: 0, y: 0, scale: 1 })
  const dragRef = useRef<{ active: boolean; lastX: number; lastY: number }>({
    active: false,
    lastX: 0,
    lastY: 0,
  })
  const [tooltip, setTooltip] = useState<TooltipState | null>(null)
  const [hint] = useState('Drag to pan · Scroll to zoom')

  showPathsRef.current = showPaths
  onConnectionChangeRef.current = onConnectionChange
  onTickStatsRef.current = onTickStats
  onFpsRef.current = onFps

  const applyCamera = () => {
    const world = worldRef.current
    if (!world) return
    const cam = cameraRef.current
    world.scale.set(cam.scale)
    world.position.set(cam.x, cam.y)
    if (cam.scale !== lastCamScaleRef.current) {
      lastCamScaleRef.current = cam.scale
      applyNpcLod()
    }
  }

  const applyNpcLod = () => {
    const texture = npcTextureRef.current
    const layer = agentsLayerRef.current
    if (!texture || !layer) return
    const scale = npcLodScale(cameraRef.current.scale, texture.width)
    for (const sprite of agentSpritesRef.current.values()) {
      sprite.particle.scaleX = scale
      sprite.particle.scaleY = scale
    }
    layer.update()
  }

  const updatePaths = (agents: Agent[]) => {
    const pathGfx = pathGfxRef.current
    const destGfx = destGfxRef.current
    if (!pathGfx || !destGfx) return

    pathGfx.clear()
    destGfx.clear()
    if (!showPathsRef.current) return

    for (const agent of agents) {
      const color = agentColor(agent.job)
      const sprite = agentSpritesRef.current.get(agent.id)
      // Use the live tile, not the interpolating sprite — otherwise the trail
      // sits on the previous tile after each 1s step.
      const from = sprite?.target ?? agent.location
      const points = remainingPath(from, agent.path ?? [])
      if (points.length >= 2) {
        pathGfx.moveTo(points[0]!.x * TILE_SIZE, points[0]!.y * TILE_SIZE)
        for (let i = 1; i < points.length; i++) {
          const prev = points[i - 1]!
          const point = points[i]!
          const x = point.x * TILE_SIZE
          const y = point.y * TILE_SIZE
          if (axisAligned(prev, point)) {
            pathGfx.lineTo(x, y)
          } else {
            pathGfx.moveTo(x, y)
          }
        }
        pathGfx.stroke({ width: 2, color, alpha: 0.9 })
      }

      if (agent.targetLocation) {
        const tx = agent.targetLocation.x * TILE_SIZE
        const ty = agent.targetLocation.y * TILE_SIZE
        const size = TILE_SIZE * 0.4
        destGfx.rect(tx - size / 2, ty - size / 2, size, size)
        destGfx.fill({ color, alpha: 0.95 })
      }
    }
  }

  updatePathsRef.current = updatePaths

  const drawMap = (map: MapState) => {
    const tilesLayer = tilesLayerRef.current
    const world = worldRef.current
    if (!tilesLayer || !world) return

    tilesLayer.removeChildren()
    tileGfxRef.current.clear()
    tileFillRef.current.clear()

    const ground = new Graphics()
    ground.rect(0, 0, map.width * TILE_SIZE, map.height * TILE_SIZE)
    ground.fill(0x888888)
    tilesLayer.addChild(ground)

    for (const tile of map.tiles) {
      const color = tileColor(tile)
      const gfx = new Graphics()
      gfx.rect(0, 0, TILE_SIZE, TILE_SIZE)
      gfx.fill(color)
      gfx.x = tile.x * TILE_SIZE
      gfx.y = tile.y * TILE_SIZE
      tileGfxRef.current.set(tile.id, gfx)
      tileFillRef.current.set(tile.id, color)
      tilesLayer.addChild(gfx)
    }

    mapSizeRef.current = { width: map.width, height: map.height }
    lastTilesRef.current = map.tiles
    mapLoadedRef.current = true
    lastMapFetchRef.current = performance.now()

    // Fit map roughly into view on first load.
    const app = appRef.current
    if (app) {
      const fit = Math.min(
        app.screen.width / (map.width * TILE_SIZE),
        app.screen.height / (map.height * TILE_SIZE),
      )
      cameraRef.current.scale = Math.max(0.35, Math.min(fit * 0.95, 1.5))
      cameraRef.current.x =
        (app.screen.width - map.width * TILE_SIZE * cameraRef.current.scale) / 2
      cameraRef.current.y =
        (app.screen.height - map.height * TILE_SIZE * cameraRef.current.scale) / 2
      applyCamera()
    }
  }

  const updateResourceTiles = (tiles: Tile[]) => {
    const known = new Set<number>()
    for (const tile of tiles) {
      known.add(tile.id)
      const color = tileColor(tile)
      let gfx = tileGfxRef.current.get(tile.id)
      if (!gfx) {
        gfx = new Graphics()
        gfx.x = tile.x * TILE_SIZE
        gfx.y = tile.y * TILE_SIZE
        tileGfxRef.current.set(tile.id, gfx)
        tilesLayerRef.current?.addChild(gfx)
      } else if (tileFillRef.current.get(tile.id) === color) {
        continue
      }
      gfx.clear()
      gfx.rect(0, 0, TILE_SIZE, TILE_SIZE)
      gfx.fill(color)
      tileFillRef.current.set(tile.id, color)
    }

    // Remove graphics for depleted resources that fell off the special-tile list.
    for (const [id, gfx] of tileGfxRef.current) {
      if (!known.has(id)) {
        tilesLayerRef.current?.removeChild(gfx)
        gfx.destroy()
        tileGfxRef.current.delete(id)
        tileFillRef.current.delete(id)
      }
    }
  }

  useEffect(() => {
    let destroyed = false
    let initialized = false
    const app = new Application()

    void (async () => {
      if (!containerRef.current || destroyed) return

      await app.init({
        resizeTo: containerRef.current,
        background: '#1a1a2e',
        antialias: false,
      })

      if (destroyed) {
        app.destroy({ removeView: true }, true)
        return
      }

      initialized = true
      containerRef.current.appendChild(app.canvas)
      appRef.current = app

      const world = new Container()
      const tilesLayer = new Container()
      const pathsLayer = new Container()
      const destLayer = new Container()
      const npcTexture = createNpcTexture(app)
      const agentsLayer = new ParticleContainer({
        texture: npcTexture,
        dynamicProperties: {
          position: true,
          rotation: false,
          color: false,
          uvs: false,
          vertex: false,
        },
      })
      const pathGfx = new Graphics()
      const destGfx = new Graphics()
      pathsLayer.addChild(pathGfx)
      destLayer.addChild(destGfx)
      world.addChild(tilesLayer)
      world.addChild(pathsLayer)
      world.addChild(destLayer)
      world.addChild(agentsLayer)
      app.stage.addChild(world)

      worldRef.current = world
      tilesLayerRef.current = tilesLayer
      pathsLayerRef.current = pathsLayer
      destLayerRef.current = destLayer
      pathGfxRef.current = pathGfx
      destGfxRef.current = destGfx
      agentsLayerRef.current = agentsLayer
      npcTextureRef.current = npcTexture

      app.ticker.add(() => {
        fpsFramesRef.current += 1
        const now = performance.now()
        if (fpsStampRef.current === 0) {
          fpsStampRef.current = now
        } else if (now - fpsStampRef.current >= 1000) {
          onFpsRef.current?.(
            Math.round((fpsFramesRef.current * 1000) / (now - fpsStampRef.current)),
          )
          fpsFramesRef.current = 0
          fpsStampRef.current = now
        }

        for (const sprite of agentSpritesRef.current.values()) {
          const dx = sprite.target.x - sprite.current.x
          const dy = sprite.target.y - sprite.current.y
          if (Math.abs(dx) <= 0.0005 && Math.abs(dy) <= 0.0005) {
            continue
          }
          sprite.current.x += dx * 0.25
          sprite.current.y += dy * 0.25
          sprite.particle.x = sprite.current.x * TILE_SIZE
          sprite.particle.y = sprite.current.y * TILE_SIZE
        }
      })
    })()

    return () => {
      destroyed = true
      if (initialized) {
        app.destroy({ removeView: true }, true)
      }
      appRef.current = null
      worldRef.current = null
      tilesLayerRef.current = null
      pathsLayerRef.current = null
      destLayerRef.current = null
      pathGfxRef.current = null
      destGfxRef.current = null
      agentsLayerRef.current = null
      npcTextureRef.current = null
      tileGfxRef.current.clear()
      tileFillRef.current.clear()
      agentSpritesRef.current.clear()
      mapLoadedRef.current = false
    }
  }, [])

  useEffect(() => {
    const loadMap = async () => {
      try {
        const res = await fetch(MAP_URL)
        if (!res.ok) {
          return
        }
        const map = (await res.json()) as MapState
        if (appRef.current && tilesLayerRef.current) {
          drawMap(map)
        } else {
          // App may still be initializing — retry shortly.
          setTimeout(() => {
            if (appRef.current && tilesLayerRef.current && !mapLoadedRef.current) {
              drawMap(map)
            }
          }, 200)
        }
      } catch {
        // Map retry is independent of the live socket.
      }
    }

    void loadMap()
  }, [])

  useEffect(() => {
    let closed = false
    let socket: WebSocket | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let attempts = 0

    const applyLiveFrame = (frame: LiveFrame) => {
      if (closed) return
      onTickStatsRef.current?.(frame.tick ?? null)
      const agentsLayer = agentsLayerRef.current
      if (!agentsLayer) {
        window.setTimeout(() => applyLiveFrame(frame), 200)
        return
      }

      const known = new Map(lastAgentsRef.current.map((agent) => [agent.id, agent]))
      const nextAgents: Agent[] = []
      const seen = new Set<number>()

      for (const pos of frame.agents ?? []) {
        seen.add(pos.id)
        const location = { x: pos.x, y: pos.y }
        const previous = known.get(pos.id)
        nextAgents.push(
          previous
            ? { ...previous, location }
            : {
                id: pos.id,
                name: `Agent ${pos.id}`,
                location,
                speed: 1,
                targetLocation: null,
                path: [],
                job: null,
                inventory: {},
              },
        )

        const color = agentColor(previous?.job)
        const texture = npcTextureRef.current
        let entry = agentSpritesRef.current.get(pos.id)
        if (!entry && texture) {
          const scale = npcLodScale(cameraRef.current.scale, texture.width)
          const particle = new Particle({
            texture,
            x: location.x * TILE_SIZE,
            y: location.y * TILE_SIZE,
            anchorX: 0.5,
            anchorY: 0.5,
            scaleX: scale,
            scaleY: scale,
            tint: color,
          })
          entry = { particle, target: location, current: { ...location }, color }
          agentSpritesRef.current.set(pos.id, entry)
          agentsLayer.addParticle(particle)
        } else if (entry) {
          entry.target = location
          if (entry.color !== color) {
            entry.particle.tint = color
            entry.color = color
            agentsLayer.update()
          }
        }
      }

      for (const [id, entry] of agentSpritesRef.current) {
        if (!seen.has(id)) {
          agentsLayer.removeParticle(entry.particle)
          agentSpritesRef.current.delete(id)
        }
      }

      lastAgentsRef.current = nextAgents
      if (showPathsRef.current) {
        updatePathsRef.current(nextAgents)
      }
    }

    const connect = () => {
      if (closed) return
      socket = new WebSocket(LIVE_URL)
      socket.onopen = () => {
        attempts = 0
        onConnectionChangeRef.current?.(true)
      }
      socket.onmessage = (event) => {
        try {
          applyLiveFrame(JSON.parse(String(event.data)) as LiveFrame)
        } catch {
          // ignore malformed frames
        }
      }
      socket.onerror = () => {
        socket?.close()
      }
      socket.onclose = () => {
        onConnectionChangeRef.current?.(false)
        onTickStatsRef.current?.(null)
        if (closed) return
        attempts += 1
        const delay = Math.min(8000, 400 * 2 ** Math.min(attempts, 4))
        reconnectTimer = setTimeout(connect, delay)
      }
    }

    connect()
    return () => {
      closed = true
      if (reconnectTimer) clearTimeout(reconnectTimer)
      socket?.close()
    }
  }, [])

  useEffect(() => {
    const fetchMeta = async () => {
      try {
        const url = showPathsRef.current ? `${STATE_URL}?paths=true` : STATE_URL
        const res = await fetch(url)
        if (!res.ok) return
        const state = (await res.json()) as WorldState

        if (mapLoadedRef.current) {
          const now = performance.now()
          if (now - lastMapFetchRef.current >= MAP_REFRESH_MS) {
            lastMapFetchRef.current = now
            const mapRes = await fetch(MAP_URL)
            if (mapRes.ok) {
              const map = (await mapRes.json()) as MapState
              lastTilesRef.current = map.tiles
              updateResourceTiles(map.tiles)
            }
          }
        }

        const byId = new Map(lastAgentsRef.current.map((agent) => [agent.id, agent]))
        lastAgentsRef.current = state.agents.map((agent) => {
          const live = byId.get(agent.id)
          const sprite = agentSpritesRef.current.get(agent.id)
          return {
            ...agent,
            location: sprite?.target ?? live?.location ?? agent.location,
          }
        })
        lastCitiesRef.current = state.cities ?? []
        syncAgentColors(lastAgentsRef.current, agentSpritesRef.current, agentsLayerRef.current)
        updatePaths(lastAgentsRef.current)
      } catch {
        // Live positions still come from the WebSocket.
      }
    }

    void fetchMeta()
    const interval = setInterval(() => void fetchMeta(), META_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    // When toggling paths, refetch once with the right query.
    void (async () => {
      try {
        const url = showPaths ? `${STATE_URL}?paths=true` : STATE_URL
        const res = await fetch(url)
        if (!res.ok) return
        const state = (await res.json()) as WorldState
        const byId = new Map(lastAgentsRef.current.map((agent) => [agent.id, agent]))
        lastAgentsRef.current = state.agents.map((agent) => {
          const live = byId.get(agent.id)
          const sprite = agentSpritesRef.current.get(agent.id)
          return {
            ...agent,
            location: sprite?.target ?? live?.location ?? agent.location,
          }
        })
        lastCitiesRef.current = state.cities ?? []
        syncAgentColors(lastAgentsRef.current, agentSpritesRef.current, agentsLayerRef.current)
        updatePaths(lastAgentsRef.current)
      } catch {
        // ignore
      }
    })()
  }, [showPaths])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const toWorld = (clientX: number, clientY: number) => {
      const rect = el.getBoundingClientRect()
      const cam = cameraRef.current
      const localX = clientX - rect.left
      const localY = clientY - rect.top
      return {
        x: (localX - cam.x) / (TILE_SIZE * cam.scale),
        y: (localY - cam.y) / (TILE_SIZE * cam.scale),
      }
    }

    const onMove = (event: MouseEvent) => {
      if (dragRef.current.active) {
        const dx = event.clientX - dragRef.current.lastX
        const dy = event.clientY - dragRef.current.lastY
        cameraRef.current.x += dx
        cameraRef.current.y += dy
        dragRef.current.lastX = event.clientX
        dragRef.current.lastY = event.clientY
        applyCamera()
        setTooltip(null)
        return
      }

      const world = toWorld(event.clientX, event.clientY)
      const agents = lastAgentsRef.current
      const tiles = lastTilesRef.current
      if (tiles.length === 0 && agents.length === 0) {
        setTooltip(null)
        return
      }

      let closest: { agent: Agent; pos: Coord; dist: number } | null = null
      for (const agent of agents) {
        const sprite = agentSpritesRef.current.get(agent.id)
        const pos = sprite?.current ?? agent.location
        const spriteDist = dist(world, pos)
        if (spriteDist <= AGENT_HIT_RADIUS && (!closest || spriteDist < closest.dist)) {
          closest = { agent, pos, dist: spriteDist }
        }
      }
      if (!closest && showPathsRef.current) {
        for (const agent of agents) {
          const sprite = agentSpritesRef.current.get(agent.id)
          const pos = sprite?.target ?? agent.location
          const points = remainingPath(pos, agent.path ?? [])
          let pathDist = distToPolyline(world, points)
          if (agent.targetLocation) {
            pathDist = Math.min(pathDist, dist(world, agent.targetLocation))
          }
          if (pathDist <= PATH_HIT_RADIUS && (!closest || pathDist < closest.dist)) {
            closest = { agent, pos, dist: pathDist }
          }
        }
      }

      if (closest) {
        setTooltip({
          x: event.clientX + 14,
          y: event.clientY + 14,
          lines: describeAgent(closest.agent, tiles, closest.pos),
        })
        return
      }

      const tile = tileAt(tiles, Math.floor(world.x), Math.floor(world.y))
      if (!tile) {
        const { width, height } = mapSizeRef.current
        const gx = Math.floor(world.x)
        const gy = Math.floor(world.y)
        if (gx >= 0 && gy >= 0 && gx < width && gy < height) {
          setTooltip({
            x: event.clientX + 14,
            y: event.clientY + 14,
            lines: [`Tile (${gx}, ${gy})`, 'Grass'],
          })
        } else {
          setTooltip(null)
        }
        return
      }

      setTooltip({
        x: event.clientX + 14,
        y: event.clientY + 14,
        lines: describeTile(tile, lastCitiesRef.current),
      })
    }

    const onDown = (event: MouseEvent) => {
      if (event.button !== 0) return
      dragRef.current = { active: true, lastX: event.clientX, lastY: event.clientY }
      el.style.cursor = 'grabbing'
    }

    const onUp = () => {
      dragRef.current.active = false
      el.style.cursor = 'grab'
    }

    const onWheel = (event: WheelEvent) => {
      event.preventDefault()
      const rect = el.getBoundingClientRect()
      const mouseX = event.clientX - rect.left
      const mouseY = event.clientY - rect.top
      const cam = cameraRef.current
      const worldX = (mouseX - cam.x) / cam.scale
      const worldY = (mouseY - cam.y) / cam.scale
      cam.scale = Math.min(4, Math.max(0.25, cam.scale * (event.deltaY < 0 ? 1.1 : 0.9)))
      cam.x = mouseX - worldX * cam.scale
      cam.y = mouseY - worldY * cam.scale
      applyCamera()
    }

    const onLeave = () => {
      dragRef.current.active = false
      setTooltip(null)
      el.style.cursor = 'grab'
    }

    el.style.cursor = 'grab'
    el.addEventListener('mousemove', onMove)
    el.addEventListener('mousedown', onDown)
    window.addEventListener('mouseup', onUp)
    el.addEventListener('mouseleave', onLeave)
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => {
      el.removeEventListener('mousemove', onMove)
      el.removeEventListener('mousedown', onDown)
      window.removeEventListener('mouseup', onUp)
      el.removeEventListener('mouseleave', onLeave)
      el.removeEventListener('wheel', onWheel)
    }
  }, [])

  return (
    <>
      <div ref={containerRef} className="viewport" />
      <div className="camera-hint">{hint}</div>
      {tooltip && (
        <div
          className="map-tooltip"
          style={{ left: tooltip.x, top: tooltip.y }}
          role="tooltip"
        >
          {tooltip.lines.map((line, index) => (
            <div key={index}>{line}</div>
          ))}
        </div>
      )}
    </>
  )
}
