import { useState } from 'react'
import Viewport from './Viewport'
import './App.css'

function App() {
  const [showPaths, setShowPaths] = useState(true)

  return (
    <div className="app">
      <div className="hud">
        <button type="button" onClick={() => setShowPaths((on) => !on)}>
          {showPaths ? 'Hide Paths' : 'Show Paths'}
        </button>
      </div>
      <Viewport showPaths={showPaths} />
    </div>
  )
}

export default App
