import { useEffect } from 'react'
import { useConnection } from '../connection'
import { useApplyScene, useDevices, useScenes } from '../hooks/data'
import { describeApply } from '../homeModel'
import { sceneSubtitle } from '../sceneModel'
import type { Scene } from '../types'
import { MoreIcon, PlusIcon } from './Icons'

/**
 * Scenes as a grid of buttons: tapping one applies it. On the phone each tile also has a "..." button that opens what can be done
 * with the scene (onMenu), and a last tile makes a new one (onNew). Without those (the wall screen) it only applies.
 */
export function ScenesSection({ onMenu, onNew }: { onMenu?: (scene: Scene) => void; onNew?: () => void }) {
  const { data: scenes } = useScenes()
  const { data: devices } = useDevices()
  const connection = useConnection()
  const apply = useApplyScene()

  // A scene's result stays visible for a few seconds.
  useEffect(() => {
    if (apply.isSuccess || apply.isError) {
      const timer = window.setTimeout(() => apply.reset(), 5000)
      return () => window.clearTimeout(timer)
    }
  }, [apply, apply.status])

  const list = scenes ?? []
  if (!onNew && list.length === 0) return null
  const agentDown = devices ? !devices.agentOnline : false
  const offline = connection === 'reconnecting'
  const result = apply.isSuccess ? describeApply(list.find((s) => s.id === apply.variables)?.name ?? 'Scene', apply.data) : null

  return (
    <section className="card scenes" aria-label="Scenes">
      <h2>Scenes</h2>
      <div className="scene-grid">
        {list.map((scene) => {
          const mine = apply.variables === scene.id
          const state = mine && apply.isPending ? 'Applying…' : mine && apply.isSuccess ? (apply.data.failed.length === 0 ? 'Applied' : `${apply.data.failed.length} failed`) : sceneSubtitle(scene)
          return (
            <div className="scene-cell" key={scene.id}>
              <button className="scene-button" disabled={agentDown || offline || apply.isPending} onClick={() => apply.mutate(scene.id)}>
                <span className="scene-name">{scene.name}</span>
                <span className="scene-state">{state}</span>
              </button>
              {onMenu && (
                <button type="button" className="scene-more" aria-label={`More for ${scene.name}`} onClick={() => onMenu(scene)}>
                  <MoreIcon />
                </button>
              )}
            </div>
          )
        })}
        {onNew && (
          <button type="button" className="scene-button scene-new" onClick={onNew}>
            <PlusIcon />
            <span className="scene-name">New scene</span>
          </button>
        )}
      </div>
      {result && !result.ok && <p className="error small">{result.text}</p>}
      {apply.isError && <p className="error small">{apply.error.message}</p>}
      {agentDown && <p className="stale">Home agent offline: scenes can't be applied</p>}
    </section>
  )
}
