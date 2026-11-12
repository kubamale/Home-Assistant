import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { api } from '../../api'
import { Chevron } from '../../components/Controls'
import { useUsers } from '../../hooks/data'
import { Field, Group, SettingsPage } from './SettingsLayout'

type Mode = 'menu' | 'rename' | 'password' | 'remove'

/** The admin adds people and looks after their accounts. Tapping a person opens what can be done with them. */
export function PeoplePage() {
  const client = useQueryClient()
  const { data: people } = useUsers()
  const [open, setOpen] = useState<number | null>(null)
  const [mode, setMode] = useState<Mode>('menu')
  const [text, setText] = useState('')
  const [newName, setNewName] = useState('')
  const [newPassword, setNewPassword] = useState('')

  const changed = () => Promise.all([client.invalidateQueries({ queryKey: ['users'] }), client.invalidateQueries({ queryKey: ['credentials'] })])
  const close = () => {
    setOpen(null)
    setMode('menu')
    setText('')
  }
  const create = useMutation({
    mutationFn: () => api.createUser(newName.trim(), newPassword),
    onSuccess: async () => {
      setNewName('')
      setNewPassword('')
      await changed()
    },
  })
  const rename = useMutation({ mutationFn: (v: { id: number; name: string }) => api.renameUser(v.id, v.name), onSuccess: async () => { close(); await changed() } })
  const password = useMutation({ mutationFn: (v: { id: number; password: string }) => api.setUserPassword(v.id, v.password), onSuccess: close })
  const remove = useMutation({ mutationFn: (id: number) => api.deleteUser(id), onSuccess: async () => { close(); await changed() } })

  const error = rename.error ?? password.error ?? remove.error
  const choose = (id: number) => {
    setOpen(open === id ? null : id)
    setMode('menu')
    setText('')
  }
  const submitNew = (e: FormEvent) => {
    e.preventDefault()
    create.mutate()
  }

  return (
    <SettingsPage title="People">
      <Group footnote="Everyone has their own tasks, calendar, pinned lights and scenes, and arranges their own display from their own phone. You look after the home itself.">
        {(people ?? []).map((u) => (
          <div key={u.id}>
            <button type="button" className="row" aria-expanded={open === u.id} onClick={() => choose(u.id)}>
              <span className="row-main">
                <span className="row-title">{u.name}</span>
                <span className="row-sub">{u.admin ? 'Admin' : 'Member'}</span>
              </span>
              <Chevron />
            </button>
            {open === u.id && (
              <div className="person-actions">
                {mode === 'menu' && (
                  <>
                    <button className="button compact" onClick={() => { setMode('rename'); setText(u.name) }}>Rename</button>
                    {!u.admin && <button className="button compact" onClick={() => { setMode('password'); setText('') }}>Set password</button>}
                    {!u.admin && <button className="button compact danger" onClick={() => setMode('remove')}>Remove</button>}
                  </>
                )}
                {mode === 'rename' && (
                  <form className="inline-edit" onSubmit={(e) => { e.preventDefault(); rename.mutate({ id: u.id, name: text.trim() }) }}>
                    <input type="text" aria-label={`New name for ${u.name}`} value={text} maxLength={40} autoFocus onChange={(e) => setText(e.target.value)} />
                    <button type="submit" className="button primary compact" disabled={rename.isPending || text.trim() === ''}>Save</button>
                    <button type="button" className="button compact" onClick={close}>Cancel</button>
                  </form>
                )}
                {mode === 'password' && (
                  <form className="inline-edit" onSubmit={(e) => { e.preventDefault(); password.mutate({ id: u.id, password: text }) }}>
                    <input type="password" autoComplete="new-password" aria-label={`New password for ${u.name}`} placeholder="8 characters or more" value={text} autoFocus onChange={(e) => setText(e.target.value)} />
                    <button type="submit" className="button primary compact" disabled={password.isPending || text.length < 8}>Set</button>
                    <button type="button" className="button compact" onClick={close}>Cancel</button>
                  </form>
                )}
                {mode === 'remove' && (
                  <div className="confirm-block" role="alert">
                    <p>Remove {u.name}? Their tasks, calendar, scenes and pins go, and their phones and displays are signed out.</p>
                    <div className="inline-buttons">
                      <button className="button compact" onClick={close}>Keep</button>
                      <button className="button danger compact" disabled={remove.isPending} onClick={() => remove.mutate(u.id)}>Remove {u.name}</button>
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
        ))}
      </Group>
      {error && <p className="error">{error.message}</p>}

      <form onSubmit={submitNew} className="stack">
        <Group title="Add a person">
          <Field label="Name">
            <input type="text" value={newName} maxLength={40} autoComplete="off" onChange={(e) => setNewName(e.target.value)} />
          </Field>
          <Field label="Password" hint="At least 8 characters. They can change it later.">
            <input type="password" value={newPassword} autoComplete="new-password" onChange={(e) => setNewPassword(e.target.value)} />
          </Field>
          <div className="group-actions">
            <button className="button primary" type="submit" disabled={create.isPending || newName.trim() === '' || newPassword.length < 8}>
              Add
            </button>
          </div>
        </Group>
        {create.error && <p className="error">{create.error.message}</p>}
      </form>
    </SettingsPage>
  )
}
