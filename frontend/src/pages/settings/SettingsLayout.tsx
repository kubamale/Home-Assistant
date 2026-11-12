import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Chevron } from '../../components/Controls'

/** One page of Settings: a back link to the menu, a large title, and the groups below it. */
export function SettingsPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <main className="phone settings">
      <header className="settings-head">
        <Link className="back-link" to="/settings">
          <svg width="10" height="17" viewBox="0 0 10 17" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M8.5 1.5 2 8.5l6.5 7" />
          </svg>
          Settings
        </Link>
        <h1>{title}</h1>
      </header>
      {children}
    </main>
  )
}

/** A titled block of rows on a rounded surface, with an optional explanation underneath. */
export function Group({ title, footnote, children, className }: { title?: string; footnote?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className="group-wrap">
      {title && <h2 className="group-title">{title}</h2>}
      <div className={className ? `group ${className}` : 'group'}>{children}</div>
      {footnote && <p className="footnote">{footnote}</p>}
    </section>
  )
}

/** A tappable row that opens another page. */
export function NavRow({ to, title, subtitle, value }: { to: string; title: string; subtitle?: string; value?: string }) {
  return (
    <Link className="row" to={to}>
      <span className="row-main">
        <span className="row-title">{title}</span>
        {subtitle && <span className="row-sub">{subtitle}</span>}
      </span>
      {value && <span className="row-value">{value}</span>}
      <Chevron />
    </Link>
  )
}

/** A labelled field inside a Group: the label is readable text above a full-width input. */
export function Field({ label, hint, children }: { label: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <label className="field-block">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  )
}
