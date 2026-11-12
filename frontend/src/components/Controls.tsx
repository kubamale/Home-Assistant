import type { ReactNode } from 'react'

/** An on/off switch with the same look as the device switches. */
export function Toggle({ on, onChange, label, disabled }: { on: boolean; onChange: (on: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <button type="button" role="switch" aria-checked={on} aria-label={label} className={on ? 'switch on' : 'switch'} disabled={disabled} onClick={() => onChange(!on)}>
      <span className="knob" />
    </button>
  )
}

/** A number with minus and plus buttons: much easier on a phone than a drop-down. */
export function Stepper({ value, min, max, onChange, label }: { value: number; min: number; max: number; onChange: (value: number) => void; label: string }) {
  return (
    <div className="stepper" role="group" aria-label={label}>
      <button type="button" aria-label={`Fewer ${label}`} disabled={value <= min} onClick={() => onChange(value - 1)}>
        −
      </button>
      <output aria-live="polite">{value}</output>
      <button type="button" aria-label={`More ${label}`} disabled={value >= max} onClick={() => onChange(value + 1)}>
        +
      </button>
    </div>
  )
}

export function Chevron() {
  return (
    <svg className="chev" width="8" height="14" viewBox="0 0 8 14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M1 1l6 6-6 6" />
    </svg>
  )
}

/** A row with a title on the left and its current value on the right, shown inside a Group. */
export function ValueRow({ title, value, children }: { title: string; value?: ReactNode; children?: ReactNode }) {
  return (
    <div className="row static">
      <span className="row-title">{title}</span>
      {children ?? <span className="row-value">{value}</span>}
    </div>
  )
}
