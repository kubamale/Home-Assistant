import { useState } from 'react'
import { COLOR_PRESETS, hsvCss } from '../color'
import { useCreateScene, useDevices, useIsAdmin, useUpdateScene } from '../hooks/data'
import { channelTitle, tileState } from '../homeModel'
import { addableLights, draftFromAction, draftFromChannel, draftToAction, hasSettings, summarizeDraft, toggleColor, toggleWhite, type ActionDraft } from '../sceneModel'
import type { Scene } from '../types'
import { Chevron, Toggle } from './Controls'
import { Sheet } from './Sheet'
import { Slider } from './Slider'

const keyOf = (deviceId: string, channel: string) => `${deviceId}/${channel}`

/**
 * Makes or changes a scene. A scene is a list of lights, each with what to do to it. The quickest way to make one is to set
 * the lights the way you like them and start from "my lights as they are now"; every light is then a card that opens to be
 * adjusted. Adding a light also starts from how it is now.
 */
export function SceneEditor({ scene, onClose }: { scene: Scene | null; onClose: () => void }) {
  const { data } = useDevices()
  const create = useCreateScene()
  const update = useUpdateScene()
  const admin = useIsAdmin()
  const builtIn = scene?.kind === 'all_off'
  const [name, setName] = useState(scene?.name ?? '')
  const [shared, setShared] = useState(false)
  const [drafts, setDrafts] = useState<ActionDraft[]>(() => (scene?.actions ?? []).map(draftFromAction))
  const [open, setOpen] = useState<string | null>(null)
  const [picking, setPicking] = useState(false)
  const [chosen, setChosen] = useState<Set<string>>(new Set())

  const devices = data?.devices ?? []
  const addable = addableLights(devices, drafts)
  const saving = create.isPending || update.isPending
  const error = create.error ?? update.error
  const valid = name.trim() !== '' && (builtIn || (drafts.length > 0 && drafts.every(hasSettings)))

  const save = () => {
    const actions = builtIn ? undefined : drafts.map(draftToAction)
    if (scene) update.mutate({ id: scene.id, name: name.trim(), actions }, { onSuccess: onClose })
    else create.mutate({ name: name.trim(), actions: actions ?? [], shared: admin && shared }, { onSuccess: onClose })
  }

  const change = (next: ActionDraft) => setDrafts(drafts.map((d) => (keyOf(d.deviceId, d.channel) === keyOf(next.deviceId, next.channel) ? next : d)))

  /** Starts the scene as a picture of the room: every light that is available, as it is now. */
  const fromNow = () => {
    setDrafts(addable.filter(({ device }) => device.present && device.reachable).map(({ device, channel }) => draftFromChannel(device, channel)))
    setOpen(null)
  }

  const openPicker = () => {
    setChosen(new Set())
    setPicking(true)
  }
  const addChosen = () => {
    setDrafts([...drafts, ...addable.filter(({ device, channel }) => chosen.has(keyOf(device.id, channel.id))).map(({ device, channel }) => draftFromChannel(device, channel))])
    setPicking(false)
  }

  if (picking) {
    const allKeys = addable.map(({ device, channel }) => keyOf(device.id, channel.id))
    return (
      <Sheet
        title="Add lights"
        subtitle="Each light starts the way it is now. You can change it afterwards."
        onClose={() => setPicking(false)}
        closeLabel="Back"
        footer={
          <>
            <button type="button" className="button" onClick={() => setChosen(chosen.size === allKeys.length ? new Set() : new Set(allKeys))}>
              {chosen.size === allKeys.length ? 'Select none' : 'Select all'}
            </button>
            <button type="button" className="button primary" disabled={chosen.size === 0} onClick={addChosen}>
              {chosen.size === 0 ? 'Add' : `Add ${chosen.size}`}
            </button>
          </>
        }
      >
        {addable.length === 0 && <p className="muted">Every light is already in this scene.</p>}
        <ul className="pick-list">
          {addable.map(({ device, channel }) => {
            const k = keyOf(device.id, channel.id)
            const unavailable = !device.present || !device.reachable
            return (
              <li key={k}>
                <label className={unavailable ? 'pick-row unavailable' : 'pick-row'}>
                  <input
                    type="checkbox"
                    checked={chosen.has(k)}
                    onChange={() => setChosen((prev) => { const next = new Set(prev); if (next.has(k)) next.delete(k); else next.add(k); return next })}
                  />
                  <span className="pick-name">{channelTitle(device, channel)}</span>
                  <span className="muted small">{unavailable ? 'Unavailable' : tileState(channel, false)}</span>
                </label>
              </li>
            )
          })}
        </ul>
      </Sheet>
    )
  }

  return (
    <Sheet
      title={scene ? (builtIn ? 'Rename scene' : 'Edit scene') : 'New scene'}
      onClose={onClose}
      closeLabel="Cancel"
      footer={
        <>
          {!builtIn && drafts.some((d) => !hasSettings(d)) && <p className="muted small">Every light needs something to do. Open it and turn a setting on.</p>}
          <button className="button primary" disabled={!valid || saving} onClick={save}>
            {saving ? 'Saving…' : 'Save scene'}
          </button>
        </>
      }
    >
      <label className="form">
        Name
        <input type="text" value={name} maxLength={40} placeholder="Movie" autoFocus={!scene} onChange={(e) => setName(e.target.value)} />
      </label>
      {admin && !scene && (
        <div className="light-row-plain">
          <span>
            For everyone
            <small>Otherwise only you see it.</small>
          </span>
          <Toggle on={shared} label="For everyone" onChange={setShared} />
        </div>
      )}

      {builtIn ? (
        <p className="muted small">This scene switches off every light that is available when it is applied. It has no lights to edit.</p>
      ) : drafts.length === 0 ? (
        <div className="empty-scene">
          <p>A scene sets several lights at once. How do you want to start?</p>
          <button type="button" className="button primary" disabled={addable.length === 0} onClick={fromNow}>
            Use my lights as they are now
          </button>
          <button type="button" className="button" disabled={addable.length === 0} onClick={openPicker}>
            Choose lights…
          </button>
          <p className="muted small">Tip: set your lights the way you want them first, then come back and pick the first button.</p>
        </div>
      ) : (
        <>
          <ul className="light-list">
            {drafts.map((d) => {
              const k = keyOf(d.deviceId, d.channel)
              return (
                <LightCard
                  key={k}
                  draft={d}
                  multi={(devices.find((x) => x.id === d.deviceId)?.channels.length ?? 1) > 1}
                  open={open === k}
                  onToggle={() => setOpen(open === k ? null : k)}
                  onChange={change}
                  onRemove={() => {
                    setDrafts(drafts.filter((x) => keyOf(x.deviceId, x.channel) !== k))
                    setOpen(null)
                  }}
                />
              )
            })}
          </ul>
          {addable.length > 0 && (
            <button type="button" className="button" onClick={openPicker}>
              Add more lights…
            </button>
          )}
        </>
      )}
      {error && <p className="error small">{error.message}</p>}
    </Sheet>
  )
}

/** One light of the scene: a line saying what it will do, which opens to change it. */
function LightCard({ draft, multi, open, onToggle, onChange, onRemove }: {
  draft: ActionDraft
  multi: boolean
  open: boolean
  onToggle: () => void
  onChange: (draft: ActionDraft) => void
  onRemove: () => void
}) {
  const caps = draft.capabilities
  const power: 'on' | 'off' | 'leave' = !draft.includePower ? 'leave' : draft.on ? 'on' : 'off'
  const ignored = power === 'off' // an off light ignores everything else
  const title = multi ? `${draft.deviceName} · ${draft.channelLabel}` : draft.deviceName

  const setPower = (next: 'on' | 'off' | 'leave') =>
    onChange({ ...draft, includePower: next !== 'leave', on: next === 'leave' ? draft.on : next === 'on' })

  return (
    <li className={open ? 'light-card open' : 'light-card'}>
      <button type="button" className="light-head" aria-expanded={open} onClick={onToggle}>
        <span className="light-name">{title}</span>
        <span className="light-summary">{summarizeDraft(draft)}</span>
        <Chevron />
      </button>
      {open && (
        <div className="light-body">
          <div className="light-setting">
            <span className="light-setting-title">Power</span>
            <div className="segmented" role="group" aria-label={`Power of ${title}`}>
              {(['on', 'off', 'leave'] as const).map((p) => (
                <button key={p} type="button" aria-pressed={power === p} onClick={() => setPower(p)}>
                  {p === 'on' ? 'On' : p === 'off' ? 'Off' : 'As is'}
                </button>
              ))}
            </div>
          </div>

          {ignored ? (
            <p className="muted small">A light that is switched off ignores its other settings.</p>
          ) : (
            <>
              {caps.brightness && (
                <Setting label="Brightness" on={draft.includeBrightness} onToggle={() => onChange({ ...draft, includeBrightness: !draft.includeBrightness })}>
                  <Slider hideName label="Brightness" unit="%" min={1} max={100} value={draft.brightness} onCommit={(brightness) => onChange({ ...draft, brightness })} />
                </Setting>
              )}
              {caps.colorTemp && (
                <Setting label="White" on={draft.includeWhite} onToggle={() => onChange(toggleWhite(draft))}>
                  <Slider hideName label="White" unit=" K" min={caps.colorTemp.min} max={caps.colorTemp.max} value={draft.colorTemp}
                    trackStyle={{ background: 'linear-gradient(to right, #ffae5a, #fff3e0 45%, #ffffff 60%, #a9d1ff)' }}
                    onCommit={(colorTemp) => onChange({ ...draft, colorTemp })} />
                </Setting>
              )}
              {caps.color && (
                <Setting label="Colour" on={draft.includeColor} onToggle={() => onChange(toggleColor(draft))}>
                  <Slider hideName label="Colour" min={0} max={359} value={draft.hue}
                    trackStyle={{ background: 'linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00)' }}
                    onCommit={(hue) => onChange({ ...draft, hue })} />
                  <Slider label="Saturation" unit="%" min={0} max={100} value={draft.saturation}
                    trackStyle={{ background: `linear-gradient(to right, #fff, ${hsvCss(draft.hue, 100)})` }}
                    onCommit={(saturation) => onChange({ ...draft, saturation })} />
                  <div className="swatches" role="group" aria-label="Colour presets">
                    {COLOR_PRESETS.map((p) => (
                      <button key={p.name} type="button" className="swatch" aria-label={p.name} title={p.name} style={{ background: hsvCss(p.hue, p.saturation) }}
                        onClick={() => onChange({ ...draft, includeColor: true, includeWhite: false, hue: p.hue, saturation: p.saturation })} />
                    ))}
                  </div>
                </Setting>
              )}
            </>
          )}
          <button type="button" className="text-button danger-text" onClick={onRemove}>
            Remove from scene
          </button>
        </div>
      )}
    </li>
  )
}

/** One setting: switched on it is part of the scene and its control shows; off, the scene leaves it as it is. */
function Setting({ label, on, onToggle, children }: { label: string; on: boolean; onToggle: () => void; children: React.ReactNode }) {
  return (
    <div className="light-setting">
      <div className="light-setting-head">
        <span className="light-setting-title">{label}</span>
        <Toggle on={on} label={`Set ${label.toLowerCase()}`} onChange={onToggle} />
      </div>
      {on ? children : <p className="muted small">Left as it is</p>}
    </div>
  )
}

