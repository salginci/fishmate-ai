import type { LiveCredentials } from '../data/types'

export type ToolCallRequest = {
  id: string | null
  name: string
  args: Record<string, unknown>
}

export type ToolResult = {
  id: string | null
  name: string
  response: Record<string, unknown>
}

export type GroundingSource = { title: string | null; uri: string }

export type LiveEvent =
  | { type: 'ready' }
  | { type: 'audio'; pcm: Int16Array }
  | { type: 'interrupted' }
  | { type: 'interimInputTranscript'; text: string }
  | { type: 'inputTranscript'; text: string }
  | { type: 'outputTranscript'; text: string }
  | { type: 'toolCalls'; calls: ToolCallRequest[] }
  | { type: 'toolCallsCancelled'; ids: string[] }
  | { type: 'grounding'; queries: string[]; sources: GroundingSource[] }
  | { type: 'resumptionHandle'; handle: string }
  | { type: 'goingAway'; timeLeftMs: number | null }
  | { type: 'generationComplete' }
  | { type: 'turnComplete' }
  | { type: 'usage'; totalTokens: number }
  | { type: 'error'; code: string; message: string; fatal: boolean }

export type LiveConnectionState =
  | { state: 'idle' }
  | { state: 'connecting' }
  | { state: 'connected' }
  | { state: 'reconnecting'; attempt: number }
  | { state: 'closed' }
  | { state: 'failed'; code: string; message: string }

/** True once the token can no longer authenticate an existing session. */
export function isExpired(creds: LiveCredentials): boolean {
  return Date.parse(creds.expires_at) <= Date.now()
}

/**
 * Whether the token can still *open* a new socket.
 *
 * Ephemeral tokens carry two deadlines, and conflating them was a real bug on
 * Android: `expires_at` (around 30 minutes) keeps an already-open session
 * alive, while `new_session_expires_at` (around a minute) is the window for
 * starting one. A reconnect that only checked the first would keep retrying a
 * token that could never open a socket again, so any drop longer than a minute
 * became permanent and surfaced as "I can't reach Gemini right now".
 */
export function canStartNewSession(creds: LiveCredentials): boolean {
  return Date.parse(creds.new_session_expires_at) > Date.now()
}
