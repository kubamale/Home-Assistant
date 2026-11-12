import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { api } from '../../api'
import { ValueRow } from '../../components/Controls'
import { useSettings } from '../../hooks/data'
import type { Place } from '../../types'
import { Field, Group, SettingsPage } from './SettingsLayout'

export function LocationPage() {
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
      await api.saveLocation({ name: place.name, latitude: place.latitude, longitude: place.longitude, timezone: place.timezone })
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
    <SettingsPage title="Location">
      <Group footnote="The weather and what “today” means follow this place, for everyone in the home.">
        <ValueRow title="Place" value={current ? current.name : 'Not set'} />
        {current && <ValueRow title="Timezone" value={current.timezone} />}
      </Group>
      <Group title="Change it">
        <Field label="Search for a place">
          <input value={query} placeholder="City or town" autoComplete="off" onChange={(e) => { setQuery(e.target.value); setSaved(null) }} />
        </Field>
        {results.map((p) => (
          <button key={`${p.latitude},${p.longitude}`} type="button" className="row" onClick={() => choose(p)}>
            <span className="row-main">
              <span className="row-title">{p.name}</span>
              <span className="row-sub">{[p.region, p.country].filter(Boolean).join(', ')}</span>
            </span>
          </button>
        ))}
        {searching && <p className="row static muted">Searching…</p>}
      </Group>
      {error && <p className="error">{error}</p>}
      {saved && <p className="ok">Location set to {saved}.</p>}
    </SettingsPage>
  )
}
