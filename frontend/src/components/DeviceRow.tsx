import { useState } from 'react'
import { useSetPower, useForgetDevice, useSetQuickAccess, useUpdateDevice } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import { availabilityNote, isUnavailable, QUICK_ACCESS_SLOTS, toPinItems, type PinnedItem } from '../homeModel'
import type { Device, Room } from '../types'
import { InlineEdit } from './InlineEdit'

interface Props {
  device: Device
  rooms: Room[]
  /** All pinned lights and scenes in slot order, needed to pin or unpin one. */
  pinned: PinnedItem[]
  agentDown: boolean
  /** The live connection to the server is down, so commands cannot go out. */
  offline: boolean
  edit: boolean
  /** Opens the device panel; the row's name becomes a button when given. */
  onOpen?: (deviceId: string) => void
}

/** One device: a power switch normally, room / pin / rename / forget controls in edit mode. */
export function DeviceRow({ device, rooms, pinned, agentDown, offline, edit, onOpen }: Props) {
  const setPower = useSetPower()
  const update = useUpdateDevice()
  const forget = useForgetDevice()
  const pin = useSetQuickAccess()
  const [renaming, setRenaming] = useState(false)
  const now = useNow()

  const unavailable = isUnavailable(device, agentDown)
  const note = availabilityNote(device, agentDown, now)
  const pinsFull = pinned.length >= QUICK_ACCESS_SLOTS
  const error = update.error ?? forget.error ?? pin.error
  const pinItems = toPinItems(pinned)
  const anyUnpinned = device.channels.some((c) => c.quickAccess === null)

  if (!edit) {
    const pending = setPower.isPending
    return (
      <li>
        <div className={unavailable ? 'device unavailable' : 'device'}>
          {onOpen ? (
            <button className="device-open" onClick={() => onOpen(device.id)} aria-label={`Open ${device.name}`}>
              <span className="device-name">
                {device.name}
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
            disabled={unavailable || offline || pending}
            onClick={() => setPower.mutate({ id: device.id, on: !device.power })}
          >
            <span className="knob" />
          </button>
        </div>
        {setPower.isError && <p className="error small">That device did not respond.</p>}
      </li>
    )
  }

  return (
    <li className="device-edit">
      <span className="device-name">
        {device.name}
        {device.name !== device.reportedName && <small> · {device.reportedName}</small>}
        {note && <small> · {note}</small>}
      </span>
      {renaming ? (
        <InlineEdit
          initial={device.name === device.reportedName ? '' : device.name}
          placeholder={device.reportedName}
          label={`New name for ${device.name}`}
          busy={update.isPending}
          onSave={(name) => update.mutate({ id: device.id, name }, { onSuccess: () => setRenaming(false) })}
          onCancel={() => setRenaming(false)}
        />
      ) : (
        <div className="edit-controls">
          <select
            aria-label={`Room for ${device.name}`}
            value={device.roomId ?? ''}
            disabled={update.isPending}
            onChange={(e) => update.mutate({ id: device.id, roomId: e.target.value === '' ? null : Number(e.target.value) })}
          >
            <option value="">Unassigned</option>
            {rooms.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name}
              </option>
            ))}
          </select>
          {device.channels.map((channel) => {
            const isPinned = channel.quickAccess !== null
            const key = { deviceId: device.id, channel: channel.id }
            return (
              <button
                key={channel.id}
                className={isPinned ? 'button compact pinned' : 'button compact'}
                aria-pressed={isPinned}
                disabled={(!isPinned && pinsFull) || pin.isPending}
                title={!isPinned && pinsFull ? `All ${QUICK_ACCESS_SLOTS} quick access slots are used` : undefined}
                onClick={() =>
                  pin.mutate(
                    isPinned
                      ? pinItems.filter((i) => !(i.deviceId === key.deviceId && i.channel === key.channel))
                      : [...pinItems, key],
                  )
                }
              >
                {isPinned ? '★ Pinned' : '☆ Pin'}
                {device.channels.length > 1 && ` ${channel.label}`}
              </button>
            )
          })}
          <button className="button compact" onClick={() => setRenaming(true)}>
            Rename
          </button>
          {!device.present && (
            <button className="button compact danger" disabled={forget.isPending} onClick={() => forget.mutate(device.id)}>
              Forget
            </button>
          )}
        </div>
      )}
      {pinsFull && anyUnpinned && !renaming && <p className="muted small">All {QUICK_ACCESS_SLOTS} quick access slots are used. Unpin one first.</p>}
      {error && <p className="error small">{error.message}</p>}
    </li>
  )
}
