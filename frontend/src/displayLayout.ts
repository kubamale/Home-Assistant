import type { DisplayLayout, Widget, WidgetType } from './types'

/** The limits the server enforces too, so the editor never offers what would be refused. */
export const MAX_COLUMNS = 3
export const MAX_PER_COLUMN = 4

export const WIDGET_TYPES: WidgetType[] = ['clock', 'weather', 'calendar', 'tasks', 'quickAccess']

export const WIDGET_LABELS: Record<WidgetType, string> = {
  clock: 'Clock and date',
  weather: 'Weather',
  calendar: 'Calendar',
  tasks: 'Tasks',
  quickAccess: 'Favourites',
}

/** The one number a card has, if any: how many hours, events or tasks it shows. */
export const COUNTS: Partial<Record<WidgetType, { label: string; min: number; max: number; fallback: number }>> = {
  weather: { label: 'Hours of forecast', min: 3, max: 8, fallback: 5 },
  calendar: { label: 'Events', min: 2, max: 8, fallback: 5 },
  tasks: { label: 'Tasks', min: 2, max: 8, fallback: 6 },
}

/** What a display looks like until its owner changes it (and while the real one is still loading). */
export const DEFAULT_LAYOUT: DisplayLayout = {
  columns: [
    { widgets: [{ type: 'clock', hour12: false }, { type: 'weather', count: 5 }] },
    { widgets: [{ type: 'calendar', count: 5 }] },
    { widgets: [{ type: 'tasks', count: 6 }, { type: 'quickAccess' }] },
  ],
  voice: true,
  motion: true,
}

export const widgetsOf = (layout: DisplayLayout): Widget[] => layout.columns.flatMap((c) => c.widgets)

export const isOnScreen = (layout: DisplayLayout, type: WidgetType): boolean => widgetsOf(layout).some((w) => w.type === type)

/** The cards that can still be added. */
export const missingTypes = (layout: DisplayLayout): WidgetType[] => WIDGET_TYPES.filter((t) => !isOnScreen(layout, t))

/** A card with its setting filled in. */
export function newWidget(type: WidgetType): Widget {
  if (type === 'clock') return { type, hour12: false }
  const count = COUNTS[type]
  return count ? { type, count: count.fallback } : { type }
}

const withoutEmptyColumns = (columns: DisplayLayout['columns']) => columns.filter((c) => c.widgets.length > 0)

/** Adds a card at the end of a column; a column number past the last one makes a new column when there is room. */
export function addWidget(layout: DisplayLayout, type: WidgetType, column: number): DisplayLayout {
  if (isOnScreen(layout, type)) return layout
  if (column >= layout.columns.length) {
    if (layout.columns.length >= MAX_COLUMNS) return layout
    return { ...layout, columns: [...layout.columns, { widgets: [newWidget(type)] }] }
  }
  if (layout.columns[column].widgets.length >= MAX_PER_COLUMN) return layout
  return {
    ...layout,
    columns: layout.columns.map((c, i) => (i === column ? { widgets: [...c.widgets, newWidget(type)] } : c)),
  }
}

/** Takes a card off the screen. The last card cannot be removed: a blank screen is not a layout. */
export function removeWidget(layout: DisplayLayout, type: WidgetType): DisplayLayout {
  if (widgetsOf(layout).length <= 1) return layout
  return {
    ...layout,
    columns: withoutEmptyColumns(layout.columns.map((c) => ({ widgets: c.widgets.filter((w) => w.type !== type) }))),
  }
}

export type Direction = 'up' | 'down' | 'left' | 'right'

/** Where a card is: its column and its place in it. */
function find(layout: DisplayLayout, type: WidgetType): { column: number; index: number } | null {
  for (let column = 0; column < layout.columns.length; column++) {
    const index = layout.columns[column].widgets.findIndex((w) => w.type === type)
    if (index >= 0) return { column, index }
  }
  return null
}

/** Whether the card can move that way (so the editor can disable the button instead of doing nothing). */
export function canMove(layout: DisplayLayout, type: WidgetType, direction: Direction): boolean {
  const at = find(layout, type)
  if (!at) return false
  const { column, index } = at
  const size = layout.columns[column].widgets.length
  switch (direction) {
    case 'up':
      return index > 0
    case 'down':
      return index < size - 1
    case 'left':
      // into the column on the left, or out into a new first column (only if something stays behind)
      return column > 0 ? layout.columns[column - 1].widgets.length < MAX_PER_COLUMN : size > 1 && layout.columns.length < MAX_COLUMNS
    case 'right':
      return column < layout.columns.length - 1
        ? layout.columns[column + 1].widgets.length < MAX_PER_COLUMN
        : size > 1 && layout.columns.length < MAX_COLUMNS
  }
}

/** Moves a card up or down in its column, or into the next column over (at the same height, or the end). */
export function moveWidget(layout: DisplayLayout, type: WidgetType, direction: Direction): DisplayLayout {
  if (!canMove(layout, type, direction)) return layout
  const at = find(layout, type)!
  const { column, index } = at
  const columns = layout.columns.map((c) => ({ widgets: [...c.widgets] }))
  const [widget] = columns[column].widgets.splice(index, 1)

  if (direction === 'up' || direction === 'down') {
    columns[column].widgets.splice(index + (direction === 'up' ? -1 : 1), 0, widget)
    return { ...layout, columns }
  }
  const target = column + (direction === 'left' ? -1 : 1)
  if (target < 0) {
    columns.unshift({ widgets: [widget] })
  } else if (target >= columns.length) {
    columns.push({ widgets: [widget] })
  } else {
    columns[target].widgets.splice(Math.min(index, columns[target].widgets.length), 0, widget)
  }
  return { ...layout, columns: withoutEmptyColumns(columns) }
}

/** Changes the one setting of a card, keeping it inside the allowed range. */
export function setWidgetOption(layout: DisplayLayout, type: WidgetType, patch: { count?: number; hour12?: boolean }): DisplayLayout {
  const range = COUNTS[type]
  return {
    ...layout,
    columns: layout.columns.map((c) => ({
      widgets: c.widgets.map((w) => {
        if (w.type !== type) return w
        if (patch.hour12 !== undefined && type === 'clock') return { ...w, hour12: patch.hour12 }
        if (patch.count !== undefined && range) return { ...w, count: Math.min(range.max, Math.max(range.min, Math.round(patch.count))) }
        return w
      }),
    })),
  }
}

export const sameLayout = (a: DisplayLayout, b: DisplayLayout): boolean => JSON.stringify(a) === JSON.stringify(b)

/** The number a card shows, with the default when the server did not say. */
export const countOf = (widget: Widget): number => widget.count ?? COUNTS[widget.type]?.fallback ?? 0
