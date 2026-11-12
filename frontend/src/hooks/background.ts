import { useSyncExternalStore } from 'react'
import { isBackgroundMode, type BackgroundMode } from '../sky'

const KEY = 'hd.background'
const CHANGED = 'hd-background-changed'

function read(): BackgroundMode {
  try {
    const value = localStorage.getItem(KEY)
    return isBackgroundMode(value) ? value : 'sky'
  } catch {
    return 'sky' // private browsing: the default, and the choice just does not last
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

/** The background the person chose on this device (the weather sky until they choose otherwise). */
export function useBackgroundMode(): [BackgroundMode, (mode: BackgroundMode) => void] {
  const mode = useSyncExternalStore(subscribe, read, () => 'sky' as BackgroundMode)
  const set = (next: BackgroundMode) => {
    try {
      localStorage.setItem(KEY, next)
    } catch {
      // not remembered, but it still applies until the page is reloaded
    }
    window.dispatchEvent(new Event(CHANGED))
  }
  return [mode, set]
}
