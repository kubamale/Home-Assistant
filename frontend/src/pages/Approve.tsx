import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useIsAdmin, useSession, useUsers } from '../hooks/data'

/** Opened from the QR code on an unpaired door screen. The user is already signed in on the phone. */
export function Approve() {
  const [params] = useSearchParams()
  const [code, setCode] = useState(params.get('code') ?? '')
  const [label, setLabel] = useState('Door screen')
  const admin = useIsAdmin()
  const session = useSession().data
  const { data: people } = useUsers({ enabled: admin })
  const [owner, setOwner] = useState<number | null>(null)
  const ownerId = owner ?? session?.userId ?? null
  const ownerName = (people ?? []).find((p) => p.id === ownerId)?.name ?? session?.name
  const [state, setState] = useState<'idle' | 'busy' | 'done'>('idle')
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setState('busy')
    setError(null)
    try {
      await api.pairApprove(code, label, ownerId ?? undefined)
      setState('done')
    } catch (err) {
      setState('idle')
      setError(
        err instanceof ApiError && err.status === 404
          ? 'That code is wrong or has expired. Check the screen for a new one.'
          : err instanceof ApiError && err.status === 429
            ? 'Too many wrong codes. Try again in a few minutes.'
            : 'Could not approve the screen.',
      )
    }
  }

  if (state === 'done') {
    return (
      <main className="narrow">
        <h1>Screen paired</h1>
        <p>The door screen will open in a moment.</p>
        <Link className="button" to="/">Back to home</Link>
      </main>
    )
  }

  return (
    <main className="narrow">
      <h1>Add display</h1>
      <form className="card form" onSubmit={submit}>
        <label>
          Code shown on the screen
          <input
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase())}
            autoCapitalize="characters"
            autoComplete="off"
            placeholder="XXXX-XXXX"
            maxLength={9}
          />
        </label>
        <label>
          Name
          <input value={label} onChange={(e) => setLabel(e.target.value)} maxLength={60} />
        </label>
        {admin && (people?.length ?? 0) > 1 ? (
          <label>
            Whose display is it?
            <select value={ownerId ?? ''} onChange={(e) => setOwner(Number(e.target.value))}>
              {(people ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
            <span className="muted small">It shows their calendar, tasks and pinned lights. They can arrange it from their own phone.</span>
          </label>
        ) : (
          ownerName && <p className="muted small">This display will be yours ({ownerName}).</p>
        )}
        {error && <p className="error">{error}</p>}
        <button className="button primary" type="submit" disabled={state === 'busy' || code.replace('-', '').length < 8}>
          Approve
        </button>
      </form>
      <Link className="muted" to="/settings">Back to settings</Link>
    </main>
  )
}
