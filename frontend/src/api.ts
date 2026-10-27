import type {
  CalendarSnapshot,
  ApplyResult,
  ChannelChange,
  CredentialView,
  DevicesSnapshot,
  PairStart,
  PinItem,
  Place,
  Room,
  Scene,
  SceneActionPayload,
  SessionInfo,
  Settings,
  Task,
  VoiceReply,
  VoiceStatus,
  WeatherSnapshot,
} from './types'

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!response.ok) {
    let message = response.statusText
    try {
      const problem = await response.json()
      message = problem.detail ?? problem.message ?? message
    } catch {
      // not a JSON error body; keep the status text
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return response.json() as Promise<T>
}

export type PairPoll = 'pending' | 'approved' | 'expired'

export const api = {
  // session
  me: () => request<SessionInfo>('GET', '/api/auth/me'),
  login: (password: string) => request<SessionInfo>('POST', '/api/auth/login', { password }),
  logout: () => request<void>('POST', '/api/auth/logout'),

  // display pairing
  pairStart: () => request<PairStart>('POST', '/api/auth/pair/start'),
  pairPoll: async (deviceCode: string): Promise<PairPoll> => {
    const response = await fetch(`/api/auth/pair/poll?deviceCode=${encodeURIComponent(deviceCode)}`, {
      credentials: 'same-origin',
    })
    if (response.status === 202) return 'pending'
    if (response.status === 200) return 'approved'
    if (response.status === 410) return 'expired'
    throw new ApiError(response.status, response.statusText)
  },
  pairApprove: (userCode: string, label?: string) =>
    request<{ id: string; label: string }>('POST', '/api/auth/pair/approve', { userCode, label }),

  // data
  weather: () => request<WeatherSnapshot>('GET', '/api/weather'),
  calendar: () => request<CalendarSnapshot>('GET', '/api/calendar/today'),
  tasks: (scope: 'today' | 'all' = 'today') => request<Task[]>('GET', `/api/tasks?scope=${scope}`),
  devices: () => request<DevicesSnapshot>('GET', '/api/devices'),
  rooms: async () => (await request<{ rooms: Room[] }>('GET', '/api/rooms')).rooms,
  scenes: async () => (await request<{ scenes: Scene[] }>('GET', '/api/scenes')).scenes,
  settings: () => request<Settings>('GET', '/api/settings'),

  // changes
  createTask: (title: string, dueDate?: string) => request<Task>('POST', '/api/tasks', { title, dueDate: dueDate || null }),
  setTaskDone: (id: number, done: boolean) => request<Task>('PATCH', `/api/tasks/${id}`, { done }),
  deleteTask: (id: number) => request<void>('DELETE', `/api/tasks/${id}`),
  setPower: (id: string, on: boolean) => request<unknown>('POST', `/api/devices/${encodeURIComponent(id)}/power`, { on }),
  setChannel: (id: string, channel: string, change: ChannelChange) =>
    request<unknown>('POST', `/api/devices/${encodeURIComponent(id)}/channels/${encodeURIComponent(channel)}`, change),

  // scenes
  createScene: (name: string, actions: SceneActionPayload[]) => request<Scene>('POST', '/api/scenes', { name, actions }),
  captureScene: (name: string, channels: { deviceId: string; channel: string }[]) =>
    request<Scene>('POST', '/api/scenes/capture', { name, channels }),
  updateScene: (id: number, name: string, actions?: SceneActionPayload[]) => request<Scene>('PUT', `/api/scenes/${id}`, { name, actions }),
  deleteScene: (id: number) => request<void>('DELETE', `/api/scenes/${id}`),
  reorderScenes: (sceneIds: number[]) => request<void>('PUT', '/api/scenes/order', { sceneIds }),
  applyScene: (id: number) => request<ApplyResult>('POST', `/api/scenes/${id}/apply`),

  // rooms, device organisation and quick access
  createRoom: (name: string) => request<Room>('POST', '/api/rooms', { name }),
  renameRoom: (id: number, name: string) => request<Room>('PATCH', `/api/rooms/${id}`, { name }),
  deleteRoom: (id: number) => request<void>('DELETE', `/api/rooms/${id}`),
  reorderRooms: (roomIds: number[]) => request<void>('PUT', '/api/rooms/order', { roomIds }),
  updateDevice: (id: string, change: { name?: string; roomId?: number | null }) =>
    request<void>('PATCH', `/api/devices/${encodeURIComponent(id)}`, {
      name: change.name,
      roomId: change.roomId ?? undefined,
      clearRoom: change.roomId === null ? true : undefined,
    }),
  forgetDevice: (id: string) => request<void>('DELETE', `/api/devices/${encodeURIComponent(id)}`),
  setQuickAccess: (items: PinItem[]) => request<void>('PUT', '/api/quick-access', { items }),

  // assistant
  voiceStatus: () => request<VoiceStatus>('GET', '/api/voice/status'),
  voiceCommand: (text: string, conversationId: string | null) =>
    request<VoiceReply>('POST', '/api/voice/command', { text, conversationId }),
  // settings
  geocode: (q: string) => request<Place[]>('GET', `/api/geocode?q=${encodeURIComponent(q)}`),
  saveLocation: (place: { name: string; latitude: number; longitude: number; timezone: string }) =>
    request<Settings>('PUT', '/api/settings/location', place),
  credentials: () => request<CredentialView[]>('GET', '/api/credentials'),
  revokeCredential: (id: string) => request<void>('DELETE', `/api/credentials/${id}`),
  createAgentToken: (label: string) =>
    request<{ id: string; token: string }>('POST', '/api/credentials/agent', { label }),
}
