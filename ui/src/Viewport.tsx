import { useEffect, useRef, useState } from 'react'
import { Application, Container, Graphics } from 'pixi.js'

const TILE_SIZE = 20
const API_URL = 'http://localhost:8080/world/state'
const INVENTORY_CAPACITY = 10
const AGENT_HIT_RADIUS = 0.45

const AGENT_COLORS = [0x3366ff, 0xff6633, 0x33cc66, 0xcc33ff, 0xffcc33]

const RESOURCE_COLORS: Record<string, number> = {
  WOOD: 0x2e7d32,
  GOLD: 0xc9a227,
  FOOD: 0xd35400,
}

const JOB_RESOURCE: Record<string, string> = {
  LUMBERJACK: 'WOOD',
  MINER: 'GOLD',
  TRADER: 'FOOD',
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
}

interface Agent {
  id: number
  name: string
  location: Coord
  speed: number
  targetLocation: Coord | null
  path: Coord[]
  job: string | null
  inventory: Record<string, number>
}

interface WorldState {
  tiles: Tile[]
  agents: Agent[]
}

interface AgentSprite {
  gfx: Graphics
  target: Coord
  current: Coord
}

interface ViewportProps {
  showPaths: boolean
  onConnectionChange?: (connected: boolean) => void
}

interface TooltipState {
  x: number
  y: number
  lines: string[]
}

function agentColor(id: number): number {
  return AGENT_COLORS[id % AGENT_COLORS.length]!
}

function tileColor(tile: Tile): number {
  if (tile.terrainType === 'mountain') {
    return 0x444444
  }
  if (tile.terrainType === 'city') {
    return 0x5c6bc0
  }
  if (tile.resourceType && tile.quantity > 0) {
    return RESOURCE_COLORS[tile.resourceType] ?? 0x888888
  }
  return 0x888888
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
  const gridX = Math.floor(displayPos.x)
  const gridY = Math.floor(displayPos.y)
  const underfoot = tileAt(tiles, gridX, gridY)
  const jobResource = agent.job ? JOB_RESOURCE[agent.job] : null
  const count = inventoryCount(agent)
  const full = count >= INVENTORY_CAPACITY

  const targetTile = agent.targetLocation
    ? tileAt(
        tiles,
        Math.floor(agent.targetLocation.x),
        Math.floor(agent.targetLocation.y),
      )
    : undefined

  if (
    underfoot?.resourceType &&
    underfoot.quantity > 0 &&
    underfoot.resourceType === jobResource &&
    !full
  ) {
    return `Harvesting ${underfoot.resourceType}`
  }

  if (underfoot?.terrainType === 'city' && count > 0) {
    return 'Depositing at city'
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

function describeTile(tile: Tile): string[] {
  const lines = [`Tile (${tile.x}, ${tile.y})`]
  if (tile.terrainType === 'city') {
    lines.push('City')
  } else if (tile.terrainType === 'mountain') {
    lines.push('Mountain (impassable)')
  } else {
    lines.push('Grass')
  }
  if (tile.resourceType && tile.quantity > 0) {
    lines.push(`Resource: ${tile.resourceType}`)
    lines.push(`Quantity: ${tile.quantity}`)
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

export default function Viewport({ showPaths, onConnectionChange }: ViewportProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const appRef = useRef<Application | null>(null)
  const pathsLayerRef = useRef<Container | null>(null)
  const destLayerRef = useRef<Container | null>(null)
  const agentsLayerRef = useRef<Container | null>(null)
  const tileGfxRef = useRef<Map<number, Graphics>>(new Map())
  const agentSpritesRef = useRef<Map<number, AgentSprite>>(new Map())
  const tilesDrawnRef = useRef(false)
  const showPathsRef = useRef(showPaths)
  const lastAgentsRef = useRef<Agent[]>([])
  const lastTilesRef = useRef<Tile[]>([])
  const onConnectionChangeRef = useRef(onConnectionChange)
  const [tooltip, setTooltip] = useState<TooltipState | null>(null)

  showPathsRef.current = showPaths
  onConnectionChangeRef.current = onConnectionChange

  const updatePaths = (agents: Agent[]) => {
    const pathsLayer = pathsLayerRef.current
    const destLayer = destLayerRef.current
    if (!pathsLayer || !destLayer) return

    pathsLayer.removeChildren()
    destLayer.removeChildren()

    if (!showPathsRef.current) return

    for (const agent of agents) {
      const color = agentColor(agent.id)

      if (agent.path.length >= 2) {
        const line = new Graphics()
        line.moveTo(agent.path[0]!.x * TILE_SIZE, agent.path[0]!.y * TILE_SIZE)
        for (let i = 1; i < agent.path.length; i++) {
          const point = agent.path[i]!
          line.lineTo(point.x * TILE_SIZE, point.y * TILE_SIZE)
        }
        line.stroke({ width: 2, color, alpha: 0.55 })
        pathsLayer.addChild(line)
      }

      if (agent.targetLocation) {
        const dest = new Graphics()
        const tx = agent.targetLocation.x * TILE_SIZE
        const ty = agent.targetLocation.y * TILE_SIZE
        const size = TILE_SIZE * 0.3
        dest.rect(tx - size / 2, ty - size / 2, size, size)
        dest.fill({ color, alpha: 0.85 })
        dest.stroke({ width: 1, color: 0xffffff, alpha: 0.8 })
        destLayer.addChild(dest)
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
        antialias: true,
      })

      if (destroyed) {
        app.destroy({ removeView: true }, true)
        return
      }

      initialized = true
      containerRef.current.appendChild(app.canvas)
      appRef.current = app

      const pathsLayer = new Container()
      const destLayer = new Container()
      const agentsLayer = new Container()
      pathsLayerRef.current = pathsLayer
      destLayerRef.current = destLayer
      agentsLayerRef.current = agentsLayer

      app.stage.addChild(pathsLayer)
      app.stage.addChild(destLayer)
      app.stage.addChild(agentsLayer)

      app.ticker.add(() => {
        for (const sprite of agentSpritesRef.current.values()) {
          sprite.current.x += (sprite.target.x - sprite.current.x) * 0.2
          sprite.current.y += (sprite.target.y - sprite.current.y) * 0.2
          sprite.gfx.x = sprite.current.x * TILE_SIZE
          sprite.gfx.y = sprite.current.y * TILE_SIZE
        }
      })
    })()

    return () => {
      destroyed = true
      if (initialized) {
        app.destroy({ removeView: true }, true)
      }
      appRef.current = null
      pathsLayerRef.current = null
      destLayerRef.current = null
      agentsLayerRef.current = null
      tileGfxRef.current.clear()
      agentSpritesRef.current.clear()
      tilesDrawnRef.current = false
    }
  }, [])

  useEffect(() => {
    const fetchState = async () => {
      try {
        const res = await fetch(API_URL)
        if (!res.ok) {
          onConnectionChangeRef.current?.(false)
          return
        }
        const state = (await res.json()) as WorldState
        onConnectionChangeRef.current?.(true)

        const app = appRef.current
        const agentsLayer = agentsLayerRef.current
        if (!app || !agentsLayer) return

        if (!tilesDrawnRef.current && state.tiles.length > 0) {
          for (const tile of state.tiles) {
            const gfx = new Graphics()
            gfx.rect(0, 0, TILE_SIZE, TILE_SIZE)
            gfx.fill(tileColor(tile))
            gfx.x = tile.x * TILE_SIZE
            gfx.y = tile.y * TILE_SIZE
            tileGfxRef.current.set(tile.id, gfx)
            app.stage.addChildAt(gfx, 0)
          }
          tilesDrawnRef.current = true
        } else {
          for (const tile of state.tiles) {
            const gfx = tileGfxRef.current.get(tile.id)
            if (!gfx) continue
            gfx.clear()
            gfx.rect(0, 0, TILE_SIZE, TILE_SIZE)
            gfx.fill(tileColor(tile))
          }
        }

        const seen = new Set<number>()
        for (const agent of state.agents) {
          seen.add(agent.id)
          const color = agentColor(agent.id)
          let entry = agentSpritesRef.current.get(agent.id)
          if (!entry) {
            const gfx = new Graphics()
            gfx.circle(0, 0, TILE_SIZE * 0.35)
            gfx.fill(color)
            gfx.stroke({ width: 1, color: 0xffffff, alpha: 0.9 })
            gfx.x = agent.location.x * TILE_SIZE
            gfx.y = agent.location.y * TILE_SIZE
            entry = {
              gfx,
              target: { ...agent.location },
              current: { ...agent.location },
            }
            agentSpritesRef.current.set(agent.id, entry)
            agentsLayer.addChild(gfx)
          } else {
            entry.target = { ...agent.location }
            entry.gfx.clear()
            entry.gfx.circle(0, 0, TILE_SIZE * 0.35)
            entry.gfx.fill(color)
            entry.gfx.stroke({ width: 1, color: 0xffffff, alpha: 0.9 })
          }
        }

        for (const [id, entry] of agentSpritesRef.current) {
          if (!seen.has(id)) {
            agentsLayer.removeChild(entry.gfx)
            entry.gfx.destroy()
            agentSpritesRef.current.delete(id)
          }
        }

        lastAgentsRef.current = state.agents
        lastTilesRef.current = state.tiles
        updatePaths(state.agents)
      } catch {
        onConnectionChangeRef.current?.(false)
      }
    }

    void fetchState()
    const interval = setInterval(() => void fetchState(), 1000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    updatePaths(lastAgentsRef.current)
  }, [showPaths])

  useEffect(() => {
    const el = containerRef.current
    if (!el) return

    const onMove = (event: MouseEvent) => {
      const rect = el.getBoundingClientRect()
      const localX = event.clientX - rect.left
      const localY = event.clientY - rect.top
      const worldX = localX / TILE_SIZE
      const worldY = localY / TILE_SIZE

      const agents = lastAgentsRef.current
      const tiles = lastTilesRef.current
      if (tiles.length === 0) {
        setTooltip(null)
        return
      }

      let closest: { agent: Agent; pos: Coord; dist: number } | null = null
      for (const agent of agents) {
        const sprite = agentSpritesRef.current.get(agent.id)
        const pos = sprite?.current ?? agent.location
        const dx = pos.x - worldX
        const dy = pos.y - worldY
        const dist = Math.hypot(dx, dy)
        if (dist <= AGENT_HIT_RADIUS && (!closest || dist < closest.dist)) {
          closest = { agent, pos, dist }
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

      const gridX = Math.floor(worldX)
      const gridY = Math.floor(worldY)
      const tile = tileAt(tiles, gridX, gridY)
      if (!tile) {
        setTooltip(null)
        return
      }

      setTooltip({
        x: event.clientX + 14,
        y: event.clientY + 14,
        lines: describeTile(tile),
      })
    }

    const onLeave = () => setTooltip(null)

    el.addEventListener('mousemove', onMove)
    el.addEventListener('mouseleave', onLeave)
    return () => {
      el.removeEventListener('mousemove', onMove)
      el.removeEventListener('mouseleave', onLeave)
    }
  }, [])

  return (
    <>
      <div ref={containerRef} className="viewport" />
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
