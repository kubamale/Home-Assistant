import { Link } from 'react-router-dom'
import { GearIcon } from '../components/Icons'
import { useState } from 'react'
import { CalendarCard } from '../components/CalendarCard'
import { DevicePanel } from '../components/DevicePanel'
import { QuickAccessCard } from '../components/QuickAccessCard'
import { TasksCard } from '../components/TasksCard'
import { WeatherCard } from '../components/WeatherCard'
import { ConnectionNotice } from '../components/StatusBar'
import { formatDate } from '../format'
import { useZone } from '../hooks/data'
import { useNow } from '../hooks/useClock'

/** The phone dashboard: one scrolling column of cards. */
export function Dashboard() {
  const now = useNow()
  const zone = useZone()
  const [panel, setPanel] = useState<string | null>(null)
  return (
    <>
      <ConnectionNotice />
      <main className="phone tabbed">
        <header className="phone-header">
          <div>
            <h1>{formatDate(now, zone)}</h1>
          </div>
          <Link className="icon-link" to="/settings" aria-label="Settings">
            <GearIcon />
          </Link>
        </header>
        <WeatherCard />
        <QuickAccessCard hint onOpenDevice={setPanel} />
        <CalendarCard />
        <TasksCard editable />
      </main>
      {panel && <DevicePanel deviceId={panel} onClose={() => setPanel(null)} />}
    </>
  )
}
