import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../../api'
import { ValueRow } from '../../components/Controls'
import { useCredentials, useDevices } from '../../hooks/data'
import { Group, SettingsPage } from './SettingsLayout'

/** The small program on the Raspberry Pi that talks to the lights. */
export function AgentPage() {
  const client = useQueryClient()
  const { data } = useCredentials()
  const { data: devices } = useDevices()
  const agents = (data ?? []).filter((c) => c.kind === 'agent')
  const [token, setToken] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = () => client.invalidateQueries({ queryKey: ['credentials'] })
  const create = async () => {
    setError(null)
    try {
      const created = await api.createAgentToken('Home agent')
      setToken(created.token)
      await refresh()
    } catch {
      setError('Could not create a token.')
    }
  }
  const revoke = async (id: string) => {
    await api.revokeCredential(id)
    await refresh()
  }

  return (
    <SettingsPage title="Home agent">
      <Group footnote="The agent finds your lamps and carries out commands. Put the token in its config file on the Pi.">
        <ValueRow title="Status" value={agents.length === 0 ? 'Not set up' : devices?.agentOnline ? 'Online' : 'Offline'} />
        {agents.map((c) => (
          <div className="row static" key={c.id}>
            <span className="row-main">
              <span className="row-title">{c.label}</span>
              <span className="row-sub">Last seen {c.lastUsedAt ? new Date(c.lastUsedAt).toLocaleString() : 'never'}</span>
            </span>
            <button className="button danger compact" onClick={() => revoke(c.id)}>
              Revoke
            </button>
          </div>
        ))}
      </Group>

      {token && (
        <Group title="Copy this token now" footnote="It is shown only once. Put it in the agent's config file.">
          <div className="token-box">
            <code>{token}</code>
          </div>
        </Group>
      )}
      {error && <p className="error">{error}</p>}
      <Group>
        <button type="button" className="row" onClick={create}>
          <span className="row-title accent-text">Create an agent token</span>
        </button>
      </Group>
    </SettingsPage>
  )
}
