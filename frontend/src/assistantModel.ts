import { ApiError } from './api'
import type { VoiceReply } from './types'

/** One bubble in the chat. */
export interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** What the assistant did to answer, shown as small chips under the bubble. */
  actions: string[]
  failed: string[]
  error: boolean
  /** The person said it (a microphone) instead of typing it. */
  spoken: boolean
}

/** Sentences to try, shown while the chat is empty. */
export const EXAMPLES = [
  'Turn off the background light',
  'What is the weather today?',
  "What's next on my calendar?",
  'Dim the desk lamp to 20 percent',
]

export const userMessage = (id: number, text: string, spoken = false): ChatMessage => ({
  id,
  role: 'user',
  text,
  actions: [],
  failed: [],
  error: false,
  spoken,
})

export const assistantMessage = (id: number, reply: VoiceReply): ChatMessage => ({
  id,
  role: 'assistant',
  text: reply.reply,
  actions: reply.actions,
  failed: reply.failed,
  error: false,
  spoken: false,
})

export const errorMessage = (id: number, text: string): ChatMessage => ({
  id,
  role: 'assistant',
  text,
  actions: [],
  failed: [],
  error: true,
  spoken: false,
})

/** What to tell the user when asking failed. The server's own wording is used when it has one worth showing. */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429) return 'Too many questions in a row. Wait a moment and try again.'
    if (e.status === 401 || e.status === 403) return 'You are signed out. Sign in again to use the assistant.'
    if (e.status === 400) return 'That message could not be sent. Try shorter wording.'
    return e.message || 'The assistant could not answer.'
  }
  return "Can't reach the server."
}

/** The text can be sent: something other than spaces, and short enough for the server (500 characters). */
export const canSend = (text: string) => text.trim().length > 0 && text.length <= 500

/** What the status line says while a voice conversation is going on; empty when there is nothing to say. */
export function voiceStatusText(state: string, handsFree: boolean): string {
  switch (state) {
    case 'connecting':
      return 'Connecting…'
    case 'listening':
      return handsFree ? 'Listening, just speak' : 'Listening, say something'
    case 'hearing':
      return 'Hearing you…'
    case 'transcribing':
      return 'Writing that down…'
    case 'thinking':
      return 'Thinking…'
    case 'speaking':
      return handsFree ? 'Speaking, talk over me to interrupt' : 'Speaking, tap to stop'
    default:
      return ''
  }
}
