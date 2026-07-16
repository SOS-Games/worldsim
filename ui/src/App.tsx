import { useState } from 'react'
import Viewport from './Viewport'
import './App.css'

function App() {
  const [showPaths, setShowPaths] = useState(true)
  const [connected, setConnected] = useState(false)

  return (
    <div className="app">
      <div className="hud">
        <div className={`status ${connected ? 'connected' : 'disconnected'}`}>
          <span className="status-dot" aria-hidden="true" />
          <span>{connected ? 'Connected' : 'Waiting for backend…'}</span>
        </div>
        <button type="button" onClick={() => setShowPaths((on) => !on)}>
          {showPaths ? 'Hide Paths' : 'Show Paths'}
        </button>
      </div>
      <Viewport showPaths={showPaths} onConnectionChange={setConnected} />
    </div>
  )
}

export default App
