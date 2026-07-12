import { useEffect, useRef } from 'react'
import { Application, Container, Graphics } from 'pixi.js'

const TILE_SIZE = 20
const API_URL = 'http://localhost:8080/world/state'

const AGENT_COLORS = [0x3366ff, 0xff6633, 0x33cc66, 0xcc33ff, 0xffcc33]

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
}

interface Agent {
  id: number
  name: string
  location: Coord
  speed: number
  targetLocation: Coord | null
  path: Coord[]
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
}

function agentColor(id: number): number {
  return AGENT_COLORS[id % AGENT_COLORS.length]!
}

export default function Viewport({ showPaths }: ViewportProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const appRef = useRef<Application | null>(null)
  const pathsLayerRef = useRef<Container | null>(null)
  const destLayerRef = useRef<Container | null>(null)
  const agentsLayerRef = useRef<Container | null>(null)
  const agentSpritesRef = useRef<Map<number, AgentSprite>>(new Map())
  const tilesDrawnRef = useRef(false)
  const showPathsRef = useRef(showPaths)
  const lastAgentsRef = useRef<Agent[]>([])

  showPathsRef.current = showPaths

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
      agentSpritesRef.current.clear()
      tilesDrawnRef.current = false
    }
  }, [])

  useEffect(() => {
    const fetchState = async () => {
      try {
        const res = await fetch(API_URL)
        const state = (await res.json()) as WorldState
        const app = appRef.current
        const agentsLayer = agentsLayerRef.current
        if (!app || !agentsLayer) return

        if (!tilesDrawnRef.current && state.tiles.length > 0) {
          for (const tile of state.tiles) {
            const gfx = new Graphics()
            gfx.rect(0, 0, TILE_SIZE, TILE_SIZE)
            gfx.fill(tile.terrainType === 'mountain' ? 0x444444 : 0x888888)
            gfx.x = tile.x * TILE_SIZE
            gfx.y = tile.y * TILE_SIZE
            app.stage.addChildAt(gfx, 0)
          }
          tilesDrawnRef.current = true
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
        updatePaths(state.agents)
      } catch (err) {
        console.error('Failed to fetch world state', err)
      }
    }

    void fetchState()
    const interval = setInterval(() => void fetchState(), 1000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    updatePaths(lastAgentsRef.current)
  }, [showPaths])

  return <div ref={containerRef} className="viewport" />
}
