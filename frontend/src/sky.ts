/** The skies the background can show. Each has a gradient in styles.css, pastel in light mode and deep in dark mode. */
export type Sky = 'clear-day' | 'clear-night' | 'cloudy-day' | 'cloudy-night' | 'rain' | 'snow' | 'storm' | 'fog'

/** How the background behaves: it follows the weather, stays one calm gradient, or is the plain grey. */
export type BackgroundMode = 'sky' | 'calm' | 'plain'

export const BACKGROUND_MODES: BackgroundMode[] = ['sky', 'calm', 'plain']

export function isBackgroundMode(value: unknown): value is BackgroundMode {
  return typeof value === 'string' && (BACKGROUND_MODES as string[]).includes(value)
}

/** Daylight when the weather does not say: between 7 and 19 o'clock. */
export const isDaytime = (hour: number): boolean => hour >= 7 && hour < 19

/**
 * Which sky matches the weather. {@code condition} is the weather's own key (clear, rain, ...), {@code isDay} what the weather
 * service says about daylight, and {@code hour} the local hour, used when either is missing so the background still has a
 * time of day before the weather has arrived.
 */
export function skyFor(condition: string | null | undefined, isDay: boolean | null | undefined, hour: number): Sky {
  const day = isDay ?? isDaytime(hour)
  switch (condition) {
    case 'cloudy':
      return day ? 'cloudy-day' : 'cloudy-night'
    case 'fog':
      return 'fog'
    case 'drizzle':
    case 'rain':
    case 'showers':
    case 'freezing-rain':
      return 'rain'
    case 'snow':
      return 'snow'
    case 'thunderstorm':
      return 'storm'
    default:
      // clear, mostly clear, partly cloudy, unknown, or no weather yet: a clear sky for the time of day
      return day ? 'clear-day' : 'clear-night'
  }
}

/** What goes on the page's root element: the sky for the weather, one fixed sky for calm, nothing for plain. */
export function backgroundAttributes(mode: BackgroundMode, sky: Sky): { bg: BackgroundMode; sky: string } {
  return { bg: mode, sky: mode === 'calm' ? 'calm' : sky }
}
