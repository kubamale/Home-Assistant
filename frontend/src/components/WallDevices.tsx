import { DeviceRow } from './DeviceRow'
import { ScenesSection } from './ScenesSection'
import { useConnection } from '../connection'
import { useDevices, useRooms } from '../hooks/data'
import { groupDevices } from '../homeModel'

/** The door screen's list of all devices by room. Switching only; organising happens on the phone. */
export function WallDevices({ onBack, onOpen }: { onBack: () => void; onOpen: (deviceId: string) => void }) {
  const { data, isPending } = useDevices()
  const { data: rooms } = useRooms()
  const connection = useConnection()

  const devices = data?.devices ?? []
  const agentDown = data ? !data.agentOnline : false
  const offline = connection === 'reconnecting'
  const grouped = groupDevices(devices, rooms ?? [])
  const sections = [
    ...grouped.rooms.filter((g) => g.devices.length > 0).map((g) => ({ key: `room-${g.room.id}`, title: g.room.name, devices: g.devices })),
    ...(grouped.unassigned.length > 0 ? [{ key: 'other', title: 'Other', devices: grouped.unassigned }] : []),
  ]

  return (
    <div className="wall-devices">
      <header className="wall-devices-head">
        <button className="button" onClick={onBack}>
          ← Dashboard
        </button>
        <h1>Devices</h1>
      </header>
      {agentDown && <p className="stale">Home agent offline: devices can't be switched</p>}
      {offline && !agentDown && <p className="stale">Offline: devices can't be switched</p>}
      {isPending && <p className="muted">Looking for devices…</p>}
      {!isPending && sections.length === 0 && <p className="muted">No devices found yet.</p>}
      <div className="wall-devices-grid">
        <ScenesSection />
        {sections.map((section) => (
          <section key={section.key} className="card" aria-label={section.title}>
            <h2>{section.title}</h2>
            <ul className="device-list">
              {section.devices.map((d) => (
                <DeviceRow key={d.id} device={d} rooms={[]} pinned={[]} agentDown={agentDown} offline={offline} edit={false} onOpen={onOpen} />
              ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}
