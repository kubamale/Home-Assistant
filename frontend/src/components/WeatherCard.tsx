import { Link } from 'react-router-dom'
import { formatLocalHour, formatTemp, localHourKey } from '../format'
import { useWeather, useZone } from '../hooks/data'
import { Stale } from './Stale'
import { WeatherIcon } from './WeatherIcon'

export function WeatherCard({ hours = 6 }: { hours?: number }) {
  const { data, isPending } = useWeather()
  const zone = useZone()

  if (isPending) return <section className="card weather"><p className="muted">Loading weather…</p></section>
  if (!data) return <section className="card weather"><p className="muted">Weather unavailable</p></section>
  if (!data.configured) {
    return (
      <section className="card weather">
        <h2>Weather</h2>
        <p className="muted">No location set yet.</p>
        <Link className="button" to="/settings">Set your location</Link>
      </section>
    )
  }
  if (!data.data) {
    return (
      <section className="card weather">
        <h2>Weather</h2>
        <p className="muted">{data.lastError ? 'Weather is unavailable right now.' : 'Fetching the forecast…'}</p>
      </section>
    )
  }

  const w = data.data
  const upcoming = nextHours(w.hourly, hours, localHourKey(new Date(), zone))
  return (
    <section className={`card weather ${w.current.isDay ? 'day' : 'night'}`} aria-label="Weather">
      <div className="weather-main">
        <WeatherIcon condition={w.current.condition} isDay={w.current.isDay} size={96} />
        <div>
          <div className="temp">{formatTemp(w.current.temperature)}</div>
          <div className="condition">{w.current.conditionLabel}</div>
        </div>
      </div>
      <div className="weather-meta">
        <span>{w.locationName}</span>
        <span>
          {formatTemp(w.today.minTemperature)} / {formatTemp(w.today.maxTemperature)}
        </span>
        <span>Feels {formatTemp(w.current.apparentTemperature)}</span>
        <span>Wind {Math.round(w.current.windSpeed)} km/h</span>
        {w.today.precipitationProbabilityMax != null && <span>Rain {w.today.precipitationProbabilityMax}%</span>}
      </div>
      {upcoming.length > 0 && (
        <ol className="hours">
          {upcoming.map((h) => (
            <li key={h.time}>
              <span className="hour">{formatLocalHour(h.time)}</span>
              <WeatherIcon condition={h.condition} isDay={isDaytime(h.time, w.today.sunrise, w.today.sunset)} size={32} />
              <span>{formatTemp(h.temperature)}</span>
              {h.precipitationProbability != null && h.precipitationProbability >= 20 && (
                <span className="rain-chance">{h.precipitationProbability}%</span>
              )}
            </li>
          ))}
        </ol>
      )}
      <Stale fetchedAt={data.fetchedAt} stale={data.stale} error={data.lastError} />
    </section>
  )
}

/**
 * The next few hours still ahead. Hourly times are wall-clock strings in the location's timezone,
 * so they are compared with the current wall-clock hour in that same zone.
 */
function nextHours<T extends { time: string }>(hourly: T[], count: number, nowKey: string): T[] {
  const found = hourly.findIndex((h) => h.time.slice(0, 13) >= nowKey)
  const start = found === -1 ? 0 : found
  return hourly.slice(start, start + count)
}

/** Hourly times, sunrise and sunset are all local wall-clock strings, so they compare as text. */
function isDaytime(time: string, sunrise: string | null, sunset: string | null): boolean {
  if (!sunrise || !sunset) return true
  return time >= sunrise.slice(0, 13) + ':00' && time < sunset.slice(0, 13) + ':00'
}
