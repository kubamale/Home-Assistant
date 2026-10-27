import { useEffect } from 'react'

const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'touchstart', 'keydown', 'scroll'] as const

/** Calls {@code onIdle} once there has been no touch or input for {@code ms}. Only counts while {@code active}. */
export function useIdle(active: boolean, ms: number, onIdle: () => void) {
  useEffect(() => {
    if (!active) return
    let timer = window.setTimeout(onIdle, ms)
    const reset = () => {
      window.clearTimeout(timer)
      timer = window.setTimeout(onIdle, ms)
    }
    for (const event of ACTIVITY_EVENTS) window.addEventListener(event, reset, { passive: true, capture: true })
    return () => {
      window.clearTimeout(timer)
      for (const event of ACTIVITY_EVENTS) window.removeEventListener(event, reset, { capture: true })
    }
  }, [active, ms, onIdle])
}
