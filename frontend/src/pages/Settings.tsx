import { Link } from 'react-router-dom'
import { useCalendarConnection, useCredentials, useDevices, useIsAdmin, useRooms, useSession, useSettings, useUsers, useVoiceSettings } from '../hooks/data'
import { useBackgroundMode } from '../hooks/background'
import { Group, NavRow } from './settings/SettingsLayout'
import { BACKGROUND_LABELS } from './settings/AppearancePage'

/**
 * The Settings menu: a short list of rows, each with a summary of what it holds, that open their own page. Nothing is edited
 * here, so the screen is never long and every row is one tap away.
 */
export function Settings() {
  const admin = useIsAdmin()
  const session = useSession().data
  const { data: calendar } = useCalendarConnection()
  const { data: credentials } = useCredentials()
  const rooms = useRooms().data ?? []
  const { data: settings } = useSettings()
  const { data: voice } = useVoiceSettings()
  const { data: people } = useUsers({ enabled: admin })
  const { data: devices } = useDevices()
  const [background] = useBackgroundMode()

  const displays = (credentials ?? []).filter((c) => c.kind === 'display')
  const phones = (credentials ?? []).filter((c) => c.kind === 'user')
  const agents = (credentials ?? []).filter((c) => c.kind === 'agent')

  const calendarValue = !calendar ? '' : calendar.connected ? (calendar.username ?? 'Connected') : calendar.serverAccount ? 'Server calendar' : 'Not connected'
  const roomName = (id: number | null) => rooms.find((r) => r.id === id)?.name ?? 'No room'
  const agentValue = agents.length === 0 ? 'Not set up' : devices?.agentOnline ? 'Online' : 'Offline'

  return (
    <main className="phone settings">
      <header className="phone-header">
        <h1>Settings</h1>
        <Link className="text-link" to="/">
          Done
        </Link>
      </header>

      <Group>
        <NavRow to="/settings/account" title="Account" value={session?.name} />
        <NavRow to="/settings/calendar" title="My calendar" value={calendarValue} />
        <NavRow to="/settings/appearance" title="Appearance" value={BACKGROUND_LABELS[background]} />
      </Group>

      <Group title={admin ? 'Displays' : 'My displays'} footnote="A display shows its owner's calendar, tasks and pinned lights. Tap one to arrange what it shows.">
        {displays.map((d) => (
          <NavRow
            key={d.id}
            to={`/settings/display/${d.id}`}
            title={d.label}
            subtitle={`${roomName(d.roomId)}${admin && d.userName ? ` · ${d.userName}` : ''}`}
          />
        ))}
        {displays.length === 0 && <p className="row static muted">No display yet.</p>}
        <NavRow to="/approve" title="Add a display" />
      </Group>

      {admin && (
        <Group title="Home">
          <NavRow to="/settings/location" title="Location" value={settings?.location?.name ?? 'Not set'} />
          <NavRow to="/settings/voice" title="Voice" value={voice?.voice} />
          <NavRow to="/settings/people" title="People" value={people ? `${people.length}` : undefined} />
          <NavRow to="/settings/agent" title="Home agent" value={agentValue} />
        </Group>
      )}

      <Group title="Signed in">
        <NavRow to="/settings/phones" title="Phones" value={`${phones.length}`} />
      </Group>
    </main>
  )
}
