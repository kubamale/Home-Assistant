import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { useSession, useWeather } from '../hooks/data'
import { useBackgroundMode } from '../hooks/background'
import { useMotionPreference } from '../hooks/motion'
import { useNow } from '../hooks/useClock'
import { backgroundAttributes, skyFor } from '../sky'

/**
 * Sets the page's background: a sky that follows the weather and the time of day. It sets attributes on the root element and
 * the gradients themselves live in styles.css. Without a weather reading (not signed in, no location yet) the sky follows the
 * clock.
 *
 * It also holds the layers for the weather's motion (clouds drifting, rain, snow, stars, fog). They are plain empty elements
 * that styles.css animates with transform and opacity only, and only while the root says data-motion="on" for the sky
 * background. Which screen decides: a phone by its own choice (Settings, Appearance), the wall by its display's layout (the
 * wall page sets data-motion itself).
 */
export function SkyBackground() {
  const signedIn = !!useSession().data
  const { data } = useWeather({ enabled: signedIn })
  const [mode] = useBackgroundMode()
  const [motion] = useMotionPreference()
  const onWall = useLocation().pathname.startsWith('/wall')
  const now = useNow()

  const current = data?.data?.current
  const sky = skyFor(current?.condition, current?.isDay, now.getHours())
  const { bg, sky: skyName } = backgroundAttributes(mode, sky)

  useEffect(() => {
    const root = document.documentElement
    root.dataset.bg = bg
    root.dataset.sky = skyName
  }, [bg, skyName])

  useEffect(() => {
    if (onWall) return // the wall page sets it from the display's layout
    document.documentElement.dataset.motion = motion ? 'on' : 'off'
  }, [onWall, motion])

  if (bg !== 'sky') return null
  return (
    <div className="weather-motion" aria-hidden="true">
      <i className="wm-cloud-1" />
      <i className="wm-cloud-2" />
      <i className="wm-cloud-3" />
      <i className="wm-sun" />
      <i className="wm-field-a" />
      <i className="wm-field-b" />
      <i className="wm-flash" />
    </div>
  )
}
