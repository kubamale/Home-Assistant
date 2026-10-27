import type { CalendarEvent } from './types'

export const isDone = (e: CalendarEvent, now: number) => !e.allDay && !(e.end && new Date(e.end).getTime() > now)

export interface CalendarRows {
  rows: CalendarEvent[]
  /** Events that did not fit and were cut off the end. */
  hidden: number
  /** Finished events dropped to make room for the rest. */
  hiddenDone: number
}

/**
 * All-day events first, then timed events in order, finished ones included (the card crosses them out).
 * With a row limit, the oldest finished events make room first, then whatever is left over is cut.
 */
export function calendarRows(events: CalendarEvent[], now: number, limit?: number): CalendarRows {
  const rows = [...events.filter((e) => e.allDay), ...events.filter((e) => !e.allDay)]
  if (!limit || rows.length <= limit) return { rows, hidden: 0, hiddenDone: 0 }
  let hiddenDone = 0
  for (let i = 0; i < rows.length && rows.length > limit; ) {
    if (isDone(rows[i], now)) {
      rows.splice(i, 1)
      hiddenDone++
    } else i++
  }
  const hidden = Math.max(0, rows.length - limit)
  return { rows: rows.slice(0, limit), hidden, hiddenDone }
}
