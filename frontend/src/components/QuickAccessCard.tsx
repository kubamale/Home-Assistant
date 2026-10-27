import { useEffect, useRef } from 'react'
import { Link } from 'react-router-dom'
import { useConnection } from '../connection'
import { useApplyScene, useDevices, useRooms, useScenes, useSetChannel } from '../hooks/data'
import { BulbIcon, SceneIcon } from './Icons'
import { channelTitle, describeApply, isUnavailable, pinnedItems, tileState } from '../homeModel'

/** How long a tile has to be held to open the device panel instead of toggling. */
const LONG_PRESS_MS = 500

/**
 * The dashboard's quick access panel: up to four pinned lights and scenes as big tiles. Tapping a light
 * toggles that channel and holding it opens the device panel (onOpenDevice); tapping a scene applies it. The phone explains how to pin when nothing
 * is pinned. The door screen passes onAllDevices to get an "All devices" button; without that and
 * without pins it shows nothing.
 */
export function QuickAccessCard({ hint, onAllDevices, onOpenDevice }: {
  hint?: boolean
  onAllDevices?: () => void
  onOpenDevice?: (deviceId: string) => void
}) {
  const { data, isPending } = useDevices()
  const { data: rooms } = useRooms()
  const connection = useConnection()
  const { data: scenes } = useScenes()
  const setChannel = useSetChannel()
  const applyScene = useApplyScene()

  // The result of a scene stays on its tile for a few seconds.
  useEffect(() => {
    if (applyScene.isSuccess || applyScene.isError) {
      const timer = window.setTimeout(() => applyScene.reset(), 5000)
      return () => window.clearTimeout(timer)
    }
  }, [applyScene, applyScene.status])

  const pinned = pinnedItems(data?.devices ?? [], scenes ?? [])
  const agentDown = data ? !data.agentOnline : false
  const offline = connection === 'reconnecting'
  const roomName = (id: number | null) => rooms?.find((r) => r.id === id)?.name

  const allDevices = onAllDevices && (data?.devices.length ?? 0) > 0 && (
    <button className="button compact all-devices" onClick={onAllDevices}>
      All devices ›
    </button>
  )

  if (!isPending && pinned.length === 0) {
    if (allDevices) {
      return (
        <section className="card quick" aria-label="Quick access">
          <h2>
            <span>Quick access</span>
            {allDevices}
          </h2>
          <p className="muted">Nothing pinned yet.</p>
        </section>
      )
    }
    if (!hint) return null
    return (
      <section className="card quick" aria-label="Quick access">
        <h2>Quick access</h2>
        <p className="muted">
          Pin up to 4 lights from the <Link to="/home">Home</Link> tab to switch them from here.
        </p>
      </section>
    )
  }

  return (
    <section className="card quick" aria-label="Quick access">
      <h2>
        <span>Quick access</span>
        {allDevices}
      </h2>
      {isPending && <p className="muted">Looking for devices…</p>}
      <div className="quick-grid">
        {pinned.map((item) => {
          if (item.kind === 'scene') {
            const { scene } = item
            const mine = applyScene.variables === scene.id
            const state = mine && applyScene.isPending ? 'Applying…' : mine && applyScene.isSuccess
              ? applyScene.data.failed.length === 0 ? 'Applied' : `${applyScene.data.failed.length} failed`
              : mine && applyScene.isError ? 'Failed' : 'Tap to apply'
            return (
              <Tile
                key={`scene-${scene.id}`}
                scene
                name={scene.name}
                state={state}
                on={false}
                unavailable={false}
                disabled={agentDown || offline || applyScene.isPending}
                onToggle={() => applyScene.mutate(scene.id)}
              />
            )
          }
          const { device, channel } = item
          const unavailable = isUnavailable(device, agentDown)
          const pending = setChannel.isPending && setChannel.variables?.deviceId === device.id && setChannel.variables.channel === channel.id
          const state = tileState(channel, unavailable)
          return (
            <Tile
              key={`${device.id}/${channel.id}`}
              room={roomName(device.roomId)}
              name={channelTitle(device, channel)}
              state={state}
              on={channel.on && !unavailable}
              unavailable={unavailable}
              disabled={unavailable || offline || pending}
              onToggle={() => setChannel.mutate({ deviceId: device.id, channel: channel.id, change: { on: !channel.on } })}
              onOpen={onOpenDevice && (() => onOpenDevice(device.id))}
            />
          )
        })}
      </div>
      {agentDown && pinned.length > 0 && <p className="stale">Home agent offline</p>}
      {offline && !agentDown && pinned.length > 0 && <p className="stale">Offline: lights can't be switched</p>}
      {setChannel.isError && <p className="error">That light did not respond.</p>}
      {applyScene.isSuccess && applyScene.data.failed.length > 0 && (
        <p className="error">{describeApply(scenes?.find((s) => s.id === applyScene.variables)?.name ?? 'Scene', applyScene.data).text}</p>
      )}
      {applyScene.isError && <p className="error">{applyScene.error.message}</p>}
    </section>
  )
}

function Tile({ scene, room, name, state, on, unavailable, disabled, onToggle, onOpen }: {
  scene?: boolean
  room?: string
  name: string
  state: string
  on: boolean
  unavailable: boolean
  disabled: boolean
  onToggle: () => void
  onOpen?: () => void
}) {
  const timer = useRef<number | undefined>(undefined)
  const held = useRef(false)

  const start = () => {
    held.current = false
    if (!onOpen) return
    timer.current = window.setTimeout(() => {
      held.current = true
      onOpen()
    }, LONG_PRESS_MS)
  }
  const cancel = () => window.clearTimeout(timer.current)

  return (
    <button
      role={scene ? undefined : 'switch'}
      aria-checked={scene ? undefined : on}
      aria-label={scene ? `Apply scene ${name}` : `${name} ${state.toLowerCase()}`}
      className={['tile', scene && 'scene', on && 'on', unavailable && 'unavailable'].filter(Boolean).join(' ')}
      disabled={disabled}
      onPointerDown={start}
      onPointerUp={cancel}
      onPointerLeave={cancel}
      onPointerCancel={cancel}
      onContextMenu={(e) => e.preventDefault()}
      onClick={() => {
        if (held.current) {
          held.current = false // the hold opened the panel; do not also toggle
          return
        }
        onToggle()
      }}
    >
      {scene ? <SceneIcon className="tile-icon" /> : <BulbIcon className="tile-icon" />}
      <span className="tile-room">{room ?? ' '}</span>
      <span className="tile-name">{name}</span>
      <span className="tile-state">{state}</span>
    </button>
  )
}
