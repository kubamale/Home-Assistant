import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { api } from '../api'
import {
  addWidget,
  canMove,
  COUNTS,
  countOf,
  MAX_PER_COLUMN,
  missingTypes,
  moveWidget,
  removeWidget,
  sameLayout,
  setWidgetOption,
  WIDGET_LABELS,
  widgetsOf,
  type Direction,
} from '../displayLayout'
import type { DisplayLayout, Widget, WidgetType } from '../types'
import { Stepper, Toggle } from './Controls'
import { Group } from '../pages/settings/SettingsLayout'

const MOVES: { direction: Direction; symbol: string; label: string }[] = [
  { direction: 'left', symbol: '←', label: 'Left' },
  { direction: 'up', symbol: '↑', label: 'Up' },
  { direction: 'down', symbol: '↓', label: 'Down' },
  { direction: 'right', symbol: '→', label: 'Right' },
]

/** What a card says about itself in the preview: its setting, so the picture tells you what the screen will show. */
function detail(widget: Widget): string {
  switch (widget.type) {
    case 'clock':
      return widget.hour12 ? '12-hour' : '24-hour'
    case 'weather':
      return `${countOf(widget)} hours`
    case 'calendar':
      return `${countOf(widget)} events`
    case 'tasks':
      return `${countOf(widget)} tasks`
    default:
      return 'pinned lights'
  }
}

/** The cards that stretch to fill their column, as on the real screen. */
const GROWS: WidgetType[] = ['calendar', 'tasks']

/**
 * Arranges one wall display on a small picture of the screen: tap a card to change it, move it or take it off. Changes are
 * only a draft until Save; the display redraws itself the moment they are saved.
 */
export function LayoutEditor({ credentialId }: { credentialId: string }) {
  const client = useQueryClient()
  const { data: saved, isPending, error } = useQuery({
    queryKey: ['layout', credentialId],
    queryFn: () => api.displayLayout(credentialId),
    staleTime: 0,
  })
  const [draft, setDraft] = useState<DisplayLayout | null>(null)
  const [selected, setSelected] = useState<WidgetType | null>(null)
  const layout = draft ?? saved

  const done = (next: DisplayLayout) => {
    client.setQueryData(['layout', credentialId], next)
    setDraft(null)
    setSelected(null)
  }
  const save = useMutation({ mutationFn: (l: DisplayLayout) => api.saveDisplayLayout(credentialId, l), onSuccess: done })
  const reset = useMutation({ mutationFn: () => api.resetDisplayLayout(credentialId), onSuccess: done })

  if (isPending) return <p className="muted">Loading…</p>
  if (error || !layout || !saved) return <p className="error">Could not load the layout.</p>

  const dirty = !sameLayout(layout, saved)
  const missing = missingTypes(layout)
  const widget = selected ? widgetsOf(layout).find((w) => w.type === selected) : undefined
  const onlyCard = widgetsOf(layout).length <= 1
  const change = (next: DisplayLayout) => setDraft(next)

  const add = (type: WidgetType) => {
    const room = layout.columns.findIndex((c) => c.widgets.length < MAX_PER_COLUMN)
    change(addWidget(layout, type, room >= 0 ? room : layout.columns.length)) // the first column with room, else a new one
    setSelected(type)
  }

  return (
    <>
      <Group title="Screen" footnote="This is the wall screen. Tap a card to change it.">
        <div className="preview" style={{ gridTemplateColumns: `repeat(${layout.columns.length}, minmax(0, 1fr))` }}>
          {layout.columns.map((column, ci) => (
            <div className="preview-col" key={ci}>
              {column.widgets.map((w) => (
                <button
                  key={w.type}
                  type="button"
                  className={`preview-card${GROWS.includes(w.type) ? ' grow' : ''}${selected === w.type ? ' selected' : ''}`}
                  aria-pressed={selected === w.type}
                  onClick={() => setSelected(selected === w.type ? null : w.type)}
                >
                  <span className="t">{WIDGET_LABELS[w.type]}</span>
                  <span className="s">{detail(w)}</span>
                </button>
              ))}
            </div>
          ))}
        </div>
      </Group>

      {widget ? (
        <Group title={WIDGET_LABELS[widget.type]}>
          {widget.type === 'clock' && (
            <div className="row static">
              <span className="row-title">12-hour clock</span>
              <Toggle on={widget.hour12 === true} label="12-hour clock" onChange={(on) => change(setWidgetOption(layout, 'clock', { hour12: on }))} />
            </div>
          )}
          {COUNTS[widget.type] && (
            <div className="row static">
              <span className="row-title">{COUNTS[widget.type]!.label}</span>
              <Stepper
                value={countOf(widget)}
                min={COUNTS[widget.type]!.min}
                max={COUNTS[widget.type]!.max}
                label={COUNTS[widget.type]!.label.toLowerCase()}
                onChange={(count) => change(setWidgetOption(layout, widget.type, { count }))}
              />
            </div>
          )}
          <div className="move-row" role="group" aria-label={`Move ${WIDGET_LABELS[widget.type]}`}>
            {MOVES.map((m) => (
              <button
                key={m.direction}
                type="button"
                className="move"
                disabled={!canMove(layout, widget.type, m.direction)}
                onClick={() => change(moveWidget(layout, widget.type, m.direction))}
              >
                <span aria-hidden="true">{m.symbol}</span>
                {m.label}
              </button>
            ))}
          </div>
          <button type="button" className="row danger-row" disabled={onlyCard} onClick={() => { change(removeWidget(layout, widget.type)); setSelected(null) }}>
            <span className="row-title">{onlyCard ? 'The last card cannot be removed' : `Remove ${WIDGET_LABELS[widget.type]} from the screen`}</span>
          </button>
        </Group>
      ) : (
        missing.length === 0 && <p className="footnote">All five cards are on the screen. Tap one to move or remove it.</p>
      )}

      {missing.length > 0 && (
        <Group title="Add a card">
          <div className="chips">
            {missing.map((type) => (
              <button key={type} type="button" className="chip" onClick={() => add(type)}>
                + {WIDGET_LABELS[type]}
              </button>
            ))}
          </div>
        </Group>
      )}

      <Group title="Background" footnote="Clouds drift, rain falls, snow settles, stars twinkle, softly behind the cards. Turn it off for a slow screen. It also stays still when the tablet's own “remove animations” setting is on.">
        <div className="row static">
          <span className="row-title">Move with the weather</span>
          <Toggle on={layout.motion} label="Move the background with the weather" onChange={(motion) => change({ ...layout, motion })} />
        </div>
      </Group>

      <Group title="Voice" footnote="The screen waits for “Hey Jarvis” and answers out loud. Needs a Gemini key on the server.">
        <div className="row static">
          <span className="row-title">Listen for “Hey Jarvis”</span>
          <Toggle on={layout.voice} label="Listen for the wake word" onChange={(voice) => change({ ...layout, voice })} />
        </div>
      </Group>

      <button type="button" className="text-button" disabled={reset.isPending} onClick={() => reset.mutate()}>
        Back to the standard layout
      </button>
      {(save.error ?? reset.error) && <p className="error">{(save.error ?? reset.error)?.message}</p>}

      {dirty && (
        <div className="savebar" role="region" aria-label="Unsaved changes">
          <span>Unsaved changes</span>
          <span className="savebar-actions">
            <button type="button" className="button" onClick={() => { setDraft(null); setSelected(null) }}>
              Undo
            </button>
            <button type="button" className="button primary" disabled={save.isPending} onClick={() => save.mutate(layout)}>
              {save.isPending ? 'Saving…' : 'Save'}
            </button>
          </span>
        </div>
      )}
    </>
  )
}
