import { useEffect, useRef, useState } from 'react'
import type { VoiceReply } from '../types'
import { openMic } from '../voice/mic'
import { PcmPlayer } from '../voice/player'
import { openTransport } from '../voice/transport'
import { VoiceClient, type VoiceState } from '../voice/voiceClient'
import { createWakeDetector } from '../voice/wakeDetector'

export interface VoiceHandlers {
  onTranscript(text: string): void
  onReply(reply: VoiceReply): void
  onNotice(text: string): void
  onError(message: string): void
  /** The wake word was heard (only with {@link VoiceOptions.wakeWord}). */
  onWake?(): void
}

export interface VoiceOptions {
  /** Load the wake word detector, so the client can be started in the wake mode. Only the door screen needs it. */
  wakeWord?: boolean
}

/**
 * One voice conversation for a page: the microphone, the connection and the speaker, kept for as long as the page
 * is shown. Returns the state to display, the microphone's loudness (0 to 1, smoothed) and the client to control it.
 */
export function useVoice(handlers: VoiceHandlers, options: VoiceOptions = {}) {
  const [state, setState] = useState<VoiceState>('idle')
  const [level, setLevel] = useState(0)
  const handlersRef = useRef(handlers)
  const frames = useRef(0)
  const smoothed = useRef(0)

  useEffect(() => {
    handlersRef.current = handlers
  })

  const [wake] = useState(() => (options.wakeWord ? createWakeDetector() : undefined))
  const [client] = useState(
    () =>
      new VoiceClient(
        { openTransport, openMic, speaker: new PcmPlayer(), wake },
        {
          onWake: () => handlersRef.current.onWake?.(),
          onState: (s) => {
            setState(s)
            if (s === 'idle') setLevel(0)
          },
          onTranscript: (text) => handlersRef.current.onTranscript(text),
          onReply: (reply) => handlersRef.current.onReply(reply),
          onNotice: (text) => handlersRef.current.onNotice(text),
          onError: (message) => handlersRef.current.onError(message),
          onLevel: (rms) => {
            smoothed.current = smoothed.current * 0.6 + Math.min(1, rms * 5) * 0.4
            if (++frames.current % 3 === 0) setLevel(smoothed.current) // about 16 updates a second is plenty for a ring
          },
        },
      ),
  )

  useEffect(() => () => client.dispose(), [client])

  return { state, level, client, wake }
}
