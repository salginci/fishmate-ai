import { useState } from 'react'
import { api, auth } from '../data/api'
import type { User } from '../data/types'

/**
 * Dev credentials are pre-filled so a local test is one click.
 *
 * `VITE_DEV_EMAIL` / `VITE_DEV_PASSWORD` are read from `.env.local`, which is
 * gitignored — the same arrangement as the Android build reading
 * `local.properties`, and for the same reason: convenience in development
 * must not put a credential in the repository.
 */
const DEV_EMAIL = import.meta.env.VITE_DEV_EMAIL ?? ''
const DEV_PASSWORD = import.meta.env.VITE_DEV_PASSWORD ?? ''

export function LoginScreen({ onSignedIn }: { onSignedIn: (user: User) => void }) {
  const [email, setEmail] = useState(DEV_EMAIL)
  const [password, setPassword] = useState(DEV_PASSWORD)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    const result = await api.login(email, password)
    setBusy(false)
    if (!result.ok) {
      setError(result.error.message)
      return
    }
    auth.setToken(result.value.access_token)
    onSignedIn(result.value.user)
  }

  return (
    <form className="auth" onSubmit={submit}>
      <div>
        <h1>FishMate AI</h1>
        <p className="sub">Your AI fishing partner. Talk to it while you fish.</p>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="field">
        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          autoComplete="username"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </div>

      <div className="field">
        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
      </div>

      <button className="primary" type="submit" disabled={busy}>
        {busy ? 'Signing in…' : 'Sign in'}
      </button>
    </form>
  )
}
