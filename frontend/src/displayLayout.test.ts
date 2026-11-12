import { describe, expect, it } from 'vitest'
import {
  addWidget,
  canMove,
  countOf,
  DEFAULT_LAYOUT,
  isOnScreen,
  missingTypes,
  moveWidget,
  removeWidget,
  sameLayout,
  setWidgetOption,
  widgetsOf,
} from './displayLayout'
import type { DisplayLayout } from './types'

const types = (layout: DisplayLayout) => layout.columns.map((c) => c.widgets.map((w) => w.type))

describe('display layout', () => {
  it('starts with every card once', () => {
    expect(widgetsOf(DEFAULT_LAYOUT)).toHaveLength(5)
    expect(missingTypes(DEFAULT_LAYOUT)).toEqual([])
  })

  it('removes a card and offers it again, but never the last one', () => {
    const without = removeWidget(DEFAULT_LAYOUT, 'quickAccess')
    expect(isOnScreen(without, 'quickAccess')).toBe(false)
    expect(missingTypes(without)).toEqual(['quickAccess'])

    let layout = DEFAULT_LAYOUT
    for (const type of ['weather', 'calendar', 'tasks', 'quickAccess'] as const) layout = removeWidget(layout, type)
    expect(types(layout)).toEqual([['clock']])
    expect(removeWidget(layout, 'clock')).toBe(layout)
  })

  it('drops a column that becomes empty', () => {
    expect(types(removeWidget(DEFAULT_LAYOUT, 'calendar'))).toEqual([['clock', 'weather'], ['tasks', 'quickAccess']])
  })

  it('adds a card with its setting filled in, in a column or a new column', () => {
    const base = removeWidget(removeWidget(DEFAULT_LAYOUT, 'tasks'), 'calendar') // two columns left
    const inFirst = addWidget(base, 'tasks', 0)
    expect(types(inFirst)[0]).toEqual(['clock', 'weather', 'tasks'])
    expect(widgetsOf(inFirst).find((w) => w.type === 'tasks')?.count).toBe(6)

    const inNew = addWidget(base, 'calendar', 5)
    expect(types(inNew)).toEqual([['clock', 'weather'], ['quickAccess'], ['calendar']])
  })

  it('does not add a card twice, a fourth column or a fifth card to a column', () => {
    expect(addWidget(DEFAULT_LAYOUT, 'clock', 0)).toBe(DEFAULT_LAYOUT)

    const missing = removeWidget(DEFAULT_LAYOUT, 'calendar')
    expect(addWidget(missing, 'calendar', 9).columns).toHaveLength(3) // there is room for it in a new column
    expect(types(addWidget(missing, 'calendar', 9))).toEqual([['clock', 'weather'], ['tasks', 'quickAccess'], ['calendar']])
    const threeColumns: DisplayLayout = { columns: [{ widgets: [{ type: 'clock' }] }, { widgets: [{ type: 'weather' }] }, { widgets: [{ type: 'tasks' }] }], voice: true, motion: true }
    expect(addWidget(threeColumns, 'calendar', 3)).toBe(threeColumns) // a fourth column is refused

    const fullColumn: DisplayLayout = {
      columns: [{ widgets: [{ type: 'clock' }, { type: 'weather' }, { type: 'calendar' }, { type: 'tasks' }] }],
      voice: true,
      motion: true,
    }
    expect(addWidget(fullColumn, 'quickAccess', 0)).toBe(fullColumn) // a fifth card in one column is refused
  })

  it('moves a card up and down inside its column', () => {
    expect(types(moveWidget(DEFAULT_LAYOUT, 'weather', 'up'))[0]).toEqual(['weather', 'clock'])
    expect(types(moveWidget(DEFAULT_LAYOUT, 'tasks', 'down'))[2]).toEqual(['quickAccess', 'tasks'])
    expect(canMove(DEFAULT_LAYOUT, 'clock', 'up')).toBe(false)
    expect(canMove(DEFAULT_LAYOUT, 'quickAccess', 'down')).toBe(false)
    expect(moveWidget(DEFAULT_LAYOUT, 'clock', 'up')).toBe(DEFAULT_LAYOUT)
  })

  it('moves a card to the next column and removes the column it empties', () => {
    expect(types(moveWidget(DEFAULT_LAYOUT, 'weather', 'right'))).toEqual([['clock'], ['calendar', 'weather'], ['tasks', 'quickAccess']])
    // it lands at the height it had, and the column it left (now empty) is gone
    expect(types(moveWidget(DEFAULT_LAYOUT, 'calendar', 'left'))).toEqual([['calendar', 'clock', 'weather'], ['tasks', 'quickAccess']])
  })

  it('makes a new column at the edge, but only if the card leaves company and there is room', () => {
    const two: DisplayLayout = { columns: [{ widgets: [{ type: 'clock' }, { type: 'weather' }] }, { widgets: [{ type: 'tasks' }] }], voice: true, motion: true }
    expect(types(moveWidget(two, 'clock', 'left'))).toEqual([['clock'], ['weather'], ['tasks']])
    expect(canMove(two, 'tasks', 'right')).toBe(false) // alone in its column: a new one would only swap places
    expect(canMove(DEFAULT_LAYOUT, 'weather', 'left')).toBe(false) // already three columns
  })

  it('keeps a setting within its range', () => {
    expect(countOf(widgetsOf(setWidgetOption(DEFAULT_LAYOUT, 'tasks', { count: 100 })).find((w) => w.type === 'tasks')!)).toBe(8)
    expect(countOf(widgetsOf(setWidgetOption(DEFAULT_LAYOUT, 'tasks', { count: 0 })).find((w) => w.type === 'tasks')!)).toBe(2)
    const clock = widgetsOf(setWidgetOption(DEFAULT_LAYOUT, 'clock', { hour12: true })).find((w) => w.type === 'clock')
    expect(clock?.hour12).toBe(true)
    // a setting the card does not have is ignored
    expect(sameLayout(setWidgetOption(DEFAULT_LAYOUT, 'quickAccess', { count: 3 }), DEFAULT_LAYOUT)).toBe(true)
  })
})
