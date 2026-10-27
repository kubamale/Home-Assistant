import { useState } from 'react'
import { useCaptureScene, useDevices } from '../hooks/data'
import { channelTitle } from '../homeModel'
import { tileState } from '../homeModel'
import type { Scene } from '../types'

/** Save the lights as they are now: pick a name and which lights to include. */
export function CaptureDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (scene: Scene) => void }) {
  const { data } = useDevices()
  const capture = useCaptureScene()
  const [name, setName] = useState('')
  const lights = (data?.devices ?? []).filter((d) => d.present && d.reachable).flatMap((device) => device.channels.map((channel) => ({ device, channel })))
  // Everything starts ticked; `unticked` holds what the user took out.
  const [unticked, setUnticked] = useState<Set<string>>(new Set())
  const key = (deviceId: string, channel: string) => `${deviceId}/${channel}`
  const chosen = lights.filter((l) => !unticked.has(key(l.device.id, l.channel.id)))

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <form
        className="sheet"
        role="dialog"
        aria-modal="true"
        aria-label="Save lights as scene"
        onClick={(e) => e.stopPropagation()}
        onSubmit={(e) => {
          e.preventDefault()
          capture.mutate({ name: name.trim(), channels: chosen.map((l) => ({ deviceId: l.device.id, channel: l.channel.id })) }, { onSuccess: onCreated })
        }}
      >
        <header className="sheet-head">
          <h2>Save lights as scene</h2>
          <button type="button" className="button" onClick={onClose}>
            Cancel
          </button>
        </header>
        <div className="sheet-body">
          <label className="form">
            Name
            <input type="text" value={name} maxLength={40} placeholder="Movie" autoFocus onChange={(e) => setName(e.target.value)} />
          </label>
          <p className="muted small">Lights that are on are saved with their brightness and colour or white; lights that are off are saved as off.</p>
          {lights.length === 0 && <p className="muted">No lights are available right now.</p>}
          <ul className="device-list">
            {lights.map(({ device, channel }) => {
              const k = key(device.id, channel.id)
              return (
                <li key={k} className="device">
                  <label className="check">
                    <input
                      type="checkbox"
                      checked={!unticked.has(k)}
                      onChange={() => setUnticked((prev) => { const next = new Set(prev); if (next.has(k)) next.delete(k); else next.add(k); return next })}
                    />
                    <span className="device-name">
                      {channelTitle(device, channel)}
                      <small> · {tileState(channel, false).toLowerCase()}</small>
                    </span>
                  </label>
                </li>
              )
            })}
          </ul>
          {capture.error && <p className="error small">{capture.error.message}</p>}
        </div>
        <footer className="sheet-foot">
          <button type="submit" className="button primary" disabled={capture.isPending || name.trim() === '' || chosen.length === 0}>
            Save scene
          </button>
        </footer>
      </form>
    </div>
  )
}
