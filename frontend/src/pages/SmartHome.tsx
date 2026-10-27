import { useState } from 'react'
import { Link } from 'react-router-dom'
import { GearIcon } from '../components/Icons'
import { CaptureDialog } from '../components/CaptureDialog'
import { DeviceRow } from '../components/DeviceRow'
import { DevicePanel } from '../components/DevicePanel'
import { QuickAccessEditor } from '../components/QuickAccessEditor'
import { RoomSection } from '../components/RoomSection'
import { SceneEditor } from '../components/SceneEditor'
import { ScenesSection } from '../components/ScenesSection'
import { ConnectionNotice } from '../components/StatusBar'
import { useConnection } from '../connection'
import { useCreateRoom, useDevices, useReorderRooms, useRooms, useScenes } from '../hooks/data'
import { groupDevices, moveItem, pinnedItems } from '../homeModel'
import type { Scene } from '../types'

/** The Home tab: devices grouped by room, with an edit mode for rooms, assignment, names and pins. */
export function SmartHome() {
  const { data: snapshot, isPending } = useDevices()
  const { data: roomList } = useRooms()
  const connection = useConnection()
  const reorder = useReorderRooms()
  const createRoom = useCreateRoom()
  const [edit, setEdit] = useState(false)
  const [newRoom, setNewRoom] = useState('')
  const [panel, setPanel] = useState<string | null>(null)
  const [editor, setEditor] = useState<Scene | 'new' | null>(null)
  const [capturing, setCapturing] = useState(false)
  const { data: sceneList } = useScenes()

  const devices = snapshot?.devices ?? []
  const rooms = roomList ?? []
  const agentDown = snapshot ? !snapshot.agentOnline : false
  const offline = connection === 'reconnecting'
  const grouped = groupDevices(devices, rooms)
  const pinned = pinnedItems(devices, sceneList ?? [])
  const roomIds = rooms.map((r) => r.id)
  const nothingYet = !isPending && devices.length === 0 && rooms.length === 0

  return (
    <>
      <ConnectionNotice />
      <main className="phone tabbed">
        <header className="phone-header">
          <h1>Home</h1>
          <div className="header-actions">
            <button className={edit ? 'button compact primary' : 'button compact'} onClick={() => setEdit(!edit)}>
              {edit ? 'Done' : 'Edit'}
            </button>
            <Link className="icon-link" to="/settings" aria-label="Settings">
              <GearIcon />
            </Link>
          </div>
        </header>

        {agentDown && devices.length > 0 && <p className="stale">Home agent offline: devices can't be switched</p>}
        {offline && !agentDown && devices.length > 0 && <p className="stale">Offline: devices can't be switched</p>}
        {isPending && <p className="muted">Looking for devices…</p>}
        {nothingYet && (
          <section className="card">
            <p className="muted">{agentDown ? 'The home agent is offline.' : 'No devices found yet.'}</p>
          </section>
        )}

        {edit && <QuickAccessEditor pinned={pinned} />}

        <ScenesSection manage={edit} pinned={pinned} onEdit={setEditor} onNew={() => setEditor('new')} onCapture={() => setCapturing(true)} />

        {grouped.unassigned.length > 0 && (
          <section className="card" aria-label="Unassigned">
            <h2>Unassigned</h2>
            {!edit && <p className="muted small">New devices show up here. Tap Edit to put them in a room.</p>}
            <ul className="device-list">
              {grouped.unassigned.map((d) => (
                <DeviceRow key={d.id} device={d} rooms={rooms} pinned={pinned} agentDown={agentDown} offline={offline} edit={edit} onOpen={setPanel} />
              ))}
            </ul>
          </section>
        )}

        {grouped.rooms.map((group, i) => (
          <RoomSection
            key={group.room.id}
            group={group}
            first={i === 0}
            last={i === grouped.rooms.length - 1}
            rooms={rooms}
            pinned={pinned}
            agentDown={agentDown}
            offline={offline}
            edit={edit}
            onOpen={setPanel}
            onMove={(delta) => reorder.mutate(moveItem(roomIds, i, delta))}
          />
        ))}
        {reorder.error && <p className="error small">{reorder.error.message}</p>}

        {edit && (
          <form
            className="card add-room"
            onSubmit={(e) => {
              e.preventDefault()
              const name = newRoom.trim()
              if (name) createRoom.mutate(name, { onSuccess: () => setNewRoom('') })
            }}
          >
            <h2>Add a room</h2>
            <div className="inline-edit">
              <input type="text" value={newRoom} maxLength={40} placeholder="Living room" aria-label="New room name" onChange={(e) => setNewRoom(e.target.value)} />
              <button type="submit" className="button primary compact" disabled={createRoom.isPending || newRoom.trim() === ''}>
                Add
              </button>
            </div>
            {createRoom.error && <p className="error small">{createRoom.error.message}</p>}
          </form>
        )}
      </main>
      {panel && <DevicePanel deviceId={panel} onClose={() => setPanel(null)} />}
      {editor && <SceneEditor scene={editor === 'new' ? null : editor} onClose={() => setEditor(null)} />}
      {capturing && (
        <CaptureDialog
          onClose={() => setCapturing(false)}
          onCreated={(scene) => {
            setCapturing(false)
            setEditor(scene)
          }}
        />
      )}
    </>
  )
}
