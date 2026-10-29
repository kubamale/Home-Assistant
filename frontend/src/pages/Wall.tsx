import { useCallback, useEffect, useState } from 'react'
import { CalendarCard } from '../components/CalendarCard'
import { DevicePanel } from '../components/DevicePanel'
import { QuickAccessCard } from '../components/QuickAccessCard'
import { TasksCard } from '../components/TasksCard'
import { WallDevices } from '../components/WallDevices'
import { WallVoice } from '../components/WallVoice'
import { WeatherCard } from '../components/WeatherCard'
import { ConnectionNotice } from '../components/StatusBar'
import { formatClock, formatDate } from '../format'
import { useZone } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import { useIdle } from '../hooks/useIdle'

/** How long the devices list or a device panel stays up without a touch before the screen returns to the dashboard. */
const DEVICES_IDLE_MS = 30_000

/**
 * The door-screen layout: fixed to the viewport, large text, large touch targets, no scrolling.
 * Always dark so it is comfortable in a hallway at night.
 */
export function Wall() {
  const now = useNow()
  const zone = useZone()
  const [view, setView] = useState<'dashboard' | 'devices'>('dashboard')
  const [panel, setPanel] = useState<string | null>(null)
  const showDashboard = useCallback(() => {
    setView('dashboard')
    setPanel(null)
  }, [])
  const closePanel = useCallback(() => setPanel(null), [])
  useIdle(view === 'devices' || panel !== null, DEVICES_IDLE_MS, showDashboard)

  useEffect(() => {
    document.documentElement.classList.add('wall-mode')
    document.documentElement.dataset.theme = 'dark'
    return () => {
      document.documentElement.classList.remove('wall-mode')
      delete document.documentElement.dataset.theme
    }
  }, [])

  return (
    <div className="wall">
      <ConnectionNotice />
      {view === 'devices' ? (
        <WallDevices onBack={showDashboard} onOpen={setPanel} />
      ) : (
        <div className="wall-grid">
          <div className="wall-left">
            <div className="wall-clock">
              <div className="clock">{formatClock(now, zone)}</div>
              <div className="date">{formatDate(now, zone)}</div>
            </div>
            <WeatherCard hours={5} />
          </div>
          <div className="wall-middle">
            <CalendarCard limit={5} />
          </div>
          <div className="wall-right">
            <TasksCard limit={6} />
            <QuickAccessCard onAllDevices={() => setView('devices')} onOpenDevice={setPanel} />
          </div>
        </div>
      )}
      {panel && <DevicePanel deviceId={panel} onClose={closePanel} />}
      <WallVoice />
    </div>
  )
}
