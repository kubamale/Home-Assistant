import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { assistantMessage, canSend, describeError, errorMessage, EXAMPLES, userMessage, type ChatMessage } from '../assistantModel'
import { GearIcon } from '../components/Icons'
import { ConnectionNotice } from '../components/StatusBar'
import { useConnection } from '../connection'
import { useVoiceStatus } from '../hooks/data'

/**
 * Type what you want in your own words ("turn off the background light", "what's the weather?"); the server
 * works out what that means, does it, and answers. The lights, scenes and tasks it changes update everywhere
 * through the usual change events.
 */
export function Assistant() {
  const { data: status } = useVoiceStatus()
  const offline = useConnection() === 'reconnecting'
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [conversationId, setConversationId] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [pending, setPending] = useState(false)
  const nextId = useRef(1)
  const end = useRef<HTMLDivElement>(null)

  useEffect(() => {
    end.current?.scrollIntoView({ block: 'end', behavior: 'smooth' })
  }, [messages, pending])

  const unavailable = status !== undefined && !status.available

  const send = async (sentence: string) => {
    if (!canSend(sentence) || pending || unavailable) return
    const asked = sentence.trim()
    setText('')
    setMessages((m) => [...m, userMessage(nextId.current++, asked)])
    setPending(true)
    try {
      const reply = await api.voiceCommand(asked, conversationId)
      setConversationId(reply.conversationId)
      setMessages((m) => [...m, assistantMessage(nextId.current++, reply)])
    } catch (e) {
      // The server forgets a conversation that failed, so start the next one fresh too.
      setConversationId(null)
      setMessages((m) => [...m, errorMessage(nextId.current++, describeError(e))])
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
  }

  return (
    <>
      <ConnectionNotice />
      <main className="phone tabbed chat">
        <header className="phone-header">
          <h1>Assistant</h1>
          <div className="header-actions">
            {messages.length > 0 && (
              <button className="button compact" onClick={reset} disabled={pending}>
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
            <p className="muted">Ask in your own words, in English or Polish. Try:</p>
            <div className="chat-examples">
              {EXAMPLES.map((example) => (
                <button key={example} className="button compact" onClick={() => void send(example)} disabled={pending || offline}>
                  {example}
                </button>
              ))}
            </div>
          </section>
        )}

        <div className="chat-thread" role="log" aria-live="polite">
          {messages.map((m) => (
            <div key={m.id} className={`chat-row ${m.role}`}>
              <div className={m.error ? 'bubble assistant error' : `bubble ${m.role}`}>{m.text}</div>
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
          {pending && (
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
          <button className="button primary" type="submit" disabled={!canSend(text) || pending || unavailable || offline}>
            Send
          </button>
        </form>
      </main>
    </>
  )
}
