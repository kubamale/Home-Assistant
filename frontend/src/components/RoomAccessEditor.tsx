import { useState } from 'react'
import { useSetRoomAccess, useUsers } from '../hooks/data'
import type { Room, RoomAccess } from '../types'

const CHOICES: { access: RoomAccess; title: string; description: string }[] = [
  { access: 'open', title: 'Everyone', description: 'Everyone can see and control the devices in this room. A shared space, like the living room.' },
  { access: 'view', title: 'Everyone can look', description: 'Everyone sees the devices, but only the people you pick can change them.' },
  { access: 'private', title: 'Only some people', description: 'The room and its devices are hidden from everyone else.' },
]

/**
 * The admin chooses who may use a room. People you pick are the only ones who control it (and, for a private room, see it).
 * You can always control everything, so you are not in the list.
 */
export function RoomAccessEditor({ room, onDone }: { room: Room; onDone: () => void }) {
  const { data: users } = useUsers()
  const save = useSetRoomAccess()
  const [access, setAccess] = useState<RoomAccess>(room.access)
  const [members, setMembers] = useState<number[]>(room.members)
  const people = (users ?? []).filter((u) => !u.admin)
  const toggle = (id: number) => setMembers((m) => (m.includes(id) ? m.filter((x) => x !== id) : [...m, id]))
  const changed = access !== room.access || (access !== 'open' && [...members].sort().join() !== [...room.members].sort().join())

  return (
    <div className="access-editor">
      <div role="radiogroup" aria-label="Who can use this room">
        {CHOICES.map((c) => (
          <button key={c.access} type="button" role="radio" aria-checked={access === c.access} className="access-choice" onClick={() => setAccess(c.access)}>
            <span className="access-title">{c.title}</span>
            <span className="muted small">{c.description}</span>
          </button>
        ))}
      </div>
      {access !== 'open' && (
        <fieldset className="access-people">
          <legend>{access === 'view' ? 'Who can control it' : 'Who can see and control it'}</legend>
          {people.length === 0 ? (
            <p className="muted small">No one else has an account yet. Add people in Settings, People. Until then only you can use this room.</p>
          ) : (
            people.map((u) => (
              <label key={u.id} className="access-person">
                <input type="checkbox" checked={members.includes(u.id)} onChange={() => toggle(u.id)} />
                <span>{u.name}</span>
              </label>
            ))
          )}
          <p className="muted small">You can always control everything.</p>
        </fieldset>
      )}
      <div className="inline-buttons">
        <button type="button" className="button" onClick={onDone}>
          Cancel
        </button>
        <button type="button" className="button primary" disabled={!changed || save.isPending} onClick={() => save.mutate({ id: room.id, access, members: access === 'open' ? [] : members }, { onSuccess: onDone })}>
          Save
        </button>
      </div>
      {save.error && <p className="error small">{save.error.message}</p>}
    </div>
  )
}
