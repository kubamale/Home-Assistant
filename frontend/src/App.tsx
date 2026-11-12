import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { PhoneTabs } from './components/TabBar'
import { SkyBackground } from './components/SkyBackground'
import { ConnectionProvider } from './connection'
import { useSession } from './hooks/data'
import { Approve } from './pages/Approve'
import { Assistant } from './pages/Assistant'
import { Dashboard } from './pages/Dashboard'
import { Login } from './pages/Login'
import { Pair } from './pages/Pair'
import { Settings } from './pages/Settings'
import { AccountPage } from './pages/settings/AccountPage'
import { AppearancePage } from './pages/settings/AppearancePage'
import { AgentPage } from './pages/settings/AgentPage'
import { CalendarPage } from './pages/settings/CalendarPage'
import { DisplayPage } from './pages/settings/DisplayPage'
import { LocationPage } from './pages/settings/LocationPage'
import { PeoplePage } from './pages/settings/PeoplePage'
import { PhonesPage } from './pages/settings/PhonesPage'
import { VoicePage } from './pages/settings/VoicePage'
import { SmartHome } from './pages/SmartHome'
import { Wall } from './pages/Wall'

/** Gate for pages that need a signed-in client. Unauthenticated visitors go to {@code redirectTo}. */
function RequireAuth({ role, redirectTo }: { role?: 'user'; redirectTo: string }) {
  const session = useSession()
  const location = useLocation()

  if (session.isPending) return <p className="center muted">Loading…</p>
  if (session.isError && !session.data) {
    return <p className="center muted">Can't reach the server. Retrying…</p>
  }
  if (!session.data) return <Navigate to={redirectTo} replace state={{ from: location.pathname + location.search }} />
  if (role === 'user' && session.data.role !== 'user') return <Navigate to="/wall" replace />
  return (
    <ConnectionProvider>
      <Outlet />
    </ConnectionProvider>
  )
}

/** Pages that manage the home itself. Anyone else is sent back to the menu. */
function RequireAdmin() {
  const session = useSession()
  if (session.isPending) return <p className="center muted">Loading…</p>
  if (!session.data?.admin) return <Navigate to="/settings" replace />
  return <Outlet />
}

export function App() {
  return (
    <>
      <SkyBackground />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/pair" element={<Pair />} />
        <Route element={<RequireAuth redirectTo="/pair" />}>
        <Route path="/wall" element={<Wall />} />
        </Route>
        <Route element={<RequireAuth role="user" redirectTo="/login" />}>
        <Route element={<PhoneTabs />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/home" element={<SmartHome />} />
          <Route path="/assistant" element={<Assistant />} />
        </Route>
        <Route path="/settings" element={<Settings />} />
        <Route path="/settings/account" element={<AccountPage />} />
        <Route path="/settings/calendar" element={<CalendarPage />} />
        <Route path="/settings/appearance" element={<AppearancePage />} />
        <Route path="/settings/phones" element={<PhonesPage />} />
        <Route path="/settings/display/:id" element={<DisplayPage />} />
        <Route element={<RequireAdmin />}>
          <Route path="/settings/location" element={<LocationPage />} />
          <Route path="/settings/voice" element={<VoicePage />} />
          <Route path="/settings/people" element={<PeoplePage />} />
          <Route path="/settings/agent" element={<AgentPage />} />
        </Route>
        <Route path="/approve" element={<Approve />} />
        </Route>
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </>
  )
}
