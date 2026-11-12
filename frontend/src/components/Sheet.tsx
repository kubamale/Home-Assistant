import { useEffect, type ReactNode } from 'react'

/**
 * A panel over the page with a title and a Close button. Closes on the backdrop, on Escape and on Close. The footer, if
 * given, stays at the bottom while the body scrolls.
 */
export function Sheet({ title, subtitle, onClose, closeLabel = 'Close', footer, children }: {
  title: string
  subtitle?: ReactNode
  onClose: () => void
  closeLabel?: string
  footer?: ReactNode
  children: ReactNode
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" role="dialog" aria-modal="true" aria-label={title} onClick={(e) => e.stopPropagation()}>
        <header className="sheet-head">
          <div>
            <h2>{title}</h2>
            {subtitle && <p className="muted small">{subtitle}</p>}
          </div>
          <button type="button" className="button" onClick={onClose}>
            {closeLabel}
          </button>
        </header>
        <div className="sheet-body">{children}</div>
        {footer && <footer className="sheet-foot">{footer}</footer>}
      </div>
    </div>
  )
}
