import { ago, formatLocalHour, formatTemp, localHourKey } from './format'

describe('format helpers', () => {
  it('rounds temperatures', () => {
    expect(formatTemp(9.5)).toBe('10°')
    expect(formatTemp(-3.6)).toBe('-4°')
  })

  it('cuts the hour out of a local ISO time', () => {
    expect(formatLocalHour('2026-10-24T14:00')).toBe('14:00')
  })

  it('builds the current wall-clock hour in the given timezone', () => {
    const instant = new Date('2026-10-24T23:30:00Z')
    expect(localHourKey(instant, 'UTC')).toBe('2026-10-24T23')
    // Warsaw is UTC+2 on that date, so it is already the next day there
    expect(localHourKey(instant, 'Europe/Warsaw')).toBe('2026-10-25T01')
    expect(localHourKey(instant, 'America/New_York')).toBe('2026-10-24T19')
  })

  it('describes how old data is', () => {
    const now = new Date('2026-10-24T12:00:00Z')
    expect(ago(null, now)).toBe('never')
    expect(ago('2026-10-24T11:59:40Z', now)).toBe('just now')
    expect(ago('2026-10-24T11:30:00Z', now)).toBe('30 min ago')
    expect(ago('2026-10-24T09:00:00Z', now)).toBe('3 h ago')
  })
})
