import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '../../api'
import { ValueRow } from '../../components/Controls'
import { useCalendarConnection } from '../../hooks/data'
import { Field, Group, SettingsPage } from './SettingsLayout'

/** Where the signed-in person's calendar comes from. The form only appears when there is something to connect or change. */
export function CalendarPage() {
  const client = useQueryClient()
  const { data: connection } = useCalendarConnection()
  const [editing, setEditing] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [names, setNames] = useState('')
  const [url, setUrl] = useState('')

  const refresh = () => Promise.all([client.invalidateQueries({ queryKey: ['calendarConnection'] }), client.invalidateQueries({ queryKey: ['calendar'] })])
  const connect = useMutation({
    mutationFn: () =>
      api.connectCalendar({
        url: url.trim() || undefined,
        username: username.trim(),
        password,
        calendars: names.split(',').map((n) => n.trim()).filter(Boolean),
      }),
    onSuccess: async () => {
      setPassword('')
      setEditing(false)
      await refresh()
    },
  })
  const disconnect = useMutation({ mutationFn: api.disconnectCalendar, onSuccess: refresh })

  if (!connection) return <SettingsPage title="My calendar">{null}</SettingsPage>
  const showForm = !connection.connected || editing
  const status = connection.connected ? 'Connected' : connection.serverAccount ? 'Server calendar' : 'Not connected'

  const submit = (e: FormEvent) => {
    e.preventDefault()
    connect.mutate()
  }

  return (
    <SettingsPage title="My calendar">
      <Group
        footnote={
          connection.connected
            ? 'Shown on your phone and on your displays.'
            : connection.serverAccount
              ? "Showing the calendar set up on the server. Connect your own to replace it."
              : 'Connect yours and it shows on your phone and on your displays.'
        }
      >
        <ValueRow title="Status" value={status} />
        {connection.connected && <ValueRow title="Account" value={connection.username} />}
        {connection.connected && connection.calendars.length > 0 && <ValueRow title="Calendars" value={connection.calendars.join(', ')} />}
      </Group>

      {showForm ? (
        <form onSubmit={submit} className="stack">
          <Group title={connection.connected ? 'New account' : 'Connect a calendar'}>
            <Field label="Account" hint="For iCloud, your Apple ID email">
              <input type="email" autoComplete="off" autoCapitalize="none" value={username} onChange={(e) => setUsername(e.target.value)} />
            </Field>
            <Field label="App-specific password" hint="Not your Apple ID password. Make one in your Apple account under Sign-In and Security, then App-Specific Passwords.">
              <input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
            </Field>
            <Field label="Only these calendars" hint="Optional. Names separated by commas; empty shows all of them.">
              <input type="text" value={names} placeholder="All of them" onChange={(e) => setNames(e.target.value)} />
            </Field>
            <Field label="Calendar address" hint="Only if it is not iCloud.">
              <input type="url" value={url} placeholder="https://caldav.icloud.com/" onChange={(e) => setUrl(e.target.value)} />
            </Field>
            <div className="group-actions">
              <button className="button primary" type="submit" disabled={connect.isPending || username.trim() === '' || password === ''}>
                {connect.isPending ? 'Checking…' : 'Connect'}
              </button>
              {connection.connected && (
                <button type="button" className="button" onClick={() => setEditing(false)}>
                  Cancel
                </button>
              )}
            </div>
          </Group>
          <p className="footnote">The password is stored encrypted, and the app only ever reads your calendar.</p>
          {connect.error && <p className="error">{connect.error.message}</p>}
        </form>
      ) : (
        <Group>
          <button type="button" className="row" onClick={() => setEditing(true)}>
            <span className="row-title accent-text">Change account</span>
          </button>
          <button type="button" className="row danger-row" disabled={disconnect.isPending} onClick={() => disconnect.mutate()}>
            <span className="row-title">Disconnect</span>
          </button>
        </Group>
      )}
      {disconnect.error && <p className="error">{disconnect.error.message}</p>}
    </SettingsPage>
  )
}
