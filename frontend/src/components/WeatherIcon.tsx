interface Props {
  condition: string
  isDay?: boolean
  size?: number
}

// Small hand-drawn SVG set instead of emoji: the Pi's Chromium may not have a colour emoji font.
const CLOUD = 'M20 46h26a9 9 0 0 0 .8-17.97A13 13 0 0 0 21.6 25 10.5 10.5 0 0 0 20 46z'

function Sun({ cx, cy, r }: { cx: number; cy: number; r: number }) {
  const rays = Array.from({ length: 8 }, (_, i) => {
    const a = (i * Math.PI) / 4
    return (
      <line
        key={i}
        x1={cx + Math.cos(a) * (r + 4)}
        y1={cy + Math.sin(a) * (r + 4)}
        x2={cx + Math.cos(a) * (r + 9)}
        y2={cy + Math.sin(a) * (r + 9)}
      />
    )
  })
  return (
    <g className="wi-sun">
      <circle cx={cx} cy={cy} r={r} />
      <g strokeWidth="3" strokeLinecap="round" stroke="currentColor">
        {rays}
      </g>
    </g>
  )
}

function Moon() {
  return <path className="wi-moon" d="M40 14a18 18 0 1 0 12 30 15 15 0 0 1-12-30z" />
}

function Cloud({ dark }: { dark?: boolean }) {
  return <path className={dark ? 'wi-cloud wi-cloud-dark' : 'wi-cloud'} d={CLOUD} />
}

function Drops({ count, slant = 3 }: { count: number; slant?: number }) {
  const xs = [26, 34, 42].slice(0, count)
  return (
    <g className="wi-rain" strokeWidth="3" strokeLinecap="round">
      {xs.map((x) => (
        <line key={x} x1={x + slant} y1="50" x2={x} y2="58" />
      ))}
    </g>
  )
}

function Flakes() {
  return (
    <g className="wi-snow">
      {[26, 34, 42].map((x, i) => (
        <circle key={x} cx={x} cy={53 + (i % 2) * 4} r="2.4" />
      ))}
    </g>
  )
}

export function WeatherIcon({ condition, isDay = true, size = 64 }: Props) {
  let content
  switch (condition) {
    case 'clear':
      content = isDay ? <Sun cx={32} cy={32} r={12} /> : <Moon />
      break
    case 'mostly-clear':
    case 'partly-cloudy':
      content = (
        <>
          {isDay ? <Sun cx={24} cy={24} r={9} /> : <Moon />}
          <Cloud />
        </>
      )
      break
    case 'cloudy':
      content = <Cloud />
      break
    case 'fog':
      content = (
        <g className="wi-fog" strokeWidth="4" strokeLinecap="round">
          <line x1="14" y1="24" x2="50" y2="24" />
          <line x1="10" y1="34" x2="46" y2="34" />
          <line x1="16" y1="44" x2="52" y2="44" />
        </g>
      )
      break
    case 'drizzle':
      content = (
        <>
          <Cloud />
          <Drops count={2} slant={1} />
        </>
      )
      break
    case 'rain':
    case 'showers':
      content = (
        <>
          <Cloud dark />
          <Drops count={3} />
        </>
      )
      break
    case 'freezing-rain':
      content = (
        <>
          <Cloud dark />
          <Drops count={2} />
          <Flakes />
        </>
      )
      break
    case 'snow':
      content = (
        <>
          <Cloud />
          <Flakes />
        </>
      )
      break
    case 'thunderstorm':
      content = (
        <>
          <Cloud dark />
          <polygon className="wi-bolt" points="34,46 27,57 33,57 30,65 41,52 35,52 38,46" />
        </>
      )
      break
    default:
      content = <Cloud />
  }
  return (
    <svg className="weather-icon" viewBox="0 0 64 64" width={size} height={size} role="img" aria-label={condition}>
      {content}
    </svg>
  )
}
