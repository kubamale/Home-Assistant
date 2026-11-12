import { Fragment, useCallback, useEffect, useState } from 'react'
import { CalendarCard } from '../components/CalendarCard'
import { DevicePanel } from '../components/DevicePanel'
import { QuickAccessCard } from '../components/QuickAccessCard'
import { TasksCard } from '../components/TasksCard'
import { WallDevices } from '../components/WallDevices'
import { WallVoice } from '../components/WallVoice'
import { WeatherCard } from '../components/WeatherCard'
import { ConnectionNotice } from '../components/StatusBar'
import { countOf, DEFAULT_LAYOUT } from '../displayLayout'
import { formatClock, formatDate } from '../format'
import { useDisplayConfig, useZone } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import { useIdle } from '../hooks/useIdle'
import type { Widget } from '../types'

/** How long the devices list or a device panel stays up without a touch before the screen returns to the dashboard. */
const DEVICES_IDLE_MS = 30_000

/**
 * The door-screen layout: fixed to the viewport, large text, large touch targets, no scrolling.
 * Always dark so it is comfortable in a hallway at night. Which cards are on it, in which columns and how many
 * items each shows is the owner's choice (see the display's layout); until it has loaded the default is shown.
 */
export function Wall() {
  const now = useNow()
  const zone = useZone()
  const { data: config } = useDisplayConfig()
  const layout = config?.layout ?? DEFAULT_LAYOUT
  const [view, setView] = useState<'dashboard' | 'devices'>('dashboard')
  const [panel, setPanel] = useState<string | null>(null)
  const showDashboard = useCallback(() => {
    setView('dashboard')
    setPanel(null)
  }, [])
  const closePanel = useCallback(() => setPanel(null), [])
  useIdle(view === 'devices' || panel !== null, DEVICES_IDLE_MS, showDashboard)

  // The background's motion (clouds, rain, snow) is this display's choice; SkyBackground holds the layers.
  useEffect(() => {
    document.documentElement.dataset.motion = layout.motion ? 'on' : 'off'
    return () => {
      delete document.documentElement.dataset.motion
    }
  }, [layout.motion])

  useEffect(() => {
    document.documentElement.classList.add('wall-mode')
    document.documentElement.dataset.theme = 'dark'
    return () => {
      document.documentElement.classList.remove('wall-mode')
      delete document.documentElement.dataset.theme
    }
  }, [])

  const card = (widget: Widget) => {
    switch (widget.type) {
      case 'clock':
        return (
          <div className="wall-clock">
            <div className="clock">{formatClock(now, zone, widget.hour12 === true)}</div>
            <div className="date">{formatDate(now, zone)}</div>
          </div>
        )
      case 'weather':
        return <WeatherCard hours={countOf(widget)} />
      case 'calendar':
        return <CalendarCard limit={countOf(widget)} />
      case 'tasks':
        return <TasksCard limit={countOf(widget)} />
      case 'quickAccess':
        return <QuickAccessCard onAllDevices={() => setView('devices')} onOpenDevice={setPanel} />
    }
  }

  return (
    <div className="wall">
      <ConnectionNotice />
      {view === 'devices' ? (
        <WallDevices onBack={showDashboard} onOpen={setPanel} />
      ) : (
        <div className="wall-grid" style={{ gridTemplateColumns: `repeat(${layout.columns.length}, minmax(0, 1fr))` }}>
          {layout.columns.map((column, i) => (
            <div className="wall-col" key={i}>
              {column.widgets.map((widget) => (
                <Fragment key={widget.type}>{card(widget)}</Fragment>
              ))}
            </div>
          ))}
        </div>
      )}
      {panel && <DevicePanel deviceId={panel} onClose={closePanel} />}
      {layout.voice && <WallVoice />}
    </div>
  )
}
