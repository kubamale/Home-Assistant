import { useEffect, useState } from 'react'
import { useConnection } from '../connection'
import { useApplyScene, useDeleteScene, useDevices, useReorderScenes, useScenes, useSetQuickAccess } from '../hooks/data'
import { describeApply, moveItem, QUICK_ACCESS_SLOTS, toPinItems, type PinnedItem } from '../homeModel'
import { sceneSubtitle } from '../sceneModel'
import type { Scene } from '../types'

/**
 * Scenes. Normally a grid of buttons that apply a scene; in manage mode (edit mode on the phone) a list
 * with pin, order, edit and delete, plus buttons to create a scene or capture the lights as they are.
 */
export function ScenesSection({ manage, pinned = [], onEdit, onNew, onCapture }: {
  manage?: boolean
  pinned?: PinnedItem[]
  onEdit?: (scene: Scene) => void
  onNew?: () => void
  onCapture?: () => void
}) {
  const { data: scenes } = useScenes()
  const { data: devices } = useDevices()
  const connection = useConnection()
  const apply = useApplyScene()
  const remove = useDeleteScene()
  const reorder = useReorderScenes()
  const pin = useSetQuickAccess()
  const [confirming, setConfirming] = useState<number | null>(null)

  // A scene's result stays visible for a few seconds.
  useEffect(() => {
    if (apply.isSuccess || apply.isError) {
      const timer = window.setTimeout(() => apply.reset(), 5000)
      return () => window.clearTimeout(timer)
    }
  }, [apply, apply.status])

  const list = scenes ?? []
  if (!manage && list.length === 0) return null
  const agentDown = devices ? !devices.agentOnline : false
  const offline = connection === 'reconnecting'
  const ids = list.map((s) => s.id)
  const pinItems = toPinItems(pinned)
  const result = apply.isSuccess ? describeApply(list.find((s) => s.id === apply.variables)?.name ?? 'Scene', apply.data) : null

  if (!manage) {
    return (
      <section className="card scenes" aria-label="Scenes">
        <h2>Scenes</h2>
        <div className="scene-grid">
          {list.map((scene) => {
            const mine = apply.variables === scene.id
            const state = mine && apply.isPending ? 'Applying…' : mine && apply.isSuccess ? (apply.data.failed.length === 0 ? 'Applied' : `${apply.data.failed.length} failed`) : sceneSubtitle(scene)
            return (
              <button key={scene.id} className="scene-button" disabled={agentDown || offline || apply.isPending} onClick={() => apply.mutate(scene.id)}>
                <span className="scene-name">{scene.name}</span>
                <span className="scene-state">{state}</span>
              </button>
            )
          })}
        </div>
        {result && !result.ok && <p className="error small">{result.text}</p>}
        {apply.isError && <p className="error small">{apply.error.message}</p>}
        {agentDown && <p className="stale">Home agent offline: scenes can't be applied</p>}
      </section>
    )
  }

  return (
    <section className="card scenes manage" aria-label="Scenes">
      <h2>Scenes</h2>
      <ul className="device-list">
        {list.map((scene, i) => {
          const isPinned = scene.quickAccess !== null
          const pinsFull = !isPinned && pinned.length >= QUICK_ACCESS_SLOTS
          return (
            <li key={scene.id} className="device-edit">
              <span className="device-name">
                {scene.name}
                <small> · {sceneSubtitle(scene)}</small>
              </span>
              <div className="edit-controls">
                <button className={isPinned ? 'button compact pinned' : 'button compact'} aria-pressed={isPinned} disabled={pinsFull || pin.isPending}
                  title={pinsFull ? `All ${QUICK_ACCESS_SLOTS} quick access slots are used` : undefined}
                  onClick={() => pin.mutate(isPinned ? pinItems.filter((p) => p.sceneId !== scene.id) : [...pinItems, { sceneId: scene.id }])}>
                  {isPinned ? '★ Pinned' : '☆ Pin'}
                </button>
                <button className="icon-button small" aria-label={`Move ${scene.name} up`} disabled={i === 0 || reorder.isPending} onClick={() => reorder.mutate(moveItem(ids, i, -1))}>
                  ↑
                </button>
                <button className="icon-button small" aria-label={`Move ${scene.name} down`} disabled={i === list.length - 1 || reorder.isPending} onClick={() => reorder.mutate(moveItem(ids, i, 1))}>
                  ↓
                </button>
                <button className="button compact" onClick={() => onEdit?.(scene)}>
                  {scene.kind === 'all_off' ? 'Rename' : 'Edit'}
                </button>
                {scene.kind !== 'all_off' && (
                  <button className="button compact danger" onClick={() => setConfirming(scene.id)}>
                    Delete
                  </button>
                )}
              </div>
              {confirming === scene.id && (
                <div className="confirm" role="alert">
                  <p>Delete {scene.name}?</p>
                  <div className="edit-controls">
                    <button className="button compact danger" disabled={remove.isPending} onClick={() => remove.mutate(scene.id, { onSuccess: () => setConfirming(null) })}>
                      Delete scene
                    </button>
                    <button className="button compact" onClick={() => setConfirming(null)}>
                      Keep
                    </button>
                  </div>
                </div>
              )}
            </li>
          )
        })}
      </ul>
      <div className="edit-controls scene-create">
        <button className="button compact primary" onClick={onCapture}>
          Save current lights as scene
        </button>
        <button className="button compact" onClick={onNew}>
          New scene
        </button>
      </div>
      {(remove.error ?? reorder.error ?? pin.error) && <p className="error small">{(remove.error ?? reorder.error ?? pin.error)?.message}</p>}
    </section>
  )
}
