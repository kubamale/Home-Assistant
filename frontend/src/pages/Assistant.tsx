import { useEffect, useRef, useState, type CSSProperties, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import {
  assistantMessage,
  canSend,
  describeError,
  errorMessage,
  EXAMPLES,
  userMessage,
  voiceStatusText,
  type ChatMessage,
} from '../assistantModel'
import { GearIcon, MicIcon, StopIcon } from '../components/Icons'
import { ConnectionNotice } from '../components/StatusBar'
import { useConnection } from '../connection'
import { useVoiceStatus } from '../hooks/data'
import { useVoice } from '../hooks/useVoice'

const SPEAK_KEY = 'hd.voice.speak'
const HANDSFREE_KEY = 'hd.voice.handsfree'

/** A switch setting remembered on this device; storage can be unavailable (private windows), so it is only a convenience. */
function useStoredFlag(key: string, initial: boolean): [boolean, (value: boolean) => void] {
  const [value, setValue] = useState(() => {
    try {
      const stored = window.localStorage.getItem(key)
      return stored === null ? initial : stored === '1'
    } catch {
      return initial
    }
  })
  const set = (next: boolean) => {
    setValue(next)
    try {
      window.localStorage.setItem(key, next ? '1' : '0')
    } catch {
      // not remembered
    }
  }
  return [value, set]
}

/**
 * Talk or type what you want in your own words ("turn off the background light", "what's the weather?"); the
 * server works out what that means, does it, and answers, in text and, when asked, out loud. The lights, scenes
 * and tasks it changes update everywhere through the usual change events.
 */
export function Assistant() {
  const { data: status } = useVoiceStatus()
  const offline = useConnection() === 'reconnecting'
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [pending, setPending] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [spokenAnswers, setSpokenAnswers] = useStoredFlag(SPEAK_KEY, true)
  const [handsFree, setHandsFree] = useStoredFlag(HANDSFREE_KEY, false)
  const nextId = useRef(1)
  const end = useRef<HTMLDivElement>(null)

  const add = (message: (id: number) => ChatMessage) => setMessages((m) => [...m, message(nextId.current++)])

  const voice = useVoice({
    onTranscript: (spoken) => add((id) => userMessage(id, spoken, true)),
    onReply: (reply) => {
      setConversationId(reply.conversationId)
      add((id) => assistantMessage(id, reply))
    },
    onNotice: setNotice,
    onError: (message) => add((id) => errorMessage(id, message)),
  })

  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [messages, pending, voice.state])

  useEffect(() => {
    voice.client.setConversation(conversationId)
  }, [voice.client, conversationId])

  useEffect(() => {
    voice.client.speak = spokenAnswers
  }, [voice.client, spokenAnswers])

  useEffect(() => {
    if (voice.state === 'hearing' || voice.state === 'listening') setNotice(null)
  }, [voice.state])

  const unavailable = status !== undefined && !status.available
  const canListen = status?.listening === true
  const active = voice.state !== 'idle'
  const working = voice.state === 'transcribing' || voice.state === 'thinking'

  const send = async (sentence: string) => {
    if (!canSend(sentence) || pending || unavailable) return
    const asked = sentence.trim()
    setText('')
    setNotice(null)
    add((id) => userMessage(id, asked))
    setPending(true)
    try {
      const reply = await api.voiceCommand(asked, conversationId)
      setConversationId(reply.conversationId)
      add((id) => assistantMessage(id, reply))
    } catch (e) {
      // The server forgets a conversation that failed, so start the next one fresh too.
      setConversationId(null)
      add((id) => errorMessage(id, describeError(e)))
    } finally {
      setPending(false)
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void send(text)
  }

  const reset = () => {
    setMessages([])
    setConversationId(null)
    setNotice(null)
  }

  /** The microphone button: start listening, stop listening, or stop the answer, depending on what is going on. */
  const tapMic = () => {
    if (voice.state === 'idle') void voice.client.start(handsFree ? 'handsfree' : 'once')
    else if (voice.state === 'speaking') voice.client.interrupt()
    else if (voice.state === 'listening' || voice.state === 'hearing') voice.client.stop()
  }

  const toggleHandsFree = (next: boolean) => {
    setHandsFree(next)
    if (active) {
      voice.client.stop()
      if (next) void voice.client.start('handsfree') // still inside the tap that asked for it
    } else if (next && canListen) {
      void voice.client.start('handsfree')
    }
  }

  const statusLine = voiceStatusText(voice.state, handsFree)
  const micLabel =
    voice.state === 'speaking' ? 'Stop talking' : voice.state === 'listening' || voice.state === 'hearing' ? 'Stop listening' : 'Talk to the assistant'

  return (
    <>
      <ConnectionNotice />
      <main className="phone tabbed chat">
        <header className="phone-header">
          <h1>Assistant</h1>
          <div className="header-actions">
            {messages.length > 0 && (
              <button className="button compact" onClick={reset} disabled={pending || active}>
                New chat
              </button>
            )}
            <Link className="icon-link" to="/settings" aria-label="Settings">
              <GearIcon />
            </Link>
          </div>
        </header>

        {unavailable && (
          <section className="card">
            <h2>Not set up yet</h2>
            <p className="muted">
              The assistant uses Claude and needs an API key on the server: set <code>ANTHROPIC_API_KEY</code> and restart it. The rest of the app
              works without it.
            </p>
          </section>
        )}

        {messages.length === 0 && !unavailable && (
          <section className="chat-empty" aria-label="Examples">
            <p className="muted">Tap the microphone and talk, or type. Try:</p>
            <div className="chat-examples">
              {EXAMPLES.map((example) => (
                <button key={example} className="button compact" onClick={() => void send(example)} disabled={pending || offline}>
                  {example}
                </button>
              ))}
            </div>
            {status && !canListen && !unavailable && (
              <p className="muted small">Talking needs a <code>GEMINI_API_KEY</code> on the server; typing works without it.</p>
            )}
          </section>
        )}

        <div className="chat-thread" role="log" aria-live="polite">
          {messages.map((m) => (
            <div key={m.id} className={`chat-row ${m.role}`}>
              <div className={m.error ? 'bubble assistant error' : `bubble ${m.role}${m.spoken ? ' spoken' : ''}`}>
                {m.spoken && <MicIcon className="bubble-mic" />}
                {m.text}
              </div>
              {(m.actions.length > 0 || m.failed.length > 0) && (
                <ul className="chat-chips">
                  {m.actions.map((a) => (
                    <li key={a} className="chat-chip">
                      {a}
                    </li>
                  ))}
                  {m.failed.map((f) => (
                    <li key={f} className="chat-chip failed">
                      {f}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
          {(pending || working) && (
            <div className="chat-row assistant">
              <div className="bubble assistant typing" aria-label="The assistant is thinking">
                <span />
                <span />
                <span />
              </div>
            </div>
          )}
          <div ref={end} className="chat-end" />
        </div>

        <div className="chat-bar">
          {/* Always present, so the controls below do not jump when a status appears. */}
          <p className={statusLine ? 'voice-status live' : 'voice-status'} role="status">
            {statusLine || notice || '\u00a0'}
          </p>
          <div className="voice-options">
            <label className="voice-option">
              <button
                type="button"
                role="switch"
                aria-checked={handsFree}
                aria-label="Hands-free"
                className={handsFree ? 'switch on' : 'switch'}
                onClick={() => toggleHandsFree(!handsFree)}
                disabled={!canListen}
              >
                <span className="knob" />
              </button>
              Hands-free
            </label>
            <label className="voice-option">
              <button
                type="button"
                role="switch"
                aria-checked={spokenAnswers}
                aria-label="Spoken answers"
                className={spokenAnswers ? 'switch on' : 'switch'}
                onClick={() => setSpokenAnswers(!spokenAnswers)}
                disabled={!status?.speaking}
              >
                <span className="knob" />
              </button>
              Spoken answers
            </label>
          </div>
          <form className="chat-input" onSubmit={submit}>
            <input
              type="text"
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={unavailable ? 'Assistant not set up' : 'Ask or tell me something'}
              aria-label="Message"
              maxLength={500}
              autoComplete="off"
              enterKeyHint="send"
              disabled={unavailable}
            />
            <button
              type="button"
              className={`mic-button ${voice.state}`}
              style={{ '--level': voice.level } as CSSProperties}
              aria-label={micLabel}
              aria-pressed={voice.state === 'listening' || voice.state === 'hearing'}
              disabled={!canListen || offline || working || voice.state === 'connecting'}
              title={canListen ? micLabel : 'Voice needs a GEMINI_API_KEY on the server'}
              onClick={tapMic}
            >
              {voice.state === 'speaking' ? <StopIcon /> : <MicIcon />}
            </button>
            <button className="button primary" type="submit" disabled={!canSend(text) || pending || unavailable || offline}>
              Send
            </button>
          </form>
        </div>
      </main>
    </>
  )
}
