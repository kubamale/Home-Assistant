import { useQueryClient } from '@tanstack/react-query'
import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

export type ConnectionStatus = 'connecting' | 'live' | 'reconnecting'

const ConnectionContext = createContext<ConnectionStatus>('connecting')

export const useConnection = () => useContext(ConnectionContext)

// Which cached queries to refetch for each server event.
const EVENT_KEYS: Record<string, string[]> = {
  'tasks.changed': ['tasks'],
  'devices.changed': ['devices'],
  'home.changed': ['rooms', 'devices', 'scenes'],
  'weather.changed': ['weather'],
  'calendar.changed': ['calendar'],
  'settings.changed': ['settings', 'weather', 'calendar', 'tasks'],
}

const BACKOFF_MS = [1000, 2000, 5000, 10000, 30000]
/** The server pings every ~20 s; silence for this long (two missed pings) means the connection is dead. */
const SILENCE_LIMIT_MS = 50_000

/**
 * Keeps one Server-Sent Events connection open while the user is signed in. After every (re)connect
 * all data is refetched, so events missed while offline do not matter. The UI keeps showing the last
 * data while status is "reconnecting".
 */
export function ConnectionProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const [status, setStatus] = useState<ConnectionStatus>('connecting')

  useEffect(() => {
    let source: EventSource | null = null
    let retryTimer: number | undefined
    let watchdog: number | undefined
    let attempt = 0
    let stopped = false

    const clearTimers = () => {
      window.clearTimeout(retryTimer)
      window.clearTimeout(watchdog)
    }

    const armWatchdog = () => {
      window.clearTimeout(watchdog)
      watchdog = window.setTimeout(() => lost(), SILENCE_LIMIT_MS)
    }

    const lost = () => {
      if (stopped) return
      source?.close()
      source = null
      window.clearTimeout(watchdog)
      setStatus('reconnecting')
      const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)]
      attempt += 1
      retryTimer = window.setTimeout(connect, delay)
      // A session that ended (revoked, expired) shows up as 401 on the next fetch.
      void client.invalidateQueries({ queryKey: ['session'] })
    }

    const connect = () => {
      if (stopped) return
      window.clearTimeout(retryTimer)
      source = new EventSource('/api/stream')
      source.onopen = () => {
        attempt = 0
        setStatus('live')
        armWatchdog()
        void client.invalidateQueries() // full refetch after any (re)connect
      }
      source.onerror = () => lost()
      source.addEventListener('ping', armWatchdog)
      for (const [event, keys] of Object.entries(EVENT_KEYS)) {
        source.addEventListener(event, () => {
          armWatchdog()
          for (const key of keys) void client.invalidateQueries({ queryKey: [key] })
        })
      }
    }

    // iOS suspends background tabs; come back quickly when the app is shown again.
    const onVisible = () => {
      if (document.visibilityState === 'visible' && (source === null || source.readyState !== EventSource.OPEN)) {
        attempt = 0
        source?.close()
        connect()
      }
    }
    const onOnline = () => onVisible()

    connect()
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('online', onOnline)
    return () => {
      stopped = true
      clearTimers()
      source?.close()
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('online', onOnline)
    }
  }, [client])

  const value = useMemo(() => status, [status])
  return <ConnectionContext.Provider value={value}>{children}</ConnectionContext.Provider>
}
