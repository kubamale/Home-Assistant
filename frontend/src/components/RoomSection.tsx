import { roomBadge, type RoomGroup } from '../homeModel'
import { useIsAdmin } from '../hooks/data'
import { DeviceRow } from './DeviceRow'
import { MoreIcon } from './Icons'

/** A room and its devices. The admin gets a "..." button that opens what can be done with the room. */
export function RoomSection({ group, agentDown, offline, onOpen, onMenu }: {
  group: RoomGroup
  agentDown: boolean
  offline: boolean
  onOpen?: (deviceId: string) => void
  onMenu?: () => void
}) {
  const { room, devices } = group
  const badge = roomBadge(room, useIsAdmin())
  return (
    <section className="card room" aria-label={room.name}>
      <h2>
        <span>
          {room.name}
          {badge && <span className={room.access === 'private' ? 'room-badge private' : 'room-badge'}>{badge}</span>}
        </span>
        {onMenu && (
          <button type="button" className="icon-button" aria-label={`More for ${room.name}`} onClick={onMenu}>
            <MoreIcon />
          </button>
        )}
      </h2>
      {devices.length === 0 ? (
        <p className="muted">No devices in this room.</p>
      ) : (
        <ul className="device-list">
          {devices.map((d) => (
            <DeviceRow key={d.id} device={d} agentDown={agentDown} offline={offline} onOpen={onOpen} />
          ))}
        </ul>
      )}
    </section>
  )
}
