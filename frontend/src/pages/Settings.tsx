import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useRooms, useSaveVoiceSettings, useSettings, useVoiceSettings, useVoiceStatus, useVoiceVoices } from '../hooks/data'
import type { Place, VoiceSettings } from '../types'

export function Settings() {
  return (
    <main className="phone settings">
      <header className="phone-header">
        <h1>Settings</h1>
        <Link className="text-link" to="/">
          Done
        </Link>
      </header>
      <LocationSection />
      <VoiceSection />
      <DisplaysSection />
      <AgentSection />
      <AccountSection />
    </main>
  )
}

function LocationSection() {
  const { data: settings } = useSettings()
  const client = useQueryClient()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Place[]>([])
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)

  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults([])
      return
    }
    let cancelled = false
    const timer = window.setTimeout(async () => {
      setSearching(true)
      setError(null)
      try {
        const found = await api.geocode(q)
        if (!cancelled) setResults(found)
      } catch {
        if (!cancelled) setError('Place search is unavailable right now.')
      } finally {
        if (!cancelled) setSearching(false)
      }
    }, 350)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [query])

  const choose = async (place: Place) => {
    setError(null)
    try {
      await api.saveLocation({
        name: place.name,
        latitude: place.latitude,
        longitude: place.longitude,
        timezone: place.timezone,
      })
      setSaved(place.name)
      setQuery('')
      setResults([])
      await client.invalidateQueries()
    } catch {
      setError('Could not save the location.')
    }
  }

  const current = settings?.location
  return (
    <section className="card">
      <h2>Home location</h2>
      <p className="muted">
        {current ? (
          <>
            {current.name} · {current.timezone}
          </>
        ) : (
          'Not set. Weather and the date use this.'
        )}
      </p>
      <label className="form">
        Search for a place
        <input
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setSaved(null)
          }}
          placeholder="City or town"
          autoComplete="off"
        />
      </label>
      {searching && <p className="muted small">Searching…</p>}
      {error && <p className="error">{error}</p>}
      {saved && <p className="ok">Location set to {saved}.</p>}
      <ul className="place-list">
        {results.map((p) => (
          <li key={`${p.latitude},${p.longitude}`}>
            <button className="place" onClick={() => choose(p)}>
              <strong>{p.name}</strong>
              <span>{[p.region, p.country].filter(Boolean).join(', ')}</span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

function DisplaysSection() {
  const client = useQueryClient()
  const { data } = useQuery({ queryKey: ['credentials'], queryFn: api.credentials })
  const sessions = (data ?? []).filter((c) => c.kind !== 'agent')
  const rooms = useRooms().data ?? []

  const revoke = async (id: string) => {
    await api.revokeCredential(id)
    await client.invalidateQueries({ queryKey: ['credentials'] })
  }

  const setRoom = async (id: string, value: string) => {
    await api.setDisplayRoom(id, value === '' ? null : Number(value))
    await client.invalidateQueries({ queryKey: ['credentials'] })
  }

  return (
    <section className="card">
      <h2>Phones and displays</h2>
      <p className="muted small">
        Give a display its room: when you talk to it, “the light” and “all the lights” mean that room's lights.
      </p>
      <ul className="credential-list">
        {sessions.map((c) => (
          <li key={c.id}>
            <div>
              <strong>{c.kind === 'display' ? c.label : c.current ? 'This phone' : 'Signed-in phone'}</strong>
              <span className="muted small">
                {c.kind === 'display' ? 'Display' : 'Phone'} · last used{' '}
                {c.lastUsedAt ? new Date(c.lastUsedAt).toLocaleDateString() : 'never'}
              </span>
              {c.kind === 'display' && (
                <select
                  aria-label={`Room of ${c.label}`}
                  value={c.roomId ?? ''}
                  onChange={(e) => setRoom(c.id, e.target.value)}
                >
                  <option value="">No room</option>
                  {rooms.map((r) => (
                    <option key={r.id} value={r.id}>
                      {r.name}
                    </option>
                  ))}
                </select>
              )}
            </div>
            {!c.current && (
              <button className="button danger" onClick={() => revoke(c.id)}>
                Remove
              </button>
            )}
          </li>
        ))}
      </ul>
      <Link className="button" to="/approve">
        Add display
      </Link>
    </section>
  )
}

function AgentSection() {
  const client = useQueryClient()
  const { data } = useQuery({ queryKey: ['credentials'], queryFn: api.credentials })
  const agents = (data ?? []).filter((c) => c.kind === 'agent')
  const [token, setToken] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const create = async () => {
    setError(null)
    try {
      const created = await api.createAgentToken('Home agent')
      setToken(created.token)
      await client.invalidateQueries({ queryKey: ['credentials'] })
    } catch {
      setError('Could not create a token.')
    }
  }

  const revoke = async (id: string) => {
    await api.revokeCredential(id)
    await client.invalidateQueries({ queryKey: ['credentials'] })
  }

  return (
    <section className="card">
      <h2>Home agent</h2>
      <p className="muted small">The small program on the Raspberry Pi that talks to your lights.</p>
      <ul className="credential-list">
        {agents.map((c) => (
          <li key={c.id}>
            <div>
              <strong>{c.label}</strong>
              <span className="muted small">
                Last seen {c.lastUsedAt ? new Date(c.lastUsedAt).toLocaleString() : 'never'}
              </span>
            </div>
            <button className="button danger" onClick={() => revoke(c.id)}>
              Revoke
            </button>
          </li>
        ))}
      </ul>
      {token && (
        <div className="token-box">
          <p className="small">Copy this token into the agent's config. It is shown only once.</p>
          <code>{token}</code>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <button className="button" onClick={create}>
        Create agent token
      </button>
    </section>
  )
}

function AccountSection() {
  const client = useQueryClient()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  const logout = async () => {
    try {
      await api.logout()
    } catch (e) {
      if (!(e instanceof ApiError)) {
        setError('Could not sign out.')
        return
      }
    }
    await client.invalidateQueries({ queryKey: ['session'] })
    client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'session' })
    navigate('/login', { replace: true })
  }

  return (
    <section className="card">
      <h2>Account</h2>
      {error && <p className="error">{error}</p>}
      <button className="button danger" onClick={logout}>
        Sign out
      </button>
    </section>
  )
}

/** The languages the household speaks, as hints for the listening service. */
const LANGUAGES = [
  { code: 'pl-PL', label: 'Polish' },
  { code: 'en-US', label: 'English' },
]

const SENSITIVITIES: { value: VoiceSettings['wakeSensitivity']; label: string }[] = [
  { value: 'low', label: 'Less sensitive' },
  { value: 'normal', label: 'Normal' },
  { value: 'high', label: 'More sensitive' },
]

function VoiceSection() {
  const { data: saved } = useVoiceSettings()
  const { data: voices } = useVoiceVoices()
  const { data: status } = useVoiceStatus()
  const save = useSaveVoiceSettings()
  const [edits, setEdits] = useState<VoiceSettings | null>(null)
  const [playing, setPlaying] = useState(false)
  const [sampleError, setSampleError] = useState<string | null>(null)

  const draft = edits ?? saved
  if (!draft) return null
  const change = (patch: Partial<VoiceSettings>) => setEdits({ ...draft, ...patch })
  const toggleLanguage = (code: string) =>
    change({ languages: draft.languages.includes(code) ? draft.languages.filter((l) => l !== code) : [...draft.languages, code] })

  const play = async () => {
    setSampleError(null)
    setPlaying(true)
    try {
      const url = URL.createObjectURL(await api.voiceSample(draft.voice, draft.style))
      const audio = new Audio(url)
      audio.onended = () => URL.revokeObjectURL(url)
      await audio.play()
    } catch (e) {
      setSampleError(e instanceof ApiError ? e.message : 'The sample could not be played.')
    } finally {
      setPlaying(false)
    }
  }

  return (
    <section className="card">
      <h2>Voice</h2>
      {status && !status.speaking && (
        <p className="muted small">
          Talking and listening need a <code>GEMINI_API_KEY</code> on the server.
        </p>
      )}

      <fieldset className="voice-languages">
        <legend className="small muted">I speak (leave both off to detect it)</legend>
        {LANGUAGES.map((language) => (
          <label key={language.code} className="check-row">
            <input type="checkbox" checked={draft.languages.includes(language.code)} onChange={() => toggleLanguage(language.code)} />
            {language.label}
          </label>
        ))}
      </fieldset>

      <label className="field">
        Voice
        <div className="voice-pick">
          <select value={draft.voice} onChange={(e) => change({ voice: e.target.value })}>
            {(voices?.voices ?? [draft.voice]).map((voice) => (
              <option key={voice} value={voice}>
                {voice}
              </option>
            ))}
          </select>
          <button type="button" className="button compact" onClick={() => void play()} disabled={playing || !status?.speaking}>
            {playing ? 'Playing…' : 'Play sample'}
          </button>
        </div>
      </label>

      <label className="field">
        How to speak (optional)
        <input type="text" value={draft.style} maxLength={200} placeholder="for example: calm and friendly" onChange={(e) => change({ style: e.target.value })} />
      </label>

      <label className="check-row">
        <input type="checkbox" checked={draft.speakReplies} onChange={(e) => change({ speakReplies: e.target.checked })} />
        Speak the answers out loud
      </label>

      <label className="field">
        After the wake word, say
        <input type="text" value={draft.wakeReply} maxLength={40} placeholder="nothing" onChange={(e) => change({ wakeReply: e.target.value })} />
        <span className="muted small">So you know it is listening, for example “Yes?” or “Tak?”. Leave it empty for no sound. Only used when answers are spoken.</span>
      </label>

      <div className="field">
        Wake word “Hey Jarvis” on door screens
        <div className="segmented" role="group" aria-label="Wake word sensitivity">
          {SENSITIVITIES.map((option) => (
            <button type="button" key={option.value} aria-pressed={draft.wakeSensitivity === option.value} onClick={() => change({ wakeSensitivity: option.value })}>
              {option.label}
            </button>
          ))}
        </div>
        <span className="muted small">More sensitive if the screen does not hear you, less if it wakes up by itself. Say it in English.</span>
      </div>

      {sampleError && <p className="error small">{sampleError}</p>}
      {save.error && <p className="error small">{save.error.message}</p>}
      <button
        className="button primary"
        disabled={!edits || save.isPending}
        onClick={() => save.mutate(draft, { onSuccess: () => setEdits(null) })}
      >
        {save.isPending ? 'Saving…' : 'Save voice settings'}
      </button>

      <p className="muted small">
        What you say is sent to Google (Gemini) to be written down, and the answers to be spoken. On Google's free tier it may use those requests to
        improve its products; on a paid plan it does not, so use a paid key.
      </p>
    </section>
  )
}
