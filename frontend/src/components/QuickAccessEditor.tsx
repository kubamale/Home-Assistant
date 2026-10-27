import { useSetQuickAccess } from '../hooks/data'
import { channelTitle, moveItem, QUICK_ACCESS_SLOTS, toPinItems, type PinnedItem } from '../homeModel'

const titleOf = (item: PinnedItem) => (item.kind === 'scene' ? item.scene.name : channelTitle(item.device, item.channel))
const keyOf = (item: PinnedItem) => (item.kind === 'scene' ? `scene-${item.scene.id}` : `${item.device.id}/${item.channel.id}`)

/** Edit mode only: reorder or unpin the quick access lights and scenes. */
export function QuickAccessEditor({ pinned }: { pinned: PinnedItem[] }) {
  const save = useSetQuickAccess()
  const items = toPinItems(pinned)

  return (
    <section className="card" aria-label="Quick access order">
      <h2>
        <span>Quick access</span>
        <span className="muted">
          {pinned.length} of {QUICK_ACCESS_SLOTS}
        </span>
      </h2>
      {pinned.length === 0 ? (
        <p className="muted">Pin a light or a scene to show it on the dashboard.</p>
      ) : (
        <ol className="device-list">
          {pinned.map((item, i) => {
            const name = titleOf(item)
            return (
              <li key={keyOf(item)} className="device">
                <span className="device-name">
                  {name}
                  {item.kind === 'scene' && <small> · scene</small>}
                </span>
                <span className="room-tools">
                  <button className="icon-button small" aria-label={`Move ${name} up`} disabled={i === 0 || save.isPending} onClick={() => save.mutate(moveItem(items, i, -1))}>
                    ↑
                  </button>
                  <button className="icon-button small" aria-label={`Move ${name} down`} disabled={i === pinned.length - 1 || save.isPending} onClick={() => save.mutate(moveItem(items, i, 1))}>
                    ↓
                  </button>
                  <button className="button compact" disabled={save.isPending} onClick={() => save.mutate(items.filter((_, index) => index !== i))}>
                    Unpin
                  </button>
                </span>
              </li>
            )
          })}
        </ol>
      )}
      {save.error && <p className="error small">{save.error.message}</p>}
    </section>
  )
}
