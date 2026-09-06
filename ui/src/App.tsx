import { useEffect, useState, type FocusEvent, type MouseEvent } from 'react'
import Viewport from './Viewport'
import './App.css'

const BACKEND_URL = 'http://localhost:8080/world/debug/backend'

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
  physicsSql?: SqlStep[]
  aiSql?: SqlStep[]
}

interface SqlStep {
  name: string
  ms: number
  rows: number
}

interface TickSide {
  lastMs: number
  sqlMs: number
  javaMs: number
  ageMs: number
  skipped: number
  ticks: number
  slow: number
  javaSteps?: SqlStep[]
}

interface BackendDebug {
  heapUsedMb: number
  heapMaxMb: number
  threads: number
  liveClients: number
  worldActive: boolean
  physics: TickSide
  ai: TickSide
  lastPhysicsError?: string | null
  lastAiError?: string | null
}

interface HoverTip {
  text: string
  x: number
  y: number
}

function DebugRow({
  label,
  value,
  tip,
  warn = false,
  onTip,
}: {
  label: string
  value: string
  tip: string
  warn?: boolean
  onTip: (tip: HoverTip | null) => void
}) {
  const show = (event: MouseEvent<HTMLDivElement> | FocusEvent<HTMLDivElement>) => {
    const rect = event.currentTarget.getBoundingClientRect()
    onTip({ text: tip, x: rect.right + 12, y: rect.top })
  }

  return (
    <div
      className={`debug-row ${warn ? 'warn' : ''}`}
      tabIndex={0}
      onMouseEnter={show}
      onFocus={show}
      onMouseLeave={() => onTip(null)}
      onBlur={() => onTip(null)}
    >
      <span className="debug-label">{label}</span>
      <span className="debug-value">{value}</span>
    </div>
  )
}

function App() {
  const [showPaths, setShowPaths] = useState(true)
  const [connected, setConnected] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [tick, setTick] = useState<TickStats | null>(null)
  const [backend, setBackend] = useState<BackendDebug | null>(null)
  const [fps, setFps] = useState<number | null>(null)
  const [hoverTip, setHoverTip] = useState<HoverTip | null>(null)

  useEffect(() => {
    if (!menuOpen) {
      return
    }
    let cancelled = false
    const load = async () => {
      try {
        const response = await fetch(BACKEND_URL)
        if (!response.ok) {
          return
        }
        const data = (await response.json()) as BackendDebug
        if (!cancelled) {
          setBackend(data)
        }
      } catch {
        if (!cancelled) {
          setBackend(null)
        }
      }
    }
    void load()
    const id = window.setInterval(() => void load(), 2000)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [menuOpen])

  const moveSlow = (tick?.moveMs ?? 0) > 900
  const replanSlow = (tick?.replanMs ?? 0) > 900
  const walking = tick?.walking ?? tick?.moved ?? 0
  const harvesting = tick?.harvesting ?? tick?.harvested ?? 0
  const buying = tick?.buying ?? 0
  const depositing = tick?.depositing ?? 0
  const needRoute = tick?.needRoute ?? tick?.waitingForPath ?? 0
  const routed = tick?.routed ?? tick?.replanned ?? 0
  const routeBacklog = needRoute > 16
  const heapFull =
    (backend?.heapMaxMb ?? 0) > 0 && (backend?.heapUsedMb ?? 0) / (backend?.heapMaxMb ?? 1) > 0.85
  const physicsStale = (backend?.physics.ageMs ?? 0) > 1500
  const aiStale = (backend?.ai.ageMs ?? 0) > 1500
  const backendWarn =
    heapFull ||
    physicsStale ||
    aiStale ||
    (backend?.physics.skipped ?? 0) > 0 ||
    (backend?.ai.skipped ?? 0) > 0 ||
    Boolean(backend?.lastPhysicsError || backend?.lastAiError)

  return (
    <div className="app">
      <aside className={`debug-drawer ${menuOpen ? 'open' : 'closed'}`}>
        <button
          type="button"
          className="hamburger"
          aria-label={menuOpen ? 'Hide debug menu' : 'Show debug menu'}
          aria-expanded={menuOpen}
          onClick={() => {
            setHoverTip(null)
            setMenuOpen((open) => !open)
          }}
        >
          <span className="hamburger-lines" aria-hidden="true">
            <span />
            <span />
            <span />
          </span>
          <span className={`status-dot ${connected ? 'connected' : 'disconnected'}`} />
        </button>

        {menuOpen && (
          <div className="debug-panel">
            <div className={`status ${connected ? 'connected' : 'disconnected'}`}>
              <span className="status-dot" aria-hidden="true" />
              <span>{connected ? 'Connected' : 'Waiting for backend…'}</span>
            </div>

            <button type="button" onClick={() => setShowPaths((on) => !on)}>
              {showPaths ? 'Hide Paths' : 'Show Paths'}
            </button>

            {tick && (
              <div className={`debug-stats ${moveSlow || replanSlow || routeBacklog ? 'slow' : ''}`}>
                <div className="debug-section">Timing</div>
                <DebugRow
                  label="FPS"
                  value={fps === null ? '…' : `${fps}`}
                  warn={(fps ?? 60) < 30}
                  onTip={setHoverTip}
                  tip="How many times the map redraws per second. 60 is smooth. Drops below 30 usually mean the path overlay or a big data refresh is hitching."
                />
                <DebugRow
                  label="Physics"
                  value={`${tick.moveMs}ms`}
                  warn={moveSlow}
                  onTip={setHoverTip}
                  tip="How long walking, harvesting, and delivering took this second. Over 900ms means the next physics tick is skipped."
                />
                <DebugRow
                  label="AI"
                  value={`${tick.replanMs}ms`}
                  warn={replanSlow}
                  onTip={setHoverTip}
                  tip="How long goal-picking and pathfinding took this second. Over 900ms means the next AI tick is skipped."
                />
                {(tick.physicsSql ?? []).map((step) => (
                  <DebugRow
                    key={`physics-${step.name}`}
                    label={step.name}
                    value={`${step.ms}ms`}
                    warn={step.ms >= 40}
                    onTip={setHoverTip}
                    tip={`Physics SQL step "${step.name}" last tick. Touched ${step.rows} rows.`}
                  />
                ))}
                {(tick.aiSql ?? []).map((step) => (
                  <DebugRow
                    key={`ai-${step.name}`}
                    label={step.name}
                    value={`${step.ms}ms`}
                    warn={step.ms >= 40}
                    onTip={setHoverTip}
                    tip={`AI step "${step.name}" last tick. Count ${step.rows}.`}
                  />
                ))}

                <div className="debug-section">NPCs now</div>
                <DebugRow
                  label="Walking"
                  value={`${walking}`}
                  onTip={setHoverTip}
                  tip="NPCs currently following a path. These are moving, not stuck."
                />
                <DebugRow
                  label="Harvesting"
                  value={`${harvesting}`}
                  onTip={setHoverTip}
                  tip="Standing still on purpose, gathering their job resource (one unit per second)."
                />
                <DebugRow
                  label="Buying"
                  value={`${buying}`}
                  onTip={setHoverTip}
                  tip="Traders standing in a cheap city, taking one unit per second from the market."
                />
                <DebugRow
                  label="Selling"
                  value={`${depositing}`}
                  onTip={setHoverTip}
                  tip="Standing in a city with cargo: gatherers dump into the market, traders sell where the price is high."
                />
                <DebugRow
                  label="Need route"
                  value={`${needRoute}`}
                  warn={routeBacklog}
                  onTip={setHoverTip}
                  tip="Standing still because they have no path yet. Waiting for AI to pick a destination and compute a route."
                />

                <div className="debug-section">This second</div>
                <DebugRow
                  label="Stepped"
                  value={`${tick.moved}`}
                  onTip={setHoverTip}
                  tip="How many NPCs actually walked one tile this second."
                />
                <DebugRow
                  label="Gathered"
                  value={`${tick.harvested}`}
                  onTip={setHoverTip}
                  tip="How many harvest actions happened this second (one unit each)."
                />
                <DebugRow
                  label="Sold"
                  value={`${tick.delivered}`}
                  onTip={setHoverTip}
                  tip="How many NPCs sold cargo into a city market this second."
                />
                <DebugRow
                  label="Bought"
                  value={`${tick.bought ?? 0}`}
                  onTip={setHoverTip}
                  tip="How many trader buy actions happened this second (one unit each)."
                />
                <DebugRow
                  label="New routes"
                  value={`${routed}`}
                  onTip={setHoverTip}
                  tip="How many new paths AI computed this second. This is the AI budget used, not how many NPCs currently have a path."
                />
                <DebugRow
                  label="Agents"
                  value={`${tick.agents}`}
                  onTip={setHoverTip}
                  tip="Total NPCs in the world."
                />
              </div>
            )}

            {backend && (
              <div className={`debug-stats ${backendWarn ? 'slow' : ''}`}>
                <div className="debug-section">Backend</div>
                <DebugRow
                  label="Heap"
                  value={`${backend.heapUsedMb} / ${backend.heapMaxMb} MB`}
                  warn={heapFull}
                  onTip={setHoverTip}
                  tip="Java heap used vs max. If this stays near full, garbage collection can hitch ticks."
                />
                <DebugRow
                  label="Threads"
                  value={`${backend.threads}`}
                  onTip={setHoverTip}
                  tip="JVM thread count, including virtual-thread carriers. A sudden climb usually means ticks or sockets are stuck."
                />
                <DebugRow
                  label="Live WS"
                  value={`${backend.liveClients}`}
                  warn={backend.liveClients === 0}
                  onTip={setHoverTip}
                  tip="Viewers connected to /world/live. Zero means this page is not getting live positions."
                />
                <DebugRow
                  label="Phys SQL/Java"
                  value={`${backend.physics.sqlMs} / ${backend.physics.javaMs}ms`}
                  warn={backend.physics.javaMs > backend.physics.sqlMs && backend.physics.javaMs >= 20}
                  onTip={setHoverTip}
                  tip="Last physics tick split: database time vs Java time (position snapshot, websocket send)."
                />
                <DebugRow
                  label="AI SQL/Java"
                  value={`${backend.ai.sqlMs} / ${backend.ai.javaMs}ms`}
                  warn={backend.ai.javaMs > backend.ai.sqlMs && backend.ai.javaMs >= 20}
                  onTip={setHoverTip}
                  tip="Last AI tick split: database/pathfinding vs Java (goal pick, persist)."
                />
                <DebugRow
                  label="Skipped"
                  value={`phys ${backend.physics.skipped}  ai ${backend.ai.skipped}`}
                  warn={backend.physics.skipped > 0 || backend.ai.skipped > 0 || physicsStale || aiStale}
                  onTip={setHoverTip}
                  tip="Scheduled ticks that never ran because the previous one was still going. Often a second backend on port 8080, or a tick over 1 second."
                />
                {(backend.lastPhysicsError || backend.lastAiError) && (
                  <DebugRow
                    label="Last error"
                    value={backend.lastPhysicsError || backend.lastAiError || ''}
                    warn
                    onTip={setHoverTip}
                    tip="Most recent Java exception from a physics or AI tick."
                  />
                )}
              </div>
            )}
          </div>
        )}
      </aside>
      {hoverTip && (
        <div className="debug-tooltip" style={{ left: hoverTip.x, top: hoverTip.y }} role="tooltip">
          {hoverTip.text}
        </div>
      )}
      <Viewport
        showPaths={showPaths}
        onConnectionChange={setConnected}
        onTickStats={setTick}
        onFps={setFps}
      />
    </div>
  )
}

export default App
