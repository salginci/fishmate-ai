import { Err, Ok, type AppResult } from '../core/result'
import { log } from '../core/log'
import type { HomeSummary, LiveCredentials, ToolCallResult, TokenResponse } from './types'

const TAG = 'Api'

/**
 * Same-origin by default: the Vite dev server proxies `/api` to the backend,
 * so no CORS policy is involved locally.
 */
const BASE = import.meta.env.VITE_API_BASE ?? ''

const TOKEN_KEY = 'fishmate.token'

export const auth = {
  token(): string | null {
    return localStorage.getItem(TOKEN_KEY)
  },
  setToken(token: string | null): void {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  },
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  { authenticated = true }: { authenticated?: boolean } = {},
): Promise<AppResult<T>> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (authenticated) {
    const token = auth.token()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }

  let response: Response
  try {
    response = await fetch(`${BASE}${path}`, { ...init, headers })
  } catch (cause) {
    // Offline, DNS, or the backend is not running. All of them read the same
    // way to the angler, and none of them should surface as a stack trace.
    log.w(TAG, `network error on ${path}`, cause)
    return Err('network', 'Could not reach FishMate. Check your connection.')
  }

  if (response.status === 401 || response.status === 403) {
    auth.setToken(null)
    return Err('unauthorized', 'Please sign in to continue.')
  }

  const text = await response.text()
  const body = text ? (JSON.parse(text) as unknown) : null

  if (!response.ok) {
    const error = (body as { error?: { code?: string; message?: string } } | null)?.error
    return Err(error?.code ?? 'http_error', error?.message ?? `Request failed (${response.status}).`)
  }
  return Ok(body as T)
}

export const api = {
  login(email: string, password: string) {
    return request<TokenResponse>(
      '/api/v1/auth/login',
      { method: 'POST', body: JSON.stringify({ email, password }) },
      { authenticated: false },
    )
  },

  home() {
    return request<HomeSummary>('/api/v1/home')
  },

  liveSession(
    language: string | null,
    expectsVideo: boolean,
    voiceName?: string | null,
    position?: { latitude: number; longitude: number } | null,
  ) {
    return request<LiveCredentials>('/api/v1/live/session', {
      method: 'POST',
      body: JSON.stringify({
        language,
        expects_video: expectsVideo,
        voice_name: voiceName ?? null,
        // The backend turns this into a place name and coarsens it to the
        // consented precision, so the session can open knowing the spot.
        latitude: position?.latitude ?? null,
        longitude: position?.longitude ?? null,
      }),
    })
  },

  translate(text: string, targetLanguage: string) {
    return request<{ text: string; target_language: string }>('/api/v1/live/translate', {
      method: 'POST',
      body: JSON.stringify({ text, target_language: targetLanguage }),
    })
  },

  executeTools(
    calls: {
      name: string
      call_id: string | null
      args: Record<string, unknown>
      language?: string | null
      latitude?: number | null
      longitude?: number | null
    }[],
  ) {
    return request<{ results: ToolCallResult[] }>('/api/v1/tools/execute-batch', {
      method: 'POST',
      body: JSON.stringify({ calls }),
    })
  },
}
