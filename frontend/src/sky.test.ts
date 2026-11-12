import { backgroundAttributes, isBackgroundMode, isDaytime, skyFor } from './sky'

describe('sky for the weather', () => {
  it('follows the weather service about day and night', () => {
    expect(skyFor('clear', true, 3)).toBe('clear-day') // the service wins over the clock
    expect(skyFor('clear', false, 12)).toBe('clear-night')
    expect(skyFor('cloudy', true, 12)).toBe('cloudy-day')
    expect(skyFor('cloudy', false, 12)).toBe('cloudy-night')
  })

  it('treats thin cloud as a clear sky, and all kinds of rain as rain', () => {
    expect(skyFor('partly-cloudy', true, 12)).toBe('clear-day')
    expect(skyFor('mostly-clear', false, 12)).toBe('clear-night')
    for (const wet of ['drizzle', 'rain', 'showers', 'freezing-rain']) expect(skyFor(wet, true, 12)).toBe('rain')
  })

  it('has a sky for snow, storms and fog, day or night', () => {
    expect(skyFor('snow', false, 1)).toBe('snow')
    expect(skyFor('thunderstorm', true, 12)).toBe('storm')
    expect(skyFor('fog', true, 12)).toBe('fog')
  })

  it('uses the clock until the weather arrives', () => {
    expect(skyFor(undefined, undefined, 12)).toBe('clear-day')
    expect(skyFor(undefined, undefined, 23)).toBe('clear-night')
    expect(skyFor('unknown', null, 6)).toBe('clear-night')
    expect(isDaytime(7)).toBe(true)
    expect(isDaytime(19)).toBe(false)
  })
})

describe('background setting', () => {
  it('knows the three modes and nothing else', () => {
    expect(['sky', 'calm', 'plain'].every(isBackgroundMode)).toBe(true)
    expect(isBackgroundMode('rainbow')).toBe(false)
    expect(isBackgroundMode(null)).toBe(false)
  })

  it('shows the weather sky, one fixed sky, or none', () => {
    expect(backgroundAttributes('sky', 'rain')).toEqual({ bg: 'sky', sky: 'rain' })
    expect(backgroundAttributes('calm', 'rain')).toEqual({ bg: 'calm', sky: 'calm' })
    expect(backgroundAttributes('plain', 'rain').bg).toBe('plain')
  })
})
