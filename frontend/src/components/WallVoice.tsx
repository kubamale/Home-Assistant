import { useEffect, useRef, useState } from 'react'
import { useVoiceSettings, useVoiceStatus } from '../hooks/data'
import { useVoice } from '../hooks/useVoice'
import { wakeThreshold } from '../voice/wakeDetector'
import { MicIcon } from './Icons'

/** Whether voice was turned on at this screen. A tap is needed once; after that the screen starts listening by itself. */
const KEY = 'wall-voice'
/** The answer stays on screen this long after the conversation has gone quiet. */
const SHOW_MS = 9000
/** Between tries to set the voice up again after the connection was lost: 2 s, then doubling to half a minute. */
const FIRST_RETRY_MS = 2000
const LAST_RETRY_MS = 30_000

const CONNECTION_LOST = 'The voice connection was lost.'
const COULD_NOT_CONNECT = 'The voice connection could not be opened'

function remembered(): boolean {
  try {
    return localStorage.getItem(KEY) === 'on'
  } catch {
    return false
  }
}

function remember(on: boolean): void {
  try {
    localStorage.setItem(KEY, on ? 'on' : 'off')
  } catch {
    // private browsing: the choice just does not last
  }
}

interface Turn {
  said?: string
  reply?: string
  notice?: string
}

const LABELS: Record<string, string> = {
  idle: 'Reconnecting…', // only shown while voice is meant to be on: the connection was lost and is being set up again
  connecting: 'Starting voice…',
  waiting: 'Say “Hey Jarvis”',
  listening: 'Listening…',
  hearing: 'Listening…',
  transcribing: 'One moment…',
  thinking: 'Thinking…',
  speaking: 'Speaking…',
}

/**
 * Voice for the door screen: it listens for the wake word on the device itself, and only then takes a sentence.
 * Turned on with one tap (a browser rule), and from then on it starts by itself and sets itself up again after a lost
 * connection. A small pill shows what it is doing; what was said and answered appears above it for a few seconds.
 */
export function WallVoice() {
  const status = useVoiceStatus().data
  const sensitivity = useVoiceSettings().data?.wakeSensitivity
  const [enabled, setEnabled] = useState(remembered)
  const [problem, setProblem] = useState<string | null>(null)
  const [turn, setTurn] = useState<Turn | null>(null)
  const retryIn = useRef(0)

  const { state, level, client, wake } = useVoice(
    {
      onTranscript: (said) => setTurn({ said }),
      onReply: (r) => setTurn((t) => ({ ...t, reply: r.reply })),
      onNotice: (notice) => setTurn({ notice }),
      onWake: () => setTurn(null),
      onError: (message) => {
        // A lost connection is mended below, without bothering anyone.
        if (message !== CONNECTION_LOST && message !== COULD_NOT_CONNECT) setProblem(message)
      },
    },
    { wakeWord: true },
  )

  const usable = !!status && status.available && status.listening

  // The sensitivity is set in Settings, once for every screen.
  useEffect(() => wake?.setThreshold(wakeThreshold(sensitivity)), [wake, sensitivity])

  // Start by itself, and again after the connection was lost; back off while the server is not answering.
  useEffect(() => {
    if (state === 'waiting') retryIn.current = 0
    if (!usable || !enabled || problem || state !== 'idle') return
    const timer = window.setTimeout(() => void client.start('wake'), retryIn.current)
    retryIn.current = Math.min(LAST_RETRY_MS, retryIn.current === 0 ? FIRST_RETRY_MS : retryIn.current * 2)
    return () => window.clearTimeout(timer)
  }, [usable, enabled, problem, state, client])

  // The conversation fades away once it has gone quiet.
  const quiet = state === 'waiting' || state === 'idle' || state === 'listening'
  useEffect(() => {
    if (!turn || !quiet) return
    const timer = window.setTimeout(() => setTurn(null), SHOW_MS)
    return () => window.clearTimeout(timer)
  }, [turn, quiet])

  if (!usable) return null

  const turnOn = () => {
    remember(true)
    setEnabled(true)
    setProblem(null)
    retryIn.current = 0
    void client.start('wake')
  }
  const turnOff = () => {
    remember(false)
    setEnabled(false)
    setProblem(null)
    client.stop()
  }
  const onTap = () => {
    if (!enabled || problem) turnOn()
    else if (state === 'speaking') client.interrupt()
    else turnOff()
  }

  const label = problem ? `${problem} Tap to try again.` : !enabled ? 'Tap to turn on voice' : (LABELS[state] ?? 'Voice')
  const live = enabled && !problem && ['listening', 'hearing'].includes(state)
  const hint = !enabled ? 'Turn voice on' : state === 'speaking' ? 'Stop the answer' : problem ? 'Try again' : 'Turn voice off'

  return (
    <div className="wall-voice">
      {turn && (turn.said || turn.reply || turn.notice) && (
        <div className="voice-card" role="status">
          {turn.said && <p className="voice-said">“{turn.said}”</p>}
          {turn.reply && <p className="voice-reply">{turn.reply}</p>}
          {turn.notice && !turn.reply && <p className="voice-reply muted">{turn.notice}</p>}
        </div>
      )}
      <button
        type="button"
        className={`voice-pill ${enabled && !problem ? state : 'off'}${live ? ' live' : ''}`}
        style={{ '--level': level } as React.CSSProperties}
        onClick={onTap}
        aria-label={`${label}. ${hint}.`}
      >
        <MicIcon />
        <span>{label}</span>
      </button>
    </div>
  )
}
