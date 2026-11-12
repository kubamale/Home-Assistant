import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../../api'
import { ValueRow } from '../../components/Controls'
import { LayoutEditor } from '../../components/LayoutEditor'
import { useCredentials, useIsAdmin, useRooms, useUsers } from '../../hooks/data'
import { Group, SettingsPage } from './SettingsLayout'

/** One wall display: whose it is, which room it is in, what it shows, and removing it. */
export function DisplayPage() {
  const { id = '' } = useParams()
  const client = useQueryClient()
  const navigate = useNavigate()
  const admin = useIsAdmin()
  const { data: credentials } = useCredentials()
  const rooms = useRooms().data ?? []
  const { data: people } = useUsers({ enabled: admin })
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  const display = (credentials ?? []).find((c) => c.id === id && c.kind === 'display')
  if (!display) {
    return (
      <SettingsPage title="Display">
        <p className="muted">{credentials ? 'This display was removed.' : 'Loading…'}</p>
      </SettingsPage>
    )
  }

  const attempt = async (job: () => Promise<unknown>) => {
    setError(null)
    try {
      await job()
      await client.invalidateQueries({ queryKey: ['credentials'] })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'That did not work.')
    }
  }
  const remove = () =>
    attempt(async () => {
      await api.revokeCredential(display.id)
      navigate('/settings', { replace: true })
    })

  return (
    <SettingsPage title={display.label}>
      <Group footnote="Said to this display, “the light” and “all the lights” mean the lights of its room.">
        <div className="row static">
          <span className="row-title">Room</span>
          <select aria-label={`Room of ${display.label}`} value={display.roomId ?? ''} onChange={(e) => attempt(() => api.setDisplayRoom(display.id, e.target.value === '' ? null : Number(e.target.value)))}>
            <option value="">No room</option>
            {rooms.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
        </div>
        {admin && (people?.length ?? 0) > 1 ? (
          <div className="row static">
            <span className="row-title">Belongs to</span>
            <select aria-label={`Owner of ${display.label}`} value={display.userId ?? ''} onChange={(e) => attempt(() => api.setDisplayUser(display.id, Number(e.target.value)))}>
              {(people ?? []).map((p) => (
                <option key={p.id} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
        ) : (
          <ValueRow title="Belongs to" value={display.userName ?? ''} />
        )}
        <ValueRow title="Last used" value={display.lastUsedAt ? new Date(display.lastUsedAt).toLocaleDateString() : 'never'} />
      </Group>
      {error && <p className="error">{error}</p>}

      <LayoutEditor credentialId={display.id} />

      <Group footnote="The screen goes back to showing a pairing code.">
        {confirming ? (
          <div className="row static">
            <span className="row-title">Remove this display?</span>
            <span className="inline-buttons">
              <button className="button compact" onClick={() => setConfirming(false)}>
                Keep
              </button>
              <button className="button danger compact" onClick={remove}>
                Remove
              </button>
            </span>
          </div>
        ) : (
          <button type="button" className="row danger-row" onClick={() => setConfirming(true)}>
            <span className="row-title">Remove this display</span>
          </button>
        )}
      </Group>
    </SettingsPage>
  )
}
