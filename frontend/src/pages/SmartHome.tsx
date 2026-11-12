import { useState } from 'react'
import { Link } from 'react-router-dom'
import { GearIcon, PlusIcon } from '../components/Icons'
import { DeviceRow } from '../components/DeviceRow'
import { DevicePanel } from '../components/DevicePanel'
import { FavouritesSheet } from '../components/FavouritesSheet'
import { QuickAccessCard } from '../components/QuickAccessCard'
import { RoomMenu } from '../components/RoomMenu'
import { RoomSection } from '../components/RoomSection'
import { SceneEditor } from '../components/SceneEditor'
import { SceneMenu } from '../components/SceneMenu'
import { ScenesSection } from '../components/ScenesSection'
import { ConnectionNotice } from '../components/StatusBar'
import { useConnection } from '../connection'
import { useCreateRoom, useDevices, useIsAdmin, useRooms } from '../hooks/data'
import { groupDevices } from '../homeModel'
import type { Scene } from '../types'

/**
 * The Home tab. There is no edit mode: favourites and scenes have their own buttons, a device opens its own panel (where it
 * becomes a favourite, or, for the admin, gets a room and a name), and a room has a "..." button for the admin.
 */
export function SmartHome() {
  const { data: snapshot, isPending } = useDevices()
  const { data: roomList } = useRooms()
  const connection = useConnection()
  const admin = useIsAdmin()
  const createRoom = useCreateRoom()
  const [newRoom, setNewRoom] = useState<string | null>(null)
  const [panel, setPanel] = useState<string | null>(null)
  const [favourites, setFavourites] = useState(false)
  const [sceneMenu, setSceneMenu] = useState<Scene | null>(null)
  const [editor, setEditor] = useState<Scene | 'new' | null>(null)
  const [roomMenuId, setRoomMenuId] = useState<number | null>(null)

  const devices = snapshot?.devices ?? []
  const rooms = roomList ?? []
  const roomMenu = rooms.find((r) => r.id === roomMenuId) ?? null // the live room, so a saved change shows at once (and a deleted room closes its menu)
  const agentDown = snapshot ? !snapshot.agentOnline : false
  const offline = connection === 'reconnecting'
  const grouped = groupDevices(devices, rooms)
  const nothingYet = !isPending && devices.length === 0 && rooms.length === 0

  const openFavourites = () => {
    setPanel(null)
    setSceneMenu(null)
    setFavourites(true)
  }

  return (
    <>
      <ConnectionNotice />
      <main className="phone tabbed">
        <header className="phone-header">
          <h1>Home</h1>
          <div className="header-actions">
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

        <QuickAccessCard onEdit={openFavourites} onOpenDevice={setPanel} />

        <ScenesSection onMenu={setSceneMenu} onNew={() => setEditor('new')} />

        {grouped.unassigned.length > 0 && (
          <section className="card" aria-label="Unassigned">
            <h2>Not in a room</h2>
            {admin && <p className="muted small">New devices show up here. Open one and choose its room.</p>}
            <ul className="device-list">
              {grouped.unassigned.map((d) => (
                <DeviceRow key={d.id} device={d} agentDown={agentDown} offline={offline} onOpen={setPanel} />
              ))}
            </ul>
          </section>
        )}

        {grouped.rooms.map((group) => (
          <RoomSection key={group.room.id} group={group} agentDown={agentDown} offline={offline} onOpen={setPanel} onMenu={admin ? () => setRoomMenuId(group.room.id) : undefined} />
        ))}

        {admin &&
          (newRoom === null ? (
            <button type="button" className="add-room-button" onClick={() => setNewRoom('')}>
              <PlusIcon />
              Add a room
            </button>
          ) : (
            <form
              className="card add-room"
              onSubmit={(e) => {
                e.preventDefault()
                const name = newRoom.trim()
                if (name) createRoom.mutate(name, { onSuccess: () => setNewRoom(null) })
              }}
            >
              <h2>Add a room</h2>
              <div className="inline-edit">
                <input type="text" value={newRoom} maxLength={40} placeholder="Living room" aria-label="New room name" autoFocus onChange={(e) => setNewRoom(e.target.value)} />
                <button type="submit" className="button primary compact" disabled={createRoom.isPending || newRoom.trim() === ''}>
                  Add
                </button>
                <button type="button" className="button compact" onClick={() => setNewRoom(null)}>
                  Cancel
                </button>
              </div>
              {createRoom.error && <p className="error small">{createRoom.error.message}</p>}
            </form>
          ))}
      </main>

      {panel && <DevicePanel deviceId={panel} onClose={() => setPanel(null)} onFavourites={openFavourites} />}
      {favourites && <FavouritesSheet onClose={() => setFavourites(false)} />}
      {sceneMenu && (
        <SceneMenu
          scene={sceneMenu}
          onClose={() => setSceneMenu(null)}
          onFavourites={openFavourites}
          onEdit={() => {
            setEditor(sceneMenu)
            setSceneMenu(null)
          }}
        />
      )}
      {editor && <SceneEditor scene={editor === 'new' ? null : editor} onClose={() => setEditor(null)} />}
      {roomMenu && <RoomMenu room={roomMenu} rooms={rooms} onClose={() => setRoomMenuId(null)} />}
    </>
  )
}
