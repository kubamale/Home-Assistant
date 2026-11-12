import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../../api'
import { ValueRow } from '../../components/Controls'
import { useSession } from '../../hooks/data'
import { Field, Group, SettingsPage } from './SettingsLayout'

export function AccountPage() {
  const client = useQueryClient()
  const navigate = useNavigate()
  const session = useSession().data
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const changePassword = async (e: FormEvent) => {
    e.preventDefault()
    if (!session) return
    setBusy(true)
    setMessage(null)
    try {
      await api.setUserPassword(session.userId, next, current)
      setMessage({ ok: true, text: 'Password changed. Your other phones were signed out.' })
      setCurrent('')
      setNext('')
    } catch (err) {
      setMessage({ ok: false, text: err instanceof ApiError ? err.message : 'Could not change the password.' })
    } finally {
      setBusy(false)
    }
  }

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
    <SettingsPage title="Account">
      <Group>
        <ValueRow title="Name" value={session?.name} />
        <ValueRow title="Role" value={session?.admin ? 'Admin' : 'Member'} />
      </Group>

      {session?.admin ? (
        <p className="footnote">The admin's password is the one set on the server (<code>APP_PASSWORD_HASH</code>), so it is changed there.</p>
      ) : (
        <form onSubmit={changePassword} className="stack">
          <Group title="Change password" footnote="Your other phones are signed out when you change it.">
            <Field label="Current password">
              <input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
            </Field>
            <Field label="New password" hint="At least 8 characters">
              <input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
            </Field>
            <div className="group-actions">
              <button className="button primary" type="submit" disabled={busy || current === '' || next.length < 8}>
                {busy ? 'Changing…' : 'Change password'}
              </button>
            </div>
          </Group>
          {message && <p className={message.ok ? 'ok' : 'error'}>{message.text}</p>}
        </form>
      )}

      <Group>
        <button type="button" className="row danger-row" onClick={logout}>
          <span className="row-title">Sign out</span>
        </button>
      </Group>
      {error && <p className="error">{error}</p>}
    </SettingsPage>
  )
}
