import { useState } from 'react'
import { COLOR_PRESETS, hsvCss } from '../color'
import { useCreateScene, useDevices, useUpdateScene } from '../hooks/data'
import { addableLights, draftForLight, draftFromAction, draftToAction, hasSettings, toggleColor, toggleWhite, type ActionDraft } from '../sceneModel'
import type { Device, Scene } from '../types'
import { Slider } from './Slider'

/** Create or edit a scene: a name and one block per light with a checkbox per setting. */
export function SceneEditor({ scene, onClose }: { scene: Scene | null; onClose: () => void }) {
  const { data } = useDevices()
  const create = useCreateScene()
  const update = useUpdateScene()
  const builtIn = scene?.kind === 'all_off'
  const [name, setName] = useState(scene?.name ?? '')
  const [drafts, setDrafts] = useState<ActionDraft[]>(() => (scene?.actions ?? []).map(draftFromAction))
  const devices = data?.devices ?? []
  const addable = addableLights(devices, drafts)
  const saving = create.isPending || update.isPending
  const error = create.error ?? update.error
  const valid = name.trim() !== '' && (builtIn || (drafts.length > 0 && drafts.every(hasSettings)))

  const save = () => {
    const actions = builtIn ? undefined : drafts.map(draftToAction)
    if (scene) update.mutate({ id: scene.id, name: name.trim(), actions }, { onSuccess: onClose })
    else create.mutate({ name: name.trim(), actions: actions ?? [] }, { onSuccess: onClose })
  }

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={scene ? `Edit ${scene.name}` : 'New scene'} onClick={(e) => e.stopPropagation()}>
        <header className="sheet-head">
          <h2>{scene ? (builtIn ? 'Rename scene' : 'Edit scene') : 'New scene'}</h2>
          <button className="button" onClick={onClose}>
            Cancel
          </button>
        </header>
        <div className="sheet-body">
          <label className="form">
            Name
            <input type="text" value={name} maxLength={40} placeholder="Movie" autoFocus={!scene} onChange={(e) => setName(e.target.value)} />
          </label>
          {builtIn && <p className="muted small">This scene switches off every light that is available when it is applied. It has no lights to edit.</p>}
          {!builtIn && drafts.length === 0 && <p className="muted">Add the lights this scene should change.</p>}
          {!builtIn &&
            drafts.map((draft, i) => (
              <ActionBlock
                key={`${draft.deviceId}/${draft.channel}`}
                draft={draft}
                devices={devices}
                onChange={(next) => setDrafts(drafts.map((d, index) => (index === i ? next : d)))}
                onRemove={() => setDrafts(drafts.filter((_, index) => index !== i))}
              />
            ))}
          {!builtIn && (
            <label className="form add-light">
              Add a light
              <select
                value=""
                disabled={addable.length === 0}
                onChange={(e) => {
                  const [deviceId, channelId] = e.target.value.split('/')
                  const found = addable.find((l) => l.device.id === deviceId && l.channel.id === channelId)
                  if (found) setDrafts([...drafts, draftForLight(found.device, found.channel)])
                }}
              >
                <option value="">{addable.length === 0 ? 'Every light is in this scene' : 'Choose a light…'}</option>
                {addable.map(({ device, channel }) => (
                  <option key={`${device.id}/${channel.id}`} value={`${device.id}/${channel.id}`}>
                    {device.channels.length > 1 ? `${device.name} · ${channel.label}` : device.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          {error && <p className="error small">{error.message}</p>}
        </div>
        <footer className="sheet-foot">
          {!builtIn && drafts.some((d) => !hasSettings(d)) && <p className="muted small">Every light needs at least one setting ticked.</p>}
          <button className="button primary" disabled={!valid || saving} onClick={save}>
            Save scene
          </button>
        </footer>
      </div>
    </div>
  )
}

function ActionBlock({ draft, devices, onChange, onRemove }: {
  draft: ActionDraft
  devices: Device[]
  onChange: (draft: ActionDraft) => void
  onRemove: () => void
}) {
  const caps = draft.capabilities
  const multi = (devices.find((d) => d.id === draft.deviceId)?.channels.length ?? 1) > 1
  const off = draft.includePower && !draft.on
  const ignored = off // an off light ignores everything else

  return (
    <section className="action-block" aria-label={draft.deviceName}>
      <div className="action-head">
        <h3>{multi ? `${draft.deviceName} · ${draft.channelLabel}` : draft.deviceName}</h3>
        <button className="button compact danger" onClick={onRemove}>
          Remove
        </button>
      </div>

      <SettingRow label="Power" included={draft.includePower} onInclude={() => onChange({ ...draft, includePower: !draft.includePower })}>
        <div className="segmented" role="group" aria-label="Power">
          <button aria-pressed={draft.on} disabled={!draft.includePower} onClick={() => onChange({ ...draft, on: true })}>
            On
          </button>
          <button aria-pressed={!draft.on} disabled={!draft.includePower} onClick={() => onChange({ ...draft, on: false })}>
            Off
          </button>
        </div>
      </SettingRow>

      {caps.brightness && (
        <SettingRow label="Brightness" included={draft.includeBrightness && !ignored} disabled={ignored} onInclude={() => onChange({ ...draft, includeBrightness: !draft.includeBrightness })}>
          <Slider hideName label="Brightness" unit="%" min={1} max={100} value={draft.brightness} disabled={!draft.includeBrightness || ignored} onCommit={(brightness) => onChange({ ...draft, brightness })} />
        </SettingRow>
      )}

      {caps.colorTemp && (
        <SettingRow label="White" included={draft.includeWhite && !ignored} disabled={ignored} onInclude={() => onChange(toggleWhite(draft))}>
          <Slider hideName label="White" unit=" K" min={caps.colorTemp.min} max={caps.colorTemp.max} value={draft.colorTemp} disabled={!draft.includeWhite || ignored}
            trackStyle={{ background: 'linear-gradient(to right, #ffae5a, #fff3e0 45%, #ffffff 60%, #a9d1ff)' }}
            onCommit={(colorTemp) => onChange({ ...draft, colorTemp })} />
        </SettingRow>
      )}

      {caps.color && (
        <SettingRow label="Colour" included={draft.includeColor && !ignored} disabled={ignored} onInclude={() => onChange(toggleColor(draft))}>
          <Slider hideName label="Colour" min={0} max={359} value={draft.hue} disabled={!draft.includeColor || ignored}
            trackStyle={{ background: 'linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00)' }}
            onCommit={(hue) => onChange({ ...draft, hue })} />
          <Slider label="Saturation" unit="%" min={0} max={100} value={draft.saturation} disabled={!draft.includeColor || ignored}
            trackStyle={{ background: `linear-gradient(to right, #fff, ${hsvCss(draft.hue, 100)})` }}
            onCommit={(saturation) => onChange({ ...draft, saturation })} />
          <div className="swatches" role="group" aria-label="Colour presets">
            {COLOR_PRESETS.map((p) => (
              <button key={p.name} className="swatch" aria-label={p.name} title={p.name} disabled={ignored}
                style={{ background: hsvCss(p.hue, p.saturation) }}
                onClick={() => onChange({ ...draft, includeColor: true, includeWhite: false, hue: p.hue, saturation: p.saturation })} />
            ))}
          </div>
        </SettingRow>
      )}
      {ignored && <p className="muted small">A light that is switched off ignores its other settings.</p>}
    </section>
  )
}

/** One setting with its include checkbox; what is not included is left alone when the scene is applied. */
function SettingRow({ label, included, disabled, onInclude, children }: {
  label: string
  included: boolean
  disabled?: boolean
  onInclude: () => void
  children: React.ReactNode
}) {
  return (
    <div className={included ? 'setting-row' : 'setting-row off'}>
      <label className="check">
        <input type="checkbox" checked={included} disabled={disabled} onChange={onInclude} aria-label={`Set ${label.toLowerCase()}`} />
        <span>{label}</span>
      </label>
      <div className="setting-body">{children}</div>
    </div>
  )
}
