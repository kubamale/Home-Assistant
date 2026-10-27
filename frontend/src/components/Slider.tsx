import { useState, type CSSProperties } from 'react'

/**
 * A labelled range slider that reports its value when it is released, not while it is dragged:
 * bulbs accept only about 60 commands a minute. The dragged value stays on screen until
 * {@code onCommit} has finished, so the thumb does not jump back before the new state arrives.
 */
export function Slider({
  label,
  value,
  min,
  max,
  unit = '',
  disabled,
  highlighted,
  hideName,
  trackStyle,
  onCommit,
}: {
  label: string
  value: number
  min: number
  max: number
  unit?: string
  disabled?: boolean
  /** Marks the setting the light is currently using, for example the white slider while in white mode. */
  highlighted?: boolean
  /** Leave the name out when something next to the slider already says what it is. */
  hideName?: boolean
  trackStyle?: CSSProperties
  onCommit: (value: number) => Promise<unknown> | void
}) {
  const [draft, setDraft] = useState<number | null>(null)
  const shown = draft ?? value

  const commit = () => {
    if (draft === null) return
    if (draft === value) {
      setDraft(null)
      return
    }
    Promise.resolve(onCommit(draft))
      .catch(() => undefined) // the caller shows the error
      .finally(() => setDraft(null))
  }

  return (
    <label className={highlighted ? 'slider active' : 'slider'}>
      <span className="slider-label">
        <span>{hideName ? '' : label}</span>
        <span className="slider-value">
          {shown}
          {unit}
        </span>
      </span>
      <input
        type="range"
        min={min}
        max={max}
        step={1}
        value={shown}
        aria-label={label}
        disabled={disabled}
        style={{ ...trackStyle, '--pf': String((shown - min) / Math.max(1, max - min)) } as CSSProperties}
        onChange={(e) => setDraft(Number(e.target.value))}
        onPointerUp={commit}
        onKeyUp={commit}
      />
    </label>
  )
}
