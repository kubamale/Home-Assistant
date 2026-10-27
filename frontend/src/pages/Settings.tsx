import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useSettings } from '../hooks/data'
import type { Place } from '../types'

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

  const revoke = async (id: string) => {
    await api.revokeCredential(id)
    await client.invalidateQueries({ queryKey: ['credentials'] })
  }

  return (
    <section className="card">
      <h2>Phones and displays</h2>
      <ul className="credential-list">
        {sessions.map((c) => (
          <li key={c.id}>
            <div>
              <strong>{c.kind === 'display' ? c.label : c.current ? 'This phone' : 'Signed-in phone'}</strong>
              <span className="muted small">
                {c.kind === 'display' ? 'Display' : 'Phone'} · last used{' '}
                {c.lastUsedAt ? new Date(c.lastUsedAt).toLocaleDateString() : 'never'}
              </span>
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
