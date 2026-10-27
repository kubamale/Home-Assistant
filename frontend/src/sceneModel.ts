import type { Capabilities, Channel, Device, Scene, SceneAction, SceneActionPayload } from './types'

/**
 * One light of a scene while it is being edited. Every setting has an "include" flag: a setting that is
 * not included is left alone when the scene is applied. White and colour exclude each other.
 */
export interface ActionDraft {
  deviceId: string
  deviceName: string
  channel: string
  channelLabel: string
  capabilities: Capabilities
  includePower: boolean
  on: boolean
  includeBrightness: boolean
  brightness: number
  includeWhite: boolean
  colorTemp: number
  includeColor: boolean
  hue: number
  saturation: number
}

const NO_CAPABILITIES: Capabilities = { brightness: false, colorTemp: null, color: false }

function defaults(caps: Capabilities) {
  return {
    brightness: 100,
    colorTemp: caps.colorTemp ? Math.round((caps.colorTemp.min + caps.colorTemp.max) / 2) : 4000,
    hue: 0,
    saturation: 100,
  }
}

/** A saved action as an editable draft. */
export function draftFromAction(a: SceneAction): ActionDraft {
  const caps = a.capabilities ?? NO_CAPABILITIES
  const d = defaults(caps)
  const s = a.settings
  return {
    deviceId: a.deviceId, deviceName: a.deviceName, channel: a.channel, channelLabel: a.channelLabel, capabilities: caps,
    includePower: s.on !== null, on: s.on ?? true,
    includeBrightness: s.brightness !== null, brightness: s.brightness ?? d.brightness,
    includeWhite: s.colorTemp !== null, colorTemp: s.colorTemp ?? d.colorTemp,
    includeColor: s.hue !== null && s.saturation !== null, hue: s.hue ?? d.hue, saturation: s.saturation ?? d.saturation,
  }
}

/** A new draft for a light that is not in the scene yet: it starts by setting power to on. */
export function draftForLight(device: Device, channel: Channel): ActionDraft {
  const d = defaults(channel.capabilities)
  return {
    deviceId: device.id, deviceName: device.name, channel: channel.id, channelLabel: channel.label, capabilities: channel.capabilities,
    includePower: true, on: true,
    includeBrightness: false, brightness: channel.brightness ?? d.brightness,
    includeWhite: false, colorTemp: channel.colorTemp ?? d.colorTemp,
    includeColor: false, hue: channel.hue ?? d.hue, saturation: channel.saturation ?? d.saturation,
  }
}

/** Includes or drops white; including it drops colour, because a light shows one or the other. */
export const toggleWhite = (d: ActionDraft): ActionDraft => ({ ...d, includeWhite: !d.includeWhite, includeColor: d.includeWhite ? d.includeColor : false })
export const toggleColor = (d: ActionDraft): ActionDraft => ({ ...d, includeColor: !d.includeColor, includeWhite: d.includeColor ? d.includeWhite : false })

/** Whether the draft sets anything at all. */
export const hasSettings = (d: ActionDraft) => d.includePower || d.includeBrightness || d.includeWhite || d.includeColor

/** The action to send: only included settings, and just "off" when the light is switched off. */
export function draftToAction(d: ActionDraft): SceneActionPayload {
  if (d.includePower && !d.on) return { deviceId: d.deviceId, channel: d.channel, settings: { on: false } }
  const settings: SceneActionPayload['settings'] = {}
  if (d.includePower) settings.on = true
  if (d.includeBrightness) settings.brightness = d.brightness
  if (d.includeWhite) settings.colorTemp = d.colorTemp
  if (d.includeColor) {
    settings.hue = d.hue
    settings.saturation = d.saturation
  }
  return { deviceId: d.deviceId, channel: d.channel, settings }
}

/** A short description of what an action does, for the scene list. */
export function describeAction(a: SceneAction): string {
  const s = a.settings
  if (s.on === false) return 'off'
  const parts: string[] = []
  if (s.on === true) parts.push('on')
  if (s.brightness !== null) parts.push(`${s.brightness}%`)
  if (s.colorTemp !== null) parts.push(`${s.colorTemp} K`)
  if (s.hue !== null) parts.push('colour')
  return parts.join(', ') || 'no change'
}

/** Lights that can still be added to a scene: every channel not already in it. */
export function addableLights(devices: Device[], inScene: ActionDraft[]): { device: Device; channel: Channel }[] {
  const used = new Set(inScene.map((a) => `${a.deviceId}/${a.channel}`))
  return devices.flatMap((device) => device.channels.filter((c) => !used.has(`${device.id}/${c.id}`)).map((channel) => ({ device, channel })))
}

export const sceneSubtitle = (scene: Scene) =>
  scene.kind === 'all_off' ? 'Every light' : scene.actions.length === 1 ? '1 light' : `${scene.actions.length} lights`
