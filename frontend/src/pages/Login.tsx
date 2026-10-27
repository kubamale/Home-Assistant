import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useSession } from '../hooks/data'

export function Login() {
  const session = useSession()
  const client = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const from = (location.state as { from?: string } | null)?.from ?? '/'

  if (session.data?.role === 'user') return <Navigate to={from} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.login(password)
      await client.invalidateQueries({ queryKey: ['session'] })
      navigate(from, { replace: true })
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 429
          ? 'Too many attempts. Try again in a few minutes.'
          : err instanceof ApiError && err.status === 401
            ? 'Wrong password.'
            : 'Could not reach the server.',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="narrow">
      <h1>Home Display</h1>
      <form className="card form" onSubmit={submit}>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            autoFocus
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p className="error">{error}</p>}
        <button className="button primary" type="submit" disabled={busy || !password}>
          Sign in
        </button>
      </form>
    </main>
  )
}
