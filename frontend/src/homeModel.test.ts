import { hsvCss } from './color'
import { availabilityNote, channelTitle, describeApply, groupDevices, isUnavailable, moveItem, pinnedItems, tileState, toPinItems } from './homeModel'
import type { Channel, Device, Room, Scene } from './types'

function channel(id: string, over: Partial<Channel> = {}): Channel {
  return {
    id, label: id === 'main' ? 'Main' : 'Background', capabilities: { brightness: false, colorTemp: null, color: false },
    on: false, brightness: null, colorTemp: null, hue: null, saturation: null, colorMode: null, quickAccess: null, ...over,
  }
}

function device(id: string, over: Partial<Device> = {}): Device {
  return {
    id, name: id, reportedName: id, type: 'yeelight', roomId: null, power: false, reachable: true, present: true,
    updatedAt: null, lastSeenAt: '2026-10-24T11:00:00Z', channels: [channel('main')], ...over,
  }
}
const kitchen: Room = { id: 1, name: 'Kitchen', position: 0 }
const hall: Room = { id: 2, name: 'Hall', position: 1 }

describe('groupDevices', () => {
  it('keeps rooms in order, including empty ones, and collects unassigned devices', () => {
    const grouped = groupDevices([device('a', { roomId: 2 }), device('b'), device('c', { roomId: 2 })], [kitchen, hall])
    expect(grouped.rooms.map((g) => [g.room.name, g.devices.map((d) => d.id)])).toEqual([
      ['Kitchen', []],
      ['Hall', ['a', 'c']],
    ])
    expect(grouped.unassigned.map((d) => d.id)).toEqual(['b'])
  })

  it('treats a device whose room no longer exists as unassigned', () => {
    expect(groupDevices([device('a', { roomId: 99 })], [kitchen]).unassigned.map((d) => d.id)).toEqual(['a'])
  })
})

const movie: Scene = { id: 7, name: 'Movie', kind: 'custom', quickAccess: 2, actions: [] }
const unpinned: Scene = { id: 8, name: 'Reading', kind: 'custom', quickAccess: null, actions: [] }

describe('pinned items', () => {
  const bar = device('bar', { channels: [channel('main', { quickAccess: 3 }), channel('background', { quickAccess: 1 })] })
  const lamp = device('lamp', { channels: [channel('main', { quickAccess: 4 })] })

  it('lists pinned lights and scenes together in slot order', () => {
    const items = pinnedItems([lamp, bar, device('other')], [unpinned, movie])
    expect(items.map((i) => (i.kind === 'scene' ? `scene:${i.scene.name}` : `${i.device.id}/${i.channel.id}`))).toEqual([
      'bar/background', 'scene:Movie', 'bar/main', 'lamp/main',
    ])
    expect(toPinItems(items)).toEqual([
      { deviceId: 'bar', channel: 'background' }, { sceneId: 7 }, { deviceId: 'bar', channel: 'main' }, { deviceId: 'lamp', channel: 'main' },
    ])
  })

  it('names a channel by its device, adding the channel only when the device has several', () => {
    expect(channelTitle(bar, bar.channels[1])).toBe('bar · Background')
    expect(channelTitle(lamp, lamp.channels[0])).toBe('lamp')
  })
})

describe('describeApply', () => {
  it('says so when everything went through', () => {
    expect(describeApply('Movie', { applied: 3, failed: [] })).toEqual({ ok: true, text: 'Movie applied' })
  })

  it('names the lights that did not respond', () => {
    const result = { applied: 1, failed: [
      { deviceId: 'a', deviceName: 'Desk lamp', channel: 'main', reason: 'Unavailable' },
      { deviceId: 'a', deviceName: 'Desk lamp', channel: 'extra', reason: 'Unavailable' },
    ] }
    expect(describeApply('Movie', result)).toEqual({ ok: false, text: 'Movie: Desk lamp did not respond (unavailable)' })
  })
})

describe('moveItem', () => {
  it('moves an item up or down and ignores moves past the ends', () => {
    expect(moveItem(['a', 'b', 'c'], 2, -1)).toEqual(['a', 'c', 'b'])
    expect(moveItem(['a', 'b', 'c'], 0, 1)).toEqual(['b', 'a', 'c'])
    expect(moveItem(['a', 'b', 'c'], 0, -1)).toEqual(['a', 'b', 'c'])
    expect(moveItem(['a', 'b', 'c'], 2, 1)).toEqual(['a', 'b', 'c'])
  })

  it('does not change the original list', () => {
    const list = ['a', 'b']
    moveItem(list, 0, 1)
    expect(list).toEqual(['a', 'b'])
  })
})

describe('availability', () => {
  const now = new Date('2026-10-24T12:00:00Z')

  it('explains why a device cannot be switched', () => {
    expect(availabilityNote(device('a'), false, now)).toBeNull()
    expect(availabilityNote(device('a', { reachable: false }), false, now)).toBe('Unavailable')
    expect(availabilityNote(device('a'), true, now)).toBe('Unavailable')
    expect(availabilityNote(device('a', { present: false, reachable: false }), false, now)).toBe('Not seen · last 1 h ago')
  })

  it('treats an offline agent, a missing device and an unreachable one as unavailable', () => {
    expect(isUnavailable(device('a'), false)).toBe(false)
    expect(isUnavailable(device('a'), true)).toBe(true)
    expect(isUnavailable(device('a', { present: false }), false)).toBe(true)
    expect(isUnavailable(device('a', { reachable: false }), false)).toBe(true)
  })
})

describe('tileState', () => {
  const dimmable = channel('main', { on: true, brightness: 60, capabilities: { brightness: true, colorTemp: null, color: false } })

  it('shows brightness when the light is on and can dim', () => {
    expect(tileState(dimmable, false)).toBe('On · 60%')
    expect(tileState({ ...dimmable, on: false }, false)).toBe('Off')
    expect(tileState(channel('main', { on: true }), false)).toBe('On')
    expect(tileState(dimmable, true)).toBe('Unavailable')
  })
})

describe('hsvCss', () => {
  it('converts the bulbs hue and saturation to a CSS colour', () => {
    expect(hsvCss(0, 100)).toBe('hsl(0 100% 50%)')
    expect(hsvCss(120, 100)).toBe('hsl(120 100% 50%)')
    expect(hsvCss(200, 0)).toBe('hsl(200 0% 100%)')
    expect(hsvCss(240, 50)).toBe('hsl(240 100% 75%)')
  })
})
