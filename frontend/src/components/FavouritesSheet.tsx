import { useState } from 'react'
import { useDevices, useScenes, useSetQuickAccess } from '../hooks/data'
import { channelTitle, isPinned, moveItem, pinnedItems, QUICK_ACCESS_SLOTS, toPinItems } from '../homeModel'
import type { PinItem } from '../types'
import { PlusIcon, SceneIcon, BulbIcon } from './Icons'
import { Sheet } from './Sheet'

/**
 * The favourites: up to four lights and scenes, shown on the dashboard and on your wall display. Every change is saved at once.
 * Empty slots are shown as such, and tapping one lists what can be added.
 */
export function FavouritesSheet({ onClose }: { onClose: () => void }) {
  const { data: devices } = useDevices()
  const { data: scenes } = useScenes()
  const save = useSetQuickAccess()
  const [picking, setPicking] = useState(false)

  const pinned = pinnedItems(devices?.devices ?? [], scenes ?? [])
  const items = toPinItems(pinned)
  const set = (next: PinItem[]) => save.mutate(next)

  const candidates = [
    ...(devices?.devices ?? []).flatMap((device) =>
      device.channels.map((channel) => ({ key: `${device.id}/${channel.id}`, kind: 'light' as const, name: channelTitle(device, channel), item: { deviceId: device.id, channel: channel.id } })),
    ),
    ...(scenes ?? []).map((scene) => ({ key: `scene-${scene.id}`, kind: 'scene' as const, name: scene.name, item: { sceneId: scene.id } })),
  ].filter((c) => !isPinned(items, c.item))

  if (picking) {
    return (
      <Sheet title="Add a favourite" subtitle="Choose a light or a scene." onClose={() => setPicking(false)} closeLabel="Back">
        {candidates.length === 0 && <p className="muted">Everything is already a favourite.</p>}
        <ul className="pick-list">
          {candidates.map((c) => (
            <li key={c.key}>
              <button
                type="button"
                className="pick-row"
                onClick={() => {
                  set([...items, c.item])
                  setPicking(false)
                }}
              >
                {c.kind === 'scene' ? <SceneIcon className="pick-icon" /> : <BulbIcon className="pick-icon" />}
                <span className="pick-name">{c.name}</span>
                <span className="muted small">{c.kind === 'scene' ? 'Scene' : 'Light'}</span>
              </button>
            </li>
          ))}
        </ul>
      </Sheet>
    )
  }

  const empty = Array.from({ length: Math.max(0, QUICK_ACCESS_SLOTS - pinned.length) })

  return (
    <Sheet title="Favourites" subtitle={`Up to ${QUICK_ACCESS_SLOTS} lights or scenes, shown on your dashboard and on your wall display.`} onClose={onClose} closeLabel="Done">
      <ol className="slot-list">
        {pinned.map((item, i) => {
          const name = item.kind === 'scene' ? item.scene.name : channelTitle(item.device, item.channel)
          return (
            <li key={item.kind === 'scene' ? `scene-${item.scene.id}` : `${item.device.id}/${item.channel.id}`} className="slot">
              <span className="slot-name">
                {name}
                <small>{item.kind === 'scene' ? 'Scene' : 'Light'}</small>
              </span>
              <span className="slot-actions">
                <button type="button" className="icon-button" aria-label={`Move ${name} up`} disabled={i === 0 || save.isPending} onClick={() => set(moveItem(items, i, -1))}>
                  ↑
                </button>
                <button type="button" className="icon-button" aria-label={`Move ${name} down`} disabled={i === pinned.length - 1 || save.isPending} onClick={() => set(moveItem(items, i, 1))}>
                  ↓
                </button>
                <button type="button" className="icon-button danger" aria-label={`Remove ${name} from favourites`} disabled={save.isPending} onClick={() => set(items.filter((_, index) => index !== i))}>
                  ✕
                </button>
              </span>
            </li>
          )
        })}
        {empty.map((_, i) => (
          <li key={`empty-${i}`} className="slot empty">
            <button type="button" className="slot-add" onClick={() => setPicking(true)}>
              <PlusIcon />
              Add a favourite
            </button>
          </li>
        ))}
      </ol>
      {save.error && <p className="error small">{save.error.message}</p>}
    </Sheet>
  )
}
