import { useEffect } from 'react'
import { useDevices } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import { availabilityNote, isUnavailable } from '../homeModel'
import { ChannelControls } from './ChannelControls'

/** A sheet with every control of one device, one section per channel. Closes on the backdrop, Escape or Close. */
export function DevicePanel({ deviceId, onClose }: { deviceId: string; onClose: () => void }) {
  const { data, isSuccess } = useDevices()
  const now = useNow()
  const device = data?.devices.find((d) => d.id === deviceId)
  const agentDown = data ? !data.agentOnline : false

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // The device was forgotten while its panel was open.
  useEffect(() => {
    if (isSuccess && !device) onClose()
  }, [isSuccess, device, onClose])

  if (!device) return null
  const unavailable = isUnavailable(device, agentDown)
  const note = availabilityNote(device, agentDown, now)

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={device.name} onClick={(e) => e.stopPropagation()}>
        <header className="sheet-head">
          <div>
            <h2>{device.name}</h2>
            {note && <p className="stale">{note}</p>}
          </div>
          <button className="button" onClick={onClose}>
            Close
          </button>
        </header>
        <div className="sheet-body">
          {device.channels.map((channel) => (
            <ChannelControls key={channel.id} device={device} channel={channel} unavailable={unavailable}
              showTitle={device.channels.length > 1} />
          ))}
        </div>
      </div>
    </div>
  )
}
