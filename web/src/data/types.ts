/** Mirrors `backend/app/schemas/api.py`; only the fields the web app uses. */

export type User = {
  id: string
  email: string
  display_name: string
  language?: string | null
}

export type TokenResponse = {
  access_token: string
  token_type: string
  expires_in: number
  user: User
}

export type Conditions = {
  summary: string
  observed_at: string
  provider: string
  air_temp_c: number | null
  water_temp_c: number | null
  wind_speed_kmh: number | null
  wind_direction: string | null
  wind_beaufort: number | null
  wave_height_m: number | null
  sea_data_available: boolean
  sunrise: string | null
  sunset: string | null
}

export type CatchSummary = {
  id: string
  species_key: string | null
  species_label?: string | null
  length_cm: number | null
  caught_at: string
  method?: string | null
}

export type SessionSummary = {
  id: string
  started_at: string
  ended_at?: string | null
  location_name?: string | null
  catch_count?: number
}

export type HomeSummary = {
  display_name: string
  active_session: SessionSummary | null
  last_session: SessionSummary | null
  recent_catches: CatchSummary[]
  tackle_count: number
  total_catches: number
  conditions: Conditions | null
  conditions_error: string | null
}

/**
 * Everything needed to open a Live WebSocket.
 *
 * `setup` is the exact first frame to send, built server-side from the SDK's
 * own converter and locked into the token. The client does not get to choose
 * its own configuration — see `docs/GEMINI_LIVE_IMPLEMENTATION.md`.
 */
export type LiveCredentials = {
  token: string
  ws_url: string
  model: string
  expires_at: string
  new_session_expires_at: string
  setup: Record<string, unknown>
  input_sample_rate_hz: number
  output_sample_rate_hz: number
  input_audio_mime: string
  user_brief: Record<string, unknown> | null
}

export type ToolCallResult = {
  name: string
  call_id: string | null
  ok: boolean
  response: Record<string, unknown>
  error_code: string | null
}
