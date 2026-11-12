import { useSyncExternalStore } from 'react'

const KEY = 'hd.motion'
const CHANGED = 'hd-motion-changed'

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === 'on'
  } catch {
    return false // private browsing: the default, and the choice just does not last
  }
}

function subscribe(onChange: () => void): () => void {
  window.addEventListener(CHANGED, onChange)
  window.addEventListener('storage', onChange) // another tab changed it
  return () => {
    window.removeEventListener(CHANGED, onChange)
    window.removeEventListener('storage', onChange)
  }
}

/**
 * Whether the weather moves in the background of this phone. Off until the person turns it on, because it costs battery;
 * a wall display has its own setting (see the display's layout). The system's "reduce motion" always wins, in the CSS.
 */
export function useMotionPreference(): [boolean, (on: boolean) => void] {
  const on = useSyncExternalStore(subscribe, read, () => false)
  const set = (next: boolean) => {
    try {
      localStorage.setItem(KEY, next ? 'on' : 'off')
    } catch {
      // not remembered, but it still applies until the page is reloaded
    }
    window.dispatchEvent(new Event(CHANGED))
  }
  return [on, set]
}
