import { useState } from 'react'
import { auth } from './data/api'
import { FishingModeScreen } from './ui/FishingModeScreen'
import { HomeScreen } from './ui/HomeScreen'
import { LoginScreen } from './ui/LoginScreen'

type Screen = 'login' | 'home' | 'fishing'

export function App() {
  const [screen, setScreen] = useState<Screen>(auth.token() ? 'home' : 'login')
  // English is the primary language; Turkish is fully supported and the model
  // follows whichever the angler speaks.
  const [language] = useState('en')
  // Remembered per browser so trying voices does not mean re-picking each time.
  const [voice, setVoice] = useState(() => localStorage.getItem('fishmate.voice') ?? 'Kore')

  function chooseVoice(next: string) {
    setVoice(next)
    try {
      localStorage.setItem('fishmate.voice', next)
    } catch {
      // Private browsing; the choice simply does not persist.
    }
  }

  return (
    <div className="app">
      {screen === 'login' && <LoginScreen onSignedIn={() => setScreen('home')} />}
      {screen === 'home' && (
        <HomeScreen
          onStart={() => setScreen('fishing')}
          onSignedOut={() => setScreen('login')}
          voice={voice}
          onVoiceChange={chooseVoice}
        />
      )}
      {screen === 'fishing' && (
        <FishingModeScreen language={language} voice={voice} onExit={() => setScreen('home')} />
      )}
    </div>
  )
}
