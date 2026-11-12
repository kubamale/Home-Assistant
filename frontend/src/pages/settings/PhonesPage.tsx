import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api, ApiError } from '../../api'
import { useCredentials, useIsAdmin } from '../../hooks/data'
import { Group, SettingsPage } from './SettingsLayout'

export function PhonesPage() {
  const client = useQueryClient()
  const admin = useIsAdmin()
  const { data } = useCredentials()
  const phones = (data ?? []).filter((c) => c.kind === 'user')
  const [error, setError] = useState<string | null>(null)

  const remove = async (id: string) => {
    setError(null)
    try {
      await api.revokeCredential(id)
      await client.invalidateQueries({ queryKey: ['credentials'] })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not sign it out.')
    }
  }

  return (
    <SettingsPage title="Phones">
      <Group footnote="Every phone or browser that is signed in. Signing one out means it has to sign in again.">
        {phones.map((c) => (
          <div className="row static" key={c.id}>
            <span className="row-main">
              <span className="row-title">{c.current ? 'This phone' : 'Signed-in phone'}</span>
              <span className="row-sub">
                {admin && c.userName ? `${c.userName} · ` : ''}last used {c.lastUsedAt ? new Date(c.lastUsedAt).toLocaleDateString() : 'never'}
              </span>
            </span>
            {!c.current && (
              <button className="button danger compact" onClick={() => remove(c.id)}>
                Sign out
              </button>
            )}
          </div>
        ))}
      </Group>
      {error && <p className="error">{error}</p>}
    </SettingsPage>
  )
}
