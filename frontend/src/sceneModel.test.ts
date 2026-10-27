import { addableLights, describeAction, draftForLight, draftFromAction, draftToAction, hasSettings, toggleColor, toggleWhite } from './sceneModel'
import type { Channel, Device, SceneAction } from './types'

const caps = { brightness: true, colorTemp: { min: 1700, max: 6500 }, color: true }

function action(settings: Partial<SceneAction['settings']>, over: Partial<SceneAction> = {}): SceneAction {
  return {
    deviceId: 'bar', deviceName: 'Bar', channel: 'background', channelLabel: 'Background', capabilities: caps,
    settings: { on: null, brightness: null, colorTemp: null, hue: null, saturation: null, ...settings }, ...over,
  }
}

function channel(id: string): Channel {
  return { id, label: id, capabilities: caps, on: true, brightness: 40, colorTemp: 3000, hue: 120, saturation: 70, colorMode: 'color', quickAccess: null }
}

describe('scene drafts', () => {
  it('round-trips a saved action, keeping only the settings that were included', () => {
    const saved = action({ on: true, brightness: 20, hue: 220, saturation: 100 })
    const draft = draftFromAction(saved)
    expect(draft).toMatchObject({ includePower: true, includeBrightness: true, includeColor: true, includeWhite: false, hue: 220 })
    expect(draftToAction(draft)).toEqual({ deviceId: 'bar', channel: 'background', settings: { on: true, brightness: 20, hue: 220, saturation: 100 } })
  })

  it('sends only "off" for a light that is switched off', () => {
    const draft = { ...draftFromAction(action({ on: false })), includeBrightness: true }
    expect(draftToAction(draft).settings).toEqual({ on: false })
  })

  it('leaves out settings that are not included', () => {
    const draft = draftFromAction(action({ brightness: 60 }))
    expect(draft.includePower).toBe(false)
    expect(draftToAction(draft).settings).toEqual({ brightness: 60 })
  })

  it('lets a light show white or colour, never both', () => {
    let draft = draftFromAction(action({ hue: 10, saturation: 90 }))
    draft = toggleWhite(draft)
    expect(draft).toMatchObject({ includeWhite: true, includeColor: false })
    draft = toggleColor(draft)
    expect(draft).toMatchObject({ includeWhite: false, includeColor: true })
    draft = toggleColor(draft)
    expect(draft).toMatchObject({ includeWhite: false, includeColor: false })
  })

  it('starts a new light with power on, taking the sliders from what the light shows now', () => {
    const device = { id: 'bar', name: 'Bar', channels: [channel('main')] } as unknown as Device
    const draft = draftForLight(device, device.channels[0])
    expect(draft).toMatchObject({ includePower: true, on: true, includeBrightness: false, brightness: 40, colorTemp: 3000, hue: 120 })
    expect(hasSettings(draft)).toBe(true)
    expect(hasSettings({ ...draft, includePower: false })).toBe(false)
  })

  it('offers only lights that are not in the scene yet', () => {
    const device = { id: 'bar', name: 'Bar', channels: [channel('main'), channel('background')] } as unknown as Device
    const inScene = [draftFromAction(action({ on: true }, { channel: 'main' }))]
    expect(addableLights([device], inScene).map((l) => l.channel.id)).toEqual(['background'])
  })

  it('describes an action in a few words', () => {
    expect(describeAction(action({ on: false }))).toBe('off')
    expect(describeAction(action({ on: true, brightness: 30, hue: 1, saturation: 1 }))).toBe('on, 30%, colour')
    expect(describeAction(action({ colorTemp: 3000 }))).toBe('3000 K')
  })
})
