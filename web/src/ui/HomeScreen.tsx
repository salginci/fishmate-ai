import { useEffect, useState } from 'react'
import { api, auth } from '../data/api'
import type { HomeSummary } from '../data/types'

/**
 * Voices confirmed to open a session against the live service. All twelve
 * were checked rather than taken from a list, because a rejected voice fails
 * at setup and looks like a broken connection.
 */
export const VOICES = [
  'Kore',
  'Puck',
  'Charon',
  'Fenrir',
  'Aoede',
  'Leda',
  'Orus',
  'Zephyr',
  'Autonoe',
  'Enceladus',
  'Sulafat',
  'Achernar',
] as const

export function HomeScreen({
  onStart,
  onSignedOut,
  voice,
  onVoiceChange,
}: {
  onStart: () => void
  onSignedOut: () => void
  voice: string
  onVoiceChange: (voice: string) => void
}) {
  const [home, setHome] = useState<HomeSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void api.home().then((result) => {
      if (cancelled) return
      if (result.ok) setHome(result.value)
      else setError(result.error.message)
    })
    return () => {
      cancelled = true
    }
  }, [])

  const conditions = home?.conditions

  return (
    <div className="stack">
      <div className="topbar">
        <h2>{home?.display_name ?? 'FishMate'}</h2>
        <button
          className="link"
          onClick={() => {
            auth.setToken(null)
            onSignedOut()
          }}
        >
          Sign out
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <button className="start-button" onClick={onStart}>
        Start fishing
      </button>

      <div className="card">
        <h3>Voice</h3>
        {/* The right voice is a matter of taste, and nobody can judge it from
            a name — so this is a picker rather than a setting buried in a
            config file. It applies to the next session. */}
        <select
          className="voice-select"
          value={voice}
          onChange={(event) => onVoiceChange(event.target.value)}
        >
          {VOICES.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </div>

      {conditions && (
        <div className="card">
          <h3>Today&rsquo;s conditions</h3>
          <p style={{ margin: '0 0 12px', lineHeight: 1.45 }}>{conditions.summary}</p>
          <div className="metrics">
            <Metric
              label="Wind"
              value={
                conditions.wind_speed_kmh !== null
                  ? `${conditions.wind_direction ?? ''} ${Math.round(conditions.wind_speed_kmh)} km/h`.trim()
                  : '—'
              }
            />
            <Metric
              label="Waves"
              value={conditions.wave_height_m !== null ? `${conditions.wave_height_m.toFixed(1)} m` : '—'}
            />
            <Metric
              label="Air"
              value={conditions.air_temp_c !== null ? `${Math.round(conditions.air_temp_c)}°C` : '—'}
            />
            <Metric
              label="Water"
              value={conditions.water_temp_c !== null ? `${Math.round(conditions.water_temp_c)}°C` : '—'}
            />
          </div>
        </div>
      )}

      {home && home.recent_catches.length > 0 && (
        <div className="card">
          <h3>Recent catches</h3>
          {home.recent_catches.slice(0, 5).map((item) => (
            <div className="catch-row" key={item.id}>
              <span>{item.species_label ?? item.species_key ?? 'Unknown'}</span>
              <span className="meta">
                {item.length_cm !== null ? `${item.length_cm} cm · ` : ''}
                {new Date(item.caught_at).toLocaleDateString()}
              </span>
            </div>
          ))}
        </div>
      )}

      {home && (
        <div className="card">
          <div className="metrics">
            <Metric label="Catches" value={String(home.total_catches)} />
            <Metric label="Tackle" value={String(home.tackle_count)} />
          </div>
        </div>
      )}
    </div>
  )
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric">
      <div className="value">{value}</div>
      <div className="label">{label}</div>
    </div>
  )
}
