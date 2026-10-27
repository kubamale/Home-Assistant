// Display helpers. Times are shown in the saved location's timezone, so the phone, the door screen
// and the server always agree on what "today" and "now" are.

export function formatTime(iso: string, timeZone?: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false, timeZone })
}

/** Hourly forecast times arrive as local wall-clock strings such as 2026-10-24T14:00. */
export function formatLocalHour(local: string): string {
  return local.slice(11, 16)
}

export function formatClock(now: Date, timeZone?: string): string {
  return now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false, timeZone })
}

export function formatDate(now: Date, timeZone?: string): string {
  return now.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long', timeZone })
}

export function formatTemp(value: number): string {
  return `${Math.round(value)}°`
}

export function formatRange(start: string, end: string, timeZone?: string): string {
  return `${formatTime(start, timeZone)}–${formatTime(end, timeZone)}`
}

/** "5 min ago" style label for stale data banners. */
export function ago(iso: string | null, now: Date = new Date()): string {
  if (!iso) return 'never'
  const minutes = Math.max(0, Math.round((now.getTime() - new Date(iso).getTime()) / 60000))
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  return `${hours} h ago`
}

/** The current hour as a wall-clock key like 2026-10-24T14, in the given timezone. */
export function localHourKey(now: Date, timeZone?: string): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(now)
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? ''
  return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}`
}

export function formatDue(dueDate: string): string {
  const [y, m, d] = dueDate.split('-').map(Number)
  return new Date(y, m - 1, d).toLocaleDateString([], { day: 'numeric', month: 'short' })
}
