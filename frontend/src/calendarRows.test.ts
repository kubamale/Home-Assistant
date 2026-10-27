import { describe, expect, it } from 'vitest'
import { calendarRows } from './calendarRows'
import type { CalendarEvent } from './types'

const now = Date.parse('2026-10-24T12:00:00Z')

function timed(id: string, startHour: number): CalendarEvent {
  const start = `2026-10-24T${String(startHour).padStart(2, '0')}:00:00Z`
  const end = `2026-10-24T${String(startHour + 1).padStart(2, '0')}:00:00Z`
  return { id, title: id, allDay: false, start, end, date: null, location: null, calendar: null }
}
const allDay: CalendarEvent = { id: 'holiday', title: 'Holiday', allDay: true, start: null, end: null, date: '2026-10-24', location: null, calendar: null }

const ids = (r: { rows: CalendarEvent[] }) => r.rows.map((e) => e.id)

describe('calendarRows', () => {
  it('puts all-day first and keeps finished events in place', () => {
    const r = calendarRows([timed('a', 8), timed('b', 13), allDay], now)
    expect(ids(r)).toEqual(['holiday', 'a', 'b'])
  })

  it('drops the oldest finished events first when there is a limit', () => {
    const events = [timed('a', 7), timed('b', 8), timed('c', 13), timed('d', 15)]
    const r = calendarRows(events, now, 3)
    expect(ids(r)).toEqual(['b', 'c', 'd'])
    expect(r.hiddenDone).toBe(1)
    expect(r.hidden).toBe(0)
  })

  it('cuts upcoming events only when nothing finished is left to drop', () => {
    const r = calendarRows([allDay, timed('c', 13), timed('d', 15), timed('e', 17)], now, 3)
    expect(ids(r)).toEqual(['holiday', 'c', 'd'])
    expect(r.hidden).toBe(1)
  })
})
