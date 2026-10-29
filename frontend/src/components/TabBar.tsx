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
          <NavLink to="/assistant" className="tab">
            <svg {...icon}>
              <path d="M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-7l-4.5 3.5V17H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z" />
              <path d="M8 10h8" />
              <path d="M8 13h5" />
            </svg>
            Assistant
          </NavLink>
        </div>
      </nav>
    </>
  )
}
