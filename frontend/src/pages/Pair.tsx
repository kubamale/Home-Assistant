import { QRCodeSVG } from 'qrcode.react'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { api } from '../api'
import { useSession } from '../hooks/data'
import type { PairStart } from '../types'

/**
 * Shown on an unpaired door screen. Displays a short code and a QR link; the user approves it from
 * the signed-in phone, after which the screen receives its own limited token and opens the wall view.
 */
export function Pair() {
  const session = useSession()
  const client = useQueryClient()
  const [pairing, setPairing] = useState<PairStart | null>(null)
  const [failed, setFailed] = useState(false)
  const [round, setRound] = useState(0)

  const signedIn = session.data != null

  useEffect(() => {
    if (signedIn) return
    let cancelled = false
    let timer: number | undefined
    setFailed(false)

    const run = async () => {
      let started: PairStart
      try {
        started = await api.pairStart()
      } catch {
        if (!cancelled) {
          setFailed(true)
          timer = window.setTimeout(() => setRound((r) => r + 1), 15_000)
        }
        return
      }
      if (cancelled) return
      setPairing(started)
      const poll = async () => {
        if (cancelled) return
        try {
          const result = await api.pairPoll(started.deviceCode)
          if (cancelled) return
          if (result === 'approved') {
            await client.invalidateQueries({ queryKey: ['session'] })
            return
          }
          if (result === 'expired') {
            setRound((r) => r + 1)
            return
          }
        } catch {
          // keep polling; the network may be briefly down
        }
        timer = window.setTimeout(poll, started.pollIntervalSeconds * 1000)
      }
      timer = window.setTimeout(poll, started.pollIntervalSeconds * 1000)
    }
    void run()
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [round, signedIn, client])

  if (signedIn) return <Navigate to="/wall" replace />

  const approveUrl = pairing ? `${window.location.origin}${pairing.approvePath}` : ''
  return (
    <main className="pair">
      <h1>Pair this screen</h1>
      {failed && <p className="error">Can't reach the server. Retrying…</p>}
      {pairing && (
        <div className="pair-body">
          <div className="pair-qr">
            <QRCodeSVG value={approveUrl} size={220} bgColor="#ffffff" fgColor="#0f1419" marginSize={2} />
            <p>Scan with your phone</p>
          </div>
          <div className="pair-code">
            <p>or open the app, then Settings → Add display, and type</p>
            <div className="code">{pairing.userCode}</div>
          </div>
        </div>
      )}
      {!pairing && !failed && <p className="muted">Getting a code…</p>}
    </main>
  )
}
