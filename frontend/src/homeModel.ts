import { ago } from './format'
import type { ApplyResult, Channel, Device, PinItem, Room, Scene } from './types'

export const QUICK_ACCESS_SLOTS = 4

export interface RoomGroup {
  room: Room
  devices: Device[]
}

/** A quick access slot: a light (a channel with its device) or a scene. */
export type PinnedItem =
  | { kind: 'light'; device: Device; channel: Channel; slot: number }
  | { kind: 'scene'; scene: Scene; slot: number }

/** Rooms in the server's order with their devices; devices without a (known) room are unassigned. */
export function groupDevices(devices: Device[], rooms: Room[]): { unassigned: Device[]; rooms: RoomGroup[] } {
  const known = new Set(rooms.map((r) => r.id))
  return {
    unassigned: devices.filter((d) => d.roomId === null || !known.has(d.roomId)),
    rooms: rooms.map((room) => ({ room, devices: devices.filter((d) => d.roomId === room.id) })),
  }
}

/** The pinned lights and scenes in slot order. */
export function pinnedItems(devices: Device[], scenes: Scene[]): PinnedItem[] {
  const lights: PinnedItem[] = devices.flatMap((device) =>
    device.channels.flatMap((channel) => (channel.quickAccess === null ? [] : [{ kind: 'light' as const, device, channel, slot: channel.quickAccess }])),
  )
  const pinnedScenes: PinnedItem[] = scenes.flatMap((scene) => (scene.quickAccess === null ? [] : [{ kind: 'scene' as const, scene, slot: scene.quickAccess }]))
  return [...lights, ...pinnedScenes].sort((a, b) => a.slot - b.slot)
}

export const toPinItems = (items: PinnedItem[]): PinItem[] =>
  items.map((i) => (i.kind === 'scene' ? { sceneId: i.scene.id } : { deviceId: i.device.id, channel: i.channel.id }))

/** Whether a pin item is this light. */
export const isLight = (item: PinItem, deviceId: string, channel: string) => item.deviceId === deviceId && item.channel === channel

/** What to call a channel: the device's name, plus the channel's label when the device has several. */
export const channelTitle = (device: Device, channel: Channel) =>
  device.channels.length > 1 ? `${device.name} · ${channel.label}` : device.name

/** A copy of the list with the item at {@code index} moved by {@code delta} places; out of range changes nothing. */
export function moveItem<T>(list: T[], index: number, delta: number): T[] {
  const target = index + delta
  if (index < 0 || index >= list.length || target < 0 || target >= list.length) return list
  const next = [...list]
  const [item] = next.splice(index, 1)
  next.splice(target, 0, item)
  return next
}

export const isUnavailable = (d: Device, agentDown: boolean) => agentDown || !d.present || !d.reachable

/** Why a device cannot be switched, or null when it can. */
export function availabilityNote(d: Device, agentDown: boolean, now: Date = new Date()): string | null {
  if (!d.present) return `Not seen · last ${ago(d.lastSeenAt, now)}`
  if (agentDown || !d.reachable) return 'Unavailable'
  return null
}

/** The line under a quick access tile's name. */
export function tileState(channel: Channel, unavailable: boolean): string {
  if (unavailable) return 'Unavailable'
  if (!channel.on) return 'Off'
  return channel.capabilities.brightness && channel.brightness !== null ? `On · ${channel.brightness}%` : 'On'
}

/** What to tell the user after a scene was applied. */
export function describeApply(sceneName: string, result: ApplyResult): { ok: boolean; text: string } {
  if (result.failed.length === 0) return { ok: true, text: `${sceneName} applied` }
  const names = [...new Set(result.failed.map((f) => f.deviceName))].join(', ')
  const reason = result.failed[0].reason
  return { ok: false, text: `${sceneName}: ${names} did not respond${reason ? ` (${reason.toLowerCase()})` : ''}` }
}
