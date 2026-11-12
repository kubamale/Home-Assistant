import { useEffect, useState } from 'react'
import { useDevices, useForgetDevice, useIsAdmin, useRooms, useScenes, useSession, useSetQuickAccess, useUpdateDevice } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import { availabilityNote, isLocked, isPinned, isUnavailable, pinnedItems, togglePin, toPinItems } from '../homeModel'
import { ChannelControls } from './ChannelControls'
import { Sheet } from './Sheet'

/**
 * Every control of one device, one section per channel. On the phone each light has a star to make it a favourite, and the
 * admin can also say which room the device is in, rename it, and forget it once it has gone. Closes on the backdrop,
 * Escape or Close.
 */
export function DevicePanel({ deviceId, onClose, onFavourites }: { deviceId: string; onClose: () => void; onFavourites?: () => void }) {
  const { data, isSuccess } = useDevices()
  const { data: scenes } = useScenes()
  const { data: rooms } = useRooms()
  const now = useNow()
  const admin = useIsAdmin()
  const person = useSession().data?.role === 'user' // a wall display cannot change favourites
  const pin = useSetQuickAccess()
  const update = useUpdateDevice()
  const forget = useForgetDevice()
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState('')
  const [full, setFull] = useState(false)
  const device = data?.devices.find((d) => d.id === deviceId)
  const agentDown = data ? !data.agentOnline : false

  // The device was forgotten while its panel was open.
  useEffect(() => {
    if (isSuccess && !device) onClose()
  }, [isSuccess, device, onClose])

  if (!device) return null
  const unavailable = isUnavailable(device, agentDown)
  const note = availabilityNote(device, agentDown, now)
  const items = toPinItems(pinnedItems(data?.devices ?? [], scenes ?? []))
  const toggleStar = (channelId: string) => {
    const result = togglePin(items, { deviceId: device.id, channel: channelId })
    setFull(result.full)
    if (!result.full) pin.mutate(result.items)
  }

  return (
    <Sheet title={device.name} subtitle={note ? <span className="stale">{note}</span> : undefined} onClose={onClose}>
      {full && (
        <p className="menu-note">
          Your favourites are full.{' '}
          {onFavourites ? (
            <button type="button" className="inline-link" onClick={onFavourites}>
              Choose which to keep
            </button>
          ) : (
            'Remove one first.'
          )}
        </p>
      )}
      {device.channels.map((channel) => (
        <ChannelControls
          key={channel.id}
          device={device}
          channel={channel}
          unavailable={unavailable || isLocked(device)}
          showTitle={device.channels.length > 1}
          favourite={person ? { on: isPinned(items, { deviceId: device.id, channel: channel.id }), onToggle: () => toggleStar(channel.id), disabled: pin.isPending || isLocked(device) } : undefined}
        />
      ))}
      {pin.error && <p className="error small">{pin.error.message}</p>}

      {admin && (
        <section className="manage" aria-label="Manage this device">
          <h3>This device</h3>
          <label className="manage-row">
            <span>Room</span>
            <select
              value={device.roomId ?? ''}
              disabled={update.isPending}
              onChange={(e) => update.mutate({ id: device.id, roomId: e.target.value === '' ? null : Number(e.target.value) })}
            >
              <option value="">No room</option>
              {(rooms ?? []).map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
          </label>
          {renaming ? (
            <form
              className="inline-edit"
              onSubmit={(e) => {
                e.preventDefault()
                update.mutate({ id: device.id, name: name.trim() }, { onSuccess: () => setRenaming(false) })
              }}
            >
              <input type="text" value={name} placeholder={device.reportedName} maxLength={60} aria-label={`New name for ${device.name}`} autoFocus onChange={(e) => setName(e.target.value)} />
              <button type="submit" className="button primary compact" disabled={update.isPending}>
                Save
              </button>
              <button type="button" className="button compact" onClick={() => setRenaming(false)}>
                Cancel
              </button>
            </form>
          ) : (
            <button
              type="button"
              className="manage-row link"
              onClick={() => {
                setName(device.name === device.reportedName ? '' : device.name)
                setRenaming(true)
              }}
            >
              <span>Name</span>
              <span className="muted">
                {device.name} ›
              </span>
            </button>
          )}
          {!device.present && (
            <button type="button" className="manage-row link danger-text" disabled={forget.isPending} onClick={() => forget.mutate(device.id, { onSuccess: onClose })}>
              Forget this device
            </button>
          )}
          {(update.error ?? forget.error) && <p className="error small">{(update.error ?? forget.error)?.message}</p>}
        </section>
      )}
    </Sheet>
  )
}
