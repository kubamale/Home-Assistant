import { NavLink, Outlet } from 'react-router-dom'

const icon = { width: 24, height: 24, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const

/** The phone's two main screens with a bottom tab bar. */
export function PhoneTabs() {
  return (
    <>
      <Outlet />
      <nav className="tabbar" aria-label="Main">
        <div className="tabbar-inner">
          <NavLink to="/" end className="tab">
            <svg {...icon}>
              <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
              <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
              <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
              <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
            </svg>
            Dashboard
          </NavLink>
          <NavLink to="/home" className="tab">
            <svg {...icon}>
              <path d="M4 11.5 12 4l8 7.5" />
              <path d="M6 10v9.5h12V10" />
              <path d="M10 19.5v-5h4v5" />
            </svg>
            Home
          </NavLink>
        </div>
      </nav>
    </>
  )
}
