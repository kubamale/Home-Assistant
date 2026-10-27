import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { PhoneTabs } from './components/TabBar'
import { ConnectionProvider } from './connection'
import { useSession } from './hooks/data'
import { Approve } from './pages/Approve'
import { Dashboard } from './pages/Dashboard'
import { Login } from './pages/Login'
import { Pair } from './pages/Pair'
import { Settings } from './pages/Settings'
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

export function App() {
  return (
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
        </Route>
        <Route path="/settings" element={<Settings />} />
        <Route path="/approve" element={<Approve />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}
