import { calendarRows, isDone } from '../calendarRows'
import { formatRange } from '../format'
import { useCalendar, useZone } from '../hooks/data'
import { useNow } from '../hooks/useClock'
import type { CalendarEvent } from '../types'
import { Stale } from './Stale'

export function CalendarCard({ limit }: { limit?: number }) {
  const { data, isPending } = useCalendar()
  const zone = useZone()
  const nowDate = useNow() // re-render each minute so finished events drop off

  if (isPending) return <section className="card calendar"><h2>Today</h2><p className="muted">Loading events…</p></section>
  if (!data) return <section className="card calendar"><h2>Today</h2><p className="muted">Calendar unavailable</p></section>
  if (!data.configured) {
    return (
      <section className="card calendar">
        <h2>Today</h2>
        <p className="muted">No calendar connected.</p>
      </section>
    )
  }

  const now = nowDate.getTime()
  const { rows, hidden, hiddenDone } = calendarRows(data.events, now, limit)

  return (
    <section className="card calendar" aria-label="Calendar">
      <h2>Today</h2>
      {rows.length === 0 && <p className="muted">Nothing planned today.</p>}
      <ul className="events">
        {rows.map((e) => (
          <EventRow key={e.id} event={e} next={e.id === data.nextEventId} zone={zone} now={now} />
        ))}
      </ul>
      {(hidden > 0 || hiddenDone > 0) && (
        <p className="muted small">
          {hidden > 0 ? `+${hidden} more` : ''}
          {hidden > 0 && hiddenDone > 0 ? ' · ' : ''}
          {hiddenDone > 0 ? `${hiddenDone} done` : ''}
        </p>
      )}
      <Stale fetchedAt={data.fetchedAt} stale={data.stale} error={data.lastError} />
    </section>
  )
}

function EventRow({ event, next, zone, now }: { event: CalendarEvent; next: boolean; zone?: string; now: number }) {
  const running = !event.allDay && event.start != null && new Date(event.start).getTime() <= now
  const classes = ['event', next && 'next', event.allDay && 'all-day', isDone(event, now) && 'done']
  return (
    <li className={classes.filter(Boolean).join(' ')}>
      <time className="event-time">
        {event.allDay ? 'All day' : event.start && event.end ? formatRange(event.start, event.end, zone) : ''}
      </time>
      <div className="event-body">
        <span className="event-title">{event.title}</span>
        {event.location && <span className="event-place">{event.location}</span>}
      </div>
      {next && <span className="badge">{running ? 'Now' : 'Next'}</span>}
    </li>
  )
}
