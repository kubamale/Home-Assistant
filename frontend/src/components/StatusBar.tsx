import { useConnection } from '../connection'

/** Shown whenever the live connection to the server is down; the cards keep their last data. */
export function ConnectionNotice() {
  const status = useConnection()
  if (status !== 'reconnecting') return null
  return (
    <div className="connection-notice" role="status">
      Reconnecting… showing the last update
    </div>
  )
}
