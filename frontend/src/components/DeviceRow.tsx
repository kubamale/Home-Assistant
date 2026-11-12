import { useSetPower } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import { availabilityNote, isLocked, isUnavailable } from '../homeModel'
import type { Device } from '../types'
import { StarIcon } from './Icons'

interface Props {
  device: Device
  agentDown: boolean
  /** The live connection to the server is down, so commands cannot go out. */
  offline: boolean
  /** Opens the device panel; the row's name becomes a button when given. */
  onOpen?: (deviceId: string) => void
}

/** One device: its name (which opens its panel, where it can be made a favourite or renamed) and a power switch. */
export function DeviceRow({ device, agentDown, offline, onOpen }: Props) {
  const setPower = useSetPower()
  const now = useNow()
  const unavailable = isUnavailable(device, agentDown)
  const note = availabilityNote(device, agentDown, now)
  const favourite = device.channels.some((c) => c.quickAccess !== null)

  return (
    <li>
      <div className={unavailable ? 'device unavailable' : 'device'}>
        {onOpen ? (
          <button className="device-open" onClick={() => onOpen(device.id)} aria-label={`Open ${device.name}`}>
            <span className="device-name">
              {device.name}
              {favourite && <StarIcon filled className="device-star" />}
              {note && <small> · {note}</small>}
            </span>
            <span className="chevron" aria-hidden="true">
              ›
            </span>
          </button>
        ) : (
          <span className="device-name">
            {device.name}
            {note && <small> · {note}</small>}
          </span>
        )}
        <button
          role="switch"
          aria-checked={device.power}
          aria-label={`${device.name} ${device.power ? 'on' : 'off'}`}
          className={device.power ? 'switch on' : 'switch'}
          disabled={unavailable || isLocked(device) || offline || setPower.isPending}
          onClick={() => setPower.mutate({ id: device.id, on: !device.power })}
        >
          <span className="knob" />
        </button>
      </div>
      {setPower.isError && <p className="error small">That device did not respond.</p>}
    </li>
  )
}
