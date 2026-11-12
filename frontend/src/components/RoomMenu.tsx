import { useState } from 'react'
import { useDeleteRoom, useRenameRoom, useReorderRooms } from '../hooks/data'
import { moveItem } from '../homeModel'
import type { Room } from '../types'
import { Sheet } from './Sheet'

/** What the admin can do with a room: rename it, move it in the list, delete it. */
export function RoomMenu({ room, rooms, onClose }: { room: Room; rooms: Room[]; onClose: () => void }) {
  const rename = useRenameRoom()
  const remove = useDeleteRoom()
  const reorder = useReorderRooms()
  const [name, setName] = useState(room.name)
  const [confirming, setConfirming] = useState(false)
  const ids = rooms.map((r) => r.id)
  const place = ids.indexOf(room.id)

  return (
    <Sheet title={room.name} onClose={onClose}>
      <form
        className="inline-edit"
        onSubmit={(e) => {
          e.preventDefault()
          if (name.trim() !== '' && name.trim() !== room.name) rename.mutate({ id: room.id, name: name.trim() }, { onSuccess: onClose })
        }}
      >
        <input type="text" value={name} maxLength={40} aria-label="Room name" onChange={(e) => setName(e.target.value)} />
        <button type="submit" className="button primary compact" disabled={rename.isPending || name.trim() === '' || name.trim() === room.name}>
          Rename
        </button>
      </form>
      <ul className="menu-list">
        <li>
          <button type="button" className="menu-row" disabled={place <= 0 || reorder.isPending} onClick={() => reorder.mutate(moveItem(ids, place, -1), { onSuccess: onClose })}>
            Move up
          </button>
        </li>
        <li>
          <button type="button" className="menu-row" disabled={place >= ids.length - 1 || reorder.isPending} onClick={() => reorder.mutate(moveItem(ids, place, 1), { onSuccess: onClose })}>
            Move down
          </button>
        </li>
        <li>
          {confirming ? (
            <div className="menu-confirm" role="alert">
              <p>Delete {room.name}? Its devices stay and become unassigned.</p>
              <div className="inline-buttons">
                <button type="button" className="button" onClick={() => setConfirming(false)}>
                  Keep
                </button>
                <button type="button" className="button danger" disabled={remove.isPending} onClick={() => remove.mutate(room.id, { onSuccess: onClose })}>
                  Delete room
                </button>
              </div>
            </div>
          ) : (
            <button type="button" className="menu-row danger-text" onClick={() => setConfirming(true)}>
              Delete room
            </button>
          )}
        </li>
      </ul>
      {(rename.error ?? remove.error ?? reorder.error) && <p className="error small">{(rename.error ?? remove.error ?? reorder.error)?.message}</p>}
    </Sheet>
  )
}
