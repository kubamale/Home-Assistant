import { ago } from '../format'

/** Small note shown on a card whose data could not be refreshed recently. */
export function Stale({ fetchedAt, stale, error }: { fetchedAt: string | null; stale: boolean; error: string | null }) {
  if (!stale && !error) return null
  return (
    <p className="stale" role="status">
      {stale ? `Updated ${ago(fetchedAt)}` : 'Could not refresh'}
    </p>
  )
}
