import { useState } from 'react'
import { useDeleteRoom, useRenameRoom } from '../hooks/data'
import type { PinnedItem, RoomGroup } from '../homeModel'
import type { Room } from '../types'
import { DeviceRow } from './DeviceRow'
import { InlineEdit } from './InlineEdit'

interface Props {
  group: RoomGroup
  first: boolean
  last: boolean
  rooms: Room[]
  pinned: PinnedItem[]
  agentDown: boolean
  offline: boolean
  edit: boolean
  onMove: (delta: number) => void
  onOpen?: (deviceId: string) => void
}

/** A room and its devices. In edit mode the room can be renamed, moved and deleted. */
export function RoomSection({ group, first, last, rooms, pinned, agentDown, offline, edit, onMove, onOpen }: Props) {
  const rename = useRenameRoom()
  const remove = useDeleteRoom()
  const [renaming, setRenaming] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const { room, devices } = group

  return (
    <section className="card room" aria-label={room.name}>
      {renaming ? (
        <InlineEdit
          initial={room.name}
          label="Room name"
          busy={rename.isPending}
          onSave={(name) => rename.mutate({ id: room.id, name }, { onSuccess: () => setRenaming(false) })}
          onCancel={() => setRenaming(false)}
        />
      ) : (
        <h2>
          <span>{room.name}</span>
          {edit && (
            <span className="room-tools">
              <button className="icon-button small" aria-label={`Move ${room.name} up`} disabled={first} onClick={() => onMove(-1)}>
                ↑
              </button>
              <button className="icon-button small" aria-label={`Move ${room.name} down`} disabled={last} onClick={() => onMove(1)}>
                ↓
              </button>
              <button className="button compact" onClick={() => setRenaming(true)}>
                Rename
              </button>
              <button className="button compact danger" onClick={() => setConfirming(true)}>
                Delete
              </button>
            </span>
          )}
        </h2>
      )}
      {confirming && (
        <div className="confirm" role="alert">
          <p>Delete {room.name}? Its devices stay and become unassigned.</p>
          <div className="edit-controls">
            <button className="button compact danger" disabled={remove.isPending} onClick={() => remove.mutate(room.id)}>
              Delete room
            </button>
            <button className="button compact" onClick={() => setConfirming(false)}>
              Keep
            </button>
          </div>
        </div>
      )}
      {devices.length === 0 ? (
        <p className="muted">No devices in this room.</p>
      ) : (
        <ul className="device-list">
          {devices.map((d) => (
            <DeviceRow key={d.id} device={d} rooms={rooms} pinned={pinned} agentDown={agentDown} offline={offline} edit={edit} onOpen={onOpen} />
          ))}
        </ul>
      )}
      {(rename.error ?? remove.error) && <p className="error small">{(rename.error ?? remove.error)?.message}</p>}
    </section>
  )
}
