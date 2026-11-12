import { useState } from 'react'
import { useDeleteScene, useDevices, useReorderScenes, useScenes, useSetQuickAccess } from '../hooks/data'
import { isPinned, moveItem, pinnedItems, togglePin, toPinItems } from '../homeModel'
import type { Scene } from '../types'
import { StarIcon } from './Icons'
import { Sheet } from './Sheet'

/** What can be done with one scene: make it a favourite, change it, move it, delete it. */
export function SceneMenu({ scene, onClose, onEdit, onFavourites }: {
  scene: Scene
  onClose: () => void
  onEdit: () => void
  onFavourites: () => void
}) {
  const { data: devices } = useDevices()
  const { data: scenes } = useScenes()
  const pin = useSetQuickAccess()
  const remove = useDeleteScene()
  const reorder = useReorderScenes()
  const [confirming, setConfirming] = useState(false)
  const [full, setFull] = useState(false)

  const items = toPinItems(pinnedItems(devices?.devices ?? [], scenes ?? []))
  const favourite = isPinned(items, { sceneId: scene.id })
  const mine = (scenes ?? []).filter((s) => s.editable).map((s) => s.id)
  const place = mine.indexOf(scene.id)
  const builtIn = scene.kind === 'all_off'

  const toggleFavourite = () => {
    const result = togglePin(items, { sceneId: scene.id })
    if (result.full) {
      setFull(true)
      return
    }
    pin.mutate(result.items, { onSuccess: onClose })
  }

  return (
    <Sheet title={scene.name} subtitle={scene.shared ? 'A scene for everyone' : 'Only you see this scene'} onClose={onClose}>
      <ul className="menu-list">
        <li>
          <button type="button" className="menu-row" disabled={pin.isPending} onClick={toggleFavourite}>
            <StarIcon filled={favourite} />
            {favourite ? 'Remove from favourites' : 'Add to favourites'}
          </button>
          {full && (
            <p className="menu-note">
              Your favourites are full. <button type="button" className="inline-link" onClick={onFavourites}>Choose which to keep</button>
            </p>
          )}
        </li>
        {scene.editable && (
          <li>
            <button type="button" className="menu-row" onClick={onEdit}>
              {builtIn ? 'Rename' : 'Edit lights and settings'}
            </button>
          </li>
        )}
        {scene.editable && mine.length > 1 && (
          <>
            <li>
              <button type="button" className="menu-row" disabled={place <= 0 || reorder.isPending} onClick={() => reorder.mutate(moveItem(mine, place, -1), { onSuccess: onClose })}>
                Move earlier in the list
              </button>
            </li>
            <li>
              <button type="button" className="menu-row" disabled={place >= mine.length - 1 || reorder.isPending} onClick={() => reorder.mutate(moveItem(mine, place, 1), { onSuccess: onClose })}>
                Move later in the list
              </button>
            </li>
          </>
        )}
        {scene.editable && !builtIn && (
          <li>
            {confirming ? (
              <div className="menu-confirm" role="alert">
                <p>Delete {scene.name}? This cannot be undone.</p>
                <div className="inline-buttons">
                  <button type="button" className="button" onClick={() => setConfirming(false)}>
                    Keep
                  </button>
                  <button type="button" className="button danger" disabled={remove.isPending} onClick={() => remove.mutate(scene.id, { onSuccess: onClose })}>
                    Delete
                  </button>
                </div>
              </div>
            ) : (
              <button type="button" className="menu-row danger-text" onClick={() => setConfirming(true)}>
                Delete scene
              </button>
            )}
          </li>
        )}
      </ul>
      {(pin.error ?? remove.error ?? reorder.error) && <p className="error small">{(pin.error ?? remove.error ?? reorder.error)?.message}</p>}
    </Sheet>
  )
}
