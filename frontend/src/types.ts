// Shapes returned by the backend API (see PRD section 6).

export interface Location {
  name: string
  latitude: number
  longitude: number
  timezone: string
}

export interface Place extends Location {
  region: string | null
  country: string | null
}

export interface Settings {
  location: Location | null
}

export interface SessionInfo {
  role: 'user' | 'display'
  label: string
  /** The person this phone or display acts as. */
  userId: number
  name: string
  /** Manages the home itself: rooms, devices, location, voice, the agent and who has an account. */
  admin: boolean
}

export interface AppUser {
  id: number
  name: string
  admin: boolean
  createdAt: string
}

export interface WeatherData {
  locationName: string
  current: {
    temperature: number
    apparentTemperature: number
    condition: string
    conditionLabel: string
    windSpeed: number
    precipitation: number
    isDay: boolean
  }
  today: {
    minTemperature: number
    maxTemperature: number
    precipitationProbabilityMax: number | null
    condition: string
    conditionLabel: string
    sunrise: string | null
    sunset: string | null
  }
  hourly: { time: string; temperature: number; precipitationProbability: number | null; condition: string }[]
}

export interface Fetched {
  fetchedAt: string | null
  stale: boolean
  lastError: string | null
}

export interface WeatherSnapshot extends Fetched {
  configured: boolean
  data: WeatherData | null
}

export interface Task {
  id: number
  title: string
  dueDate: string | null
  done: boolean
  overdue: boolean
}

export interface CalendarEvent {
  id: string
  title: string
  allDay: boolean
  start: string | null
  end: string | null
  date: string | null
  location: string | null
  calendar: string | null
}

export interface CalendarSnapshot extends Fetched {
  configured: boolean
  events: CalendarEvent[]
  nextEventId: string | null
}

/** What a channel supports besides power. */
export interface Capabilities {
  brightness: boolean
  /** Kelvin range of the adjustable white, or null. */
  colorTemp: { min: number; max: number } | null
  color: boolean
}

/** One independently controllable light of a device, for example a light bar's main or background light. */
export interface Channel {
  id: string
  label: string
  capabilities: Capabilities
  on: boolean
  brightness: number | null
  colorTemp: number | null
  hue: number | null
  saturation: number | null
  colorMode: 'temperature' | 'color' | null
  /** Slot 1 to 4 in the dashboard's quick access panel, or null. */
  quickAccess: number | null
}

/** A partial change of one channel; hue and saturation go together. */
export interface ChannelChange {
  on?: boolean
  brightness?: number
  colorTemp?: number
  hue?: number
  saturation?: number
}

export interface Device {
  id: string
  /** The name to show: the user's own if set, otherwise the one the device reports. */
  name: string
  reportedName: string
  type: string
  roomId: number | null
  /** True when any channel is on. */
  power: boolean
  reachable: boolean
  /** False when the agent no longer lists the device (it stays in its room). */
  present: boolean
  updatedAt: string | null
  lastSeenAt: string
  channels: Channel[]
  /** False when the device is in a room that is view only for this person: they see it but cannot change it. */
  controllable: boolean
}

/** A quick access slot: a light (device and channel) or a scene. */
export interface PinItem {
  deviceId?: string
  channel?: string
  sceneId?: number
}

/** The settings of a scene action; null means "leave that setting alone". */
export interface SceneSettings {
  on: boolean | null
  brightness: number | null
  colorTemp: number | null
  hue: number | null
  saturation: number | null
}

export interface SceneAction {
  deviceId: string
  deviceName: string
  channel: string
  channelLabel: string
  /** What the light supports now, null if the light no longer exists. */
  capabilities: Capabilities | null
  settings: SceneSettings
}

export interface Scene {
  id: number
  name: string
  /** "all_off" is the built-in scene: it has no stored actions. */
  kind: 'custom' | 'all_off'
  quickAccess: number | null
  /** Everyone's scene; otherwise it is the signed-in person's own. */
  shared: boolean
  /** Whether the signed-in person may change or delete it. */
  editable: boolean
  actions: SceneAction[]
}

export interface SceneFailure {
  deviceId: string
  deviceName: string
  channel: string
  reason: string
}

export interface ApplyResult {
  applied: number
  failed: SceneFailure[]
  /** Lights left out because they are in a room the person may not control. */
  skipped?: number
}

/** An action as sent to the server: only the settings that are included. */
export interface SceneActionPayload {
  deviceId: string
  channel: string
  settings: Partial<{ on: boolean; brightness: number; colorTemp: number; hue: number; saturation: number }>
}

export interface DevicesSnapshot {
  devices: Device[]
  agentOnline: boolean
}

/** Who may use a room: everyone, everyone to look and some people to control, or only some people. */
export type RoomAccess = 'open' | 'view' | 'private'

export interface Room {
  id: number
  name: string
  position: number
  access: RoomAccess
  /** Whether the person asking may control the devices in it (false where it is view only for them). */
  canControl: boolean
  /** The people it names. Only the admin is told; empty for everyone else. */
  members: number[]
}

export interface CredentialView {
  id: string
  kind: 'user' | 'display' | 'agent'
  label: string
  createdAt: string
  lastUsedAt: string | null
  current: boolean
  /** The room a display is in; phones and the agent have none. */
  roomId: number | null
  /** The person a phone or display belongs to. */
  userId: number | null
  userName: string | null
}

export type WidgetType = 'clock' | 'weather' | 'calendar' | 'tasks' | 'quickAccess'

/** One card on a wall display. `count` is how many hours, events or tasks; `hour12` is for the clock. */
export interface Widget {
  type: WidgetType
  count?: number | null
  hour12?: boolean | null
}

export interface DisplayLayout {
  columns: { widgets: Widget[] }[]
  /** Whether the display listens for its wake word. */
  voice: boolean
  /** Whether the background moves with the weather (rain falls, clouds drift). Off suits a slow screen. */
  motion: boolean
}

export interface DisplayConfig {
  label: string
  owner: { id: number; name: string }
  layout: DisplayLayout
}

export interface CalendarConnection {
  connected: boolean
  /** Showing the calendar set up on the server (the admin's, until they connect their own). */
  serverAccount: boolean
  url: string | null
  username: string | null
  calendars: string[]
}

export interface PairStart {
  deviceCode: string
  userCode: string
  approvePath: string
  expiresIn: number
  pollIntervalSeconds: number
}

/** The answer to one sentence typed to the assistant. */
export interface VoiceReply {
  reply: string
  /** What was done, for example "Desk lamp: on, 40%". */
  actions: string[]
  /** What was attempted and did not work. */
  failed: string[]
  conversationId: string
}

/** `available`: the typed assistant works. `listening` and `speaking`: the microphone and the speaker do. */
export interface VoiceStatus {
  available: boolean
  model: string
  listening: boolean
  speaking: boolean
}

export interface VoiceSettings {
  voice: string
  style: string
  /** Hints for the listening service, such as pl-PL; empty lets it detect the language. */
  languages: string[]
  speakReplies: boolean
  /** How easily a door screen takes a sound for its wake word. */
  wakeSensitivity: 'low' | 'normal' | 'high'
  /** What a door screen says when it hears the wake word, such as "Yes?"; empty for nothing. */
  wakeReply: string
}

export interface VoiceVoices {
  voices: string[]
  defaultVoice: string
}
