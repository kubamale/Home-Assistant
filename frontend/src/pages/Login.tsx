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
  const [name, setName] = useState(() => {
    try {
      return localStorage.getItem('login-name') ?? ''
    } catch {
      return ''
    }
  })
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
      await api.login(name, password)
      try {
        localStorage.setItem('login-name', name.trim())
      } catch {
        // private browsing: the name just is not remembered
      }
      await client.invalidateQueries({ queryKey: ['session'] })
      navigate(from, { replace: true })
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 429
          ? 'Too many attempts. Try again in a few minutes.'
          : err instanceof ApiError && err.status === 401
            ? 'Wrong name or password.'
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
          Name
          <input
            type="text"
            autoComplete="username"
            autoCapitalize="words"
            placeholder="Leave empty if you are the admin"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            autoFocus={name !== ''}
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
